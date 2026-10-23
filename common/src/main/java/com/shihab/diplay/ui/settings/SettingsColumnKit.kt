// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.shihab.diplay.ui.DiPlayHost
import com.shihab.diplay.ui.GlassDropdown

/** Shared row helpers for shell setting columns. */
internal abstract class SettingsColumnKit(private val page: Fragment) {
    protected val host get() = page.requireActivity() as DiPlayHost
    protected val isAdded get() = page.isAdded
    protected val activity get() = page.activity
    protected fun requireContext() = page.requireContext()
    protected fun requireActivity() = page.requireActivity()

    protected fun divider(ctx: android.content.Context): View = GlassSettingsRows.divider(ctx)
    protected fun settingRow(ctx: android.content.Context, title: String, right: View) =
        GlassSettingsRows.settingRow(ctx, title, right)
    protected fun capPick(ctx: android.content.Context, text: String, click: (TextView) -> Unit) =
        GlassSettingsRows.capPick(ctx, text, click)
    protected fun glassSwitchRow(ctx: android.content.Context, title: String, value: Boolean, save: (Boolean) -> Unit) =
        GlassSettingsRows.glassSwitchRow(ctx, title, value, save)
    protected fun reconnectSwitchRow(
        ctx: android.content.Context,
        title: String,
        value: Boolean,
        onConfirm: (Boolean) -> Unit,
    ) = GlassSettingsRows.reconnectSwitchRow(ctx, host, title, value, onConfirm)

    /** 玻璃下拉：紧凑宽高 + 打开期间压制系统导航栏。 */
    protected open fun showDropdown(
        anchor: View,
        labels: List<String>,
        selected: Int,
        syncAnchorText: Boolean = true,
        onSelect: (Int) -> Unit,
    ) {
        GlassDropdown.show(anchor, labels, selected, onSelect, syncAnchorText = syncAnchorText)
    }
}
