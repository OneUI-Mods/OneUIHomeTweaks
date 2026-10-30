package dev.ryuya.oneuihometweaks

import android.app.Activity
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlin.math.max

/* A read-only preview precedes every explicit confirmation to persist icon positions. */
class GridSaveUi(activity: Activity) {
    private val activity: Activity
    private val button: MaterialButton
    private val main: Handler = Handler(Looper.getMainLooper())
    private var waiting: String = ""

    init {
        this.activity = activity
        button = activity.findViewById<MaterialButton?>(R.id.save_grid_positions)
        button.setOnClickListener(
            android.view.View.OnClickListener { v: View? -> request("preview", "") }
        )
        val p = GridSaveStore.prefs(activity)
        if (GridSaveStore.valid(activity) && !p!!.getBoolean("ui_done", false)) {
            waiting = (p!!.getString("token", ""))!!
            val token: String? = waiting
            main.post(Runnable { this.result() })
            main.postDelayed(
                Runnable {
                    if (token == waiting) {
                        waiting = ""
                        availability()
                        show(R.string.grid_save_unavailable)
                    }
                },
                max(
                    1L,
                    GridSaveStore.MAX_AGE -
                        (SystemClock.elapsedRealtime() - p!!.getLong("time", 0)),
                ),
            )
        }
        availability()
    }

    fun availability() {
        val p = activity.getSharedPreferences(ConfigProvider.PREFS, Activity.MODE_PRIVATE)
        button.setEnabled(
            waiting.isEmpty() &&
                p.getBoolean("enabled", true) &&
                p.getBoolean("home_enabled", true) &&
                p.getBoolean("diy_icon_grid", false)
        )
    }

    private fun request(action: String?, fingerprint: String?) {
        button.setEnabled(false)
        try {
            waiting = (GridSaveStore.enqueue(activity, action, fingerprint))!!
            val token: String? = waiting
            Snackbar.make(button, R.string.grid_save_wait, Snackbar.LENGTH_SHORT).show()
            main.postDelayed(
                Runnable {
                    if (token == waiting) {
                        waiting = ""
                        availability()
                        show(R.string.grid_save_unavailable)
                    }
                },
                GridSaveStore.MAX_AGE,
            )
        } catch (error: Throwable) {
            waiting = ""
            availability()
            show(R.string.grid_save_failed)
        }
    }

    fun result() {
        if (waiting.isEmpty() || activity.isFinishing() || activity.isDestroyed()) return
        val p = GridSaveStore.prefs(activity)
        if (waiting != p!!.getString("token", "")) return
        val state = p!!.getString("state", "")
        if (state!!.isEmpty()) return
        p!!.edit().putBoolean("ui_done", true).apply()
        waiting = ""
        availability()
        val count = p!!.getInt("count", 0)
        if ("preview" == state) {
            if (count == 0) {
                show(R.string.grid_save_nochanges)
                return
            }
            val hash = p!!.getString("hash", "")
            if (!hash!!.matches("[0-9a-f]{64}".toRegex())) {
                show(R.string.grid_save_failed)
                return
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.save_grid_positions)
                .setMessage(
                    activity.getString(R.string.grid_save_confirm, count, p!!.getInt("pages", 0))
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                    R.string.grid_save_commit,
                    android.content.DialogInterface.OnClickListener { _, w: Int ->
                        request("save", hash)
                    },
                )
                .show()
        } else if ("saved" == state) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.save_grid_positions)
                .setMessage(activity.getString(R.string.grid_save_done, count))
                .setPositiveButton(R.string.close, null)
                .show()
        } else {
            val error = p!!.getString("error", "failed")
            show(
                if ("changed" == error) R.string.grid_save_changed
                else if ("unavailable" == error || "timeout" == error)
                    R.string.grid_save_unavailable
                else R.string.grid_save_failed
            )
        }
    }

    private fun show(message: Int) {
        if (activity.isFinishing() || activity.isDestroyed()) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.save_grid_positions)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
    }
}
