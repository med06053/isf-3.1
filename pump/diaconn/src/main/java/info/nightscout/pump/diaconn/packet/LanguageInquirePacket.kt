package info.nightscout.pump.diaconn.packet

import app.aaps.interfaces.logging.LTag
import dagger.android.HasAndroidInjector
import info.nightscout.pump.diaconn.DiaconnG8Pump
import javax.inject.Inject

/**
 * LanguageInquirePacket
 */
class LanguageInquirePacket(
    injector: HasAndroidInjector
) : DiaconnG8Packet(injector) {

    @Inject lateinit var diaconnG8Pump: DiaconnG8Pump

    init {
        msgType = 0x60.toByte()
        aapsLogger.debug(LTag.PUMPCOMM, "LanguageInquirePacket init")
    }

    override fun encode(msgSeq: Int): ByteArray {
        val buffer = prefixEncode(msgType, msgSeq, MSG_CON_END)
        return suffixEncode(buffer)
    }

    override fun getFriendlyName(): String {
        return "PUMP_LANGUAGE_INQUIRE"
    }
}