package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared._
import uk.gov.homeoffice.drt.arrivals.ArrivalStatus
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.eeaMachineReadableToDesk
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Terminals.{InvalidTerminal, T1}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.concurrent.duration._

class CrunchFlightExclusionsSpec extends CrunchTestLike {
  sequential
  isolated

  private val oneMinute = 60d / 60

  "Given two flights, one with an invalid terminal " +
    "When I ask for a crunch " +
    "I should only see crunch results for the flight with a valid terminal" >> {
    val scheduled00 = "2017-01-01T00:00Z"
    val scheduled01 = "2017-01-01T00:01Z"

    val scheduled = "2017-01-01T00:00Z"

    val flights = List(
      ArrivalGenerator.live(schDt = scheduled00, iata = "BA0001", terminal = T1, totalPax = Option(15)),
      ArrivalGenerator.live(schDt = scheduled01, iata = "FR8819", terminal = InvalidTerminal, totalPax = Option(10)),
    )

    val crunch = runCrunchGraph(TestConfig(
      now = () => SDate(scheduled),
      airportConfig = defaultAirportConfig.copy(
        terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> oneMinute)),
        queuesByTerminal = SortedMap(LocalDate(2014, 1, 1) -> SortedMap(T1 -> Seq(Queues.EeaDesk))),
        minutesToCrunch = 120
      )))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

    val expected = Map(
      T1 -> Map(Queues.EeaDesk -> Seq(
        15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

    crunch.portStateTestProbe.fishForMessage(5.seconds) {
      case ps: PortState =>
        val resultSummary = paxLoadsFromPortState(ps, 30)
        resultSummary == expected
    }

    success
  }

  "Given four flights, one with status 'cancelled', one status 'canceled', and one with status 'deleted' " +
    "When I ask for a crunch " +
    "I should only see crunch results for the one flight that was not cancelled" >> {
    val scheduled00 = "2017-01-01T00:00Z"
    val scheduled01 = "2017-01-01T00:05Z"
    val scheduled02 = "2017-01-01T00:10Z"
    val scheduled03 = "2017-01-01T00:15Z"

    val scheduled = "2017-01-01T00:00Z"

    val flights = List(
      ArrivalGenerator.live(schDt = scheduled00, iata = "BA0001", terminal = T1, totalPax = Option(15), status = ArrivalStatus("On time")),
      ArrivalGenerator.live(schDt = scheduled01, iata = "FR8819", terminal = T1, totalPax = Option(10), status = ArrivalStatus("xx cancelled xx")),
      ArrivalGenerator.live(schDt = scheduled02, iata = "BA1000", terminal = T1, totalPax = Option(10), status = ArrivalStatus("xx canceled xx")),
      ArrivalGenerator.live(schDt = scheduled03, iata = "ZX0888", terminal = T1, totalPax = Option(10), status = ArrivalStatus("xx deleted xx"))
    )

    val crunch = runCrunchGraph(TestConfig(
      now = () => SDate(scheduled),
      airportConfig = defaultAirportConfig.copy(
        terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> oneMinute)),
        queuesByTerminal = SortedMap(LocalDate(2014, 1, 1) -> SortedMap(T1 -> Seq(Queues.EeaDesk))),
        minutesToCrunch = 120
      )
    ))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

    val expected = Map(
      T1 -> Map(Queues.EeaDesk -> Seq(
        15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

    crunch.portStateTestProbe.fishForMessage(10.seconds) {
      case ps: PortState =>
        val resultSummary = paxLoadsFromPortState(ps, 30)
        resultSummary == expected
    }

    success
  }
}
