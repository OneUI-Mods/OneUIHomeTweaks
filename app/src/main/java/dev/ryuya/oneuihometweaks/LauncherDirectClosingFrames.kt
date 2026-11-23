package dev.ryuya.oneuihometweaks

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Trace
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.View
import androidx.annotation.RequiresApi
import dev.ryuya.oneuihometweaks.compat.DexSymbols
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredField
import dev.ryuya.oneuihometweaks.compat.compatibleDeclaredMethod
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executor

/** Transfers the stock frame's transaction and release sequence once its icon can be moved with it. */
@RequiresApi(34)
internal class LauncherDirectClosingFrames(loader: ClassLoader, private val main: Handler) {
    private val applier = DexSymbols.findClass("com.honeyspace.transition.utils.SurfaceTransactionApplier", loader)
    private val params = DexSymbols.findClass("com.honeyspace.transition.utils.SurfaceTransaction", loader)
    private val schedule = applier.compatibleDeclaredMethod("scheduleApply", params).apply { isAccessible = true }
    private val sequence = applier.compatibleDeclaredField("lastSequenceNumber").apply { isAccessible = true }
    private val release = requireNotNull(applier.superclass)
    private val setCanRelease = release.compatibleDeclaredMethod("setCanRelease", Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }
    private val viewRoot = applier.compatibleDeclaredField("targetViewRootImpl").apply { isAccessible = true }
    private val readView = viewRoot.type.compatibleDeclaredMethod("getView").apply { isAccessible = true }
    private val barrier = applier.compatibleDeclaredField("barrierSurfaceControl").apply { isAccessible = true }
    private val readWrapper = params.compatibleDeclaredMethod("getTransaction").apply { isAccessible = true }
    private val readTransaction = readWrapper.returnType.compatibleDeclaredField("mTransaction").apply { isAccessible = true }
    private val readVsync = Choreographer::class.java.compatibleDeclaredMethod("getVsyncId").apply { isAccessible = true }
    private val setVsync = SurfaceControl.Transaction::class.java.compatibleDeclaredMethod(
        "setFrameTimelineVsync", Long::class.javaPrimitiveType,
    ).apply { isAccessible = true }
    private val applyMessage = applier.compatibleDeclaredMethod("onApplyMessage", Message::class.java)
    private val committedDirect = WeakHashMap<Any, Int>()
    private val outstandingDirect = WeakHashMap<Any, WeakReference<Handoff>>()
    private val acknowledge = Executor { callback -> main.post(callback) }
    private var current: Scope? = null
    private var draining: Handoff? = null
    private var targetReplacement: Handoff? = null
    private var earlyClaim = WeakReference<Handoff>(null)
    private var playerFrames = 0

    fun install(handles: MutableList<ModernHook.Handle>) {
        handles += ModernHook.aroundMethod(schedule) { chain ->
            val owner = chain.thisObject
            if (owner == null || Looper.myLooper() !== main.looper) return@aroundMethod chain.proceed()
            val scope = current
            val argument = chain.args[0]
            val replacing = targetReplacement
            if (argument != null && replacing != null && replacing.directLease.hasPendingCommit()) {
                replacing.fallBack { sendToStock(owner, argument) }
                return@aroundMethod null
            }
            val previous = outstandingDirect[owner]?.get()
            if (argument != null && previous != null && scope?.handoff !== previous && previous.blocksStock()) {
                previous.fallBack { sendToStock(owner, argument) }
                return@aroundMethod null
            }
            val early = earlyClaim.get()
            if (argument != null && early != null && playerFrames > 0 && scope?.handoff !== early && early.owner !== owner &&
                early.blocksStock() && targetsWindow(owner, early.earlyWindow.get())
            ) {
                // Another stock player over the same Home window, for example a catch gesture, waits for the last worker frame.
                mark("OneUI closing early yields to another stock applier")
                early.fallBack { sendToStock(owner, argument) }
                return@aroundMethod null
            }
            if (scope == null) {
                draining?.takeIf { it.owner === owner && it.waiting != null }?.fallBack()
                return@aroundMethod chain.proceed()
            }
            if (scope.stockApplier != null && owner !== scope.stockApplier) return@aroundMethod chain.proceed()
            capture(scope, owner, argument) { chain.proceed() }
        }
        handles += ModernHook.aroundMethod(applyMessage) { chain ->
            val owner = chain.thisObject
            val message = chain.args[0] as? Message
            if (owner != null && message?.what == 0 && Looper.myLooper() === main.looper &&
                committedDirect[owner] == sequence.getInt(owner)
            ) true else chain.proceed()
        }
    }

