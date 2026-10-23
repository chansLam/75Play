package com.shihab.diplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shihab.diplay.util.AppLog

/** Starts the CarPlay host after boot when the user has enabled the startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, launchEnabled)
        if (!launchEnabled) return

        // enable() sleeps while STA drops. goAsync keeps the process alive off the main thread.
        val pending = goAsync()
        Thread({
            try {
                bringUpBootHotspot(context)
                launchShell(context)
            } finally {
                pending.finish()
            }
        }, "diplay-boot").apply { isDaemon = true }.start()
    }

    private fun bringUpBootHotspot(context: Context) {
        // Wireless CarPlay needs the unit's own hotspot up before the iPhone can join. KitKat lets a
        // normal app enable the saved hotspot config, so do it right at boot instead of waiting for
        // the user to open the car's tethering settings.
        if (AirPlayPersistence.loadWirelessEnabled(context) &&
            AirPlayPersistence.loadWirelessHotspotMode(context) ==
            com.shihab.diplay.orchestration.WirelessHotspotMode.MANUAL &&
            com.shihab.diplay.network.CarHotspotStatus.isEnabled(context) == false
        ) {
            val ssid = AirPlayPersistence.loadManualHotspotSsid(context).ifBlank { null }
            val pass = AirPlayPersistence.loadManualHotspotPassphrase(context)
            val result = com.shihab.diplay.network.CarHotspotEnable.enable(
                context = context,
                ssid = ssid,
                passphrase = pass.takeIf { ssid != null },
                waitUntilReadyMs = 0L,
            )
            AppLog.i(TAG, "boot hotspot enable ${result.diagnostic} ok=${result.ok}")
        }
    }

    private fun launchShell(context: Context) {
        val launch = Intent(context, DiPlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(launch)
            StartupDiagnosticSnapshot.launchResult(context)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.launchResult(context, error)
            AppLog.w(TAG, "Boot auto-start could not launch DiPlayActivity", error)
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
    }
}
