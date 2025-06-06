package drt.server.feeds

import org.apache.pekko.actor.typed
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.typed.scaladsl.ActorSource

import scala.concurrent.duration.FiniteDuration

case class Feed[T](source: Source[ArrivalsFeedResponse, T], initialDelay: FiniteDuration, interval: FiniteDuration)

object Feed {
  sealed trait FeedWithFrequency[T] {
    val feedSource: T
    val interval: FiniteDuration
  }

  case class EnabledFeedWithFrequency[T](feedSource: T, initialDelay: FiniteDuration, interval: FiniteDuration) extends FeedWithFrequency[T]

  sealed trait FeedTick

  case object Tick extends FeedTick

  case object Stop extends FeedTick

  case class Fail(ex: Exception) extends FeedTick

  def actorRefSource: Source[FeedTick, typed.ActorRef[FeedTick]] = ActorSource.actorRef[FeedTick](
    completionMatcher = {
      case Stop =>
    },
    failureMatcher = {
      case Fail(ex) => ex
    },
    bufferSize = 8, overflowStrategy = OverflowStrategy.dropHead
  )
}
