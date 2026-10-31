package dev.ryuya.oneuihometweaks

import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Resources.NotFoundException
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.ViewTreeObserver.OnScrollChangedListener
import android.view.ViewTreeObserver.OnWindowFocusChangeListener
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Collections
import java.util.WeakHashMap
import kotlin.jvm.JvmField
import kotlin.math.ceil
import kotlin.math.log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/* Samples only the live wallpaper layer, then applies contrast separately to each home label. */
class HomeLabelBridge {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val saved: MutableMap<TextView?, HomeLabelBridge.Saved?> =
        WeakHashMap<TextView?, HomeLabelBridge.Saved?>()
    private var home: WeakReference<Activity?> = WeakReference<Activity?>(null)
    private var worker: Handler? = null
    private var iconClass: Class<*>? = null
    private var finderClass: Class<*>? = null
    private var getColor: Method? = null
    private var setColor: Method? = null
    private var screenshot: Method? = null
    private var windowService: Any? = null
    private var enabled: Boolean = false
    private var failed: Boolean = false
    private var busy: Boolean = false
    private var ownTextColor: Boolean = false
    private var geometryBlocked: Boolean = false
    private var initialReveals: Int = 0
    private var initialFallbacks: Int = 0
    private var generation: Long = 0
    private var state: String = "unavailable"
    private var lastLog: String = ""
    private var changed: Runnable? = null
    private val sample: Runnable = Runnable { this.sample() }
    private val expirePending: Runnable = Runnable { this.expirePending() }
    private val layout: OnGlobalLayoutListener = OnGlobalLayoutListener { this.invalidate() }
    private val scroll: OnScrollChangedListener = OnScrollChangedListener { this.invalidate() }
    private val focus: OnWindowFocusChangeListener =
        OnWindowFocusChangeListener { focused: Boolean ->
            if (focused) invalidate() else cancel()
        }

