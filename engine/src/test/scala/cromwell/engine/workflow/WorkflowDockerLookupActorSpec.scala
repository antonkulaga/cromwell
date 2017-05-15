package cromwell.engine.workflow

import akka.actor.{ActorRef, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestProbe}
import cromwell.core.actor.StreamIntegration.BackPressure
import cromwell.core.{TestKitSuite, WorkflowId}
import cromwell.docker.DockerHashActor.DockerHashSuccessResponse
import cromwell.docker.{DockerHashRequest, DockerHashResult, DockerImageIdentifier, DockerImageIdentifierWithoutHash}
import cromwell.engine.workflow.WorkflowActor.{StartMode, StartNewWorkflow}
import cromwell.engine.workflow.WorkflowDockerLookupActor.{DockerHashActorTimeout, WorkflowDockerLookupFailure}
import cromwell.engine.workflow.WorkflowDockerLookupActorSpec.TestWorkflowDockerLookupActor
import org.scalatest.{BeforeAndAfter, FlatSpecLike, Matchers}
import org.specs2.mock.Mockito

import scala.concurrent.duration._
import scala.language.postfixOps


class WorkflowDockerLookupActorSpec extends TestKitSuite("WorkflowDockerLookupActorSpecSystem") with FlatSpecLike with Matchers with ImplicitSender with BeforeAndAfter with Mockito {
  it should "wait and resubmit the docker request when it gets a backpressure message" in {
    val dockerValue = "ubuntu:latest"
    val dockerId = DockerImageIdentifier.fromString(dockerValue).get.asInstanceOf[DockerImageIdentifierWithoutHash]

    val workflowId = WorkflowId.randomId()
    val dockerHashingActor = TestProbe()
    val backpressureWaitTime = 2 seconds

    val lookupActor = TestActorRef(Props(new TestWorkflowDockerLookupActor(workflowId, dockerHashingActor.ref, StartNewWorkflow, backpressureWaitTime)), self)
    val request = DockerHashRequest(dockerId)
    lookupActor ! request

    dockerHashingActor.expectMsg(request)
    dockerHashingActor.reply(BackPressure(request))
    // Give a couple of seconds of margin to account for test latency etc...
    dockerHashingActor.expectMsg(backpressureWaitTime.+(2 seconds), request)
  }

  it should "not look up the same tag again after a successful lookup" in {
    val dockerValue = "ubuntu:latest"
    val dockerId = DockerImageIdentifier.fromString(dockerValue).get.asInstanceOf[DockerImageIdentifierWithoutHash]

    val workflowId = WorkflowId.randomId()
    val dockerHashingActor = TestProbe()

    val lookupActor = TestActorRef(Props(new WorkflowDockerLookupActor(workflowId, dockerHashingActor.ref, StartNewWorkflow)), self)
    val request = DockerHashRequest(dockerId)
    lookupActor ! request
    val response = DockerHashSuccessResponse(DockerHashResult("md5", "AAAAAAAA"), request)

    // The WorkflowDockerLookupActor should not have the hash for this tag yet and will need to query the dockerHashingActor.
    dockerHashingActor.expectMsg(request)
    dockerHashingActor.reply(response)
    // The WorkflowDockerLookupActor should forward the success message to this actor.
    expectMsg(response)

    // Now the WorkflowDockerLookupActor should now have this hash in its mappings and should not query the dockerHashingActor again.
    dockerHashingActor.expectNoMsg()
    lookupActor ! request
    // The WorkflowDockerLookupActor should forward the success message to this actor.
    expectMsg(response)
  }

  it should "soldier on after docker hashing actor timeouts" in {
    val latestDockerId = DockerImageIdentifier.fromString("ubuntu:latest").get.asInstanceOf[DockerImageIdentifierWithoutHash]
    val olderDockerId = DockerImageIdentifier.fromString("ubuntu:older").get.asInstanceOf[DockerImageIdentifierWithoutHash]

    val workflowId = WorkflowId.randomId()
    val dockerHashingActor = TestProbe()

    val lookupActor = TestActorRef(Props(new WorkflowDockerLookupActor(workflowId, dockerHashingActor.ref, StartNewWorkflow)), self)
    val latestRequest = DockerHashRequest(latestDockerId)
    val olderRequest = DockerHashRequest(olderDockerId)

    lookupActor ! latestRequest
    lookupActor ! olderRequest

    val timeoutReason = "request timed out"
    val timeout = DockerHashActorTimeout(timeoutReason)

    // The WorkflowDockerLookupActor should not have the hash for this tag yet and will need to query the dockerHashingActor.
    dockerHashingActor.expectMsg(latestRequest)
    dockerHashingActor.expectMsg(olderRequest)
    dockerHashingActor.reply(timeout)
    // This actor should see failure messages for the pending requests.
    val expectedMessage = "Timeout looking up docker hash: " + timeoutReason
    val failedRequests = Set(1, 2) map { _ =>
      expectMsgPF(2 seconds) {
        case msg: WorkflowDockerLookupFailure if msg.reason.getMessage == expectedMessage => msg.request
      }
    }

    Set(latestRequest, olderRequest) should equal(failedRequests)

    // Try again.  The hashing actor should receive the messages and this time won't time out.
    lookupActor ! latestRequest
    lookupActor ! olderRequest
    dockerHashingActor.expectMsg(latestRequest)
    dockerHashingActor.expectMsg(olderRequest)
    val latestResponse = DockerHashSuccessResponse(DockerHashResult("md5", "AAAAAAAA"), latestRequest)
    val olderResponse = DockerHashSuccessResponse(DockerHashResult("md5", "BBBBBBBB"), olderRequest)
    dockerHashingActor.reply(latestResponse)
    dockerHashingActor.reply(olderResponse)

    val hashResponses = Set(1, 2) map { _ =>
      expectMsgPF(2 seconds) {
        case msg: DockerHashSuccessResponse => msg
      }
    }

    // Success after transient timeout failures:
    hashResponses should equal(Set(latestResponse, olderResponse))
  }
}


object WorkflowDockerLookupActorSpec {
  class TestWorkflowDockerLookupActor(workflowId: WorkflowId, dockerHashingActor: ActorRef, startMode: StartMode, override val backpressureTimeout: FiniteDuration)
    extends WorkflowDockerLookupActor(workflowId, dockerHashingActor, startMode)
}
