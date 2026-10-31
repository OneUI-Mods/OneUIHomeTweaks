package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmStatic
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/* Composes measured snapshot pixels and native card geometry without changing its aspect/style. */
object RecentsLiveGeometry {
    @JvmStatic
    fun compose(
        width: Int,
        height: Int,
        snapshotWidth: Int,
        snapshotHeight: Int,
        scale: Double,
        shader: FloatArray,
        global: FloatArray,
    ): RecentsAffine.Transform? {
        require(
            !(width <= 0 ||
                height <= 0 ||
                snapshotWidth <= 0 ||
                snapshotHeight <= 0 ||
                !java.lang.Double.isFinite(scale) ||
                scale <= 0 ||
                abs(snapshotWidth - width * scale) > 1 ||
                abs(snapshotHeight - height * scale) > 1)
        ) {
            "Snapshot does not cover the remote app"
        }
        return RecentsLiveGeometry.composeMeasured(
            RecentsAffine.Transform(scale, 0.0, 0.0, scale, 0.0, 0.0),
            width,
            height,
            shader,
            global,
        )
    }

    @JvmStatic
    fun compose(
        source: RecentsCardSpace.Space,
        bitmapWidth: Int,
        bitmapHeight: Int,
        snapshotRotation: Int,
        scale: Double,
        shader: FloatArray,
        global: FloatArray,
    ): RecentsAffine.Transform? {
        require(
            !(!java.lang.Double.isFinite(scale) ||
                scale <= 0 ||
                snapshotRotation < 0 ||
                snapshotRotation > 3)
        ) {
            "Invalid snapshot metadata"
        }
        val naturalWidth: Int = if (source.rotation % 2 === 0) source.width else source.height
        val naturalHeight: Int = if (source.rotation % 2 === 0) source.height else source.width
        val width: Int = if (snapshotRotation % 2 == 0) naturalWidth else naturalHeight
        val height: Int = if (snapshotRotation % 2 == 0) naturalHeight else naturalWidth
        require(
            !(bitmapWidth <= 0 ||
                bitmapHeight <= 0 ||
                abs(bitmapWidth - width * scale) > 1 ||
                abs(bitmapHeight - height * scale) > 1)
        ) {
            "Snapshot does not match source display geometry"
        }
        val snapshot = RecentsCardSpace.Space(width, height, snapshotRotation)
        return RecentsLiveGeometry.composeMeasured(
            RecentsCardSpace.between(source, snapshot)!!.then(
                RecentsAffine.Transform(scale, 0.0, 0.0, scale, 0.0, 0.0)
            )!!,
            source.width,
            source.height,
            shader,
            global,
        )
    }

    @JvmStatic
    private fun composeMeasured(
        source: RecentsAffine.Transform,
        width: Int,
        height: Int,
        shader: FloatArray,
        global: FloatArray,
    ): RecentsAffine.Transform? {
        val m =
            RecentsLiveGeometry.multiply(
                RecentsLiveGeometry.matrix(global),
                RecentsLiveGeometry.multiply(
                    RecentsLiveGeometry.matrix(shader),
                    doubleArrayOf(
                        source.a,
                        source.c,
                        source.tx,
                        source.b,
                        source.d,
                        source.ty,
                        (0).toDouble(),
                        (0).toDouble(),
                        (1).toDouble(),
                    ),
                ),
            )
        if (!java.lang.Double.isFinite(m[8]) || abs(m[8]) < 1e-12)
            throw RecentsLiveGeometry.PerspectiveCard()
        val divisor: Double = m[8]
        for (i in 0..8) {
            m[i] /= divisor
            require(java.lang.Double.isFinite(m[i])) { "Nonfinite composed matrix" }
        }
        // Home Up leaves tiny projective residuals even after a card settles. Bound the error over
        // the full source rectangle before dropping those terms. A real perspective stays a
        // snapshot.
        var minW: Double = Double.POSITIVE_INFINITY
        var maxW: Double = Double.NEGATIVE_INFINITY
        var maxNumerator: Double = 0.0
        for (x in intArrayOf(0, width)) for (y in intArrayOf(0, height)) {
            val w: Double = m[6] * x + m[7] * y + 1
            minW = min(minW, w)
            maxW = max(maxW, w)
            maxNumerator =
                max(
                    maxNumerator,
                    hypot(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5]),
                )
        }
        // Linear denominators and affine numerators attain their interval/norm bounds at corners;
        // this bounds every interior pixel too, not just the sampled corners.
        val error: Double = maxNumerator * max(abs(minW - 1), abs(maxW - 1)) / minW
        if (minW <= 0 || !java.lang.Double.isFinite(error) || error > .5)
            throw RecentsLiveGeometry.PerspectiveCard()
        return RecentsAffine.Transform(m[0], m[3], m[1], m[4], m[2], m[5])
    }

    @JvmStatic
    private fun matrix(m: FloatArray?): DoubleArray {
        require(!(m == null || m.size != 9)) { "Unknown matrix" }
        val result = DoubleArray(9)
        for (i in 0..8) {
            require(java.lang.Float.isFinite(m[i])) { "Nonfinite matrix" }
            result[i] = m[i].toDouble()
        }
        return result
    }

    @JvmStatic
    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (row in 0..2) for (col in 0..2) for (k in 0..2) r[row * 3 + col] +=
            a[row * 3 + k] * b[k * 3 + col]
        return r
    }

    class PerspectiveCard : IllegalArgumentException("Perspective card")
}