    @Throws(Exception::class)
    fun install(context: Context, loader: ClassLoader?, background: Handler?, report: Runnable?) {
        if (
            context.checkSelfPermission("android.permission.READ_FRAME_BUFFER") !=
                PackageManager.PERMISSION_GRANTED
        )
            throw SecurityException("No existing wallpaper capture permission")
        val icon = Class.forName("com.honeyspace.ui.common.iconview.IconViewImpl", false, loader)
        check(TextView::class.java.isAssignableFrom(icon)) { "Icon is not TextView" }
        getColor = icon.getMethod("getCustomLabelColor")
        setColor = icon.getMethod("setCustomLabelColor", Int::class.javaObjectType)
        check(
            !(getColor!!.getReturnType() != Int::class.javaObjectType ||
                setColor!!.getReturnType() != Void.TYPE)
        ) {
            "Custom label contract changed"
        }
        windowService =
            Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService")
                .invoke(null)
        screenshot = Class.forName("android.view.IWindowManager").getMethod("screenshotWallpaper")
        check(screenshot!!.getReturnType() == Bitmap::class.java) {
            "Wallpaper capture contract changed"
        }
        iconClass = icon
        worker = background
        changed = report
        try {
            finderClass =
                Class.forName(
                    "com.honeyspace.ui.common.pageindicator.StrokeTextView",
                    false,
                    loader,
                )
            check(TextView::class.java.isAssignableFrom(finderClass)) { "Finder type changed" }
            val indicator =
                Class.forName(
                    "com.honeyspace.ui.common.pageindicator.PageIndicatorView",
                    false,
                    loader,
                )
            val binding =
                Class.forName(
                    "com.honeyspace.ui.common.databinding.IndicatorSearchLayoutBinding",
                    false,
                    loader,
                )
            val updateFinder = indicator.getDeclaredMethod("updateFinderButton", binding)
            check(
                !(updateFinder.getReturnType() != Void.TYPE || !ModernHook.deoptimize(updateFinder))
            ) {
                "Finder color caller cannot be verified/deoptimized"
            }
            // Actual PageIndicatorView.updateFinderButton calls this exact overload. Only tracked
            // Finder
            // instances are intercepted; preserve Samsung's newer native request for later
            // restoration.
            ModernHook.hookMethod(
                TextView::class.java.getMethod("setTextColor", Int::class.javaPrimitiveType),
                object : ModernHook.Callback() {
                    override fun beforeHookedMethod(p: ModernHook.Param) {
                        if (!enabled || ownTextColor || Looper.myLooper() != Looper.getMainLooper())
                            return
                        val original = saved.get(p.thisObject)
                        if (original == null || !original.finder || original.applied == null) return
                        original.textColors = ColorStateList.valueOf(p.args[0] as Int)
                        p.args[0] = original.applied
                    }
                },
            )
        } catch (t: Throwable) {
            finderClass = null
            HookEntry.log("LOCAL_FINDER_UNSUPPORTED " + t)
        }
        // Native style updates can replace the displayed text color without changing
        // customLabelColor.
        val style = Class.forName("com.honeyspace.sdk.source.entity.ItemStyle", false, loader)
        ModernHook.hookMethod(
            icon.getMethod("setItemStyle", style),
            object : ModernHook.Callback() {
                override fun afterHookedMethod(p: ModernHook.Param) {
                    if (!p.hasThrowable() && enabled) invalidate()
                }
            },
        )
        ModernHook.hookMethod(
            icon.getDeclaredMethod("onDraw", Canvas::class.java),
            object : ModernHook.Callback() {
                override fun beforeHookedMethod(p: ModernHook.Param) {
                    if (
                        !enabled ||
                            failed ||
                            geometryBlocked ||
                            Looper.myLooper() != Looper.getMainLooper()
                    )
                        return
                    val view: TextView? = p.thisObject as TextView?
                    val activity = home.get()
                    if (
                        !usable(activity) ||
                            view!!.getRootView() !== activity!!.getWindow().getDecorView() ||
                            view!!.getRootView().getHeight() <= view!!.getRootView().getWidth() ||
                            !homeIcon(view) ||
                            view!!.getText().length == 0 ||
                            saved.containsKey(view)
                    )
                        return
                    val visible = Rect()
                    if (!view!!.getGlobalVisibleRect(visible)) return
                    try {
                        val original = remember(view!!)
                        original!!.original = getColor!!.invoke(view) as Int?
                        original!!.pending = true
                        original!!.applied = Color.TRANSPARENT
                        // Only the label is delayed; icon graphics stay visible. Never paint a
                        // native white
                        // label on a previously unseen page before the first local contrast result
                        // is ready.
                        setColor!!.invoke(view, Color.TRANSPARENT)
                        invalidate()
                    } catch (t: Throwable) {
                        disable(t)
                    }
                }
            },
        )
        state = "off"
        HookEntry.log("LOCAL_LABEL_READY wallpaper-only screenshot; native per-icon color API")
    }

    fun setHome(activity: Activity) {
        if ("com.sec.android.app.launcher.Launcher" != activity.javaClass.getName()) return
        val old = home.get()
        if (old !== activity) {
            if (old != null) detach(old)
            home = WeakReference<Activity?>(activity)
            val tree = activity.getWindow().getDecorView().getViewTreeObserver()
            tree.addOnGlobalLayoutListener(layout)
            tree.addOnScrollChangedListener(scroll)
            tree.addOnWindowFocusChangeListener(focus)
        }
        invalidate()
    }

    fun paused(activity: Activity?) {
        if (home.get() === activity) cancel()
    }

    fun destroyed(activity: Activity) {
        if (home.get() !== activity) return
        detach(activity)
        home.clear()
        cancel()
        restore()
    }

    private fun detach(activity: Activity) {
        val tree = activity.getWindow().getDecorView().getViewTreeObserver()
        if (!tree.isAlive()) return
        tree.removeOnGlobalLayoutListener(layout)
        tree.removeOnScrollChangedListener(scroll)
        tree.removeOnWindowFocusChangeListener(focus)
    }

    fun setEnabled(value: Boolean) {
        var value: Boolean = value
        value = value and (iconClass != null && !failed)
        if (value == enabled) return
        enabled = value
        cancel()
        if (value) {
            update("waiting")
            invalidate()
        } else {
            restore()
            if (!failed) update("off")
        }
    }

