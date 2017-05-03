package cromwell.engine.workflow

import akka.actor.{ActorRef, LoggingFSM, Props}
import cromwell.core.{Dispatcher, WorkflowId}
import cromwell.database.sql.tables.DockerHashStoreEntry
import cromwell.docker.DockerHashActor.{DockerHashFailureResponse, DockerHashSuccessResponse}
import cromwell.docker.{DockerClientHelper, DockerHashRequest, DockerHashResult, DockerImageIdentifier}
import cromwell.engine.workflow.WorkflowDockerLookupActor._
import cromwell.services.SingletonServicesStore
import lenthall.util.TryUtil

import scala.collection.immutable.Queue
import scala.util.{Failure, Success, Try}

class WorkflowDockerLookupActor(workflowId: WorkflowId, val dockerHashingActor: ActorRef, restart: Boolean)
  extends LoggingFSM[WorkflowDockerLookupActorState, WorkflowDockerLookupActorData] with DockerClientHelper with SingletonServicesStore {

  implicit val ec = context.system.dispatchers.lookup(Dispatcher.EngineDispatcher)

  if (restart) {
    startWith(LoadingCache, WorkflowDockerLookupActorData.empty)
  } else {
    startWith(Idle, WorkflowDockerLookupActorData.empty)
  }

  override def preStart(): Unit = {
    if (restart) {
      databaseInterface.queryDockerHashStoreEntries(workflowId.toString) onComplete {
        case Success(dockerHashEntries) =>
          val dockerMappings = dockerHashEntries map { entry =>
            entry.dockerTag -> entry.dockerHash
          } toMap

          self ! DockerHashStoreLoadingSuccess(dockerMappings)
        case Failure(ex) => self ! DockerHashStoreLoadingFailure(ex)
      }
    }
    super.preStart()
  }

  when(LoadingCache) {
    case Event(DockerHashStoreLoadingSuccess(dockerHashEntries), data) =>
      loadCache(dockerHashEntries, data)
    case Event(DockerHashStoreLoadingFailure(reason), _) =>
      // FIXME: apply the decided policy in case of store loading failure
      log.error(reason, "Failed to load docker tag -> hash mappings from DB")
      context stop self
      stay()
  }

  when(Idle) {
    case Event(request: DockerHashRequest, data) =>
      val replyTo = sender()
      useCacheOrLookup(data.enqueue(request, replyTo))
  }

  when(WaitingForDockerHashLookup) {
    case Event(dockerResponse: DockerHashSuccessResponse, data: WorkflowDockerLookupActorDataWithRequest) =>
      handleLookupSuccess(dockerResponse, data)
    case Event(dockerResponse: DockerHashFailureResponse, data: WorkflowDockerLookupActorDataWithRequest) =>
      // FIXME: apply the decided policy in case of lookup failure
      replyAndCheckForWork(dockerResponse, data)
  }

  when(WaitingForDockerHashStore) {
    case Event(DockerHashStoreSuccess(result), data: WorkflowDockerLookupActorDataWithRequest) =>
      replyAndCheckForWork(result, data)
    case Event(DockerHashStoreFailure(reason), data: WorkflowDockerLookupActorDataWithRequest) =>
      // FIXME: apply the decided policy in case of store reading failure
      replyAndCheckForWork(DockerLookupStorageFailure(data.currentRequest.dockerHashRequest, reason), data)
  }

  whenUnhandled {
    case Event(request: DockerHashRequest, data) =>
      stay() using data.enqueue(request, sender())
    case Event(DockerHashActorTimeout, data: WorkflowDockerLookupActorDataWithRequest) =>
      // FIXME: apply the decided policy in case of lookup failure
      replyAndCheckForWork(DockerLookupTimeout(data.currentRequest.dockerHashRequest), data)
  }

  def loadCache(hashEntries: Map[String, String], data: WorkflowDockerLookupActorData) = {
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
        // FIXME: apply the decided policy in case of store loading failure
        log.error(reason, "Failed to load docker tag -> hash mappings from DB")
        context stop self
        stay()
    }
  }

  def useCacheOrLookup(data: WorkflowDockerLookupActorDataWithRequest) = {
    val request = data.currentRequest.dockerHashRequest
    val replyTo = data.currentRequest.replyTo

    data.dockerMappings.get(request.dockerImageID) match {
      case Some(result) =>
        replyTo ! DockerHashSuccessResponse(result, request)
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

  /* Internal ADTs */
  case class DockerRequestContext(dockerHashRequest: DockerHashRequest, replyTo: ActorRef)
  sealed trait DockerHashStoreResponse
  case class DockerHashStoreSuccess(successResponse: DockerHashSuccessResponse) extends DockerHashStoreResponse
  case class DockerHashStoreFailure(reason: Throwable) extends DockerHashStoreResponse
  case class DockerHashStoreLoadingSuccess(dockerMappings: Map[String, String])
  case class DockerHashStoreLoadingFailure(reason: Throwable)
  case object DockerHashActorTimeout

  /* Responses */
  sealed trait WorkflowLookupFailure
  final case class DockerLookupLoadingFailure(reason: Throwable) extends WorkflowLookupFailure
  final case class DockerLookupStorageFailure(request: DockerHashRequest, reason: Throwable) extends WorkflowLookupFailure
  final case class DockerLookupTimeout(request: DockerHashRequest) extends WorkflowLookupFailure
  
  def props(workflowId: WorkflowId, dockerHashingActor: ActorRef, restart: Boolean) = {
    Props(new WorkflowDockerLookupActor(workflowId, dockerHashingActor, restart))
  }

  /* Data */
  object WorkflowDockerLookupActorData {
    def empty = WorkflowDockerLookupActorDataNoRequest(Map.empty)
  }

  sealed trait WorkflowDockerLookupActorData {
    def enqueue(request: DockerHashRequest, replyTo: ActorRef): WorkflowDockerLookupActorDataWithRequest
    def withInitialMappings(dockerMappings: Map[DockerImageIdentifier, DockerHashResult]): WorkflowDockerLookupActorData
  }

  case class WorkflowDockerLookupActorDataNoRequest(dockerMappings: Map[DockerImageIdentifier, DockerHashResult]) extends WorkflowDockerLookupActorData {
    def enqueue(request: DockerHashRequest, replyTo: ActorRef) = {
      WorkflowDockerLookupActorDataWithRequest(dockerMappings, Queue.empty, DockerRequestContext(request, replyTo))
    }

    def withInitialMappings(dockerMappings: Map[DockerImageIdentifier, DockerHashResult]) = {
      WorkflowDockerLookupActorDataNoRequest(dockerMappings)
    }
  }

  case class WorkflowDockerLookupActorDataWithRequest(dockerMappings: Map[DockerImageIdentifier, DockerHashResult],
                                                      awaitingDockerRequests: Queue[DockerRequestContext],

                                                      currentRequest: DockerRequestContext) extends WorkflowDockerLookupActorData {

    def withInitialMappings(dockerMappings: Map[DockerImageIdentifier, DockerHashResult]) = {
      this.copy(dockerMappings = dockerMappings)
    }

    def withNewMapping(request: DockerHashRequest, result: DockerHashResult) = {
      this.copy(dockerMappings = dockerMappings + (request.dockerImageID -> result))
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