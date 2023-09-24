package info.nightscout.plugins.sync.nsclient.extensions

import app.aaps.interfaces.profile.ProfileUtil
import app.aaps.interfaces.utils.DateUtil
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import info.nightscout.core.utils.JsonHelper
import info.nightscout.database.entities.BolusCalculatorResult
import org.json.JSONObject

fun BolusCalculatorResult.toJson(isAdd: Boolean, dateUtil: DateUtil, profileUtil: ProfileUtil): JSONObject =
    JSONObject()
        .put("eventType", info.nightscout.database.entities.TherapyEvent.Type.BOLUS_WIZARD.text)
        .put("created_at", dateUtil.toISOString(timestamp))
        .put("isValid", isValid)
        .put("bolusCalculatorResult", Gson().toJson(this))
        .put("date", timestamp)
        .put("glucose", profileUtil.fromMgdlToUnits(glucoseValue))
        .put("units", profileUtil.units.asText)
        .put("notes", note)
        .also { if (isAdd && interfaceIDs.nightscoutId != null) it.put("_id", interfaceIDs.nightscoutId) }

fun BolusCalculatorResult.Companion.fromJson(jsonObject: JSONObject): BolusCalculatorResult? {
    val timestamp =
        JsonHelper.safeGetLongAllowNull(jsonObject, "mills", null)
            ?: JsonHelper.safeGetLongAllowNull(jsonObject, "date", null)
            ?: return null
    val isValid = JsonHelper.safeGetBoolean(jsonObject, "isValid", true)
    val id = JsonHelper.safeGetStringAllowNull(jsonObject, "identifier", null)
        ?: JsonHelper.safeGetStringAllowNull(jsonObject, "_id", null)
        ?: return null
    val bcrString = JsonHelper.safeGetStringAllowNull(jsonObject, "bolusCalculatorResult", null) ?: return null

    if (timestamp == 0L) return null

    return try {
        Gson().fromJson(bcrString, BolusCalculatorResult::class.java)
            .also {
                it.id = 0
                it.isValid = isValid
                it.interfaceIDs.nightscoutId = id
                it.version = 0
            }
    } catch (e: JsonSyntaxException) {
        null
    }
}
