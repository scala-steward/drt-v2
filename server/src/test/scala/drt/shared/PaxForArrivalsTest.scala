package drt.shared

import drt.shared.FlightsApi.PaxForArrivals
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, ArrivalsDiff, FlightsWithSplits, Passengers}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports._

class PaxForArrivalsTest extends Specification {
  "When I ask to extract the historic api nos" >> {
    "Given an arrival with no passenger totals" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("ABC")).toArrival(LiveFeedSource)
      "I should get an empty PaxForArrivals" >> {
        PaxForArrivals.from(Seq(arrival), HistoricApiFeedSource) === PaxForArrivals.empty
      }
    }

    "Given an arrival with live api passenger totals" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("ABC"), totalPax = Option(100)).toArrival(LiveFeedSource)
      "I should get an empty PaxForArrivals" >> {
        PaxForArrivals.from(Seq(arrival), HistoricApiFeedSource) === PaxForArrivals.empty
      }
    }

    "Given an arrival with live & historic api passenger totals" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("ABC")).toArrival(LiveFeedSource).copy(
        PassengerSources = Map(ApiFeedSource -> Passengers(Option(100), None), HistoricApiFeedSource -> Passengers(Option(100), None)))
      "I should get a PaxForArrivals with HistoricApiFeedSource with 100 passengers" >> {
        PaxForArrivals.from(Seq(arrival), HistoricApiFeedSource) === PaxForArrivals(Map(
          arrival.unique -> Map[FeedSource, Passengers](HistoricApiFeedSource -> Passengers(Option(100), None))
        ))
      }
    }
  }

  "When I apply PaxForArrivals to FlightWithSplits" >> {
    "Given no new passengers and " >> {
      val paxForArrivals = PaxForArrivals.empty

      "No existing flights" >> {
        val flights = FlightsWithSplits(Seq())

        "Then I should get an empty FlightsWithSplits" >> {
          val updated = paxForArrivals.diff(flights, 1L)
          updated === ArrivalsDiff.empty
        }
      }

      "One existing flight" >> {
        val arrival = ArrivalGenerator.arrival(iata = "BA0001").toArrival(LiveFeedSource)
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set())))

        "Then I should get an empty FlightsWithSplits" >> {
          val updated = paxForArrivals.diff(flights, 1L)
          updated === ArrivalsDiff.empty
        }
      }
    }

    "Given one new passenger" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001").toArrival(LiveFeedSource)
      val livePax: Map[FeedSource, Passengers] = Map(LiveFeedSource -> Passengers(Option(1), None))
      val paxForArrivals = PaxForArrivals(Map(arrival.unique -> livePax))

      "No existing flights" >> {
        val flights = FlightsWithSplits(Seq())

        "Then I should get an empty FlightsWithSplits" >> {
          val updated = paxForArrivals.diff(flights, 1L)
          updated === ArrivalsDiff.empty
        }
      }

      "One existing flight with passengers that match the existing passengers" >> {
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival.copy(PassengerSources = livePax), Set())))

        "Then I should get an empty FlightsWithSplits" >> {
          val updated = paxForArrivals.diff(flights, 1L)
          updated === ArrivalsDiff.empty
        }
      }

      "One existing flight with passengers that match the existing passengers plus another source" >> {
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival.copy(PassengerSources = livePax.updated(ForecastFeedSource, Passengers(Option(2), None))), Set())))

        "Then I should get an empty FlightsWithSplits" >> {
          val updated = paxForArrivals.diff(flights, 1L)
          updated === ArrivalsDiff.empty
        }
      }

      "One existing flight with passengers that don't match the existing passengers" >> {
        val arrival1 = arrival.copy(PassengerSources = livePax.updated(LiveFeedSource, Passengers(Option(2), None)))
        val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival1, Set())))

        "Then I should get a FlightsWithSplits containing the updated flight" >> {
          val updated = paxForArrivals.diff(flights, 1L)
          val expectedArrival = arrival1.copy(PassengerSources = Map(LiveFeedSource -> Passengers(Option(1), None)))
          updated === ArrivalsDiff(Seq(expectedArrival), Seq())
        }
      }


    }
  }
}
