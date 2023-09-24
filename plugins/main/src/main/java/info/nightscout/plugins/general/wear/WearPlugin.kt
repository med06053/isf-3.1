package info.nightscout.plugins.general.wear

import android.content.Context
import app.aaps.interfaces.logging.AAPSLogger
import app.aaps.interfaces.plugin.PluginBase
import app.aaps.interfaces.plugin.PluginDescription
import app.aaps.interfaces.plugin.PluginType
import app.aaps.interfaces.resources.ResourceHelper
import app.aaps.interfaces.rx.AapsSchedulers
import app.aaps.interfaces.rx.bus.RxBus
import app.aaps.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.interfaces.rx.events.EventDismissBolusProgressIfRunning
import app.aaps.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.interfaces.rx.events.EventMobileDataToWear
import app.aaps.interfaces.rx.events.EventMobileToWear
import app.aaps.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.interfaces.rx.events.EventPreferenceChange
import app.aaps.interfaces.rx.events.EventWearUpdateGui
import app.aaps.interfaces.rx.weardata.CwfData
import app.aaps.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.interfaces.rx.weardata.EventData
import app.aaps.interfaces.sharedPreferences.SP
import dagger.android.HasAndroidInjector
import info.nightscout.core.utils.fabric.FabricPrivacy
import info.nightscout.plugins.R
import info.nightscout.plugins.general.wear.wearintegration.DataHandlerMobile
import info.nightscout.plugins.general.wear.wearintegration.DataLayerListenerServiceMobileHelper
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val aapsSchedulers: AapsSchedulers,
    private val sp: SP,
    private val fabricPrivacy: FabricPrivacy,
    private val rxBus: RxBus,
    private val context: Context,
    private val dataHandlerMobile: DataHandlerMobile,
    private val dataLayerListenerServiceMobileHelper: DataLayerListenerServiceMobileHelper

) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .fragmentClass(WearFragment::class.java.name)
        .pluginIcon(info.nightscout.core.main.R.drawable.ic_watch)
        .pluginName(info.nightscout.core.ui.R.string.wear)
        .shortName(R.string.wear_shortname)
        .preferencesId(R.xml.pref_wear)
        .description(R.string.description_wear),
    aapsLogger, rh, injector
) {

    private val disposable = CompositeDisposable()

    var connectedDevice = "---"
    var savedCustomWatchface: CwfData? = null

    override fun onStart() {
        super.onStart()
        dataLayerListenerServiceMobileHelper.startService(context)
        disposable += rxBus
            .toObservable(EventDismissBolusProgressIfRunning::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event: EventDismissBolusProgressIfRunning ->
                           event.resultSuccess?.let {
                               val status =
                                   if (it) rh.gs(info.nightscout.core.ui.R.string.success)
                                   else rh.gs(R.string.no_success)
                               if (isEnabled()) rxBus.send(EventMobileToWear(EventData.BolusProgress(percent = 100, status = status)))
                           }
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventOverviewBolusProgress::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event: EventOverviewBolusProgress ->
                           if (!event.isSMB() || sp.getBoolean("wear_notifySMB", true)) {
                               if (isEnabled()) rxBus.send(EventMobileToWear(EventData.BolusProgress(percent = event.percent, status = event.status)))
                           }
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           dataHandlerMobile.resendData("EventPreferenceChange")
                           checkCustomWatchfacePreferences()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAutosensCalculationFinished::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ dataHandlerMobile.resendData("EventAutosensCalculationFinished") }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventLoopUpdateGui::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ dataHandlerMobile.resendData("EventLoopUpdateGui") }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventWearUpdateGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                           it.customWatchfaceData?.let { cwf ->
                               if (!it.exportFile) {
                                   savedCustomWatchface = cwf
                                   checkCustomWatchfacePreferences()
                               }
                           }
                       }, fabricPrivacy::logException)
    }

    fun checkCustomWatchfacePreferences() {
        savedCustomWatchface?.let { cwf ->
            val cwf_authorization = sp.getBoolean(info.nightscout.core.utils.R.string.key_wear_custom_watchface_autorization, false)
            if (cwf_authorization != cwf.metadata[CwfMetadataKey.CWF_AUTHORIZATION]?.toBooleanStrictOrNull()) {
                // resend new customWatchface to Watch with updated authorization for preferences update
                val newCwf = cwf.copy()
                newCwf.metadata[CwfMetadataKey.CWF_AUTHORIZATION] = sp.getBoolean(info.nightscout.core.utils.R.string.key_wear_custom_watchface_autorization, false).toString()
                rxBus.send(EventMobileDataToWear(EventData.ActionSetCustomWatchface(newCwf)))
            }
        }
    }

    override fun onStop() {
        disposable.clear()
        super.onStop()
        dataLayerListenerServiceMobileHelper.stopService(context)
    }
}