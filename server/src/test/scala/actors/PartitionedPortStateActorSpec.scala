package actors

import actors.PartitionedPortStateActor.{GetStateForDateRange, PointInTimeQuery}
import org.apache.pekko.actor.{Actor, ActorRef, Props}
import org.apache.pekko.testkit.TestProbe
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.time.TimeZoneHelper.utcTimeZone
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.duration._


class DummyActor extends Actor {
  override def receive: Receive = {
    case _ =>
  }
}

class PartitionedPortStateActorSpec extends CrunchTestLike {
  val probe: TestProbe = TestProbe("port-state")
  val flightsActor: ActorRef = system.actorOf(Props(new DummyActor))
  val queuesActor: ActorRef = system.actorOf(Props(new DummyActor))
  val staffActor: ActorRef = system.actorOf(Props(new DummyActor))
  val queueUpdatesActor: ActorRef = system.actorOf(Props(new DummyActor))
  val staffUpdatesActor: ActorRef = system.actorOf(Props(new DummyActor))
  val flightUpdatesActor: ActorRef = system.actorOf(Props(new DummyActor))
  val pointInTime = "2020-07-06T12:00"
  val myNow: () => SDateLike = () => SDate(pointInTime)
  val journalType: StreamingJournalLike = InMemoryStreamingJournal

  "Given a PartitionedPortStateActor, a legacy data cutoff off of 2020-07-06T12:00" >> {
    "When I request GetStateForDateRange wrapped in a PointInTimeQuery for 2020-07-06T12:00 (matching the cutoff)" >> {
      "I should see that no request is forwarded to the CrunchStateReadActor" >> {
        val portStateActor = system.actorOf(Props(new PartitionedPortStateActor(
          flightsActor,
          queuesActor,
          staffActor,
          queueUpdatesActor,
          staffUpdatesActor,
          flightUpdatesActor,
          myNow,
          journalType
        )))
        val rangeStart = SDate("2020-10-10")
        val rangeEnd = rangeStart.addDays(1)
        val dateRangeMessage = GetStateForDateRange(rangeStart.millisSinceEpoch, rangeEnd.millisSinceEpoch)
        val pitMessage = PointInTimeQuery(SDate(pointInTime).millisSinceEpoch, dateRangeMessage)
        portStateActor ! pitMessage
        probe.expectNoMessage(250.milliseconds)
        success
      }
    }

    "When I request GetStateForDateRange with an end date whose last local midnight is before the legacy cutoff" >> {
      "I should see that no request is forwarded to the CrunchStateReadActor" >> {
        val portStateActor = system.actorOf(Props(new PartitionedPortStateActor(
          flightsActor,
          queuesActor,
          staffActor,
          queueUpdatesActor,
          staffUpdatesActor,
          flightUpdatesActor,
          myNow,
          journalType
        )))
        val rangeStart = SDate("2020-07-06T00:00", utcTimeZone)
        val rangeEnd = SDate("2020-07-07T12:59", utcTimeZone)
        val message = GetStateForDateRange(rangeStart.millisSinceEpoch, rangeEnd.millisSinceEpoch)
        portStateActor ! message
        probe.expectNoMessage(250.milliseconds)
        success
      }
    }

  }
}
