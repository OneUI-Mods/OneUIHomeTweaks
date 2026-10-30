package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Window pixel spaces. Layout dimensions are observed, never resized to request an orientation. */
object RecentsCardSpace {
    @JvmStatic
    fun between(
        from: RecentsCardSpace.Space,
        to: RecentsCardSpace.Space,
    ): RecentsAffine.Transform? {
        require(
            !(from.naturalWidth() != to.naturalWidth() ||
                from.naturalHeight() != to.naturalHeight())
        ) {
            "Card windows do not share display geometry"
        }
        val w = from.naturalWidth()
        val h = from.naturalHeight()
        return RecentsAffine.Transform.display(from.rotation, w.toDouble(), h.toDouble())!!
            .inverse()!!
            .then(RecentsAffine.Transform.display(to.rotation, w.toDouble(), h.toDouble())!!)
    }

    @JvmRecord
    data class Space(val width: Int, val height: Int, val rotation: Int) {
        fun naturalWidth(): Int {
            return if (rotation % 2 === 0) width else height
        }

        fun naturalHeight(): Int {
            return if (rotation % 2 === 0) height else width
        }

        init {

            require(!(width <= 0 || height <= 0 || rotation < 0 || rotation > 3)) {
                "Unmeasured card space"
            }
        }

        override fun toString(): String = "Space[width=$width, height=$height, rotation=$rotation]"
    }
}
