// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.AppLocale
import com.shihab.diplay.DiPlayPreferences
import com.shihab.diplay.host.R

internal class VehicleColumn(page: Fragment) : AboutColumnKit(page) {

    private fun buildVehicleColumn(card: LinearLayout) {
        val ctx = card.context
        card.addView(settingRow(ctx, ctx.getString(R.string.language_app_language), capPick(ctx, appLanguageLabel(ctx, AppLocale.preference(ctx))) { cap ->
            showDropdown(cap, AppLocale.ALL.map { appLanguageLabel(ctx, it) },
                AppLocale.ALL.indexOf(AppLocale.preference(ctx)).coerceAtLeast(0)) { idx ->
                AppLocale.apply(requireActivity(), AppLocale.ALL[idx])
            }
        }))
        val fingers = listOf(
            ctx.getString(R.string.about_gesture_fingers, 2),
            ctx.getString(R.string.about_gesture_fingers, 3),
            ctx.getString(R.string.about_gesture_fingers, 4),
        )
        val cur = AirPlayPersistence.loadSettingsGestureFingers(ctx).coerceIn(2, 4)
        card.addView(settingRow(ctx, ctx.getString(R.string.about_carplay_gesture),
            capPick(ctx, ctx.getString(R.string.about_gesture_fingers, cur)) { cap ->
                showDropdown(cap, fingers, cur - 2) { idx ->
                    AirPlayPersistence.saveSettingsGestureFingers(ctx, idx + 2)
                    cap.text = fingers[idx] + " ▾"
                }
            }))
        card.addView(glassSwitchRow(
            ctx,
            ctx.getString(R.string.settings_floating_ball),
            AirPlayPersistence.loadFloatingBallEnabled(ctx),
        ) { on -> host.setFloatingBallEnabled(on) })
        card.addView(divider(ctx))
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.about_auto_start_carplay), AirPlayPersistence.loadAutoStartOnBoot(ctx)) { on ->
            AirPlayPersistence.saveAutoStartOnBoot(ctx, on)
        })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.about_auto_connect_on_start), DiPlayPreferences.autoConnect(ctx)) { on ->
            DiPlayPreferences.saveAutoConnect(ctx, on)
        })
    }

    /** 中列：语言 / 手势 / 悬浮球 / 开机自启与自动连接。 */

    private fun appLanguageLabel(ctx: android.content.Context, code: String): String = when (code) {
        AppLocale.SYSTEM -> ctx.getString(R.string.language_system_default)
        AppLocale.ENGLISH -> "English"
        AppLocale.SIMPLIFIED_CHINESE -> ctx.getString(R.string.about_lang_simplified)
        AppLocale.TRADITIONAL_CHINESE -> ctx.getString(R.string.about_lang_traditional)
        AppLocale.ARABIC -> "العربية"
        AppLocale.RUSSIAN -> "Русский"
        AppLocale.SPANISH -> "Español"
        AppLocale.UKRAINIAN -> "Українська"
        else -> ctx.getString(R.string.about_lang_simplified)
    }

    fun fill(card: LinearLayout) = buildVehicleColumn(card)
}
