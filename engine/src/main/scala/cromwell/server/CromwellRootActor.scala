package cromwell.server

import akka.actor.SupervisorStrategy.{Escalate, Restart}
import akka.actor.{Actor, ActorInitializationException, ActorRef, OneForOneStrategy}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.routing.RoundRobinPool
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import cromwell.core.{Dispatcher, DockerConfiguration, DockerLocalLookup, DockerRemoteLookup}
import cromwell.core.actor.StreamActorHelper.ActorRestartException
import cromwell.docker.DockerHashActor
import cromwell.docker.DockerHashActor.DockerHashContext
import cromwell.docker.local.DockerCliFlow
import cromwell.docker.registryv2.flows.HttpFlowWithRetry.ContextWithRequest
import cromwell.docker.registryv2.flows.dockerhub.DockerHubFlow
import cromwell.docker.registryv2.flows.gcr.GoogleFlow
import cromwell.core.io.Throttle
import cromwell.docker.registryv2.flows.quay.QuayFlow
import cromwell.engine.backend.{BackendSingletonCollection, CromwellBackends}
import cromwell.engine.io.IoActor
import cromwell.engine.workflow.WorkflowManagerActor
import cromwell.engine.workflow.lifecycle.CopyWorkflowLogsActor
import cromwell.engine.workflow.lifecycle.execution.callcaching.{CallCache, CallCacheReadActor, CallCacheWriteActor}
import cromwell.engine.workflow.tokens.JobExecutionTokenDispenserActor
import cromwell.engine.workflow.workflowstore.{SqlWorkflowStore, WorkflowStore, WorkflowStoreActor}
import cromwell.jobstore.{JobStore, JobStoreActor, SqlJobStore}
import cromwell.services.{ServiceRegistryActor, SingletonServicesStore}
import cromwell.subworkflowstore.{SqlSubWorkflowStore, SubWorkflowStoreActor}
import net.ceedubs.ficus.Ficus._

