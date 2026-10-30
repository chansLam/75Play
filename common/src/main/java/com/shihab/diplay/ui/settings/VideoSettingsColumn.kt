// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.AmbientLightThreshold
import com.shihab.diplay.CarPlayNightMode
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.DiPlayDialog

internal class VideoSettingsColumn(page: Fragment) : SettingsColumnKit(page) {
    private fun buildVideoAdvancedCard(card: LinearLayout) {
        val ctx = card.context
        val nightModes = listOf(
            CarPlayNightMode.SYSTEM, CarPlayNightMode.AMBIENT, CarPlayNightMode.DAY,
            CarPlayNightMode.NIGHT, CarPlayNightMode.SCHEDULE,
        )
        val curMode = AirPlayPersistence.loadCarPlayNightMode(ctx)
        val curModeIdx = nightModes.indexOf(curMode).let { if (it < 0) 0 else it }
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_theme_mode), capPick(ctx, nightModeLabel(ctx, curMode)) { cap ->
            showDropdown(cap, nightModes.map { nightModeLabel(ctx, it) }, curModeIdx) { idx ->
                AirPlayPersistence.saveCarPlayNightMode(ctx, nightModes[idx])
                host.rebuildTheme()
            }
        }))
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_ambient_light_threshold), capPick(ctx, "${AirPlayPersistence.loadAmbientLightThreshold(ctx).lux} lux") { promptAmbientLightThreshold(ctx) }))
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_ambient_delay), capPick(ctx, ctx.getString(R.string.setting_seconds_value, AirPlayPersistence.loadAmbientDelaySeconds(ctx))) { cap -> showAmbientDelayPicker(cap) }))
        card.addView(reconnectSwitchRow(ctx, ctx.getString(R.string.setting_smooth_video), AirPlayPersistence.loadSmoothVideo(ctx)) { on ->
            AirPlayPersistence.saveSmoothVideo(ctx, on); host.reconnectIfRunning()
        })
    }

    private fun nightModeLabel(ctx: android.content.Context, mode: CarPlayNightMode): String = when (mode) {
        CarPlayNightMode.SYSTEM -> ctx.getString(R.string.setting_night_follow_system)
        CarPlayNightMode.AMBIENT -> ctx.getString(R.string.setting_night_ambient)
        CarPlayNightMode.DAY -> ctx.getString(R.string.setting_night_light)
        CarPlayNightMode.NIGHT -> ctx.getString(R.string.setting_night_dark)
        CarPlayNightMode.SCHEDULE -> ctx.getString(R.string.setting_night_schedule)
    }

    private fun promptAmbientLightThreshold(ctx: android.content.Context) {
        val cur = AirPlayPersistence.loadAmbientLightThreshold(ctx).lux
        DiPlayDialog.showInput(
            ctx,
            ctx.getString(R.string.setting_ambient_light_threshold),
            "${AmbientLightThreshold.MIN_LUX}-${AmbientLightThreshold.MAX_LUX} lux",
            cur.toString(),
            save = ctx.getString(R.string.set),
            cancel = ctx.getString(R.string.cancel),
            validator = { v ->
                v.toIntOrNull()?.let {
                    if (AmbientLightThreshold.isValid(it)) null
                    else ctx.getString(R.string.setting_threshold_range_error, AmbientLightThreshold.MIN_LUX, AmbientLightThreshold.MAX_LUX)
                } ?: ctx.getString(R.string.setting_input_enter_number)
            },
        ) { v -> AirPlayPersistence.saveAmbientLightThreshold(ctx, AmbientLightThreshold(v.toInt())) }
    }

    private fun showAmbientDelayPicker(anchor: View) {
        val ctx = anchor.context
        val secondsValues = (0..60 step 2).toList()
        val options = secondsValues.map { ctx.getString(R.string.setting_seconds_value, it) }
        val cur = AirPlayPersistence.loadAmbientDelaySeconds(ctx).coerceIn(0, 60)
        showDropdown(anchor, options, cur / 2) { idx ->
            AirPlayPersistence.saveAmbientDelaySeconds(ctx, secondsValues[idx])
        }
    }


    fun fill(card: LinearLayout) = buildVideoAdvancedCard(card)
}
