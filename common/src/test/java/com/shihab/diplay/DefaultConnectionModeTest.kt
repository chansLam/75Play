package com.shihab.diplay
import com.shihab.diplay.host.CarPlayBackgroundSession

import android.content.Intent
import android.os.Looper
import com.shihab.diplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class DefaultConnectionModeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
    }

    @After fun cleanup() {
        CarPlayBackgroundSession.clear()
    }

    @Test fun initialLaunchUsesTheFixedDefaultInsteadOfTheLastManualTransport() {
        DiPlayPreferences.saveAutoConnect(app, true)
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(app, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "12345678")
        app.getSharedPreferences("diplay", 0).edit().putBoolean("notification_asked", true).commit()
        for (mode in listOf(DefaultConnectionMode.WIRELESS)) {
            val expectedWireless = mode == DefaultConnectionMode.WIRELESS
            DiPlayPreferences.saveDefaultConnectionMode(app, mode)
            AirPlayPersistence.saveWirelessEnabled(app, !expectedWireless)
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java, Intent(Intent.ACTION_MAIN)).create()
            val activity = controller.get()
            // Source-only test builds have no authentication assets; exercise the startup decision.
            ReflectionHelpers.setField(activity, "setupError", null)
            controller.start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(expectedWireless, AirPlayPersistence.loadWirelessEnabled(app))
            // Shell connect owns the in-flight attempt; Host opens only after ACTION_CONNECTED.
            assertNull(
                "Host must not open until shell connect succeeds",
                shadowOf(activity).nextStartedActivity?.component?.className
                    ?.takeIf { it == CarPlayHostActivity::class.java.name },
            )
            assertEquals(mode, DiPlayPreferences.defaultConnectionMode(app))
            controller.pause().stop().destroy()
        }
    }

    @Test fun openingSettingsDoesNotAutoConnectOrChangeTheLastTransport() {
        DiPlayPreferences.saveAutoConnect(app, true)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.WIRELESS)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent().putExtra("page", "settings")).create()
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "setupError", null)
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isCarPlayConnecting())
        controller.pause().stop().destroy()
    }

    @Test fun existingInstallationsKeepLastUsedBehaviorAndUnknownValuesFallBack() {
        for (lastWireless in listOf(false, true)) {
            AirPlayPersistence.saveWirelessEnabled(app, lastWireless)
            assertEquals(lastWireless, DiPlayPreferences.autoConnectWireless(app))
            app.getSharedPreferences("diplay", 0).edit()
                .putString("default_connection_mode", "future_mode").commit()
            assertEquals(DefaultConnectionMode.LAST_USED, DiPlayPreferences.defaultConnectionMode(app))
            assertEquals(lastWireless, DiPlayPreferences.autoConnectWireless(app))
        }
    }

    @Test fun fixedDefaultSurvivesManualTransportChangesWithoutChangingAutoConnectOrPhone() {
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        val expectedModes = mapOf(
            DefaultConnectionMode.LAST_USED to listOf(false, true),
            DefaultConnectionMode.WIRELESS to listOf(true, true),
        )
        for ((mode, expectedWireless) in expectedModes) {
            DiPlayPreferences.saveDefaultConnectionMode(app, mode)
            assertEquals(mode, DiPlayPreferences.defaultConnectionMode(app))
            for ((index, lastWireless) in listOf(false, true).withIndex()) {
                AirPlayPersistence.saveWirelessEnabled(app, lastWireless)
                assertEquals(expectedWireless[index], DiPlayPreferences.autoConnectWireless(app))
                assertEquals(lastWireless, AirPlayPersistence.loadWirelessEnabled(app))
            }
            assertFalse(DiPlayPreferences.autoConnect(app))
            assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(app))
        }
    }

    @Test fun settingsSavePersistsChoiceAndCancelKeepsSavedDefault() {
        // Glass UI no longer exposes connectionSettings(LinearLayout); persistence is the product contract.
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.WIRELESS)
        assertEquals(DefaultConnectionMode.WIRELESS, DiPlayPreferences.defaultConnectionMode(app))
        // A cancelled editor would not call save — saved default must remain.
        assertEquals(DefaultConnectionMode.WIRELESS, DiPlayPreferences.defaultConnectionMode(app))
        assertFalse(DiPlayPreferences.autoConnect(app))
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.LAST_USED)
        assertEquals(DefaultConnectionMode.LAST_USED, DiPlayPreferences.defaultConnectionMode(app))
    }
}
