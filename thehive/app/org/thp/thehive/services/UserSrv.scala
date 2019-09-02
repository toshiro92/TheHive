package org.thp.thehive.services

import gremlin.scala._
import javax.inject.{Inject, Singleton}
import org.thp.scalligraph.auth.{AuthContext, AuthContextImpl, Permission}
import org.thp.scalligraph.models._
import org.thp.scalligraph.query.PropertyUpdater
import org.thp.scalligraph.services._
import org.thp.scalligraph.{EntitySteps, InternalError}
import org.thp.thehive.models._
import play.api.libs.json.JsObject

import scala.util.{Failure, Success, Try}

object UserSrv {
  val initUser: String         = "admin"
  val initUserPassword: String = "secret"

}

@Singleton
class UserSrv @Inject()(roleSrv: RoleSrv, auditSrv: AuditSrv, implicit val db: Database) extends VertexSrv[User, UserSteps] {

  override val initialValues: Seq[User] = Seq(
    User(
      login = UserSrv.initUser,
      name = "Default admin user",
      apikey = None,
      locked = false,
      password = Some(LocalPasswordAuthSrv.hashPassword(UserSrv.initUserPassword))
    )
  )
  val userRoleSrv = new EdgeSrv[UserRole, User, Role]

  override def steps(raw: GremlinScala[Vertex])(implicit graph: Graph): UserSteps = new UserSteps(raw)

  def create(user: User, organisation: Organisation with Entity, profile: Profile with Entity)(
      implicit graph: Graph,
      authContext: AuthContext
  ): Try[RichUser] =
    for {
      createdUser <- create(user)
      _           <- roleSrv.create(createdUser, organisation, profile)
      _           <- auditSrv.user.create(createdUser)
    } yield RichUser(createdUser, profile.name, profile.permissions, organisation.name)

  def current(implicit graph: Graph, authContext: AuthContext): UserSteps = get(authContext.userId)

  def getOrganisation(user: User with Entity)(implicit graph: Graph): Try[Organisation with Entity] =
    get(user.login)
      .organisations
      .headOption()
      .fold[Try[Organisation with Entity]](Failure(InternalError(s"The user $user (${user._id}) has no organisation.")))(Success.apply)

  override def get(idOrName: String)(implicit graph: Graph): UserSteps =
    if (db.isValidId(idOrName)) getByIds(idOrName)
    else initSteps.getByName(idOrName)

  def getProfile(userId: String, organizationName: String)(implicit graph: Graph): UserSteps =
    get(userId)

  override def update(
      steps: UserSteps,
      propertyUpdaters: Seq[PropertyUpdater]
  )(implicit graph: Graph, authContext: AuthContext): Try[(UserSteps, JsObject)] =
    auditSrv.mergeAudits(super.update(steps, propertyUpdaters)) {
      case (userSteps, updatedFields) =>
        userSteps
          .clone()
          .getOrFail()
          .flatMap(auditSrv.user.update(_, updatedFields))
    }
}

@EntitySteps[User]
class UserSteps(raw: GremlinScala[Vertex])(implicit db: Database, graph: Graph) extends BaseVertexSteps[User, UserSteps](raw) {
  def current(authContext: AuthContext): UserSteps = get(authContext.userId)

  def get(idOrName: String): UserSteps =
    if (db.isValidId(idOrName)) getByIds(idOrName)
    else getByName(idOrName)

  def getByName(login: String): UserSteps = new UserSteps(raw.has(Key("login") of login))

  def visible(implicit authContext: AuthContext): UserSteps = newInstance(
    raw.filter(_.outTo[UserRole].outTo[RoleOrganisation].has(Key("name") of authContext.organisation))
  )

  override def newInstance(raw: GremlinScala[Vertex]): UserSteps = new UserSteps(raw)

  def can(permission: Permission)(implicit authContext: AuthContext): UserSteps = newInstance(
    raw
      .outTo[UserRole]
      .outTo[RoleOrganisation]
      .has(Key("name") of authContext.organisation)
      .inTo[RoleOrganisation]
      .filter(_.outTo[RoleProfile].has(Key("permissions") of permission))
      .inTo[UserRole]
      .has(Key("login") of authContext.userId)
  )

  def getByAPIKey(key: String): UserSteps = new UserSteps(raw.has(Key("apikey") of key))

  def organisations: OrganisationSteps = new OrganisationSteps(raw.outTo[UserRole].outTo[RoleOrganisation])

  def organisations(requiredPermission: String): OrganisationSteps = new OrganisationSteps(
    raw
      .outTo[UserRole]
      .filter(_.outTo[RoleProfile].has(Key("permissions") of requiredPermission))
      .outTo[RoleOrganisation]
  )

  def config: ConfigSteps = new ConfigSteps(raw.outTo[UserConfig])

  //  def availableFor(authContext: AuthContext): UserSteps = ???
//    availableFor(authContext.organisation)

//  def availableFor(organisation: String): UserSteps = ???
//  newInstance(raw.filter(_.outTo[UserOrganisation].value("name").is(organisation)))

  def getAuthContext(requestId: String, organisation: Option[String]): ScalarSteps[AuthContext] = {
    val organisationName = organisation
      .orElse(
        raw
          .clone()
          .outTo[UserRole]
          .outTo[RoleOrganisation]
          .value[String]("name")
          .headOption()
      )
      .getOrElse("default")
    getAuthContext(requestId, organisationName)
  }

  def getAuthContext(requestId: String, organisationName: String): ScalarSteps[AuthContext] =
    ScalarSteps(
      raw
        .has(Key("locked") of false)
        .project(
          _.apply(By(__.value[String]("login")))
            .and(By(__.value[String]("name")))
            .and(By(__[Vertex].outTo[UserRole].filter(_.outTo[RoleOrganisation].has(Key("name") of organisationName)).outTo[RoleProfile]))
        )
        .map {
          case (userId, userName, profile) =>
            AuthContextImpl(userId, userName, organisationName, requestId, profile.as[Profile].permissions)
        }
    )

  def richUser(organisation: String): ScalarSteps[RichUser] =
    new ScalarSteps[RichUser](
      raw
        .project(
          _.apply(By[Vertex]())
            .and(By(__[Vertex].outTo[UserRole].filter(_.outTo[RoleOrganisation].has(Key("name") of organisation)).outTo[RoleProfile].fold()))
        )
        .collect {
          case (user, profiles) if profiles.size() == 1 =>
            val profile = profiles.get(0).as[Profile]
            RichUser(user.as[User], profile.name, profile.permissions, organisation)
        }
    )
}
