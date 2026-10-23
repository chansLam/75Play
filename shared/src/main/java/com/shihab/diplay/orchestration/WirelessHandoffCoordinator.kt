package com.shihab.diplay.orchestration

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wireless Bluetooth→tunnel iAP2 handoff / watchdog coordination extracted from [CarPlayController].
 *
 * Lock ownership stays with the controller: [wirelessResourceLock] and field access are injected so
 * call order, thread names, and timeout semantics are unchanged.
 */
internal object WirelessHandoffCoordinator {
    private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L

    fun onTunnelReady(
        generation: Int,
        wirelessResourceLock: Any,
        closed: () -> Boolean,
        isWirelessPhase: () -> Boolean,
        wirelessGeneration: AtomicInteger,
        wirelessFailureReported: AtomicBoolean,
        wirelessTunnelReady: AtomicBoolean,
        wirelessHandoffRequested: AtomicBoolean,
        markAuthenticated: (Int) -> Unit,
        debugLog: (String) -> Unit,
        maybeComplete: () -> Unit,
    ) {
        synchronized(wirelessResourceLock) {
            if (closed() || !isWirelessPhase() || generation != wirelessGeneration.get() ||
                wirelessFailureReported.get()) return
            wirelessTunnelReady.set(true)
        }
        markAuthenticated(generation)
        debugLog("wireless iAP2 tunnel ready; handoffRequested=${wirelessHandoffRequested.get()}")
        maybeComplete()
    }

    /** One owner releases Bluetooth, including when a rendered-video fallback gains a tunnel later. */
    fun maybeComplete(
        wirelessResourceLock: Any,
        closed: () -> Boolean,
        isWirelessPhase: () -> Boolean,
        wirelessFailureReported: AtomicBoolean,
        wirelessHandoffRequested: AtomicBoolean,
        wirelessTunnelReady: AtomicBoolean,
        wirelessActiveReported: AtomicBoolean,
        wirelessHandoffFellBack: AtomicBoolean,
        wirelessGeneration: AtomicInteger,
        debugLog: (String) -> Unit,
        closeBluetoothBootstrapTransport: () -> Unit,
        onWirelessActive: (Int) -> Unit,
    ) {
        val generation = synchronized(wirelessResourceLock) {
            if (closed() || !isWirelessPhase() || wirelessFailureReported.get() ||
                !wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
            val firstHandoff = wirelessActiveReported.compareAndSet(false, true)
            val promotesFallback = wirelessHandoffFellBack.compareAndSet(true, false)
            if (!firstHandoff && !promotesFallback) return
            wirelessGeneration.get()
        }
        Thread(
            {
                synchronized(wirelessResourceLock) {
                    // A queued completion must not close a replacement generation's bootstrap.
                    if (closed() || !isWirelessPhase() || generation != wirelessGeneration.get() ||
                        wirelessFailureReported.get() || !wirelessTunnelReady.get()) return@Thread
                    debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                    closeBluetoothBootstrapTransport()
                    onWirelessActive(generation)
                }
            },
            "xcertplay-wireless-handoff",
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun keepBluetoothControlAlive(
        generation: Int,
        wirelessResourceLock: Any,
        closed: () -> Boolean,
        isWirelessPhase: () -> Boolean,
        wirelessGeneration: AtomicInteger,
        wirelessFailureReported: AtomicBoolean,
        hasActiveSession: () -> Boolean,
        hasRenderedFrame: (Int) -> Boolean,
    ): Boolean = synchronized(wirelessResourceLock) {
        !closed() && isWirelessPhase() && generation == wirelessGeneration.get() &&
            !wirelessFailureReported.get() && hasActiveSession() &&
            hasRenderedFrame(generation)
    }

    fun armWatchdog(
        generation: Int,
        postDelayed: (Runnable, Long) -> Unit,
        closed: () -> Boolean,
        isWirelessPhase: () -> Boolean,
        wirelessGeneration: AtomicInteger,
        wirelessHandoffRequested: AtomicBoolean,
        wirelessActiveReported: AtomicBoolean,
        debugLog: (String) -> Unit,
        onTimeout: (Int) -> Unit,
    ) {
        postDelayed(
            Runnable {
                if (
                    closed() ||
                    !isWirelessPhase() ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@Runnable
                }
                debugLog("wireless handoff timed out waiting for tunnel iAP2 readiness")
                Thread(
                    { onTimeout(generation) },
                    "xcertplay-wireless-handoff-timeout",
                ).apply {
                    isDaemon = true
                    start()
                }
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    fun handleTimeout(
        generation: Int,
        wirelessResourceLock: Any,
        closed: () -> Boolean,
        isWirelessPhase: () -> Boolean,
        wirelessGeneration: AtomicInteger,
        wirelessFailureReported: AtomicBoolean,
        wirelessHandoffRequested: AtomicBoolean,
        wirelessActiveReported: AtomicBoolean,
        wirelessTunnelReady: AtomicBoolean,
        wirelessHandoffFellBack: AtomicBoolean,
        hasActiveSession: () -> Boolean,
        hasRenderedFrame: (Int) -> Boolean,
        debugLog: (String) -> Unit,
        onWirelessActiveFallback: (Int) -> Unit,
        onHardFail: (Int) -> Unit,
    ) = synchronized(wirelessResourceLock) {
        if (closed() || !isWirelessPhase() || generation != wirelessGeneration.get() ||
            wirelessFailureReported.get() ||
            !wirelessHandoffRequested.get() || wirelessActiveReported.get() || wirelessTunnelReady.get()) {
            return@synchronized
        }
        // Session establishment alone also occurs on black-screen failures. Only a rendered
        // frame proves that projection is usable when the optional tunneled control is absent.
        if (hasActiveSession() && hasRenderedFrame(generation)) {
            if (!wirelessActiveReported.compareAndSet(false, true)) return@synchronized
            wirelessHandoffFellBack.set(true)
            // The Bluetooth link is the only iAP2 channel here. Closing it would stop NowPlaying,
            // route guidance and call updates for the rest of the session, so keep it until the
            // tunnel really takes over (see maybeComplete).
            debugLog("wireless handoff fallback after rendered video; tunnel iAP2 unavailable; " +
                "preserving AirPlay and keeping the Bluetooth bootstrap for iAP2 control")
            onWirelessActiveFallback(generation)
        } else {
            onHardFail(generation)
        }
    }
}
