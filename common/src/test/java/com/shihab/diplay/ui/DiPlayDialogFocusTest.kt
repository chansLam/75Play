// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui

import android.app.Activity
import android.view.View
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiPlayDialogFocusTest {
    @Test fun customDialogWithInputCanTakeFocus() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = DiPlayDialog.showCustom(activity, "搜索", View(activity), allowFocus = true)
        try {
            val flags = dialog.window!!.attributes.flags
            assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        } finally {
            dialog.dismiss()
        }
    }

    @Test fun customDialogWithoutInputStaysUnfocused() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = DiPlayDialog.showCustom(activity, "列表", View(activity))
        try {
            val flags = dialog.window!!.attributes.flags
            assertEquals(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            )
        } finally {
            dialog.dismiss()
        }
    }
}
