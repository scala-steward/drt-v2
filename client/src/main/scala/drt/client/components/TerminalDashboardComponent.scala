package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import diode.react.ReactConnectProxy
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.components.FlightComponents.SplitsGraph.splitsGraphComponentColoured
import drt.client.components.TerminalContentComponent.originMapper
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{SPACircuit, ViewLive}
import drt.shared.CrunchApi.CrunchMinute
import drt.shared._
import drt.shared.api.WalkTimes
import drt.shared.redlist.RedList
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.{HashSet, Map}
import scala.scalajs.js.URIUtils
import scala.util.Try


object TerminalDashboardComponent {
  case class Props(terminalPageTabLoc: TerminalPageTabLoc,
                   airportConfig: AirportConfig,
                   slaConfigs: SlaConfigs,
                   router: RouterCtl[Loc],
                   featureFlags: Pot[FeatureFlags],
                   loggedInUser: LoggedInUser,
                   redListPorts: Pot[HashSet[PortCode]],
                   redListUpdates: RedListUpdates,
                   walkTimes: Pot[WalkTimes],
                   paxFeedSourceOrder: List[FeedSource],
                  ) extends UseValueEq

  val defaultSlotSize = 120

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TerminalDashboard")
    .render_P { props =>
      val slotSize = Try {
        props.terminalPageTabLoc.subMode.toInt
      }.getOrElse(defaultSlotSize)

      def timeSlotStart: SDateLike => SDateLike = timeSlotForTime(slotSize)

      val startPoint = props.terminalPageTabLoc.queryParams.get("start")
        .flatMap(s => SDate.parse(s))
        .getOrElse(SDate.now())
      val start = timeSlotStart(startPoint)
      val end = start.addMinutes(slotSize)
      val prevSlotStart = start.addMinutes(-slotSize)

      val urlPrevTime = URIUtils.encodeURI(prevSlotStart.toISOString)
      val urlNextTime = URIUtils.encodeURI(end.toISOString)

      val terminal = props.terminalPageTabLoc.terminal

      def slas: Map[Queue, Int] = props.slaConfigs.configForDate(startPoint.millisSinceEpoch).getOrElse(props.airportConfig.slaByQueue)

      val flightTableComponent = FlightTable.apply(
        shortLabel = true,
        originMapper = originMapper,
        splitsGraphComponent = splitsGraphComponentColoured
      )

      val portStateRCP: ReactConnectProxy[Pot[PortState]] = SPACircuit.connect(_.portStatePot)
      val flightHighlightRCP: ReactConnectProxy[FlightHighlight] = SPACircuit.connect(_.flightHighlight)

      portStateRCP { portStateProxy =>
        val portStatePot = portStateProxy()
        val pot = for {
          featureFlags <- props.featureFlags
          redListPorts <- props.redListPorts
          walkTimes <- props.walkTimes
          portState <- portStatePot
        } yield {
          val currentSlotPs = portState.window(start, end, props.paxFeedSourceOrder)
          val prevSlotPs = portState.window(prevSlotStart, start, props.paxFeedSourceOrder)

          val terminalPax = currentSlotPs.crunchMinutes.collect {
            case (_, cm) if cm.terminal == props.terminalPageTabLoc.terminal => cm.paxLoad
          }.sum.round

          <.div(^.className := "terminal-dashboard",
            if (props.terminalPageTabLoc.queryParams.contains("showArrivals")) {
              val closeArrivalsPopupLink = props.terminalPageTabLoc.copy(
                queryParams = props.terminalPageTabLoc.queryParams - "showArrivals"
              )
              <.div(<.div(^.className := "popover-overlay",
                ^.onClick --> props.router.set(closeArrivalsPopupLink)),
                <.div(^.className := "dashboard-arrivals-popup",
                  <.h2("Arrivals"),
                  <.div(^.className := "terminal-dashboard__arrivals_popup_table", {
                    flightHighlightRCP { flightHighlightProxy =>
                      val flightHighlight = flightHighlightProxy()
                      flightTableComponent(
                        FlightTable.Props(
                          queueOrder = props.airportConfig.queueTypeSplitOrder(props.terminalPageTabLoc.terminal),
                          hasEstChox = props.airportConfig.hasEstChox,
                          loggedInUser = props.loggedInUser,
                          viewMode = ViewLive,
                          defaultWalkTime = props.airportConfig.defaultWalkTimeMillis(props.terminalPageTabLoc.terminal),
                          hasTransfer = props.airportConfig.hasTransfer,
                          displayRedListInfo = featureFlags.displayRedListInfo,
                          redListOriginWorkloadExcluded = RedList.redListOriginWorkloadExcluded(props.airportConfig.portCode, terminal),
                          terminal = terminal,
                          portCode = props.airportConfig.portCode,
                          redListPorts = redListPorts,
                          airportConfig = props.airportConfig,
                          redListUpdates = props.redListUpdates,
                          walkTimes = walkTimes,
                          viewStart = start,
                          viewEnd = end,
                          showFlagger = false,
                          paxFeedSourceOrder = props.paxFeedSourceOrder,
                          flightHighlight = flightHighlight
                        )
                      )
                    }
                  }),
                  props.router.link(closeArrivalsPopupLink)(^.className := "close-arrivals-popup btn btn-default", "close")
                ))
            } else <.div()
            ,
            <.div(^.className := "terminal-dashboard-queues",
              <.div(^.className := "pax-bar", s"$terminalPax passengers presenting at the PCP"),
              <.div(^.className := "queue-boxes",
                props.airportConfig.nonTransferQueues(terminal).filterNot(_ == Queues.FastTrack).map(q => {
                  val qCMs = cmsForTerminalAndQueue(currentSlotPs, q, terminal)
                  val prevSlotCMs = cmsForTerminalAndQueue(prevSlotPs, q, terminal)
                  val qPax = qCMs.map(_.paxLoad).sum.round
                  val qWait = maxWaitInPeriod(qCMs)
                  val prevSlotQWait = maxWaitInPeriod(prevSlotCMs)

                  val waitIcon = (prevSlotQWait, qWait) match {
                    case (p, c) if p > c => Icon.arrowDown
                    case (p, c) if p < c => Icon.arrowUp
                    case _ => Icon.arrowRight
                  }

                  <.dl(^.aria.label := s"Passenger joining queue ${Queues.displayName(q)}",
                    ^.className := s"queue-box col ${q.toString.toLowerCase} ${TerminalDesksAndQueuesRow.slaRagStatus(qWait, slas(q))}",
                    <.dt(^.className := "queue-name", s"${Queues.displayName(q)}"),
                    <.dd(^.className := "queue-box-text", Icon.users, s"$qPax pax joining"),
                    <.dd(^.className := "queue-box-text", Icon.clockO, s"${MinuteAsAdjective(qWait).display} wait"),
                    <.dd(^.className := "queue-box-text", waitIcon, s"queue time")
                  )
                }).toTagMod
              ),
              <.div(^.className := "tb-bar-wrapper",
                props.router.link(props.terminalPageTabLoc.
                  copy(queryParams = Map("start" -> s"$urlPrevTime")))
                (^.aria.label := s"View previous $slotSize minutes", ^.className := "dashboard-time-switcher prev-bar col", Icon.angleDoubleLeft),
                <.div(^.className := "tb-bar", ^.aria.label := "current display time range",
                  s"${start.prettyTime} - ${end.prettyTime}",
                ),
                props.router.link(props.terminalPageTabLoc.
                  copy(queryParams = Map("start" -> s"$urlNextTime")))
                (^.aria.label := s"View next $slotSize minutes", ^.className := "dashboard-time-switcher next-bar col", Icon.angleDoubleRight)
              )
            )
            ,
            <.div(^.className := "terminal-dashboard-side",
              props.router
                .link(props.terminalPageTabLoc.copy(
                  queryParams = props.terminalPageTabLoc.queryParams + ("showArrivals" -> "true")
                ))(^.className := "terminal-dashboard-side__sidebar_widget", "View Arrivals"),
              <.div(
                ^.className := "terminal-dashboard-side__sidebar_widget time-slot-changer",
                <.label(^.className := "terminal-dashboard-side__sidebar_widget__label",
                  ^.aria.label := "Select timeslot size for PCP passengers display", "Time slot duration"),
                <.select(
                  ^.onChange ==> ((e: ReactEventFromInput) =>
                    props.router.set(props.terminalPageTabLoc.copy(subMode = e.target.value))),
                  ^.value := slotSize,
                  <.option("15 minutes", ^.value := "15"),
                  <.option("30 minutes", ^.value := "30"),
                  <.option("1 hour", ^.value := "60"),
                  <.option("2 hours", ^.value := "120"),
                  <.option("3 hours", ^.value := "180")))
            )
          )
        }
        <.div(pot.render(identity))
      }
    }
    .componentDidMount(p => Callback {
      GoogleEventTracker.sendPageView(page = s"terminal-dashboard-${p.props.terminalPageTabLoc.terminal}")
    })
    .build

