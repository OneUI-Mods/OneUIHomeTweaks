package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmStatic

object PagePolicy {
    @JvmStatic
    fun offset(scroll: Int, positions: IntArray): Float = offset(scroll, positions, positions.size)

    internal fun offset(scroll: Int, positions: IntArray, count: Int): Float {
        if (count <= 1) return 0.5f
        val ascending: Boolean = positions[count - 1] > positions[0]
        for (i in 1 until count) require(
            !(if (ascending) positions[i] <= positions[i - 1] else positions[i] >= positions[i - 1])
        ) {
            "Nonmonotonic page positions"
        }
        val first = positions[0].toFloat()
        val last = positions[count - 1].toFloat()
        if (if (ascending) scroll <= first else scroll >= first) return 0f
        if (if (ascending) scroll >= last else scroll <= last) return 1f
        for (i in 0 until count - 1) {
            if (if (ascending) scroll <= positions[i + 1] else scroll >= positions[i + 1])
                return ((i +
                    (scroll - positions[i].toFloat()) /
                        (positions[i + 1] - positions[i].toFloat())) / (count - 1))
        }
        return 0.5f
    }
}
