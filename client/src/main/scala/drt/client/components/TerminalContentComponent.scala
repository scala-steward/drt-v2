package drt.client.components

import diode.UseValueEq
import diode.data.{Pending, Pot}
import diode.react.{ModelProxy, ReactConnectProxy}
import drt.client.SPAMain
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.components.Icon.Icon
import drt.client.components.ToolTips.staffMovementsTabTooltip
import drt.client.components.scenarios.ScenarioSimulationComponent
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.CrunchApi.StaffMinute
import drt.shared._
import drt.shared.api.{FlightManifestSummary, WalkTimes}
import drt.shared.redlist.RedList
import io.kinoplan.scalajs.react.material.ui.core.MuiButton
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.GetApp
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.{<, VdomAttr, VdomElement, ^, _}
import japgolly.scalajs.react.vdom.{TagOf, html_<^}
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.auth.Roles.{ArrivalSimulationUpload, Role, StaffMovementsExport}
import uk.gov.homeoffice.drt.auth._
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.collection.immutable.HashSet

object TerminalContentComponent {
  case class Props(potShifts: Pot[ShiftAssignments],
                   potFixedPoints: Pot[FixedPointAssignments],
                   potStaffMovements: Pot[StaffMovements],
                   airportConfig: AirportConfig,
                   slaConfigs: Pot[SlaConfigs],
                   terminalPageTab: TerminalPageTabLoc,
                   defaultTimeRangeHours: TimeRangeHours,
                   router: RouterCtl[Loc],
                   showActuals: Boolean,
                   viewMode: ViewMode,
                   loggedInUser: LoggedInUser,
                   featureFlags: Pot[FeatureFlags],
                   redListPorts: Pot[HashSet[PortCode]],
                   redListUpdates: Pot[RedListUpdates],
                   walkTimes: Pot[WalkTimes],
                   paxFeedSourceOrder: List[FeedSource],
                   flights: Pot[Seq[ApiFlightWithSplits]],
                   flightManifestSummaries: Map[ArrivalKey, FlightManifestSummary],
                   arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
                   simulationResult: Pot[SimulationResult],
                   flightHighlight: FlightHighlight,
                   viewStart: SDateLike,
                   hoursToView: Int,
                   windowCrunchSummaries: Pot[Map[Long, Map[Queue, CrunchApi.CrunchMinute]]],
                   dayCrunchSummaries: Pot[Map[Long, Map[Queue, CrunchApi.CrunchMinute]]],
                   windowStaffSummaries: Pot[Map[Long, StaffMinute]],
                   defaultDesksAndQueuesViewType: String,
                  ) extends UseValueEq

  case class State(activeTab: String, showExportDialogue: Boolean = false)

  def airportWrapper(portCode: PortCode): ReactConnectProxy[Pot[AirportInfo]] = SPACircuit.connect(_.airportInfos.getOrElse(portCode, Pending()))

  def originMapper(portCode: PortCode, style: html_<^.TagMod): VdomElement = airportWrapper(portCode) {
    proxy: ModelProxy[Pot[AirportInfo]] =>
      <.span(
        style,
        proxy().render(ai =>
          <.dfn(^.className := "flight-origin-dfn", Tippy.describe(<.span(s"${ai.airportName}, ${ai.city}, ${ai.country}"),
            <.abbr(^.className := "dotted-underline", s"${portCode.toString}")), s", ${ai.country}")),
        proxy().renderEmpty(<.abbr(^.className := "dotted-underline", portCode.toString))
      )
  }

