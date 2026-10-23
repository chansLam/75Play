package com.shihab.diplay

import android.content.Context
import com.shihab.diplay.bluetooth.BluetoothAddressResolver
import com.shihab.diplay.bluetooth.CarPlayBluetoothManager
import com.shihab.diplay.util.AppLog

internal object DiPlayBluetooth {
    private const val TAG = "DiPlayBluetooth"

    /**
     * Local head-unit Bluetooth MAC for CarPlay audio routing and Settings display.
     *
     * Prefer the hybrid adapter (vendor HSAE / Android) — same path [CarPlayController] uses —
     * then fall back to the multi-source [BluetoothAddressResolver].
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun localAddress(context: Context): String? {
        val custom = AirPlayPersistence.loadCustomBluetoothMac(context)
        BluetoothAddressResolver.normalize(custom)?.let { return it }
        val hybrid = runCatching {
            CarPlayBluetoothManager.get(context).localAddress(context)
        }.getOrNull()
        BluetoothAddressResolver.normalize(hybrid)?.let { return it }
        val resolved = BluetoothAddressResolver.resolve(context)
        if (resolved == null) {
            AppLog.w(TAG, "localAddress unresolved (Android may hide the hardware MAC from apps)")
        } else {
            AppLog.i(TAG, "localAddress=$resolved")
        }
        return resolved
    }
}
