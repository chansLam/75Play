package com.shihab.diplay

import android.content.Context
import com.shihab.diplay.network.WifiP2pChannels
import com.shihab.diplay.orchestration.CarPlayRuntimeConfig
import com.shihab.diplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", manifest = Config.NONE)
class WifiDirectChannelSettingsTest {
    private val prefs get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        prefs.edit().clear().commit()
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
    }

    @Test fun defaultIsAutoAndUnsavedPreviewDoesNotPersist() {
        assertEquals(WifiP2pChannels.AUTO, AirPlayPersistence.loadWifiP2pPreferredChannel(app))
        // Cancelled channel picker would leave Auto — only saveWifiP2pPreferredChannel persists.
        assertEquals(WifiP2pChannels.AUTO, AirPlayPersistence.loadWifiP2pPreferredChannel(app))
    }

    @Test fun savedChannelSurvivesModeChangesAndCanBeResetToAuto() {
        AirPlayPersistence.saveWifiP2pPreferredChannel(app, 149)
        assertEquals(149, AirPlayPersistence.loadWifiP2pPreferredChannel(app))
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        assertEquals(149, AirPlayPersistence.loadWifiP2pPreferredChannel(app))
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        assertEquals(149, AirPlayPersistence.loadWifiP2pPreferredChannel(app))
        AirPlayPersistence.saveWifiP2pPreferredChannel(app, WifiP2pChannels.AUTO)
        assertEquals(WifiP2pChannels.AUTO, AirPlayPersistence.loadWifiP2pPreferredChannel(app))
    }

    @Test fun invalidOrCorruptSavedChannelsUseAuto() {
        for (channel in listOf(-1, 12, 14, 52, 100, 196)) {
            prefs.edit().putInt("wifi_p2p_preferred_channel", channel).commit()
            assertEquals(WifiP2pChannels.AUTO, AirPlayPersistence.loadWifiP2pPreferredChannel(app))
        }
        prefs.edit().putString("wifi_p2p_preferred_channel", "149").commit()
        assertEquals(WifiP2pChannels.AUTO, AirPlayPersistence.loadWifiP2pPreferredChannel(app))
    }

    @Test fun hostRuntimeConfigReceivesSavedChannel() {
        AirPlayPersistence.saveWifiP2pPreferredChannel(app, 149)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        CarPlayHostActivity::class.java.getDeclaredField("airPlayIdentity").apply { isAccessible = true }
            .set(host, AirPlayPersistence.loadIdentity(host))
        val config = CarPlayHostActivity::class.java.getDeclaredMethod("createRuntimeConfig")
            .apply { isAccessible = true }.invoke(host) as CarPlayRuntimeConfig
        assertEquals(149, config.wifiP2pPreferredChannel)
    }
}
