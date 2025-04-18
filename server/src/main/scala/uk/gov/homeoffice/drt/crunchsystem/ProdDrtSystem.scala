package uk.gov.homeoffice.drt.crunchsystem

import actors._
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.util.Timeout
import com.google.inject.Inject
import manifests.{ManifestLookup, ManifestLookupLike}
import slickdb._
import uk.gov.homeoffice.drt.db._
import uk.gov.homeoffice.drt.db.dao.{ABFeatureDao, IABFeatureDao, IUserFeedbackDao, StaffShiftsDao, UserFeedbackDao}
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.service.staffing.{ShiftsService, ShiftsServiceImpl}
import uk.gov.homeoffice.drt.service.{ActorsServiceService, FeedService, ProdFeedService}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike}

import javax.inject.Singleton
import scala.concurrent.ExecutionContext

@Singleton
case class ProdDrtSystem @Inject()(airportConfig: AirportConfig, params: DrtParameters, now: () => SDateLike)
                                  (implicit val materializer: Materializer,
                                   val ec: ExecutionContext,
                                   val system: ActorSystem,
                                   val timeout: Timeout) extends DrtSystemInterface {

  lazy override val aggregatedDb: AggregatedDbTables = AggregateDb

  lazy override val akkaDb: AkkaDbTables = AkkaDb

  override val minuteLookups: MinuteLookupsLike = MinuteLookups(
    now,
    MilliTimes.oneDayMillis,
    airportConfig.queuesByTerminal,
    update15MinuteQueueSlotsLiveView
  )

  override val flightLookups: FlightLookupsLike = FlightLookups(
    system,
    now,
    airportConfig.queuesByTerminal,
    params.maybeRemovalCutOffSeconds,
    paxFeedSourceOrder,
    splitsCalculator.terminalSplits,
    updateFlightsLiveView,
  )

  override val manifestLookupService: ManifestLookupLike = ManifestLookup(AggregateDb)

  override val manifestLookups: ManifestLookups = ManifestLookups(system, airportConfig.terminals)

  override val userService: UserTableLike = UserTable(AggregateDb)

  override val featureGuideService: FeatureGuideTableLike = FeatureGuideTable(AggregateDb)

  override val featureGuideViewService: FeatureGuideViewLike = FeatureGuideViewTable(AggregateDb)

  override val dropInService: DropInTableLike = DropInTable(AggregateDb)

  override val dropInRegistrationService: DropInsRegistrationTableLike = DropInsRegistrationTable(AggregateDb)

  override val userFeedbackService: IUserFeedbackDao = UserFeedbackDao(AggregateDb)

  override val abFeatureService: IABFeatureDao = ABFeatureDao(AggregateDb)

  override val shiftsService: ShiftsService = ShiftsServiceImpl(StaffShiftsDao(AggregateDb))


  lazy override val actorService: ActorsServiceLike = ActorsServiceService(
    journalType = StreamingJournal.forConfig(config),
    airportConfig = airportConfig,
    now = now,
    forecastMaxDays = params.forecastMaxDays,
    flightLookups = flightLookups,
    minuteLookups = minuteLookups,
  )

  lazy val feedService: FeedService = ProdFeedService(
    journalType = journalType,
    airportConfig = airportConfig,
    now = now,
    params = params,
    config = config,
    paxFeedSourceOrder = paxFeedSourceOrder,
    flightLookups = flightLookups,
    manifestLookups = manifestLookups,
    requestAndTerminateActor = actorService.requestAndTerminateActor,
    params.forecastMaxDays,
    params.legacyFeedArrivalsBeforeDate,
  )


  lazy val persistentActors: PersistentStateActors = ProdPersistentStateActors(
    system,
    now,
    manifestLookups,
    airportConfig.terminals,
  )

  override def run(): Unit = applicationService.run()

}
