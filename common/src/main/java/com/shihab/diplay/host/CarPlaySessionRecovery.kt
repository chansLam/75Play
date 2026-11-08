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
    @Volatile private var pollThread: Thread? = null
    private var connectedReceiver: BroadcastReceiver? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        attachHandle = runCatching {
            iphoneHost.registerAttachReceiver { scheduleRecoveryCheck(ATTACH_DEBOUNCE_MILLIS) }
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
        // A healthy session (or an in-flight connect) already exists: nothing to recover.
        if (CarPlayConnectService.connecting) return
        if (CarPlayBackgroundSession.snapshot() != null) return
        // Wired requires an iPhone to be present; wireless bring-up creates its own network and
        // is triggered unconditionally (the iPhone connects back once the hotspot is up).
        if (!AirPlayPersistence.loadWirelessEnabled(appContext) &&
            iphoneHost.discover().isEmpty()
        ) {
            return
        }
        startConnectService()
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
    }
}
