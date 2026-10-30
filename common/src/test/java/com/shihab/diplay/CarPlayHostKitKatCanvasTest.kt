package com.shihab.diplay

import android.os.Build
import android.os.Handler
import com.shihab.diplay.airplay.AirPlayDisplayConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ExecutorService

/**
 * Android 4.4 has no decoder capability query, so an enlarged canvas stays on the panel size.
 *
 * The head unit fakes SDK_INT while RELEASE stays 4.4.x, so KitKat is reached the way
 * [com.shihab.diplay.compat.realSdkInt] does it on the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostKitKatCanvasTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var reportedRelease: String

    @Before fun setUp() {
        reportedRelease = Build.VERSION.RELEASE
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "RELEASE", "4.4.2")
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
    }

    @After fun tearDown() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "RELEASE", reportedRelease)
        (field("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        (field("airPlayCommandExecutor") as ExecutorService).shutdownNow()
    }

    @Test fun kitkatKeepsTheNativeCanvasByDefault() {
        assertEquals("legacy_decoder_capabilities", support(3072, 1584).second)
        assertFalse(support(3072, 1584).first)
    }

    private fun support(width: Int, height: Int): Pair<Boolean, String> {
        val method = CarPlayHostActivity::class.java
            .getDeclaredMethod("decoderCanvasSupport", AirPlayDisplayConfig::class.java)
            .apply { isAccessible = true }
        val result = method.invoke(activity, AirPlayDisplayConfig(widthPixels = width, heightPixels = height))
        val type = result.javaClass
        val supported = type.getDeclaredField("supported").apply { isAccessible = true }.getBoolean(result)
        val reason = type.getDeclaredField("reason").apply { isAccessible = true }.get(result) as String
        return supported to reason
    }

    private fun field(name: String): Any? = CarPlayHostActivity::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(activity)
}
