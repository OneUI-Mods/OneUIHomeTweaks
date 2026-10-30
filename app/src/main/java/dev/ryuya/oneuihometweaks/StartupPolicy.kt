package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min

/* Monotonic-time startup policy, independent of host APIs. */
object StartupPolicy {
    @JvmStatic
    fun nextCount(
        previousCount: Int,
        healthy: Boolean,
        previousElapsed: Long,
        elapsed: Long,
        exit: StartupPolicy.Exit?,
    ): Int {
        val count = max(0, min(3, previousCount))
        if (
            healthy ||
                previousElapsed <= 0 ||
                elapsed < previousElapsed ||
                elapsed - previousElapsed > 600000 ||
                exit != StartupPolicy.Exit.CRASH
        )
            return 0
        return min(3, count + 1)
    }

    enum class Exit {
        CRASH,
        INTENTIONAL,
        UNKNOWN,
    }
}
