package dev.ryuya.oneuihometweaks

/** Version the private wire contract independently from application releases. */
internal object RecentsFocusCompatibility {
    const val PROTOCOL_VERSION = 1

    fun accepts(protocol: Int, build: Int): Boolean =
        protocol == PROTOCOL_VERSION ||
            // These shipped implementations have identical server/wire/policy sources.
            (protocol == 0 && build in 31..34)
}
