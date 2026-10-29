package com.shihab.diplay

import android.content.res.Configuration
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HeadlightThemeTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearHeadlightTheme() {
        Settings.System.putString(context.contentResolver, HeadlightTheme.SETTING, null)
    }

    @Test fun nightAndDayThemesMapToABoolean() {
        assertEquals(true, HeadlightTheme.nightOrNull("NightTheme"))
        assertEquals(false, HeadlightTheme.nightOrNull("DaytimeTheme"))
        assertNull(HeadlightTheme.nightOrNull(null))
        assertNull(HeadlightTheme.nightOrNull(""))
        assertNull(HeadlightTheme.nightOrNull("other"))
    }

    @Test fun followSystemUsesHeadlightThemeAheadOfUiMode() {
        setUiMode(Configuration.UI_MODE_NIGHT_NO)
        Settings.System.putString(context.contentResolver, HeadlightTheme.SETTING, HeadlightTheme.NIGHT)
        assertEquals(true, context.followSystemNight())

        setUiMode(Configuration.UI_MODE_NIGHT_YES)
        Settings.System.putString(context.contentResolver, HeadlightTheme.SETTING, HeadlightTheme.DAY)
        assertEquals(false, context.followSystemNight())
    }

    @Test fun missingOrUnknownHeadlightThemeFallsBackToUiMode() {
        Settings.System.putString(context.contentResolver, HeadlightTheme.SETTING, null)
        setUiMode(Configuration.UI_MODE_NIGHT_YES)
        assertEquals(true, context.followSystemNight())

        Settings.System.putString(context.contentResolver, HeadlightTheme.SETTING, "custom")
        setUiMode(Configuration.UI_MODE_NIGHT_NO)
        assertEquals(false, context.followSystemNight())

        Settings.System.putString(context.contentResolver, HeadlightTheme.SETTING, null)
        setUiMode(Configuration.UI_MODE_NIGHT_UNDEFINED)
        assertNull(context.followSystemNight())
    }

    private fun setUiMode(night: Int) {
        context.resources.configuration.uiMode =
            night or Configuration.UI_MODE_TYPE_CAR
    }
}
