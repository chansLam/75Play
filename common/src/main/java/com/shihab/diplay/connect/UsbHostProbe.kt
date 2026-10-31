// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.fragment.app.FragmentActivity
import com.shihab.diplay.LegacyConnectionProbe
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.DiPlayDialog
import com.shihab.diplay.util.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-demand wired USB host feasibility probe.
 *
 * It does not establish a CarPlay session. It requests USB permission for every attached
 * device through the framework (no root), then runs [LegacyConnectionProbe.usbHostSection],
 * which opens, claims and control-transfers the device, and presents the result in a dialog.
 */
class UsbHostProbe(private val activity: FragmentActivity) {
    private val appContext = activity.applicationContext
    private val running = AtomicBoolean(false)

    fun run() {
        if (!running.compareAndSet(false, true)) return
        val usb = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usb == null) {
            running.set(false)
            showInfo(activity.getString(R.string.usb_probe_no_service))
            return
        }
        Thread({
            try {
                val devices = usb.deviceList.values.toList()
                if (devices.isEmpty()) {
                    showInfo(activity.getString(R.string.usb_probe_no_device))
                    return@Thread
                }
                ensurePermissions(usb, devices)
                val section = LegacyConnectionProbe.usbHostSection(appContext)
                section.lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { AppLog.i("DiPlayUsbProbe", it) }
                post {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        DiPlayDialog.showLongText(
                            activity,
                            activity.getString(R.string.usb_probe_title),
                            section.trim(),
                        )
                    }
                }
            } catch (error: Throwable) {
                AppLog.w("DiPlayUsbProbe", "probe failed", error)
                showInfo(activity.getString(R.string.usb_probe_failed, error.javaClass.simpleName))
            } finally {
                running.set(false)
            }
        }, "usb-host-probe").apply { isDaemon = true }.start()
    }

    /**
     * Framework permission flow. Already-granted devices are skipped; the rest trigger the
     * system USB consent dialog once and the broadcast result is awaited per device.
     */
    private fun ensurePermissions(usb: UsbManager, devices: List<UsbDevice>) {
        val pending = devices.filterNot { usb.hasPermission(it) }
        if (pending.isEmpty()) return

        val action = appContext.packageName + PERMISSION_ACTION_SUFFIX
        val latch = CountDownLatch(pending.size)
        val granted = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != action) return
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    granted.set(true)
                }
                latch.countDown()
            }
        }
        appContext.registerReceiver(receiver, IntentFilter(action))
        try {
            pending.forEach { device ->
                val intent = Intent(action).setPackage(appContext.packageName)
                @Suppress("DEPRECATION")
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    appContext,
                    device.deviceId,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
                usb.requestPermission(device, pendingIntent)
            }
            // The user may take a moment on the consent dialog; do not block forever.
            latch.await(PERMISSION_WAIT_SECONDS, TimeUnit.SECONDS)
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    private fun showInfo(message: String) = post {
        if (!activity.isFinishing && !activity.isDestroyed) {
            DiPlayDialog.showInfo(
                activity,
                activity.getString(R.string.usb_probe_title),
                message,
            )
        }
    }

    private fun post(action: () -> Unit) {
        activity.runOnUiThread(action)
    }

    companion object {
        private const val PERMISSION_ACTION_SUFFIX = ".action.USB_PROBE_PERMISSION"
        private const val PERMISSION_WAIT_SECONDS = 60L
    }
}
