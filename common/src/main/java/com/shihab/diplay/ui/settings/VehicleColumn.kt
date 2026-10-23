// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.os.Build
import android.os.StatFs
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.AppLocale
import com.shihab.diplay.DiPlayBluetooth
import com.shihab.diplay.DiPlayPreferences
import com.shihab.diplay.WheelRootInjector
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.DiPlayDialog
import com.shihab.diplay.ui.DiPlayToast
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.GlassSwitch
import com.shihab.diplay.ui.LayoutMetrics

internal class VehicleColumn(page: Fragment) : AboutColumnKit(page) {
    private var wheelRootEnableGeneration = 0

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
        card.addView(settingRow(ctx, ctx.getString(R.string.about_wheel_keys_config), ghostButton(ctx, ctx.getString(R.string.about_configure)) { host.openWheelKeysSettings() }))
        card.addView(settingRow(ctx, ctx.getString(R.string.about_learn_siri_key), ghostButton(ctx, ctx.getString(R.string.about_learn_button)) {
            DiPlayDialog.showInfo(ctx, ctx.getString(R.string.about_learn_siri_key),
                ctx.getString(R.string.about_learn_siri_hint))
            WheelRootInjector.learn(ctx) { result ->
                val act = activity ?: return@learn
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    val learned = WheelRootInjector.learned(ctx)
                    val injectSuffix = if (learned != null) ctx.getString(R.string.about_root_inject_enabled)
                        else ctx.getString(R.string.about_root_inject_retry)
                    DiPlayDialog.showInfo(ctx, ctx.getString(R.string.about_wheel_root_inject),
                        ctx.getString(R.string.about_root_inject_result, result, injectSuffix))
                }
            }
        }))
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.about_wheel_siri_switch), WheelRootInjector.isEnabled(ctx)) { sw, on ->
            if (on && WheelRootInjector.learned(ctx) == null) {
                DiPlayDialog.showInfo(ctx, ctx.getString(R.string.about_need_learn_title),
                    ctx.getString(R.string.about_need_learn_hint))
                sw.setChecked(false, animate = true)
                return@glassSwitchRow
            }
            if (on) {
                val gen = ++wheelRootEnableGeneration
                Thread({
                    val ok = WheelRootInjector.setEnabled(ctx, true)
                    val act = activity
                    if (act == null) {
                        if (!WheelRootInjector.isEnabled(ctx)) WheelRootInjector.stop()
                        return@Thread
                    }
                    act.runOnUiThread {
                        if (!isAdded || gen != wheelRootEnableGeneration) {
                            if (!WheelRootInjector.isEnabled(ctx)) WheelRootInjector.stop()
                            return@runOnUiThread
                        }
                        if (!ok || !WheelRootInjector.isEnabled(ctx)) {
                            if (sw.isChecked) sw.setChecked(false, animate = true)
                            if (!ok) DiPlayDialog.toast(ctx, ctx.getString(R.string.diplay_root_failed_fallback))
                        }
                    }
                }, "wheel-root-enable").start()
            } else {
                wheelRootEnableGeneration++
                WheelRootInjector.setEnabled(ctx, false)
            }
        })
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

    /** 中列：语言 / 更新 / Root 方向盘。 */

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
