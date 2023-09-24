package info.nightscout.pump.medtrum.ui

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import app.aaps.interfaces.logging.AAPSLogger
import app.aaps.interfaces.protection.ProtectionCheck
import app.aaps.interfaces.resources.ResourceHelper
import app.aaps.interfaces.rx.AapsSchedulers
import info.nightscout.core.ui.dialogs.OKDialog
import info.nightscout.pump.medtrum.MedtrumPump
import info.nightscout.pump.medtrum.R
import info.nightscout.pump.medtrum.code.EventType
import info.nightscout.pump.medtrum.code.PatchStep
import info.nightscout.pump.medtrum.comm.enums.MedtrumPumpState
import info.nightscout.pump.medtrum.databinding.FragmentMedtrumOverviewBinding
import info.nightscout.pump.medtrum.ui.viewmodel.MedtrumOverviewViewModel
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject

class MedtrumOverviewFragment : MedtrumBaseFragment<FragmentMedtrumOverviewBinding>() {

    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var medtrumPump: MedtrumPump
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var rh: ResourceHelper

    private var disposable: CompositeDisposable = CompositeDisposable()

    override fun getLayoutId(): Int = R.layout.fragment_medtrum_overview

    override fun onDestroy() {
        super.onDestroy()
        disposable.clear()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.apply {
            viewmodel = ViewModelProvider(this@MedtrumOverviewFragment, viewModelFactory)[MedtrumOverviewViewModel::class.java]
            viewmodel?.apply {
                eventHandler.observe(viewLifecycleOwner) { evt ->
                    when (evt.peekContent()) {
                        EventType.CHANGE_PATCH_CLICKED -> requireContext().apply {
                            protectionCheck.queryProtection(
                                requireActivity(),
                                ProtectionCheck.Protection.PREFERENCES,
                                {
                                    val nextStep = when {
                                        medtrumPump.pumpState > MedtrumPumpState.EJECTED && medtrumPump.pumpState < MedtrumPumpState.STOPPED -> {
                                            PatchStep.START_DEACTIVATION
                                        }

                                        medtrumPump.pumpState in listOf(MedtrumPumpState.STOPPED, MedtrumPumpState.NONE)                     -> {
                                            PatchStep.PREPARE_PATCH
                                        }

                                        else                                                                                                 -> {
                                            PatchStep.RETRY_ACTIVATION
                                        }
                                    }
                                    startActivity(MedtrumActivity.createIntentFromMenu(this, nextStep))
                                }
                            )
                        }

                        EventType.PROFILE_NOT_SET      -> {
                            OKDialog.show(requireActivity(), rh.gs(info.nightscout.core.ui.R.string.message), rh.gs(R.string.no_profile_selected))
                        }

                        EventType.SERIAL_NOT_SET       -> {
                            OKDialog.show(requireActivity(), rh.gs(info.nightscout.core.ui.R.string.message), rh.gs(R.string.no_sn_in_settings))
                        }
                    }
                }
            }
        }
    }
}
