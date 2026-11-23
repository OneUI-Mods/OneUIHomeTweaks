package dev.ryuya.oneuihometweaks

import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * Records where a stock object stored a module-owned replacement, so the stock value can be put back
 * when this generation retires. A replacement left in a long-lived stock object keeps the retired
 * module class loader, and every class it defined, reachable.
 */
internal class StockReferenceSwap private constructor(
    private val replacement: Any,
    private val original: Any,
) {
    private class Slot(holder: Any, val field: Field?, val index: Int) {
        val holder = WeakReference(holder)
    }

    private val slots = mutableListOf<Slot>()

    /** Main thread. Restores the stock value wherever it still holds the replacement. */
    fun restore() {
        for (slot in slots) {
            val holder = slot.holder.get() ?: continue
            try {
                val field = slot.field
                if (field != null) {
                    if (field.get(holder) === replacement) field.set(holder, original)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val array = holder as Array<Any?>
                    if (slot.index < array.size && array[slot.index] === replacement) array[slot.index] = original
                }
            } catch (failure: ReflectiveOperationException) {
                HookEntry.log("STOCK_REFERENCE_RESTORE_FAILED ${failure.javaClass.simpleName}")
            } catch (failure: RuntimeException) {
                HookEntry.log("STOCK_REFERENCE_RESTORE_FAILED ${failure.javaClass.simpleName}")
            }
        }
        slots.clear()
    }

    companion object {
        private const val MAX_DEPTH = 5
        private const val MAX_VISITS = 4_000

        /** Main thread. Searches the host objects reachable from [root] for fields holding [replacement]. */
        fun find(root: Any, replacement: Any, original: Any, hostLoader: ClassLoader?): StockReferenceSwap {
            val swap = StockReferenceSwap(replacement, original)
            val seen = IdentityHashMap<Any, Unit>()
            var frontier = listOf(root)
            var visits = 0
            for (depth in 0 until MAX_DEPTH) {
                val next = mutableListOf<Any>()
                for (current in frontier) {
                    if (seen.put(current, Unit) != null || ++visits > MAX_VISITS) continue
                    if (current is Array<*>) {
                        current.forEachIndexed { index, value ->
                            if (value === replacement) swap.slots += Slot(current, null, index)
                            else if (value != null && traversable(value, hostLoader)) next += value
                        }
                        continue
                    }
                    var type: Class<*>? = current.javaClass
                    while (type != null && type != Any::class.java) {
                        for (field in type.declaredFields) {
                            if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                            val value = try {
                                field.isAccessible = true
                                field.get(current)
                            } catch (_: ReflectiveOperationException) {
                                null
                            } catch (_: RuntimeException) {
                                null
                            } ?: continue
                            if (value === replacement) swap.slots += Slot(current, field, -1)
                            else if (traversable(value, hostLoader)) next += value
                        }
                        type = type.superclass
                    }
                }
                frontier = next
            }
            return swap
        }

        // Host objects and the collections they use; never the framework or this module.
        private fun traversable(value: Any, hostLoader: ClassLoader?): Boolean {
            if (value is Array<*>) return !value.javaClass.componentType!!.isPrimitive
            val type = value.javaClass
            if (type.name.startsWith("java.util.ArrayList") || type.name.startsWith("java.util.concurrent.CopyOnWriteArrayList")) {
                return true
            }
            return hostLoader != null && type.classLoader === hostLoader
        }
    }
}
