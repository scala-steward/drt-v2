package drt.client.components

import diode.UseValueEq
import drt.client.actions.Actions.{DeleteEgateBanksUpdate, SaveEgateBanksUpdate}
import drt.client.components.ConfirmDialog.ConfirmParams
import drt.client.components.styles.DrtReactTheme
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.MillisSinceEpoch
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.{Add, Delete, Edit}
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CallbackTo, ReactEventFromInput, ScalaComponent}
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, SetEgateBanksUpdate}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.scalajs.js


object EgatesScheduleEditor {
  val maxGatesPerBank = 15

  case class Props(initialUpdates: EgateBanksUpdates) extends UseValueEq

  case class Editing(update: EgateBanksUpdate, originalDate: MillisSinceEpoch) {
    def setEffectiveFrom(newMillis: MillisSinceEpoch): Editing = copy(update = update.copy(effectiveFrom = newMillis))
  }

  case class State(updates: Iterable[EgateBanksUpdate], editing: Option[Editing], confirm: Option[ConfirmParams]) extends UseValueEq

  def apply(terminal: Terminal, egatesUpdates: EgateBanksUpdates, newUpdatesTemplate: IndexedSeq[EgateBank]): Unmounted[Props, State, Unit] = {
    val comp = ScalaComponent
      .builder[Props]("EgatesScheduleEditor")
      .initialStateFromProps(p => State(p.initialUpdates.updates, None, None))
      .renderS { (scope, s) =>
        val setDate: ReactEventFromInput => CallbackTo[Unit] = e => {
          e.persist()
          scope.modState { currentState =>
            val updatedEditing = currentState.editing.map(editing => editing.setEffectiveFrom(SDate(e.target.value).millisSinceEpoch))
            currentState.copy(editing = updatedEditing)
          }
        }
        val addBank: () => ReactEventFromInput => CallbackTo[Unit] = () => e => {
          e.persist()
          scope.modState { currentState =>
            val maybeUpdatedEditing = currentState.editing.map { editing =>
              editing.copy(update = editing.update.copy(banks = editing.update.banks :+ EgateBank(IndexedSeq.fill(maxGatesPerBank)(true))))
            }
            currentState.copy(editing = maybeUpdatedEditing)
          }
        }
        val removeBank: (Int) => ReactEventFromInput => CallbackTo[Unit] = (bankIdx) => e => {
          e.persist()
          scope.modState { currentState =>
            val maybeUpdatedEditing = currentState.editing.map { editing =>
              val updatedBanks = editing.update.banks.zipWithIndex.collect {
                case (bank, idx) if idx != bankIdx => bank
              }
              editing.copy(update = editing.update.copy(banks = updatedBanks))
            }
            currentState.copy(editing = maybeUpdatedEditing)
          }
        }
        val setGate: (Int, Int, Boolean) => ReactEventFromInput => CallbackTo[Unit] = (bankIdx, gateIdx, gateIsOn) => e => {
          e.persist()
          scope.modState { currentState =>
            val maybeUpdatedEditing = currentState.editing.map { editing =>
              val bankToUpdate: EgateBank = editing.update.banks(bankIdx)
              val updatedGates = bankToUpdate.gates.updated(gateIdx, gateIsOn)
              val updatedBank = bankToUpdate.copy(gates = updatedGates)
              val updatedBanks = editing.update.banks.updated(bankIdx, updatedBank)
              editing.copy(update = editing.update.copy(banks = updatedBanks))
            }
            currentState.copy(editing = maybeUpdatedEditing)
          }
        }
        val setGates: (Int, Boolean) => ReactEventFromInput => CallbackTo[Unit] = (bankIdx, gateIsOn) => e => {
          e.persist()
          scope.modState { currentState =>
            val maybeUpdatedEditing = currentState.editing.map { editing =>
              val bankToUpdate: EgateBank = editing.update.banks(bankIdx)
              val updatedBank = bankToUpdate.copy(gates = IndexedSeq.fill(bankToUpdate.maxCapacity)(gateIsOn))
              val updatedBanks = editing.update.banks.updated(bankIdx, updatedBank)
              editing.copy(update = editing.update.copy(banks = updatedBanks))
            }
            currentState.copy(editing = maybeUpdatedEditing)
          }
        }

        val cancelEdit: CallbackTo[Unit] = scope.modState(_.copy(editing = None))

        val saveEdit: CallbackTo[Unit] =
          scope.modState { state =>
            val updatedChangeSets = state.editing match {
              case Some(editSet) =>
                SPACircuit.dispatch(SaveEgateBanksUpdate(SetEgateBanksUpdate(terminal, editSet.originalDate, editSet.update)))
                val withoutOriginal = state.updates
                  .filter(cs => cs.effectiveFrom != editSet.update.effectiveFrom && cs.effectiveFrom != editSet.originalDate)
                withoutOriginal ++ Iterable(editSet.update)
              case None =>
                state.updates
            }
            state.copy(editing = None, updates = updatedChangeSets)
          }

        def deleteUpdates(effectiveFrom: MillisSinceEpoch): CallbackTo[Unit] = scope.modState { state =>
          state.copy(confirm = Option(ConfirmParams(
            "Are you sure you want to delete e-Gates change?",
            () => {
              SPACircuit.dispatch(DeleteEgateBanksUpdate(terminal, effectiveFrom))
              scope.modState(_.copy(updates = state.updates.filter(_.effectiveFrom != effectiveFrom)))
            },
            () => scope.modState(_.copy(confirm = None))
          )))
        }

        val today = SDate.now().getLocalLastMidnight.millisSinceEpoch

        ThemeProvider(DrtReactTheme)(
          <.div(^.className := "terminal-config",
            s.confirm.map(ConfirmDialog(_)).toTagMod,
            <.h3(s"$terminal"),
            s.editing match {
              case Some(editing) =>
                MuiDialog(open = s.editing.isDefined, maxWidth = "sm")(
                  MuiDialogTitle()(s"${if (editing.originalDate == today) "Add" else "Edit"} EGates change"),
                  MuiDialogContent()(
                    <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "column", "gap" -> "16px", "padding" -> "8px"),
                      MuiTextField(
                        label = VdomNode("Date the changes take effect"),
                        fullWidth = true,
                      )(
                        ^.`type` := "datetime-local",
                        ^.defaultValue := SDate(editing.update.effectiveFrom).toLocalDateTimeString,
                        ^.onChange ==> setDate
                      ),
                      MuiButton(color = Color.primary, variant = "outlined", size = "small")(MuiIcons(Add)(fontSize = "large"), "Add bank", ^.onClick ==> addBank()),
                      editing.update.banks.zipWithIndex.map { case (egateBank, bankIdx) =>
                        MuiGrid(item = true, container = true, spacing = 1)(
                          <.div(^.style := js.Dictionary("display" -> "flex", "gap" -> "16px", "padding" -> "8px", "alignItems" -> "center"),
                            s"Bank ${bankIdx + 1}",
                            MuiButton(color = Color.primary, variant = "outlined", size = "small")(MuiIcons(Delete)(fontSize = "large"), ^.onClick ==> removeBank(bankIdx))
                          ),
                          MuiGrid(item = true, container = true, xs = 12, justify = "flex-start")(
                            MuiGrid(item = true, direction = "column", justify = "center", alignContent = "center")(
                              MuiGrid(item = true, justify = "center", alignContent = "left")(<.span("All", ^.textAlign := "center", ^.display := "block")),
                              MuiGrid(item = true)(
                                MuiCheckbox(indeterminate = !egateBank.isFullyOpen && !egateBank.isClosed)(
                                  ^.checked := egateBank.isFullyOpen,
                                  ^.onChange ==> setGates(bankIdx, !egateBank.isFullyOpen))
                              )),
                            egateBank.gates.zipWithIndex.map { case (gateIsOn, gateIdx) =>
                              MuiGrid(item = true, direction = "column", justify = "center", alignContent = "center")(
                                MuiGrid(item = true, justify = "center", alignContent = "left")(<.span(gateIdx + 1, ^.textAlign := "center", ^.display := "block")),
                                MuiGrid(item = true)(
                                  MuiCheckbox()(
                                    ^.checked := gateIsOn,
                                    ^.onChange ==> setGate(bankIdx, gateIdx, !gateIsOn)
                                  )))
                            }.toTagMod
                          ),
                        )
                      }.toTagMod
                    )
                  ),
                  MuiDialogActions()(
                    MuiButton(color = Color.primary, variant = "outlined", size = "medium")("Cancel", ^.onClick --> cancelEdit),
                    MuiButton(color = Color.primary, variant = "outlined", size = "medium")("Save", ^.onClick --> saveEdit),
                  )
                )
              case None => EmptyVdom
            },
            MuiGrid(container = true, xs = 12, spacing = 1)(
              MuiGrid(container = true, item = true, spacing = 1)(
                MuiGrid(item = true, xs = 4)(MuiTypography(variant = "subtitle1")("Effective from")),
                MuiGrid(item = true, xs = 4)(MuiTypography(variant = "subtitle1")("Open gates per bank")),
                MuiGrid(item = true, xs = 4, justify = "flex-end", container = true)(
                  MuiButton(color = Color.primary, variant = "outlined", size = "medium")(
                    MuiIcons(Add)(fontSize = "large"),
                    "Add e-Gates change",
                    ^.onClick --> scope.modState(_.copy(editing = Option(Editing(EgateBanksUpdate(today, newUpdatesTemplate), today)))))),
              ),
              s.updates.toList.sortBy(_.effectiveFrom).reverseIterator.map { updates =>
                val date = SDate(updates.effectiveFrom)
                MuiGrid(container = true, item = true, spacing = 1)(
                  MuiGrid(item = true, xs = 4)(MuiTypography(variant = "body1")(s"${date.prettyDateTime}")),
                  MuiGrid(item = true, xs = 4)(
                    <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "column", "gap" -> "8px"),
                      updates.banks.zipWithIndex.map { case (bank, idx) =>
                        val open = bank.gates.count(_ == true) match {
                          case 0 => "all closed"
                          case n if n == bank.gates.length => "all open"
                          case n => s"$n open"
                        }
                        MuiTypography(variant = "body1")(s"Bank ${idx + 1}: ${bank.gates.length} gates, $open")
                      }.toTagMod
                    )
                  ),
                  MuiGrid(item = true, xs = 4)(
                    <.div(^.style := js.Dictionary("display" -> "flex", "gap" -> "8px"),
                      MuiButton(color = Color.primary, variant = "outlined", size = "small")(
                        MuiIcons(Edit)(fontSize = "large"),
                        ^.onClick --> scope.modState(_.copy(editing = Option(Editing(updates, updates.effectiveFrom))))),
                      MuiButton(color = Color.primary, variant = "outlined", size = "small")(
                        MuiIcons(Delete)(fontSize = "large"),
                        ^.onClick --> deleteUpdates(updates.effectiveFrom)),
                    )
                  )
                )
              }.toTagMod
            )
          )
        )
      }
      .build

    comp(Props(egatesUpdates))
  }
}
