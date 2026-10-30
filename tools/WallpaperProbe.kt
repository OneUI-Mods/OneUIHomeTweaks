import android.app.WallpaperColors
import android.app.WallpaperManager
import android.app.WallpaperManager.OnColorsChangedListener
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Bitmap.Config
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import java.util.Locale
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min

/* Read-only probe. Run with app_process, never modifies settings or wallpaper. */
object WallpaperProbe {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        val context: Context? = at.getMethod("getSystemContext").invoke(thread) as Context?
        val wm = WallpaperManager.getInstance(context)
        println("uid=" + Process.myUid() + " system-context user=0")
        for (name in
            arrayOf<String>(
                "android.app.WallpaperManager",
                "android.app.SemWallpaperColors",
                "android.app.SemWallpaperColors\$Item",
                "com.samsung.android.wallpaper.utils.WhichChecker",
            )) {
            try {
                val c = Class.forName(name)
                for (m in c.getDeclaredMethods()) {
                    if (
                        name.contains("WhichChecker") ||
                            m.getName()
                                .lowercase(Locale.getDefault())
                                .matches(".*(color|dim|which|mode|local).*".toRegex())
                    )
                        println("METHOD " + m)
                }
                for (f in c.getDeclaredFields()) {
                    if (
                        Modifier.isStatic(f.getModifiers()) &&
                            Modifier.isPublic(f.getModifiers()) &&
                            (f.getName().contains("HOMESCREEN") ||
                                f.getName().contains("FONT_COLOR"))
                    )
                        println("FIELD " + f.getName() + "=" + f.get(null))
                }
            } catch (e: Throwable) {
                println("ERROR " + name + " " + e)
            }
        }
        try {
            println("INFO " + wm.getWallpaperInfo())
        } catch (e: Throwable) {
            println(e)
        }
        if (args.size > 0 && "--thumbnail" == args[0]) {
            try {
                val d = wm.getWallpaperInfo().loadThumbnail(context!!.getPackageManager())
                if (d != null) {
                    val w = max(1, min(1024, d.getIntrinsicWidth()))
                    val h = max(1, min(2048, d.getIntrinsicHeight()))
                    val b = Bitmap.createBitmap(w, h, Config.ARGB_8888)
                    d.setBounds(0, 0, w, h)
                    d.draw(Canvas(b))
                    FileOutputStream("/data/local/tmp/oneui-static-thumbnail.png").use { out ->
                        b.compress(CompressFormat.PNG, 100, out)
                    }
                    b.recycle()
                    println("THUMBNAIL saved from WallpaperInfo.loadThumbnail()")
                }
            } catch (e: Throwable) {
                println("THUMBNAIL ERROR " + e)
            }
        }
        for (which in intArrayOf(1, 2)) {
            try {
                WallpaperProbe.print("GET which=" + which, wm.getWallpaperColors(which))
            } catch (e: Throwable) {
                println("GET ERROR " + which + " " + e)
            }
        }
        for (which in intArrayOf(5, 6, 9, 17)) {
            try {
                println(
                    ("SEM which=" +
                        which +
                        " " +
                        wm.javaClass
                            .getMethod("semGetWallpaperColors", Int::class.javaPrimitiveType)
                            .invoke(wm, which))
                )
            } catch (e: Throwable) {
                println("SEM ERROR " + which + " " + e)
            }
        }
        // Read Settings via adb shell separately: app_process has no registered app provider token.
        val handler = Handler(Looper.getMainLooper())
        val listener = OnColorsChangedListener { colors: WallpaperColors?, which: Int ->
            WallpaperProbe.print("EVENT which=" + which, colors)
        }
        try {
            wm.addOnColorsChangedListener(listener, handler)
        } catch (e: Throwable) {
            println("LISTENER ERROR " + e)
        }
        handler.postDelayed(
            Runnable {
                wm.removeOnColorsChangedListener(listener)
                println("DONE")
                System.exit(0)
            },
            15000,
        )
        Looper.loop()
    }

    @JvmStatic
    fun print(label: String?, c: WallpaperColors?) {
        println(
            (label +
                " colors=" +
                c +
                (if (c == null) ""
                else
                    (" hints=" +
                        c.getColorHints() +
                        " darkText=" +
                        ((c.getColorHints() and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0) +
                        " darkTheme=" +
                        ((c.getColorHints() and WallpaperColors.HINT_SUPPORTS_DARK_THEME) != 0))))
        )
    }
}
