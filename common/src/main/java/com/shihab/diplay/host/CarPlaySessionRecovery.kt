package com.shihab.diplay.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.CarPlayHostActivity
import com.shihab.diplay.CarPlayConnectService
import com.shihab.diplay.DiPlayPreferences
import com.shihab.diplay.connect.ConnectBroadcasts
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-level watcher that re-establishes a CarPlay link after the iPhone disappears and comes
 * back (ignition off/on at quick resume, USB power glitch, or a wireless hotspot drop), without the
 * driver having to tap connect.
 *
 * Wired: combines the USB ATTACH broadcast with a low-frequency device-list poll (quick-resume
 * does not always deliver an attach broadcast) and requires an iPhone to be present.
 * Wireless: the iPhone is the active initiator once the hotspot is back, so the poll re-triggers
 * the full bring-up (hotspot/Bonjour/link) without requiring a USB device; the connect service's
 * own in-flight gate and retry budget provide the throttling.
 *
 * A recovery runs only when auto-connect is enabled and no healthy session exists. Hosted by
 * [com.shihab.diplay.DiPlaySessionService], which stays foreground for the session.
 */
internal class CarPlaySessionRecovery(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val iphoneHost = IphoneUsbHost(
        appContext,
        usbManager,
        IphoneUsbMatcher.appleVendor(),
        onDiagnostic = { },
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var attachHandle: java.io.Closeable? = null
    private var detachHandle: java.io.Closeable? = null
    @Volatile private var pollThread: Thread? = null
    private var connectedReceiver: BroadcastReceiver? = null
    // Tracks USB enumeration, not the wireless preference. DETACHED is unreliable on this head
    // unit, and a saved wireless preference must not hide an unplug of an auto-detected wired link.
    @Volatile private var usbPresentLastPoll = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        attachHandle = runCatching {
            iphoneHost.registerAttachReceiver { scheduleRecoveryCheck(ATTACH_DEBOUNCE_MILLIS) }
        }.getOrNull()
        // Physical cable unplug: do not wait for the read watchdog (8-10s) on a frozen host.
        // Abort the in-flight link and return to the shell immediately.
        detachHandle = runCatching {
            iphoneHost.registerDetachReceiver { onIphoneDetached() }
        }.getOrNull()
        // Reopen the CarPlay host automatically as soon as a background recovery succeeds, so the
        // driver never has to tap through the shell. singleTask reuses an existing host instance.
        connectedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ConnectBroadcasts.ACTION_CONNECTED) return
                if (!DiPlayPreferences.autoConnect(appContext)) return
                openCarPlayHost()
            }
        }.also { receiver ->
            runCatching {
                appContext.registerReceiver(
                    receiver,
                    IntentFilter(ConnectBroadcasts.ACTION_CONNECTED),
                )
            }
        }
        Thread({
            while (running.get()) {
                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS)
                    if (running.get()) runRecoveryCheck()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "carplay-recovery-poll").apply {
            isDaemon = true
            pollThread = this
        }.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { attachHandle?.close() }
        attachHandle = null
        runCatching { detachHandle?.close() }
        detachHandle = null
        pollThread?.interrupt()
        pollThread = null
        connectedReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        connectedReceiver = null
        mainHandler.removeCallbacks(checkRunnable)
    }

    private val checkRunnable = Runnable { runRecoveryCheck() }

    private fun scheduleRecoveryCheck(delayMillis: Long) {
        mainHandler.removeCallbacks(checkRunnable)
        mainHandler.postDelayed(checkRunnable, delayMillis)
    }

    private fun runRecoveryCheck() {
        if (!running.get()) return
        // Respect the user's auto-connect choice.
        if (!DiPlayPreferences.autoConnect(appContext)) return
        val wireless = AirPlayPersistence.loadWirelessEnabled(appContext)
        val usbPresent = iphoneHost.discover().isNotEmpty()
        val snapshot = CarPlayBackgroundSession.snapshot()
        if (
            shouldDropLinkAfterUsbLoss(
                usbWasPresent = usbPresentLastPoll,
                usbPresent = usbPresent,
                wirelessPreferred = wireless,
                sessionOrConnectActive = snapshot != null || CarPlayConnectService.connecting,
                wiredLinkActive = snapshot?.controller?.isWiredLink() == true,
            )
        ) {
            usbPresentLastPoll = false
            onIphoneDetached()
            return
        }
        usbPresentLastPoll = usbPresent

        // A healthy session (or an in-flight connect) already exists: nothing to recover.
        if (CarPlayConnectService.connecting) return
        if (snapshot != null) return
        // The previous controller can still own USB after its snapshot is cleared.
        if (CarPlayBackgroundSession.isReleasing()) return
        // Wired requires an iPhone to be present; wireless bring-up creates its own network and
        // is triggered unconditionally (the iPhone connects back once the hotspot is up).
        if (!shouldStartRecovery(wireless, usbPresent)) return
        startConnectService()
    }

    private fun onIphoneDetached() {
        if (!running.get()) return
        usbPresentLastPoll = false
        android.util.Log.i("CarPlayRecovery", "iPhone gone; returning to shell and closing session")
        // The service remains alive briefly after the handshake while the host adopts its
        // controller. "connecting" is already false in that window, but it still needs the
        // link-loss action so teardown cannot strand a foreground service and block reinsertion.
        if (CarPlayConnectService.running) {
            runCatching {
                val cancel = Intent(appContext, CarPlayConnectService::class.java)
                    .setAction(CarPlayConnectService.ACTION_LINK_LOST)
                appContext.startService(cancel)
            }
        }
        // Hand the UI back to the shell first so the host finishes and releases its owner claim.
        FloatingBall.backToDiPlay(appContext)
        // Once the host has parked, close through its registered stop action. forceReset() only
        // drops bookkeeping and can leak native NCM/USB resources.
        mainHandler.postDelayed({
            runCatching { CarPlayBackgroundSession.stop() }
        }, RESET_AFTER_DETACH_MILLIS)
    }

    private fun startConnectService() {
        val intent = Intent(appContext, CarPlayConnectService::class.java)
            .setAction(CarPlayConnectService.ACTION_START)
        runCatching { appContext.startService(intent) }
    }

    private fun openCarPlayHost() {
        val intent = Intent(appContext, CarPlayHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 3_000L
        const val ATTACH_DEBOUNCE_MILLIS = 1_500L
        const val RESET_AFTER_DETACH_MILLIS = 300L
    }
}
