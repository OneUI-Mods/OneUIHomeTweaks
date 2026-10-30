package dev.ryuya.oneuihometweaks

import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.math.abs

/* Window-local navigation for the native Recents window over a retained controller. */
class RecentsNavigationGesture(
    width: Int,
    height: Int,
    left: Int,
    right: Int,
    bottom: Int,
    slop: Int,
) {
    @JvmRecord
    data class Decision(val consume: Boolean, val command: Int) {
        override fun toString(): String = "Decision[consume=$consume, command=$command]"
    }

    private val width: Int
    private val height: Int
    private val left: Int
    private val right: Int
    private val bottom: Int
    private val slop: Int
    private var down: Long = 0
    private var startX: Float = 0f
    private var startY: Float = 0f
    private var key: Int = 0
    private var direction: Int = 0
    private var blocked: Boolean = false

    init {
        require(
            !(width <= 0 ||
                height <= 0 ||
                left <= 0 ||
                right <= 0 ||
                bottom <= 0 ||
                left >= width * .2f ||
                right >= width * .2f ||
                bottom >= height * .1f ||
                slop <= 0)
        ) {
            "Unknown native navigation regions"
        }
        this.width = width
        this.height = height
        this.left = left
        this.right = right
        this.bottom = bottom
        this.slop = slop
    }

    fun event(
        downTime: Long,
        action: Int,
        pointers: Int,
        x: Float,
        y: Float,
    ): RecentsNavigationGesture.Decision? {
        if (action == 0) {
            down = 0
            key = 0
            blocked = false
            if (
                pointers == 1 &&
                    downTime > 0 &&
                    java.lang.Float.isFinite(x) &&
                    java.lang.Float.isFinite(y) &&
                    x >= 0 &&
                    x <= width &&
                    y >= 0 &&
                    y <= height
            ) {
                if (y >= height - bottom && x >= width * .04f && x <= width * .96f)
                    key = RecentsNavigationGesture.HOME
                else if (y < height - bottom && (x <= left || x >= width - right)) {
                    key = RecentsNavigationGesture.BACK
                    blocked = true
                    direction = if (x < width * .5f) 1 else -1
                }
                if (key != 0) {
                    down = downTime
                    startX = x
                    startY = y
                }
            }
        }
        val consume: Boolean = blocked
        if (
            pointers != 1 ||
                action == 3 ||
                !java.lang.Float.isFinite(x) ||
                !java.lang.Float.isFinite(y) ||
                downTime != down
        ) {
            down = 0
            key = 0
        }
        var command: Int = 0
        if (action == 1 && down != 0L) {
            val up: Float = startY - y
            val inward: Float = (x - startX) * direction
            if (
                key == RecentsNavigationGesture.HOME &&
                    y < height * .9f &&
                    up > slop &&
                    abs(x - startX) < up
            )
                command = RecentsNavigationGesture.HOME
            else if (
                key == RecentsNavigationGesture.BACK && inward > slop && abs(y - startY) < inward
            )
                command = RecentsNavigationGesture.BACK
        }
        if (action == 1 || action == 3) {
            down = 0
            key = 0
            blocked = false
        }
        return RecentsNavigationGesture.Decision(consume, command)
    }

    companion object {
        const val HOME: Int = 3
        const val BACK: Int = 4

        @JvmField
        val NONE: RecentsNavigationGesture.Decision = RecentsNavigationGesture.Decision(false, 0)
    }
}
