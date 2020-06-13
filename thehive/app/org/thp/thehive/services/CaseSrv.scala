package org.thp.thehive.services

import java.util.{List => JList, Set => JSet}

import akka.actor.ActorRef
import gremlin.scala._
import javax.inject.{Inject, Named, Singleton}
import org.apache.tinkerpop.gremlin.process.traversal.{Order, Path, P => JP}
import org.thp.scalligraph.auth.{AuthContext, Permission}
import org.thp.scalligraph.controllers.FPathElem
import org.thp.scalligraph.models._
import org.thp.scalligraph.query.PropertyUpdater
import org.thp.scalligraph.services._
import org.thp.scalligraph.steps.StepsOps._
import org.thp.scalligraph.steps.{Traversal, TraversalLike, VertexSteps}
import org.thp.scalligraph.{CreateError, EntitySteps, InternalError, RichJMap, RichOptionTry, RichSeq}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.models._
import play.api.libs.json.{JsNull, JsObject, Json}

import scala.collection.JavaConverters._
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

@Singleton
class CaseSrv @Inject() (
    tagSrv: TagSrv,
    customFieldSrv: CustomFieldSrv,
    userSrv: UserSrv,
    organisationSrv: OrganisationSrv,
    profileSrv: ProfileSrv,
    shareSrv: ShareSrv,
    taskSrv: TaskSrv,
    observableSrv: ObservableSrv,
    auditSrv: AuditSrv,
    resolutionStatusSrv: ResolutionStatusSrv,
    impactStatusSrv: ImpactStatusSrv,
    @Named("case-dedup-actor") caseDedupActor: ActorRef
)(implicit db: Database)
    extends VertexSrv[Case, CaseSteps] {

  val caseTagSrv              = new EdgeSrv[CaseTag, Case, Tag]
  val caseImpactStatusSrv     = new EdgeSrv[CaseImpactStatus, Case, ImpactStatus]
  val caseResolutionStatusSrv = new EdgeSrv[CaseResolutionStatus, Case, ResolutionStatus]
  val caseUserSrv             = new EdgeSrv[CaseUser, Case, User]
  val caseCustomFieldSrv      = new EdgeSrv[CaseCustomField, Case, CustomField]
  val caseCaseTemplateSrv     = new EdgeSrv[CaseCaseTemplate, Case, CaseTemplate]
  val mergedFromSrv           = new EdgeSrv[MergedFrom, Case, Case]

  override def createEntity(e: Case)(implicit graph: Graph, authContext: AuthContext): Try[Case with Entity] =
    super.createEntity(e).map { `case` =>
      caseDedupActor ! DedupActor.EntityAdded
      `case`
    }

  def create(
      `case`: Case,
      user: Option[User with Entity],
      organisation: Organisation with Entity,
      tags: Set[Tag with Entity],
      customFields: Map[String, Option[Any]],
      caseTemplate: Option[RichCaseTemplate],
      additionalTasks: Seq[(Task, Option[User with Entity])]
  )(implicit graph: Graph, authContext: AuthContext): Try[RichCase] =
    for {
      createdCase <- createEntity(if (`case`.number == 0) `case`.copy(number = nextCaseNumber) else `case`)
      assignee    <- user.fold(userSrv.current.getOrFail())(Success(_))
      _           <- caseUserSrv.create(CaseUser(), createdCase, assignee)
      _           <- shareSrv.shareCase(owner = true, createdCase, organisation, profileSrv.orgAdmin)
      _           <- caseTemplate.map(ct => caseCaseTemplateSrv.create(CaseCaseTemplate(), createdCase, ct.caseTemplate)).flip
      createdTasks <- caseTemplate.fold(additionalTasks)(_.tasks.map(t => t.task -> t.owner)).toTry {
        case (task, owner) => taskSrv.create(task, owner)
      }
      _ <- createdTasks.toTry(t => shareSrv.shareTask(t, createdCase, organisation))
      caseTemplateCustomFields = caseTemplate
        .fold[Seq[RichCustomField]](Nil)(_.customFields)
        .map(cf => cf.name -> cf.value)
      cfs <- (caseTemplateCustomFields.toMap ++ customFields).toTry { case (name, value) => createCustomField(createdCase, name, value) }
      caseTemplateTags = caseTemplate.fold[Seq[Tag with Entity]](Nil)(_.tags)
      allTags          = tags ++ caseTemplateTags
      _ <- allTags.toTry(t => caseTagSrv.create(CaseTag(), createdCase, t))
      richCase = RichCase(createdCase, allTags.toSeq, None, None, Some(assignee.login), cfs, authContext.permissions)
      _ <- auditSrv.`case`.create(createdCase, richCase.toJson)
    } yield richCase

  def nextCaseNumber(implicit graph: Graph): Int = initSteps.getLast.headOption().fold(0)(_.number) + 1

  override def update(
      steps: CaseSteps,
      propertyUpdaters: Seq[PropertyUpdater]
  )(implicit graph: Graph, authContext: AuthContext): Try[(CaseSteps, JsObject)] = {
    val closeCase = PropertyUpdater(FPathElem("closeCase"), "") { (vertex, _, _, _) =>
      get(vertex)
        .tasks
        .or(_.has("status", "Waiting"), _.has("status", "InProgress"))
        .toIterator
        .toTry {
          case task if task.status == TaskStatus.InProgress => taskSrv.updateStatus(task, null, TaskStatus.Completed)
          case task                                         => taskSrv.updateStatus(task, null, TaskStatus.Cancel)
        }
        .flatMap { _ =>
          vertex.property("endDate", System.currentTimeMillis())
          Success(Json.obj("endDate" -> System.currentTimeMillis()))
        }
    }

    val isCloseCase = propertyUpdaters.exists(p => p.path.matches(FPathElem("status")) && p.value == CaseStatus.Resolved)

    val newPropertyUpdaters = if (isCloseCase) closeCase +: propertyUpdaters else propertyUpdaters
    auditSrv.mergeAudits(super.update(steps, newPropertyUpdaters)) {
      case (caseSteps, updatedFields) =>
        caseSteps
          .newInstance()
          .getOrFail()
          .flatMap(auditSrv.`case`.update(_, updatedFields))
    }
  }

  def updateTagNames(`case`: Case with Entity, tags: Set[String])(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    tags.toTry(tagSrv.getOrCreate).flatMap(t => updateTags(`case`, t.toSet))

  def updateTags(`case`: Case with Entity, tags: Set[Tag with Entity])(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    val (tagsToAdd, tagsToRemove) = get(`case`)
      .tags
      .toIterator
      .foldLeft((tags, Set.empty[Tag with Entity])) {
        case ((toAdd, toRemove), t) if toAdd.contains(t) => (toAdd - t, toRemove)
        case ((toAdd, toRemove), t)                      => (toAdd, toRemove + t)
      }
    for {
      _ <- tagsToAdd.toTry(caseTagSrv.create(CaseTag(), `case`, _))
      _ = get(`case`).removeTags(tagsToRemove)
      _ <- auditSrv.`case`.update(`case`, Json.obj("tags" -> tags.map(_.toString)))
    } yield ()
  }

  def addTags(`case`: Case with Entity, tags: Set[String])(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    val currentTags = get(`case`)
      .tags
      .toList
      .map(_.toString)
      .toSet
    for {
      createdTags <- (tags -- currentTags).toTry(tagSrv.getOrCreate)
      _           <- createdTags.toTry(caseTagSrv.create(CaseTag(), `case`, _))
      _           <- auditSrv.`case`.update(`case`, Json.obj("tags" -> (currentTags ++ tags)))
    } yield ()
  }

  def addObservable(`case`: Case with Entity, richObservable: RichObservable)(
      implicit graph: Graph,
      authContext: AuthContext
  ): Try[Unit] = {
    val alreadyExistInThatCase = observableSrv
      .get(richObservable.observable)
      .similar
      .visible
      .`case`
      .hasId(`case`._id)
      .exists() || get(`case`).observables.filter(_.hasId(richObservable.observable._id)).exists()
    if (alreadyExistInThatCase)
      Failure(CreateError("Observable already exists"))
    else
      for {
        organisation <- organisationSrv.getOrFail(authContext.organisation)
        _            <- shareSrv.shareObservable(richObservable, `case`, organisation)
        _            <- auditSrv.observable.create(richObservable.observable, richObservable.toJson)
      } yield ()
  }

  def cascadeRemove(`case`: Case with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    for {
      _ <- get(`case`).tasks.toIterator.toTry(taskSrv.cascadeRemove(_))
      _ <- get(`case`).observables.toIterator.toTry(observableSrv.cascadeRemove(_))
      _ = get(`case`).share.remove()
      _ = get(`case`).remove()
      _ <- auditSrv.`case`.delete(`case`)
    } yield ()

  override def get(idOrNumber: String)(implicit graph: Graph): CaseSteps =
    Success(idOrNumber)
      .filter(_.headOption.contains('#'))
      .map(_.tail.toInt)
      .map(initSteps.getByNumber(_))
      .getOrElse(super.getByIds(idOrNumber))

  def getCustomField(`case`: Case with Entity, customFieldName: String)(implicit graph: Graph): Option[RichCustomField] =
    get(`case`).customFields(customFieldName).richCustomField.headOption()

  def updateCustomField(
      `case`: Case with Entity,
      customFieldValues: Seq[(CustomField, Any)]
  )(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    val customFieldNames = customFieldValues.map(_._1.name)
    get(`case`)
      .customFields
      .richCustomField
      .toIterator
      .filterNot(rcf => customFieldNames.contains(rcf.name))
      .foreach(rcf => get(`case`).customFields(rcf.name).remove())
    customFieldValues
      .toTry { case (cf, v) => setOrCreateCustomField(`case`, cf.name, Some(v)) }
      .map(_ => ())
  }

  def setOrCreateCustomField(`case`: Case with Entity, customFieldName: String, value: Option[Any])(
      implicit graph: Graph,
      authContext: AuthContext
  ): Try[Unit] = {
    val cfv = get(`case`).customFields(customFieldName)
    if (cfv.newInstance().exists())
      cfv.setValue(value)
    else
      createCustomField(`case`, customFieldName, value).map(_ => ())
  }

  def createCustomField(
      `case`: Case with Entity,
      customFieldName: String,
      customFieldValue: Option[Any]
  )(implicit graph: Graph, authContext: AuthContext): Try[RichCustomField] =
    for {
      cf   <- customFieldSrv.getOrFail(customFieldName)
      ccf  <- CustomFieldType.map(cf.`type`).setValue(CaseCustomField(), customFieldValue)
      ccfe <- caseCustomFieldSrv.create(ccf, `case`, cf)
    } yield RichCustomField(cf, ccfe)

  override def steps(raw: GremlinScala[Vertex])(implicit graph: Graph): CaseSteps = new CaseSteps(raw)

  def setImpactStatus(
      `case`: Case with Entity,
      impactStatus: String
  )(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    impactStatusSrv.getOrFail(impactStatus).flatMap(setImpactStatus(`case`, _))

  def setImpactStatus(
      `case`: Case with Entity,
      impactStatus: ImpactStatus with Entity
  )(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(`case`).unsetImpactStatus()
    caseImpactStatusSrv.create(CaseImpactStatus(), `case`, impactStatus)
    auditSrv.`case`.update(`case`, Json.obj("impactStatus" -> impactStatus.value))
  }

  def unsetImpactStatus(`case`: Case with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(`case`).unsetImpactStatus()
    auditSrv.`case`.update(`case`, Json.obj("impactStatus" -> JsNull))
  }

  def setResolutionStatus(
      `case`: Case with Entity,
      resolutionStatus: String
  )(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    resolutionStatusSrv.getOrFail(resolutionStatus).flatMap(setResolutionStatus(`case`, _))

  def setResolutionStatus(
      `case`: Case with Entity,
      resolutionStatus: ResolutionStatus with Entity
  )(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(`case`).unsetResolutionStatus()
    caseResolutionStatusSrv.create(CaseResolutionStatus(), `case`, resolutionStatus)
    auditSrv.`case`.update(`case`, Json.obj("resolutionStatus" -> resolutionStatus.value))
  }

  def unsetResolutionStatus(`case`: Case with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(`case`).unsetResolutionStatus()
    auditSrv.`case`.update(`case`, Json.obj("resolutionStatus" -> JsNull))
  }

  def assign(`case`: Case with Entity, user: User with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(`case`).unassign()
    caseUserSrv.create(CaseUser(), `case`, user)
    auditSrv.`case`.update(`case`, Json.obj("owner" -> user.login))
  }

  def unassign(`case`: Case with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(`case`).unassign()
    auditSrv.`case`.update(`case`, Json.obj("owner" -> JsNull))
  }

  def merge(cases: Seq[Case with Entity])(implicit graph: Graph, authContext: AuthContext): RichCase = ???
//  {
//    val summaries         = cases.flatMap(_.summary)
//    val user              = userSrv.getOrFail(authContext.userId)
//    val organisation      = organisationSrv.getOrFail(authContext.organisation)
//    val caseTaskSrv       = new EdgeSrv[CaseTask, Case, Task]
//    val caseObservableSrv = new EdgeSrv[CaseObservable, Case, Observable] nop
//
//    val mergedCase = create(
//      Case(
//        nextCaseNumber,
//        cases.map(_.title).mkString(" / "),
//        cases.map(_.description).mkString("\n\n"),
//        cases.map(_.severity).max,
//        cases.map(_.startDate).min,
//        None,
//        cases.flatMap(_.tags).distinct,
//        cases.exists(_.flag),
//        cases.map(_.tlp).max,
//        cases.map(_.pap).max,
//        CaseStatus.open,
//        if (summaries.isEmpty) None else Some(summaries.mkString("\n\n"))
//      ))
//    caseUserSrv.create(CaseUser(), mergedCase, user)
//    caseOrganisationSrv.create(CaseOrganisation(), mergedCase, organisation)
//    cases
//      .map(get)
//      .flatMap(_.customFields().toList
//      .groupBy(_.name)
//      .foreach {
//        case (name, l) ⇒
//          val values = l.collect { case cfwv: CustomFieldWithValue if cfwv.value.isDefined ⇒ cfwv.value.get }
//          val cf     = customFieldSrv.getOrFail(name)
//          val caseCustomField =
//            if (values.size == 1) cf.`type`.setValue(CaseCustomField(), values.head)
//            else CaseCustomField()
//          caseCustomFieldSrv.create(caseCustomField, mergedCase, cf)
//      }
//
//    cases.foreach(mergedFromSrv.create(MergedFrom(), mergedCase, _))
//
//    cases
//      .map(get)
//      .flatMap(_.tasks.toList
//      .foreach(task ⇒ caseTaskSrv.create(CaseTask(), task, mergedCase))
//
//    cases
//      .map(get)
//      .flatMap(_.observables.toList
//      .foreach(observable ⇒ observableCaseSrv.create(ObservableCase(), observable, mergedCase))
//
//    get(mergedCase).richCase.head()
//  }
}

@EntitySteps[Case]
class CaseSteps(raw: GremlinScala[Vertex])(implicit db: Database, graph: Graph) extends VertexSteps[Case](raw) {
  def resolutionStatus: ResolutionStatusSteps = new ResolutionStatusSteps(raw.outTo[CaseResolutionStatus])

  def get(id: String): CaseSteps =
    Success(id)
      .filter(_.headOption.contains('#'))
      .map(_.tail.toInt)
      .map(getByNumber)
      .getOrElse(this.getByIds(id))

  def getByNumber(caseNumber: Int): CaseSteps = newInstance(raw.has(Key("number") of caseNumber))

  def visible(implicit authContext: AuthContext): CaseSteps = newInstance(
    raw.filter(_.inTo[ShareCase].inTo[OrganisationShare].inTo[RoleOrganisation].inTo[UserRole].has(Key("login") of authContext.userId))
  )

  def assignee = new UserSteps(raw.outTo[CaseUser])

  def can(permission: Permission)(implicit authContext: AuthContext): CaseSteps =
    newInstance(
      raw.filter(
        _.inTo[ShareCase]
          .filter(_.outTo[ShareProfile].has(Key("permissions") of permission))
          .inTo[OrganisationShare]
          .inTo[RoleOrganisation]
          .filter(_.outTo[RoleProfile].has(Key("permissions") of permission))
          .inTo[UserRole]
          .has(Key("login") of authContext.userId)
      )
    )

  override def newInstance(newRaw: GremlinScala[Vertex]): CaseSteps = new CaseSteps(newRaw)

  override def newInstance(): CaseSteps = new CaseSteps(raw.clone())

  def getLast: CaseSteps =
    newInstance(raw.order(By(Key[Int]("number"), Order.desc)))

  def richCaseWithCustomRenderer[A](
      entityRenderer: CaseSteps => TraversalLike[_, A]
  )(implicit authContext: AuthContext): Traversal[(RichCase, A), (RichCase, A)] =
    Traversal(
      raw
        .project(
          _.apply(By[Vertex]())
            .and(By(__[Vertex].outTo[CaseTag].fold))
            .and(By(__[Vertex].outTo[CaseImpactStatus].values[String]("value").fold))
            .and(By(__[Vertex].outTo[CaseResolutionStatus].values[String]("value").fold))
            .and(By(__[Vertex].outTo[CaseUser].values[String]("login").fold))
            .and(By(__[Vertex].outToE[CaseCustomField].inV().path.fold))
            .and(By(entityRenderer(newInstance(__[Vertex])).raw))
            .and(By(newInstance(__[Vertex]).share(authContext.organisation).profile.permissions.fold.raw))
            .and(
              By(
                newInstance(__[Vertex])
                  .organisations
                  .has("name", authContext.organisation)
                  .userProfile(authContext.userId)
                  .permissions
                  .fold
                  .raw
              )
            )
        )
        .map {
          case (caze, tags, impactStatus, resolutionStatus, user, customFields, renderedEntity, sharePermissions, userPermissions) =>
            val customFieldValues = (customFields: JList[Path])
              .asScala
              .map(_.asScala.takeRight(2).toList.asInstanceOf[List[Element]])
              .map {
                case List(ccf, cf) => RichCustomField(cf.as[CustomField], ccf.as[CaseCustomField])
                case _             => throw InternalError("Not possible")
              }
            RichCase(
              caze.as[Case],
              tags.asScala.map(_.as[Tag]),
              atMostOneOf[String](impactStatus),
              atMostOneOf[String](resolutionStatus),
              atMostOneOf[String](user),
              customFieldValues,
              (sharePermissions.asScala.toSet & userPermissions.asScala.toSet).map(Permission.apply)
            ) -> renderedEntity
        }
    )

  def customFields(name: String): CustomFieldValueSteps =
    new CustomFieldValueSteps(raw.outToE[CaseCustomField].filter(_.inV().has(Key("name") of name)))

  def customFields: CustomFieldValueSteps =
    new CustomFieldValueSteps(raw.outToE[CaseCustomField])

  def share(implicit authContext: AuthContext): ShareSteps = share(authContext.organisation)

  def share(organistionName: String): ShareSteps =
    new ShareSteps(
      raw
        .inTo[ShareCase]
        .filter(_.inTo[OrganisationShare].has(Key("name") of organistionName))
    )

  def shares: ShareSteps = new ShareSteps(raw.inTo[ShareCase])

  def organisations: OrganisationSteps = new OrganisationSteps(raw.inTo[ShareCase].inTo[OrganisationShare])

  def organisations(permission: Permission) =
    new OrganisationSteps(raw.inTo[ShareCase].filter(_.outTo[ShareProfile].has(Key("permissions") of permission)).inTo[OrganisationShare])

  def origin: OrganisationSteps = new OrganisationSteps(raw.inTo[ShareCase].has(Key("owner") of true).inTo[OrganisationShare])

  // Warning: this method doesn't generate audit log
  def unassign(): Unit = {
    raw.outToE[CaseUser].drop().iterate()
    ()
  }

  def unsetResolutionStatus(): Unit = {
    raw.outToE[CaseResolutionStatus].drop().iterate()
    ()
  }

  def unsetImpactStatus(): Unit = {
    raw.outToE[CaseImpactStatus].drop().iterate()
    ()
  }

  def removeTags(tags: Set[Tag with Entity]): Unit =
    if (tags.nonEmpty)
      this.outToE[CaseTag].filter(_.otherV().hasId(tags.map(_._id).toSeq: _*)).remove()

  def linkedCases(implicit authContext: AuthContext): Seq[(RichCase, Seq[RichObservable])] = {
    val originCaseLabel = StepLabel[JSet[Vertex]]()
    val observableLabel = StepLabel[Vertex]()
    val linkedCaseLabel = StepLabel[Vertex]()

    val richCaseLabel        = StepLabel[RichCase]()
    val richObservablesLabel = StepLabel[JList[RichObservable]]()
    Traversal(
      raw
        .`match`(
          _.as(originCaseLabel.name)
            .in("ShareCase")
            .filter(
              _.inTo[OrganisationShare]
                .inTo[RoleOrganisation]
                .inTo[UserRole]
                .has(Key("login") of authContext.userId)
            )
            .out("ShareObservable")
            .as(observableLabel.name),
          _.as(observableLabel.name)
            .out("ObservableData")
            .in("ObservableData")
            .in("ShareObservable")
            .filter(
              _.inTo[OrganisationShare]
                .inTo[RoleOrganisation]
                .inTo[UserRole]
                .has(Key("login") of authContext.userId)
            )
            .out("ShareCase")
            .where(JP.neq(originCaseLabel.name))
            .as(linkedCaseLabel.name),
          c => new CaseSteps(c.as(linkedCaseLabel)).richCase.as(richCaseLabel).raw,
          o => new ObservableSteps(o.as(observableLabel)).richObservable.fold.as(richObservablesLabel).raw
        )
        .dedup(richCaseLabel.name)
        .select(richCaseLabel.name, richObservablesLabel.name)
    ).toList
      .map { resultMap =>
        resultMap.getValue(richCaseLabel) -> resultMap.getValue(richObservablesLabel).asScala
      }
  }

  def richCase(implicit authContext: AuthContext): Traversal[RichCase, RichCase] =
    Traversal(
      raw
        .project(
          _.apply(By[Vertex]())
            .and(By(__[Vertex].outTo[CaseTag].fold))
            .and(By(__[Vertex].outTo[CaseImpactStatus].values[String]("value").fold))
            .and(By(__[Vertex].outTo[CaseResolutionStatus].values[String]("value").fold))
            .and(By(__[Vertex].outTo[CaseUser].values[String]("login").fold))
            .and(By(__[Vertex].outToE[CaseCustomField].inV().path.fold))
            .and(By(newInstance(__[Vertex]).share(authContext.organisation).profile.permissions.fold.raw))
            .and(
              By(
                newInstance(__[Vertex])
                  .organisations
                  .has("name", authContext.organisation)
                  .userProfile(authContext.userId)
                  .permissions
                  .fold
                  .raw
              )
            )
        )
        .map {
          case (caze, tags, impactStatus, resolutionStatus, user, customFields, sharePermissions, userPermissions) =>
            val customFieldValues = (customFields: JList[Path])
              .asScala
              .map(_.asScala.takeRight(2).toList.asInstanceOf[List[Element]])
              .map {
                case List(ccf, cf) => RichCustomField(cf.as[CustomField], ccf.as[CaseCustomField])
                case _             => throw InternalError("Not possible")
              }
            RichCase(
              caze.as[Case],
              tags.asScala.map(_.as[Tag]),
              atMostOneOf[String](impactStatus),
              atMostOneOf[String](resolutionStatus),
              atMostOneOf[String](user),
              customFieldValues,
              (sharePermissions.asScala.toSet & userPermissions.asScala.toSet).map(Permission.apply)
            )
        }
    )

  def user: UserSteps = new UserSteps(raw.outTo[CaseUser])

  def richCaseWithoutPerms: Traversal[RichCase, RichCase] =
    Traversal(
      raw
        .project(
          _.apply(By[Vertex]())
            .and(By(__[Vertex].outTo[CaseTag].fold))
            .and(By(__[Vertex].outTo[CaseImpactStatus].values[String]("value").fold))
            .and(By(__[Vertex].outTo[CaseResolutionStatus].values[String]("value").fold))
            .and(By(__[Vertex].outTo[CaseUser].values[String]("login").fold))
            .and(By(__[Vertex].outToE[CaseCustomField].inV().path.fold))
        )
        .map {
          case (caze, tags, impactStatus, resolutionStatus, user, customFields) =>
            val customFieldValues = (customFields: JList[Path])
              .asScala
              .map(_.asScala.takeRight(2).toList.asInstanceOf[List[Element]])
              .map {
                case List(ccf, cf) => RichCustomField(cf.as[CustomField], ccf.as[CaseCustomField])
                case _             => throw InternalError("Not possible")
              }
            RichCase(
              caze.as[Case],
              tags.asScala.map(_.as[Tag]),
              atMostOneOf[String](impactStatus),
              atMostOneOf[String](resolutionStatus),
              atMostOneOf[String](user),
              customFieldValues,
              Set.empty
            )
        }
    )

  def tags: TagSteps = new TagSteps(raw.outTo[CaseTag])

  def impactStatus: ImpactStatusSteps = new ImpactStatusSteps(raw.outTo[CaseImpactStatus])

  def tasks(implicit authContext: AuthContext): TaskSteps =
    new TaskSteps(
      raw
        .inTo[ShareCase]
        .filter(_.inTo[OrganisationShare].has(Key("name") of authContext.organisation))
        .outTo[ShareTask]
    )

  def observables(implicit authContext: AuthContext): ObservableSteps =
    new ObservableSteps(
      raw
        .inTo[ShareCase]
        .filter(_.inTo[OrganisationShare].has(Key("name") of authContext.organisation))
        .outTo[ShareObservable]
    )

  def assignableUsers(implicit authContext: AuthContext): UserSteps =
    organisations(Permissions.manageCase)
      .visible
      .users(Permissions.manageCase)
      .dedup

  def alert: AlertSteps = new AlertSteps(raw.inTo[AlertCase])
}

class CaseDedupOps(val db: Database, val service: CaseSrv) extends DedupOps[Case] {
  override def resolve(entities: List[Case with Entity])(implicit graph: Graph): Try[Unit] = {
    val nextNumber = service.nextCaseNumber
    entities
      .sorted(createdFirst)
      .tail
      .flatMap(service.get(_).raw.headOption())
      .zipWithIndex
      .foreach {
        case (vertex, index) =>
          db.setSingleProperty(vertex, "number", nextNumber + index, UniMapping.int)
      }
    Success(())
  }
}

class CaseDedupActor @Inject() (db: Database, service: CaseSrv) extends CaseDedupOps(db, service) with DedupActor {
  override val min: FiniteDuration = 5.seconds
  override val max: FiniteDuration = 10.seconds
}

@Singleton
class CaseDedupActorProvider extends DedupActorProvider[CaseDedupActor]("Case")
