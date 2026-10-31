package dev.ryuya.oneuihometweaks

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import kotlin.jvm.JvmStatic

/* Private authenticated protocol, not a replacement for any Android Binder transaction. */
object RecentsFocusWire {
    // Outside the verified ATMS transaction range, inside Binder's application transaction range.
    const val DISCOVER: Int = 0x00f04f55
    const val STATUS: Int = 1
    const val ARM: Int = 2
    const val BEGIN: Int = 3
    const val END: Int = 4
    const val STOP: Int = 5
    const val DESCRIPTOR: String = "dev.ryuya.oneuihometweaks.recents.focus.v1"

    @JvmStatic
    @Throws(Exception::class)
    fun call(endpoint: IBinder, code: Int, data: Bundle?): Bundle? {
        val `in` = Parcel.obtain()
        val out = Parcel.obtain()
        try {
            `in`.writeInterfaceToken(RecentsFocusWire.DESCRIPTOR)
            `in`.writeBundle(if (data == null) Bundle() else data)
            check(endpoint.transact(code, `in`, out, 0)) { "Focus backend not loaded" }
            out.readException()
            val reply = out.readBundle(RecentsFocusWire::class.java.getClassLoader())
            check(
                !(reply == null ||
                    !RecentsFocusCompatibility.accepts(
                        reply.getInt("protocol", 0),
                        reply.getInt("build", -1),
                    ))
            ) {
                "Focus backend version mismatch"
            }
            return reply
        } finally {
            `in`.recycle()
            out.recycle()
        }
    }
}
