package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared._
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.eeaMachineReadableToDesk
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2, Terminal}
import uk.gov.homeoffice.drt.ports.{PaxTypeAndQueue, PortCode, Queues}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.concurrent.duration._


class CrunchCodeSharesSpec extends CrunchTestLike {
  sequential
  isolated

  val oneMinute: Double = 60d / 60

  val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(
    T1 -> Map(eeaMachineReadableToDesk -> oneMinute),
    T2 -> Map(eeaMachineReadableToDesk -> oneMinute))

    "Given 2 flights which are codeshares with each other " +
      "When I ask for a crunch " +
      "Then I should see workload representing only the flight with the highest passenger numbers" >> {
      val scheduled = "2017-01-01T00:00Z"


      val arrivals = List(
        ArrivalGenerator.live(iata = "BA0001", schDt = scheduled, terminal = T1, totalPax = Option(15), origin = PortCode("JFK")),
        ArrivalGenerator.live(iata = "FR8819", schDt = scheduled, terminal = T1, totalPax = Option(10), origin = PortCode("JFK"))
      )
      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        airportConfig = defaultAirportConfig.copy(
          terminalProcessingTimes = procTimes,
          queuesByTerminal = SortedMap(LocalDate(2014, 1, 1) -> SortedMap(T1 -> Seq(Queues.EeaDesk)))
        )))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(arrivals))

      val expected = Map(T1 -> Map(Queues.EeaDesk -> Seq(15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))

      crunch.portStateTestProbe.fishForMessage(2.seconds) {
        case ps: PortState =>
          val resultSummary = paxLoadsFromPortState(ps, 15)
          resultSummary == expected
      }

      success
    }

    "Given flights some of which are code shares with each other " +
      "When I ask for a crunch " +
      "Then I should see workload correctly split to the appropriate terminals, and having accounted for code shares" >> {
      val scheduled00 = "2017-01-01T00:00Z"
      val scheduled15 = "2017-01-01T00:15Z"
      val scheduled = "2017-01-01T00:00Z"

      val flights = List(
        ArrivalGenerator.live(schDt = scheduled00, iata = "BA0001", terminal = T1, totalPax = Option(15)),
        ArrivalGenerator.live(schDt = scheduled00, iata = "FR8819", terminal = T1, totalPax = Option(10)),
        ArrivalGenerator.live(schDt = scheduled15, iata = "EZ1010", terminal = T2, totalPax = Option(12))
      )

      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        airportConfig = defaultAirportConfig.copy(
          terminalProcessingTimes = procTimes,
          queuesByTerminal = SortedMap(LocalDate(2014, 1, 1) -> SortedMap(T1 -> Seq(Queues.EeaDesk), T2 -> Seq(Queues.EeaDesk))))
        ))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

      val expected1 = Map(
        T1 -> Map(Queues.EeaDesk -> Seq(
          15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
          0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))
      val expected2 = Map(
        T2 -> Map(Queues.EeaDesk -> Seq(
          0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
          12.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

      crunch.portStateTestProbe.fishForMessage(2.seconds) {
        case ps: PortState =>
          val resultSummary = paxLoadsFromPortState(ps, 30)
          resultSummary == expected1
      }

      crunch.portStateTestProbe.fishForMessage(2.seconds) {
        case ps: PortState =>
          val resultSummary = paxLoadsFromPortState(ps, 30)
          resultSummary == (expected1 ++ expected2).toMap
      }

      success
    }
}
