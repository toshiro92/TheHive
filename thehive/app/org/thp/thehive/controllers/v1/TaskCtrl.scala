package org.thp.thehive.controllers.v1

import scala.util.Success
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, Results}
import javax.inject.{Inject, Singleton}
import org.thp.scalligraph.controllers.{EntryPoint, FieldsParser}
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.query.{ParamQuery, PropertyUpdater, PublicProperty, Query}
import org.thp.scalligraph.steps.PagedResult
import org.thp.scalligraph.steps.StepsOps._
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.dto.v1.InputTask
import org.thp.thehive.models.{Permissions, RichTask}
import org.thp.thehive.services.{CaseSrv, OrganisationSrv, ShareSrv, TaskSrv, TaskSteps}

@Singleton
class TaskCtrl @Inject()(
    entryPoint: EntryPoint,
    db: Database,
    properties: Properties,
    taskSrv: TaskSrv,
    caseSrv: CaseSrv,
    organisationSrv: OrganisationSrv,
    shareSrv: ShareSrv
) extends QueryableCtrl {

  override val entityName: String                           = "task"
  override val publicProperties: List[PublicProperty[_, _]] = properties.task ::: metaProperties[TaskSteps]
  override val initialQuery: Query =
    Query.init[TaskSteps]("listTask", (graph, authContext) => organisationSrv.get(authContext.organisation)(graph).shares.tasks)
  override val pageQuery: ParamQuery[OutputParam] = Query.withParam[OutputParam, TaskSteps, PagedResult[RichTask]](
    "page",
    FieldsParser[OutputParam],
    (range, taskSteps, _) => taskSteps.richPage(range.from, range.to, withTotal = true)(_.richTask)
  )
  override val getQuery: ParamQuery[IdOrName] = Query.initWithParam[IdOrName, TaskSteps](
    "getTask",
    FieldsParser[IdOrName],
    (param, graph, authContext) => taskSrv.get(param.idOrName)(graph).visible(authContext)
  )
  override val outputQuery: Query = Query.output[RichTask]()
  override val extraQueries: Seq[ParamQuery[_]] = Seq(
    Query[TaskSteps, List[RichTask]]("toList", (taskSteps, _) => taskSteps.richTask.toList)
  )

  def create: Action[AnyContent] =
    entryPoint("create task")
      .extract("task", FieldsParser[InputTask])
      .authTransaction(db) { implicit request => implicit graph =>
        val inputTask: InputTask = request.body("task")
        for {
          case0       <- caseSrv.getOrFail(inputTask.caseId)
          createdTask <- taskSrv.create(inputTask.toTask)
          richTask = RichTask(createdTask, None)
          _ <- shareSrv.shareCaseTask(case0, richTask)
        } yield Results.Created(richTask.toJson)
      }

  def get(taskId: String): Action[AnyContent] =
    entryPoint("get task")
      .authRoTransaction(db) { implicit request => implicit graph =>
        taskSrv
          .getByIds(taskId)
          .visible
          .richTask
          .getOrFail()
          .map(task => Results.Ok(task.toJson))
      }

  def list: Action[AnyContent] =
    entryPoint("list task")
      .authRoTransaction(db) { implicit request => implicit graph =>
        val tasks = taskSrv
          .initSteps
          .visible
          .richTask
          .toList
          .map(_.toJson)
        Success(Results.Ok(Json.toJson(tasks)))
      }

  def update(taskId: String): Action[AnyContent] =
    entryPoint("update task")
      .extract("task", FieldsParser.update("task", properties.task))
      .authTransaction(db) { implicit request => implicit graph =>
        val propertyUpdaters: Seq[PropertyUpdater] = request.body("task")
        taskSrv
          .update(
            _.getByIds(taskId)
              .can(Permissions.manageTask),
            propertyUpdaters
          )
          .map(_ => Results.NoContent)
      }
}
