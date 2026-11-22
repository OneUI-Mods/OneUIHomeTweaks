package dev.ryuya.oneuihometweaks

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Retains only weak references to stock objects across code updates. */
internal object SystemUiLiveObjects {
    private val groups = HashMap<String, WeakHashMap<Any, Unit>>()
    private val latest = HashMap<String, WeakReference<Any>>()

    @Synchronized fun track(group: String, value: Any) {
        groups.getOrPut(group) { WeakHashMap() }[value] = Unit
    }

    @Synchronized fun snapshot(group: String): List<Any> = groups[group]?.keys?.toList().orEmpty()

    @Synchronized fun remember(slot: String, value: Any) {
        latest[slot] = WeakReference(value)
    }

    @Synchronized fun find(slot: String): Any? = latest[slot]?.get()

    @Synchronized
    fun exportState(): Array<Any> = buildList<Any> {
        for ((name, members) in groups) {
            for (member in members.keys) add(arrayOf(name, false, WeakReference(member)))
        }
        for ((name, reference) in latest) add(arrayOf(name, true, reference))
    }.toTypedArray()

    fun importState(snapshot: Any?) {
        for (row in snapshot as? Array<*> ?: return) {
            val record = row as? Array<*> ?: continue
            val name = record.getOrNull(0) as? String ?: continue
            val latest = record.getOrNull(1) as? Boolean ?: continue
            val value = (record.getOrNull(2) as? WeakReference<*>)?.get() ?: continue
            if (latest) remember(name, value) else track(name, value)
        }
    }
}
