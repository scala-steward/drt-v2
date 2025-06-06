package uk.gov.homeoffice.drt.testsystem.db

import slick.dbio.{DBIOAction, NoStream}
import slick.jdbc.JdbcProfile
import slickdb.AkkaDbTables

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

object AkkaDbH2 extends AkkaDbTables {
  override val profile: JdbcProfile = slick.jdbc.H2Profile
  val db: profile.backend.Database = profile.api.Database.forConfig("h2-pekko-db")

  override def run[R](action: DBIOAction[R, NoStream, Nothing]): Future[R] = db.run[R](action)


  def dropAndCreateH2Tables()
                           (implicit ec: ExecutionContext): Unit = {
    val tables = Seq(journalTable, snapshotTable)

    import profile.api._

    Await.result(
      run(DBIO.seq(tables.map(_.schema.dropIfExists): _*))
        .flatMap(_ => run(DBIO.seq(tables.map(_.schema.create): _*))),
      1.second
    )
  }
}
