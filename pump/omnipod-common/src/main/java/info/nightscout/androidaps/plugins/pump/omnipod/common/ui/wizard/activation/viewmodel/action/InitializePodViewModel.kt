package info.nightscout.androidaps.plugins.pump.omnipod.common.ui.wizard.activation.viewmodel.action

import app.aaps.interfaces.logging.AAPSLogger
import app.aaps.interfaces.rx.AapsSchedulers
import dagger.android.HasAndroidInjector

abstract class InitializePodViewModel(
    injector: HasAndroidInjector,
    logger: AAPSLogger,
    aapsSchedulers: AapsSchedulers
) : PodActivationActionViewModelBase(injector, logger, aapsSchedulers)