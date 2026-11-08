// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.DiPlayDialog
import com.shihab.diplay.ui.FocusSupport
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.LayoutMetrics

internal class SystemColumn(page: Fragment) : AboutColumnKit(page) {
    private var versionTaps = 0
    private var lastTap = 0L

    private fun buildAbout(card: LinearLayout) {
        val ctx = card.context
        val preview = GlassSettingsRows.label(
            ctx,
            ctx.getString(R.string.about_preview_title, host.versionString()),
            LayoutMetrics.cardTitleSize(ctx),
            GlassColors.TEXT_PRIMARY,
            true,
        ).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastTap > 2000) versionTaps = 0
                lastTap = now
                versionTaps++
                if (versionTaps >= 5) {
                    versionTaps = 0
                    host.openDevTerminal()
                }
            }
            FocusSupport.attachRow(this)
        }
        card.addView(preview)
        card.addView(paragraph(ctx, ctx.getString(R.string.about_preview_body)))
        card.addView(
            GlassSettingsRows.label(ctx, ctx.getString(R.string.about_dev_tip), LayoutMetrics.settingsCapTextSize(ctx), GlassColors.TEXT_MUTED).apply {
                setPadding(0, GlassSettingsRows.dp(ctx, 4), 0, 0)
            },
        )
        card.addView(divider(ctx))
        card.addView(sectionTitle(ctx, ctx.getString(R.string.about_credits_section)))
        card.addView(paragraph(ctx, ctx.getString(R.string.about_license_text)))
    }

    private fun buildMaintenance(card: LinearLayout) {
        val ctx = card.context
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.about_auto_update), host.autoUpdateEnabled()) { host.setAutoUpdateEnabled(it) })
        card.addView(paragraph(ctx, ctx.getString(R.string.about_auto_update_hint)))
        card.addView(settingRow(ctx, ctx.getString(R.string.about_check_software), ghostButton(ctx, ctx.getString(R.string.about_check)) { host.updateCheckNow() }))
        card.addView(divider(ctx))
        card.addView(settingRow(ctx, ctx.getString(R.string.about_export_report), ghostButton(ctx, ctx.getString(R.string.about_export)) { host.exportDiagnosticsNow() }))
        card.addView(settingRow(ctx, ctx.getString(R.string.choose_save_location), capPick(ctx, ctx.getString(R.string.about_default)) { host.chooseReportDestination() }))
        card.addView(divider(ctx))
        addPrivilegedUsbRow(card)
    }

    /**
     * One-time privileged install so wired USB permission is granted silently. The heavy work
     * (su probe / remount / copy) runs off the UI thread; the reboot itself is confirmed first.
     */
    private fun addPrivilegedUsbRow(card: LinearLayout) {
        val ctx = card.context
        val root = com.shihab.diplay.host.RootController(ctx)
        val button = ghostButton(ctx, ctx.getString(R.string.usb_privilege_action)) {
            Thread {
                when {
                    root.hasManageUsb() || root.isSystemApp() ->
                        host.showGlassToast(ctx.getString(R.string.usb_privilege_already))
                    !root.isRootAvailable() ->
                        host.showGlassToast(ctx.getString(R.string.usb_privilege_no_root))
                    else -> mainThread(ctx) {
                        DiPlayDialog.showConfirm(
                            ctx,
                            ctx.getString(R.string.usb_privilege_title),
                            ctx.getString(R.string.usb_privilege_confirm_body),
                            ctx.getString(R.string.usb_privilege_reboot),
                            ctx.getString(R.string.cancel),
                            danger = true,
                            onConfirm = {
                                Thread {
                                    val error = root.installAsPrivilegedApp()
                                    if (error != null) mainThread(ctx) {
                                        DiPlayDialog.showInfo(
                                            ctx,
                                            ctx.getString(R.string.usb_privilege_title),
                                            error,
                                        )
                                    }
                                }.start()
                            },
                        )
                    }
                }
            }.start()
        }
        card.addView(settingRow(ctx, ctx.getString(R.string.usb_privilege_row), button))
        card.addView(paragraph(ctx, ctx.getString(R.string.usb_privilege_hint)))
    }

    private fun mainThread(ctx: android.content.Context, block: () -> Unit) {
        android.os.Handler(ctx.mainLooper).post(block)
    }

    private fun sectionTitle(ctx: android.content.Context, text: String) =
        GlassSettingsRows.label(ctx, text, LayoutMetrics.settingsRowTextSize(ctx), GlassColors.TEXT_PRIMARY, true).apply {
            setPadding(0, GlassSettingsRows.dp(ctx, 2), 0, GlassSettingsRows.dp(ctx, 4))
        }

    private fun paragraph(ctx: android.content.Context, text: String) =
        GlassSettingsRows.label(ctx, text, LayoutMetrics.settingsRowTextSize(ctx), GlassColors.TEXT_BODY).apply {
            setLineSpacing(0f, 1.25f)
            setPadding(0, GlassSettingsRows.dp(ctx, 2), 0, GlassSettingsRows.dp(ctx, 2))
        }

    fun fillAbout(card: LinearLayout) = buildAbout(card)

    fun fillMaintenance(card: LinearLayout) = buildMaintenance(card)
}
