package actors.routing

import actors.FlightLookups
import actors.PartitionedPortStateActor.{GetFlights, GetFlightsForTerminalDateRange, PointInTimeQuery}
import actors.persistent.nebo.NeboArrivalActor
import actors.routing.FlightsRouterActor.runAndCombine
import actors.routing.minutes.MockFlightsLookup
import controllers.ArrivalGenerator
import controllers.model.RedListCounts
import drt.shared._
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{ActorRef, Props}
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestProbe
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.DataUpdates.FlightUpdates
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.SplitStyle.PaxNumbers
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.PaxTypes.EeaNonMachineReadable
import uk.gov.homeoffice.drt.ports.Queues.{EeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.Historical
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.service.QueueConfig
import uk.gov.homeoffice.drt.time.MilliDate.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike, UtcDate}

import scala.collection.SortedMap
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class FlightsRouterActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1

  val date: SDateLike = SDate("2020-01-01T00:00")
  val myNow: () => SDateLike = () => date

  val flightWithSplits: ApiFlightWithSplits = ArrivalGenerator.flightWithSplitsForDayAndTerminal(date, T1, LiveFeedSource)
  val flightsWithSplits: FlightsWithSplits = FlightsWithSplits(List(flightWithSplits))

  val testProbe: TestProbe = TestProbe()

  val noopUpdates: (Option[ActorRef], Option[ActorRef]) => ((Terminal, UtcDate), FlightUpdates) => Future[Set[TerminalUpdateRequest]] =
    (_, _) => (_, _: FlightUpdates) => Future(Set())

  "Concerning visibility of flights (scheduled & pcp range)" >> {
    "Given a flight that is scheduled within the range of dates" >> {
      val fws = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-22T01:00Z"), T1, LiveFeedSource)
      val flights = FlightsWithSplits(List(fws))

      val from = SDate("2020-09-22T00:00Z")
      val to = from.addDays(1).addMinutes(-1)

      val mockLookup = MockFlightsLookup()

      "Then I should get that flight back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          (_, _) => Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates,
          paxFeedSourceOrder,
        )))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1))
          .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1.second)

        result === flights
      }
    }

    "Given multiple flights scheduled on multiple days within the range" >> {
      val fws1 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-22T01:00Z"), T1, LiveFeedSource)
      val fws2 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-23T01:00Z"), T1, LiveFeedSource)
      val fws3 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-24T01:00Z"), T1, LiveFeedSource)
      val flights = FlightsWithSplits(List(fws1, fws2, fws3))

      val from = SDate("2020-09-22T00:00Z")
      val to = SDate("2020-09-25T00:00Z")

      val mockLookup = MockFlightsLookup()

      "Then I should get all the flights back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          (_, _) => Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates,
          paxFeedSourceOrder,
        )))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1))
          .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1.second)

        result === flights
      }
    }

    "Given multiple flights scheduled on days inside and outside the requested range" >> {
      val fws1 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-21T01:00Z"), T1, LiveFeedSource)
      val fws2 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-23T01:00Z"), T1, LiveFeedSource)
      val fws3 = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-25T01:00Z"), T1, LiveFeedSource)
      val flights = FlightsWithSplits(List(fws1, fws2, fws3))

      val from = SDate("2020-09-22T00:00Z")
      val to = SDate("2020-09-24T00:00Z")

      val mockLookup = MockFlightsLookup()

      "Then I should only get back flights within the requested range" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          (_, _) => Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates,
          paxFeedSourceOrder,
        )))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1))
          .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1.second)

        val expected = FlightsWithSplits(List(fws2))
        result === expected
      }
    }

    "Given a flight scheduled before the range and a PCP time within the range" >> {
      val fws1 = ApiFlightWithSplits(
        ArrivalGenerator.live(schDt = "2020-09-22T23:00").toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2020-09-23T00:30").millisSinceEpoch)),
        Set()
      )

      val flights = FlightsWithSplits(List(fws1))

      val from = SDate("2020-09-23T00:00Z")
      val to = SDate("2020-09-24T00:00Z")

      val mockLookup = MockFlightsLookup()

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          (_, _) => Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates,
          paxFeedSourceOrder,
        )))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1))
          .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1.second)

        val expected = FlightsWithSplits(List(fws1))
        result === expected
      }
    }

    "Given a flight scheduled in the range and a PCP time outside the range" >> {
      val fws1 = ApiFlightWithSplits(
        ArrivalGenerator.live(schDt = "2020-09-22T23:00").toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2020-09-23T00:30").millisSinceEpoch)),
        Set()
      )

      val flights = FlightsWithSplits(List(fws1))

      val from = SDate("2020-09-22T00:00Z")
      val to = SDate("2020-09-22T23:01Z")

      val mockLookup = MockFlightsLookup()

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          (_, _) => Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates,
          paxFeedSourceOrder,
        )))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1))
          .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1.second)

        val expected = FlightsWithSplits(List(fws1))
        result === expected
      }
    }

    "Given a flight scheduled 2 days before the requested range and a PCP time in the range" >> {
      val fws1 = ApiFlightWithSplits(
        ArrivalGenerator.live(schDt = "2020-09-21T23:00").toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2020-09-23T00:30").millisSinceEpoch)),
        Set()
      )

      val flights = FlightsWithSplits(List(fws1))

      val from = SDate("2020-09-23T00:00Z")
      val to = SDate("2020-09-25T23:01Z")

      val mockLookup = MockFlightsLookup()

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          (_, _) => Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates,
          paxFeedSourceOrder,
        )))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1))
          .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1.second)

        val expected = FlightsWithSplits(List(fws1))
        result === expected
      }
    }

    "Given a flight scheduled 1 day after the requested range and a PCP time in the range" >> {
      val fws1 = ApiFlightWithSplits(
        ArrivalGenerator.live(schDt = "2020-09-24T23:00").toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2020-09-23T00:30").millisSinceEpoch)),
        Set()
      )

      val flights = FlightsWithSplits(List(fws1))

      val from = SDate("2020-09-23T00:00Z")
      val to = SDate("2020-09-23T01:00Z")

      val mockLookup = MockFlightsLookup()

      "Then I should get back that flight" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          (_, _) => Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates,
          paxFeedSourceOrder,
        )))
        val eventualResult = cmActor.ask(GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1))
          .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1.second)

        val expected = FlightsWithSplits(List(fws1))
        result === expected
      }
    }
  }

  "Concerning visibility of flights (scheduled & pcp range) when using Point in Time Queries" >> {

    "Given a flight that is scheduled within the range of dates" >> {
      val fws = ArrivalGenerator.flightWithSplitsForDayAndTerminal(SDate("2020-09-22T01:00Z"), T1, LiveFeedSource)
      val flights = FlightsWithSplits(List(fws))

      val from = SDate("2020-09-22T00:00Z")
      val to = from.addDays(1).addMinutes(-1)

      val mockLookup = MockFlightsLookup()

      "Then I should get that flight back" >> {
        val cmActor: ActorRef = system.actorOf(Props(new FlightsRouterActor(
          (_, _) => Seq(T1),
          mockLookup.lookup(flights),
          noopUpdates,
          paxFeedSourceOrder,
        )))
        val request = GetFlightsForTerminalDateRange(from.millisSinceEpoch, to.millisSinceEpoch, T1)
        val pitQuery = PointInTimeQuery(SDate("2020-09-22").millisSinceEpoch, request)
        val eventualResult = cmActor.ask(pitQuery).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
        val result: FlightsWithSplits = Await.result(FlightsRouterActor.runAndCombine(eventualResult), 1.second)

        result === flights
      }
    }
  }

  "Concerning persistence of flights" >> {
    "Given a router, I should see updates sent to it are persisted" >> {
      val lookups = FlightLookups(system, myNow, terminalsForDateRange = (_, _) => Seq(T1), None, paxFeedSourceOrder, _ => None, (_, _) => Future.successful(()))
      val router = lookups.flightsRouterActor

      val scheduled = "2021-06-01T00:00"
      val arrival = ArrivalGenerator.live(iata = "BA0001", schDt = scheduled, terminal = T1).toArrival(LiveFeedSource)
      val requestForFlights = GetFlights(SDate(scheduled).millisSinceEpoch, SDate(scheduled).addHours(6).millisSinceEpoch)

      "When I send it a flight with no splits" >> {
        val eventualFlights = router
          .ask(ArrivalsDiff(Iterable(arrival), Iterable()))
          .flatMap(_ => runAndCombine(
            router
              .ask(requestForFlights)
              .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]])
            .map(_.flights.values.headOption))

        val result = Await.result(eventualFlights, 5.second)

        result === Option(ApiFlightWithSplits(arrival, Set(), lastUpdated = Option(myNow().millisSinceEpoch)))
      }

      "When I send it a flight with no splits, followed by its splits" >> {
        val splits = Splits(Set(ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 1, None, None)), Historical, None, PaxNumbers)
        val eventualFlights = router
          .ask(ArrivalsDiff(Iterable(arrival), Iterable()))
          .flatMap(_ => router
            .ask(SplitsForArrivals(Map(arrival.unique -> Set(splits))))
            .flatMap(_ => runAndCombine(
              router
                .ask(requestForFlights)
                .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]])
              .map(_.flights.values.headOption)))

        val result = Await.result(eventualFlights, 5.second)

        result === Option(ApiFlightWithSplits(arrival, Set(splits), lastUpdated = Option(myNow().millisSinceEpoch)))
      }
    }

    "A flights router actor" should {
      val scheduled = "2021-06-24T10:25"
      val redListPax: Seq[String] = util.RandomString.getNRandomString(10, 10)
      val redListPax2: Seq[String] = redListPax ++ util.RandomString.getNRandomString(5, 10)
      val queues: SortedMap[LocalDate, Map[Terminal, Seq[Queue]]] = SortedMap(LocalDate(2014, 1, 1) -> Map(T1 -> Seq.empty[Queue], T2 -> Seq.empty[Queue]))
      val terminalsForDateRange = QueueConfig.terminalsForDateRange(queues)

      "Add red list pax to an existing arrival" in {
        val redListNow = SDate("2021-06-24T12:10:00")
        val lookups = FlightLookups(system, () => redListNow, terminalsForDateRange, None, paxFeedSourceOrder, _ => None, (_, _) => Future.successful(()))
        val redListPassengers = RedListPassengers("BA0001", PortCode("LHR"), SDate(scheduled), redListPax)
        val neboArrivalActor: ActorRef = system.actorOf(NeboArrivalActor.props(redListPassengers, () => redListNow))
        val arrival = ArrivalGenerator.live(iata = "BA0001", terminal = T1, schDt = scheduled).toArrival(LiveFeedSource)
        val flightsRouter = lookups.flightsRouterActor

        Await.ready(neboArrivalActor ? redListPassengers, 10.second)
        Await.ready(flightsRouter ? ArrivalsDiff(Seq(arrival), Seq()), 1.second)
        Await.ready(flightsRouter ? RedListCounts(Seq(redListPassengers)), 15.second)
        val eventualFlights = (flightsRouter ? GetFlightsForTerminalDateRange(redListNow.getLocalLastMidnight.millisSinceEpoch, redListNow.getLocalNextMidnight.millisSinceEpoch, T1)).flatMap {
          case source: Source[(UtcDate, FlightsWithSplits), NotUsed] => source.runFold(FlightsWithSplits.empty)(_ ++ _._2)
        }

        val flights = Await.result(eventualFlights, 1.second)

        flights.flights.values.head.apiFlight === arrival.copy(RedListPax = Option(redListPax.size))
      }

      "Add red list pax counts to the appropriate arrivals" in {
        val redListNow = SDate("2021-06-24T12:10:00")
        val lookups = FlightLookups(system, () => redListNow, terminalsForDateRange, None, paxFeedSourceOrder, _ => None, (_, _) => Future.successful(()))
        val flightsRouter = lookups.flightsRouterActor
        val scheduled2 = "2021-06-24T15:05"
        val arrivalT1 = ArrivalGenerator.live(iata = "BA0001", terminal = T1, schDt = scheduled).toArrival(LiveFeedSource)
        val arrivalT2 = ArrivalGenerator.live(iata = "AB1234", terminal = T2, schDt = scheduled2).toArrival(LiveFeedSource)
        Await.ready(flightsRouter ? ArrivalsDiff(Seq(arrivalT1, arrivalT2), Seq()), 1.second)
        val redListPax = util.RandomString.getNRandomString(10, 10)
        val redListPassengers = Seq(
          RedListPassengers("BA0001", PortCode("LHR"), SDate(scheduled), redListPax),
          RedListPassengers("EZT1234", PortCode("LHR"), SDate(scheduled2), redListPax2),
        )

        RedListCounts(redListPassengers).passengers.map { redListPassenger =>
          val neboArrivalActor = system.actorOf(NeboArrivalActor.props(redListPassenger, () => redListNow))
          Await.ready(neboArrivalActor ? redListPassenger, 10.second)
        }

        Await.ready(flightsRouter ? RedListCounts(redListPassengers), 10.second)


        val eventualFlights = (flightsRouter ? GetFlights(redListNow.getLocalLastMidnight.millisSinceEpoch, redListNow.getLocalNextMidnight.millisSinceEpoch)).flatMap {
          case source: Source[(UtcDate, FlightsWithSplits), NotUsed] => source.runFold(FlightsWithSplits.empty)(_ ++ _._2)
        }
        val arrivals = Await.result(eventualFlights, 1.second).flights.values.map(_.apiFlight).toList

        arrivals.contains(arrivalT1.copy(RedListPax = Option(redListPax.size))) && arrivals.contains(arrivalT2.copy(RedListPax = Option(redListPax2.size)))
      }

      "Retain red list pax counts after updating an arrival" in {
        val redListNow = SDate("2021-06-24T12:10:00")
        val lookups = FlightLookups(system, () => redListNow, terminalsForDateRange, None, paxFeedSourceOrder, _ => None, (_, _) => Future.successful(()))
        val flightsRouter = lookups.flightsRouterActor
        val arrivalT1 = ArrivalGenerator.live(iata = "BA0001", terminal = T1, schDt = scheduled).toArrival(LiveFeedSource)
        val redListPax = util.RandomString.getNRandomString(10, 10)
        val redListPassengers = RedListPassengers("BA0001", PortCode("LHR"), SDate(scheduled), redListPax)
        Await.ready(flightsRouter ? ArrivalsDiff(Seq(arrivalT1), Seq()), 1.second)
        RedListCounts(Seq(redListPassengers)).passengers.map { redListPassenger =>
          val neboArrivalActor = system.actorOf(NeboArrivalActor.props(redListPassenger, () => redListNow))
          Await.ready(neboArrivalActor ? redListPassengers, 1.second)

        }
        Await.ready(flightsRouter ? RedListCounts(Seq(redListPassengers)), 10.second)


        val updatedArrival = arrivalT1.copy(Estimated = Option(10L))
        Await.ready(flightsRouter ? ArrivalsDiff(Iterable(updatedArrival), Seq()), 10.second)

        val eventualFlights = (flightsRouter ? GetFlights(redListNow.getLocalLastMidnight.millisSinceEpoch, redListNow.getLocalNextMidnight.millisSinceEpoch)).flatMap {
          case source: Source[(UtcDate, FlightsWithSplits), NotUsed] => source.runFold(FlightsWithSplits.empty)(_ ++ _._2)
        }
        val arrivals = Await.result(eventualFlights, 1.second).flights.values.map(_.apiFlight).toList

        arrivals.contains(updatedArrival.copy(RedListPax = Option(redListPax.size)))
      }

    }
  }

  "Concerning multi-terminal queries" >> {
    val terminals: Seq[Terminal] = List(T2, T3, T4, T5)

    val t21015 = ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0001", origin = PortCode("JFK"), terminal = T2)
      .toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2021-07-10T15:00").millisSinceEpoch)), Set())
    val t21013 = ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0002", origin = PortCode("JFK"), terminal = T2)
      .toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2021-07-10T13:00").millisSinceEpoch)), Set())
    val t31015 = ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0003", origin = PortCode("JFK"), terminal = T3)
      .toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2021-07-10T15:00").millisSinceEpoch)), Set())
    val t31013 = ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0004", origin = PortCode("JFK"), terminal = T3)
      .toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2021-07-10T13:00").millisSinceEpoch)), Set())
    val t21115 = ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0005", origin = PortCode("JFK"), terminal = T2)
      .toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2021-07-10T15:00").millisSinceEpoch)), Set())
    val t21113 = ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0006", origin = PortCode("JFK"), terminal = T2)
      .toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2021-07-10T13:00").millisSinceEpoch)), Set())
    val t31115 = ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0007", origin = PortCode("JFK"), terminal = T3)
      .toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2021-07-10T15:00").millisSinceEpoch)), Set())
    val t31113 = ApiFlightWithSplits(ArrivalGenerator.live(iata = "BA0008", origin = PortCode("JFK"), terminal = T3)
      .toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2021-07-10T13:00").millisSinceEpoch)), Set())
    val flights: Map[(Terminal, UtcDate), FlightsWithSplits] = Map(
      (T2, UtcDate(2021, 7, 10)) -> FlightsWithSplits(List(t21015, t21013)),
      (T3, UtcDate(2021, 7, 10)) -> FlightsWithSplits(List(t31015, t31013)),
      (T2, UtcDate(2021, 7, 11)) -> FlightsWithSplits(List(t21115, t21113)),
      (T3, UtcDate(2021, 7, 11)) -> FlightsWithSplits(List(t31115, t31113)),
    )

    def flightsForDayAndTerminal(d: UtcDate)(t: Terminal): Future[FlightsWithSplits] =
      Future.successful(flights.getOrElse((t, d), FlightsWithSplits.empty))

    "sortedSourceForIterables" should {
      "produce a FlightsWithSplits for each date, with flights from all terminals sorted by pcp time & voyage number" in {
        val flightsByDayAndTerminalProvider: Option[MillisSinceEpoch] => UtcDate => Terminal => Future[FlightsWithSplits] = _ => flightsForDayAndTerminal

        val flightsStream = FlightsRouterActor.multiTerminalFlightsByDaySource(flightsByDayAndTerminalProvider)(SDate(UtcDate(2021, 7, 10)), SDate(UtcDate(2021, 7, 11)), terminals, None, paxFeedSourceOrder)

        val result = Await.result(flightsStream.runWith(Sink.seq), 1.second)

        result === Seq((UtcDate(2021, 7, 10), FlightsWithSplits(Seq(t21013, t31013, t21015, t31015))), (UtcDate(2021, 7, 11), FlightsWithSplits(Seq(t21113, t31113, t21115, t31115))))
      }
    }

  }
}
