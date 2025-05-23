package controllers.application

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming
import drt.shared._
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import services.exports.StaffMovementsExport
import uk.gov.homeoffice.drt.auth.Roles.{BorderForceStaff, FixedPointsEdit, FixedPointsView, StaffEdit, StaffMovementsEdit, StaffMovementsExport => StaffMovementsExportRole}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.service.staffing._
import uk.gov.homeoffice.drt.time.SDate
import upickle.default._
import scala.concurrent.Future


class StaffingController @Inject()(cc: ControllerComponents,
                                   ctrl: DrtSystemInterface,
                                   fixedPointsService: FixedPointsService,
                                   movementsService: StaffMovementsService) extends AuthController(cc, ctrl) {
  def getFixedPoints: Action[AnyContent] = authByRole(FixedPointsView) {
    Action.async { request: Request[AnyContent] =>
      val maybePointInTime = request.queryString.get("pointInTime").flatMap(_.headOption.map(_.toLong))
      fixedPointsService.fixedPoints(maybePointInTime)
        .map(sa => Ok(write(sa)))
    }
  }

  def saveFixedPoints: Action[AnyContent] = authByRole(FixedPointsEdit) {
    Action { request =>
      request.body.asText match {
        case Some(text) =>
          val fixedPoints: FixedPointAssignments = read[FixedPointAssignments](text)
          fixedPointsService.updateFixedPoints(fixedPoints.assignments)
          Accepted
        case None =>
          BadRequest
      }
    }
  }

  def addStaffMovements: Action[AnyContent] = authByRole(StaffMovementsEdit) {
    Action.async {
      request =>
        request.body.asText match {
          case Some(text) =>
            val movementsToAdd: List[StaffMovement] = read[List[StaffMovement]](text)
            movementsService.addMovements(movementsToAdd).map(_ => Accepted)
          case None =>
            Future.successful(BadRequest)
        }
    }
  }

  def removeStaffMovements(movementsToRemove: String): Action[AnyContent] = authByRole(StaffMovementsEdit) {
    Action {
      movementsService.removeMovements(movementsToRemove)
      Accepted
    }
  }

  def getStaffMovements(date: String): Action[AnyContent] = authByRole(BorderForceStaff) {
    Action.async { request =>
      val localDate = SDate(date).toLocalDate
      val maybePointInTime = request.queryString.get("pointInTime").flatMap(_.headOption.map(_.toLong))
      val eventualStaffMovements = movementsService.movementsForDate(localDate, maybePointInTime)

      eventualStaffMovements.map(sms => Ok(write(sms)))
    }
  }

  def exportStaffMovements(terminalString: String, date: String): Action[AnyContent] =
    authByRole(StaffMovementsExportRole) {
      Action {
        val terminal = Terminal(terminalString)
        val localDate = SDate(date).toLocalDate
        val eventualStaffMovements = movementsService.movementsForDate(localDate, None)

        val csvSource: Source[String, NotUsed] =
          Source.future(
            eventualStaffMovements.map { sm =>
              StaffMovementsExport.toCSVWithHeader(sm, terminal)
            }
          )

        CsvFileStreaming.sourceToCsvResponse(
          csvSource,
          CsvFileStreaming.makeFileName(
            "staff-movements",
            Seq(terminal),
            SDate(localDate),
            SDate(localDate),
            airportConfig.portCode
          ) + ".csv"
        )
      }
    }
}
