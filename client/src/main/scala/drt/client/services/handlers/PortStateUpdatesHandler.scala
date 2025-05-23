package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode._
import diode.data._
import drt.client.actions.Actions._
import drt.client.logger._
import drt.client.services._
import drt.client.services.handlers.PortStateUpdatesHandler.splitsToManifestKeys
import drt.shared.CrunchApi._
import drt.shared._
import org.scalajs.dom
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.models.{CrunchMinute, FlightManifestSummary, ManifestKey, TQM}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import upickle.default.read

import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

object PortStateUpdatesHandler {
  def splitsToManifestKeys(incomingSplits: Iterable[SplitsForArrivals],
                           flights: Map[UniqueArrival, ApiFlightWithSplits],
                           existingManifestKeys: Set[ManifestKey]
                          ): Set[ManifestKey] =
    incomingSplits
      .flatMap { splitsForArrivals =>
        splitsForArrivals.splits.filter { case (_, splits) =>
          splits.exists(_.source == ApiSplitsWithHistoricalEGateAndFTPercentages)
        }
      }
      .toMap.keys
      .map(ua => manifestArrivalKey(ua, flights))
      .toSet
      .diff(existingManifestKeys)

  def manifestArrivalKey(ua: UniqueArrival, flights: Map[UniqueArrival, ApiFlightWithSplits]): ManifestKey =
    flights.get(ua) match {
      case Some(fws) => ManifestKey(fws.apiFlight)
      case None => ManifestKey(ua.origin, VoyageNumber(ua.number), ua.scheduled)
    }
}

