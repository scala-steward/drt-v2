package drt.server.feeds.lgw

import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals
import uk.gov.homeoffice.drt.ports.Terminals.{InvalidTerminal, N, S}
import uk.gov.homeoffice.drt.time.SDate

import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import scala.xml.Node

case class ResponseToArrivals(data: String) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def getArrivals: List[LiveArrival] = Try {
    scala.xml.Utility
      .trimProper(scala.xml.XML.loadString(data))
      .map(nodeToArrival)
  } match {
    case Success(arrivals) => arrivals.toList
    case Failure(t) =>
      log.error(s"Failed to get an Arrival from the Gatwick XML.", t)
      List.empty[LiveArrival]
  }

  private def nodeToArrival: Node => LiveArrival = (n: Node) => {
    val operator = (n \ "AirlineIATA") text
    val totalPax = parsePaxCount(n, "70A").orElse(None)
    val transPax = parsePaxCount(n, "TIP")
    val carrier = n \\ "AirlineIATA" text
    val (carrierCode, voyageNumber, suffix) = FlightCode.flightCodeToParts(carrier + flightNumber(n))

    val arrival = LiveArrival(
      operator = if (operator.isEmpty) None else Option(operator),
      maxPax = (n \\ "SeatCapacity").headOption.map(n => (n text).toInt),
      totalPax = totalPax,
      transPax = transPax,
      terminal = parseTerminal(n),
      voyageNumber = voyageNumber.numeric,
      carrierCode = carrierCode.code,
      flightCodeSuffix = suffix.map(_.suffix),
      origin = parseOrigin(n),
      previousPort = None,
      scheduled = (((n \ "FlightLeg").head \ "LegData").head \\ "OperationTime")
        .find(n => (n \ "@OperationQualifier" text).equals("ONB") && (n \ "@TimeType" text).equals("SCT"))
        .map(n => SDate.parseString(n text).millisSinceEpoch).getOrElse(0),
      estimated = parseDateTime(n, operationQualifier = "TDN", timeType = "EST"),
      touchdown = parseDateTime(n, operationQualifier = "TDN", timeType = "ACT"),
      estimatedChox = parseDateTime(n, operationQualifier = "ONB", timeType = "EST"),
      actualChox = parseDateTime(n, operationQualifier = "ONB", timeType = "ACT"),
      status = parseStatus(n),
      gate = (n \\ "PassengerGate").headOption.map(n => n text).filterNot(_.isBlank),
      stand = (n \\ "ArrivalStand").headOption.map(n => n text).filterNot(_.isBlank),
      runway = parseRunwayId(n).filterNot(_.isBlank),
      baggageReclaim = Try(n \\ "BaggageClaimUnit" text).toOption.filterNot(_.isBlank),
    )
    log.debug(s"parsed arrival: $arrival")
    arrival
  }

  private def parseTerminal(n: Node): Terminals.Terminal = {
    val terminal = (n \\ "AirportResources" \ "Resource")
      .find(n => (n \ "@DepartureOrArrival" text).equals("Arrival")).map(n => n \\ "AircraftTerminal" text).getOrElse("")
    val mappedTerminal = terminal match {
      case "1" => S
      case "2" => N
      case _ => InvalidTerminal
    }
    mappedTerminal
  }

  private def flightNumber(n: Node): String =
    ((n \ "FlightLeg").head \ "LegIdentifier").head \ "FlightNumber" text

  private def parseStatus(n: Node): String = {
    val aidxCodeOrIdahoCode = ((n \ "FlightLeg").head \ "LegData").head \ "OperationalStatus" text

    aidxCodeOrIdahoCode match {
      case "DV" => "Diverted"
      case "DX" | "CX" => "Cancelled"
      case "EST" | "ES" => "Estimated"
      case "EXP" | "EX" => "Expected"
      case "FRB" | "FB" => "First Bag Delivered"
      case "LAN" | "LD" => "Landed"
      case "LSB" | "LB" => "Last Bag Delivered"
      case "NIT" | "NI" => "Next Information Time"
      case "ONB" | "OC" => "On Chocks"
      case "OVS" | "OV" => "Overshoot"
      case "REM" | "**" => "Deleted / Removed Flight Record"
      case "SCT" | "SH" => "Scheduled"
      case "TEN" | "FS" => "Final Approach"
      case "THM" | "ZN" => "Zoning"
      case "UNK" | "??" => "Unknown"
      case "FCT" | "LC" => "Last Call (Departure Only)"
      case "BST" | "BD" => "Boarding (Departure Only)"
      case "GCL" | "GC" => "Gate Closed (Departure Only)"
      case "GOP" | "GO" => "Gate Opened (Departure Only)"
      case "RST" | "RS" => "Return to Stand (Departure Only)"
      case "OFB" | "TX" => "Taxied (Departure Only)"
      case "TKO" | "AB" => "Airborne (Departure Only)"
      case unknownCode => unknownCode
    }
  }

  private def parseOrigin(n: Node): String = {
    ((n \ "FlightLeg").head \ "LegIdentifier").head \ "DepartureAirport" text
  }

  private def parseRunwayId(n: Node): Option[String] = {
    (n \\ "AirportResources" \ "Resource").find(n => (n \ "@DepartureOrArrival" text).equals("Arrival")).map(n => n \\ "Runway" text)
  }

  private def parsePaxCount(n: Node, qualifier: String): Option[Int] = {
    (n \\ "CabinClass").find(n => (n \ "@Class").isEmpty).flatMap(n => (n \ "PaxCount").find(n => (n \ "@Qualifier" text).equals(qualifier)).map(n => (n text).toInt))
  }

  def parseDateTime(n: Node, operationQualifier: String, timeType: String): Option[MillisSinceEpoch] = {
    (((n \ "FlightLeg").head \ "LegData").head \\ "OperationTime").find(n => (n \ "@OperationQualifier" text).equals(operationQualifier) && (n \ "@TimeType" text).equals(timeType)).map(n => SDate.parseString(n text).millisSinceEpoch)
  }

}
