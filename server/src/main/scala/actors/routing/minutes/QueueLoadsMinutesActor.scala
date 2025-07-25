package actors.routing.minutes

import actors.routing.RouterActorLikeWithSubscriber2
import actors.routing.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import drt.shared.CrunchApi.{MinutesContainer, PassengersMinute}
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.models.TQM
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

object QueueLoadsMinutesActor {
  def splitByResource(request: MinutesContainer[PassengersMinute, TQM]): Map[(Terminal, UtcDate), MinutesContainer[PassengersMinute, TQM]] = {
    request.minutes.groupBy(m => (m.terminal, SDate(m.minute).toUtcDate)).map {
      case ((terminal, date), minutes) => ((terminal, date), MinutesContainer(minutes))
    }
  }

  def alwaysSend(request: MinutesContainer[PassengersMinute, TQM]): Boolean = true
}

class QueueLoadsMinutesActor(terminalsForDateRange: (LocalDate, LocalDate) => Seq[Terminal],
                             lookup: MinutesLookup[PassengersMinute, TQM],
                             updateMinutes: MinutesUpdate[PassengersMinute, TQM, TerminalUpdateRequest])
  extends MinutesActorLike2(
    terminalsForDateRange = terminalsForDateRange,
    lookup = lookup,
    updateMinutes = updateMinutes,
    splitByResource = QueueLoadsMinutesActor.splitByResource,
    shouldSendEffects = QueueLoadsMinutesActor.alwaysSend,
  ) with RouterActorLikeWithSubscriber2[MinutesContainer[PassengersMinute, TQM], (Terminal, UtcDate), TerminalUpdateRequest]
