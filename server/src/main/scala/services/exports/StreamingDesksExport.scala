package services.exports

import actors.routing.minutes.MinutesActorLike.MinutesLookup
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinute}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.models.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{DateRange, SDate, SDateLike, UtcDate}

import scala.collection.immutable
import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}

object StreamingDesksExport {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def colHeadings(deskTitle: String = "req"): List[String] = List("Pax", "Wait", s"Desks $deskTitle", "Act. wait time", "Act. desks")

  private def eGatesHeadings(deskTitle: String): List[String] = List("Pax", "Wait", s"Staff $deskTitle", "Act. wait time", "Act. desks")

  private def csvHeader(queues: Seq[Queue], deskTitle: String): String = {

    val headingsLine1 = "Date,Terminal,," + queueHeadings(queues) + ",Misc,Moves,PCP Staff,PCP Staff"
    val headingsLine2 = ",,Start," + queues.flatMap(q => {
      if (q == Queues.EGate) eGatesHeadings(deskTitle) else colHeadings(deskTitle)
    }).mkString(",") +
      s",Staff req,Staff movements,Avail,Req"

    headingsLine1 + "\n" + headingsLine2 + "\n"
  }

  def queueHeadings(queues: Seq[Queue]): String = queues.map(queue => Queues.displayName(queue))
    .flatMap(qn => List.fill(colHeadings().length)(Queues.exportQueueDisplayNames.getOrElse(Queue(qn), qn))).mkString(",")

  def deskDepsTerminalsToCSVStreamWithHeaders(start: SDateLike,
                                              end: SDateLike,
                                              terminals: Seq[Terminal],
                                              exportQueuesInOrder: Seq[Queue],
                                              crunchMinuteLookup: MinutesLookup[CrunchMinute, TQM],
                                              staffMinuteLookup: MinutesLookup[StaffMinute, TM],
                                              maybePit: Option[MillisSinceEpoch] = None,
                                              periodMinutes: Int)(implicit ec: ExecutionContext): Source[String, NotUsed] = {
    val streams = exportTerminalsDesksToCSVStream(start, end, terminals, exportQueuesInOrder, crunchMinuteLookup, staffMinuteLookup,
      deploymentsCsv, maybePit, periodMinutes
    )

    val header = Source.single(csvHeader(exportQueuesInOrder, "dep"))

    header.concat(streams)
  }

  def deskRecsTerminalsToCSVStreamWithHeaders(start: SDateLike,
                                              end: SDateLike,
                                              terminals: Seq[Terminal],
                                              exportQueuesInOrder: Seq[Queue],
                                              crunchMinuteLookup: MinutesLookup[CrunchMinute, TQM],
                                              staffMinuteLookup: MinutesLookup[StaffMinute, TM],
                                              maybePit: Option[MillisSinceEpoch] = None,
                                              periodMinutes: Int)(implicit ec: ExecutionContext): Source[String, NotUsed] = {
    val streams = exportTerminalsDesksToCSVStream(start, end, terminals, exportQueuesInOrder, crunchMinuteLookup, staffMinuteLookup,
      deskRecsCsv, maybePit, periodMinutes
    )

    val header = Source.single(csvHeader(exportQueuesInOrder, "req"))

    header.concat(streams)
  }

  def deskRecsToCSVStreamWithHeaders(start: SDateLike,
                                     end: SDateLike,
                                     terminal: Terminal,
                                     exportQueuesInOrder: Seq[Queue],
                                     crunchMinuteLookup: MinutesLookup[CrunchMinute, TQM],
                                     staffMinuteLookup: MinutesLookup[StaffMinute, TM],
                                     maybePit: Option[MillisSinceEpoch] = None,
                                     periodMinutes: Int,
                                    )(implicit ec: ExecutionContext): Source[String, NotUsed] =
    exportDesksToCSVStream(
      start, end, terminal, exportQueuesInOrder, crunchMinuteLookup, staffMinuteLookup, deskRecsCsv, maybePit, periodMinutes
    ).prepend(Source(List(csvHeader(exportQueuesInOrder, "req"))))


