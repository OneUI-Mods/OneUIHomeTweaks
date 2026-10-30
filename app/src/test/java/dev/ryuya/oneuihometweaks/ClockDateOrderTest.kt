package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test

class ClockDateOrderTest {
    @Test
    fun dateOffNeverInventsDate() {
        assertNull(ClockDateOrder.parts("17:41", "17:41", "午後5:41", "10/17"))
        assertEquals("17:41", ClockDateOrderTest.move("17:41", "17:41", "午後5:41", "10/17"))
    }

    @Test
    fun preservesNativeDateAndSpacing() {
        assertEquals(
            "10/17  17:41",
            ClockDateOrderTest.move("17:41  10/17", "17:41", "午後5:41", "10/17"),
        )
        assertEquals(
            "10月17日(土)   17:41",
            ClockDateOrderTest.move("17:41   10月17日(土)", "17:41", "午後5:41", "10月17日(土)"),
        )
    }

    @Test
    fun amPmAndAccessibilityUseExactNativeTime() {
        assertEquals(
            "Sat, Oct 17  5:41 PM",
            ClockDateOrderTest.move("5:41 PM  Sat, Oct 17", "5:41", "5:41 PM", "Sat, Oct 17"),
        )
        assertEquals(
            "토요일  17:41:32",
            ClockDateOrderTest.move("17:41:32  토요일", "17:41:32", "오후5:41:32", "토요일"),
        )
    }

    @Test
    fun unknownAlreadyReorderedAndIncompleteFormatsStayNative() {
        for (text in
            arrayOf<String>(
                "10/17  17:41",
                "AiNudge 17:41  10/17",
                "17:41 — 10/17",
                "17:41  ",
                "10/17",
            )) assertNull(ClockDateOrder.parts(text, "17:41", "午後5:41", "10/17"))
        assertNull(ClockDateOrder.parts("17:41  ", "17:41", "午後5:41", ""))
        assertNull(ClockDateOrder.parts(null, "17:41", "午後5:41", "10/17"))
        assertNull(ClockDateOrder.parts("  10/17", "", null, "10/17"))
    }

    companion object {
        @JvmStatic
        private fun move(text: String, time: String?, spoken: String?, date: String?): String? {
            val parts = ClockDateOrder.parts(text, time, spoken, date)
            return if (parts == null) text
            else
                (text.substring(parts.dateStart) +
                    text.substring(parts.timeEnd, parts.dateStart) +
                    text.substring(0, parts.timeEnd))
        }
    }
}
