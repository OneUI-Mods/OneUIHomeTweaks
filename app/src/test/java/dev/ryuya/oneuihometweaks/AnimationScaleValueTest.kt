package dev.ryuya.oneuihometweaks

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

open class AnimationScaleValueTest {
    @Test
    open fun hundredStepsDoNotDrift() {
        var ticks = AnimationScaleValue.parse("1.19")
        for (i in 0..99) ticks = AnimationScaleValue.parse(AnimationScaleValue.wire(ticks + 1)!!)
        assertEquals(219, ticks)
        assertEquals("2.19", AnimationScaleValue.wire(ticks))
    }

    @Test
    open fun localizedNumbersAndExistingScaleRoundTrip() {
        for (locale in
            arrayOf<Locale?>(
                Locale.JAPAN,
                Locale.GERMANY,
                Locale.forLanguageTag("ar"),
            )) assertEquals(
            119,
            AnimationScaleValue.parse(AnimationScaleValue.format(119, locale)!!),
        )
        assertEquals(119, AnimationScaleValue.ticks(1.19f))
        assertEquals(119, AnimationScaleValue.parse("１．１９"))
        assertEquals(119, AnimationScaleValue.parse("1.1900"))
    }

    @Test
    open fun rejectInvalidValuesRatherThanClampOrRound() {
        for (value in
            arrayOf<String>(
                "",
                "Float.NaN",
                "Infinity",
                "-1",
                "10.01",
                "1.191",
                "1e2",
                "1.2.3",
                "1 19",
                "999999999999999",
            )) {
            assertThrows(RuntimeException::class.java, { AnimationScaleValue.parse(value) })
        }
        assertThrows(IllegalArgumentException::class.java, { AnimationScaleValue.ticks(Float.NaN) })
        assertEquals(0, AnimationScaleValue.parse("0"))
        assertEquals(1000, AnimationScaleValue.parse("10.00"))
    }
}
