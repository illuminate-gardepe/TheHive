package org.thp.thehive.controllers.v1

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import org.thp.scalligraph.controllers.Entrypoint
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.services.GenIntegrityCheckOps
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.thehive.models.Permissions
import org.thp.thehive.services.{CheckState, CheckStats, GetCheckStats, GlobalCheckRequest}
import play.api.Logger
import play.api.libs.json.{JsObject, Json, OWrites}
import play.api.mvc.{Action, AnyContent, Results}

import javax.inject.{Inject, Named, Singleton}
import scala.collection.immutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

@Singleton
class AdminCtrl @Inject() (
    entrypoint: Entrypoint,
    @Named("integrity-check-actor") integrityCheckActor: ActorRef,
    integrityCheckOps: immutable.Set[GenIntegrityCheckOps],
    db: Database,
    implicit val ec: ExecutionContext
) {

  implicit val timeout: Timeout                      = Timeout(5.seconds)
  implicit val checkStatsWrites: OWrites[CheckStats] = Json.writes[CheckStats]
  implicit val checkStateWrites: OWrites[CheckState] = OWrites[CheckState] { state =>
    Json.obj(
      "needCheck"              -> state.needCheck,
      "duplicateTimer"         -> state.duplicateTimer.isDefined,
      "duplicateStats"         -> state.duplicateStats,
      "globalStats"            -> state.globalStats,
      "globalCheckRequestTime" -> state.globalCheckRequestTime
    )
  }
  lazy val logger: Logger = Logger(getClass)

  def triggerCheck(name: String): Action[AnyContent] =
    entrypoint("Trigger check")
      .authPermitted(Permissions.managePlatform) { _ =>
        integrityCheckActor ! GlobalCheckRequest(name)
        Success(Results.NoContent)
      }

  def checkStats: Action[AnyContent] =
    entrypoint("Get check stats")
      .asyncAuthPermitted(Permissions.managePlatform) { _ =>
        Future
          .traverse(integrityCheckOps.toSeq) { c =>
            (integrityCheckActor ? GetCheckStats(c.name))
              .mapTo[CheckState]
              .recover {
                case error =>
                  logger.error(s"Fail to get check stats of ${c.name}", error)
                  CheckState.empty
              }
              .map(c.name -> _)
          }
          .map { results =>
            Results.Ok(JsObject(results.map(r => r._1 -> Json.toJson(r._2))))
          }
      }

  private val indexedModels = Seq("Alert", "Attachment", "Audit", "Case", "Log", "Observable", "Tag", "Task")
  def indexStatus: Action[AnyContent] =
    entrypoint("Get index status")
      .authPermittedRoTransaction(db, Permissions.managePlatform) { _ => graph =>
        val status = indexedModels.map { label =>
          val mixedCount     = graph.V(label).getCount
          val compositeCount = graph.underlying.traversal().V().has("_label", label).count().next().toLong
          label -> Json.obj(
            "mixedCount"     -> mixedCount,
            "compositeCount" -> compositeCount,
            "status"         -> (if (mixedCount == compositeCount) "OK" else "Error")
          )
        }
        Success(Results.Ok(JsObject(status)))
      }

  def reindex(label: String): Action[AnyContent] =
    entrypoint("Reindex data")
      .authPermitted(Permissions.managePlatform) { _ =>
        Future(db.reindexData(label))
        Success(Results.NoContent)
      }
}
