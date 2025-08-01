package actors.daily

import actors.serializers.ManifestMessageConversion
import drt.shared.CrunchApi.MillisSinceEpoch
import org.apache.pekko.actor.Props
import org.apache.pekko.persistence.SaveSnapshotSuccess
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.RecoveryActorLike
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.models.{ManifestKey, VoyageManifest, VoyageManifests}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.VoyageManifest.VoyageManifestsMessage
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike, UtcDate}

object DayManifestActor {
  def props(date: UtcDate, terminals: LocalDate => Iterable[Terminal]): Props =
    Props(new DayManifestActor(date.year, date.month, date.day, None, terminals))

  def propsPointInTime(date: UtcDate, pointInTime: MillisSinceEpoch, terminals: LocalDate => Iterable[Terminal]): Props =
    Props(new DayManifestActor(date.year, date.month, date.day, Option(pointInTime), terminals))
}


class DayManifestActor(year: Int, month: Int, day: Int, override val maybePointInTime: Option[MillisSinceEpoch], terminalsForDate: LocalDate => Iterable[Terminal])
  extends RecoveryActorLike {

  def now: () => SDate.JodaSDate = () => SDate.now()

  val loggerSuffix: String = maybePointInTime match {
    case None => ""
    case Some(pit) => f"@${SDate(pit).toISOString}"
  }

  val firstMinuteOfDay: SDateLike = SDate(year, month, day, 0, 0)
  val lastMinuteOfDay: SDateLike = firstMinuteOfDay.addDays(1).addMinutes(-1)

  override val log: Logger = LoggerFactory.getLogger(f"$getClass-$year%04d-$month%02d-$day%02d$loggerSuffix")

  override def persistenceId: String = f"manifests-$year-$month%02d-$day%02d"

  private val maxSnapshotInterval = 250
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)

  var state: Map[ManifestKey, VoyageManifest] = Map()

  override def receiveCommand: Receive = {
    case manifests: VoyageManifests =>
      updateAndPersist(manifests)

    case GetState =>
      log.debug(s"Received GetState")
      sender() ! VoyageManifests(state.values.toSet)

    case _: SaveSnapshotSuccess =>
      ackIfRequired()

    case m => log.error(s"Got unexpected message: $m")
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {

    case vmm@VoyageManifestsMessage(Some(createdAt), _) =>
      maybePointInTime match {
        case Some(pit) if pit < createdAt =>
        case _ =>
          state = state ++ ManifestMessageConversion.voyageManifestsFromMessage(vmm).toMap
      }
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case vmm: VoyageManifestsMessage =>
      state = ManifestMessageConversion.voyageManifestsFromMessage(vmm).toMap
  }

  override def stateToMessage: GeneratedMessage = ManifestMessageConversion
    .voyageManifestsToMessage(VoyageManifests(state.values.toSet))

  private def updateAndPersist(vms: VoyageManifests): Unit = {
    state = state ++ vms.toMap

    val updateRequests = vms.manifests
      .map(vm => vm.scheduled.toLocalDate)
      .flatMap(ms => terminalsForDate(SDate.now().toLocalDate).map(TerminalUpdateRequest(_, ms)))
      .toSet

    val replyToAndMessage = List((sender(), updateRequests))
    persistAndMaybeSnapshotWithAck(ManifestMessageConversion.voyageManifestsToMessage(vms), replyToAndMessage)
  }
}
