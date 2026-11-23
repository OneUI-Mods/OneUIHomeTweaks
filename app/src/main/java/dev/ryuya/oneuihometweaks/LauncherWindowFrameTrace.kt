package dev.ryuya.oneuihometweaks

import android.os.Trace

internal class LauncherWindowFrameTrace(prefix: String) {
    private val sample = "$prefix sample"
    private val trial = "$prefix trial"
    private val time = "$prefix frame time"
    private val x = "$prefix x x100"
    private val y = "$prefix y x100"
    private val cropLeft = "$prefix crop left"
    private val cropTop = "$prefix crop top"
    private val cropRight = "$prefix crop right"
    private val cropBottom = "$prefix crop bottom"
    private val scale = "$prefix scale x1e6"
    private val alpha = "$prefix alpha x1e6"
    private val radius = "$prefix radius x100"
    private val shadow = "$prefix shadow x100"
    private val foregroundAlpha = "$prefix fg alpha x1e6"
    private val foregroundScale = "$prefix fg scale x1e6"
    private val groupAlpha = "$prefix group alpha x1e6"
    private val groupScale = "$prefix group scale x1e6"
    private val progress = "$prefix progress x1e6"

    fun record(frameTime: Long, token: Long, state: LauncherWindowGeometry.Frame) {
        if (!Trace.isEnabled()) return
        Trace.beginSection(sample)
        Trace.setCounter(trial, token)
        Trace.setCounter(time, frameTime)
        Trace.setCounter(x, (state.x * 100f).toLong())
        Trace.setCounter(y, (state.y * 100f).toLong())
        Trace.setCounter(cropLeft, state.crop.left.toLong())
        Trace.setCounter(cropTop, state.crop.top.toLong())
        Trace.setCounter(cropRight, state.crop.right.toLong())
        Trace.setCounter(cropBottom, state.crop.bottom.toLong())
        Trace.setCounter(scale, (state.scale * 1_000_000f).toLong())
        Trace.setCounter(alpha, (state.alpha * 1_000_000f).toLong())
        Trace.setCounter(radius, (state.radius * 100f).toLong())
        Trace.setCounter(shadow, (state.shadowRadius * 100f).toLong())
        Trace.setCounter(foregroundAlpha, (state.foregroundAlpha * 1_000_000f).toLong())
        Trace.setCounter(foregroundScale, (state.foregroundScale * 1_000_000f).toLong())
        Trace.setCounter(groupAlpha, (state.groupAlpha * 1_000_000f).toLong())
        Trace.setCounter(groupScale, (state.groupScale * 1_000_000f).toLong())
        Trace.setCounter(progress, (state.progress * 1_000_000f).toLong())
        Trace.endSection()
    }
}
