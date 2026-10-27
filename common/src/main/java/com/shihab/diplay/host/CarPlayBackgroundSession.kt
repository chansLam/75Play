package com.shihab.diplay.host

import com.shihab.diplay.CarPlayHostActivity
import com.shihab.diplay.media.AndroidMediaSink
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.session.CarPlaySessionDisplay

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
    /** Owner after the host Activity has left. The controller stays up for the next host. */
    private val parkedOwner = Any()

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        val currentDisplay = display ?: return null
        return Snapshot(currentController, currentSink, width, height, currentDisplay)
    }

    @Synchronized
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int,
        owner: Any, display: CarPlaySessionDisplay, stop: (() -> Unit) -> Unit) {
        this.stopAction = stop
        this.owner = owner
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.display = display
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
    }

    /**
     * The host Activity is going away. Keep the controller so the next host can adopt it,
     * but this Activity must not stay the owner or reconnect on the shared radio.
     * Shell disconnect still closes the controller through the replacement stop action.
     */
    @Synchronized
    fun park(previousOwner: Any) {
        if (owner !== previousOwner) return
        val current = controller ?: return
        owner = parkedOwner
        stopAction = { completion ->
            runCatching { current.close() }
            clear(current)
            completion()
        }
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
            pending
        }
        callbacks.forEach { runCatching { it() } }
    }
}
