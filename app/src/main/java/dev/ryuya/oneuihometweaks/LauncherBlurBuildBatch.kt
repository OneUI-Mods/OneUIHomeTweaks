package dev.ryuya.oneuihometweaks

import android.graphics.RenderEffect

/** Reuses a size-independent effect within one stock loop, leaving its first and last build intact. */
internal class LauncherBlurBuildBatch {
    private var expected = 0
    private var owner: Any? = null
    private var effect: RenderEffect? = null
    var requests = 0
        private set
    var nativeBuilds = 0
        private set

    fun begin(visibleViews: Int) {
        clearEffect()
        expected = visibleViews
        requests = 0
        nativeBuilds = 0
    }

    inline fun build(
        filter: Any,
        validSize: Boolean,
        buildNative: () -> RenderEffect?,
        isSizeIndependent: () -> Boolean,
    ): RenderEffect? {
        requests++
        if (owner != null && owner !== filter) clearEffect()
        if (validSize && requests in 2 until expected && owner === filter) {
            effect?.let { return it }
        }
        nativeBuilds++
        val created = buildNative()
        if (requests == 1 && expected > 2 && validSize && created != null && isSizeIndependent()) {
            owner = filter
            effect = created
        }
        return created
    }

    fun finish() {
        clearEffect()
        expected = 0
    }

    private fun clearEffect() {
        owner = null
        effect = null
    }
}
