package info.nightscout.androidaps.danar.comm

import app.aaps.interfaces.logging.LTag
import dagger.android.HasAndroidInjector

class MsgHistoryBolus(
    injector: HasAndroidInjector
) : MsgHistoryAll(injector) {

    init {
        setCommand(0x3101)
        aapsLogger.debug(LTag.PUMPCOMM, "New message")
    }
    // Handle message taken from MsgHistoryAll
}