package com.shihab.diplay
import com.shihab.diplay.host.CarPlayBackgroundSession

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.GlassSwitch
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SystemBarSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DiPlayActivity>? = null
    private val activity get() = requireNotNull(controller).get()

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
        controller?.pause()?.stop()?.destroy()
    }

    @Test fun settingsExposeTwoIndependentSwitchesWithTheExistingDefaults() {
        openSettings()

        assertTrue(statusBarSwitch().isChecked)
        assertTrue(navigationBarSwitch().isChecked)
    }

    @Test fun settingsReadAllFourExistingCombinationsWithoutChangingThem() {
        openSettings()
        for (hideTop in listOf(false, true)) {
            for (hideBottom in listOf(false, true)) {
                AirPlayPersistence.saveHideTopBar(context, hideTop)
                AirPlayPersistence.saveHideBottomBar(context, hideBottom)

                requireNotNull(controller).recreate()

                assertEquals(hideTop, statusBarSwitch().isChecked)
                assertEquals(hideBottom, navigationBarSwitch().isChecked)
                assertEquals(hideTop, AirPlayPersistence.loadHideTopBar(context))
                assertEquals(hideBottom, AirPlayPersistence.loadHideBottomBar(context))
            }
        }
    }

    @Test fun togglingEitherBarPreservesTheOtherPreferenceAndSwitch() {
        openSettings()

        statusBarSwitch().performClick()
        assertBars(false, true)
        navigationBarSwitch().performClick()
        assertBars(false, false)
        statusBarSwitch().performClick()
        assertBars(true, false)
        navigationBarSwitch().performClick()
        assertBars(true, true)
    }

    @Test fun hidingOnlyTheStatusBarSurvivesRecreation() {
        openSettings()
        navigationBarSwitch().performClick()

        requireNotNull(controller).recreate()

        assertBars(true, false)
    }

    @Test fun returningToSettingsReloadsChangesFromTheOtherEntryPoint() {
        openSettings()
        requireNotNull(controller).pause()
        AirPlayPersistence.saveHideTopBar(context, false)
        AirPlayPersistence.saveHideBottomBar(context, true)

        requireNotNull(controller).resume()

        assertBars(false, true)
    }

    @Test fun harmanFullscreenPersistsWithoutSettingsUi() {
        AirPlayPersistence.saveHarmanFullscreen(context, false)
        assertFalse(AirPlayPersistence.loadHarmanFullscreen(context))

        AirPlayPersistence.saveHarmanFullscreen(context, true)
        assertTrue(AirPlayPersistence.loadHarmanFullscreen(context))

        AirPlayPersistence.saveHarmanFullscreen(context, false)
        assertFalse(AirPlayPersistence.loadHarmanFullscreen(context))
    }

    private fun openSettings() {
        controller = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup()
        // First paint is a short splash; advance past the delayed showPage().
        ShadowLooper.idleMainLooper(1300, TimeUnit.MILLISECONDS)
    }

    private fun assertBars(hideTop: Boolean, hideBottom: Boolean) {
        assertEquals(hideTop, AirPlayPersistence.loadHideTopBar(context))
        assertEquals(hideBottom, AirPlayPersistence.loadHideBottomBar(context))
        assertEquals(hideTop, statusBarSwitch().isChecked)
        assertEquals(hideBottom, navigationBarSwitch().isChecked)
    }

    private fun statusBarSwitch(): GlassSwitch = barSwitch(R.string.setting_hide_top_bar)

    private fun navigationBarSwitch(): GlassSwitch = barSwitch(R.string.setting_hide_bottom_bar)

    private fun barSwitch(label: Int): GlassSwitch {
        val labelText = activity.getString(label)
        val labelView = descendants(activity.window.decorView)
            .filterIsInstance<TextView>()
            .single { it.text?.toString() == labelText }
        val parent = labelView.parent as LinearLayout
        return (0 until parent.childCount)
            .map { parent.getChildAt(it) }
            .filterIsInstance<GlassSwitch>()
            .single()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
