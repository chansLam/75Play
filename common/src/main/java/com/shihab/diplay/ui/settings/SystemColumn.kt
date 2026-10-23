// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.FocusSupport
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.LayoutMetrics

internal class SystemColumn(page: Fragment) : AboutColumnKit(page) {
    private var versionTaps = 0
    private var lastTap = 0L

    private fun buildSystemColumn(card: LinearLayout) {
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
        card.addView(sectionGap(ctx))
        card.addView(sectionTitle(ctx, ctx.getString(R.string.about_update_section)))
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.about_auto_update), host.autoUpdateEnabled()) { host.setAutoUpdateEnabled(it) })
        card.addView(paragraph(ctx, ctx.getString(R.string.about_auto_update_hint)))
        card.addView(settingRow(ctx, ctx.getString(R.string.about_check_software), ghostButton(ctx, ctx.getString(R.string.about_check)) { host.updateCheckNow() }))
        card.addView(divider(ctx))
        card.addView(sectionGap(ctx))
        card.addView(sectionTitle(ctx, ctx.getString(R.string.about_credits_section)))
        card.addView(paragraph(ctx, ctx.getString(R.string.about_license_text)))
        card.addView(divider(ctx))
        card.addView(sectionGap(ctx))
        card.addView(settingRow(ctx, ctx.getString(R.string.about_export_report), ghostButton(ctx, ctx.getString(R.string.about_export)) { host.exportDiagnosticsNow() }))
        card.addView(settingRow(ctx, ctx.getString(R.string.choose_save_location), capPick(ctx, ctx.getString(R.string.about_default)) { host.chooseReportDestination() }))
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

    /** Share leftover column height between the preview, update, and credits blocks. */
    private fun sectionGap(ctx: android.content.Context): View {
        val gap = View(ctx)
        gap.layoutParams = if (LayoutMetrics.isCockpit(ctx)) {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, GlassSettingsRows.dp(ctx, 8))
        }
        return gap
    }

    fun fill(card: LinearLayout) = buildSystemColumn(card)
}
