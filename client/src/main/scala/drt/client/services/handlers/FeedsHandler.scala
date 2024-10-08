package drt.client.services.handlers

import diode.Implicits.runAfterImpl
import diode.data.{Pot, Ready}
import diode.{Action, ActionResult, Effect, ModelRW}
import drt.client.actions.Actions.RetryActionAfter
import drt.client.logger.log
import drt.client.services.{DrtApi, PollDelay}
import uk.gov.homeoffice.drt.feeds.FeedSourceStatuses
import uk.gov.homeoffice.drt.ports.FeedSource
import upickle.default.{read, write}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

case class GetFeedSourceStatuses() extends Action

case class SetFeedSourceStatuses(statuses: Seq[FeedSourceStatuses]) extends Action

case class CheckFeed(feedSource: FeedSource) extends Action

class FeedsHandler[M](modelRW: ModelRW[M, Pot[Seq[FeedSourceStatuses]]]) extends PotActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case CheckFeed(feedSource) =>
      DrtApi.post("feeds/check", write(feedSource))
      noChange

    case SetFeedSourceStatuses(statuses) =>
      val poll = Effect(Future(GetFeedSourceStatuses())).after(15 seconds)
      updateIfChanged(statuses, poll)

    case GetFeedSourceStatuses() =>
      val apiCallEffect = Effect(DrtApi.get("feeds/statuses")
        .map(r => SetFeedSourceStatuses(read[Seq[FeedSourceStatuses]](r.responseText)))
        .recoverWith {
          case _ =>
            log.error(s"Failed to get feed statuses. Re-requesting after ${PollDelay.recoveryDelay}")
            Future(RetryActionAfter(GetFeedSourceStatuses(), PollDelay.recoveryDelay))
        })

      effectOnly(apiCallEffect)
  }
}
