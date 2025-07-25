package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.actions.Actions.UpdateStaffAdjustmentDialogueState
import drt.client.components.TerminalDesksAndQueues.{Deployments, DeskType, Ideal, queueActualsColour, queueColour}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions._
import drt.client.services.{SPACircuit, ViewMode}
import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinute}
import drt.shared._
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}
import org.scalajs.dom.html
import org.scalajs.dom.html.TableCell
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.StaffMovementsEdit
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.time.MilliDate

object TerminalDesksAndQueuesRow {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  def ragStatus(totalRequired: Int, totalDeployed: Int): String = {
    totalRequired.toDouble / totalDeployed match {
      case diff if diff >= 1 => "red"
      case diff if diff >= 0.75 => "amber"
      case _ => "green"
    }
  }

  case class Props(minuteMillis: MillisSinceEpoch,
                   queueMinutes: Seq[CrunchMinute],
                   staffMinute: StaffMinute,
                   maxPaxInQueues: Map[Queue, Int],
                   airportConfig: AirportConfig,
                   slaConfigs: Pot[SlaConfigs],
                   terminal: Terminal,
                   showActuals: Boolean,
                   viewType: DeskType,
                   hasActualDeskStats: Boolean,
                   viewMode: ViewMode,
                   loggedInUser: LoggedInUser,
                   slotMinutes: Int,
                   showWaitColumn: Boolean,
                   queues: Seq[Queue],
                  ) extends UseValueEq

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TerminalDesksAndQueuesRow")
    .render_P(props => {
      val crunchMinutesByQueue = props.queueMinutes.filter(qm => props.queues.contains(qm.queue)).map(
        qm => Tuple2(qm.queue, qm)).toMap

      val queueTds = crunchMinutesByQueue.flatMap {
        case (queue, cm) =>
          val paxLoadTd = <.td(^.className := queueColour(queue), s"${Math.round(cm.paxLoad)}")

          def deployDeskTd: VdomTagOf[TableCell] = <.td(
            ^.className := s"${queueColour(queue)}",
            Tippy.interactive("deploy-desk", <.span(s"Suggested deployments with available staff: ${cm.deployedDesks.getOrElse("-")}"),
              s"${cm.deskRec}")
          )

          def deployRecsDeskTd: VdomTagOf[TableCell] = <.td(
            ^.className := s"${queueColour(queue)}",
            Tippy.interactive("deploy-recs-desk",
              <.span(s"Recommended for this time slot / queue: ${cm.deskRec}"),
              s"${cm.deployedDesks.getOrElse("-")}"
            )
          )

          def withSlaClass(waitTimeMinutes: Int): (String => VdomNode) => TagMod = {
            classToTagMod =>
              props.slaConfigs.render { slaConfigs =>
                val slas = slaConfigs.configForDate(props.viewMode.millis).getOrElse(props.airportConfig.slaByQueue)
                classToTagMod(slaRagStatus(waitTimeMinutes, slas(queue)))
              }
          }

          val queueCells = props.viewType match {
            case Deployments =>
              if (props.showWaitColumn)
                List(
                  paxLoadTd,
                  deployRecsDeskTd,
                  withSlaClass(cm.deployedWait.getOrElse(0))(ragClass => <.td(
                    ^.className := s"${queueColour(queue)} $ragClass",
                    Tippy.interactive("deployed-wait",
                      <.span(s"Recommended for this time slot / queue: ${cm.waitTime}"),
                      s"${cm.deployedWait.map(Math.round(_)).getOrElse("-")}"
                    )
                  )),
                )
              else List(paxLoadTd, deployRecsDeskTd)

            case Ideal =>
              if (props.showWaitColumn)
                List(
                  paxLoadTd,
                  deployDeskTd,
                  withSlaClass(cm.waitTime)(ragClass => <.td(
                    ^.className := s"${queueColour(queue)} $ragClass",
                    Tippy.interactive("ideal-wait-time", <.span(s"Suggested deployments with available staff: ${cm.waitTime}"),
                      s"${Math.round(cm.waitTime)}")
                  )),
                )
              else List(paxLoadTd, deployDeskTd)
          }

          def queueActualsTd(actDesks: String) = <.td(^.className := queueActualsColour(queue), actDesks)

          if (props.showActuals) {
            val actDesks: String = cm.actDesks.map(act => s"$act").getOrElse("-")
            val actWaits: String = cm.actWait.map(act => s"$act").getOrElse("-")

            queueCells ++ Seq(queueActualsTd(actDesks), <.td(^.className := queueActualsColour(queue), actWaits))

          } else queueCells
      }
      val fixedPoints = props.staffMinute.fixedPoints
      val movements = props.staffMinute.movements
      val available = props.staffMinute.available
      val crunchMinutes = crunchMinutesByQueue.values.toList
      val totalRequired = DesksAndQueues.totalRequired(props.staffMinute, crunchMinutes)
      val totalDeployed = DesksAndQueues.totalDeployed(props.staffMinute, crunchMinutes)
      val ragClass = ragStatus(totalRequired, available)

      def allowAdjustments: Boolean = props.loggedInUser.hasRole(StaffMovementsEdit) &&
        SDate(props.viewMode.localDate).millisSinceEpoch >= SDate.midnightThisMorning().millisSinceEpoch

      val minus: TagMod = adjustmentLink(props, "-")
      val plus: TagMod = adjustmentLink(props, "+")

      val pcpTds: Seq[VdomTagOf[TableCell]] = List(
        <.td(^.className := s"non-pcp", fixedPoints),
        <.td(^.className := s"non-pcp", movements),
        <.td(^.className := s"total-deployed $ragClass", totalRequired),
        <.td(^.className := s"total-deployed", totalDeployed),
        if (allowAdjustments)
          <.td(^.className := s"total-deployed staff-adjustments", ^.colSpan := 2, <.span(minus, <.span(^.className := "deployed", available), plus))
        else
          <.td(^.className := s"total-deployed staff-adjustments", ^.colSpan := 2, <.span(^.className := "deployed", available)))
      <.tr((<.td(SDate(MilliDate(props.minuteMillis)).toHoursAndMinutes) :: queueTds.toList ++ pcpTds).toTagMod)
    })
    .componentDidMount(_ => Callback.log("TerminalDesksAndQueuesRow did mount"))
    .build

  def slaRagStatus(waitTime: Int, sla: Int): String = waitTime.toDouble / sla match {
    case pc if pc >= 1 => "red"
    case pc if pc >= 0.7 => "amber"
    case _ => ""
  }

  def adjustmentLink(props: Props, action: String): TagOf[html.Div] = {
    val popupState = adjustmentState(props, action)
    val initialiseDialogue = Callback(SPACircuit.dispatch(UpdateStaffAdjustmentDialogueState(Option(popupState))))
    <.div(^.className := "staff-deployment-adjustment-container", <.div(^.className := "popover-trigger", action, ^.onClick --> initialiseDialogue))
  }

  def adjustmentState(props: Props, action: String): StaffAdjustmentDialogueState =
    StaffAdjustmentDialogueState(
      props.airportConfig.terminals(props.viewMode.localDate),
      Option(props.terminal),
      "Additional info",
      SDate(props.minuteMillis),
      30,
      action,
      1,
      props.loggedInUser
    )

  def apply(props: Props): VdomElement = component(props)
}
