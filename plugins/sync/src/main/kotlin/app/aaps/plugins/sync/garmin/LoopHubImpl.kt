package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.GlucoseUnit
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.main.constraints.ConstraintObject
import app.aaps.core.main.graph.OverviewData
import app.aaps.database.ValueWrapper
import app.aaps.database.entities.EffectiveProfileSwitch
import app.aaps.database.entities.GlucoseValue
import app.aaps.database.entities.HeartRate
import app.aaps.database.entities.OfflineEvent
import app.aaps.database.entities.TemporaryTarget
import app.aaps.database.entities.UserEntry
import app.aaps.database.entities.ValueWithUnit
import app.aaps.database.impl.AppRepository
import app.aaps.database.impl.transactions.CancelCurrentOfflineEventIfAnyTransaction
import app.aaps.database.impl.transactions.CancelCurrentTemporaryTargetIfAnyTransaction
import app.aaps.database.impl.transactions.InsertAndCancelCurrentTemporaryTargetTransaction
import app.aaps.database.impl.transactions.InsertOrUpdateHeartRateTransaction
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * Interface to the functionality of the looping algorithm and storage systems.
 */
class LoopHubImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val commandQueue: CommandQueue,
    private val constraintChecker: ConstraintsChecker,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val profileFunction: ProfileFunction,
    private val repo: AppRepository,
    private val userEntryLogger: UserEntryLogger,
    private val sp: SP,
    private val overviewData: OverviewData,
    private val profileUtil: ProfileUtil,
    private val rh: ResourceHelper,
    private val uiInteraction: UiInteraction

) : LoopHub {

    @VisibleForTesting
    var clock: Clock = Clock.systemUTC()

    /** Returns the active insulin profile. */
    override val currentProfile: Profile? get() = profileFunction.getProfile()

    /** Returns the name of the active insulin profile. */
    override val currentProfileName: String
        get() = profileFunction.getProfileName()

    /** Returns the glucose unit (mg/dl or mmol/l) as selected by the user. */
    override val glucoseUnit: GlucoseUnit
        get() = GlucoseUnit.fromText(sp.getString(
            app.aaps.core.utils.R.string.key_units,
            GlucoseUnit.MGDL.asText))

    /** Returns the remaining bolus insulin on board. */
    override val insulinOnboard: Double
        get() = iobCobCalculator.calculateIobFromBolus().iob

    /** Returns the remaining bolus and basal insulin on board. */
    override val insulinBasalOnboard :Double
        get() = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().basaliob

    /** Returns the remaining carbs on board. */
    override val carbsOnboard: Double?
       get() = overviewData.cobInfo(iobCobCalculator).displayCob

    /** Returns true if the pump is connected. */
    override val isConnected: Boolean get() = !loop.isDisconnected

    /** Returns true if the current profile is set of a limited amount of time. */
    override val isTemporaryProfile: Boolean
        get() {
            val resp = repo.getEffectiveProfileSwitchActiveAt(clock.millis())
            val ps: EffectiveProfileSwitch? =
                (resp.blockingGet() as? ValueWrapper.Existing<EffectiveProfileSwitch>)?.value
            return ps != null && ps.originalDuration > 0
        }

    /** Returns the factor by which the basal rate is currently raised (> 1) or lowered (< 1). */
    override val temporaryBasal: Double
        get() {
            val apsResult = loop.lastRun?.constraintsProcessed
            return if (apsResult == null) Double.NaN else apsResult.percent / 100.0
        }

    /** Tells the loop algorithm that the pump is physically connected. */
    override fun connectPump() {
        repo.runTransaction(
            CancelCurrentOfflineEventIfAnyTransaction(clock.millis())
        ).subscribe()
        commandQueue.cancelTempBasal(true, null)
        userEntryLogger.log(UserEntry.Action.RECONNECT, UserEntry.Sources.GarminDevice)
    }

    /** Tells the loop algorithm that the pump will be physically disconnected
     *  for the given number of minutes. */
    override fun disconnectPump(minutes: Int) {
        currentProfile?.let { p ->
            loop.goToZeroTemp(minutes, p, OfflineEvent.Reason.DISCONNECT_PUMP)
            userEntryLogger.log(
                UserEntry.Action.DISCONNECT,
                UserEntry.Sources.GarminDevice,
                ValueWithUnit.Minute(minutes)
            )
        }
    }

    /** Retrieves the glucose values starting at from. */
    override fun getGlucoseValues(from: Instant, ascending: Boolean): List<GlucoseValue> {
        return repo.compatGetBgReadingsDataFromTime(from.toEpochMilli(), ascending)
                   .blockingGet()
    }

    /** Notifies the system that carbs were eaten and stores the value. */
    override fun postCarbs(carbohydrates: Int) {
        aapsLogger.info(LTag.GARMIN, "post $carbohydrates g carbohydrates")
        val carbsAfterConstraints =
            carbohydrates.coerceAtMost(constraintChecker.getMaxCarbsAllowed().value())
        userEntryLogger.log(
            UserEntry.Action.CARBS,
            UserEntry.Sources.GarminDevice,
            ValueWithUnit.Gram(carbsAfterConstraints)
        )
        val detailedBolusInfo = DetailedBolusInfo().apply {
            eventType = DetailedBolusInfo.EventType.CARBS_CORRECTION
            carbs = carbsAfterConstraints.toDouble()
        }
        commandQueue.bolus(detailedBolusInfo, null)
    }

    /** Triggers a bolus. */
    override fun postBolus(bolus: Double) {
        aapsLogger.info(LTag.GARMIN, "trigger a bolus of $bolus U")
        userEntryLogger.log(
            UserEntry.Action.BOLUS, UserEntry.Sources.GarminDevice,
            "",
            ValueWithUnit.Insulin(bolus)
        )
        val insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(bolus, aapsLogger)).value()
        if (insulinAfterConstraints > 0) {
            val detailedBolusInfo = DetailedBolusInfo()
            detailedBolusInfo.eventType = DetailedBolusInfo.EventType.CORRECTION_BOLUS
            detailedBolusInfo.insulin = insulinAfterConstraints
            detailedBolusInfo.context = null
            detailedBolusInfo.notes = ""
            detailedBolusInfo.timestamp = System.currentTimeMillis()
            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                override fun run() {
                    if (!result.success) {
                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                    }
                }
            })
        }
    }

    override fun postTempTarget(target: Double, duration: Int) {
        if (target == 0.0 || duration == 0) {
            repo.runTransactionForResult(CancelCurrentTemporaryTargetIfAnyTransaction(
                System.currentTimeMillis()
            )).subscribe({ result ->
                 result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated temp target $it") }
             }, {
                 aapsLogger.error(LTag.DATABASE, "Error while saving temporary target", it)
             })
            userEntryLogger.log(
                UserEntry.Action.CANCEL_TT,
                UserEntry.Sources.GarminDevice,
            )
        } else {
            repo.runTransactionForResult(InsertAndCancelCurrentTemporaryTargetTransaction(
                    timestamp = System.currentTimeMillis(),
                    duration = TimeUnit.MINUTES.toMillis(duration.toLong()),
                    reason = TemporaryTarget.Reason.CUSTOM,
                    lowTarget = profileUtil.convertToMgdl(target, profileUtil.units),
                    highTarget = profileUtil.convertToMgdl(target, profileUtil.units)
            )).subscribe({ result ->
                  result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted temp target $it") }
                  result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated temp target $it") }
              }, {
                  aapsLogger.error(LTag.DATABASE, "Error while saving temporary target", it)
              })
            userEntryLogger.log(
                UserEntry.Action.TT,
                UserEntry.Sources.GarminDevice,
                ValueWithUnit.TherapyEventTTReason(TemporaryTarget.Reason.CUSTOM),
                ValueWithUnit.fromGlucoseUnit(target, profileUtil.units.asText),
                ValueWithUnit.Minute(duration)
            )
        }
    }

    /** Stores hear rate readings that a taken and averaged of the given interval. */
    override fun storeHeartRate(
        samplingStart: Instant, samplingEnd: Instant,
        avgHeartRate: Int,
        device: String?) {
        val hr = HeartRate(
            timestamp = samplingStart.toEpochMilli(),
            duration = samplingEnd.toEpochMilli() - samplingStart.toEpochMilli(),
            dateCreated = clock.millis(),
            beatsPerMinute = avgHeartRate.toDouble(),
            device = device ?: "Garmin",
        )
        repo.runTransaction(InsertOrUpdateHeartRateTransaction(hr)).blockingAwait()
    }
}