package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmStatic
import kotlin.math.abs

/*
 * Resolves the effective source space from the actual player basis, including normalized leashes.
 */
object RecentsLeashSpace {
    @JvmStatic
    fun resolve(
        root: RecentsCardSpace.Space?,
        width: Int,
        height: Int,
        basis: FloatArray?,
    ): RecentsCardSpace.Space? {
        require(!(basis == null || basis.size != 9)) { "Unmeasured player basis" }
        for (f in basis) require(java.lang.Float.isFinite(f)) { "Nonfinite basis" }
        var match: RecentsCardSpace.Space? = null
        for (rotation in 0..3) {
            val candidate = RecentsCardSpace.Space(width, height, rotation)
            val t: RecentsAffine.Transform?
            try {
                t = RecentsCardSpace.between(root!!, candidate)
            } catch (mismatch: IllegalArgumentException) {
                continue
            }
            val values =
                doubleArrayOf(
                    t!!.a,
                    t!!.c,
                    t!!.tx,
                    t!!.b,
                    t!!.d,
                    t!!.ty,
                    (0).toDouble(),
                    (0).toDouble(),
                    (1).toDouble(),
                )
            var equal: Boolean = true
            for (i in 0..8) equal = equal and (abs(values[i] - basis[i]) <= .001)
            if (equal) {
                require(match == null) { "Ambiguous basis" }
                match = candidate
            }
        }
        requireNotNull(match) { "Player basis does not match measured source bounds" }
        return match
    }
}
