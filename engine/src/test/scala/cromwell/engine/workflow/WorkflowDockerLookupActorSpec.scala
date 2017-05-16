package cromwell.engine.workflow

import akka.actor.{ActorRef, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestProbe}
import com.typesafe.config.ConfigFactory
import cromwell.core.actor.StreamIntegration.BackPressure
import cromwell.core.{TestKitSuite, WorkflowId}
import cromwell.database.slick.SlickDatabase
import cromwell.database.sql.tables.DockerHashStoreEntry
import cromwell.docker.DockerHashActor.{DockerHashFailedResponse, DockerHashSuccessResponse}
import cromwell.docker.{DockerHashRequest, DockerHashResult, DockerImageIdentifier, DockerImageIdentifierWithoutHash}
import cromwell.engine.workflow.WorkflowActor.{RestartExistingWorkflow, StartMode, StartNewWorkflow}
import cromwell.engine.workflow.WorkflowDockerLookupActor.{DockerHashActorTimeout, WorkflowDockerLookupFailure}
import cromwell.engine.workflow.WorkflowDockerLookupActorSpec.TestWorkflowDockerLookupActor
import cromwell.services.ServicesStore._
import cromwell.services.SingletonServicesStore
import org.scalatest.{BeforeAndAfter, FlatSpecLike, Matchers}
import org.specs2.mock.Mockito

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
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

    val databaseConfig = ConfigFactory.load.getConfig("database")

    var numWrites = 0

    val databaseInterface = new SlickDatabase(databaseConfig) {

      override def queryDockerHashStoreEntries(workflowExecutionUuid: String)
                                              (implicit ec: ExecutionContext): Future[Seq[DockerHashStoreEntry]] =
        throw new RuntimeException("should not be called, this workflow is not restarting")

      override def addDockerHashStoreEntry(dockerHashStoreEntry: DockerHashStoreEntry)(implicit ec: ExecutionContext): Future[Unit] = {
        numWrites = numWrites + 1
        Future.successful(())
      }
    }.initialized

    val lookupActor = TestActorRef(WorkflowDockerLookupActor.props(workflowId, dockerHashingActor.ref, StartNewWorkflow, databaseInterface))
    val request = DockerHashRequest(dockerId)
    lookupActor ! request
    val response = DockerHashSuccessResponse(DockerHashResult("md5", "AAAAAAAA"), request)

    // The WorkflowDockerLookupActor should not have the hash for this tag yet and will need to query the dockerHashingActor.
    dockerHashingActor.expectMsg(request)
    dockerHashingActor.reply(response)
    // The WorkflowDockerLookupActor should forward the success message to this actor.
    expectMsg(response)
    numWrites should equal(1)

    // Now the WorkflowDockerLookupActor should now have this hash in its mappings and should not query the dockerHashingActor again.
    dockerHashingActor.expectNoMsg()
    lookupActor ! request
    // The WorkflowDockerLookupActor should forward the success message to this actor.
    expectMsg(response)
    numWrites should equal(1)
  }

  it should "soldier on after docker hashing actor timeouts" in {
    val latestDockerId = DockerImageIdentifier.fromString("ubuntu:latest").get.asInstanceOf[DockerImageIdentifierWithoutHash]
    val olderDockerId = DockerImageIdentifier.fromString("ubuntu:older").get.asInstanceOf[DockerImageIdentifierWithoutHash]

    val workflowId = WorkflowId.randomId()
    val dockerHashingActor = TestProbe()

    val lookupActor = TestActorRef(WorkflowDockerLookupActor.props(workflowId, dockerHashingActor.ref, StartNewWorkflow))
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

  it should "respond appropriately to docker hash lookup failures" in {
    val latestDockerId = DockerImageIdentifier.fromString("ubuntu:latest").get.asInstanceOf[DockerImageIdentifierWithoutHash]
    val olderDockerId = DockerImageIdentifier.fromString("ubuntu:older").get.asInstanceOf[DockerImageIdentifierWithoutHash]

    val workflowId = WorkflowId.randomId()
    val dockerHashingActor = TestProbe()

    val lookupActor = TestActorRef(WorkflowDockerLookupActor.props(workflowId, dockerHashingActor.ref, StartNewWorkflow))
    val latestRequest = DockerHashRequest(latestDockerId)
    val olderRequest = DockerHashRequest(olderDockerId)

    lookupActor ! latestRequest
    lookupActor ! olderRequest

    // The WorkflowDockerLookupActor should not have the hash for this tag yet and will need to query the dockerHashingActor.
    dockerHashingActor.expectMsg(latestRequest)
    dockerHashingActor.expectMsg(olderRequest)
    val latestSuccessResponse = DockerHashSuccessResponse(DockerHashResult("md5", "AAAAAAAA"), latestRequest)
    val olderFailedResponse = DockerHashFailedResponse(new RuntimeException("Lookup failed"), olderRequest)

    dockerHashingActor.reply(latestSuccessResponse)
    dockerHashingActor.reply(olderFailedResponse)

    val mixedResponses = Set(1, 2) map { _ =>
      expectMsgPF(2 seconds) {
        case msg: DockerHashSuccessResponse => msg
        // Scoop out the request here since we can't match the exception on the whole message.
        case msg: WorkflowDockerLookupFailure if msg.reason.getMessage == "Failed to get docker hash for library/ubuntu:older Lookup failed" => msg.request
      }
    }

    Set(latestSuccessResponse, olderRequest) should equal(mixedResponses)

    // Try again, I have a good feeling about this.
    lookupActor ! olderRequest
    dockerHashingActor.expectMsg(olderRequest)
    val olderSuccessResponse = DockerHashSuccessResponse(DockerHashResult("md5", "BBBBBBBB"), olderRequest)
    dockerHashingActor.reply(olderSuccessResponse)
    expectMsg(olderSuccessResponse)
  }

  it should "try to look up hashes on restart" in {

    val workflowId = WorkflowId.randomId()
    val dockerHashingActor = TestProbe()

    val databaseConfig = ConfigFactory.load.getConfig("database")

    val databaseInterface = new SlickDatabase(databaseConfig) {
      override def queryDockerHashStoreEntries(workflowExecutionUuid: String)
                                              (implicit ec: ExecutionContext): Future[Seq[DockerHashStoreEntry]] = Future.successful {
        Seq(
          DockerHashStoreEntry(workflowId.toString, "ubuntu:latest", "md5:AAAAA"),
          DockerHashStoreEntry(workflowId.toString, "ubuntu:yesterday", "md5:BBBBB")
        )
      }

      override def addDockerHashStoreEntry(dockerHashStoreEntry: DockerHashStoreEntry)(implicit ec: ExecutionContext): Future[Unit] =
        throw new RuntimeException("This should not be called in this test, all the hashes requested should be present in the DB already!")
    }.initialized

    val lookupActor = TestActorRef(WorkflowDockerLookupActor.props(workflowId, dockerHashingActor.ref, RestartExistingWorkflow, databaseInterface))

    val latestDockerValue = "ubuntu:latest"
    val latestDockerId = DockerImageIdentifier.fromString(latestDockerValue).get.asInstanceOf[DockerImageIdentifierWithoutHash]
    val latestDockerRequest = DockerHashRequest(latestDockerId)
    lookupActor ! latestDockerRequest

    val yesterdaysDockerValue = "ubuntu:yesterday"
    val yesterdaysDockerId = DockerImageIdentifier.fromString(yesterdaysDockerValue).get.asInstanceOf[DockerImageIdentifierWithoutHash]
    val yesterdaysDockerRequest = DockerHashRequest(yesterdaysDockerId)
    lookupActor ! yesterdaysDockerRequest

    dockerHashingActor.expectNoMsg()

    val results = Set(1, 2) map { _ =>
      expectMsgPF(2 seconds) {
        case result: DockerHashSuccessResponse => result
      }
    }

    results should equal(Set(
      DockerHashSuccessResponse(DockerHashResult("md5:AAAAA"), latestDockerRequest),
      DockerHashSuccessResponse(DockerHashResult("md5:BBBBB"), yesterdaysDockerRequest)))
  }

  it should "not try to look up hashes if not restarting" in {

    val workflowId = WorkflowId.randomId()
    val dockerHashingActor = TestProbe()

    val databaseConfig = ConfigFactory.load.getConfig("database")

    val databaseInterface = new SlickDatabase(databaseConfig) {
      override def queryDockerHashStoreEntries(workflowExecutionUuid: String)
                                              (implicit ec: ExecutionContext): Future[Seq[DockerHashStoreEntry]] =
        throw new RuntimeException("Should not query for docker hashes if not restarting!")
    }.initialized

    val lookupActor = TestActorRef(WorkflowDockerLookupActor.props(workflowId, dockerHashingActor.ref, StartNewWorkflow, databaseInterface))

    val latestDockerValue = "ubuntu:latest"
    val latestDockerId = DockerImageIdentifier.fromString(latestDockerValue).get.asInstanceOf[DockerImageIdentifierWithoutHash]
    val latestDockerRequest = DockerHashRequest(latestDockerId)

    val yesterdaysDockerValue = "ubuntu:yesterday"
    val yesterdaysDockerId = DockerImageIdentifier.fromString(yesterdaysDockerValue).get.asInstanceOf[DockerImageIdentifierWithoutHash]
    val yesterdaysDockerRequest = DockerHashRequest(yesterdaysDockerId)

    lookupActor ! latestDockerRequest
    lookupActor ! yesterdaysDockerRequest

    dockerHashingActor.expectMsg(latestDockerRequest)
    dockerHashingActor.expectMsg(yesterdaysDockerRequest)
    val latestSuccessResponse = DockerHashSuccessResponse(DockerHashResult("md5", "AAAAA"), latestDockerRequest)
    val yesterdaysSuccessResponse = DockerHashSuccessResponse(DockerHashResult("md5", "BBBBB"), yesterdaysDockerRequest)
    dockerHashingActor.reply(latestSuccessResponse)
    dockerHashingActor.reply(yesterdaysSuccessResponse)

    val results = Set(1, 2) map { _ =>
      expectMsgPF(2 seconds) {
        case result: DockerHashSuccessResponse => result
      }
    }

    results should equal(Set(latestSuccessResponse, yesterdaysSuccessResponse))
  }

  it should "handle hash write errors appropriately" in {
    val dockerValue = "ubuntu:latest"
    val dockerId = DockerImageIdentifier.fromString(dockerValue).get.asInstanceOf[DockerImageIdentifierWithoutHash]

    val workflowId = WorkflowId.randomId()
    val dockerHashingActor = TestProbe()

    val databaseConfig = ConfigFactory.load.getConfig("database")

    var numWrites = 0

    val databaseInterface = new SlickDatabase(databaseConfig) {

      override def queryDockerHashStoreEntries(workflowExecutionUuid: String)
                                              (implicit ec: ExecutionContext): Future[Seq[DockerHashStoreEntry]] =
        throw new RuntimeException("should not be called, this workflow is not restarting")

      override def addDockerHashStoreEntry(dockerHashStoreEntry: DockerHashStoreEntry)(implicit ec: ExecutionContext): Future[Unit] = {
        numWrites = numWrites + 1
        if (numWrites == 1) Future.failed(new RuntimeException("Fake exception from a test.")) else Future.successful(())
      }
    }.initialized

    val lookupActor = TestActorRef(WorkflowDockerLookupActor.props(workflowId, dockerHashingActor.ref, StartNewWorkflow, databaseInterface))
    val request = DockerHashRequest(dockerId)
    lookupActor ! request
    val response = DockerHashSuccessResponse(DockerHashResult("md5", "AAAAAAAA"), request)

    // The WorkflowDockerLookupActor should not have the hash for this tag yet and will need to query the dockerHashingActor.
    dockerHashingActor.expectMsg(request)
    dockerHashingActor.reply(response)
    // The WorkflowDockerLookupActor is going to fail when it tries to write to that broken DB.
    expectMsgPF(2 seconds) {
      case fail: WorkflowDockerLookupFailure =>
    }
    numWrites should equal(1)

    lookupActor ! request
    // The WorkflowDockerLookupActor will query the dockerHashingActor again.
    dockerHashingActor.expectMsg(request)
    dockerHashingActor.reply(response)
    // The WorkflowDockerLookupActor should forward the success message to this actor.
    expectMsg(response)
    numWrites should equal(2)
  }
}


object WorkflowDockerLookupActorSpec {

  class TestWorkflowDockerLookupActor(workflowId: WorkflowId, dockerHashingActor: ActorRef, startMode: StartMode, override val backpressureTimeout: FiniteDuration)
    extends WorkflowDockerLookupActor(workflowId, dockerHashingActor, startMode, SingletonServicesStore.databaseInterface)

}
