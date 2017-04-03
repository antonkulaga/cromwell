package cromwell.engine.workflow

import akka.actor.{ActorRef, FSM}
import cromwell.core.WorkflowId
import cromwell.core.callcaching.docker.DockerHashActor.DockerHashResponseSuccess
import cromwell.core.callcaching.docker.{DockerClientHelper, DockerHashRequest, DockerHashResult}
import cromwell.database.sql.tables.DockerHashStoreEntry
import cromwell.engine.workflow.WorkflowDockerLookupActor._
import cromwell.services.SingletonServicesStore

import scala.collection.immutable.Queue
import scala.util.{Failure, Success}

class WorkflowDockerLookupActor(workflowId: WorkflowId, val dockerHashingActor: ActorRef, restart: Boolean) 
  extends FSM[WorkflowDockerLookupActorState, WorkflowDockerLookupActorData] with DockerClientHelper with SingletonServicesStore {
  startWith(Idle, WorkflowDockerLookupActorDataNoRequest(Map.empty))
  
  when(Idle) {
    case Event(request: DockerHashRequest, data) =>
      val replyTo = sender()
      useCacheOrLookup(data.enqueue(request, replyTo))
  }
  
  when(WaitingForDockerHashLookup) {
    case Event(dockerResponse: DockerHashResponseSuccess, data: WorkflowDockerLookupActorDataWithRequest) =>
      handleLookupSuccess(dockerResponse, data)
  }

  when(WaitingForDockerHashStore) {
    case Event(DockerHashStoreSuccess(result), data: WorkflowDockerLookupActorDataWithRequest) =>
      handleHashStoreSuccess(result, data)
  }
  
  whenUnhandled {
    case Event(request: DockerHashRequest, data) =>
      stay() using data.enqueue(request, sender())
  }
  
  def useCacheOrLookup(data: WorkflowDockerLookupActorDataWithRequest) = {
    val request = data.currentRequest.dockerHashRequest
    val replyTo = data.currentRequest.replyTo
    
    data.dockerMappings.get(request) match {
      case Some(result) =>
        replyTo ! result
        stay()
      case None => lookup(request, data)
    }
  }
  
  def lookup(request: DockerHashRequest, data: WorkflowDockerLookupActorDataWithRequest) = {
    sendDockerCommand(request)
    goto(WaitingForDockerHashLookup) using data
  }
  
  def handleLookupSuccess(response: DockerHashResponseSuccess, data: WorkflowDockerLookupActorDataWithRequest) = {
    val dockerHashStoreEntry = DockerHashStoreEntry(workflowId.toString, response.request.dockerImageID.fullName, response.dockerHash.algorithmAndHash)
    databaseInterface.addDockerHashStoreEntries(Seq(dockerHashStoreEntry)) onComplete {
      case Success(_) => self ! DockerHashStoreSuccess(response.dockerHash)
      case Failure(ex) => self ! DockerHashStoreFailure(ex)
    }
    goto(WaitingForDockerHashStore)
  }
  
  def handleHashStoreSuccess(result: DockerHashResult, data: WorkflowDockerLookupActorDataWithRequest) = {
    data.currentRequest.replyTo ! result
    data.dequeue match {
      case withRequest: WorkflowDockerLookupActorDataWithRequest => useCacheOrLookup(withRequest)
      case noRequest: WorkflowDockerLookupActorDataNoRequest => goto(Idle) using noRequest
    }
  }

  override protected def onTimeout(message: Any, to: ActorRef): Unit = ???
}

object WorkflowDockerLookupActor {
  /* States */
  sealed trait WorkflowDockerLookupActorState
  case object Idle extends WorkflowDockerLookupActorState
  case object WaitingForDockerHashLookup extends WorkflowDockerLookupActorState
  case object WaitingForDockerHashStore extends WorkflowDockerLookupActorState

  /* Internal ADTs */
  case class DockerRequestContext(dockerHashRequest: DockerHashRequest, replyTo: ActorRef)
  sealed trait DockerHashStoreResponse
  case class DockerHashStoreSuccess(hashResult: DockerHashResult) extends DockerHashStoreResponse
  case class DockerHashStoreFailure(reason: Throwable) extends DockerHashStoreResponse
  
  /* Data */
  object WorkflowDockerLookupActorData {
    def empty = WorkflowDockerLookupActorDataNoRequest(Map.empty)
  }

  sealed trait WorkflowDockerLookupActorData {
    def enqueue(request: DockerHashRequest, replyTo: ActorRef): WorkflowDockerLookupActorDataWithRequest
  }
  
  case class WorkflowDockerLookupActorDataNoRequest(dockerMappings: Map[DockerHashRequest, DockerHashResult]) extends WorkflowDockerLookupActorData {
    def enqueue(request: DockerHashRequest, replyTo: ActorRef) = {
      WorkflowDockerLookupActorDataWithRequest(dockerMappings, Queue.empty, DockerRequestContext(request, replyTo))
    } 
  }
  
  case class WorkflowDockerLookupActorDataWithRequest(dockerMappings: Map[DockerHashRequest, DockerHashResult],
                                                      awaitingDockerRequests: Queue[DockerRequestContext],
                                                      currentRequest: DockerRequestContext) extends WorkflowDockerLookupActorData {
    
    def withNewMapping(request: DockerHashRequest, result: DockerHashResult) = {
      this.copy(dockerMappings = dockerMappings + (request -> result))
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