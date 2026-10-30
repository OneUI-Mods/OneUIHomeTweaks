package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Accept only the native Samsung time + separator + date composition. */
object ClockDateOrder {
    @JvmStatic
    fun parts(
        rendered: String?,
        time: String?,
        spokenTime: String?,
        date: String?,
    ): ClockDateOrder.Parts? {
        if (rendered == null || date == null || date.isEmpty()) return null
        for (candidate in arrayOf<String?>(time, spokenTime)) {
            if (candidate == null || candidate.isEmpty()) continue
            for (separator in arrayOf<String>("  ", "   ")) if (
                rendered == candidate + separator + date
            )
                return ClockDateOrder.Parts(candidate.length, candidate.length + separator.length)
        }
        return null
    }

    @JvmRecord
    data class Parts(val timeEnd: Int, val dateStart: Int) {
        override fun toString(): String = "Parts[timeEnd=$timeEnd, dateStart=$dateStart]"
    }
}
