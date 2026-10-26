// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.AmbientLightThreshold
import com.shihab.diplay.CarPlayNightMode
import com.shihab.diplay.CarPlayPicture
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.DiPlayDialog
import com.shihab.diplay.ui.GlassSlider

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
        card.addView(divider(ctx))
        card.addView(reconnectSwitchRow(ctx, ctx.getString(R.string.setting_efficient_video_hevc), AirPlayPersistence.loadHevcEnabled(ctx)) { on ->
            AirPlayPersistence.saveHevcEnabled(ctx, on); host.reconnectIfRunning()
        })
        card.addView(reconnectSwitchRow(ctx, ctx.getString(R.string.setting_smooth_video), AirPlayPersistence.loadSmoothVideo(ctx)) { on ->
            AirPlayPersistence.saveSmoothVideo(ctx, on); host.reconnectIfRunning()
        })
        card.addView(reconnectSwitchRow(ctx, ctx.getString(R.string.setting_direct_surface_view), AirPlayPersistence.loadDirectSurfaceView(ctx)) { on ->
            AirPlayPersistence.saveDirectSurfaceView(ctx, on);             host.reconnectIfRunning()
        })
        card.addView(divider(ctx))
        addPictureAdjustments(card)
    }

    private fun nightModeLabel(ctx: android.content.Context, mode: CarPlayNightMode): String = when (mode) {
        CarPlayNightMode.SYSTEM -> ctx.getString(R.string.setting_night_follow_system)
        CarPlayNightMode.AMBIENT -> ctx.getString(R.string.setting_night_ambient)
        CarPlayNightMode.DAY -> ctx.getString(R.string.setting_night_light)
        CarPlayNightMode.NIGHT -> ctx.getString(R.string.setting_night_dark)
        CarPlayNightMode.SCHEDULE -> ctx.getString(R.string.setting_night_schedule)
    }

    /** Shell writes the same prefs the live host texture already observes. */
    private fun addPictureAdjustments(card: LinearLayout) {
        val ctx = card.context
        val prefs = CarPlayPicture.preferences(ctx)
        val labels = listOf(
            R.string.picture_brightness,
            R.string.picture_contrast,
            R.string.picture_saturation,
            R.string.picture_warmth,
        )
        val sliders = mutableListOf<Pair<String, SeekBar>>()
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_picture_adjust), capPick(ctx, ctx.getString(R.string.picture_reset)) {
            CarPlayPicture.reset(prefs)
            sliders.forEach { (key, slider) ->
                slider.progress = CarPlayPicture.defaultValue(key) - CarPlayPicture.range(key).first
            }
        }))
        val rowH = com.shihab.diplay.ui.LayoutMetrics.rowMinH(ctx)
        CarPlayPicture.keys.forEachIndexed { index, key ->
            val range = CarPlayPicture.range(key)
            val caption = TextView(ctx).apply {
                setTextColor(com.shihab.diplay.ui.GlassColors.TEXT_BODY)
                textSize = com.shihab.diplay.ui.LayoutMetrics.settingsRowTextSize(ctx)
                includeFontPadding = false
                maxLines = 1
            }
            fun show(value: Int) {
                caption.text = "${ctx.getString(labels[index])} $value"
            }
            show(CarPlayPicture.value(prefs, key))
            val slider = GlassSlider(ctx).apply {
                max = range.last - range.first
                progress = CarPlayPicture.value(prefs, key) - range.first
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = progress + range.first
                        show(value)
                        if (fromUser) prefs.edit().putInt(key, value).apply()
                    }
                    override fun onStartTrackingTouch(seek: SeekBar) = Unit
                    override fun onStopTrackingTouch(seek: SeekBar) = Unit
                })
            }
            sliders += key to slider
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = rowH
            }
            row.addView(caption, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))
            row.addView(slider, LinearLayout.LayoutParams(0, rowH, 1.1f))
            card.addView(row)
        }
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