class PortStateUpdatesHandler[M](getCurrentViewMode: () => ViewMode,
                                 portStateModel: ModelRW[M, (Pot[PortState], MillisSinceEpoch, MillisSinceEpoch, MillisSinceEpoch)],
                                 manifestSummariesModel: ModelR[M, Map[ManifestKey, FlightManifestSummary]],
                                 paxFeedSourceOrder: ModelR[M, List[FeedSource]],
                                ) extends LoggingActionHandler(portStateModel) {
  private val liveRequestFrequency: FiniteDuration = 2 seconds
  private val forecastRequestFrequency: FiniteDuration = 15 seconds

  val thirtySixHoursInMillis: Long = 1000L * 60 * 60 * 36

  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case GetPortStateUpdates(viewMode) =>
      val (_, flightsSince, queuesSince, staffSince) = portStateModel.value
      val startMillis = viewMode.dayStart.millisSinceEpoch
      val endMillis = startMillis + thirtySixHoursInMillis
      val updateRequestFuture = DrtApi.get(s"crunch?start=$startMillis&end=$endMillis&flights-since=$flightsSince&queues-since=$queuesSince&staff-since=$staffSince")

      val eventualAction = processUpdatesRequest(viewMode, updateRequestFuture)

      effectOnly(Effect(eventualAction))

    case UpdatePortStateFromUpdates(viewMode, _) if viewMode.isDifferentTo(getCurrentViewMode()) =>
      log.info(s"Ignoring out of date view response")
      noChange

    case UpdatePortStateFromUpdates(viewMode, crunchUpdates) =>
      portStateModel.value match {
        case (Ready(existingState), _, _, _) =>
          val newState = updateStateFromUpdates(viewMode.dayStart.millisSinceEpoch, crunchUpdates, existingState)
          val scheduledUpdateRequest = Effect(Future(SchedulePortStateUpdateRequest(viewMode)))

          val existingFlights = value._1.map(_.flights).getOrElse(Map.empty)
          val manifestRequestArrivalKeys = splitsToManifestKeys(crunchUpdates.updatesAndRemovals.splitsUpdates.values, existingFlights, manifestSummariesModel.value.keySet)
          val manifestRequest = if (manifestRequestArrivalKeys.nonEmpty) {
            List(Effect(Future(GetManifestSummaries(manifestRequestArrivalKeys))))
          } else List.empty

          val newOriginCodes = crunchUpdates.updatesAndRemovals.arrivalUpdates.flatMap(_._2.toUpdate.map(_._1.origin)).toSet

          val effects = (manifestRequest ++ airportsRequest(newOriginCodes))
            .foldLeft(new EffectSet(scheduledUpdateRequest, Set(), queue))(_ + _)

          updated((Ready(newState), crunchUpdates.lastFlightsUpdate, crunchUpdates.lastQueuesUpdate, crunchUpdates.lastStaffUpdate), effects)

        case _ =>
          log.warn(s"No existing state to apply updates to. Ignoring")
          noChange
      }

    case SchedulePortStateUpdateRequest(viewMode) if viewMode.isDifferentTo(getCurrentViewMode()) =>
      log.info(s"Ignoring out of date schedule updates request")
      noChange

    case SchedulePortStateUpdateRequest(viewMode) => effectOnly(getCrunchUpdatesAfterDelay(viewMode))
  }

  private def airportsRequest(newOriginCodes: Set[PortCode]): List[EffectSingle[GetAirportInfos]] =
    if (newOriginCodes.nonEmpty)
      List(Effect(Future(GetAirportInfos(newOriginCodes))))
    else
      List.empty

  private def processUpdatesRequest(viewMode: ViewMode, call: Future[dom.XMLHttpRequest]): Future[Action] =
    call
      .map(r => read[Option[PortStateUpdates]](r.responseText))
      .map {
        case Some(cu) => UpdatePortStateFromUpdates(viewMode, cu)
        case None => SchedulePortStateUpdateRequest(viewMode)
      }
      .recoverWith {
        case throwable =>
          log.error(s"Call to crunch-state failed (${throwable.getMessage}. Re-requesting after ${PollDelay.recoveryDelay}")
          Future(RetryActionAfter(GetPortStateUpdates(viewMode), PollDelay.recoveryDelay))
      }

  private def updateStateFromUpdates(startMillis: MillisSinceEpoch, crunchUpdates: PortStateUpdates, existingState: PortState): PortState = {
    val withArrivalUpdates = crunchUpdates.updatesAndRemovals.arrivalUpdates.foldLeft(FlightsWithSplits(existingState.flights)) {
      case (acc, (ts, diff)) =>
        diff.applyTo(acc, ts, paxFeedSourceOrder.value)._1
    }
    val withSplitsUpdates = crunchUpdates.updatesAndRemovals.splitsUpdates.foldLeft(withArrivalUpdates) {
      case (acc, (ts, diff)) =>
        diff.applyTo(acc, ts, paxFeedSourceOrder.value)._1
    }
    val trimmedFlights = trimFlights(withSplitsUpdates.flights, startMillis)

    val minutes = updateAndTrimCrunch(crunchUpdates, existingState, startMillis)
    val staff = updateAndTrimStaff(crunchUpdates, existingState, startMillis)
    PortState(trimmedFlights, minutes, staff)
  }

  private def updateAndTrimCrunch(crunchUpdates: PortStateUpdates,
                                  existingState: PortState,
                                  keepFromMillis: MillisSinceEpoch,
                                 ): SortedMap[TQM, CrunchMinute] = {
    val relevantMinutes = existingState.crunchMinutes.dropWhile {
      case (TQM(_, _, m), _) => m < keepFromMillis
    }
    crunchUpdates.queueMinutes.foldLeft(relevantMinutes) {
      case (soFar, newCm) => soFar.updated(TQM(newCm), newCm)
    }
  }

  private def updateAndTrimStaff(crunchUpdates: PortStateUpdates,
                                 existingState: PortState,
                                 keepFromMillis: MillisSinceEpoch,
                                ): SortedMap[TM, CrunchApi.StaffMinute] = {
    val relevantMinutes = existingState.staffMinutes.dropWhile {
      case (TM(_, m), _) => m < keepFromMillis
    }
    crunchUpdates.staffMinutes.foldLeft(relevantMinutes) {
      case (soFar, newSm) => soFar.updated(TM(newSm), newSm)
    }
  }

  private def trimFlights(flights: Map[UniqueArrival, ApiFlightWithSplits], keepFromMillis: MillisSinceEpoch): Map[UniqueArrival, ApiFlightWithSplits] = {
    val thirtyMinutesMillis = 30 * 60000
    flights
      .filter { case (_, fws) => fws.apiFlight.PcpTime.isDefined }
      .filter { case (_, fws) => keepFromMillis - thirtyMinutesMillis <= fws.apiFlight.PcpTime.getOrElse(0L) }
  }

  def getCrunchUpdatesAfterDelay(viewMode: ViewMode): Effect = Effect(Future(GetPortStateUpdates(viewMode))).after(requestFrequency(viewMode))

  def requestFrequency(viewMode: ViewMode): FiniteDuration = viewMode match {
    case ViewLive => liveRequestFrequency
    case _ => forecastRequestFrequency
  }
}