  def deploymentsToCSVStreamWithHeaders(start: SDateLike,
                                        end: SDateLike,
                                        terminal: Terminal,
                                        exportQueuesInOrder: Seq[Queue],
                                        crunchMinuteLookup: MinutesLookup[CrunchMinute, TQM],
                                        staffMinuteLookup: MinutesLookup[StaffMinute, TM],
                                        maybePit: Option[MillisSinceEpoch] = None,
                                        periodMinutes: Int,
                                       )(implicit ec: ExecutionContext): Source[String, NotUsed] =
    exportDesksToCSVStream(start, end, terminal, exportQueuesInOrder, crunchMinuteLookup, staffMinuteLookup,
      deploymentsCsv, maybePit, periodMinutes
    ).prepend(Source(List(csvHeader(exportQueuesInOrder, "dep"))))


  def exportTerminalsDesksToCSVStream(start: SDateLike,
                                      end: SDateLike,
                                      terminals: Seq[Terminal],
                                      exportQueuesInOrder: Seq[Queue],
                                      crunchMinuteLookup: MinutesLookup[CrunchMinute, TQM],
                                      staffMinuteLookup: MinutesLookup[StaffMinute, TM],
                                      deskExportFn: CrunchMinute => String,
                                      maybePit: Option[MillisSinceEpoch] = None,
                                      periodMinutes: Int,
                                     )(implicit ec: ExecutionContext): Source[String, NotUsed] =
    DateRange.utcDateRangeSource(start, end)
      .mapAsync(1) { crunchUtcDate =>
        val crunchMinutesFutures = terminals.map { terminal =>
          crunchMinuteLookup((terminal, crunchUtcDate), maybePit)
        }
        val staffMinutesFutures = terminals.map { terminal =>
          staffMinuteLookup((terminal, crunchUtcDate), maybePit)
        }

        for {
          crunchMinutes <- Future.sequence(crunchMinutesFutures)
          staffMinutes <- Future.sequence(staffMinutesFutures)
        } yield {
          val combinedCrunchMinutes = crunchMinutes.flatten.flatMap(_.minutes.map(_.toMinute))
          val combinedStaffMinutes = staffMinutes.flatten.flatMap(_.minutes.map(_.toMinute))

          terminalsMinutesDesksAndQueuesToCsv(
            terminals,
            exportQueuesInOrder,
            crunchUtcDate,
            start,
            end,
            combinedCrunchMinutes,
            combinedStaffMinutes,
            deskExportFn,
            periodMinutes,
          )
        }
      }


  def exportDesksToCSVStream(start: SDateLike,
                             end: SDateLike,
                             terminal: Terminal,
                             exportQueuesInOrder: Seq[Queue],
                             crunchMinuteLookup: MinutesLookup[CrunchMinute, TQM],
                             staffMinuteLookup: MinutesLookup[StaffMinute, TM],
                             deskExportFn: CrunchMinute => String,
                             maybePit: Option[MillisSinceEpoch] = None,
                             periodMinutes: Int,
                            )(implicit ec: ExecutionContext): Source[String, NotUsed] =
    DateRange.utcDateRangeSource(start, end)
      .mapAsync(1) { crunchUtcDate =>
        for {
          maybeCMs <- crunchMinuteLookup((terminal, crunchUtcDate), maybePit)
          maybeSMs <- staffMinuteLookup((terminal, crunchUtcDate), maybePit)
        } yield {
          minutesDesksAndQueuesToCsv(
            terminal,
            exportQueuesInOrder,
            crunchUtcDate,
            start,
            end,
            maybeCMs.map(_.minutes.map(_.toMinute)).getOrElse(List()),
            maybeSMs.map(_.minutes.map(_.toMinute)).getOrElse(List()),
            deskExportFn,
            periodMinutes,
          )
        }
      }

