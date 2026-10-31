package com.shihab.diplay.settings

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import com.shihab.diplay.airplay.CarPlayDisplayScale
import com.shihab.diplay.orchestration.MfiTarget
import com.shihab.diplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsWidgetsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun constructingAndChangingASwitchDoesNotSaveTheInitialValue() {
        for (theme in SettingsTheme.entries) {
            val saved = mutableListOf<Boolean>()
            val row = SettingsWidgets.createSwitchRow(context, "Title", "Details", true, theme,
                onChanged = saved::add)
            assertTrue(saved.isEmpty())
            row.switch.isChecked = false
            row.switch.isChecked = false
            assertEquals(listOf(false), saved)
        }
    }

    @Test fun resolutionDisplayAndSliderStayWithinTheSameRangeWithoutSavingDuringConstruction() {
        for (initial in listOf(-1, 73, 999)) {
            val saved = mutableListOf<Int>()
            val row = SettingsWidgets.createResolutionSlider(context, initial, onPercentChanged = saved::add)
            val expected = initial.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
            assertEquals(expected - CarPlayDisplayScale.MIN_PERCENT, row.seekBar.progress)
            assertEquals(expected.toString() + "%", row.valueTextView.text.toString())
            assertTrue(saved.isEmpty())
            row.seekBar.progress = 0
            row.seekBar.progress = Int.MAX_VALUE
            assertEquals(CarPlayDisplayScale.MAX_PERCENT.toString() + "%", row.valueTextView.text.toString())
            assertTrue(saved.all { it in CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT })
            assertEquals(CarPlayDisplayScale.MAX_PERCENT, saved.last())
        }
    }

    @Test fun cardDescriptionsKeepMultilineSpacingAndTheirAccessibleTitle() {
        val row = SettingsWidgets.createSwitchRow(context, "Title", "First line\nSecond line", false,
            SettingsTheme.CARD, onChanged = {})
        val texts = views(row.rowView).filterIsInstance<TextView>().filter { it !== row.switch }.toList()
        assertEquals(listOf("Title", "First line\nSecond line"), texts.map { it.text.toString() })
        assertTrue(texts.all { it.lineSpacingExtra == SettingsTheme.CARD.dp(context, 3).toFloat() })
        assertEquals("Title", row.switch.contentDescription)
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
}
