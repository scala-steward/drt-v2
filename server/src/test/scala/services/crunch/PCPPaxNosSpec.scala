package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedResponse, ManifestsFeedSuccess}
import drt.shared._
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, EventTypes, LiveArrival, VoyageNumber}
import uk.gov.homeoffice.drt.models.{ManifestDateOfArrival, ManifestTimeOfArrival, VoyageManifest}
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.eeaChildToDesk
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{PaxTypeAndQueue, PortCode, Queues}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.concurrent.duration._

class PCPPaxNosSpec extends CrunchTestLike {
  sequential
  isolated

  val oneMinute: Double = 60d / 60
  val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(eeaChildToDesk -> oneMinute))

  val scheduled = "2019-11-20T00:00Z"

  val flights: Seq[LiveArrival] = List(
    ArrivalGenerator.live(iata = "BA0001", schDt = scheduled, totalPax = Option(101), origin = PortCode("JFK"))
  )

  val manifests: ManifestsFeedResponse =
    ManifestsFeedSuccess(DqManifests(0, Set(
      VoyageManifest(EventTypes.DC, defaultAirportConfig.portCode, PortCode("JFK"), VoyageNumber("0001"),
        CarrierCode("BA"), ManifestDateOfArrival("2019-11-20"), ManifestTimeOfArrival("00:00"),
        VoyageManifestGenerator.xOfPaxType(100, VoyageManifestGenerator.euPassport))
    )))

  "Given flights with API and live feed passenger numbers then we should use the live feed passenger numbers" >> {

    val crunch = runCrunchGraph(TestConfig(
      now = () => SDate(scheduled),
      airportConfig = defaultAirportConfig.copy(
        terminalProcessingTimes = procTimes,
        queuesByTerminal = SortedMap(LocalDate(2014, 1, 1) -> SortedMap(T1 -> Seq(Queues.EeaDesk)))
      ),
    ))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))
    offerAndWait(crunch.manifestsLiveInput, manifests)

    val expected = Map(T1 -> Map(Queues.EeaDesk -> Seq(20, 20, 20, 20, 20, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)))

    crunch.portStateTestProbe.fishForMessage(2.seconds) {
      case ps: PortState =>
        val resultSummary = paxLoadsFromPortState(ps, 15)
        resultSummary == expected
    }

    success
  }

}