    private fun capture(scope: Scope, owner: Any, argument: Any?, stock: () -> Any?): Any? {
        val handoff = scope.handoff
        if (scope.pending != null) {
            current = null
            scope.pending?.restoreStock()
            scope.pending = null
            return afterDirect(handoff, owner, argument, stock)
        }
        if (handoff.early) return captureEarly(scope, handoff, owner, argument, stock)
        val targetView = viewRoot.get(owner)?.let { readView.invoke(it) } as? View
        val surface = barrier.get(owner) as? SurfaceControl
        if (targetView?.rootView !== scope.window || surface?.isValid != true) {
            mark("OneUI closing direct root mismatch")
            return if (handoff.owner === owner) afterDirect(handoff, owner, argument, stock) else stock()
        }
        if (handoff.disabled || argument == null) return afterDirect(handoff, owner, argument, stock)
        if (handoff.owner != null && handoff.owner !== owner) {
            return afterDirect(handoff, owner, argument, stock)
        }
        val transaction = readTransaction.get(readWrapper.invoke(argument)) as? SurfaceControl.Transaction
        if (transaction == null) {
            return afterDirect(handoff, owner, argument, stock)
        }
        scope.onStockSchedule?.invoke()
        handoff.owner = owner
        val previous = sequence.getInt(owner)
        if (handoff.beginStockCapture()) {
            if (!scope.ready) return afterDirect(handoff, owner, argument, stock)
            scope.pending = Pending(handoff, owner, argument, transaction, previous, 0L, detachedStock = true)
            return null
        }
        if (scope.bindOnStockFrame) {
            val vsync = readVsync.invoke(Choreographer.getInstance()) as Long
            if (vsync > 0 && handoff.waiting == null && !handoff.directLease.hasPendingCommit()) {
                scope.pending = Pending(handoff, owner, argument, transaction, previous, vsync, stockDraw = true)
                return null
            }
        }
        // A newly constructed stock applier has never sent an update to the ViewRoot queue.
        val firstClosingFrame = !handoff.opening && handoff.lastSequence == null && previous == 0
        if (!scope.ready && scope.invisibleClosingIcon &&
            (firstClosingFrame || handoff.hasInvisibleBaseline && handoff.lastSequence == previous)
        ) {
            val vsync = if (scope.delayedVsync > 0) scope.delayedVsync else readVsync.invoke(Choreographer.getInstance()) as Long
            if (vsync > 0) {
                // PlayerImpl may make the icon visible after scheduleApply, so check again before submitting.
                scope.pending = Pending(handoff, owner, argument, transaction, previous, vsync,
                    invisibleClosingBaseline = true)
                return null
            }
        }
        if (!scope.ready || (handoff.lastSequence != previous && !firstClosingFrame)) {
            if (handoff.waiting != null || handoff.directLease.hasPendingCommit()) {
                return afterDirect(handoff, owner, argument, stock)
            }
            handoff.watchStock(transaction, previous + 1)
            scope.observedStockFrame = true
            return stock()
        }
        val vsync = if (scope.delayedVsync > 0) scope.delayedVsync else readVsync.invoke(Choreographer.getInstance()) as Long
        if (vsync <= 0) {
            mark("OneUI closing direct missing vsync")
            return afterDirect(handoff, owner, argument, stock)
        }
        if (firstClosingFrame) mark("OneUI closing fresh applier has no stock frame to drain")
        val next = handoff.reserveSequence(owner, previous)
        scope.pending = Pending(handoff, owner, argument, transaction, next, vsync)
        return null
    }

    /** Main thread. Marks a stock floating player's frame, which may target the leash owned since release. */
    fun <T> duringPlayerFrame(frame: () -> T): T {
        playerFrames++
        try {
            return frame()
        } finally {
            playerFrames--
        }
    }

