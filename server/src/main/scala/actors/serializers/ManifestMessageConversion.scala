package actors.serializers

import manifests.passengers.BestAvailableManifest
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, EventType, VoyageNumber}
import uk.gov.homeoffice.drt.models._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSource
import uk.gov.homeoffice.drt.ports.{PaxAge, PortCode}
import uk.gov.homeoffice.drt.protobuf.messages.VoyageManifest._
import uk.gov.homeoffice.drt.time.SDate

import scala.util.Try

object ManifestMessageConversion {

  def passengerInfoFromMessage(m: PassengerInfoJsonMessage): PassengerInfoJson = PassengerInfoJson(
    DocumentType = m.documentType.map(DocumentType(_)),
    DocumentIssuingCountryCode = m.documentIssuingCountryCode
      .map(n => Nationality(correctNationalityBug(n))).getOrElse(Nationality("")),
    EEAFlag = EeaFlag(m.eeaFlag.getOrElse("")),
    Age = m.age.flatMap(ageString => Try(ageString.toInt).toOption).map(a => PaxAge(a)),
    DisembarkationPortCode = m.disembarkationPortCode.map(PortCode(_)),
    InTransitFlag = InTransit(m.inTransitFlag.getOrElse("")),
    DisembarkationPortCountryCode = m.disembarkationPortCountryCode.map(n => Nationality(correctNationalityBug(n))),
    NationalityCountryCode = m.nationalityCountryCode.map(n => Nationality(correctNationalityBug(n))),
    PassengerIdentifier = m.passengerIdentifier
  )

  def manifestPassengerProfileFromMessage(m: ManifestPassengerProfileMessage): ManifestPassengerProfile = ManifestPassengerProfile(
    nationality = Nationality(correctNationalityBug(m.nationality.getOrElse(""))),
    documentType = m.documentType.map(DocumentType(_)),
    age = m.age.flatMap(ageString => Try(ageString.toInt).toOption).map(a => PaxAge(a)),
    inTransit = m.inTransit.getOrElse(false),
    passengerIdentifier = m.passengerIdentifier
  )

  def correctNationalityBug(nationality: String): String =
    nationality
      .replace("Nationality(", "").replace(")", "")

  def voyageManifestFromMessage(m: VoyageManifestMessage): VoyageManifest = {
    VoyageManifest(
      EventCode = EventType(m.eventCode.getOrElse("")),
      ArrivalPortCode = PortCode(m.arrivalPortCode.getOrElse("")),
      DeparturePortCode = PortCode(m.departurePortCode.getOrElse("")),
      VoyageNumber = VoyageNumber(m.voyageNumber.getOrElse("")),
      CarrierCode = CarrierCode(m.carrierCode.getOrElse("")),
      ScheduledDateOfArrival = ManifestDateOfArrival(m.scheduledDateOfArrival.getOrElse("")),
      ScheduledTimeOfArrival = ManifestTimeOfArrival(m.scheduledTimeOfArrival.getOrElse("")),
      PassengerList = m.passengerList.toList.map(passengerInfoFromMessage)
    )
  }

  def maybeManifestLikeFromMessage(m: MaybeManifestLikeMessage): Option[ManifestLike] =
    m.maybeVoyageManifest.map(manifestLikeFromMessage)

  def manifestLikeFromMessage(m: ManifestLikeMessage): ManifestLike = {
    BestAvailableManifest(
      maybeEventType = m.eventCode.map(EventType(_)),
      source = SplitSource(m.splitSource.getOrElse("")),
      arrivalPortCode = PortCode(m.arrivalPortCode.getOrElse("")),
      departurePortCode = PortCode(m.departurePortCode.getOrElse("")),
      voyageNumber = VoyageNumber(m.voyageNumber.getOrElse("")),
      carrierCode = CarrierCode(m.carrierCode.getOrElse("")),
      scheduled = SDate(s"${m.scheduledDateOfArrival.getOrElse("")}T${m.scheduledTimeOfArrival.getOrElse("")}"),
      nonUniquePassengers = m.passengerList.toList.map(manifestPassengerProfileFromMessage)
    )
  }

  def voyageManifestsFromMessage(vmms: VoyageManifestsMessage): VoyageManifests = VoyageManifests(
    vmms.manifestMessages.map(voyageManifestFromMessage).toSet
  )

  def voyageManifestsToMessage(vms: VoyageManifests): VoyageManifestsMessage = VoyageManifestsMessage(
    Option(SDate.now().millisSinceEpoch),
    vms.manifests.map(voyageManifestToMessage).toSeq
  )

  def voyageManifestToMessage(vm: VoyageManifest): VoyageManifestMessage = {
    VoyageManifestMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      eventCode = Option(vm.EventCode.toString),
      arrivalPortCode = Option(vm.ArrivalPortCode.iata),
      departurePortCode = Option(vm.DeparturePortCode.iata),
      voyageNumber = Option(vm.VoyageNumber.toString),
      carrierCode = Option(vm.CarrierCode.code),
      scheduledDateOfArrival = Option(vm.ScheduledDateOfArrival.date),
      scheduledTimeOfArrival = Option(vm.ScheduledTimeOfArrival.time),
      passengerList = vm.PassengerList.map(passengerInfoToMessage)
    )
  }

  def manifestLikeToMessage(vm: ManifestLike): ManifestLikeMessage = {
    ManifestLikeMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      splitSource = Option(vm.source.toString),
      eventCode = Option(vm.maybeEventType.map(_.toString).getOrElse("")),
      arrivalPortCode = Option(vm.arrivalPortCode.iata),
      departurePortCode = Option(vm.departurePortCode.iata),
      voyageNumber = Option(vm.voyageNumber.toString),
      carrierCode = Option(vm.carrierCode.code),
      scheduledDateOfArrival = Option(vm.scheduled.toISODateOnly),
      scheduledTimeOfArrival = Option(vm.scheduled.toHoursAndMinutes),
      passengerList = vm.uniquePassengers.map(manifestPassengerProfileToMessage)
    )
  }

  def passengerInfoToMessage(pi: PassengerInfoJson): PassengerInfoJsonMessage = {
    PassengerInfoJsonMessage(
      documentType = pi.DocumentType.map(_.toString),
      documentIssuingCountryCode = Option(pi.DocumentIssuingCountryCode.toString),
      eeaFlag = Option(pi.EEAFlag.value),
      age = pi.Age.map(_.toString),
      disembarkationPortCode = pi.DisembarkationPortCode.map(_.toString),
      inTransitFlag = Option(pi.InTransitFlag.toString),
      disembarkationPortCountryCode = pi.DisembarkationPortCountryCode.map(_.toString),
      nationalityCountryCode = pi.NationalityCountryCode.map(_.toString),
      passengerIdentifier = pi.PassengerIdentifier
    )
  }

  def manifestPassengerProfileToMessage(pi: ManifestPassengerProfile): ManifestPassengerProfileMessage = {
    ManifestPassengerProfileMessage(
      nationality = Option(pi.nationality.toString),
      documentType = Option(pi.documentType.map(_.toString).getOrElse("")),
      age = pi.age.map(_.toString()),
      inTransit = Option(pi.inTransit),
      passengerIdentifier = pi.passengerIdentifier
    )
  }
}