  private def generateCsv(terminal: Terminal,
                          exportQueuesInOrder: Seq[Queue],
                          utcDate: UtcDate,
                          start: SDateLike,
                          end: SDateLike,
                          crunchMinutes: Iterable[CrunchMinute],
                          staffMinutes: Iterable[StaffMinute],
                          deskExportFn: CrunchMinute => String,
                          periodMinutes: Int): Seq[(MillisSinceEpoch, String)] = {
    val portState = PortState(
      List(),
      crunchMinutes,
      staffMinutes
    )

    val minutesInaDay = 1440d
    val numberOfPeriods = (minutesInaDay / periodMinutes).ceil.toInt

    val terminalCrunchMinutes = portState
      .crunchSummary(SDate(utcDate), numberOfPeriods, periodMinutes, terminal, exportQueuesInOrder)
    val terminalCrunchMinutesWithinRange: SortedMap[MillisSinceEpoch, Map[Queue, CrunchMinute]] = terminalCrunchMinutes.filter {
      case (millis, _) => start.millisSinceEpoch <= millis && millis <= end.millisSinceEpoch
    }

    val terminalStaffMinutes = portState
      .staffSummary(SDate(utcDate), numberOfPeriods, periodMinutes, terminal)
    val terminalStaffMinutesWithinRange: Map[MillisSinceEpoch, StaffMinute] = terminalStaffMinutes.filter {
      case (millis, _) => start.millisSinceEpoch <= millis && millis <= end.millisSinceEpoch
    }

    terminalCrunchMinutesWithinRange.map {
      case (minute, qcm) =>
        val qcms: immutable.Seq[CrunchMinute] = exportQueuesInOrder.map(q => qcm.get(q)).collect {
          case Some(qcm) => qcm
        }
        val qsCsv: String = qcms.map(deskExportFn).mkString(",")
        val staffMinutesCsv = terminalStaffMinutesWithinRange.get(minute) match {
          case Some(sm) =>
            s"${sm.fixedPoints},${sm.movements},${sm.shifts}"
          case _ => "Missing staffing data for this period,,"
        }
        val total = qcms.map(_.deskRec).sum
        val localMinute = SDate(minute, europeLondonTimeZone)
        val misc = terminalStaffMinutesWithinRange.get(minute).map(_.fixedPoints).getOrElse(0)
        (minute, s"${localMinute.toISODateOnly},${terminal.toString},${localMinute.prettyTime},$qsCsv,$staffMinutesCsv,${total + misc}\n")
    }.toSeq
  }

  def terminalsMinutesDesksAndQueuesToCsv(terminals: Seq[Terminal],
                                          exportQueuesInOrder: Seq[Queue],
                                          utcDate: UtcDate,
                                          start: SDateLike,
                                          end: SDateLike,
                                          crunchMinutes: Iterable[CrunchMinute],
                                          staffMinutes: Iterable[StaffMinute],
                                          deskExportFn: CrunchMinute => String,
                                          periodMinutes: Int): String = {
    val terminalData = terminals.flatMap { terminal =>
      generateCsv(terminal, exportQueuesInOrder, utcDate, start, end, crunchMinutes, staffMinutes, deskExportFn, periodMinutes)
    }

    val groupedByMinute = terminalData.groupBy(_._1).toSeq.sortBy(_._1)

    groupedByMinute.flatMap {
      case (_, data) => data.map(_._2)
    }.mkString
  }

  def minutesDesksAndQueuesToCsv(terminal: Terminal,
                                 exportQueuesInOrder: Seq[Queue],
                                 utcDate: UtcDate,
                                 start: SDateLike,
                                 end: SDateLike,
                                 crunchMinutes: Iterable[CrunchMinute],
                                 staffMinutes: Iterable[StaffMinute],
                                 deskExportFn: CrunchMinute => String,
                                 periodMinutes: Int): String = {
    generateCsv(terminal, exportQueuesInOrder, utcDate, start, end, crunchMinutes, staffMinutes, deskExportFn, periodMinutes)
      .map(_._2)
      .mkString
  }

  private def deskRecsCsv(cm: CrunchMinute): String =
    s"${Math.round(cm.paxLoad)},${cm.waitTime},${cm.deskRec},${cm.actWait.getOrElse("")},${cm.actDesks.getOrElse("")}"

  private def deploymentsCsv(cm: CrunchMinute): String =
    s"${Math.round(cm.paxLoad)},${cm.deployedWait.getOrElse("")},${cm.deployedDesks.getOrElse(0)},${cm.actWait.getOrElse("")},${cm.actDesks.getOrElse("")}"
}
