package services.arrivals

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff, UniqueArrival}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{DateLike, LocalDate, UtcDate}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object MergeArrivals {
  case class FeedArrivalSet(isPrimary: Boolean, maybeScheduleTolerance: Option[FiniteDuration], arrivals: Map[UniqueArrival, Arrival])

  def apply(existingMerged: (Terminal, UtcDate) => Future[Set[UniqueArrival]],
            arrivalSources: Seq[(DateLike, Terminal) => Future[FeedArrivalSet]],
            adjustments: Arrival => Arrival,
           )
           (implicit ec: ExecutionContext): (Terminal, UtcDate) => Future[ArrivalsDiff] =
    (terminal, date) => {
      for {
        arrivalSets <- Future.sequence(arrivalSources.map(_(date, terminal)))
        existing <- existingMerged(terminal, date)
      } yield {
        val validatedArrivalSets = arrivalSets.map {
          case fas@FeedArrivalSet(_, _, arrivals) =>
            val filtered = arrivals.filterNot {
              case (_, arrival) =>
                val isInvalidSuffix = arrival.FlightCodeSuffix.exists(fcs => fcs.suffix == "P" || fcs.suffix == "F")
                val isDomestic = arrival.Origin.isDomestic
                isInvalidSuffix || isDomestic
            }
            fas.copy(arrivals = filtered)
        }
        mergeSets(existing, validatedArrivalSets, adjustments)
      }
    }

  def mergeSets(existingMerged: Set[UniqueArrival],
                arrivalSets: Seq[FeedArrivalSet],
                adjustments: Arrival => Arrival,
               ): ArrivalsDiff = {
    def existsInPrimary(uniqueArrival: UniqueArrival): Boolean = arrivalSets
      .filter {
        case FeedArrivalSet(isPrimary, _, _) => isPrimary
      }
      .exists {
        case FeedArrivalSet(isPrimary, _, arrivals) => isPrimary && arrivals.contains(uniqueArrival)
      }

    def findFuzzyInPrimary(maybeTolerance: Option[FiniteDuration], uniqueArrival: UniqueArrival): Option[UniqueArrival] =
      maybeTolerance.flatMap { tolerance =>
        arrivalSets
          .filter {
            case FeedArrivalSet(isPrimary, _, _) => isPrimary
          }
          .map {
            case FeedArrivalSet(_, _, arrivals) =>
              arrivals.keys.find { case UniqueArrival(n, t, s, o) =>
                def fuzzyScheduleMatches: Boolean = uniqueArrival.scheduled - tolerance.toMillis <= s && s <= uniqueArrival.scheduled + tolerance.toMillis

                val isFuzzyMatch = uniqueArrival.number == n && uniqueArrival.terminal == t && uniqueArrival.origin == o && fuzzyScheduleMatches
                isFuzzyMatch
              }
          }
          .find(_.isDefined)
          .flatten
      }

    val newMerged = arrivalSets.foldLeft(Map.empty[UniqueArrival, Arrival]) {
      case (acc, FeedArrivalSet(isPrimary, maybeTolerance, arrivals)) =>
        arrivals.foldLeft(acc) {
          case (acc, (uniqueArrival, nextArrival)) =>
            acc.get(uniqueArrival) match {
              case Some(existingArrival) =>
                acc + (uniqueArrival -> mergeArrivals(existingArrival, nextArrival))
              case None =>
                if (isPrimary || existsInPrimary(uniqueArrival)) {
                  acc + (uniqueArrival -> nextArrival)
                }
                else {
                  findFuzzyInPrimary(maybeTolerance, uniqueArrival) match {
                    case Some(primaryUniqueArrival) =>
                      acc + (primaryUniqueArrival -> nextArrival.copy(Scheduled = primaryUniqueArrival.scheduled))
                    case None =>
                      acc
                  }
                }
            }
        }
    }

    val mergedAndAdjusted = newMerged.values.map(adjustments).map(a => a.unique -> a).toMap
    val removed = existingMerged -- mergedAndAdjusted.keySet
    ArrivalsDiff(mergedAndAdjusted, removed)
  }

  def mergeArrivals(current: Arrival, next: Arrival): Arrival =
    next.copy(
      Operator = next.Operator.orElse(current.Operator),
      CarrierCode = current.CarrierCode,
      Status = if (next.Status.description.isEmpty) current.Status else next.Status,
      Estimated = next.Estimated.orElse(current.Estimated),
      Actual = next.Actual.orElse(current.Actual),
      EstimatedChox = next.EstimatedChox.orElse(current.EstimatedChox),
      ActualChox = next.ActualChox.orElse(current.ActualChox),
      Gate = next.Gate.orElse(current.Gate),
      Stand = next.Stand.orElse(current.Stand),
      MaxPax = next.MaxPax.orElse(current.MaxPax),
      RunwayID = next.RunwayID.orElse(current.RunwayID),
      BaggageReclaimId = next.BaggageReclaimId.orElse(current.BaggageReclaimId),
      FeedSources = next.FeedSources ++ current.FeedSources,
      CarrierScheduled = next.CarrierScheduled.orElse(current.CarrierScheduled),
      ScheduledDeparture = next.ScheduledDeparture.orElse(current.ScheduledDeparture),
      RedListPax = next.RedListPax.orElse(current.RedListPax),
      PassengerSources = next.PassengerSources ++ current.PassengerSources,
      FlightCodeSuffix = next.FlightCodeSuffix.orElse(current.FlightCodeSuffix),
      PreviousPort = next.PreviousPort.orElse(current.PreviousPort),
    )

  def processingRequestToArrivalsDiff(mergeArrivalsForDate: (Terminal, UtcDate) => Future[ArrivalsDiff],
                                      setPcpTimes: Seq[Arrival] => Future[Seq[Arrival]],
                                      addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff],
                                      today: () => LocalDate,
                                     )
                                     (implicit ec: ExecutionContext): Flow[TerminalUpdateRequest, ArrivalsDiff, NotUsed] =
    Flow[TerminalUpdateRequest]
      .mapAsync(1) {
        request =>
          mergeArrivalsForDate(request.terminal, UtcDate(request.date.year, request.date.month, request.date.day))
            .flatMap { arrivalsDiff =>
              if (request.date >= today()) addArrivalPredictions(arrivalsDiff)
              else Future.successful(arrivalsDiff)
            }
            .flatMap { diff =>
              setPcpTimes(diff.toUpdate.values.toSeq)
                .map(arrivals => diff.copy(toUpdate = arrivals.map(a => a.unique -> a).toMap))
            }
      }
}
