package providers

import actors.PartitionedPortStateActor.UtcDateRangeLike
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout
import drt.shared.CrunchApi.MinutesContainer
import uk.gov.homeoffice.drt.arrivals.WithTimeAccessor
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.UtcDate

object MinutesProvider {
  def singleTerminal[A, B <: WithTimeAccessor](minutesRouterActor: ActorRef)
                                              (implicit timeout: Timeout): Terminal => (UtcDate, UtcDate) => Source[(UtcDate, Seq[A]), NotUsed] =
    terminal => (start, end) => {
      val request = actors.routing.minutes.GetStreamingMinutesForTerminalDateRange(terminal, start, end)
      minutesByUtcDate(minutesRouterActor, request)
    }

  def allTerminals[A, B <: WithTimeAccessor](minutesRouterActor: ActorRef)
                                            (implicit timeout: Timeout): (UtcDate, UtcDate) => Source[(UtcDate, Seq[A]), NotUsed] =
    (start, end) => {
      val request = actors.routing.minutes.GetStreamingMinutesForDateRange(start, end)
      minutesByUtcDate(minutesRouterActor, request)
    }

  private def minutesByUtcDate[B <: WithTimeAccessor, A](staffMinutesRouterActor: ActorRef, request: UtcDateRangeLike)
                                                        (implicit timeout: Timeout): Source[(UtcDate, Seq[A]), NotUsed] =
    Source
      .future(
        staffMinutesRouterActor.ask(request)
          .mapTo[Source[(UtcDate, MinutesContainer[A, B]), NotUsed]]
      )
      .flatMapConcat(identity)
      .map {
        case (date, container) => (date, container.minutes.map(_.toMinute).toSeq)
      }
}
