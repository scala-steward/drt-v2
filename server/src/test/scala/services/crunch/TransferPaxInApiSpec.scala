package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedSuccess}
import drt.shared._
import services.crunch.VoyageManifestGenerator.{euPassport, inTransitFlag}
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, EventTypes, VoyageNumber}
import uk.gov.homeoffice.drt.models.{ManifestDateOfArrival, ManifestTimeOfArrival, VoyageManifest}
import uk.gov.homeoffice.drt.ports.PaxTypes._
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues._
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode, Queues}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.collection.immutable.{Seq, SortedMap}
import scala.concurrent.duration._

class TransferPaxInApiSpec extends CrunchTestLike {
  sequential
  isolated

  val oneMinute: Double = 60d / 60

  val lhrAirportConfig: AirportConfig = defaultAirportConfig.copy(
    portCode = PortCode("LHR"),
    terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> oneMinute)),
    queuesByTerminal = SortedMap(LocalDate(2014, 1, 1) -> SortedMap(T1 -> Seq(EeaDesk))),
    terminalPaxTypeQueueAllocation = Map(
      T1 -> Map(
        GBRNational -> List(Queues.EeaDesk -> 1.0),
        GBRNationalBelowEgateAge -> List(Queues.EeaDesk -> 1.0),
        EeaMachineReadable -> List(Queues.EeaDesk -> 1.0),
        EeaBelowEGateAge -> List(Queues.EeaDesk -> 1.0),
        EeaNonMachineReadable -> List(Queues.EeaDesk -> 1.0),
        NonVisaNational -> List(Queues.NonEeaDesk -> 1.0),
        VisaNational -> List(Queues.NonEeaDesk -> 1.0),
        B5JPlusNational -> List(Queues.EGate -> 0.6, Queues.EeaDesk -> 0.4),
        B5JPlusNationalBelowEGateAge -> List(Queues.EeaDesk -> 1)
      )
    ),
    hasTransfer = true
  )

  "Given a flight with transfer passengers in the port feed " >> {
    "Then these passengers should not be included in the total pax when using flight pax nos" >> {

      val scheduled = "2017-01-01T00:00Z"

      val flights = Seq(
        ArrivalGenerator.live(
          schDt = scheduled,
          iata = "BA0001",
          terminal = T1,
          totalPax = Option(2),
          transPax = Option(1))
        )


      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        airportConfig = lhrAirportConfig
      ))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

      val expected = 1

      crunch.portStateTestProbe.fishForMessage(1.seconds) {
        case ps: PortState =>
          val totalPaxAtPCP = paxLoadsFromPortState(ps, 60, 0)
            .values
            .flatMap(_.values)
            .flatten
            .sum
          totalPaxAtPCP == expected
      }

      success
    }
  }

  "Given a flight using API for passenger numbers, and which has Transit passengers in the API data " >> {
    "Then these passengers should not be included in the total pax when using API pax nos" >> {
      val scheduled = "2017-01-01T00:00Z"

      val flights = Seq(
        ArrivalGenerator.forecast(origin = PortCode("JFK"), schDt = scheduled, iata = "TST001", terminal = T1)
      )

      val portCode = PortCode("LHR")

      val inputManifests = ManifestsFeedSuccess(
        DqManifests(0,
          Set(
            VoyageManifest(
              EventTypes.DC,
              portCode,
              PortCode("JFK"),
              VoyageNumber(1),
              CarrierCode("TS"),
              ManifestDateOfArrival("2017-01-01"),
              ManifestTimeOfArrival("00:00"),
              List(euPassport, inTransitFlag))
          ))
      )

      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        airportConfig = lhrAirportConfig,

      ))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(flights))

      crunch.portStateTestProbe.fishForMessage(1.seconds) {
        case ps: PortState => ps.flights.nonEmpty
      }

      offerAndWait(crunch.manifestsLiveInput, inputManifests)

      crunch.portStateTestProbe.fishForMessage(2.seconds) {
        case ps: PortState =>
          ps.flights.values.head.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder) == Option(1)
      }

      success
    }
  }
}
