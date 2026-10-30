package dev.ryuya.oneuihometweaks

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings.Global
import java.util.UUID
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized
import kotlin.math.max
import kotlin.math.min

/* The app owns the command queue; only the Launcher may consume it or submit a result. */
object GridSaveStore {
    const val MAX_AGE: Long = 20000

    @JvmStatic
    fun boot(c: Context): Int {
        return Global.getInt(c.getContentResolver(), Global.BOOT_COUNT, -1)
    }

    @JvmStatic
    fun prefs(c: Context): SharedPreferences? {
        return c.getSharedPreferences("grid_save", Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun enqueue(c: Context, action: String?, expected: String?): String? {
        val token = UUID.randomUUID().toString()
        check(
            GridSaveStore.prefs(c)!!.edit()
                .clear()
                .putString("token", token)
                .putString("action", action)
                .putString("expected", expected)
                .putLong("time", SystemClock.elapsedRealtime())
                .putInt("boot", GridSaveStore.boot(c))
                .putBoolean("taken", false)
                .commit()
        ) {
            "Command persistence unavailable"
        }
        c.getContentResolver().notifyChange(ConfigProvider.URI!!, null)
        return token
    }

    @JvmStatic
    fun valid(c: Context): Boolean {
        val p = GridSaveStore.prefs(c)
        val age: Long = SystemClock.elapsedRealtime() - p!!.getLong("time", -GridSaveStore.MAX_AGE)
        return GridSaveStore.boot(c) >= 0 &&
            GridSaveStore.boot(c) == p!!.getInt("boot", -1) &&
            age >= 0 &&
            age < GridSaveStore.MAX_AGE
    }

    @JvmStatic
    @Synchronized
    fun take(c: Context): Bundle? {
        val p = GridSaveStore.prefs(c)
        if (p!!.getBoolean("taken", true) || !GridSaveStore.valid(c)) return Bundle.EMPTY
        val action = p!!.getString("action", "")
        if (action != "preview" && action != "save") return Bundle.EMPTY
        val b = Bundle()
        b.putString("token", p!!.getString("token", ""))
        b.putString("action", action)
        b.putString("expected", p!!.getString("expected", ""))
        b.putLong("time", p!!.getLong("time", 0))
        if (!p!!.edit().putBoolean("taken", true).commit()) return Bundle.EMPTY
        return b
    }

    @JvmStatic
    @Synchronized
    fun result(c: Context, b: Bundle?): Bundle? {
        val p = GridSaveStore.prefs(c)
        if (
            b == null ||
                !GridSaveStore.valid(c) ||
                !p!!.getBoolean("taken", false) ||
                (p!!.getString("token", "") != b.getString("token"))
        )
            throw SecurityException("Expired/unrelated grid result")
        val state = b.getString("state", "error")
        val hash = b.getString("hash", "")
        require(
            !(!java.util.Set.of<String?>("preview", "saved", "error").contains(state) ||
                (!hash.isEmpty() && !hash.matches("[0-9a-f]{64}".toRegex())))
        ) {
            "Invalid grid result"
        }
        p!!
            .edit()
            .putString("state", state)
            .putString("hash", hash)
            .putString("error", b.getString("error", "failed"))
            .putInt("count", max(0, min(500, b.getInt("count"))))
            .putInt("pages", max(0, min(500, b.getInt("pages"))))
            .commit()
        c.getContentResolver().notifyChange(ConfigProvider.STATUS_URI!!, null)
        return Bundle.EMPTY
    }
}