    fun diagnostic(): String {
        return state
    }

    /* A finite burst after events catches page/image fades without a permanent polling loop. */
    fun invalidate() {
        if (!enabled || failed) return
        cancel()
        main.postDelayed(sample, 180)
        main.postDelayed(sample, 400)
        main.postDelayed(sample, 750)
        main.postDelayed(sample, 1200)
        main.postDelayed(sample, 1800)
        main.postDelayed(sample, 2600)
        main.postDelayed(expirePending, 3000)
    }

    private fun cancel() {
        generation++
        main.removeCallbacks(sample)
        main.removeCallbacks(expirePending)
    }

    private fun remember(view: TextView): HomeLabelBridge.Saved? {
        var original = saved.get(view)
        if (original != null) return original
        original = HomeLabelBridge.Saved()
        original.attach =
            object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (!homeIcon(v) && !finder(v)) restore(v as TextView)
                }

                override fun onViewDetachedFromWindow(v: View) {}
            }
        saved.put(view, original)
        view.addOnAttachStateChangeListener(original.attach)
        return original
    }

    private fun expirePending() {
        if (!usable(home.get())) return
        var count: Int = 0
        try {
            for (view in ArrayList<TextView?>(saved.keys)) {
                val original = saved.get(view)
                if (original == null || !original.pending) continue
                if (getColor!!.invoke(view) == original.applied)
                    setColor!!.invoke(view, original.original)
                original.applied = null
                original.pending = false
                // Retain this entry to prevent hide/retry loops when capture never becomes
                // available.
                count++
            }
            initialFallbacks += count
            if (count > 0) HookEntry.log("LOCAL_LABEL_INITIAL_FALLBACK count=" + count)
        } catch (t: Throwable) {
            disable(t)
        }
    }

    private fun reveal(view: TextView, original: HomeLabelBridge.Saved, color: Int) {
        original.pending = false
        initialReveals++
        if (original.animator != null) original!!.animator!!.cancel()
        val fade = ValueAnimator.ofInt(0, 255)
        original.animator = fade
        fade.setDuration(120)
        setTextColor(view, color and 0x00ffffff)
        fade.addUpdateListener(
            AnimatorUpdateListener { animation: ValueAnimator? ->
                if (enabled && saved.get(view) == original)
                    setTextColor(
                        view,
                        (animation!!.getAnimatedValue() as Int shl 24) or (color and 0x00ffffff),
                    )
            }
        )
        fade.start()
    }

    private fun usable(activity: Activity?): Boolean {
        if (
            !enabled ||
                failed ||
                activity == null ||
                activity.isDestroyed() ||
                !activity.hasWindowFocus() ||
                activity.isInMultiWindowMode() ||
                activity.isInPictureInPictureMode()
        )
            return false
        val root = activity.getWindow().getDecorView()
        val keyguard = activity.getSystemService<KeyguardManager?>(KeyguardManager::class.java)
        return root.isAttachedToWindow() &&
            root.isShown() &&
            root.getDisplay() != null &&
            root.getDisplay().getDisplayId() == 0 &&
            keyguard != null &&
            !keyguard.isKeyguardLocked()
    }

    private fun sample() {
        val activity = home.get()
        if (busy || !usable(activity)) return
        try {
            val screen = activity!!.getWindowManager().getMaximumWindowMetrics().getBounds()
            if (
                screen.left != 0 ||
                    screen.top != 0 ||
                    screen.width() <= 0 ||
                    screen.height() <= screen.width()
            ) {
                restore()
                update("geometry-skipped")
                return
            }
            val targets = ArrayList<HomeLabelBridge.Target?>()
            collect(activity!!.getWindow().getDecorView(), screen, targets)
            // Offscreen pages retain their last color. Restoring native white while sliding a page
            // out
            // produced a visible white flash when it came back. Restore only on reparenting or
            // disable.
            for (view in ArrayList<TextView?>(saved.keys)) if (
                view!!.getParent() != null && !homeIcon(view) && !finder(view)
            )
                restore(view!!)
            if (targets.isEmpty()) {
                update("waiting")
                return
            }
            val epoch: Long = generation
            busy = true
            worker!!.post(Runnable { capture(targets, screen, epoch) })
        } catch (t: Throwable) {
            disable(t)
        }
    }

    @Throws(Exception::class)
    private fun collect(view: View, screen: Rect?, targets: MutableList<HomeLabelBridge.Target?>) {
        if (!view.isShown() || view.getAlpha() < 0.99f) return
        val finder = finder(view)
        if ((iconClass!!.isInstance(view) && homeIcon(view)) || finder) {
            val text: TextView? = view as TextView?
            val textLayout = text!!.getLayout()
            if (
                text!!.getText().length == 0 || textLayout == null || textLayout.getLineCount() == 0
            )
                return
            // TextView total padding includes the compound drawable above Samsung's app label.
            val matrix = Matrix()
            view.transformMatrixToGlobal(matrix)
            val regions = ArrayList<RectF?>()
            val visible = Rect()
            if (!view.getGlobalVisibleRect(visible)) return
            for (i in 0 until (if (finder) 1 else textLayout.getLineCount())) {
                val line: RectF?
                if (finder) {
                    // StrokeTextView draws at compoundPaddingLeft/getBaseline, not Layout's padded
                    // line box.
                    // Its fixed-height view is shorter than the font line box on this Samsung font.
                    if (view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) return
                    val ink = Rect()
                    val title = text!!.getText().toString()
                    text!!.getPaint().getTextBounds(title, 0, title.length, ink)
                    line = RectF(ink)
                    line.offset(
                        text!!.getCompoundPaddingLeft().toFloat(),
                        text!!.getBaseline().toFloat(),
                    )
                } else {
                    line =
                        RectF(
                            textLayout.getLineLeft(i),
                            textLayout.getLineTop(i).toFloat(),
                            textLayout.getLineRight(i),
                            textLayout.getLineBottom(i).toFloat(),
                        )
                    line.offset(
                        (text!!.getTotalPaddingLeft() - text!!.getScrollX()).toFloat(),
                        (text!!.getTotalPaddingTop() - text!!.getScrollY()).toFloat(),
                    )
                }
                matrix.mapRect(line)
                val clipped = RectF(line)
                if (
                    !clipped.intersect(RectF(visible)) ||
                        !clipped.intersect(RectF(screen)) ||
                        clipped.width() < line.width() * 0.95f ||
                        clipped.height() < line.height() * 0.95f
                )
                    return
                val margin = text!!.getResources().getDisplayMetrics().density
                line.inset(-2 * margin, -margin)
                if (line.intersect(RectF(screen)) && line.width() > 1 && line.height() > 1)
                    regions.add(line)
            }
            if (!regions.isEmpty()) {
                val prior = saved.get(text)
                val target =
                    HomeLabelBridge.Target(
                        text,
                        regions,
                        if (prior == null) null else prior.applied!!,
                    )
                target.finder = finder
                if (finder) finderBackground(text, target)
                targets.add(target)
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.getChildCount()) collect(view.getChildAt(i), screen, targets)
        }
    }

    private fun finder(view: View): Boolean {
        if (
            finderClass == null ||
                !(finderClass!!.isInstance(view) ||
                    (view is TextView && finderClass!!.getName() == view.javaClass.getName())) ||
                view.getId() == View.NO_ID
        )
            return false
        try {
            if ("finderTextView" != view.getResources().getResourceEntryName(view.getId()))
                return false
            var p = view.getParent()
            while (p is View) {
                var type: Class<*>? = p.javaClass
                while (type != null) {
                    if (
                        "com.honeyspace.ui.common.pageindicator.PageIndicatorView" == type.getName()
                    )
                        return true
                    type = type.getSuperclass()
                }
                p = p.getParent()
            }
        } catch (ignored: NotFoundException) {}
        return false
    }

    /* Draw only the Finder pill's backgrounds, never the text or child icons, over the wallpaper. */
    private fun finderBackground(view: TextView?, target: HomeLabelBridge.Target) {
        val union = RectF(target.regions.get(0))
        for (rect in target.regions) union.union(rect!!)
        val bounds = Rect()
        union.roundOut(bounds)
        val overlay = Bitmap.createBitmap(bounds.width(), bounds.height(), Config.ARGB_8888)
        try {
            val chain = ArrayList<View?>()
            var v: View? = view
            while (v != null) {
                chain.add(v)
                if ("indicator_search_icon_layout" == resourceName(v)) break
                v = run {
                    val parent = v.getParent() as? View
                    if (parent != null) parent else null
                }
            }
            Collections.reverse(chain)
            val canvas = Canvas(overlay)
            for (v in chain) {
                val background = v!!.getBackground()
                if (background == null) continue
                val checkpoint = canvas.save()
                canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
                val matrix = Matrix()
                v!!.transformMatrixToGlobal(matrix)
                canvas.concat(matrix)
                background.draw(canvas)
                canvas.restoreToCount(checkpoint)
            }
            target.overlay = IntArray(bounds.width() * bounds.height())
            overlay.getPixels(
                target.overlay!!,
                0,
                bounds.width(),
                0,
                0,
                bounds.width(),
                bounds.height(),
            )
            target.overlayBounds = bounds
        } finally {
            overlay.recycle()
        }
    }

    private fun resourceName(view: View): String? {
        try {
            return view.getResources().getResourceEntryName(view.getId())
        } catch (ignored: NotFoundException) {
            return ""
        }
    }

    private fun setTextColor(view: TextView, color: Int) {
        ownTextColor = true
        try {
            view.setTextColor(color)
        } finally {
            ownTextColor = false
        }
    }

    private fun homeIcon(view: View): Boolean {
        var workspace: Boolean = false
        var parent = view.getParent()
        while (parent is View) {
            val name = parent.javaClass.getName()
            if (
                name.contains(".applist.") ||
                    name.contains(".verticalapplist.") ||
                    name.contains(".folder.")
            )
                return false
            if (
                name.startsWith("com.honeyspace.ui.honeypots.workspace.presentation.") ||
                    name.startsWith("com.honeyspace.ui.honeypots.freegrid.presentation.") ||
                    name.startsWith("com.honeyspace.ui.honeypots.hotseat.presentation.")
            )
                workspace = true
            parent = parent.getParent()
        }
        return workspace
    }

    private fun capture(targets: MutableList<HomeLabelBridge.Target?>, screen: Rect, epoch: Long) {
        var raw: Bitmap? = null
        var bitmap: Bitmap? = null
        var failure: Throwable? = null
        var unavailable: String? = null
        try {
            raw = screenshot!!.invoke(windowService) as Bitmap?
            if (raw == null) unavailable = "waiting"
            else if (raw.getWidth() != screen.width() || raw.getHeight() != screen.height())
                unavailable =
                    "geometry-skipped" // Do not guess crop/offset coordinates of oversized
            // surfaces.
            else {
                val ratio = min(1f, 1024f / raw.getHeight())
                bitmap =
                    Bitmap.createBitmap(
                        max(1, Math.round(raw.getWidth() * ratio)),
                        max(1, Math.round(raw.getHeight() * ratio)),
                        Config.ARGB_8888,
                    )
                Canvas(bitmap)
                    .drawBitmap(
                        raw,
                        null,
                        Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()),
                        Paint(Paint.FILTER_BITMAP_FLAG),
                    )
                for (target in targets) {
                    val pixels = ArrayList<Int?>()
                    for (region in target!!.regions) {
                        val left =
                            max(0, (region!!.left * bitmap.getWidth() / screen.width()).toInt())
                        val right =
                            min(
                                bitmap.getWidth(),
                                (ceil(region!!.right * bitmap.getWidth() / screen.width())).toInt(),
                            )
                        val top = max(0, (region!!.top * ratio).toInt())
                        val bottom =
                            min(bitmap.getHeight(), (ceil(region!!.bottom * ratio)).toInt())
                        var y: Int = top
                        while (y < bottom) {
                            var x: Int = left
                            while (x < right) {
                                var color = bitmap.getPixel(x, y)
                                if (target!!.overlay != null) {
                                    val bounds = target!!.overlayBounds
                                    val ox =
                                        max(
                                            0,
                                            min(
                                                bounds!!.width() - 1,
                                                Math.round(
                                                    (x + 0.5f) * screen.width() / bitmap.getWidth()
                                                ) - bounds!!.left,
                                            ),
                                        )
                                    val oy =
                                        max(
                                            0,
                                            min(
                                                bounds!!.height() - 1,
                                                Math.round((y + 0.5f) / ratio) - bounds!!.top,
                                            ),
                                        )
                                    color =
                                        ColorUtils.compositeColors(
                                            target!!.overlay!![oy * bounds!!.width() + ox],
                                            color,
                                        )
                                }
                                pixels.add(color)
                                x += max(1, (right - left) / 24)
                            }
                            y += max(1, (bottom - top) / 8)
                        }
                    }
                    target!!.pixels =
                        pixels.stream().mapToInt { obj: Int? -> obj!!.toInt() }.toArray()
                    target!!.color = LocalContrast.choose(target!!.pixels!!, target!!.previous)
                }
            }
        } catch (t: Throwable) {
            failure = t
        } finally {
            if (bitmap != null) bitmap.recycle()
            if (raw != null) raw.recycle()
        }
        val error: Throwable? = failure
        val skip: String? = unavailable
        main.post(
            Runnable {
                busy = false
                if (epoch != generation || !usable(home.get())) return@Runnable
                if (error != null) {
                    disable(error)
                    return@Runnable
                }
                if (skip != null) {
                    // A temporarily hidden wallpaper during a page transition is not a
                    // compatibility error.
                    if ("waiting" != skip) {
                        geometryBlocked = true
                        restore()
                    }
                    update(skip)
                    return@Runnable
                }
                try {
                    geometryBlocked = false
                    var black: Int = 0
                    var white: Int = 0
                    var finderState: String? =
                        if (finderClass == null) "unavailable" else "not-visible"
                    for (target in targets) {
                        val view = target!!.view.get()
                        if (
                            view == null ||
                                !view.isAttachedToWindow() ||
                                !(homeIcon(view) || finder(view))
                        )
                            continue
                        if (target!!.color == null) {
                            restore(view)
                            remember(
                                view
                            ) // Unknown pixels use native text, without repeatedly hiding it.
                            continue
                        }
                        val original = remember(view)
                        val settled = LocalContrast.settled(original!!.pixels, target!!.pixels)
                        original!!.pixels = target!!.pixels
                        // Publish a changed color only after two matching, settled local
                        // observations.
                        if (settled && original!!.candidate == target!!.color) {
                            original!!.finder = target!!.finder
                            if (target!!.finder) {
                                if (
                                    original!!.applied == null ||
                                        view.getCurrentTextColor() != original!!.applied
                                )
                                    original!!.textColors = view.getTextColors()
                                setTextColor(view, target!!.color!!)
                                tintSearchIcon(view, original!!, target!!.color!!)
                            } else {
                                val current: Int? = getColor!!.invoke(view) as Int?
                                if (original!!.applied == null || current != original!!.applied)
                                    original!!.original = current
                                if (current != target!!.color)
                                    setColor!!.invoke(view, target!!.color)
                                else if (view.getCurrentTextColor() != target!!.color)
                                    setTextColor(view, target!!.color!!)
                            }
                            original!!.applied = target!!.color
                            if (original!!.pending) reveal(view, original!!, target!!.color!!)
                        }
                        original!!.candidate = target!!.color
                        if (target!!.finder) finderState = "waiting"
                        if (original!!.applied != null && !original!!.pending) {
                            if (target!!.finder)
                                finderState =
                                    ((if (original!!.applied == LocalContrast.BLACK) "black"
                                    else "white") +
                                        (if (original!!.appliedSearchTint != null) "+icon" else ""))
                            else if (original!!.applied == LocalContrast.BLACK) black++ else white++
                        }
                    }
                    update(
                        ((if (black + white > 0) "active" else "waiting") +
                            " black=" +
                            black +
                            " white=" +
                            white +
                            " finder=" +
                            finderState +
                            " initial=" +
                            initialReveals +
                            " fallback=" +
                            initialFallbacks)
                    )
                } catch (t: Throwable) {
                    disable(t)
                }
            }
        )
    }

    private fun tintSearchIcon(text: TextView, original: HomeLabelBridge.Saved, color: Int) {
        val parent = text.getParent() as? ViewGroup
        if (parent == null) return
        for (i in 0 until parent.getChildCount()) {
            val child = parent.getChildAt(i)
            if (child !is ImageView || "searchIcon" != resourceName(child)) continue
            val previous: ImageView? =
                if (original.search == null) null else original!!.search!!.get()
            if (previous !== child) {
                restoreSearchIcon(original)
                original.search = WeakReference<ImageView?>(child)
                original.searchTint = child.getImageTintList()
                original.appliedSearchTint = null
            } else if (child.getImageTintList() != original.appliedSearchTint) {
                original.searchTint = child.getImageTintList()
            }
            val next = ColorStateList.valueOf(color)
            if (child.getImageTintList() != next) child.setImageTintList(next)
            original.appliedSearchTint = next
            return
        }
    }

    private fun restoreSearchIcon(original: HomeLabelBridge.Saved) {
        val image: ImageView? = if (original.search == null) null else original!!.search!!.get()
        if (
            image != null &&
                original.appliedSearchTint != null &&
                image.getImageTintList() == original.appliedSearchTint
        )
            image.setImageTintList(original.searchTint)
        original.search = null
        original.appliedSearchTint = null
    }

    private fun restore(view: TextView) {
        val original = saved.remove(view)
        if (original == null) return
        if (original.animator != null) original!!.animator!!.cancel()
        restoreSearchIcon(original)
        if (original.attach != null) view.removeOnAttachStateChangeListener(original.attach)
        if (original.applied == null) return
        try {
            if (original.finder) {
                if (view.getCurrentTextColor() == original.applied && original.textColors != null)
                    view.setTextColor(original.textColors)
            } else if (getColor!!.invoke(view) == original.applied)
                setColor!!.invoke(view, original.original)
        } catch (t: Throwable) {
            HookEntry.log("LOCAL_LABEL_RESTORE_ERROR " + t.javaClass.getSimpleName())
        }
    }

    private fun restore() {
        for (view in ArrayList<TextView?>(saved.keys)) restore(view!!)
    }

    private fun disable(t: Throwable) {
        failed = true
        enabled = false
        cancel()
        restore()
        update("error:" + t.javaClass.getSimpleName())
        HookEntry.log("LOCAL_LABEL_DISABLED " + t)
    }

    private fun update(next: String) {
        if (next == state) return
        state = next
        if (next != lastLog) {
            HookEntry.log("LOCAL_LABEL " + next)
            lastLog = next
        }
        if (changed != null) changed!!.run()
    }

    private class Saved {
        @JvmField var original: Int? = null
        @JvmField var applied: Int? = null
        @JvmField var candidate: Int? = null
        @JvmField var textColors: ColorStateList? = null
        @JvmField var search: WeakReference<ImageView?>? = null
        @JvmField var searchTint: ColorStateList? = null
        @JvmField var appliedSearchTint: ColorStateList? = null
        @JvmField var animator: ValueAnimator? = null
        @JvmField var attach: OnAttachStateChangeListener? = null
        @JvmField var pixels: IntArray? = null
        @JvmField var finder: Boolean = false
        @JvmField var pending: Boolean = false
    }

    private class Target(view: TextView?, regions: MutableList<RectF?>, previous: Int?) {
        @JvmField val view: WeakReference<TextView?>
        @JvmField val regions: MutableList<RectF?>
        @JvmField val previous: Int?
        @JvmField var color: Int? = null
        @JvmField var pixels: IntArray? = null
        @JvmField var overlay: IntArray? = null
        @JvmField var overlayBounds: Rect? = null
        @JvmField var finder: Boolean = false

        init {
            this.view = WeakReference<TextView?>(view)
            this.regions = regions
            this.previous = previous
        }
    }
}
