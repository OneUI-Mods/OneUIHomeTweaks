package dev.ryuya.oneuihometweaks

import android.os.Trace
import android.view.SurfaceControl
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/** Records one icon generation's compositor acknowledgement without reading its Views. */
@RequiresApi(33)
internal class LauncherOpeningIconCommit(private val label: String) {
    private enum class State { WAITING, COMMITTED, RETIRED }

    private val state = AtomicReference(State.WAITING)
    val committed: Boolean get() = state.get() == State.COMMITTED

    fun watch(transaction: SurfaceControl.Transaction) {
        transaction.addTransactionCommittedListener(compositorCallback) {
            if (state.compareAndSet(State.WAITING, State.COMMITTED) && Trace.isEnabled()) {
                Trace.beginSection(label)
                Trace.endSection()
            }
        }
    }

    fun retire() {
        state.set(State.RETIRED)
    }

    private companion object {
        val compositorCallback = Executor { it.run() }
    }
}
