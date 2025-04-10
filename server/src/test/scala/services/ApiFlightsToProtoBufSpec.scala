package services

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, Operator, Passengers, Predictions}
import uk.gov.homeoffice.drt.ports.Terminals.T2
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, PortCode}
import uk.gov.homeoffice.drt.prediction.arrival.OffScheduleModelAndFeatures
import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion.{apiFlightToFlightMessage, flightMessageToApiFlight}
import uk.gov.homeoffice.drt.time.SDate

class ApiFlightsToProtoBufSpec extends Specification {
  "apiFlightToFlightMessage" should {
    "take a single Arrival and return a FlightMessage representing it" in {
      val apiFlight = Arrival(
        Operator = Option(Operator("Op")),
        Status = ArrivalStatus("scheduled"),
        Estimated = Option(SDate("2016-01-01T13:05:00Z").millisSinceEpoch),
        Predictions = Predictions(SDate.now().millisSinceEpoch, Map(OffScheduleModelAndFeatures.targetName -> 55)),
        Actual = Option(SDate("2016-01-01T13:10:00Z").millisSinceEpoch),
        EstimatedChox = Option(SDate("2016-01-01T13:15:00Z").millisSinceEpoch),
        ActualChox = Option(SDate("2016-01-01T13:20:00Z").millisSinceEpoch),
        Gate = Option("10"),
        Stand = Option("10A"),
        MaxPax = Option(200),
        RunwayID = Option("1"),
        BaggageReclaimId = Option("A"),
        AirportID = PortCode("LHR"),
        Terminal = T2,
        rawICAO = "BAA0001",
        rawIATA = "BA0001",
        Origin = PortCode("JFK"),
        PreviousPort = Option(PortCode("ABZ")),
        PcpTime = Option(1451655000000L),
        Scheduled = SDate("2016-01-01T13:00:00Z").millisSinceEpoch,
        FeedSources = Set(ApiFeedSource),
        CarrierScheduled = Option(100L),
        PassengerSources = Map(ApiFeedSource -> Passengers(Option(150),Option(10)))
      )
      val flightMessage = apiFlightToFlightMessage(apiFlight)
      val deserialised = flightMessageToApiFlight(flightMessage)

      deserialised === apiFlight
    }
  }
}