  class Backend {
    def render(props: Props, state: State): TagOf[Div] = {
      val terminal = props.terminalPageTab.terminal
      val queueOrder: Seq[Queue] = props.airportConfig.queueTypeSplitOrder(terminal)

      val desksAndQueuesActive = if (state.activeTab == "desksAndQueues") "active" else ""
      val arrivalsActive = if (state.activeTab == "arrivals") "active" else ""
      val staffingActive = if (state.activeTab == "staffing") "active" else ""
      val simulationsActive = if (state.activeTab == "simulations") "active" else ""

      val desksAndQueuesPanelActive = if (state.activeTab == "desksAndQueues") "active" else "fade"
      val arrivalsPanelActive = if (state.activeTab == "arrivals") "active" else "fade"
      val staffingPanelActive = if (state.activeTab == "staffing") "active" else "fade"
      val viewModeStr = props.terminalPageTab.viewMode.getClass.getSimpleName.toLowerCase
      val terminalName = terminal.toString
      val arrivalsExportForPort = ArrivalsExportComponent(props.airportConfig.portCode, terminal, props.viewStart)
      val movementsExportDate: LocalDate = props.viewMode match {
        case ViewLive => SDate.now().toLocalDate
        case ViewDay(localDate, _) => localDate
      }

      <.div(^.className := "queues-and-arrivals",
        <.div(^.className := s"view-mode-content $viewModeStr",
          <.div(^.className := "tabs-with-export",
            <.ul(^.className := "nav nav-tabs",
              <.li(^.className := arrivalsActive,
                <.a(^.id := "arrivalsTab", VdomAttr("data-toggle") := "tab", "Arrivals"), ^.onClick --> {
                  GoogleEventTracker.sendEvent(terminalName, "Arrivals", props.terminalPageTab.dateFromUrlOrNow.toISODateOnly)
                  props.router.set(props.terminalPageTab.copy(subMode = "arrivals"))
                }),
              <.li(^.className := desksAndQueuesActive,
                <.a(^.className := "flexed-anchor", ^.id := "desksAndQueuesTab", VdomAttr("data-toggle") := "tab", "Desks & Queues"), ^.onClick --> {
                  GoogleEventTracker.sendEvent(terminalName, "Desks & Queues", props.terminalPageTab.dateFromUrlOrNow.toISODateOnly)
                  props.router.set(props.terminalPageTab.copy(
                    subMode = "desksAndQueues",
                    queryParams = props.terminalPageTab.queryParams.updated("viewType", props.defaultDesksAndQueuesViewType)
                  ))
                }),
              <.li(^.className := staffingActive,
                <.a(^.className := "flexed-anchor", ^.id := "staffMovementsTab", VdomAttr("data-toggle") := "tab", "Staff Movements", staffMovementsTabTooltip),
                ^.onClick --> {
                  GoogleEventTracker.sendEvent(terminalName, "Staff Movements", props.terminalPageTab.dateFromUrlOrNow.toISODateOnly)
                  props.router.set(props.terminalPageTab.copy(subMode = "staffing"))
                }),
              displayForRole(
                <.li(^.className := simulationsActive,
                  <.a(^.className := "flexed-anchor", ^.id := "simulationDayTab", VdomAttr("data-toggle") := "tab", "Simulate Day"), ^.onClick --> {
                    GoogleEventTracker.sendEvent(terminalName, "Simulate Day", props.terminalPageTab.dateFromUrlOrNow.toISODateOnly)
                    props.router.set(props.terminalPageTab.copy(subMode = "simulations"))
                  }),
                ArrivalSimulationUpload, props.loggedInUser
              )
            ),
            <.div(^.className := "exports",
              arrivalsExportForPort(
                props.terminalPageTab.terminal,
                props.terminalPageTab.dateFromUrlOrNow,
                props.loggedInUser,
                props.viewMode),
              exportLink(
                props.terminalPageTab.dateFromUrlOrNow,
                terminalName,
                ExportDeskRecs,
                SPAMain.exportUrl(ExportDeskRecs, props.terminalPageTab.viewMode, terminal),
                None,
                "desk-recs",
              ),
              exportLink(
                props.terminalPageTab.dateFromUrlOrNow,
                terminalName,
                ExportDeployments,
                SPAMain.exportUrl(ExportDeployments, props.terminalPageTab.viewMode, terminal),
                None,
                "deployments"
              ),
              displayForRole(
                exportLink(
                  props.terminalPageTab.dateFromUrlOrNow,
                  terminalName,
                  ExportStaffMovements,
                  SPAMain.absoluteUrl(s"export/staff-movements/${movementsExportDate.toISOString}/$terminal"),
                  None,
                  "staff-movements",
                ),
                StaffMovementsExport,
                props.loggedInUser
              ),
              MultiDayExportComponent(props.airportConfig.portCode, terminal, props.viewMode, props.terminalPageTab.dateFromUrlOrNow, props.loggedInUser)))
          ,
          <.div(^.className := "tab-content",
            <.div(^.id := "desksAndQueues", ^.className := s"tab-pane terminal-desk-recs-container $desksAndQueuesPanelActive",
              if (state.activeTab == "desksAndQueues") {
                props.featureFlags.render { features =>
                  TerminalDesksAndQueues(
                    TerminalDesksAndQueues.Props(
                      router = props.router,
                      viewStart = props.viewStart,
                      hoursToView = props.hoursToView,
                      airportConfig = props.airportConfig,
                      slaConfigs = props.slaConfigs,
                      terminalPageTab = props.terminalPageTab,
                      showActuals = props.showActuals,
                      viewMode = props.viewMode,
                      loggedInUser = props.loggedInUser,
                      featureFlags = features,
                      windowCrunchSummaries = props.windowCrunchSummaries,
                      dayCrunchSummaries = props.dayCrunchSummaries,
                      windowStaffSummaries = props.windowStaffSummaries,
                      terminal = terminal,
                    )
                  )
                }
              } else ""
            ),
            <.div(^.id := "arrivals", ^.className := s"tab-pane in $arrivalsPanelActive", {
              if (state.activeTab == "arrivals") {
                val maybeArrivalsComp = for {
                  features <- props.featureFlags
                  redListPorts <- props.redListPorts
                  redListUpdates <- props.redListUpdates
                  walkTimes <- props.walkTimes
                } yield {
                  FlightTable(
                    FlightTable.Props(
                      queueOrder = queueOrder,
                      hasEstChox = props.airportConfig.hasEstChox,
                      loggedInUser = props.loggedInUser,
                      viewMode = props.viewMode,
                      hasTransfer = props.airportConfig.hasTransfer,
                      displayRedListInfo = features.displayRedListInfo,
                      redListOriginWorkloadExcluded = RedList.redListOriginWorkloadExcluded(props.airportConfig.portCode, terminal),
                      terminal = terminal,
                      portCode = props.airportConfig.portCode,
                      redListPorts = redListPorts,
                      airportConfig = props.airportConfig,
                      redListUpdates = redListUpdates,
                      walkTimes = walkTimes,
                      showFlagger = true,
                      paxFeedSourceOrder = props.paxFeedSourceOrder,
                      flightHighlight = props.flightHighlight,
                      flights = props.flights,
                      flightManifestSummaries = props.flightManifestSummaries,
                      arrivalSources = props.arrivalSources,
                      originMapper = originMapper,
                    )
                  )
                }
                maybeArrivalsComp.render(x => x)
              } else EmptyVdom
            }),
            displayForRole(
              <.div(^.id := "simulations", ^.className := s"tab-pane in $simulationsActive", {
                if (state.activeTab == "simulations") {
                  props.slaConfigs.render(slaConfigs =>
                    ScenarioSimulationComponent(
                      ScenarioSimulationComponent.Props(
                        props.viewMode.dayStart.toLocalDate,
                        props.terminalPageTab.terminal,
                        props.airportConfig,
                        slaConfigs,
                        props.simulationResult,
                      )
                    )
                  )
                } else "not rendering"
              }),
              ArrivalSimulationUpload,
              props.loggedInUser
            ),
            <.div(^.id := "available-staff", ^.className := s"tab-pane terminal-staffing-container $staffingPanelActive",
              if (state.activeTab == "staffing") {
                TerminalStaffing(TerminalStaffing.Props(
                  terminal,
                  props.potShifts,
                  props.potFixedPoints,
                  props.potStaffMovements,
                  props.airportConfig,
                  props.loggedInUser,
                  props.viewMode
                ))
              } else ""
            )
          )
        )
      )
    }
  }

