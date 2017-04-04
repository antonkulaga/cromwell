package cromwell.engine.workflow

import akka.actor.{ActorRef, LoggingFSM}
import cromwell.core.WorkflowId
import cromwell.core.callcaching.docker.DockerHashActor.{DockerHashFailureResponse, DockerHashSuccessResponse}
import cromwell.core.callcaching.docker._
import cromwell.database.sql.tables.DockerHashStoreEntry
import cromwell.engine.workflow.WorkflowDockerLookupActor._
import cromwell.services.SingletonServicesStore

import scala.collection.immutable.Queue
import scala.language.postfixOps
import scala.util.{Failure, Success}

class WorkflowDockerLookupActor(workflowId: WorkflowId, val dockerHashingActor: ActorRef, restart: Boolean) 
  extends LoggingFSM[WorkflowDockerLookupActorState, WorkflowDockerLookupActorData] with DockerClientHelper with SingletonServicesStore {
  
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
          
          self ! DockerHashStoreQuerySuccess(dockerMappings)
        case Failure(ex) => self ! DockerHashStoreQueryFailure(ex)
      }
    }
    super.preStart()
  }

  when(LoadingCache) {
    case Event(DockerHashStoreQuerySuccess(dockerHashEntries), data) =>
      loadCache(dockerHashEntries)
    case Event(DockerHashStoreQueryFailure(reason), _) =>
      throw reason
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
      replyAndCheckForWork(dockerResponse, data)
  }

  when(WaitingForDockerHashStore) {
    case Event(DockerHashStoreSuccess(result), data: WorkflowDockerLookupActorDataWithRequest) =>
      replyAndCheckForWork(result, data)
    case Event(DockerHashStoreFailure(reason), data: WorkflowDockerLookupActorDataWithRequest) =>
      replyAndCheckForWork(DockerLookupStorageFailure(data.currentRequest.dockerHashRequest, reason), data)
  }
  
  whenUnhandled {
    case Event(request: DockerHashRequest, data) =>
      stay() using data.enqueue(request, sender())
    case Event(DockerHashActorTimeout, data: WorkflowDockerLookupActorDataWithRequest) =>
      replyAndCheckForWork(DockerLookupTimeout(data.currentRequest.dockerHashRequest), data)
  }
  
  def loadCache(hashEntries: Map[String, String]) = { hashEntries map {
    case (dockerTag, dockerHash) => DockerImageIdentifier.fromString(dockerTag) -> DockerHashResult(dockerHash)
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
  private sealed trait WorkflowDockerLookupActorState
  private case object LoadingCache extends WorkflowDockerLookupActorState
  private case object Idle extends WorkflowDockerLookupActorState
  private case object WaitingForDockerHashLookup extends WorkflowDockerLookupActorState
  private case object WaitingForDockerHashStore extends WorkflowDockerLookupActorState

  /* Internal ADTs */
  private case class DockerRequestContext(dockerHashRequest: DockerHashRequest, replyTo: ActorRef)
  private sealed trait DockerHashStoreResponse
  private case class DockerHashStoreSuccess(successResponse: DockerHashSuccessResponse) extends DockerHashStoreResponse
  private case class DockerHashStoreFailure(reason: Throwable) extends DockerHashStoreResponse
  private case class DockerHashStoreQuerySuccess(dockerMappings: Map[String, String])
  private case class DockerHashStoreQueryFailure(reason: Throwable)
  private case object DockerHashActorTimeout
  
  /* Responses */
  sealed trait WorkflowLookupFailure
  final case class DockerLookupStorageFailure(request: DockerHashRequest, reason: Throwable) extends WorkflowLookupFailure
  final case class DockerLookupTimeout(request: DockerHashRequest) extends WorkflowLookupFailure
  
  /* Data */
  private object WorkflowDockerLookupActorData {
    def empty = WorkflowDockerLookupActorDataNoRequest(Map.empty)
  }

  private sealed trait WorkflowDockerLookupActorData {
    def enqueue(request: DockerHashRequest, replyTo: ActorRef): WorkflowDockerLookupActorDataWithRequest
  }

  private case class WorkflowDockerLookupActorDataNoRequest(dockerMappings: Map[DockerImageIdentifierWithoutHash, DockerHashResult]) extends WorkflowDockerLookupActorData {
    def enqueue(request: DockerHashRequest, replyTo: ActorRef) = {
      WorkflowDockerLookupActorDataWithRequest(dockerMappings, Queue.empty, DockerRequestContext(request, replyTo))
    } 
  }

  private case class WorkflowDockerLookupActorDataWithRequest(dockerMappings: Map[DockerImageIdentifierWithoutHash, DockerHashResult],
                                                              awaitingDockerRequests: Queue[DockerRequestContext],
                                                              currentRequest: DockerRequestContext) extends WorkflowDockerLookupActorData {
    
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