    private fun targetsWindow(applier: Any, window: View?): Boolean =
        window != null && (viewRoot.get(applier)?.let { readView.invoke(it) } as? View)?.rootView === window

    /** The worker already owns this close. Fence the applier's release once, then keep every stock pose off the leash. */
    private fun captureEarly(scope: Scope, handoff: Handoff, owner: Any, argument: Any?, stock: () -> Any?): Any? {
        if (!handoff.detached || handoff.disabled || argument == null) return afterDirect(handoff, owner, argument, stock)
        val known = handoff.owner
        if (known != null && known !== owner) {
            mark("OneUI closing early applier changed")
            return afterDirect(handoff, owner, argument, stock)
        }
        val transaction = readTransaction.get(readWrapper.invoke(argument)) as? SurfaceControl.Transaction
            ?: return afterDirect(handoff, owner, argument, stock)
        if (known == null) {
            handoff.owner = owner
            handoff.reserveSequence(owner, sequence.getInt(owner))
            // Stock updates outside a player frame are deferred to the drain as well.
            outstandingDirect[owner] = WeakReference(handoff)
            mark("OneUI closing early applier fenced")
        }
        scope.pending = Pending(handoff, owner, argument, transaction, sequence.getInt(owner), 0L, detachedStock = true)
        return null
    }

    private fun afterDirect(handoff: Handoff, owner: Any, argument: Any?, stock: () -> Any?): Any? {
        if (argument != null && handoff.blocksStock()) {
            handoff.fallBack { sendToStock(owner, argument) }
            return null
        }
        handoff.fallBack()
        return stock()
    }

    private fun sendToStock(owner: Any, argument: Any) {
        val outer = current
        current = null
        try {
            schedule.invoke(owner, argument)
        } finally {
            current = outer
        }
    }

    fun begin(window: View, handoff: Handoff, ready: Boolean, delayedVsync: Long = 0L, player: Any? = null,
        stockApplier: Any? = null, mayOmitStockUpdate: Boolean = true, bindOnStockFrame: Boolean = false,
        openingWindow: LauncherStockWindowUpdater.Window? = null,
        invisibleClosingIcon: Boolean = false,
        onStockSchedule: (() -> Unit)? = null): Scope? {
        if (current != null) return null
        return Scope(window, handoff, ready, delayedVsync, player, stockApplier, mayOmitStockUpdate,
            bindOnStockFrame, openingWindow, invisibleClosingIcon, onStockSchedule).also { current = it }
    }

    fun canOmitStockUpdate(player: Any, applier: Any, view: View): Boolean {
        if (Looper.myLooper() !== main.looper) return false
        val scope = current ?: return false
        val handoff = scope.handoff
        return scope.mayOmitStockUpdate && (!handoff.opening || scope.openingWindow != null) &&
            scope.player === player && scope.pending == null && scope.ready && handoff.detached &&
            handoff.hasSubmittedDetachedFrame && handoff.owner === applier && view.isAttachedToWindow &&
            view.rootView === scope.window && (barrier.get(applier) as? SurfaceControl)?.isValid == true
    }

    fun openingWindowForOmission(): LauncherStockWindowUpdater.Window? = current?.openingWindow

    fun omitStockUpdate(applier: Any, replay: () -> Unit) {
        val scope = checkNotNull(current)
        check(scope.pending == null && scope.handoff.detached)
        scope.pending = Pending(scope.handoff, applier, null, null, sequence.getInt(applier), 0L,
            detachedStock = true, omitted = replay)
    }

    fun end(scope: Scope?): Pending? {
        if (current === scope) current = null
        return scope?.pending.also { if (scope != null) scope.pending = null }
    }

    fun idleForHotReload(): Boolean =
        current == null && draining == null && outstandingDirect.values.none { it.get()?.blocksStock() == true }

    internal class Scope(val window: View, val handoff: Handoff, val ready: Boolean, val delayedVsync: Long,
        val player: Any?, val stockApplier: Any?, val mayOmitStockUpdate: Boolean, val bindOnStockFrame: Boolean,
        val openingWindow: LauncherStockWindowUpdater.Window?,
        val invisibleClosingIcon: Boolean,
        val onStockSchedule: (() -> Unit)?) {
        var pending: Pending? = null
        var observedStockFrame = false
    }

