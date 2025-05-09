package controllers.application

import actors.persistent.DeleteAlerts
import org.apache.pekko.pattern._
import org.apache.pekko.util.Timeout
import com.google.inject.Inject
import drt.shared.Alert
import drt.shared.CrunchApi.MillisSinceEpoch
import org.joda.time.DateTime
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.auth.Roles.CreateAlerts
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class AlertsController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def getAlerts(createdAfter: MillisSinceEpoch): Action[AnyContent] = Action.async { _ =>
    val eventualAlerts: Future[Seq[Alert]] = for {
      alerts <- (ctrl.applicationService.alertsActor ? GetState).mapTo[Seq[Alert]]
    } yield alerts.filter(a => a.createdAt > createdAfter)

    eventualAlerts.map(alerts => Ok(write(alerts)))
  }

  def addAlert: Action[AnyContent] = authByRole(CreateAlerts) {
    Action.async { request =>
      log.info(s"Adding an Alert!")
      request.body.asText match {
        case Some(text) =>
          val alert: Alert = read[Alert](text)
          (ctrl.applicationService.alertsActor ? Alert(alert.title, alert.message, alert.alertClass, alert.expires, createdAt = DateTime.now.getMillis))
            .mapTo[Alert]
            .map { alert =>
              Ok(s"$alert added!")
            }
          Future(Accepted)
        case None =>
          Future(BadRequest)
      }
    }
  }

  def deleteAlerts: Action[AnyContent] = authByRole(CreateAlerts) {
    Action.async {
      val futureAlerts = ctrl.applicationService.alertsActor.ask(DeleteAlerts)(new Timeout(5 second))
      futureAlerts.map(s => {
        log.info(s"Removing all the alerts: $s")
        Ok(s.toString)
      })
    }
  }
}
