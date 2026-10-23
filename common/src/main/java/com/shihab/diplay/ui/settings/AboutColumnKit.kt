// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.AppLocale
import com.shihab.diplay.DiPlayBluetooth
import com.shihab.diplay.DiPlayPreferences
import com.shihab.diplay.WheelRootInjector
import com.shihab.diplay.ui.DiPlayDialog
import com.shihab.diplay.ui.DiPlayToast
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.GlassDropdown
import com.shihab.diplay.ui.GlassSwitch
import com.shihab.diplay.ui.LayoutMetrics

internal abstract class AboutColumnKit(page: Fragment) : SettingsColumnKit(page) {
    protected fun capVal(ctx: android.content.Context, text: String, color: Int = GlassColors.CAP_TEXT) =
        GlassSettingsRows.capVal(ctx, text, color)
    protected fun ghostButton(ctx: android.content.Context, text: String, click: () -> Unit) =
        GlassSettingsRows.ghostButton(ctx, text, cornerRadiusDp = 10, click)
    protected fun glassSwitchRow(
        ctx: android.content.Context,
        title: String,
        value: Boolean,
        save: (GlassSwitch, Boolean) -> Unit,
    ) = GlassSettingsRows.glassSwitchRow(ctx, title, value, save)

    protected override fun showDropdown(
        anchor: View,
        labels: List<String>,
        selected: Int,
        syncAnchorText: Boolean,
        onSelect: (Int) -> Unit,
    ) {
        GlassDropdown.show(anchor, labels, selected, onSelect, syncAnchorText = false)
    }

}