    internal inner class Handoff(val opening: Boolean = false, private val onTimeout: () -> Unit) {
        val directLease = LauncherSurfaceSubmitLease(acknowledge)
        private val ownership = Any()
        private var workerOffer: WorkerOffer? = null
        var owner: Any? = null
        var lastSequence: Int? = null
        var hasInvisibleBaseline = false
        @Volatile var disabled = false
            private set
        @Volatile var detached = false
            private set
        @Volatile var hasDetached = false
            private set
        @Volatile var hasSubmittedDetachedFrame = false
        /** Claimed at release, before any stock frame entered this handoff. */
        @Volatile var early = false
            private set
        var earlyWindow = WeakReference<View>(null)
            private set
        @Volatile var waiting: Pending? = null
            private set
        @Volatile private var drainWorker: WorkerOffer? = null
        private var stockCommit: StockCommit? = null
        private val timeout = Runnable {
            synchronized(ownership) {
                val commit = stockCommit ?: return@synchronized
                if (commit.acknowledged) {
                    onStockCommit(commit)
                } else {
                    mark("OneUI closing drain timed out")
                    fallBack()
                    onTimeout()
                }
            }
        }

        fun blocksStock() = synchronized(ownership) { detached || waiting != null || directLease.hasPendingCommit() }

        fun reserveSequence(applier: Any, previous: Int): Int = synchronized(ownership) {
            val next = previous + 1
            sequence.setInt(applier, next)
            setCanRelease.invoke(applier, false)
            lastSequence = next
            next
        }

        // A newer stock update either sees the worker's ownership or ends the previous offer.
        fun beginStockCapture(): Boolean = synchronized(ownership) {
            if (!detached) workerOffer = null
            detached
        }

        fun offerToWorker(executor: Executor, queuedHasIcon: Boolean?, maySubmitOpeningPair: Boolean,
            onReady: (WorkerOffer) -> Unit): WorkerOffer = synchronized(ownership) {
            check(!disabled && !detached && workerOffer == null)
            WorkerOffer(this, executor, queuedHasIcon, maySubmitOpeningPair, onReady).also { workerOffer = it }
        }

        fun claimWorker(offer: WorkerOffer, publish: () -> Boolean): Boolean = synchronized(ownership) {
            if (disabled || workerOffer !== offer || detached || !publish()) return@synchronized false
            hasDetached = true
            detached = true
            mark(if (opening) "OneUI opening detached claimed off main" else "OneUI closing detached claimed off main")
            true
        }

        fun claimEarly(window: View): Boolean = synchronized(ownership) {
            if (disabled || detached || hasDetached || workerOffer != null || owner != null || waiting != null ||
                directLease.hasPendingCommit()
            ) return@synchronized false
            early = true
            earlyWindow = WeakReference(window)
            earlyClaim = WeakReference(this)
            hasDetached = true
            detached = true
            mark("OneUI closing early claimed")
            true
        }

        fun holdsRelease(offer: WorkerOffer?): Boolean = synchronized(ownership) {
            // A late acknowledgement for the baseline can arrive during the return to stock.
            // After detachment, only the drain callback may release that surface.
            detached || (disabled && hasDetached) || (offer != null && workerOffer === offer)
        }

        fun expireWorkerOffer(offer: WorkerOffer): Boolean = synchronized(ownership) {
            // A newer stock pulse can clear the offer before creating another transaction.
            // The caller also verifies the release sequence, so a superseding frame keeps its own fence.
            if (disabled || detached || (workerOffer != null && workerOffer !== offer)) return@synchronized false
            workerOffer = null
            true
        }

        fun duringTargetReplacement(update: () -> Any?): Any? {
            val prior = targetReplacement
            targetReplacement = this
            try {
                return update()
            } finally {
                targetReplacement = prior
            }
        }

        fun watchStock(transaction: SurfaceControl.Transaction, next: Int): Unit = synchronized(ownership) {
            val commit = StockCommit()
            val handoff = WeakReference(this)
            stockCommit = commit
            lastSequence = next
            transaction.addTransactionCommittedListener(immediate) {
                commit.acknowledged = true
                mark(if (opening) "OneUI opening stock baseline acknowledged" else "OneUI closing stock baseline acknowledged")
                handoff.get()?.dispatchStockCommit(commit)
            }
        }

        private fun dispatchStockCommit(commit: StockCommit) {
            // The compositor callback only signals readiness. The worker checks ownership before applying.
            val offer = drainWorker
            if (offer == null) {
                main.postAtFrontOfQueue { onStockCommit(commit) }
            } else {
                offer.execute { onStockCommit(commit, onWorker = true) }
            }
        }

        private fun onStockCommit(commit: StockCommit, onWorker: Boolean = false): Unit = synchronized(ownership) {
            if (stockCommit !== commit || disabled) return@synchronized
            val pending = waiting
            if (onWorker && (pending == null || pending.submissionWorker() == null || !pending.isNewestSequence())) {
                // Main is between reserving and supplying a newer frame. That frame will drain on arrival.
                main.postAtFrontOfQueue { onStockCommit(commit) }
                return@synchronized
            }
            if (pending == null) {
                stockCommit = null
                return@synchronized
            }
            mark(when {
                opening && onWorker -> "OneUI opening drain submits off main"
                opening -> "OneUI opening drain stock committed"
                onWorker -> "OneUI closing drain submits off main"
                else -> "OneUI closing drain stock committed"
            })
            try {
                pending.submit()
                stockCommit = null
                takeWaiting()
            } catch (error: Exception) {
                if (onWorker) {
                    commit.failure = error
                    main.postAtFrontOfQueue { recoverDrain(commit, error) }
                } else recoverDrain(commit, error)
            }
        }

        private fun recoverDrain(commit: StockCommit, error: Exception): Unit = synchronized(ownership) {
            if (stockCommit !== commit) return@synchronized
            fallBack()
            onTimeout()
            HookEntry.log("LAUNCHER_CLOSING_DRAIN_ERROR ${error.javaClass.simpleName}")
        }

        fun send(pending: Pending): Unit = synchronized(ownership) {
            val applier = checkNotNull(owner)
            outstandingDirect[applier] = WeakReference(this)
            val commit = stockCommit
            if (commit == null) {
                pending.submit()
                return@synchronized
            }
            val previous = waiting
            if (previous == null) {
                draining = this
                main.postDelayed(timeout, STOCK_DRAIN_TIMEOUT_MS)
                mark("OneUI closing drain started")
            } else {
                previous.append(pending)
            }
            waiting = previous ?: pending
            drainWorker = waiting?.submissionWorker()
            val error = commit.failure
            if (error != null) {
                recoverDrain(commit, error)
            } else if (commit.acknowledged) {
                onStockCommit(commit)
            }
        }

        fun fallBack(afterDrain: (() -> Unit)? = null): Unit = synchronized(ownership) {
            if (!disabled) {
                disabled = true
                stockCommit = null
                val heldSequence = synchronized(ownership) {
                    val held = lastSequence.takeIf { detached || workerOffer != null }
                    workerOffer = null
                    detached = false
                    held
                }
                val waiting = takeWaiting()
                val needDrain = directLease.hasPendingCommit()
                if (needDrain) mark("OneUI closing waits for final direct commit")
                directLease.revoke {
                    val applier = owner
                    if (outstandingDirect[applier]?.get() === this) outstandingDirect.remove(applier)
                    waiting?.sendToStock()
                    if (applier != null && heldSequence != null) main.post {
                        // Let any stock update queued by the caller establish its own release fence first.
                        if (sequence.getInt(applier) == heldSequence && outstandingDirect[applier]?.get() == null) {
                            committedDirect[applier] = heldSequence
                            setCanRelease.invoke(applier, true)
                        }
                    }
                    if (needDrain) mark("OneUI closing returned after final direct commit")
                }
            }
            if (afterDrain != null) directLease.revoke(afterDrain)
        }

        private fun takeWaiting(): Pending? {
            main.removeCallbacks(timeout)
            val pending = waiting
            waiting = null
            drainWorker = null
            if (Looper.myLooper() === main.looper) {
                if (draining === this) draining = null
            } else main.post {
                if (draining === this && waiting == null) draining = null
            }
            return pending
        }
    }

