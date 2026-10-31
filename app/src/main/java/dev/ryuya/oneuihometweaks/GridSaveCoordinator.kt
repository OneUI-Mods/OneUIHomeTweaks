package dev.ryuya.oneuihometweaks

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Collections
import java.util.HashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmStatic
import kotlin.math.log

/*
 * User-initiated saves through the verified native editor, followed by read-only DB confirmation.
 */
class GridSaveCoordinator(bridge: DiyGridBridge) {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val io: ScheduledExecutorService? =
        Executors.newSingleThreadScheduledExecutor(
            ThreadFactory { r: Runnable? ->
                val t = Thread(r, "OneUiGridSave")
                t.setDaemon(true)
                t
            }
        )
    private val bridge: DiyGridBridge
    private var busy: Boolean = false

    init {
        this.bridge = bridge
    }

    fun accept(context: Context, request: Bundle?) {
        if (request == null || request.isEmpty()) return
        val token = request.getString("token", "")
        if (busy) {
            GridSaveCoordinator.reply(context, token, "error", null, "busy")
            return
        }
        busy = true
        val prepared: DiyGridBridge.PreparedSave?
        try {
            prepared = bridge.prepareSave()
        } catch (error: Throwable) {
            finish(context, token, null, "unavailable", error)
            return
        }
        val plan = prepared!!.plan
        val save: Boolean = "save" == request.getString("action")
        if (save && plan!!.fingerprint() != request.getString("expected")) {
            finish(context, token, null, "changed", null)
            return
        }
        io!!.execute(
            Runnable {
                try {
                    if (!GridSaveCoordinator.databaseMatches(context, plan!!, false)) {
                        main.post(Runnable { finish(context, token, null, "changed", null) })
                        return@Runnable
                    }
                    main.post(
                        Runnable {
                            val age: Long =
                                SystemClock.elapsedRealtime() - request.getLong("time", 0)
                            if (age < 0 || age > GridSaveStore.MAX_AGE - 4000) {
                                finish(context, token, null, "timeout", null)
                                return@Runnable
                            }
                            if (!save) {
                                busy = false
                                GridSaveCoordinator.reply(context, token, "preview", plan, "")
                                return@Runnable
                            }
                            var mutationStarted: Boolean = false
                            try {
                                val current = bridge.prepareSave()
                                if (
                                    current!!.owner !== prepared!!.owner ||
                                        !current!!.plan!!.fingerprint().equals(plan!!.fingerprint())
                                ) {
                                    finish(context, token, null, "changed", null)
                                    return@Runnable
                                }
                                mutationStarted = true
                                bridge.applySave(current!!, false)
                                verify(context, token, current!!, 0)
                            } catch (error: Throwable) {
                                if (mutationStarted) {
                                    try {
                                        bridge.applySave(prepared!!, true)
                                    } catch (rollback: Throwable) {
                                        HookEntry.log("GRID_SAVE_ROLLBACK_ERROR " + rollback)
                                    }
                                }
                                finish(context, token, null, "failed", error)
                            }
                        }
                    )
                } catch (error: Throwable) {
                    main.post(Runnable { finish(context, token, null, "unavailable", error) })
                }
            }
        )
    }

    private fun verify(
        context: Context,
        token: String?,
        prepared: DiyGridBridge.PreparedSave,
        attempt: Int,
    ) {
        val delays = longArrayOf(100, 300, 800, 1600)
        io!!.schedule(
            Runnable {
                var saved: Boolean = false
                var failure: Throwable? = null
                try {
                    saved = GridSaveCoordinator.databaseMatches(context, prepared.plan!!, true)
                } catch (error: Throwable) {
                    failure = error
                }
                val confirmed: Boolean = saved
                val error: Throwable? = failure
                main.post(
                    Runnable {
                        if (confirmed) {
                            busy = false
                            GridSaveCoordinator.reply(context, token, "saved", prepared.plan, "")
                        } else if (attempt + 1 < delays.size)
                            verify(context, token, prepared, attempt + 1)
                        else finish(context, token, null, "failed", error)
                    }
                )
            },
            delays[attempt],
            TimeUnit.MILLISECONDS,
        )
    }

    private fun finish(
        context: Context,
        token: String?,
        plan: GridSavePlan?,
        reason: String?,
        error: Throwable?,
    ) {
        busy = false
        HookEntry.log("GRID_SAVE_ERROR " + reason + (if (error == null) "" else " " + error))
        GridSaveCoordinator.reply(context, token, "error", plan, reason)
    }

    companion object {
        @JvmStatic
        private fun databaseMatches(context: Context, plan: GridSavePlan, saved: Boolean): Boolean {
            val ids =
                plan.entries!!.stream().map({ e -> e!!.id.toString() }).toArray { size ->
                    arrayOfNulls<String>(size)
                }
            val placeholders = Collections.nCopies(ids.size, "?").joinToString(",")
            val expected = HashMap<Int?, GridSavePlan.Entry?>()
            for (entry in plan.entries!!) expected.put(entry!!.id, entry)
            SQLiteDatabase.openDatabase(
                    context.getDatabasePath("OneUI.db").getPath(),
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                )
                .use { db ->
                    db.rawQuery(
                            ("SELECT id,type,container_id,position_x,position_y FROM item WHERE id IN (" +
                                placeholders +
                                ")"),
                            ids,
                        )
                        .use { cursor ->
                            var count: Int = 0
                            while (cursor.moveToNext()) {
                                val entry = expected.get(cursor.getInt(0))
                                if (
                                    entry == null ||
                                        !java.util.Set.of<String?>(
                                                "app",
                                                "folder",
                                                "shortcut",
                                                "deepshortcut",
                                            )
                                            .contains(cursor.getString(1)) ||
                                        cursor.getInt(2) != entry.page ||
                                        cursor.getInt(3) != (if (saved) entry.x else entry.oldX) ||
                                        cursor.getInt(4) != (if (saved) entry.y else entry.oldY)
                                )
                                    return false
                                count++
                            }
                            return count == expected.size
                        }
                }
        }

        @JvmStatic
        private fun reply(
            context: Context,
            token: String?,
            state: String?,
            plan: GridSavePlan?,
            error: String?,
        ) {
            try {
                val result = Bundle()
                result.putString("token", token)
                result.putString("state", state)
                result.putString("error", error)
                if (plan != null) {
                    result.putInt("count", plan.changed())
                    result.putInt("pages", plan.pages())
                    result.putString("hash", plan.fingerprint())
                }
                context.getContentResolver().call(ConfigProvider.URI!!, "grid_result", null, result)
                HookEntry.log(
                    "GRID_SAVE " + state + (if (plan == null) "" else " count=" + plan.changed())
                )
            } catch (failure: Throwable) {
                HookEntry.log("GRID_SAVE_RESULT_UNAVAILABLE " + failure)
            }
        }
    }
}
