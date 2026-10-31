package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmStatic

/* Terminal diagnostics survive a closed process, but never an unrelated device boot. */
object StatusFreshness {
    @JvmStatic
    fun usable(
        state: String?,
        reportBoot: Int,
        boot: Int,
        reported: Long,
        now: Long,
        requested: Long,
    ): Boolean {
        if (reported <= 0 || reported > now || (reportBoot >= 0 && reportBoot != boot)) return false
        val live: Boolean = "ACTIVE" == state || "OFF" == state
        if (live) return reported >= requested
        // Legacy reports have no boot identity: do not revive an old crash/compatibility
        // diagnostic.
        return reportBoot >= 0 && boot >= 0
    }
}
