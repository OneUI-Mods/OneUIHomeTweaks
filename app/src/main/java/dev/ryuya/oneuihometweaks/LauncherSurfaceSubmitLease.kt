package dev.ryuya.oneuihometweaks

import android.view.SurfaceControl
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/** Serializes submission with revocation and fences the return to the stock renderer.
 * The controller calls revoke on the same thread that receives completion callbacks.
 */
@RequiresApi(33)
internal class LauncherSurfaceSubmitLease(private val completionExecutor: Executor) {
    private val submissionLock = Any()
    private val commitLock = Any()
    private var acceptsFrames = true
    private var submitted = 0L
    private var committed = 0L
    private val committedAhead = HashSet<Long>()
    private val afterDrain = ArrayDeque<() -> Unit>()
    private var drainQueued = false

    fun hasPendingCommit(): Boolean = synchronized(commitLock) { committed < submitted || drainQueued }

    fun submit(transaction: SurfaceControl.Transaction, onCommit: (() -> Unit)? = null): Boolean = synchronized(submissionLock) {
        val sequence = synchronized(commitLock) {
            if (!acceptsFrames) return false
            submitted + 1
        }
        // Detached frames have no UI-side work to acknowledge. The compositor callback only takes commitLock.
        val executor = if (onCommit == null) immediate else completionExecutor
        transaction.addTransactionCommittedListener(executor) { acknowledge(sequence, onCommit) }
        synchronized(commitLock) { submitted = sequence }
        try {
            transaction.apply()
        } catch (failure: RuntimeException) {
            acknowledge(sequence, null)
            throw failure
        }
        true
    }

    /** This stops new submissions immediately. The callback follows the last accepted native commit. */
    fun revoke(onDrained: () -> Unit) {
        val ready = synchronized(submissionLock) {
            synchronized(commitLock) {
                acceptsFrames = false
                if (committed >= submitted && !drainQueued) true else {
                    afterDrain.addLast(onDrained)
                    false
                }
            }
        }
        if (ready) onDrained()
    }

    private fun acknowledge(sequence: Long, onCommit: (() -> Unit)?) {
        val shouldDrain = synchronized(commitLock) {
            if (sequence <= committed) return
            if (sequence == committed + 1) {
                committed = sequence
                while (committedAhead.isNotEmpty() && committedAhead.remove(committed + 1)) committed++
            } else if (!committedAhead.add(sequence)) return
            if (acceptsFrames || committed < submitted || afterDrain.isEmpty() || drainQueued) false else {
                drainQueued = true
                true
            }
        }
        if (shouldDrain) {
            if (onCommit == null) completionExecutor.execute(::drain) else drain()
        }
        onCommit?.invoke()
    }

    private fun drain() {
        val callbacks = synchronized(commitLock) {
            drainQueued = false
            afterDrain.toList().also { afterDrain.clear() }
        }
        for (after in callbacks) {
            try {
                after()
            } catch (failure: Exception) {
                HookEntry.log("LAUNCHER_SURFACE_DRAIN_CALLBACK_FAILED ${failure.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        val immediate = Executor { it.run() }
    }
}
