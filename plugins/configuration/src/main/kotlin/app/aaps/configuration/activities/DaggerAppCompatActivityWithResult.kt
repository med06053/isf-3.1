package app.aaps.configuration.activities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import app.aaps.configuration.R
import app.aaps.configuration.maintenance.CustomWatchfaceFileContract
import app.aaps.configuration.maintenance.PrefsFileContract
import app.aaps.interfaces.logging.AAPSLogger
import app.aaps.interfaces.logging.LTag
import app.aaps.interfaces.maintenance.ImportExportPrefs
import app.aaps.interfaces.resources.ResourceHelper
import app.aaps.interfaces.rx.bus.RxBus
import app.aaps.interfaces.rx.events.EventThemeSwitch
import dagger.android.support.DaggerAppCompatActivity
import info.nightscout.core.ui.dialogs.OKDialog
import info.nightscout.core.ui.locale.LocaleHelper
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject

open class DaggerAppCompatActivityWithResult : DaggerAppCompatActivity() {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var importExportPrefs: ImportExportPrefs
    @Inject lateinit var aapsLogger: AAPSLogger

    private val compositeDisposable = CompositeDisposable()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        compositeDisposable.add(rxBus.toObservable(EventThemeSwitch::class.java).subscribe {
            recreate()
        })

    }

    override fun onDestroy() {
        compositeDisposable.clear()
        super.onDestroy()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    val callForPrefFile = registerForActivityResult(PrefsFileContract()) {
        it?.let {
            importExportPrefs.importSharedPreferences(this, it)
        }
    }

    val callForCustomWatchfaceFile = registerForActivityResult(CustomWatchfaceFileContract()) { }

    val callForBatteryOptimization = registerForActivityResult(OptimizationPermissionContract()) {
        updateButtons()
    }

    val requestMultiplePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        permissions.entries.forEach {
            aapsLogger.info(LTag.CORE, "Permission ${it.key} ${it.value}")
            if (it.value)
                if (ActivityCompat.checkSelfPermission(this, it.key) == PackageManager.PERMISSION_GRANTED) {
                    when (it.key) {
                        Manifest.permission.WRITE_EXTERNAL_STORAGE ->
                            //show dialog after permission is granted
                            OKDialog.show(this, "", rh.gs(R.string.alert_dialog_storage_permission_text))
                        //  ignore the rest
                    }
                }
        }
        updateButtons()
    }

    // Used for SetupWizardActivity
    open fun updateButtons() {}
}