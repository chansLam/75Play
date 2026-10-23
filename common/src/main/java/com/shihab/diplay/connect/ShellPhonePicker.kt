// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.os.Build
import androidx.fragment.app.FragmentActivity
import com.shihab.diplay.ActiveSessionLog
import com.shihab.diplay.AsyncDiagnosticLog
import com.shihab.diplay.DiPlayPreferences
import com.shihab.diplay.compat.BluetoothCompat
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.DiPlayDialog
import java.util.Locale
import java.util.concurrent.Executors
import com.shihab.diplay.util.AppLog

/**
 * 壳层选 iPhone：混合蓝牙列表、已连接配置、手动 MAC。
 * 选中的地址仍是无线栈要用的那一台。
 */
class ShellPhonePicker(
    private val activity: FragmentActivity,
) {
    data class Phone(
        val address: String,
        val name: String,
        val isConnected: Boolean,
    )

    private val executor = Executors.newSingleThreadExecutor()

    /** 连接流程在等用户选机。选完后由调用方决定是否立刻 `connect(true)`。 */
    var pendingWireless = false

    /** 车机蓝牙若被关闭，后台自动打开，保证 CarPlay 无线连接可用。 */
    fun ensureEnabledAsync() {
        executor.execute {
            runCatching {
                val adapter = BluetoothCompat.getAdapter(activity)
                if (adapter != null && !adapter.isEnabled) adapter.enable()
            }
        }
    }

    fun runOffMain(block: () -> Unit) {
        executor.execute(block)
    }

    /** The Bluetooth device that is currently connected (from the hybrid HSAE stack CarPlay uses). */
    fun currentlyConnected(): Phone? = runCatching {
        // Multiple devices can be connected at once (A2DP/HFP split). Prefer an already-connected
        // iPhone; fall back to the first connected device, then the adapter's own pick.
        val hybrid = com.shihab.diplay.bluetooth.CarPlayBluetoothManager.get(activity)
        val connected = hybrid.getPairedDevices().filter { it.isConnected }
        val iphone = connected.firstOrNull { it.name.contains("iPhone", ignoreCase = true) }
        val pick = iphone ?: connected.firstOrNull() ?: hybrid.getConnectedDevice()
        pick?.let { Phone(it.address.trim().uppercase(Locale.US), it.name, true) }
    }.getOrNull()

    /**
     * 无上次连接设备时：若车机已配对的设备恰好只有一个，则返回它用于自动连接；
     * 配对为空或存在多个配对设备都返回 null（由调用方弹窗让用户选择）。
     * 不按设备名判断是否为 iPhone（用户可能改名，按名字判断会失效）。
     */
    fun uniquePaired(): Phone? {
        val adapter = BluetoothCompat.getAdapter(activity)
        val devices = fetchDevices(adapter)
        return if (devices.size == 1) devices.single() else null
    }

    fun connectedDisplayName(): String {
        var name = currentlyConnected()?.name?.takeIf { it.isNotBlank() } ?: ""
        if (name.isEmpty()) {
            // WiFi-direct / bootstrap mode may not keep a persistent A2DP "connected" state
            // (isConnected is false even though the iPhone is reachable), so fall back to a
            // paired iPhone, then any paired device, so the startup card shows a name instead
            // of the picker hint.
            val paired = runCatching {
                com.shihab.diplay.bluetooth.CarPlayBluetoothManager.get(activity).getPairedDevices()
            }.getOrNull().orEmpty()
            name = paired.firstOrNull { it.name.contains("iPhone", ignoreCase = true) }?.name.orEmpty()
            if (name.isEmpty()) name = paired.firstOrNull()?.name.orEmpty()
        }
        if (name.isEmpty()) name = DiPlayPreferences.phoneName(activity)?.takeIf { it.isNotBlank() }.orEmpty()
        runCatching {
            AsyncDiagnosticLog.append(
                ActiveSessionLog.current,
                "BluetoothConnected: name=$name",
            )
        }
        return name
    }

    @SuppressLint("MissingPermission")
    fun choose(onSaved: (startWireless: Boolean) -> Unit) {
        val adapter = BluetoothCompat.getAdapter(activity)
        if (adapter != null && !adapter.isEnabled) {
            runCatching { adapter.enable() }
        }
        executor.execute {
            val devices = fetchDevices(adapter)
            activity.runOnUiThread {
                if (!alive()) return@runOnUiThread
                if (devices.isNotEmpty()) {
                    showDialog(devices, onSaved)
                } else {
                    promptManual(onSaved)
                }
            }
        }
    }

    /**
     * Phone picker sources (merged):
     * 1. Hybrid / HSAE vendor paired list (CS75 often keeps the iPhone here only)
     * 2. Android bonded + GATT + A2DP/HEADSET connected devices
     */
    @SuppressLint("MissingPermission")
    private fun fetchDevices(adapter: BluetoothAdapter?): List<Phone> {
        val result = LinkedHashMap<String, Phone>()

        fun put(addr: String?, name: String?, connected: Boolean) {
            val key = addr?.trim()?.uppercase(Locale.US) ?: return
            if (key.isEmpty() || !BluetoothAdapter.checkBluetoothAddress(key)) return
            val existing = result[key]
            val resolvedName = name?.takeIf { it.isNotBlank() }
                ?: existing?.name
                ?: activity.getString(R.string.paired_device)
            result[key] = Phone(
                address = key,
                name = resolvedName,
                isConnected = connected || existing?.isConnected == true,
            )
        }

        runCatching {
            val hybrid = com.shihab.diplay.bluetooth.CarPlayBluetoothManager.get(activity)
            for (device in hybrid.getPairedDevices()) {
                put(device.address, device.name, device.isConnected)
            }
            hybrid.getConnectedDevice()?.let { put(it.address, it.name, true) }
        }.onFailure { AppLog.w("DiPlay", "hybrid Bluetooth phone list failed", it) }

        if (adapter != null) {
            val bonded = runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            for (device in bonded) {
                put(device.address, device.name, isDeviceConnected(device))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                for (device in BluetoothCompat.getConnectedGattDevices(activity)) {
                    put(device.address, device.name, true)
                }
            }
            runCatching {
                for (device in queryConnectedProfileDevices(adapter)) {
                    put(device.address, device.name, true)
                }
            }
        }

        return result.values.sortedWith(
            compareByDescending<Phone> { it.isConnected }
                .thenByDescending { it.name.contains("iPhone", ignoreCase = true) }
                .thenBy { it.name },
        )
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (_: Throwable) {
        false
    }

    private fun queryConnectedProfileDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> {
        val connected = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val latch = java.util.concurrent.CountDownLatch(2)

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    proxy.connectedDevices?.let { connected.addAll(it) }
                } catch (t: Throwable) {
                    AppLog.w("ShellPhonePicker", "read connectedDevices on profile $profile failed", t)
                } finally {
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                latch.countDown()
            }
        }

        val hasHeadset = runCatching {
            adapter.getProfileProxy(activity, listener, BluetoothProfile.HEADSET)
        }.getOrDefault(false)
        if (!hasHeadset) latch.countDown()

        val hasA2dp = runCatching {
            adapter.getProfileProxy(activity, listener, BluetoothProfile.A2DP)
        }.getOrDefault(false)
        if (!hasA2dp) latch.countDown()

        runCatching { latch.await(600, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return synchronized(connected) { connected.toSet() }
    }

    private fun showDialog(devices: List<Phone>, onSaved: (startWireless: Boolean) -> Unit) {
        val manualLabel = activity.getString(R.string.manual_input_mac)
        val options = devices.map { device ->
            val status = if (device.isConnected) " · ${activity.getString(R.string.bluetooth_device_connected)}" else ""
            val duplicate = devices.count { it.name == device.name } > 1
            val disambiguation = if (duplicate) " (${device.address.takeLast(5)})" else ""
            "${device.name}$disambiguation$status"
        } + manualLabel
        val savedAddr = DiPlayPreferences.phoneAddress(activity)?.trim()?.uppercase(Locale.US)
        val connectedIdx = devices.indexOfFirst { it.isConnected }
        val savedIdx = devices.indexOfFirst { it.address == savedAddr }
        val current = when {
            connectedIdx >= 0 -> connectedIdx
            savedIdx >= 0 -> savedIdx
            else -> 0
        }
        DiPlayDialog.showSingleChoice(
            activity,
            activity.getString(R.string.choose_your_iphone),
            options,
            current,
        ) { index ->
            if (index == devices.size) {
                promptManual(onSaved)
            } else {
                val device = devices[index]
                DiPlayPreferences.savePhone(activity, device.address, device.name)
                finishPick(onSaved)
            }
        }
    }

    private fun promptManual(onSaved: (startWireless: Boolean) -> Unit) {
        DiPlayDialog.showInput(
            activity,
            activity.getString(R.string.choose_your_iphone),
            "00:11:22:33:44:55",
            DiPlayPreferences.phoneAddress(activity) ?: "",
            assist = activity.getString(R.string.manual_bluetooth_input_mac_label),
            save = activity.getString(R.string.save_details),
            validator = { mac ->
                if (!BluetoothAdapter.checkBluetoothAddress(mac.trim().uppercase(Locale.US)))
                    activity.getString(R.string.manual_bluetooth_input_invalid_mac) else null
            },
            onSave = { mac ->
                val raw = mac.trim().uppercase(Locale.US)
                DiPlayPreferences.savePhone(activity, raw, "iPhone")
                finishPick(onSaved)
            },
        )
    }

    private fun finishPick(onSaved: (startWireless: Boolean) -> Unit) {
        val start = pendingWireless
        pendingWireless = false
        onSaved(start)
    }

    private fun alive(): Boolean =
        !activity.isFinishing && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed)
}
