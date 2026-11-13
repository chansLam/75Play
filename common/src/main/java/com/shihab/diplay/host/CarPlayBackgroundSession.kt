package com.shihab.diplay.host

import com.shihab.diplay.CarPlayHostActivity
import com.shihab.diplay.media.AndroidMediaSink
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.session.CarPlaySessionDisplay
import java.util.concurrent.atomic.AtomicBoolean

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
internal object CarPlayBackgroundSession {
    @Volatile var active = false
    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var stopping = false
    private var owner: Any? = null
    @Synchronized fun isOwner(candidate: Any): Boolean = owner === candidate
    /** True while a session exists or teardown is in flight (waiters / cancel coordination). */
    @Synchronized fun hasSession(): Boolean = stopAction != null || stopping
    /** True only during [stop] teardown — UI must not treat this as still connected. */
    @Synchronized fun isStopping(): Boolean = stopping
    private val stopWaiters = mutableListOf<() -> Unit>()

    fun stop(completion: () -> Unit = {}) {
        val action: (((() -> Unit)) -> Unit)?
        synchronized(this) {
            if (stopping) { stopWaiters.add(completion); return }
            action = stopAction
            if (action != null) { stopping = true; stopWaiters.add(completion) }
        }
        if (action == null) { completion(); return }
        action.invoke {
            val callbacks = synchronized(this) {
                stopping = false
                stopWaiters.toList().also { stopWaiters.clear() }
            }
            callbacks.forEach { it() }
        }
    }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val display: CarPlaySessionDisplay,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0
    private var display: CarPlaySessionDisplay? = null
    /**
     * The user already sent the cluster map to the dash during this connection.
     * Survives the host leaving for the shell. A full session clear drops it.
     */
    @Volatile var clusterMapHeld = false
    /** Owner after the host Activity has left. The controller stays up for the next host. */
    private val parkedOwner = Any()
    /** Shell callback after a parked session ends and its radio has been released. */
    @Volatile var onParkedSessionLost: (() -> Unit)? = null
    private var parkedLostOwner: Any? = null
    private val releasingParked = AtomicBoolean(false)

    /** True until the parked controller has finished closing and released USB. */
    fun isReleasing(): Boolean = releasingParked.get()

    /** Registers [callback] for [owner]. A later owner replaces it; an older owner cannot clear it. */
    fun setParkedSessionLost(owner: Any, callback: (() -> Unit)?) {
        synchronized(this) {
            parkedLostOwner = owner
            onParkedSessionLost = callback
        }
    }

    fun clearParkedSessionLost(owner: Any) {
        synchronized(this) {
            if (parkedLostOwner !== owner) return
            parkedLostOwner = null
            onParkedSessionLost = null
        }
    }

    /**
     * The parked controller's session ended. Close it once so the Direct group is released,
     * wait for radio teardown, then tell the shell it may start a new connection.
     * A host that has already adopted the session is left alone.
     */
    fun releaseParked(reason: String) {
        val closing = synchronized(this) {
            if (owner !== parkedOwner || stopping || controller == null || stopAction == null) return
            if (!releasingParked.compareAndSet(false, true)) return
            controller
        } ?: run {
            releasingParked.set(false)
            return
        }
        android.util.Log.i("xcertplay", "parked session release: $reason")
        stop {
            Thread({
                // The registered stop action may already have been cleared. Close here so USB
                // cannot outlive the bookkeeping. close() is idempotent when the action ran.
                runCatching { closing.close() }
                runCatching { closing.awaitClosed(PARKED_CLOSE_TIMEOUT_MILLIS) }
                releasingParked.set(false)
                val callback = onParkedSessionLost
                if (callback != null) runCatching { callback() }
            }, "diplay-parked-await").apply { isDaemon = true }.start()
        }
    }

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        val currentDisplay = display ?: return null
        return Snapshot(currentController, currentSink, width, height, currentDisplay)
    }

    @Synchronized
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int,
        owner: Any, display: CarPlaySessionDisplay, stop: (() -> Unit) -> Unit): Boolean {
        if (stopping || releasingParked.get()) return false
        this.stopAction = stop
        this.owner = owner
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.display = display
        return true
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null, keepOwner: Boolean = false) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        if (!keepOwner) { stopAction = null; owner = null }
        active = false
        width = 0
        height = 0
        display = null
        if (!keepOwner) clusterMapHeld = false
    }

    /**
     * The host Activity is going away. Keep the controller so the next host can adopt it,
     * but this Activity must not stay the owner or reconnect on the shared radio.
     * Shell disconnect still closes the controller through the replacement stop action.
     */
    fun park(previousOwner: Any, sessionLostReason: String? = null) {
        val parked = synchronized(this) {
            if (owner !== previousOwner) return@synchronized false
            val current = controller ?: return@synchronized false
            owner = parkedOwner
            stopAction = { completion ->
                runCatching { current.close() }
                clear(current)
                completion()
            }
            true
        }
        // A loss callback can arrive before the Activity leaves. Park first, then consume that
        // already-delivered event instead of waiting for ParkedSessionListener to replay it.
        if (parked && sessionLostReason != null) releaseParked(sessionLostReason)
    }

    /**
     * Drop session bookkeeping immediately (UI cancel path). Does not close the controller;
     * the connect service still tears that down. A host that already adopted the session is
     * left alone. Clears [stopping] so [hasSession] is false before an async [stop] finishes,
     * and runs anyone already waiting on [stop].
     */
    fun forceReset() {
        val callbacks = synchronized(this) {
            if (owner is CarPlayHostActivity) return
            val pending = stopWaiters.toList()
            stopWaiters.clear()
            stopAction = null
            stopping = false
            controller = null
            sink = null
            owner = null
            active = false
            width = 0
            height = 0
            display = null
            clusterMapHeld = false
            pending
        }
        callbacks.forEach { runCatching { it() } }
    }

    private const val PARKED_CLOSE_TIMEOUT_MILLIS = 4_000L
}
