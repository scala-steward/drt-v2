package drt.client.components

import diode.data.Pot
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.MillisSinceEpoch
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

case class ApiFeedStatus(totalLanded: Int, withApi: Int, withValidApi: Int) {
  val receivedPct: Option[Double] = if (totalLanded > 0) Option((withApi.toDouble / totalLanded) * 100) else None
  val validPct: Option[Double] = if (withApi > 0) Option((withValidApi.toDouble / withApi) * 100) else None
}

object ApiFeedStatus {
  def apply(flights: Iterable[ApiFlightWithSplits],
            nowMillis: MillisSinceEpoch,
            hasLiveFeed: Boolean,
            paxFeedSourceOrder: List[FeedSource],
           ): ApiFeedStatus = {
    val landed = flights.filter(fws => fws.apiFlight.bestArrivalTime(considerPredictions = true) <= nowMillis)
    val international = landed.filterNot(fws => fws.apiFlight.Origin.isDomesticOrCta)
    val withNonZeroPax = international.filter(fws => fws.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder).exists(_ > 0) || !hasLiveFeed)
    val apiFlightCount = withNonZeroPax.count(_.hasApi)
    val validApiCount = withNonZeroPax.count(_.hasValidApi)
    ApiFeedStatus(withNonZeroPax.size, apiFlightCount, validApiCount)
  }
}

object ApiStatusComponent {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(canValidate: Boolean, terminal: Terminal)

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ApiStatus")
    .render_P { props =>
      case class FlightsAndPaxFeedOrder(flights: Pot[Iterable[ApiFlightWithSplits]], paxFeedSourceOrder: List[FeedSource])
      val terminalFlightsProxy = SPACircuit.connect { m =>
        FlightsAndPaxFeedOrder(m.portStatePot.map(_.flights.values.filter(_.apiFlight.Terminal == props.terminal)), m.paxFeedSourceOrder)
      }

      terminalFlightsProxy { terminalFlightsPot =>
        <.div(
          terminalFlightsPot().flights.renderReady { flights =>
            val apiFeedStatus = ApiFeedStatus(flights, SDate.now().millisSinceEpoch, props.canValidate, terminalFlightsPot().paxFeedSourceOrder)

            def ragClass(pct: Option[Double]): String = pct match {
              case Some(red) if red < 80 => "red"
              case Some(amber) if amber < 90 => "amber"
              case Some(_) => "green"
              case None => "grey"
            }

            def statToString(maybeStat: Option[Double]): String = maybeStat match {
              case Some(stat) => s"${stat.toInt}%"
              case None => "n/a"
            }

            <.div(^.className := "status-bar-item", "API",
              <.div(^.className := s"status-bar-item-value ${ragClass(apiFeedStatus.receivedPct)}", s"received: ${statToString(apiFeedStatus.receivedPct)}"),
              if (props.canValidate)
                <.div(^.className := s"status-bar-item-value ${ragClass(apiFeedStatus.validPct)}", s"valid: ${statToString(apiFeedStatus.validPct)}")
              else EmptyVdom,
            )
          })
      }
    }
    .build

  def apply(props: Props): VdomElement = component(props)
}
