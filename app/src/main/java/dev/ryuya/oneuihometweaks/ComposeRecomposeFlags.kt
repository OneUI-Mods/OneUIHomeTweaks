package dev.ryuya.oneuihometweaks

/** Keep Samsung's conditional Composer.changed calls in the same slot order. */
object ComposeRecomposeFlags {
    private const val STATE_COMPARISON = 0xC00
    private const val STATE_CHANGED = 0x800
    private const val MODIFIER_COMPARISON = 0x30
    private const val MODIFIER_CHANGED = 0x20
    private const val FORCE_EXECUTION = 1

    fun panelStateChanged(flags: Int): Int = argumentChanged(flags, STATE_COMPARISON, STATE_CHANGED)

    fun tileModifierChanged(flags: Int, wasDefault: Boolean): Int =
        if (wasDefault) (flags and MODIFIER_COMPARISON.inv()) or MODIFIER_CHANGED or FORCE_EXECUTION
        else argumentChanged(flags, MODIFIER_COMPARISON, MODIFIER_CHANGED)

    private fun argumentChanged(flags: Int, comparisonMask: Int, changed: Int): Int {
        // Unknown metadata means the native renderer must call Composer.changed itself.
        // Replacing it with "known changed" skips a slot and reads the next remember
        // value from the wrong location. Only replace an already-known comparison.
        val next =
            if (flags and comparisonMask == 0) flags
            else (flags and comparisonMask.inv()) or changed
        return next or FORCE_EXECUTION
    }
}
