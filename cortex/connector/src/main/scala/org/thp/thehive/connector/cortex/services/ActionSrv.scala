package org.thp.thehive.connector.cortex.services

import java.util.Date

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import play.api.libs.json.{JsObject, Json, OWrites}

import akka.actor.ActorRef
import com.google.inject.name.Named
import gremlin.scala._
import io.scalaland.chimney.dsl._
import javax.inject.Inject
import org.thp.cortex.client.CortexClient
import org.thp.cortex.dto.v0.{CortexOutputJob, InputCortexAction}
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models._
import org.thp.scalligraph.services._
import org.thp.scalligraph.steps.StepsOps._
import org.thp.scalligraph.steps.{Traversal, VertexSteps}
import org.thp.scalligraph.{EntitySteps, NotFoundError}
import org.thp.thehive.connector.cortex.controllers.v0.Conversion._
import org.thp.thehive.connector.cortex.models._
import org.thp.thehive.connector.cortex.services.Conversion._
import org.thp.thehive.connector.cortex.services.CortexActor.CheckJob
import org.thp.thehive.controllers.v0.Conversion._
import org.thp.thehive.models.{Case, Task}

class ActionSrv @Inject()(
    @Named("cortex-actor") cortexActor: ActorRef,
    actionOperationSrv: ActionOperationSrv,
    entityHelper: EntityHelper,
    serviceHelper: ServiceHelper,
    connector: Connector,
    implicit val schema: Schema,
    implicit val db: Database,
    implicit val ec: ExecutionContext,
    auditSrv: CortexAuditSrv
) extends VertexSrv[Action, ActionSteps] {

  val actionContextSrv = new EdgeSrv[ActionContext, Action, Product]

  override def steps(raw: GremlinScala[Vertex])(implicit graph: Graph): ActionSteps = new ActionSteps(raw)

  /**
    * Executes an Action on user demand,
    * creates a job on Cortex side and then persist the
    * Action, looking forward job completion
    *
    * @param action      the initial data
    * @param entity      the Entity to execute an Action upon
    * @param authContext necessary auth context
    * @return
    */
  def execute(action: Action, entity: Entity)(implicit writes: OWrites[Entity], authContext: AuthContext): Future[RichAction] = {
    val cortexClients = serviceHelper.availableCortexClients(connector.clients, authContext.organisation)
    for {
      client <- action.cortexId match {
        case Some(cortexId) =>
          cortexClients
            .find(_.name == cortexId)
            .fold[Future[CortexClient]](Future.failed(NotFoundError(s"Cortex $cortexId not found")))(Future.successful)
        case None if cortexClients.nonEmpty =>
          Future.firstCompletedOf {
            cortexClients
              .map(client => client.getResponder(action.responderId).map(_ => client))
          }

        case None => Future.failed(NotFoundError(s"Responder ${action.responderId} not found"))
      }
      (label, tlp, pap) <- Future.fromTry(db.roTransaction(implicit graph => entityHelper.entityInfo(entity)))
      inputCortexAction = toCortexAction(action, label, tlp, pap, writes.writes(entity))
      job <- client.execute(action.responderId, inputCortexAction)
      updatedAction = action.copy(
        responderName = Some(job.workerName),
        responderDefinition = Some(job.workerDefinition),
        status = job.status.toJobStatus,
        startDate = job.startDate.getOrElse(new Date()),
        cortexId = Some(client.name),
        cortexJobId = Some(job.id)
      )
      createdAction <- Future.fromTry {
        db.tryTransaction { implicit graph =>
          for {
            a <- create(updatedAction, entity)
            _ <- auditSrv.action.create(a.action, entity, a.toJson)
          } yield a
        }
      }
      _ = cortexActor ! CheckJob(None, job.id, Some(createdAction._id), client.name, authContext)
    } yield createdAction
  }

  def toCortexAction(action: Action, label: String, tlp: Int, pap: Int, data: JsObject): InputCortexAction =
    action
      .into[InputCortexAction]
      .withFieldConst(_.dataType, s"thehive:${action.objectType}")
      .withFieldConst(_.label, label)
      .withFieldConst(_.data, data)
      .withFieldConst(_.tlp, tlp)
      .withFieldConst(_.pap, pap)
      .transform

  /**
    * Creates an Action with necessary ActionContext edge
    *
    * @param action      the action to persist
    * @param context     the context Entity to link to
    * @param graph       graph needed for db queries
    * @param authContext auth for db queries
    * @return
    */
  def create(
      action: Action,
      context: Entity
  )(implicit graph: Graph, authContext: AuthContext): Try[RichAction] =
    for {
      createdAction <- createEntity(action)
      _             <- actionContextSrv.create(ActionContext(), createdAction, context)
    } yield RichAction(createdAction, context)

  /**
    * Once the job is finished for a precise Action,
    * updates it
    *
    * @param actionId        the action to update
    * @param cortexOutputJob the result Cortex job
    * @param authContext     context for db queries
    * @return
    */
  def finished(actionId: String, cortexOutputJob: CortexOutputJob)(implicit authContext: AuthContext): Try[Action with Entity] =
    db.tryTransaction { implicit graph =>
      val operations = cortexOutputJob
        .report
        .fold[Seq[ActionOperation]](Nil)(_.operations.map(_.toActionOperation))
        .filter(_.status == ActionOperationStatus.Waiting)
        .map { operation =>
          (for {
            action <- getByIds(actionId).richAction.getOrFail()
            updatedOperation <- actionOperationSrv.execute(
              action.context,
              operation,
              relatedCase(actionId),
              relatedTask(actionId)
            )
          } yield updatedOperation)
            .fold(t => operation.updateStatus(ActionOperationStatus.Failure, t.getMessage), identity)
        }

      getByIds(actionId)
        .update(
          "status"     -> cortexOutputJob.status.toJobStatus,
          "report"     -> cortexOutputJob.report.map(r => Json.toJson(r.copy(operations = Nil))),
          "endDate"    -> Some(new Date()),
          "operations" -> operations.map(Json.toJsObject(_))
        )
    }

  /**
    * Gets an optional related Case to the Action Entity
    * @param id action id
    * @param graph db graph
    * @return
    */
  def relatedCase(id: String)(implicit graph: Graph): Option[Case with Entity] =
    for {
      richAction  <- initSteps.getByIds(id).richAction.getOrFail().toOption
      relatedCase <- entityHelper.parentCase(richAction.context)
    } yield relatedCase

  def relatedTask(id: String)(implicit graph: Graph): Option[Task with Entity] =
    for {
      richAction  <- initSteps.getByIds(id).richAction.getOrFail().toOption
      relatedTask <- entityHelper.parentTask(richAction.context)
    } yield relatedTask

  // TODO to be tested
  def listForEntity(id: String)(implicit graph: Graph): List[Action with Entity] = initSteps.forEntity(id).toList
}

@EntitySteps[Action]
class ActionSteps(raw: GremlinScala[Vertex])(implicit db: Database, graph: Graph, schema: Schema) extends VertexSteps[Action](raw) {

  /**
    * Provides a RichAction model with additional Entity context
    *
    * @return
    */
  def richAction: Traversal[RichAction, RichAction] =
    Traversal(
      raw
        .project(
          _.apply(By[Vertex]())
            .and(By(__[Vertex].outTo[ActionContext]))
        )
        .map {
          case (action, context) =>
            RichAction(action.as[Action], context.asEntity)
        }
    )

  def forEntity(entityId: String): ActionSteps =
    newInstance(
      raw.filter(
        _.outTo[ActionContext]
          .hasId(entityId)
      )
    )

  def context: Traversal[Entity, Entity] = Traversal(raw.outTo[ActionContext].map(_.asEntity))

  def visible(authContext: AuthContext): ActionSteps = ???

  override def newInstance(newRaw: GremlinScala[Vertex]): ActionSteps = new ActionSteps(newRaw)
  override def newInstance(): ActionSteps                             = new ActionSteps(raw.clone())
}
