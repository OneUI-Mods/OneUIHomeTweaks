package dev.ryuya.oneuihometweaks

import java.util.concurrent.locks.ReentrantReadWriteLock

/** Keeps an intercepted call on one generation, including around hooks and their continuations. */
internal object SystemUiHookScopes {
    private val lock = ReentrantReadWriteLock()
    private val binding = ThreadLocal<SystemUiHookScope>()
    @Volatile private var active: SystemUiHookScope? = null

    fun bound(): SystemUiHookScope? = binding.get()

    inline fun <T> bind(scope: SystemUiHookScope, action: () -> T): T {
        val previous = binding.get()
        if (previous === scope) return action()
        binding.set(scope)
        try {
            return action()
        } finally {
            if (previous == null) binding.remove() else binding.set(previous)
        }
    }

    inline fun dispatch(scope: SystemUiHookScope, stock: () -> Any?, hook: () -> Any?): Any? {
        if (active !== scope) return stock()
        if (bound() === scope) return hook()
        val read = lock.readLock()
        read.lock()
        try {
            if (active === scope) return bind(scope, hook)
        } finally {
            read.unlock()
        }
        return stock()
    }

    /** Close resources once the last invocation has finished. Leave the active scope on rejection. */
    fun tryRetire(save: () -> Unit): Boolean = tryRetireIf {
        save()
        true
    }

    fun tryRetireIf(save: () -> Boolean): Boolean {
        val write = lock.writeLock()
        if (!write.tryLock()) return false
        val old = try {
            val current = active ?: return false
            if (!save()) return false
            active = null
            current
        } finally {
            write.unlock()
        }
        old.close()
        return true
    }

    /** Avoid making the main thread wait behind an intercepted framework call. */
    fun trySwitch(next: SystemUiHookScope, retire: (SystemUiHookScope?) -> Unit): Boolean {
        val write = lock.writeLock()
        if (!write.tryLock()) return false
        val old = try {
            val previous = active
            active = next
            previous
        } finally {
            write.unlock()
        }
        retire(old)
        bind(next, next::activate)
        return true
    }
}

internal class SystemUiHookScope(private val report: (Throwable) -> Unit) {
    private val handles = LinkedHashSet<ModernHook.Handle>()
    private val activations = ArrayList<() -> Unit>()
    private val retirements = ArrayList<() -> Unit>()
    private var activated = false
    private var closed = false

    fun track(handle: ModernHook.Handle): ModernHook.Handle {
        val registered = synchronized(this) { !closed && handles.add(handle) }
        if (!registered) handle.unhook()
        return ModernHook.Handle {
            if (synchronized(this) { handles.remove(handle) }) handle.unhook()
        }
    }

    fun afterActivation(action: () -> Unit) {
        val run = synchronized(this) {
            when {
                closed -> false
                activated -> true
                else -> { activations.add(action); false }
            }
        }
        if (run) action()
    }

    fun onRetire(action: () -> Unit) {
        val run = synchronized(this) {
            if (closed) true else { retirements.add(action); false }
        }
        if (run) safely(action)
    }

    fun activate() {
        val pending = synchronized(this) {
            activated = true
            activations.toList().also { activations.clear() }
        }
        pending.forEach { safely(it) }
    }

    fun close() {
        val (installed, cleanup) = synchronized(this) {
            if (closed) return
            closed = true
            activations.clear()
            Pair(handles.toList().asReversed(), retirements.toList().asReversed()).also {
                handles.clear()
                retirements.clear()
            }
        }
        installed.forEach { safely(it::unhook) }
        cleanup.forEach(::safely)
    }

    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            report(error)
        } catch (error: LinkageError) {
            report(error)
        }
    }
}
