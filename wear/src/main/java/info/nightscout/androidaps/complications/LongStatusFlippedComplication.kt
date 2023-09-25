@file:Suppress("DEPRECATION")

package info.nightscout.androidaps.complications

import android.app.PendingIntent
import android.support.wearable.complications.ComplicationData
import android.support.wearable.complications.ComplicationText
import app.aaps.core.interfaces.logging.LTag
import dagger.android.AndroidInjection
import info.nightscout.androidaps.data.RawDisplayData

/*
 * Created by dlvoy on 2019-11-12
 */
class LongStatusFlippedComplication : BaseComplicationProviderService() {

    // Not derived from DaggerService, do injection here
    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
    }

    override fun buildComplicationData(dataType: Int, raw: RawDisplayData, complicationPendingIntent: PendingIntent): ComplicationData? {
        var complicationData: ComplicationData? = null
        when (dataType) {
            ComplicationData.TYPE_LONG_TEXT -> {
                val glucoseLine = displayFormat.longGlucoseLine(raw)
                val detailsLine = displayFormat.longDetailsLine(raw)
                val builderLong = ComplicationData.Builder(ComplicationData.TYPE_LONG_TEXT)
                    .setLongTitle(ComplicationText.plainText(detailsLine))
                    .setLongText(ComplicationText.plainText(glucoseLine))
                    .setTapAction(complicationPendingIntent)
                complicationData = builderLong.build()
            }

            else                            -> aapsLogger.warn(LTag.WEAR, "Unexpected complication type $dataType")
        }
        return complicationData
    }

    override fun getProviderCanonicalName(): String = LongStatusFlippedComplication::class.java.canonicalName!!
    override fun usesSinceField(): Boolean = true
}