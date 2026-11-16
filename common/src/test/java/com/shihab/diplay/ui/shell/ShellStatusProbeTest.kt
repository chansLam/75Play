package com.shihab.diplay.ui.shell

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import com.shihab.diplay.network.CarHotspotStatus
import org.robolectric.shadows.ShadowNetworkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ShellStatusProbeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun hotspotAndBluetoothStayOnTheLastBroadcast() {
        val probe = ShellStatusProbe(app)
        probe.start()
        assertFalse(probe.snapshot().hotspotOn)
        assertEquals(ShellBluetooth.OFF, probe.snapshot().bluetooth)

        probe.onRadioBroadcast(Intent(CarHotspotStatus.WIFI_AP_STATE_CHANGED_ACTION).apply {
            putExtra("wifi_state", 13)
            putExtra("wifi_ap_mode", 1)
        })
        probe.onRadioBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON)
        })
        assertTrue(probe.snapshot().hotspotOn)
        assertEquals(ShellBluetooth.ON, probe.snapshot().bluetooth)

        probe.onRadioBroadcast(Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED).apply {
            putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED)
        })
        assertEquals(ShellBluetooth.CONNECTED, probe.snapshot().bluetooth)

        probe.onRadioBroadcast(Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED).apply {
            putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
        })
        assertEquals(ShellBluetooth.CONNECTED, probe.snapshot().bluetooth)

        probe.onRadioBroadcast(Intent(CarHotspotStatus.WIFI_AP_STATE_CHANGED_ACTION).apply {
            putExtra("wifi_state", 13)
            putExtra("wifi_ap_mode", 2)
        })
        assertTrue(probe.snapshot().hotspotOn)

        probe.onRadioBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
        })
        assertEquals(ShellBluetooth.OFF, probe.snapshot().bluetooth)

        probe.onRadioBroadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
            putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED)
        })
        @Suppress("DEPRECATION")
        probe.onRadioBroadcast(Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION).apply {
            putExtra(
                WifiManager.EXTRA_NETWORK_INFO,
                ShadowNetworkInfo.newInstance(
                    NetworkInfo.DetailedState.CONNECTED,
                    ConnectivityManager.TYPE_WIFI,
                    0,
                    true,
                    true,
                ),
            )
        })
        probe.onRadioBroadcast(Intent(WifiManager.RSSI_CHANGED_ACTION).apply {
            putExtra(WifiManager.EXTRA_NEW_RSSI, -50)
        })
        val connected = probe.snapshot()
        assertTrue(connected.wifiOn)
        assertTrue(connected.wifiConnected)
        assertEquals(3, connected.wifiBars)

        probe.onRadioBroadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
            putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED)
        })
        val off = probe.snapshot()
        assertFalse(off.wifiOn)
        assertFalse(off.wifiConnected)
        assertEquals(0, off.wifiBars)
        probe.stop()
    }
}
