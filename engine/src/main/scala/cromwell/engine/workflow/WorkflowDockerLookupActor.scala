package cromwell.engine.workflow

import akka.actor.{ActorRef, LoggingFSM, Props}
import cromwell.core.{Dispatcher, WorkflowId}
import cromwell.database.sql.tables.DockerHashStoreEntry
import cromwell.docker.DockerHashActor.{DockerHashFailureResponse, DockerHashSuccessResponse}
import cromwell.docker.{DockerClientHelper, DockerHashRequest, DockerHashResult, DockerImageIdentifier}
import cromwell.engine.workflow.WorkflowActor.{RestartExistingWorkflow, StartMode}
import cromwell.engine.workflow.WorkflowDockerLookupActor._
import cromwell.services.SingletonServicesStore
import lenthall.util.TryUtil

import scala.collection.immutable.Queue
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
  * Ensures docker hashes consistency throughout a workflow.
  * 
  * Caches successful docker hash lookups and serve them to subsequent identical requests.
  * Persists those hashes in the database to be resilient to server restarts.
  * 
  * Failure modes:
  * 1) Fail to load hashes from DB upon restart
  * 2) Fail to parse hashes from the DB upon restart
  * 3) Fail to write hash result to the DB
  * 4) Fail to lookup docker hash
  * 
  * Behavior:
  * 1-2) Return lookup failures for all requests
  * 3-4) Return lookup failure for current request and all subsequent requests for the same tag
  */

