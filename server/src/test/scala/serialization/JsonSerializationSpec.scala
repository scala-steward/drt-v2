package serialization

import drt.shared.CrunchApi._
import drt.shared.{DrtPortConfigs, FixedPointAssignments, FlightUpdatesAndRemovals, PortState, StaffAssignment, TM}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Percentage
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.auth.Roles
import uk.gov.homeoffice.drt.auth.Roles.Role
import uk.gov.homeoffice.drt.feeds.{FeedStatusFailure, FeedStatusSuccess, FeedStatuses}
import uk.gov.homeoffice.drt.models.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.PaxTypes._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.Historical
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.services.AirportInfoService
import upickle.default._

import scala.collection.immutable.SortedMap

class JsonSerializationSpec extends Specification {

  "Scala case classes can be serialized to JSON and then deserialized back to case classes without data loss" >> {

    "Terminal" >> {

      val terminal: Terminal = T1

      val terminalAsJson: String = write(terminal)

      val terminalDeserialized: Terminal = read[Terminal](terminalAsJson)

      terminal === terminalDeserialized
    }

    "AirportConfig" >> {

      val lhrAirportConfig = DrtPortConfigs.confByPort(PortCode("LHR"))

      val lhrAirportConfigAsJson: String = write(lhrAirportConfig)

      val lhrAirportConfigDeserialized: AirportConfig = read[AirportConfig](lhrAirportConfigAsJson)

      lhrAirportConfig === lhrAirportConfigDeserialized
    }

    "PaxTypes" >> {

      val allPaxTypes = Seq(
        EeaNonMachineReadable,
        Transit,
        VisaNational,
        EeaMachineReadable,
        EeaBelowEGateAge,
        NonVisaNational,
        B5JPlusNational,
        B5JPlusNationalBelowEGateAge,
      )

      val asJson: Seq[String] = allPaxTypes.map((pt: PaxType) => write(pt))

      val deserialized: Seq[PaxType] = asJson.map(s => read[PaxType](s))

      deserialized === allPaxTypes
    }

    "Roles" >> {
      val allRoles = Roles.availableRoles

      val asJson: Set[String] = allRoles.map(r => write(r))

      val deserialized: Set[Role] = asJson.map(s => read[Role](s))

      deserialized === allRoles
    }

    "AirportInfo" >> {
      val info: Map[String, AirportInfo] = AirportInfoService.airportInfoByIataPortCode

      val asJson: String = write(info)

      val deserialized = read[Map[String, AirportInfo]](asJson)

      deserialized === info
    }

    "PortState (empty)" >> {
      val ps = PortState(Seq(), Seq(), Seq())

      val asJson: String = write(ps)

      val deserialized: PortState = read[PortState](asJson)

      deserialized === ps
    }

    "PortState" >> {
      val flightWithSplits = ApiFlightWithSplits(
        Arrival(
          Operator = None,
          Status = ArrivalStatus("scheduled"),
          Estimated = None,
          Predictions = Predictions(0L, Map()),
          Actual = None,
          EstimatedChox = None,
          ActualChox = None,
          Gate = None,
          Stand = None,
          MaxPax = None,
          RunwayID = None,
          BaggageReclaimId = None,
          AirportID = PortCode("test"),
          Terminal = T1,
          rawICAO = "test",
          rawIATA = "test",
          Origin = PortCode("test"),
          PreviousPort = None,
          Scheduled = 0L,
          PcpTime = None,
          FeedSources = Set(AclFeedSource, LiveFeedSource)),
        Set(
          Splits(
            Set(
              ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 1, None, None),
              ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 1, None, None)
            ), Historical, None, Percentage))
      )
      val flightsWithSplits = SortedMap(flightWithSplits.apiFlight.unique -> flightWithSplits)

      val crunchMinutes = SortedMap[TQM, CrunchMinute]() ++ List(
        CrunchMinute(T1, Queues.NonEeaDesk, 0L, 2.0, 2.0, 1, 1, None, None, None, None, Some(0)),
        CrunchMinute(T1, Queues.NonEeaDesk, 0L, 2.0, 2.0, 1, 1, None, None, None, None, Some(0))
      ).map(cm => (TQM(cm), cm))

      val staffMinutes = SortedMap[TM, StaffMinute]() ++ List(
        StaffMinute(T1, 0L, 1, 1, 1, None),
        StaffMinute(T1, 0L, 1, 1, 1, None)
      ).map(sm => (TM(sm), sm))

      val cs = PortState(flightsWithSplits, crunchMinutes, staffMinutes)

      val asJson: String = write(cs)

      val deserialized = read[PortState](asJson)

      deserialized === cs
    }

    "PortStateError" >> {
      val ce = PortStateError("Error Message")

      val json = write(ce)

      val deserialized = read[PortStateError](json)

      deserialized === ce
    }

    "PortStateUpdates" >> {
      val arrival = Arrival(
        Operator = None,
        Status = ArrivalStatus("scheduled"),
        Estimated = None,
        Predictions = Predictions(0L, Map()),
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = None,
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("test"),
        Terminal = T1,
        rawICAO = "test",
        rawIATA = "test",
        Origin = PortCode("test"),
        PreviousPort = None,
        Scheduled = 0L,
        PcpTime = None,
        FeedSources = Set(AclFeedSource, LiveFeedSource),
        PassengerSources = Map()
      )
      val splits = Set(Splits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 1, Option(Map(Nationality("tw") -> 7.0)), None)),
        Historical,
        None,
        Percentage
      ))
      val updatesAndRemovals = FlightUpdatesAndRemovals(
        Map(1L -> ArrivalsDiff(Seq(arrival), Seq(UniqueArrival(100, T1, 60000L, PortCode("STN"))))),
        Map(1L -> SplitsForArrivals(Map(arrival.unique -> splits))),
      )
      val cu = PortStateUpdates(
        0L,
        0L,
        0L,
        updatesAndRemovals,
        Seq(CrunchMinute(T1, Queues.NonEeaDesk, 0L, 2.0, 2.0, 1, 1, None, None, None, None, Some(0))),
        Seq(StaffMinute(T1, 0L, 1, 1,1,None))
      )

      val asJson: String = write(cu)

      val deserialized = read[PortStateUpdates](asJson)

      deserialized === cu
    }

    "FeedStatuses" >> {
      val fss = FeedStatuses(
        List(
          FeedStatusFailure(0L, "failure"),
          FeedStatusSuccess(0L, 3)
        ),
        None,
        None,
        None
      )

      val json = write(fss)

      val deserialized = read[FeedStatuses](json)

      deserialized === fss
    }

    "FixedPointAssignments" >> {
      val fpa = FixedPointAssignments(
        Seq(StaffAssignment("test", T1, 0L, 0L, 0, None))
      )

      val json = write(fpa)

      val deserialized = read[FixedPointAssignments](json)

      deserialized === fpa
    }
  }
}
