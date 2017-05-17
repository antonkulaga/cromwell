package cromwell.server

import akka.actor.Props
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.Timeout
import cromwell.webservice.AkkaHttpService

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

// Note that as per the language specification, this is instantiated lazily and only used when necessary (i.e. server mode)
object CromwellServer {

  implicit val timeout = Timeout(5.seconds)

  def run(cromwellSystem: CromwellSystem): Future[Any] = {
    implicit val actorSystem = cromwellSystem.actorSystem
    implicit val materializer = ActorMaterializer()
    implicit val ec = actorSystem.dispatcher
    actorSystem.actorOf(CromwellServerActor.props(cromwellSystem), "cromwell-service")
    Future {
      Await.result(actorSystem.whenTerminated, Duration.Inf)
    }
  }
}

class CromwellServerActor(cromwellSystem: CromwellSystem)(override implicit val materializer: ActorMaterializer) extends CromwellRootActor with AkkaHttpService {
  // FIXME: swagger!
  implicit val actorSystem = context.system
  override implicit val ec = context.dispatcher
//  override implicit val materializer = ActorMaterializer()

  override val serverMode = true
  override val abortJobsOnTerminate = false

  val webserviceConf = cromwellSystem.conf.getConfig("webservice")
  val interface = webserviceConf.getString("interface")
  val port = webserviceConf.getInt("port")

  // FIXME: Can I use that httpapp thing?

  Http().bindAndHandle(routes, interface, port) onComplete {
    case Success(_) => actorSystem.log.info("Cromwell service started...")
    case Failure(regerts) =>
      /*
        TODO:
        If/when CromwellServer behaves like a better async citizen, we may be less paranoid about our async log messages
        not appearing due to the actor system shutdown. For now, synchronously print to the stderr so that the user has
        some idea of why the server failed to start up.
      */
      Console.err.println(s"Binding failed interface $interface port $port")
      regerts.printStackTrace(Console.err)
      cromwellSystem.shutdownActorSystem()
  }

  /*
    During testing it looked like not explicitly invoking the WMA in order to evaluate all of the lazy actors in
    CromwellRootActor would lead to weird timing issues the first time it was invoked organically
   */
  workflowManagerActor
}

object CromwellServerActor {
  def props(cromwellSystem: CromwellSystem)(implicit materializer: ActorMaterializer): Props = {
    Props(new CromwellServerActor(cromwellSystem))
  }
}

//  def run(cromwellSystem: CromwellSystem): Future[Any] = {
//    implicit val executionContext = scala.concurrent.ExecutionContext.Implicits.global
//
//    val actorSystem: ActorSystem = cromwellSystem.actorSystem
//    implicit val materializer: ActorMaterializer = cromwellSystem.materializer
//
//    val service = actorSystem.actorOf(CromwellServerActor.props(cromwellSystem.conf), "cromwell-service")
//    val webserviceConf = cromwellSystem.conf.getConfig("webservice")
//
//    val interface = webserviceConf.getString("interface")
//    val port = webserviceConf.getInt("port")
//    val timeout = webserviceConf.as[FiniteDuration]("binding-timeout")
//    val futureBind = service.bind(interface, port)(implicitly, timeout, actorSystem, implicitly)
//    futureBind andThen {
//      case Success(_) =>
//        actorSystem.log.info("Cromwell service started...")
//        Await.result(actorSystem.whenTerminated, Duration.Inf)
//      case Failure(throwable) =>
//        /*
//        TODO:
//        If/when CromwellServer behaves like a better async citizen, we may be less paranoid about our async log messages
//        not appearing due to the actor system shutdown. For now, synchronously print to the stderr so that the user has
//        some idea of why the server failed to start up.
//         */
//        Console.err.println(s"Binding failed interface $interface port $port")
//        throwable.printStackTrace(Console.err)
//        cromwellSystem.shutdownActorSystem()
//    }
//  }
//}

//class CromwellServerActor(config: Config)(implicit materializer: ActorMaterializer) extends CromwellRootActor with CromwellApiService with SwaggerService {
//  implicit def executionContext: ExecutionContextExecutor = actorRefFactory.dispatcher
//
//  override val serverMode = true
//  override val abortJobsOnTerminate = false
//
//  override def actorRefFactory: ActorContext = context
//  override def receive: PartialFunction[Any, Unit] = handleTimeouts orElse runRoute(possibleRoutes)
//
//  val routeUnwrapped: Boolean = config.as[Option[Boolean]]("api.routeUnwrapped").getOrElse(false)
//  val possibleRoutes: Route = workflowRoutes.wrapped("api", routeUnwrapped) ~ swaggerUiResourceRoute
//  val timeoutError: String = APIResponse.error(new TimeoutException(
//    "The server was not able to produce a timely response to your request.")).toJson.prettyPrint
//
//  def handleTimeouts: Receive = {
//    case Timedout(_: HttpRequest) =>
//      sender() ! HttpResponse(StatusCodes.InternalServerError, HttpEntity(ContentType(MediaTypes.`application/json`), timeoutError))
//  }
//
//  /*
//    During testing it looked like not explicitly invoking the WMA in order to evaluate all of the lazy actors in
//    CromwellRootActor would lead to weird timing issues the first time it was invoked organically
//   */
//  workflowManagerActor
//}
//
//object CromwellServerActor {
//  def props(config: Config)(implicit materializer: ActorMaterializer): Props = {
//    Props(new CromwellServerActor(config)).withDispatcher(EngineDispatcher)
//  }
//}