import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * An actor which serves as the lord protector for the rest of Cromwell, allowing us to have more fine grain
  * control on top level supervision, etc.
  *
  * For now there are only two (non-test) entry points into Cromwell - either using SingleWorkflowRunnerActor or CromwellServerActor.
  * This is intended to be extended by those entry points.
  *
  * If any of the actors created by CromwellRootActor fail to initialize the ActorSystem will die, which means that
  * Cromwell will fail to start in a bad state regardless of the entry point.
  */
 abstract class CromwellRootActor(implicit materializer: ActorMaterializer) extends Actor {
  import CromwellRootActor._

  private val logger = Logging(context.system, this)
  private val config = ConfigFactory.load()
  private implicit val system = context.system
  
  val serverMode: Boolean

  lazy val systemConfig = config.getConfig("system")
  lazy val serviceRegistryActor: ActorRef = context.actorOf(ServiceRegistryActor.props(config), "ServiceRegistryActor")
  lazy val numberOfWorkflowLogCopyWorkers = systemConfig.as[Option[Int]]("number-of-workflow-log-copy-workers").getOrElse(DefaultNumberOfWorkflowLogCopyWorkers)

  lazy val workflowStore: WorkflowStore = SqlWorkflowStore(SingletonServicesStore.databaseInterface)
  lazy val workflowStoreActor = context.actorOf(WorkflowStoreActor.props(workflowStore, serviceRegistryActor), "WorkflowStoreActor")

  lazy val jobStore: JobStore = new SqlJobStore(SingletonServicesStore.databaseInterface)
  lazy val jobStoreActor = context.actorOf(JobStoreActor.props(jobStore), "JobStoreActor")

  lazy val subWorkflowStore = new SqlSubWorkflowStore(SingletonServicesStore.databaseInterface)
  lazy val subWorkflowStoreActor = context.actorOf(SubWorkflowStoreActor.props(subWorkflowStore), "SubWorkflowStoreActor")
  
  // Io Actor
  lazy val throttleElements = systemConfig.as[Option[Int]]("io.number-of-requests").getOrElse(100000)
  lazy val throttlePer = systemConfig.as[Option[FiniteDuration]]("io.per").getOrElse(100 seconds)
  lazy val ioThrottle = Throttle(throttleElements, throttlePer, throttleElements)
  lazy val ioActor = context.actorOf(IoActor.props(1000, Option(ioThrottle)).withDispatcher(Dispatcher.IoDispatcher))

  lazy val workflowLogCopyRouter: ActorRef = context.actorOf(RoundRobinPool(numberOfWorkflowLogCopyWorkers)
    .withSupervisorStrategy(CopyWorkflowLogsActor.strategy)
    .props(CopyWorkflowLogsActor.props(serviceRegistryActor, ioActor)),
    "WorkflowLogCopyRouter")

  lazy val callCache: CallCache = new CallCache(SingletonServicesStore.databaseInterface)

  lazy val numberOfCacheReadWorkers = config.getConfig("system").as[Option[Int]]("number-of-cache-read-workers").getOrElse(DefaultNumberOfCacheReadWorkers)
  lazy val callCacheReadActor = context.actorOf(RoundRobinPool(numberOfCacheReadWorkers)
    .props(CallCacheReadActor.props(callCache)),
    "CallCacheReadActor")

  lazy val callCacheWriteActor = context.actorOf(CallCacheWriteActor.props(callCache), "CallCacheWriteActor")
  
  // Docker Actor
  lazy val ioEc = context.system.dispatchers.lookup(Dispatcher.IoDispatcher)
  lazy val dockerConf = DockerConfiguration.instance
  // Sets the number of requests that the docker actor will accept before it starts backpressuring (modulo the number of in flight requests)
  lazy val dockerActorQueueSize = 500
  
  lazy val dockerHttpPool = Http().superPool[ContextWithRequest[DockerHashContext]]()
  lazy val googleFlow = new GoogleFlow(dockerHttpPool, dockerConf.gcrApiQueriesPer100Seconds)(ioEc, materializer, system.scheduler)
  lazy val dockerHubFlow = new DockerHubFlow(dockerHttpPool)(ioEc, materializer, system.scheduler)
  lazy val quayFlow = new QuayFlow(dockerHttpPool)(ioEc, materializer, system.scheduler)
  lazy val dockerCliFlow = new DockerCliFlow()(ioEc, materializer, system.scheduler)
  lazy val dockerFlows = dockerConf.method match {
    case DockerLocalLookup => Seq(dockerCliFlow)
    case DockerRemoteLookup => Seq(dockerHubFlow, googleFlow, quayFlow)
  }
  lazy val dockerHashActor = context.actorOf(DockerHashActor.props(dockerFlows, dockerActorQueueSize, dockerConf.cacheEntryTtl, dockerConf.cacheSize)(materializer).withDispatcher(Dispatcher.IoDispatcher))

  lazy val backendSingletons = CromwellBackends.instance.get.backendLifecycleActorFactories map {
    case (name, factory) => name -> (factory.backendSingletonActorProps map context.actorOf)
  }
  lazy val backendSingletonCollection = BackendSingletonCollection(backendSingletons)

  lazy val jobExecutionTokenDispenserActor = context.actorOf(JobExecutionTokenDispenserActor.props)

  def abortJobsOnTerminate: Boolean

  lazy val workflowManagerActor = context.actorOf(
    WorkflowManagerActor.props(
      workflowStoreActor, ioActor, serviceRegistryActor, workflowLogCopyRouter, jobStoreActor, subWorkflowStoreActor, callCacheReadActor, callCacheWriteActor,
    dockerHashActor, jobExecutionTokenDispenserActor, backendSingletonCollection, abortJobsOnTerminate, serverMode),
    "WorkflowManagerActor")

  override def receive = {
    case _ => logger.error("CromwellRootActor is receiving a message. It prefers to be left alone!")
  }

  /**
    * Validate that all of the direct children actors were successfully created, otherwise error out the initialization
    * of Cromwell by passing a Throwable to the guardian.
    */
  override val supervisorStrategy = OneForOneStrategy() {
    case _: ActorInitializationException => Escalate
    case _: ActorRestartException => Restart
    case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
  }
}

object CromwellRootActor {
  val DefaultNumberOfWorkflowLogCopyWorkers = 10
  val DefaultCacheTTL = 20 minutes
  val DefaultNumberOfCacheReadWorkers = 25
}
