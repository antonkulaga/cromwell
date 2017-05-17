package cromwell.webservice

import akka.actor.ActorRef
import akka.http.scaladsl.server.Directives._

import scala.concurrent.{ExecutionContext, Future, Promise}
import akka.http.scaladsl.model.Multipart
import akka.http.scaladsl.model.Multipart.BodyPart
import akka.stream.ActorMaterializer
import cromwell.engine.backend.BackendConfiguration
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.{RequestContext, Route, RouteResult}
import cromwell.core.WorkflowSourceFilesCollection
import cromwell.engine.workflow.workflowstore.WorkflowStoreActor
import spray.json.DefaultJsonProtocol._
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._

// FIXME: rename once the cutover happens
trait AkkaHttpService extends PerRequestCreator {
  import cromwell.webservice.AkkaHttpService._

  implicit val materializer: ActorMaterializer
  implicit val ec: ExecutionContext

  val workflowStoreActor: ActorRef
  val workflowManagerActor: ActorRef

  // FIXME: make this bigger and elsewhere?
  val duration = 5.seconds
  implicit val timeout: Timeout = duration

  // FIXME: these should live elsewhere (WorkflowJsonSupport currently)
  implicit val BackendResponseFormat = jsonFormat2(BackendResponse)
  implicit val EngineStatsFormat = jsonFormat2(EngineStatsActor.EngineStats)

  val backendResponse = BackendResponse(BackendConfiguration.AllBackendEntries.map(_.name).sorted, BackendConfiguration.DefaultBackendEntry.name)

  // a custom directive
  def imperativelyComplete(inner: ImperativeRequestContext => Unit): Route = { ctx: RequestContext =>
    val p = Promise[RouteResult]()
    inner(new ImperativeRequestContext(ctx, p))
    p.future
  }

  // FIXME: This is missing the 'api' stuff

  val routes =
    path("workflows" / Segment / "backends") { version =>
      get {
        complete(backendResponse)
      }
    } ~ // FIXME: per-requestify
    path("engine" / Segment / "stats") { version =>
      get {
        imperativelyComplete { ctx =>
          perRequest(ctx, CromwellApiHandler.props(workflowManagerActor), CromwellApiHandler.ApiHandlerEngineStats)
        }

//        onComplete(workflowManagerActor.ask(WorkflowManagerActor.EngineStatsCommand).mapTo[EngineStatsActor.EngineStats]) {
//          case Success(stats) => complete(stats)
//          case Failure(blah) => complete("oh that's too bad")
//        }
      }
    } ~
    path("workflows" / Segment) { version => // FIXME: This doesn't handle all of the new workflow inputs files nor the zip file. See hte PartialSources stuff
      post {
        entity(as[Multipart.FormData]) { shite =>
          val allParts: Future[Map[String, String]] = shite.parts.mapAsync[(String, String)](1) {
            case b: BodyPart => b.toStrict(duration).map(strict => b.name -> strict.entity.data.utf8String)
          }.runFold(Map.empty[String, String])((map, tuple) => map + tuple)

          onSuccess(allParts) { files =>
            val wdlSource = files("wdlSource")
            val workflowInputs = files.getOrElse("workflowInputs", "{}")
            val workflowOptions = files.getOrElse("workflowOptions", "{}")
            val workflowLabels = files.getOrElse("customLabels", "{}")
            val workflowSourceFiles = WorkflowSourceFilesCollection(wdlSource, workflowInputs, workflowOptions, workflowLabels, None)
            // FIXME: blows up on wdlSource, doesn't check for other inputs
            workflowStoreActor.ask(WorkflowStoreActor.SubmitWorkflow(workflowSourceFiles)).mapTo[WorkflowStoreActor.SubmitWorkflow] // FIXME: unassigned
            complete("submitted") // FIXME: not the right response
          }
        }
      }
    }
}

object AkkaHttpService {
  case class BackendResponse(supportedBackends: List[String], defaultBackend: String)

  final class ImperativeRequestContext(val ctx: RequestContext, promise: Promise[RouteResult]) {
    private implicit val ec = ctx.executionContext
    def complete(obj: ToResponseMarshallable): Unit = ctx.complete(obj).onComplete(promise.complete)
    def fail(error: Throwable): Unit = ctx.fail(error).onComplete(promise.complete)
  }
}