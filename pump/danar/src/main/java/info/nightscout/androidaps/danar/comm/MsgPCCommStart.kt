package info.nightscout.androidaps.danar.comm

import app.aaps.interfaces.logging.LTag
import dagger.android.HasAndroidInjector

class MsgPCCommStart(
    injector: HasAndroidInjector
) : MessageBase(injector) {

    init {
        setCommand(0x3001)
        aapsLogger.debug(LTag.PUMPCOMM, "New message")
    }

    override fun handleMessage(bytes: ByteArray) {
        aapsLogger.debug(LTag.PUMPCOMM, "PC comm start received")
    }
}