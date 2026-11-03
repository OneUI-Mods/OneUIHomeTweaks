package dev.ryuya.oneuihometweaks

/** Only reject an overlap when both complete view bounds prove that it is impossible. */
object IconOverlapPolicy {
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun valid(): Boolean =
            left.isFinite() &&
                top.isFinite() &&
                right.isFinite() &&
                bottom.isFinite() &&
                right > left &&
                bottom > top
    }

    fun definitelySeparate(first: Bounds, second: Bounds): Boolean =
        first.valid() &&
            second.valid() &&
            (first.right <= second.left ||
                second.right <= first.left ||
                first.bottom <= second.top ||
                second.bottom <= first.top)
}
