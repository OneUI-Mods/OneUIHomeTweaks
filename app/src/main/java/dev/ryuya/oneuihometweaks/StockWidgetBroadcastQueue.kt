package dev.ryuya.oneuihometweaks

import android.content.Intent
import android.os.Handler
import java.util.concurrent.atomic.AtomicInteger

internal class StockWidgetBroadcastQueue(
    private val widgetLock: Any,
    stateWriter: Handler,
    private val onFailure: (Exception) -> Unit,
) {
    // The stock writer calls removeMessages(userId), including what=0.
    // Share its thread but use a different Handler target for broadcasts.
    private val delivery = Handler(stateWriter.looper)
    private val pending = AtomicInteger()

    fun hasPending(): Boolean = pending.get() != 0

    fun enqueue(stateLock: Any, intent: Intent, send: (Intent) -> Unit): Boolean {
        if (stateLock !== widgetLock || !Thread.holdsLock(widgetLock)) return false
        val snapshot = Intent(intent).replaceExtras(intent.extras?.deepCopy())
        pending.incrementAndGet()
        if (delivery.post {
                try {
                    // Posts are ordered by the widget lock. Finish the state update
                    // before this worker can enter ActivityManager.
                    synchronized(widgetLock) { }
                    send(snapshot)
                } catch (error: Exception) {
                    onFailure(error)
                } finally {
                    pending.decrementAndGet()
                }
            }
        ) return true
        pending.decrementAndGet()
        return false
    }
}
