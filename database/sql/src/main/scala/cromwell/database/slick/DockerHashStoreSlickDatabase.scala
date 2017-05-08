package cromwell.database.slick

import cats.instances.future._
import cats.syntax.functor._
import cromwell.database.sql.DockerHashStoreSqlDatabase
import cromwell.database.sql.tables.DockerHashStoreEntry

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

trait DockerHashStoreSlickDatabase extends DockerHashStoreSqlDatabase {
  this: SlickDatabase =>

  import dataAccess.driver.api._

  /**
    * Adds docker hash entries to the store.
    */
  override def addDockerHashStoreEntries(dockerHashStoreEntries: Iterable[DockerHashStoreEntry])
                               (implicit ec: ExecutionContext): Future[Unit] = {
    // This will do a batch insert but we're currently not limiting the batch size.  That's probably ok given the
    // scale of this particular case.
    val action = dataAccess.dockerHashStoreEntries ++= dockerHashStoreEntries
    runTransaction(action) void
  }

  /**
    * Retrieves docker hash entries for a workflow.
    *
    */
  override def queryDockerHashStoreEntries(workflowExecutionUuid: String)
                                          (implicit ec: ExecutionContext): Future[Seq[DockerHashStoreEntry]] = {
    val action = dataAccess.dockerHashStoreEntriesForWorkflowExecutionUuid(workflowExecutionUuid).result
    runTransaction(action)
  }

  /**
    * Deletes docker hash entries related to a workflow, returning the number of rows affected.
    */
  override def removeDockerHashStoreEntries(workflowExecutionUuid: String)(implicit ec: ExecutionContext): Future[Int] = {
    val action = dataAccess.dockerHashStoreEntriesForWorkflowExecutionUuid(workflowExecutionUuid).delete
    runTransaction(action)
  }
}
