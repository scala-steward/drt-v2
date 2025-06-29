package services.crunch.workload

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import services.crunch.CrunchTestLike
import services.graphstages.{DynamicWorkloadCalculator, FlightFilter}
import uk.gov.homeoffice.drt.arrivals.SplitStyle.PaxNumbers
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Queues.{Open, Queue, QueueFallbacks}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitSource, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.ports.config.AirportConfigDefaults
import uk.gov.homeoffice.drt.redlist.{RedListUpdate, RedListUpdates}
import uk.gov.homeoffice.drt.time.SDate

class WorkloadSpec extends CrunchTestLike {

  private def generateSplits(paxCount: Int, splitSource: SplitSource, eventType: Option[EventType]): Set[Splits] =
    Set(
      Splits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, paxCount, None, None)),
        splitSource,
        eventType,
        PaxNumbers))

  private def workloadCalculator(procTimes: Map[PaxTypeAndQueue, Double], filter: FlightFilter) =
    DynamicWorkloadCalculator(
      Map(T1 -> procTimes, T2 -> procTimes, T3 -> procTimes, T4 -> procTimes, T5 -> procTimes),
      QueueFallbacks((_, _) => Seq.empty),
      filter,
      AirportConfigDefaults.fallbackProcessingTime,
      paxFeedSourceOrder,
    )

  private val procTime = 1.5

  val redListedZimbabwe: RedListUpdates = RedListUpdates(Map(0L -> RedListUpdate(0L, Map("Zimbabwe" -> "ZWE"), List())))

  private def workloadForFlight(arrival: Arrival, splits: Set[Splits], filter: FlightFilter): Double = {
    val procTimes = Map(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> procTime)
    workloadCalculator(procTimes, filter)
      .flightLoadMinutes(
        arrival.pcpRange(paxFeedSourceOrder),
        FlightsWithSplits(Iterable(ApiFlightWithSplits(arrival, splits, None))),
        redListedZimbabwe,
        (_: Terminal) => (_: Queue, _: MillisSinceEpoch) => Open,
        paxFeedSourceOrder,
        terminalSplits = _ => None,
      )
      .minutes.values.map(_.workLoad).sum
  }

  "Given an arrival with 1 pax and 1 split containing 1 pax with no nationality data " +
    "When I ask for the workload for this arrival " +
    "Then I see the 1x the proc time provided" >> {

    val arrival = ArrivalGenerator.live(totalPax = Option(1))
    val splits = generateSplits(1, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))

    val workloads = workloadForFlight(arrival.toArrival(LiveFeedSource), splits, FlightFilter.regular(List(T1)))

    workloads === procTime
  }

  "Given an arrival with a PCP time that has seconds, then these seconds should be ignored for workload calculations" >> {
    val arrival = ArrivalGenerator.live(totalPax = Option(1)).toArrival(LiveFeedSource)
      .copy(PcpTime = Some(SDate("2018-08-28T17:07:05").millisSinceEpoch))

    val splits = generateSplits(1, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))
    val procTimes = Map(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk) -> procTime)

    val workloads = workloadCalculator(procTimes, FlightFilter.regular(List(T1)))
      .flightLoadMinutes(
        arrival.pcpRange(paxFeedSourceOrder),
        FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, splits, None))),
        RedListUpdates.empty,
        _ => (_: Queue, _: MillisSinceEpoch) => Open,
        paxFeedSourceOrder,
        terminalSplits = _ => None,
      )

    val startTime = SDate(workloads.minutes.keys.toList.minBy(_.minute).minute).toISOString

    startTime === "2018-08-28T17:07:00Z"
  }

  "Given an arrival with None pax on the arrival and 1 split containing 6 pax " +
    "When I ask for the workload for this arrival " +
    "Then I see the 6x the proc time provided" >> {

    val arrival = ArrivalGenerator.live(totalPax = Option(6))
    val splits = generateSplits(6, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))
    val workloads = workloadForFlight(arrival.toArrival(AclFeedSource), splits, FlightFilter.regular(List(T1)))

    workloads === procTime * 6
  }

  "Given an arrival with 1 pax on the arrival and 1 historic split containing 6 pax " +
    "When I ask for the workload for this arrival " +
    "Then I see the 1x the proc time provided" >> {

    val arrival = ArrivalGenerator.live().toArrival(LiveFeedSource).copy(
      FeedSources = Set(LiveFeedSource),
      PassengerSources = Map(LiveFeedSource -> Passengers(Option(1), None), HistoricApiFeedSource -> Passengers(Option(6), None))
    )
    val splits = generateSplits(6, SplitSources.Historical, None)
    val workloads = workloadForFlight(arrival, splits, FlightFilter.regular(List(T1)))

    workloads === procTime * 1
  }

  "Concerning red list origins" >> {
    val redListOriginBulawayo = PortCode("BUQ")

    def portConf(port: PortCode): AirportConfig = DrtPortConfigs.confByPort(port)

    val paxCount = 6

    s"Given an arrival with 6 pax at STN $T1 from a red list country, I should see some workload" >> {
      workloadForFlightFromTo(redListOriginBulawayo, portConf(PortCode("STN")), T1, paxCount) === paxCount * procTime
    }

    s"Given an arrival with 6 pax at LHR $T2 from a red list country, I should see no workload" >> {
      workloadForFlightFromTo(redListOriginBulawayo, portConf(PortCode("LHR")), T2, paxCount) === 0
    }

    s"Given an arrival with 6 pax at LHR $T3 from a red list country, I should see no workload" >> {
      workloadForFlightFromTo(redListOriginBulawayo, portConf(PortCode("LHR")), T3, paxCount) === paxCount * procTime
    }

    s"Given an arrival with 6 pax at LHR $T4 from a red list country, I should see no workload" >> {
      workloadForFlightFromTo(redListOriginBulawayo, portConf(PortCode("LHR")), T4, paxCount) === paxCount * procTime
    }

    s"Given an arrival with 6 pax at LHR $T5 from a red list country, I should see no workload" >> {
      workloadForFlightFromTo(redListOriginBulawayo, portConf(PortCode("LHR")), T5, paxCount) === 0
    }
  }

  def workloadForFlightFromTo(origin: PortCode, config: AirportConfig, terminal: Terminal, paxCount: Int): Double = {
    val arrival = ArrivalGenerator.live(
      schDt = "2021-06-01T12:00",
      terminal = terminal,
      origin = origin,
      totalPax = Option(paxCount)
    ).toArrival(LiveFeedSource)
    val splits = generateSplits(paxCount, SplitSources.Historical, None)
    workloadForFlight(arrival, splits, FlightFilter.forPortConfig(config))
  }
}
