package dev.ryuya.oneuihometweaks

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.os.Trace
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.ImageView
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod

/** Keeps the measured search-bar background geometry during a paint-only update in layout. */
internal object LauncherSearchBackgroundLayoutHooks {
    fun install(app: Application, loader: ClassLoader) {
        val sdk = Build.VERSION.SDK_INT
        if (sdk != 37 || !HookCompatibility.matchesClockBuild(sdk, Build.DISPLAY)) return
        try {
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            if (HookCompatibility.matchesLauncherVersion(sdk, version)) install(loader)
        } catch (failure: Exception) {
            HookEntry.log("LAUNCHER_SEARCH_BACKGROUND_LAYOUT_UNAVAILABLE ${failure.javaClass.simpleName}: ${failure.message}")
        }
    }

    private fun install(loader: ClassLoader) {
        val qsb = DexSymbols.findClass("com.honeyspace.ui.common.widget.QSBHostViewContainer", loader)
        val blur = DexSymbols.findClass("com.honeyspace.ui.common.qsb.QSBBlurBackground", loader)
        val dim = DexSymbols.findClass("com.honeyspace.ui.common.qsb.QSBDimBackground", loader)
        val background = qsb.compatibleDeclaredField("backgroundView").apply { isAccessible = true }
        val dimBackground = qsb.compatibleDeclaredField("dimBackgroundView").apply { isAccessible = true }
        val border = qsb.compatibleDeclaredMethod("updateBorder", Float::class.javaPrimitiveType)
        val onWidgetLayout = qsb.compatibleDeclaredMethod("onLayoutAppWidget")
        val placeBackground = qsb.compatibleDeclaredMethod("updateBackgroundParam", View::class.java, Rect::class.java)
        val bitmap = blur.compatibleDeclaredMethod("setImageBitmap", Bitmap::class.java)
        val color = dim.compatibleDeclaredMethod(
            "updateColor", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaObjectType,
        )
        val requestDuringLayout = Class.forName("android.view.ViewRootImpl")
            .compatibleDeclaredMethod("requestLayoutDuringLayout", View::class.java)
        val requestLayout = View::class.java.compatibleDeclaredMethod("requestLayout")
        val priorWidthSpec = View::class.java.compatibleDeclaredField("mOldWidthMeasureSpec").apply { isAccessible = true }
        val priorHeightSpec = View::class.java.compatibleDeclaredField("mOldHeightMeasureSpec").apply { isAccessible = true }
        val frame = FrameLayout::class.java
        val paddedLeft = frame.compatibleDeclaredMethod("getPaddingLeftWithForeground").apply { isAccessible = true }
        val paddedTop = frame.compatibleDeclaredMethod("getPaddingTopWithForeground").apply { isAccessible = true }
        val paddedRight = frame.compatibleDeclaredMethod("getPaddingRightWithForeground").apply { isAccessible = true }
        val paddedBottom = frame.compatibleDeclaredMethod("getPaddingBottomWithForeground").apply { isAccessible = true }
        val updateBlur = qsb.compatibleDeclaredMethod(
            "updateBlur", Point::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        check(ModernHook.deoptimize(requestLayout)) { "View may inline requestLayoutDuringLayout" }
        check(ModernHook.deoptimize(onWidgetLayout)) { "Widget callback may inline background placement" }
        check(ModernHook.deoptimize(updateBlur)) { "Blur update may inline background placement" }

        val main = Looper.getMainLooper()
        var active: SearchBackgroundLayout? = null
        var layoutHost: FrameLayout? = null
        var backgroundPlacement: SearchBackgroundPlacement? = null
        val installed = mutableListOf<ModernHook.Handle>()
        try {
            installed += ModernHook.aroundMethod(requestDuringLayout) { chain ->
                if (Looper.myLooper() !== main) return@aroundMethod chain.proceed()
                val requester = chain.args[0]
                val placement = backgroundPlacement
                if (placement != null && placement.handlesRequest(requester)) return@aroundMethod false
                val scope = active
                if (scope == null || requester !== scope.view || !scope.canKeepLayout()) return@aroundMethod chain.proceed()
                mark(scope.traceName)
                false
            }
            installed += ModernHook.aroundMethod(onWidgetLayout) { chain ->
                val host = chain.thisObject as? FrameLayout
                if (host == null || Looper.myLooper() !== main || layoutHost != null || !host.isInLayout) {
                    return@aroundMethod chain.proceed()
                }
                layoutHost = host
                try {
                    chain.proceed()
                } finally {
                    layoutHost = null
                }
            }
            installed += ModernHook.aroundMethod(placeBackground) { chain ->
                val host = layoutHost
                if (host == null || chain.thisObject !== host || Looper.myLooper() !== main || backgroundPlacement != null ||
                    !measuredExactly(priorWidthSpec.getInt(host), host.width) ||
                    !measuredExactly(priorHeightSpec.getInt(host), host.height)
                ) return@aroundMethod chain.proceed()
                val view = chain.args[0] as? ImageView ?: return@aroundMethod chain.proceed()
                if (view !== background.get(host) && view !== dimBackground.get(host)) return@aroundMethod chain.proceed()
                val bounds = chain.args[1] as? Rect ?: return@aroundMethod chain.proceed()
                val content = Rect(
                    paddedLeft.invoke(host) as Int, paddedTop.invoke(host) as Int,
                    host.width - (paddedRight.invoke(host) as Int), host.height - (paddedBottom.invoke(host) as Int),
                )
                val placement = SearchBackgroundPlacement.create(host, view, bounds.width(), bounds.height(), content)
                    ?: return@aroundMethod chain.proceed()
                backgroundPlacement = placement
                var completed = false
                try {
                    val result = chain.proceed()
                    completed = true
                    result
                } finally {
                    backgroundPlacement = null
                    if (placement.skippedRequest) {
                        val laidOut = completed && try {
                            placement.apply()
                        } catch (failure: RuntimeException) {
                            HookEntry.log("LAUNCHER_SEARCH_BACKGROUND_LAYOUT_FALLBACK ${failure.javaClass.simpleName}")
                            false
                        }
                        if (laidOut) mark("OneUI search background laid out locally")
                        else view.requestLayout()
                    }
                }
            }
            for ((method, label) in listOf(
                border to "OneUI search border retained layout",
                bitmap to "OneUI search bitmap retained layout",
                color to "OneUI search dim retained layout",
            )) {
                installed += ModernHook.aroundMethod(method) { chain ->
                    if (Looper.myLooper() !== main || active != null) return@aroundMethod chain.proceed()
                    val owner = chain.thisObject ?: return@aroundMethod chain.proceed()
                    val view = (if (method === border) background.get(owner) else owner) as? ImageView
                        ?: return@aroundMethod chain.proceed()
                    if (view.parent?.javaClass !== qsb ||
                        (view.javaClass !== blur && view.javaClass !== dim) || !view.isInLayout
                    ) return@aroundMethod chain.proceed()
                    val scope = SearchBackgroundLayout(view, label)
                    if (!scope.canKeepLayout()) return@aroundMethod chain.proceed()
                    active = scope
                    try {
                        chain.proceed()
                    } finally {
                        active = null
                    }
                }
            }
        } catch (failure: Exception) {
            installed.asReversed().forEach { it.unhook() }
            throw failure
        }
        HookEntry.log("LAUNCHER_SEARCH_BACKGROUND_LAYOUT_READY")
    }

    private fun measuredExactly(spec: Int, laidOutSize: Int): Boolean =
        laidOutSize > 0 && MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY && MeasureSpec.getSize(spec) == laidOutSize

    private fun mark(name: String) {
        if (!Trace.isEnabled()) return
        Trace.beginSection(name)
        Trace.endSection()
    }
}

internal class SearchBackgroundLayout(val view: ImageView, val traceName: String) {
    private val parent = view.parent
    private val params = view.layoutParams as? FrameLayout.LayoutParams
    private val width = params?.width ?: 0
    private val height = params?.height ?: 0
    private val leftPadding = view.paddingLeft
    private val topPadding = view.paddingTop
    private val rightPadding = view.paddingRight
    private val bottomPadding = view.paddingBottom

    fun canKeepLayout(): Boolean =
        params != null && params.javaClass === FrameLayout.LayoutParams::class.java && view.layoutParams === params &&
            view.parent === parent && parent != null && view.isAttachedToWindow && view.isLaidOut &&
            view.visibility != View.GONE && !view.adjustViewBounds && !view.baselineAlignBottom &&
            width > 0 && height > 0 && params.width == width && params.height == height &&
            view.measuredWidth == width && view.measuredHeight == height &&
            view.width == width && view.height == height &&
            view.paddingLeft == leftPadding && view.paddingTop == topPadding &&
            view.paddingRight == rightPadding && view.paddingBottom == bottomPadding
}
