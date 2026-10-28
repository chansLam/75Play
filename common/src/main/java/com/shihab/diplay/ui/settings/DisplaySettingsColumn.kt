// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.CarPlayDock
import com.shihab.diplay.InterfaceSize
import com.shihab.diplay.airplay.CarPlaySize
import com.shihab.diplay.airplay.SafeAreaRect
import com.shihab.diplay.compat.realSdkInt
import com.shihab.diplay.host.R
import com.shihab.diplay.localizedLabel
import com.shihab.diplay.ui.DiPlayDialog
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.GlassSwitch
import com.shihab.diplay.ui.LayoutMetrics

internal class DisplaySettingsColumn(page: Fragment) : SettingsColumnKit(page) {
    /** 系统栏开关引用：Fragment 被壳层 show/hide 缓存后，onResume 需要按 prefs 重同步视觉态。 */
    private var hideTopBarSwitch: GlassSwitch? = null
    private var hideBottomBarSwitch: GlassSwitch? = null

    private fun buildDisplayCard(card: LinearLayout) {
        val ctx = card.context
        val curSize = CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(ctx))
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_display_size), capPick(ctx, curSize.localizedLabel(ctx)) { cap -> showCarPlaySizePicker(cap) }))
        val scalePercent = AirPlayPersistence.loadDisplayScalePercent(ctx)
        card.addView(settingRow(ctx, ctx.getString(R.string.setting_display_scale), capPick(ctx, "$scalePercent%") { cap -> showScalePicker(cap) }))
        val fpsValues = if (realSdkInt() <= 19) {
            listOf(15, 30)
        } else {
            listOf(15, 30, 45, 60)
        }
        val fps = buildList {
            add(ctx.getString(R.string.setting_fps_min_savings))
            add(ctx.getString(R.string.setting_fps_more_savings))
            if (fpsValues.size > 2) {
                add(ctx.getString(R.string.setting_fps_balanced))
                add(ctx.getString(R.string.setting_fps_smooth))
            }
        }
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
        card.addView(settingRow(ctx, ctx.getString(R.string.settings_interface_size), capPick(ctx, InterfaceSize.displayName(ctx, InterfaceSize.preference(ctx))) { cap -> showInterfaceSizePicker(cap) }))
        card.addView(settingRow(ctx, ctx.getString(R.string.safe_area), capPick(ctx, safeAreaSummary(ctx)) { cap -> showSafeAreaEditor(cap) }))
        card.addView(reconnectSwitchRow(ctx, ctx.getString(R.string.draw_outside_safe_area), AirPlayPersistence.loadSafeAreaDrawOutside(ctx)) { on ->
            AirPlayPersistence.saveSafeAreaDrawOutside(ctx, on)
            host.reconnectIfRunning()
        })
        card.addView(settingRow(ctx, ctx.getString(R.string.carplay_dock), capPick(ctx, dockLabel(ctx, CarPlayDock.load(ctx))) { cap -> showDockPicker(cap) }))
        card.addView(reconnectSwitchRow(ctx, ctx.getString(R.string.setting_left_system_dock_inset), AirPlayPersistence.loadLeftSystemDockInset(ctx)) { on ->
            AirPlayPersistence.saveLeftSystemDockInset(ctx, on)
            host.reconnectIfRunning()
        })
        card.addView(divider(ctx))
        val hideTopRow = glassSwitchRow(ctx, ctx.getString(R.string.setting_hide_top_bar), AirPlayPersistence.loadHideTopBar(ctx)) { AirPlayPersistence.saveHideTopBar(ctx, it) }
        card.addView(hideTopRow)
        hideTopBarSwitch = (hideTopRow as LinearLayout).findSwitch()
        val hideBottomRow = glassSwitchRow(ctx, ctx.getString(R.string.setting_hide_bottom_bar), AirPlayPersistence.loadHideBottomBar(ctx)) { AirPlayPersistence.saveHideBottomBar(ctx, it) }
        card.addView(hideBottomRow)
        hideBottomBarSwitch = (hideBottomRow as LinearLayout).findSwitch()
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_root_hide_navbar), AirPlayPersistence.loadRootHideNavbar(ctx)) { host.setRootHideNavbar(it) })
        card.addView(glassSwitchRow(ctx, ctx.getString(R.string.setting_adapt_pip_resolution), AirPlayPersistence.loadAdaptPipResolution(ctx)) { AirPlayPersistence.saveAdaptPipResolution(ctx, it) })
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

    private fun showInterfaceSizePicker(anchor: View) {
        val ctx = anchor.context
        val choices = InterfaceSize.CHOICES
        val labels = choices.map { InterfaceSize.displayName(ctx, it) }
        val cur = InterfaceSize.preference(ctx)
        showDropdown(anchor, labels, choices.indexOf(cur).coerceAtLeast(0), syncAnchorText = false) { idx ->
            GlassSettingsRows.confirmReconnect(host, ctx) {
                InterfaceSize.save(ctx, choices[idx])
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

    /** glassSwitchRow 的行是 LinearLayout，开关是其直接子 View。 */
    private fun LinearLayout.findSwitch(): GlassSwitch? =
        (0 until childCount).map { getChildAt(it) }.filterIsInstance<GlassSwitch>().firstOrNull()

    /**
     * 设置页 onResume 时调用：快捷面板等其它入口可能在本页 pause 期间改了系统栏开关。
     * 只按 prefs 刷新视觉态并临时摘掉 listener，避免把「读到的值」当用户操作再保存一次。
     */
    internal fun syncSystemBarSwitchesFromPrefs() {
        hideTopBarSwitch?.let { syncSwitch(it, AirPlayPersistence.loadHideTopBar(it.context)) }
        hideBottomBarSwitch?.let { syncSwitch(it, AirPlayPersistence.loadHideBottomBar(it.context)) }
    }

    private fun syncSwitch(sw: GlassSwitch, value: Boolean) {
        val listener = sw.onCheckedChangeListener
        sw.onCheckedChangeListener = null
        sw.setChecked(value, animate = false)
        sw.onCheckedChangeListener = listener
    }

    fun fill(card: LinearLayout) = buildDisplayCard(card)
}