class WorkflowDockerLookupActor(workflowId: WorkflowId, val dockerHashingActor: ActorRef, startMode: StartMode)
  extends LoggingFSM[WorkflowDockerLookupActorState, WorkflowDockerLookupActorData] with DockerClientHelper with SingletonServicesStore {

  implicit val ec = context.system.dispatchers.lookup(Dispatcher.EngineDispatcher)

  // Amount of time after which the docker request should be considered lost and sent again
  override protected def backpressureTimeout: FiniteDuration = 10 seconds
  // Amount of time to wait when we get a Backpressure response before sending the request again
  override protected def backpressureRandomizerFactor: Double = 0.5D

  private val restart = startMode == RestartExistingWorkflow

  context.become(dockerReceive orElse receive)

  if (restart) {
    startWith(LoadingCache, WorkflowDockerLookupActorData.empty)
  } else {
    startWith(Idle, WorkflowDockerLookupActorData.empty)
  }

  override def preStart(): Unit = {
    if (restart) {
      databaseInterface.queryDockerHashStoreEntries(workflowId.toString) onComplete {
        case Success(dockerHashEntries) =>
          val dockerMappings = dockerHashEntries map { entry => entry.dockerTag -> entry.dockerHash } toMap

          self ! DockerHashStoreLoadingSuccess(dockerMappings)
        case Failure(ex) => self ! DockerHashStoreLoadingFailure(ex)
      }
    }
    super.preStart()
  }

  // Waiting for a response from the database with the hash mapping for this workflow
  when(LoadingCache) {
    case Event(DockerHashStoreLoadingSuccess(dockerHashEntries), data) =>
      loadCache(dockerHashEntries, data)
    case Event(DockerHashStoreLoadingFailure(reason), _) =>
      log.error(reason, "Failed to load docker tag -> hash mappings from DB")
      goto(FailMode)
  }

  // Nothing to do - waiting for requests
  when(Idle) {
    case Event(request: DockerHashRequest, data) =>
      val replyTo = sender()
      useCacheOrLookup(data.enqueue(request, replyTo))
  }

  // Waiting for a response from the DockerHashActor for the current request
  when(WaitingForDockerHashLookup) {
    case Event(dockerResponse: DockerHashSuccessResponse, data: WorkflowDockerLookupActorDataWithRequest) =>
      handleLookupSuccess(dockerResponse, data)
    case Event(dockerResponse: DockerHashFailureResponse, data: WorkflowDockerLookupActorDataWithRequest) =>
      val response = WorkflowDockerLookupFailure(new Exception(dockerResponse.reason), dockerResponse.request)
      replyAndCheckForWork(response, data.withFailureMapping(dockerResponse.request, dockerResponse.reason))
  }

  // Waiting for a mapping to be written to the database
  when(WaitingForDockerHashStore) {
    case Event(DockerHashStoreSuccess(result), data: WorkflowDockerLookupActorDataWithRequest) =>
      // Add the new label to hash mapping to the current set of mappings.
      val newData = data.withNewMapping(data.currentRequest.dockerHashRequest, result.dockerHash)
      replyAndCheckForWork(result, newData)
    case Event(DockerHashStoreFailure(reason), data: WorkflowDockerLookupActorDataWithRequest) =>
      val dockerRequest = data.currentRequest.dockerHashRequest
      replyAndCheckForWork(WorkflowDockerLookupFailure(reason, dockerRequest), data.withFailureMapping(dockerRequest, reason.getMessage))
  }

  whenUnhandled {
    // Requests are enqueued in the data
    case Event(request: DockerHashRequest, data) =>
      stay() using data.enqueue(request, sender())
    case Event(DockerHashActorTimeout, data: WorkflowDockerLookupActorDataWithRequest) =>
      val dockerRequest = data.currentRequest.dockerHashRequest
      val reason = new Exception(s"Timeout looking up docker hash for ${dockerRequest.dockerImageID.fullName}")
      replyAndCheckForWork(WorkflowDockerLookupFailure(reason, dockerRequest), data.withFailureMapping(dockerRequest, reason.getMessage))
  }
  
  // In FailMode we reject all requests
  when(FailMode) {
    case Event(request: DockerHashRequest, _) =>
      sender() ! WorkflowDockerLookupFailure(FailModeException, request)
      stay()
  }
  
  onTransition {
    // When transitioning to FailMode, fail all enqueued requests
    case _ -> FailMode =>
      stateData match {
        case withRequests: WorkflowDockerLookupActorDataWithRequest =>
          val allRequests = withRequests.awaitingDockerRequests :+ withRequests.currentRequest
          allRequests foreach { request =>
            request.replyTo ! WorkflowDockerLookupFailure(FailModeException, request.dockerHashRequest)
          }
        case _ =>
      }
  }

  /**
    * Load mappings from the database into the state data.
    */
  private def loadCache(hashEntries: Map[String, String], data: WorkflowDockerLookupActorData) = {
    val dockerMappingsTry = hashEntries map {
      case (dockerTag, dockerHash) => DockerImageIdentifier.fromString(dockerTag) -> Try(DockerHashResult(dockerHash))
    }

    TryUtil.sequenceKeyValues(dockerMappingsTry) match {
      case Success(dockerMappings) =>
        data.withInitialMappings(dockerMappings) match {
          case withRequests: WorkflowDockerLookupActorDataWithRequest => useCacheOrLookup(withRequests)
          case noRequest: WorkflowDockerLookupActorDataNoRequest => goto(Idle) using noRequest
        }

      case Failure(reason) =>
        log.error(reason, "Failed to load docker tag -> hash mappings from DB")
        goto(FailMode)
    }
  }

  /**
    * Look for the current request in the cache.
    * If it's found reply with that otherwise look the tag up.
    */
  private def useCacheOrLookup(data: WorkflowDockerLookupActorDataWithRequest) = {
    val request = data.currentRequest.dockerHashRequest
    val replyTo = data.currentRequest.replyTo

    data.dockerMappings.get(request.dockerImageID) match {
      case Some(Success(result)) =>
        replyTo ! DockerHashSuccessResponse(result, request)
        stay()
      case Some(Failure(failure)) =>
        replyTo ! WorkflowDockerLookupFailure(failure, request)
        stay()
      case None => lookup(request, data)
    }
  }

  def lookup(request: DockerHashRequest, data: WorkflowDockerLookupActorDataWithRequest) = {
    sendDockerCommand(request)
    goto(WaitingForDockerHashLookup) using data
  }

  def handleLookupSuccess(response: DockerHashSuccessResponse, data: WorkflowDockerLookupActorDataWithRequest) = {
    val dockerHashStoreEntry = DockerHashStoreEntry(workflowId.toString, response.request.dockerImageID.fullName, response.dockerHash.algorithmAndHash)
    databaseInterface.addDockerHashStoreEntries(Seq(dockerHashStoreEntry)) onComplete {
      case Success(_) => self ! DockerHashStoreSuccess(response)
      case Failure(ex) => self ! DockerHashStoreFailure(ex)
    }
    goto(WaitingForDockerHashStore)
  }

  def replyAndCheckForWork(response: Any, data: WorkflowDockerLookupActorDataWithRequest) = {
    data.currentRequest.replyTo ! response
    data.dequeue match {
      case withRequest: WorkflowDockerLookupActorDataWithRequest => useCacheOrLookup(withRequest)
      case noRequest: WorkflowDockerLookupActorDataNoRequest => goto(Idle) using noRequest
    }
  }

  override protected def onTimeout(message: Any, to: ActorRef): Unit = self ! DockerHashActorTimeout
}

