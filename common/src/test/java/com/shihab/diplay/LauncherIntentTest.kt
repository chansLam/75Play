package com.shihab.diplay
import com.shihab.diplay.host.CarPlayBackgroundSession

import android.content.Intent
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class LauncherIntentTest {
    private var controller: ActivityController<DiPlayActivity>? = null
    private var stops = 0

    @Before fun setup() {
        DiPlayActivity.returnedFromCarPlayAt = 0L
        CarPlayHostActivity.pictureVisible = false
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        val stop: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
    }

    @After fun cleanup() {
        DiPlayActivity.returnedFromCarPlayAt = 0L
        CarPlayHostActivity.pictureVisible = false
        controller?.destroy()
        CarPlayBackgroundSession.clear()
    }
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Test
    fun onlyAPlainLauncherIntentReturnsToCarPlay() {
        assertTrue(DiPlayActivity.isLauncherIntent(launcher()))
        assertFalse(DiPlayActivity.isLauncherIntent(launcher().putExtra("page", "settings")))
        assertFalse(DiPlayActivity.isLauncherIntent(launcher().putExtra("page", null as String?)))
        assertFalse(DiPlayActivity.isLauncherIntent(launcher().putExtra("page", 123)))
        assertFalse(DiPlayActivity.isLauncherIntent(Intent(Intent.ACTION_MAIN)))
        assertFalse(DiPlayActivity.isLauncherIntent(Intent().putExtra("page", "connection")))
    }

    @Test fun freshLauncherReturnsToTheExistingHostWithoutStoppingTheSession() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java, launcher()).create()
        val activity = controller!!.get()
        assertTrue(activity.isFinishing)
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertEquals(0, stops)
    }

    @Test fun threeFingerReturnStaysOnDiPlay() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent().putExtra("page", "settings")).create()
        val activity = controller!!.get()
        shadowOf(activity).clearNextStartedActivities()
        ReflectionHelpers.setField(activity, "userNavigatedFromCarPlay", false)
        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "onNewIntent",
            ReflectionHelpers.ClassParameter(Intent::class.java, Intent().putExtra("returnFromCarPlay", true)),
        )
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(true, ReflectionHelpers.getField<Boolean>(activity, "userNavigatedFromCarPlay"))
        assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
    }

    @Test fun launcherDeliveredToExistingSettingsOpensProjection() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent().putExtra("page", "settings")).create()
        val activity = controller!!.get()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onNewIntent",
            ReflectionHelpers.ClassParameter(Intent::class.java, launcher()))
        assertEquals("home", ReflectionHelpers.getField<String>(activity, "page"))
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertEquals(0, stops)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onNewIntent",
            ReflectionHelpers.ClassParameter(Intent::class.java, launcher().putExtra("page", "settings")))
        assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun recreationRetainsSettingsInsteadOfRedirectingToProjection() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java, launcher())
            .create(Bundle().apply { putString("page", "settings") })
        val activity = controller!!.get()
        assertFalse(activity.isFinishing)
        assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, stops)
    }

    @Test fun hotResumeWithActiveSessionRedirectsToCarPlayHostActivity() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java, launcher())
            .create(Bundle().apply { putString("page", "settings") })
            .start().resume()
        val activity = controller!!.get()
        assertEquals(0, stops)
        shadowOf(activity).clearNextStartedActivities()

        // User navigates back to home inside DiPlay
        ReflectionHelpers.setField(activity, "page", "home")
        // User leaves the app (e.g. backgrounded)
        controller!!.pause().stop()

        // User brings DiPlay back to foreground (hot resume) while session is active
        controller!!.restart().start().resume()
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
    }

    @Test fun projectionWithoutAWarmSurfaceStillOpensTheHost() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java, Intent())
            .create(Bundle().apply { putString("page", "home") })
        val activity = controller!!.get()
        shadowOf(activity).clearNextStartedActivities()
        activity.openProjection()
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        shadowOf(activity).clearNextStartedActivities()
        activity.openProjection()
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
    }
}
