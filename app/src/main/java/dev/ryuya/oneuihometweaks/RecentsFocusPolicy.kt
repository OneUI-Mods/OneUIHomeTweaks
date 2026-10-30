package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Identity-based leases prevent an old gesture/death callback from ending a newer gesture. */
class RecentsFocusPolicy {
    @JvmRecord
    data class Target(
        val task: Int,
        val uid: Int,
        val user: Int,
        val display: Int,
        val windowMode: Int,
        val activityType: Int,
    ) {
        fun eligible(): Boolean {
            return task >= 0 &&
                uid >= 10000 &&
                uid <= 19999 &&
                user == 0 &&
                display == 0 &&
                windowMode == 1 &&
                activityType == 1
        }

        override fun toString(): String =
            "Target[task=$task, uid=$uid, user=$user, display=$display, windowMode=$windowMode, activityType=$activityType]"
    }

    @JvmRecord
    data class Lease(val owner: Any?, val target: RecentsFocusPolicy.Target?, val mode: Int) {
        override fun toString(): String = "Lease[owner=$owner, target=$target, mode=$mode]"
    }

    private var current: RecentsFocusPolicy.Lease? = null

    fun current(): RecentsFocusPolicy.Lease? {
        return current
    }

    fun begin(owner: Any?, target: RecentsFocusPolicy.Target?, mode: Int): Boolean {
        if (
            owner == null ||
                target == null ||
                !target.eligible() ||
                RecentsFocusPolicy.mode(mode) == RecentsFocusPolicy.NATIVE
        )
            return false
        current = RecentsFocusPolicy.Lease(owner, target, mode)
        return true
    }

    fun end(owner: Any?): RecentsFocusPolicy.Lease? {
        if (current == null || current!!.owner !== owner) return null
        val ended: RecentsFocusPolicy.Lease? = current
        current = null
        return ended
    }

    companion object {
        const val NATIVE: Int = 0
        const val NOTIFY: Int = 1
        const val PRESERVE: Int = 2

        @JvmStatic
        fun mode(value: Int): Int {
            return if (value == RecentsFocusPolicy.NOTIFY || value == RecentsFocusPolicy.PRESERVE)
                value
            else RecentsFocusPolicy.NATIVE
        }

        @JvmStatic
        fun canOverride(mode: Int, nativeStayFocus: Boolean): Boolean {
            // With native retention off, setting it after focus gain cannot initialize
            // DecorView.mStayFocus.
            return RecentsFocusPolicy.mode(mode) == RecentsFocusPolicy.NOTIFY ||
                (RecentsFocusPolicy.mode(mode) == RecentsFocusPolicy.PRESERVE && nativeStayFocus)
        }

        @JvmStatic
        fun allowedUid(uid: Int, moduleUid: Int, launcherUid: Int, appOnly: Boolean): Boolean {
            return uid >= 10000 &&
                uid <= 19999 &&
                (uid == moduleUid || (!appOnly && uid == launcherUid))
        }
    }
}
