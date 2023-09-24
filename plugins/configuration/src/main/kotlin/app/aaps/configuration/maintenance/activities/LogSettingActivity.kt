package app.aaps.configuration.maintenance.activities

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import app.aaps.configuration.R
import app.aaps.configuration.databinding.ActivityLogsettingBinding
import app.aaps.interfaces.logging.L
import app.aaps.interfaces.logging.LogElement
import app.aaps.interfaces.resources.ResourceHelper
import info.nightscout.core.ui.activities.TranslatedDaggerAppCompatActivity
import javax.inject.Inject

class LogSettingActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var l: L
    @Inject lateinit var rh: ResourceHelper

    private lateinit var binding: ActivityLogsettingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsettingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        title = rh.gs(R.string.nav_logsettings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        createViewsForSettings()

        binding.reset.setOnClickListener {
            l.resetToDefaults()
            createViewsForSettings()
        }
    }

    private fun createViewsForSettings() {
        binding.placeholder.removeAllViews()
        for (element in l.getLogElements()) {
            val logViewHolder = LogViewHolder(element)
            binding.placeholder.addView(logViewHolder.baseView)
        }

    }

    internal inner class LogViewHolder(element: LogElement) {

        @Suppress("InflateParams")
        var baseView = layoutInflater.inflate(R.layout.logsettings_item, null) as LinearLayout

        init {
            (baseView.findViewById<View>(R.id.logsettings_description) as TextView).text = element.name
            val enabled = baseView.findViewById<CheckBox>(R.id.logsettings_visibility)
            enabled.isChecked = element.enabled
            enabled.setOnClickListener { element.enable(enabled.isChecked) }
        }

    }
}