object WorkflowDockerLookupActor {
  /* States */
  sealed trait WorkflowDockerLookupActorState
  case object LoadingCache extends WorkflowDockerLookupActorState
  case object Idle extends WorkflowDockerLookupActorState
  case object WaitingForDockerHashLookup extends WorkflowDockerLookupActorState
  case object WaitingForDockerHashStore extends WorkflowDockerLookupActorState
  case object FailMode extends WorkflowDockerLookupActorState
  val FailModeException = new Exception(s"The service responsible for workflow level docker hash resolution failed to restart when the server restarted. Subsequent docker tags for this workflow won't be resolved.")

  /* Internal ADTs */
  case class DockerRequestContext(dockerHashRequest: DockerHashRequest, replyTo: ActorRef)
  sealed trait DockerHashStoreResponse
  case class DockerHashStoreSuccess(successResponse: DockerHashSuccessResponse) extends DockerHashStoreResponse
  case class DockerHashStoreFailure(reason: Throwable) extends DockerHashStoreResponse
  case class DockerHashStoreLoadingSuccess(dockerMappings: Map[String, String])
  case class DockerHashStoreLoadingFailure(reason: Throwable)
  case object DockerHashActorTimeout

  /* Responses */
  final case class WorkflowDockerLookupFailure(reason: Throwable, request: DockerHashRequest)
  
  def props(workflowId: WorkflowId, dockerHashingActor: ActorRef, startMode: StartMode) = {
    Props(new WorkflowDockerLookupActor(workflowId, dockerHashingActor, startMode))
  }

  /* Data */
  object WorkflowDockerLookupActorData {
    def empty = WorkflowDockerLookupActorDataNoRequest(Map.empty)
  }

  sealed trait WorkflowDockerLookupActorData {
    def enqueue(request: DockerHashRequest, replyTo: ActorRef): WorkflowDockerLookupActorDataWithRequest
    def withInitialMappings(dockerMappings: Map[DockerImageIdentifier, DockerHashResult]): WorkflowDockerLookupActorData
  }

  case class WorkflowDockerLookupActorDataNoRequest(dockerMappings: Map[DockerImageIdentifier, Try[DockerHashResult]]) extends WorkflowDockerLookupActorData {
    def enqueue(request: DockerHashRequest, replyTo: ActorRef) = {
      WorkflowDockerLookupActorDataWithRequest(dockerMappings, Queue.empty, DockerRequestContext(request, replyTo))
    }

    def withInitialMappings(dockerMappings: Map[DockerImageIdentifier, DockerHashResult]) = {
      WorkflowDockerLookupActorDataNoRequest(dockerMappings mapValues Success.apply)
    }
  }

  case class WorkflowDockerLookupActorDataWithRequest(dockerMappings: Map[DockerImageIdentifier, Try[DockerHashResult]],
                                                      awaitingDockerRequests: Queue[DockerRequestContext],
                                                      currentRequest: DockerRequestContext) extends WorkflowDockerLookupActorData {

    def withInitialMappings(dockerMappings: Map[DockerImageIdentifier, DockerHashResult]) = {
      this.copy(dockerMappings = dockerMappings mapValues Success.apply)
    }

    def withNewMapping(request: DockerHashRequest, result: DockerHashResult) = {
      this.copy(dockerMappings = dockerMappings + (request.dockerImageID -> Success(result)))
    }
    
    def withFailureMapping(request: DockerHashRequest, reason: String) = {
      this.copy(dockerMappings = dockerMappings + (request.dockerImageID -> Failure(new Exception(reason))))
    }

    def enqueue(request: DockerHashRequest, replyTo: ActorRef) = {
      this.copy(awaitingDockerRequests = awaitingDockerRequests.enqueue(DockerRequestContext(request, replyTo)))
    }

    def dequeue: WorkflowDockerLookupActorData = {
      awaitingDockerRequests.dequeueOption match {
        case Some((next, newQueue)) =>
          this.copy(currentRequest = next, awaitingDockerRequests = newQueue)
        case None =>
          WorkflowDockerLookupActorDataNoRequest(dockerMappings)
      }
    }
  }
}