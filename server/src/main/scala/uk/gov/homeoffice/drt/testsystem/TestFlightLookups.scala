package uk.gov.homeoffice.drt.testsystem

import actors.FlightLookupsLike
import actors.daily.{RequestAndTerminate, RequestAndTerminateActor}
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.pattern.ask
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Splits, UniqueArrival}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.testsystem.TestActors.{ResetData, TestFlightsRouterActor, TestTerminalDayFlightActor}
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

import scala.concurrent.Future

case class TestFlightLookups(system: ActorSystem,
                             now: () => SDateLike,
                             queuesByTerminal: Map[Terminal, Seq[Queue]],
                             paxFeedSourceOrder: List[FeedSource],
                             terminalSplits: Terminal => Option[Splits],
                             updateLiveView: (Iterable[ApiFlightWithSplits], Iterable[UniqueArrival]) => Future[Unit],
                            ) extends FlightLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "test-flights-lookup-kill-actor")

  val resetFlightsData: (Terminal, UtcDate) => Future[Any] = (terminal: Terminal, date: UtcDate) => {
    val props = Props(new TestTerminalDayFlightActor(date.year, date.month, date.day, terminal, now, paxFeedSourceOrder, None, None, Option(updateLiveView)))
    val actor = system.actorOf(props)
    requestAndTerminateActor.ask(RequestAndTerminate(actor, ResetData))
  }

  override val flightsRouterActor: ActorRef = system.actorOf(
    Props(
      new TestFlightsRouterActor(
        queuesByTerminal.keys,
        flightsByDayLookup(None),
        updateFlights(None, updateLiveView),
        resetFlightsData,
        paxFeedSourceOrder,
      )))
}
