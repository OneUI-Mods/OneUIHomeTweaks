package dev.ryuya.oneuihometweaks

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized
import kotlin.math.log
import kotlin.math.max

/* Host-private persistent startup protection; never touches system partitions. */
class CrashGuard private constructor(context: Context) {
    private val file: AtomicFile
    private val state: Properties = Properties()

    init {
        file = AtomicFile(File(context.getFilesDir(), CrashGuard.FILE))
    }

    fun blocked(): Boolean {
        return state.getProperty("blocked", "false").toBoolean()
    }

    @Synchronized
    fun healthy() {
        if (blocked()) return
        state.setProperty("healthy", "true")
        state.setProperty("attempts", "0")
        try {
            save()
        } catch (e: Exception) {
            HookEntry.log("GUARD_HEALTH_WRITE_ERROR " + e.javaClass.getSimpleName())
        }
    }

    @Synchronized
    @Throws(IOException::class)
    private fun save() {
        var out: FileOutputStream? = null
        try {
            out = file.startWrite()
            state.store(out, "One UI Home Tweaks startup protection")
            file.finishWrite(out)
        } catch (e: IOException) {
            if (out != null) file.failWrite(out)
            throw e
        }
    }

    companion object {
        const val FILE: String = "dev.ryuya.oneuihometweaks.guard"

        @JvmStatic
        @Throws(Exception::class)
        fun enter(context: Context): CrashGuard {
            val guard = CrashGuard(context)
            try {
                guard.file.openRead().use { `in` ->
                    guard.state.load(`in`)
                }
            } catch (ignored: FileNotFoundException) {}
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            var count = guard.state.getProperty("attempts", "0").toInt()
            if ("2" != guard.state.getProperty("schema")) count = max(0, count - 1)
            val previous = guard.state.getProperty("started", "0").toLong()
            val previousPid = guard.state.getProperty("pid", "0").toInt()
            val healthy = guard.state.getProperty("healthy", "true").toBoolean()
            if (!guard.state.getProperty("blocked", "false").toBoolean()) {
                var lastExit = StartupPolicy.Exit.UNKNOWN
                // Confirm real crash reason where Android provides it; don't count intentional
                // restarts.
                try {
                    val am = context.getSystemService<ActivityManager?>(ActivityManager::class.java)
                    for (exit in
                        am.getHistoricalProcessExitReasons(
                            context.getPackageName(),
                            if (previousPid > 0) previousPid else 0,
                            8,
                        )) {
                        if (
                            (context.getPackageName() != exit.getProcessName()) ||
                                (previousPid > 0 && exit.getPid() != previousPid) ||
                                exit.getTimestamp() < previous
                        )
                            continue
                        val reason = exit.getReason()
                        lastExit =
                            if (
                                reason == ApplicationExitInfo.REASON_CRASH ||
                                    reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                                    reason == ApplicationExitInfo.REASON_ANR
                            )
                                StartupPolicy.Exit.CRASH
                            else StartupPolicy.Exit.INTENTIONAL
                        break
                    }
                } catch (ignored: RuntimeException) {
                    /* unfinished-start fallback remains */
                }
                count =
                    StartupPolicy.nextCount(
                        count,
                        healthy,
                        guard.state.getProperty("elapsed", "0").toLong(),
                        elapsed,
                        lastExit,
                    )
                guard.state.setProperty("blocked", (count >= 3).toString())
                if (count >= 3) {
                    guard.state.setProperty("blockedSince", now.toString())
                    guard.state.setProperty("blockedReason", lastExit.name)
                }
                guard.state.setProperty("lastExit", lastExit.name)
            }
            guard.state.setProperty("schema", "2")
            guard.state.setProperty("attempts", count.toString())
            guard.state.setProperty("started", now.toString())
            guard.state.setProperty("elapsed", elapsed.toString())
            guard.state.setProperty("pid", Process.myPid().toString())
            guard.state.setProperty("healthy", "false")
            guard.save()
            return guard
        }
    }
}