  def exportLink(exportDay: SDateLike,
                 terminalName: String,
                 exportType: ExportType,
                 exportUrl: String,
                 maybeExtraIcon: Option[Icon] = None,
                 title: String,
                ): VdomTagOf[Div] = {
    val keyValue = s"${title.toLowerCase.replace(" ", "-")}-${exportType.toUrlString}"
    <.div(
      ^.key := keyValue,
      MuiButton(color = Color.primary, variant = "outlined", size = "medium")(
        MuiIcons(GetApp)(fontSize = "small"),
        s" $exportType",
        maybeExtraIcon.getOrElse(EmptyVdom),
        ^.className := "btn btn-default",
        ^.href := exportUrl,
        ^.target := "_blank",
        ^.id := s"export-day-${exportType.toUrlString}",
        ^.onClick --> {
          Callback(GoogleEventTracker.sendEvent(terminalName, s"Export $exportType", exportDay.toISODateOnly))
        }
      )
    )
  }

  private def displayForRole(node: VdomNode, role: Role, loggedInUser: LoggedInUser): TagMod =
    if (loggedInUser.hasRole(role))
      node
    else
      EmptyVdom

  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("TerminalContentComponent")
    .initialStateFromProps(p => State(p.terminalPageTab.subMode))
    .renderBackend[TerminalContentComponent.Backend]
    .componentDidMount { p =>
      Callback {
        val hours = p.props.defaultTimeRangeHours
        val page = s"${p.props.terminalPageTab.terminal}/${p.props.terminalPageTab.mode}/${p.props.terminalPageTab.subMode}"
        val pageWithTime = s"$page/${hours.start}/${hours.end}"
        val pageWithDate = p.props.terminalPageTab.maybeViewDate
          .map(s => s"$page/$s/${hours.start}/${hours.end}").getOrElse(pageWithTime)
        GoogleEventTracker.sendPageView(pageWithDate)
      }
    }
    .build

  def apply(props: Props): VdomElement = component(props)
}