    private class StockCommit {
        @Volatile var acknowledged = false
        var failure: Exception? = null
    }

    internal class WorkerOffer(
        private val handoff: Handoff,
        private val executor: Executor,
        val queuedHasIcon: Boolean?,
        val maySubmitOpeningPair: Boolean,
        private val onReady: (WorkerOffer) -> Unit,
    ) {
        @Volatile var queued = false
            private set
        // The commit callback and claimant both execute on the rendering worker.
        private var committed = false

        fun execute(action: () -> Unit) = executor.execute(action)

        fun watch(transaction: SurfaceControl.Transaction) {
            transaction.addTransactionCommittedListener(executor) {
                committed = true
                onReady(this)
            }
        }

        fun onQueued() {
            // The stock window update just entered the same process queue used by the lease.
            // The lease serializes native apply calls and still fences release on actual commits.
            queued = true
            execute { onReady(this) }
        }

        fun claim(publish: () -> Boolean): Boolean = (committed || queued) && handoff.claimWorker(this, publish)
    }

    internal inner class Pending(
        val handoff: Handoff,
        private val owner: Any,
        private val argument: Any?,
        private val transaction: SurfaceControl.Transaction?,
        private var frameSequence: Int,
        private var vsync: Long,
        val detachedStock: Boolean = false,
        private val omitted: (() -> Unit)? = null,
        val stockDraw: Boolean = false,
        val invisibleClosingBaseline: Boolean = false,
    ) {
        val stockVsync get() = vsync
        private var accepted = false
        private var finished = false
        private var updateIcon: ((SurfaceControl.Transaction) -> Unit)? = null
        private var workerOffer: WorkerOffer? = null

        fun offerToWorker(executor: Executor, queuedHasIcon: Boolean? = null, submitOpeningPairOnWorker: Boolean = false,
            onReady: (WorkerOffer) -> Unit): WorkerOffer {
            check(!accepted && !finished && !detachedStock && workerOffer == null)
            return handoff.offerToWorker(
                executor, queuedHasIcon.takeUnless { handoff.opening }, submitOpeningPairOnWorker && handoff.opening, onReady,
            ).also { workerOffer = it }
        }

        fun commit(updateIcon: ((SurfaceControl.Transaction) -> Unit)? = null) {
            check(!accepted && !finished && !detachedStock && !stockDraw && !invisibleClosingBaseline)
            this.updateIcon = updateIcon
            handoff.send(this)
            accepted = true
        }

        fun commitInvisibleClosingBaseline(): Boolean {
            check(invisibleClosingBaseline && !accepted && !finished)
            if (handoff.disabled || handoff.detached || !isNewestSequence()) return false
            frameSequence = handoff.reserveSequence(owner, frameSequence)
            handoff.send(this)
            accepted = true
            handoff.hasInvisibleBaseline = true
            mark("OneUI closing window submitted before icon visible")
            return true
        }

        /** Keep the native ViewRoot merge and its release sequence for the first icon frame. */
        fun completeStockDraw(appendIcon: (SurfaceControl.Transaction) -> Boolean): Boolean {
            check(stockDraw && !finished)
            val transaction = checkNotNull(transaction)
            if (handoff.disabled || sequence.getInt(owner) != frameSequence || !appendIcon(transaction)) return false
            handoff.watchStock(transaction, frameSequence + 1)
            sendToStock()
            if (Trace.isEnabled()) {
                Trace.setCounter("OneUI stock open bound vsync", vsync)
                mark("OneUI opening icon bound in stock frame")
            }
            return true
        }

        fun discardStock() {
            check(detachedStock && handoff.detached && !finished)
            finished = true
            transaction?.close()
        }

        val isOmitted get() = omitted != null

        /** A stock pose computed on the delayed stock clock never reaches a leash owned since release. */
        fun discardIfEarly(): Boolean {
            if (finished || !detachedStock || !handoff.early || !handoff.detached || handoff.disabled) return false
            finished = true
            transaction?.close()
            return true
        }

        fun commitWhileDetached(setCurrentWindow: (SurfaceControl.Transaction) -> Boolean): Boolean {
            check(detachedStock && handoff.detached && !finished)
            val transaction = transaction ?: return false
            if (!setCurrentWindow(transaction) || !handoff.directLease.submit(transaction)) return false
            finished = true
            transaction.close()
            return true
        }

        fun append(newer: Pending) {
            // Preserve stock surface changes and callbacks, with the newest geometry winning.
            val next = checkNotNull(newer.transaction)
            checkNotNull(transaction).merge(next)
            frameSequence = newer.frameSequence
            vsync = newer.vsync
            updateIcon = newer.updateIcon
            workerOffer = newer.workerOffer
            next.close()
        }

        fun isNewestSequence(): Boolean = sequence.getInt(owner) == frameSequence

        // Submission and ownership are distinct. The opening pair still waits for its own commit.
        fun submissionWorker(): WorkerOffer? {
            val hasIcon = updateIcon != null
            return workerOffer?.takeIf { it.queuedHasIcon == hasIcon || (hasIcon && it.maySubmitOpeningPair) }
        }

        fun submit() {
            check(!finished && isNewestSequence())
            val transaction = checkNotNull(transaction)
            val withIcon = updateIcon != null
            updateIcon?.invoke(transaction)
            setVsync.invoke(transaction, vsync)
            val offer = workerOffer
            val readyOnSubmission = offer?.queuedHasIcon == withIcon
            if (!readyOnSubmission) offer?.watch(transaction)
            val submittedVsync = vsync
            val nativeMarker: (() -> Unit)? = if (Trace.isEnabled()) {
                {
                    Trace.setCounter("OneUI direct native committed vsync", submittedVsync)
                    mark("OneUI direct native committed")
                }
            } else null
            val submitted = handoff.directLease.submit(transaction, onNativeCommit = nativeMarker) {
                if (sequence.getInt(owner) == frameSequence) {
                    if (!handoff.holdsRelease(offer)) {
                        committedDirect[owner] = frameSequence
                        setCanRelease.invoke(owner, true)
                    } else if (offer != null && !handoff.detached) {
                        main.postDelayed({
                            if (handoff.expireWorkerOffer(offer) && sequence.getInt(owner) == frameSequence) {
                                committedDirect[owner] = frameSequence
                                setCanRelease.invoke(owner, true)
                            }
                        }, WORKER_OFFER_RELEASE_MS)
                    }
                }
                mark(when {
                    handoff.opening -> "OneUI opening direct pair committed"
                    withIcon -> "OneUI closing direct pair committed"
                    else -> "OneUI closing direct window committed"
                })
                if (Trace.isEnabled()) Trace.setCounter("OneUI direct owner committed vsync", submittedVsync)
            }
            check(submitted)
            finished = true
            updateIcon = null
            transaction.close()
            if (Trace.isEnabled()) {
                Trace.setCounter(if (handoff.opening) "OneUI direct open submitted vsync" else "OneUI direct close submitted vsync", vsync)
                mark(when {
                    handoff.opening -> "OneUI opening direct pair submitted"
                    withIcon -> "OneUI closing direct pair submitted"
                    else -> "OneUI closing direct window submitted"
                })
            }
            if (readyOnSubmission) checkNotNull(offer).onQueued()
        }

        fun restoreStock() {
            if (accepted || finished) return
            if (invisibleClosingBaseline && !handoff.hasInvisibleBaseline && !handoff.disabled &&
                !handoff.directLease.hasPendingCommit() && isNewestSequence()
            ) {
                handoff.watchStock(checkNotNull(transaction), frameSequence + 1)
                sendToStock()
                mark("OneUI closing visible icon requires stock draw")
                return
            }
            if (stockDraw) sendToStock() else handoff.fallBack(::sendToStock)
        }

        fun sendToStock() {
            if (finished) return
            finished = true
            updateIcon = null
            if (omitted == null) {
                this@LauncherDirectClosingFrames.sendToStock(owner, checkNotNull(argument))
            } else {
                if (sequence.getInt(owner) != frameSequence) return
                val outer = current
                current = null
                try {
                    omitted.invoke()
                } finally {
                    current = outer
                }
            }
        }
    }

    private fun mark(name: String) {
        if (!Trace.isEnabled()) return
        Trace.beginSection(name)
        Trace.endSection()
    }

    private companion object {
        val immediate = Executor { it.run() }
        const val STOCK_DRAIN_TIMEOUT_MS = 80L
        const val WORKER_OFFER_RELEASE_MS = 25L
    }
}
