package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmStatic
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/* Composes measured snapshot pixels and native card geometry without changing its aspect/style. */
object RecentsLiveGeometry {
    internal class Workspace {
        val source = DoubleArray(9)
        val local = DoubleArray(9)
        val composed = DoubleArray(9)
    }

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
            Workspace(),
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
    ): RecentsAffine.Transform? =
        compose(source, bitmapWidth, bitmapHeight, snapshotRotation, scale, shader, global, Workspace())

    internal fun compose(
        source: RecentsCardSpace.Space,
        bitmapWidth: Int,
        bitmapHeight: Int,
        snapshotRotation: Int,
        scale: Double,
        shader: FloatArray,
        global: FloatArray,
        workspace: Workspace,
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
            workspace,
        )
    }

    @JvmStatic
    private fun composeMeasured(
        source: RecentsAffine.Transform,
        width: Int,
        height: Int,
        shader: FloatArray,
        global: FloatArray,
        workspace: Workspace,
    ): RecentsAffine.Transform? {
        // Preserve the original multiplication order for the perspective check.
        validateMatrix(global)
        validateMatrix(shader)
        val matrix = workspace.source
        matrix[0] = source.a
        matrix[1] = source.c
        matrix[2] = source.tx
        matrix[3] = source.b
        matrix[4] = source.d
        matrix[5] = source.ty
        matrix[8] = 1.0
        multiply(shader, matrix, workspace.local)
        val m = workspace.composed
        multiply(global, workspace.local, m)
        if (!java.lang.Double.isFinite(m[8]) || abs(m[8]) < 1e-12)
            throw RecentsLiveGeometry.PerspectiveCard()
        val divisor: Double = m[8]
        for (i in 0..8) {
            m[i] /= divisor
            require(java.lang.Double.isFinite(m[i])) { "Nonfinite composed matrix" }
        }
        // With no projective terms the denominator is exactly one across the entire card.
        if (m[6] == 0.0 && m[7] == 0.0) return RecentsAffine.Transform(m[0], m[3], m[1], m[4], m[2], m[5])
        // Home Up leaves tiny projective residuals even after a card settles. Bound the error over
        // the full source rectangle before dropping those terms. A real perspective stays a
        // snapshot.
        var minW: Double = Double.POSITIVE_INFINITY
        var maxW: Double = Double.NEGATIVE_INFINITY
        var maxNumerator: Double = 0.0
        for (corner in 0..3) {
            val x = if ((corner and 2) == 0) 0 else width
            val y = if ((corner and 1) == 0) 0 else height
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

    private fun validateMatrix(matrix: FloatArray) {
        require(matrix.size == 9) { "Unknown matrix" }
        for (value in matrix) require(value.isFinite()) { "Nonfinite matrix" }
    }

    private fun multiply(left: FloatArray, right: DoubleArray, output: DoubleArray) {
        for (row in 0..2) {
            val start = row * 3
            for (column in 0..2) {
                var value = 0.0
                for (index in 0..2) value += left[start + index] * right[index * 3 + column]
                output[start + column] = value
            }
        }
    }

    class PerspectiveCard : IllegalArgumentException("Perspective card")
}
