// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.CarPlayDock
import com.shihab.diplay.DiPlayPreferences
import com.shihab.diplay.airplay.CarPlaySize
import com.shihab.diplay.airplay.SafeAreaRect
import com.shihab.diplay.host.R
import com.shihab.diplay.host.TemperatureBar
import com.shihab.diplay.localizedLabel
import com.shihab.diplay.ui.DiPlayDialog

internal class DisplaySettingsColumn(page: Fragment) : SettingsColumnKit(page) {
    private fun buildDisplayCard(card: LinearLayout) {
        val ctx = card.context
        val curSize = CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(ctx))
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_display_size), capPick(ctx, curSize.localizedLabel(ctx)) { cap -> showCarPlaySizePicker(cap) }))
        val scalePercent = AirPlayPersistence.loadDisplayScalePercent(ctx)
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_display_scale), capPick(ctx, "$scalePercent%") { cap -> showScalePicker(cap) }))
        // 45/60 fps 在慢车机上会卡顿，iOS 26 的部分 CarPlay 显示设置又依赖上报帧率，所以一律可选。
        val fpsValues = listOf(15, 30, 45, 60)
        val fps = listOf(
            ctx.getString(R.string.setting_fps_min_savings),
            ctx.getString(R.string.setting_fps_more_savings),
            ctx.getString(R.string.setting_fps_balanced),
            ctx.getString(R.string.setting_fps_smooth),
        )
        val fpsCur = AirPlayPersistence.loadFps(ctx)
        val fpsIdx = fpsValues.indexOf(fpsCur).takeIf { it >= 0 }
            ?: fpsValues.indexOf(30).coerceAtLeast(0)
        card.addView(settingRow(ctx, ctx.getString(R.string.frame_rate), capPick(ctx, fps[fpsIdx]) { cap ->
            showDropdown(cap, fps, fpsIdx, syncAnchorText = false) { idx ->
                GlassSettingsRows.confirmReconnect(host, ctx) {
                    AirPlayPersistence.saveFps(ctx, fpsValues[idx])
                    cap.text = fps[idx] + " ▾"
                    host.reconnectIfRunning()
                }
            }
        }))
        card.addView(settingRow(ctx, ctx.getString(R.string.safe_area), capPick(ctx, safeAreaSummary(ctx)) { cap -> showSafeAreaEditor(cap) }))
        card.addView(reconnectSwitchRow(ctx, ctx.getString(R.string.draw_outside_safe_area), AirPlayPersistence.loadSafeAreaDrawOutside(ctx)) { on ->
            AirPlayPersistence.saveSafeAreaDrawOutside(ctx, on)
            host.reconnectIfRunning()
        })
        card.addView(settingRow(ctx, ctx.getString(R.string.carplay_dock), capPick(ctx, dockLabel(ctx, CarPlayDock.load(ctx))) { cap -> showDockPicker(cap) }))
        // 全局顶部温度栏（室外/车内/设定）。即时显隐，无需重连。
        card.addView(glassSwitchRow(
            ctx,
            ctx.getString(R.string.settings_temperature_bar),
            DiPlayPreferences.temperatureBarEnabled(ctx),
        ) { on ->
            DiPlayPreferences.saveTemperatureBarEnabled(ctx, on)
            val app = ctx.applicationContext
            if (on) TemperatureBar.show(app) else TemperatureBar.hide(app)
        })
    }

    private fun dockLabel(ctx: android.content.Context, dock: CarPlayDock): String = when (dock) {
        CarPlayDock.AUTOMATIC -> ctx.getString(R.string.carplay_dock_automatic)
        CarPlayDock.DRIVER_SIDE -> ctx.getString(R.string.carplay_dock_driver_side)
        CarPlayDock.BOTTOM -> ctx.getString(R.string.carplay_dock_bottom)
    }

    private fun showDockPicker(anchor: View) {
        val ctx = anchor.context
        val docks = CarPlayDock.entries
        val labels = docks.map { dockLabel(ctx, it) }
        showDropdown(anchor, labels, docks.indexOf(CarPlayDock.load(ctx)).coerceAtLeast(0), syncAnchorText = false) { idx ->
            GlassSettingsRows.confirmReconnect(host, ctx) {
                CarPlayDock.save(ctx, docks[idx])
                (anchor as? TextView)?.text = labels[idx] + " ▾"
                host.reconnectIfRunning()
            }
        }
    }

    /** 中列：视频编解码 / Dock / 环境光等进阶项。 */

    private fun showScalePicker(anchor: View) {
        val ctx = anchor.context
        val scales = listOf(50, 75, 100, 125, 150)
        val cur = AirPlayPersistence.loadDisplayScalePercent(ctx)
        val labels = scales.map { "$it%" }
        showDropdown(anchor, labels, scales.indexOf(cur).coerceAtLeast(0), syncAnchorText = false) { idx ->
            GlassSettingsRows.confirmReconnect(host, ctx) {
                AirPlayPersistence.saveDisplayScalePercent(ctx, scales[idx])
                (anchor as? TextView)?.text = labels[idx] + " ▾"
                host.reconnectIfRunning()
            }
        }
    }

    private fun showCarPlaySizePicker(anchor: View) {
        val ctx = anchor.context
        val sizes = CarPlaySize.entries
        val current = CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(ctx))
        val labels = sizes.map { it.localizedLabel(ctx) }
        showDropdown(anchor, labels, sizes.indexOf(current).coerceAtLeast(0), syncAnchorText = false) { idx ->
            GlassSettingsRows.confirmReconnect(host, ctx) {
                AirPlayPersistence.saveWidthPhysicalMm(ctx, sizes[idx].widthMillimeters)
                (anchor as? TextView)?.text = labels[idx] + " ▾"
                host.reconnectIfRunning()
            }
        }
    }

    private fun activitySizePx(): Pair<Int, Int> {
        val dm = android.util.DisplayMetrics()
        requireActivity().windowManager.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
    }

    private fun safeAreaOrFull(ctx: android.content.Context, w: Int, h: Int): com.shihab.diplay.airplay.SafeAreaRect =
        AirPlayPersistence.loadSafeAreaRect(ctx, w, h)
            ?: com.shihab.diplay.airplay.AirPlaySafeArea.full(w, h)

    private fun safeAreaSummary(ctx: android.content.Context): String {
        val (w, h) = activitySizePx()
        val rect = AirPlayPersistence.loadSafeAreaRect(ctx, w, h) ?: return ctx.getString(R.string.safe_area_full)
        return "${rect.width}×${rect.height}"
    }

    private fun showSafeAreaEditor(anchor: View) {
        val ctx = anchor.context
        val (w, h) = activitySizePx()
        // 未自定义时用全屏矩形作编辑底稿；禁止 SafeAreaRect(0,0,0,0)（会抛非空矩形异常）。
        val currentRect = safeAreaOrFull(ctx, w, h)
        val edges = listOf(
            ctx.getString(R.string.setting_edge_left, currentRect.left),
            ctx.getString(R.string.setting_edge_top, currentRect.top),
            ctx.getString(R.string.setting_edge_right, currentRect.right),
            ctx.getString(R.string.setting_edge_bottom, currentRect.bottom),
        )
        showDropdown(anchor, edges, 0, syncAnchorText = false) { idx ->
            val cur = safeAreaOrFull(ctx, w, h)
            val current = listOf(cur.left, cur.top, cur.right, cur.bottom)[idx]
            val maxForAxis = if (idx == 0 || idx == 2) w else h
            DiPlayDialog.showInput(
                ctx,
                edges[idx],
                "0-$maxForAxis px",
                current.toString(),
                save = ctx.getString(R.string.set),
                cancel = ctx.getString(R.string.cancel),
                validator = { v ->
                    v.toIntOrNull()?.let {
                        if (it in 0..maxForAxis) null
                        else ctx.getString(R.string.setting_safe_area_range_error, maxForAxis)
                    } ?: ctx.getString(R.string.setting_input_enter_number)
                },
            ) { v ->
                GlassSettingsRows.confirmReconnect(host, ctx) {
                    val r = safeAreaOrFull(ctx, w, h)
                    val raw = v.toInt()
                    val next = when (idx) {
                        0 -> r.copy(left = raw.coerceIn(0, r.right - 1))
                        1 -> r.copy(top = raw.coerceIn(0, r.bottom - 1))
                        2 -> r.copy(right = raw.coerceIn(r.left + 1, w))
                        else -> r.copy(bottom = raw.coerceIn(r.top + 1, h))
                    }
                    AirPlayPersistence.saveSafeAreaRect(ctx, w, h, next)
                    (anchor as? TextView)?.text = safeAreaSummary(ctx) + " ▾"
                    host.reconnectIfRunning()
                }
            }
        }
    }

    fun fill(card: LinearLayout) = buildDisplayCard(card)
}
