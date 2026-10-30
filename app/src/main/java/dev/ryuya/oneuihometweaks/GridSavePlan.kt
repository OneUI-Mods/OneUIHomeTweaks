package dev.ryuya.oneuihometweaks

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Comparator
import java.util.List
import java.util.Locale
import java.util.stream.Collectors
import kotlin.jvm.JvmRecord

/* Immutable preview identity: a confirmation cannot silently apply to a different layout. */

class GridSavePlan(entries: MutableList<GridSavePlan.Entry?>?) {
    val entries: MutableList<GridSavePlan.Entry?>?

    @JvmRecord
    data class Entry(
        val id: Int,
        val page: Int,
        val oldX: Int,
        val oldY: Int,
        val x: Int,
        val y: Int,
        val shape: String?,
    ) {
        fun changed(): Boolean {
            return oldX != x || oldY != y
        }

        override fun toString(): String =
            "Entry[id=$id, page=$page, oldX=$oldX, oldY=$oldY, x=$x, y=$y, shape=$shape]"
    }

    fun changed(): Int {
        return entries!!
            .stream()
            .filter({ obj: GridSavePlan.Entry? -> obj!!.changed() })
            .count()
            .toInt()
    }

    fun pages(): Int {
        return entries!!
            .stream()
            .filter({ obj: GridSavePlan.Entry? -> obj!!.changed() })
            .map({ entry -> entry!!.page })
            .distinct()
            .count()
            .toInt()
    }

    fun fingerprint(): String? {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            for (e in entries!!) digest!!.update(
                (e.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
            )
            val out = StringBuilder()
            for (b in digest!!.digest()) out.append(
                String.format(Locale.ROOT, "%02x", b.toInt() and 255)
            )
            return out.toString()
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException(e)
        }
    }

    init {
        var entries: MutableList<GridSavePlan.Entry?>? = entries
        entries =
            List.copyOf<GridSavePlan.Entry?>(
                entries!!
                    .stream()
                    .sorted(Comparator.comparingInt<GridSavePlan.Entry?>({ entry -> entry!!.id }))
                    .collect(Collectors.toList())
            )
        require(
            !(entries.isEmpty() ||
                entries.size > 500 ||
                entries.stream().map<Any?>({ entry -> entry!!.id }).distinct().count() !=
                    entries.size.toLong())
        ) {
            "Ambiguous icon list"
        }

        this.entries = entries
    }

    fun entries(): MutableList<GridSavePlan.Entry?>? = entries

    override fun equals(other: Any?): Boolean = other is GridSavePlan && entries == other.entries

    override fun hashCode(): Int {
        var result = entries.hashCode()
        return result
    }

    override fun toString(): String = "GridSavePlan[entries=$entries]"
}
