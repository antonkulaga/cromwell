package cromwell.engine.workflow

import akka.actor.{ActorRef, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestProbe}
import cromwell.core.actor.StreamIntegration.BackPressure
import cromwell.core.{TestKitSuite, WorkflowId}
import cromwell.docker.{DockerHashRequest, DockerImageIdentifier, DockerImageIdentifierWithoutHash}
import cromwell.engine.workflow.WorkflowActor.{StartMode, StartNewWorkflow}
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
}


object WorkflowDockerLookupActorSpec {
  class TestWorkflowDockerLookupActor(workflowId: WorkflowId, dockerHashingActor: ActorRef, startMode: StartMode, override val backpressureTimeout: FiniteDuration)
    extends WorkflowDockerLookupActor(workflowId, dockerHashingActor, startMode)
}