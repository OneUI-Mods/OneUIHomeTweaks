package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.abs

/* Affine geometry for the live app Surface inside a native Recents card. */
object RecentsAffine {
    @JvmStatic
    private fun finite(vararg values: Double) {
        for (value in values) require(java.lang.Double.isFinite(value)) { "Nonfinite geometry" }
    }

    @JvmStatic
    private fun positive(vararg values: Double) {
        RecentsAffine.finite(*values)
        for (value in values) require(!(value <= 0)) { "Nonpositive geometry" }
    }

    @JvmRecord
    data class Point(val x: Double, val y: Double) {

        init {

            RecentsAffine.finite(x, y)
        }

        override fun toString(): String = "Point[x=$x, y=$y]"
    }

    /* Affine matrix [a c tx; b d ty; 0 0 1]. then(B) means B * this. */
    @JvmRecord
    data class Transform(
        val a: Double,
        val b: Double,
        val c: Double,
        val d: Double,
        val tx: Double,
        val ty: Double,
    ) {
        fun map(x: Double, y: Double): RecentsAffine.Point? {
            return RecentsAffine.Point(a * x + c * y + tx, b * x + d * y + ty)
        }

        fun then(next: RecentsAffine.Transform): RecentsAffine.Transform? {
            return RecentsAffine.Transform(
                next.a * a + next.c * b,
                next.b * a + next.d * b,
                next.a * c + next.c * d,
                next.b * c + next.d * d,
                next.a * tx + next.c * ty + next.tx,
                next.b * tx + next.d * ty + next.ty,
            )
        }

        fun inverse(): RecentsAffine.Transform? {
            val determinant: Double = a * d - b * c
            return RecentsAffine.Transform(
                d / determinant,
                -b / determinant,
                -c / determinant,
                a / determinant,
                (c * ty - d * tx) / determinant,
                (b * tx - a * ty) / determinant,
            )
        }

        init {

            RecentsAffine.finite(a, b, c, d, tx, ty)
            require(!(abs(a * d - b * c) < 1e-12)) { "Singular transform" }
        }

        companion object {
            @JvmStatic /*
                        * Natural portrait pixels to logical display pixels, matching quarter-turn display rotations.
                        */
            fun display(
                rotation: Int,
                naturalWidth: Double,
                naturalHeight: Double,
            ): RecentsAffine.Transform? {
                RecentsAffine.positive(naturalWidth, naturalHeight)
                return when (rotation) {
                    0 -> RecentsAffine.Transform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
                    1 -> RecentsAffine.Transform(0.0, -1.0, 1.0, 0.0, 0.0, naturalWidth)
                    2 -> RecentsAffine.Transform(-1.0, 0.0, 0.0, -1.0, naturalWidth, naturalHeight)
                    3 -> RecentsAffine.Transform(0.0, 1.0, -1.0, 0.0, naturalHeight, 0.0)
                    else -> throw IllegalArgumentException("Unknown display rotation")
                }
            }
        }

        override fun toString(): String = "Transform[a=$a, b=$b, c=$c, d=$d, tx=$tx, ty=$ty]"
    }
}
