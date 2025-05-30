package actors.persistent.staffing

import drt.shared.{Shift, ShiftAssignments, StaffAssignment}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

class StaffingUtilSpec extends Specification {

  "StaffingUtil" should {
    "generate daily assignments for each day between start and end date" in {
      val shift = Shift(
        port = "LHR",
        terminal = "T1",
        shiftName = "Morning Shift",
        startDate = LocalDate(2023, 10, 1),
        startTime = "08:00",
        endTime = "16:00",
        endDate = Some(LocalDate(2023, 10, 3)),
        staffNumber = 5,
        frequency = None,
        createdBy = Some("test"),
        createdAt = System.currentTimeMillis()
      )

      val assignments: Seq[StaffAssignment] = StaffingUtil.generateDailyAssignments(shift)

      assignments.length must beEqualTo(3)

      assignments.foreach { assignment =>
        assignment.name must beEqualTo("Morning Shift")
        assignment.terminal.toString must beEqualTo("T1")
        assignment.numberOfStaff must beEqualTo(5)
        assignment.createdBy must beSome("test")
      }

      val expectedStartMillis = SDate(2023, 10, 1, 8, 0, europeLondonTimeZone).millisSinceEpoch

      val expectedEndMillis = SDate(2023, 10, 3, 16, 0, europeLondonTimeZone).millisSinceEpoch

      SDate(assignments.head.start).toISOString must beEqualTo(SDate(expectedStartMillis).toISOString)
      SDate(assignments.last.end).toISOString must beEqualTo(SDate(expectedEndMillis).toISOString)
    }
  }

  "updateWithShiftDefaultStaff" should {
    "update assignments with zero staff" in {
      val shifts = Seq(
        Shift("LHR", "T1", "day", LocalDate(2023, 10, 1), "14:00", "16:00", Some(LocalDate(2023, 10, 1)), 5, None, None, 0L)
      )

      val allShifts = ShiftAssignments(
        StaffAssignment("afternoon", Terminal("terminal"), SDate(2023, 10, 1, 14, 0, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 0, europeLondonTimeZone).millisSinceEpoch, 0, None).splitIntoSlots(15),
      )

      val updatedAssignments = StaffingUtil.updateWithShiftDefaultStaff(shifts, allShifts)

      updatedAssignments should have size 8
      updatedAssignments === List(
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 14, 0, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 14, 14, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 14, 15, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 14, 29, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 14, 30, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 14, 44, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 14, 45, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 14, 59, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 0, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 14, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 15, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 29, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 30, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 44, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 45, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 59, europeLondonTimeZone).millisSinceEpoch, 5, None))

    }

    "not update assignments with non-zero staff" in {
      val shifts = Seq(
        Shift("LHR", "T1", "afternoon", LocalDate(2023, 10, 1), "14:00", "16:00", Some(LocalDate(2023, 10, 1)), 5, None, None, 0L)
      )

      val allShifts = ShiftAssignments(
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 14, 0, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 0, europeLondonTimeZone).millisSinceEpoch, 3, None).splitIntoSlots(15),
      )

      val updatedAssignments = StaffingUtil.updateWithShiftDefaultStaff(shifts, allShifts)

      updatedAssignments should have size 8

      updatedAssignments.toSet === Set(
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 14, 0, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 14, 14, europeLondonTimeZone).millisSinceEpoch, 3, None),
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 14, 15, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 14, 29, europeLondonTimeZone).millisSinceEpoch, 3, None),
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 14, 30, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 14, 44, europeLondonTimeZone).millisSinceEpoch, 3, None),
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 14, 45, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 14, 59, europeLondonTimeZone).millisSinceEpoch, 3, None),
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 15, 0, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 14, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 15, 15, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 29, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 15, 30, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 44, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("afternoon", Terminal("T1"), SDate(2023, 10, 1, 15, 45, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 59, europeLondonTimeZone).millisSinceEpoch, 5, None))

    }

    "overlapping shifts to sum if they overlap" in {
      val shifts = Seq(
        Shift("LHR", "T1", "day", LocalDate(2023, 10, 1), "14:00", "16:00", Some(LocalDate(2023, 10, 1)), 5, None, None, 0L),
        Shift("LHR", "T1", "day", LocalDate(2023, 10, 1), "15:00", "17:00", Some(LocalDate(2023, 10, 1)), 5, None, None, 0L)
      )

      val allShifts = ShiftAssignments(
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 14, 0, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 0, europeLondonTimeZone).millisSinceEpoch, 0, None).splitIntoSlots(15),
      )

      val updatedAssignments = StaffingUtil.updateWithShiftDefaultStaff(shifts, allShifts)

      updatedAssignments should have size 12

      updatedAssignments.toSet === Set(
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 14, 0, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 14, 14, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 14, 15, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 14, 29, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 14, 30, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 14, 44, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 14, 45, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 14, 59, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 0, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 14, europeLondonTimeZone).millisSinceEpoch, 10, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 15, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 29, europeLondonTimeZone).millisSinceEpoch, 10, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 30, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 44, europeLondonTimeZone).millisSinceEpoch, 10, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 45, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 15, 59, europeLondonTimeZone).millisSinceEpoch, 10, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 16, 0, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 16, 14, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 16, 15, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 16, 29, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 16, 30, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 16, 44, europeLondonTimeZone).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 16, 45, europeLondonTimeZone).millisSinceEpoch, SDate(2023, 10, 1, 16, 59, europeLondonTimeZone).millisSinceEpoch, 5, None))
    }

  }
}