  def cmsForTerminalAndQueue(ps: PortStateLike, queue: Queue, terminal: Terminal): Iterable[CrunchMinute] = ps
    .crunchMinutes
    .collect {
      case (tqm, cm) if tqm.queue == queue && tqm.terminal == terminal => cm
    }

  def maxWaitInPeriod(cru: Iterable[CrunchApi.CrunchMinute]): Int = if (cru.nonEmpty)
    cru.map(cm => cm.deployedWait.getOrElse(cm.waitTime)).max
  else 0

  def apply(terminalPageTabLoc: TerminalPageTabLoc,
            airportConfig: AirportConfig,
            slaConfigs: SlaConfigs,
            router: RouterCtl[Loc],
            featureFlags: Pot[FeatureFlags],
            loggedInUser: LoggedInUser,
            redListPorts: Pot[HashSet[PortCode]],
            redListUpdates: RedListUpdates,
            walkTimes: Pot[WalkTimes],
            paxFeedSourceOrder: List[FeedSource],
           ): VdomElement =
    component(Props(
      terminalPageTabLoc,
      airportConfig,
      slaConfigs,
      router,
      featureFlags,
      loggedInUser,
      redListPorts,
      redListUpdates,
      walkTimes,
      paxFeedSourceOrder,
    ))

  def timeSlotForTime(slotSize: Int)(sd: SDateLike): SDateLike = {
    val offset: Int = sd.getMinutes % slotSize

    sd.addMinutes(offset * -1)
  }
}
