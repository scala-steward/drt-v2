package uk.gov.homeoffice.drt.service.staffing

import actors.DrtStaticParameters.time48HoursAgo
import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.persistent.staffing.LegacyShiftAssignmentsActor.UpdateShifts
import actors.persistent.staffing.{LegacyShiftAssignmentsActor, ShiftAssignmentsActorLike, ShiftAssignmentsReadActor}
import org.apache.pekko.actor.{ActorRef, ActorSystem, PoisonPill}
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import drt.shared.{ShiftAssignments, StaffAssignmentLike}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.time.MilliDate.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}


object LegacyShiftAssignmentsServiceImpl {
  def pitActor(implicit system: ActorSystem): SDateLike => ActorRef = pointInTime => {
    val actorName = s"shifts-read-actor-" + UUID.randomUUID().toString
    system.actorOf(ShiftAssignmentsReadActor.props(LegacyShiftAssignmentsActor.persistenceId, pointInTime, time48HoursAgo(() => pointInTime)), actorName)
  }
}

case class LegacyShiftAssignmentsServiceImpl(liveShiftAssignmentsActor: ActorRef,
                                             shiftAssignmentsSequentialWriteActor: ActorRef,
                                             pitShiftAssignmentsActor: SDateLike => ActorRef,
                                            )
                                            (implicit timeout: Timeout, ec: ExecutionContext) extends LegacyShiftAssignmentsService {
  override def shiftAssignmentsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments] = {
    maybePointInTime match {
      case None =>
        liveShiftAssignmentsForDate(date)

      case Some(millis) =>
        shiftAssignmentsForPointInTime(SDate(millis))
    }
  }

  override def allShiftAssignments: Future[ShiftAssignments] =
    liveShiftAssignmentsActor
      .ask(GetState)
      .mapTo[ShiftAssignments]

  private def liveShiftAssignmentsForDate(date: LocalDate): Future[ShiftAssignments] = {
    val start = SDate(date).millisSinceEpoch
    val end = SDate(date).addDays(1).addMinutes(-1).millisSinceEpoch
    liveShiftAssignmentsActor.ask(GetStateForDateRange(start, end))
      .map { case sa: ShiftAssignments => sa }
  }

  private def shiftAssignmentsForPointInTime(pointInTime: SDateLike): Future[ShiftAssignments] = {
    val shiftsReadActor: ActorRef = pitShiftAssignmentsActor(pointInTime)

    val start = pointInTime.getLocalLastMidnight.millisSinceEpoch
    val end = pointInTime.getLocalNextMidnight.addMinutes(-1).millisSinceEpoch

    shiftsReadActor.ask(GetStateForDateRange(start, end))
      .map { case sa: ShiftAssignments =>
        shiftsReadActor ! PoisonPill
        sa
      }
      .recoverWith {
        case t =>
          shiftsReadActor ! PoisonPill
          throw t
      }
  }

  override def updateShiftAssignments(shiftAssignments: Seq[StaffAssignmentLike]): Future[ShiftAssignments] =
    shiftAssignmentsSequentialWriteActor
      .ask(UpdateShifts(shiftAssignments))
      .mapTo[ShiftAssignments]
//      .map { a =>
//        println(s"Updated shift assignments: ${a.assignments.sortBy(_.start).map(a => s"${SDate(a.start).toISOString}: ${a.numberOfStaff}").mkString("\n")}")
//        a
//      }
}
