// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shihab.diplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN")
class DiPlayImagePreviewTest {
    @Test fun confirmStaysOpenUntilTheCallerClosesIt() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        var confirmed = 0
        val dialog = DiPlayDialog.showImagePreview(activity, bitmap, onConfirm = { _ -> confirmed++ }).dialog
        try {
            val buttons = buttons(dialog.window!!.decorView)
            val back = buttons.first { it.text == activity.getString(R.string.back) }
            val confirm = buttons.first { it.text == activity.getString(R.string.dialog_ok) }
            assertEquals("返回", back.text.toString())
            assertEquals("确定", confirm.text.toString())
            confirm.performClick()
            confirm.performClick()
            assertEquals(1, confirmed)
            assertTrue(dialog.isShowing)
            assertFalse(bitmap.isRecycled)
            assertFalse(back.isEnabled)
            assertFalse(confirm.isEnabled)
        } finally {
            dialog.dismiss()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        }
        assertTrue(bitmap.isRecycled)
    }

    @Test fun backClosesThePreviewWithoutConfirming() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        var confirmed = false
        val dialog = DiPlayDialog.showImagePreview(activity, bitmap, onConfirm = { _ -> confirmed = true }).dialog
        buttons(dialog.window!!.decorView).first { it.text == activity.getString(R.string.back) }.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertFalse(confirmed)
        assertFalse(dialog.isShowing)
        assertTrue(bitmap.isRecycled)
    }

    @Test fun verticalStepFollowsTheSwipeAndButtonsReplaceTheImage() {
        assertEquals(1, DiPlayDialog.verticalStep(0f, -40f, 10))
        assertEquals(-1, DiPlayDialog.verticalStep(0f, 40f, 10))
        assertEquals(null, DiPlayDialog.verticalStep(80f, 20f, 10))
        assertEquals(null, DiPlayDialog.verticalStep(0f, 8f, 10))

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val first = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        var step = 0
        val preview = DiPlayDialog.showImagePreview(activity, first, onConfirm = { _ -> }, onStep = { step = it })
        preview.setEnds(hasPrevious = true, hasNext = true)
        try {
            val shown = buttons(preview.dialog.window!!.decorView)
            shown.first { it.text == activity.getString(R.string.settings_wallpaper_next) }.performClick()
            assertEquals(1, step)
            shown.first { it.text == activity.getString(R.string.settings_wallpaper_previous) }.performClick()
            assertEquals(-1, step)
            preview.replace(second)
            assertFalse(first.isRecycled)
            assertFalse(second.isRecycled)
            assertEquals(second, preview.bitmap())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertTrue(first.isRecycled)
            assertFalse(second.isRecycled)
        } finally {
            preview.dialog.dismiss()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        }
        assertTrue(second.isRecycled)
    }

    @Test fun releaseDisplayedRecyclesAfterTheFrameThenContinues() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val preview = DiPlayDialog.showImagePreview(activity, bitmap, onConfirm = { _ -> })
        var continued = false
        preview.releaseDisplayed {
            assertTrue(bitmap.isRecycled)
            continued = true
        }
        assertFalse(continued)
        assertFalse(bitmap.isRecycled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue(continued)
        assertTrue(bitmap.isRecycled)
        preview.dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }

    private fun buttons(root: View): List<Button> = views(root).filterIsInstance<Button>().toList()

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
        }
    }
}
