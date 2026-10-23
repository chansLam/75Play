package com.shihab.diplay.orchestration

import com.shihab.diplay.airplay.AirPlayListenerIdentity
import com.shihab.diplay.iap2.session.Iap2Session
import com.shihab.diplay.network.CarPlayBonjour
import com.shihab.diplay.network.CarPlayWirelessService
import com.shihab.diplay.network.WirelessHotspotManager
import com.shihab.diplay.network.WirelessStartupDiagnostics
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tears down the wireless link in the same order as the former controller method.
 * The controller keeps a private closeWirelessStack so reflection tests still find that signature.
 * The wireless resource lock stays owned by the controller.
 */
internal object WirelessStackCloser {
    fun close(
        service: CarPlayWirelessService?,
        generation: Int?,
        wirelessResourceLock: Any,
        wirelessGeneration: AtomicInteger,
        watchdog: () -> FirstTcpWatchdog?,
        takeDiagnostics: () -> WirelessStartupDiagnostics?,
        clearConnectionProof: () -> Unit,
        clearTunnelHandler: () -> Unit,
        takeTunnel: () -> Iap2Session?,
        closeBluetoothBootstrap: () -> Unit,
        takeBonjour: () -> CarPlayBonjour?,
        takeHotspot: () -> WirelessHotspotManager?,
        clearIdentification: () -> Unit,
        wirelessHandoffRequested: AtomicBoolean,
        wirelessTunnelReady: AtomicBoolean,
        wirelessActiveReported: AtomicBoolean,
        wirelessHandoffFellBack: AtomicBoolean,
        closeBestEffort: (String, () -> Unit) -> Unit,
    ) {
        synchronized(wirelessResourceLock) {
            if (generation != null && generation != wirelessGeneration.get()) return
            val owner: AirPlayListenerIdentity? = watchdog()?.listener
            watchdog()?.terminate()
            takeDiagnostics()?.close()
            clearConnectionProof()
            clearTunnelHandler()
            takeTunnel()?.let { closeBestEffort("tunneled iAP2 link") { it.close() } }
            closeBluetoothBootstrap()
            takeBonjour()?.let { closeBestEffort("Bonjour") { it.close() } }
            takeHotspot()?.let { closeBestEffort("wireless hotspot") { it.close() } }
            clearIdentification()
            wirelessHandoffRequested.set(false)
            wirelessTunnelReady.set(false)
            wirelessActiveReported.set(false)
            wirelessHandoffFellBack.set(false)
            if (service != null && owner != null) {
                closeBestEffort("AirPlay service") { service.detachWireless(owner) }
            }
        }
    }
}
