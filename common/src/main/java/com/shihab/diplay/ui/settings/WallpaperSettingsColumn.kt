// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.DiPlayDialog
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.LayoutMetrics
import com.shihab.diplay.wallpaper.WallpaperBitmaps
import com.shihab.diplay.wallpaper.WallpaperCatalog
import com.shihab.diplay.wallpaper.WallpaperDailySource
import com.shihab.diplay.wallpaper.WallpaperMode
import com.shihab.diplay.wallpaper.WallpaperStore
import java.time.LocalDate
import java.util.concurrent.Executors

internal class WallpaperSettingsColumn(
    page: Fragment,
    private val pickImage: () -> Unit,
) : SettingsColumnKit(page) {
    private val io = Executors.newSingleThreadExecutor()
    private val store get() = WallpaperStore(requireContext())
    private var previousMode = WallpaperMode.DEFAULT
    private lateinit var modeCap: TextView
    private lateinit var pickRow: View
    private lateinit var pickCap: TextView
    private lateinit var sourceRow: View
    private lateinit var sourceCap: TextView
    private lateinit var statusView: TextView

    fun fill(card: LinearLayout) {
        val ctx = card.context
        modeCap = capPick(ctx, modeLabel(store.mode())) { showModePicker(it) }
        card.addView(settingRow(ctx, ctx.getString(R.string.settings_wallpaper), modeCap))
        pickCap = capPick(ctx, pickLabel()) {
            previousMode = store.mode()
            pickImage()
        }
        pickRow = settingRow(ctx, ctx.getString(R.string.settings_wallpaper_pick), pickCap)
        card.addView(pickRow)
        sourceCap = capPick(ctx, sourceLabel(store.source())) { showSourcePicker(it) }
        statusView = TextView(ctx).apply {
            textSize = LayoutMetrics.settingsCapTextSize(ctx)
            setTextColor(GlassColors.TEXT_MUTED)
            gravity = Gravity.END
        }
        val sourceBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            isClickable = true
            setOnClickListener { sourceCap.performClick() }
            addView(sourceCap)
            addView(statusView)
        }
        sourceRow = settingRow(ctx, ctx.getString(R.string.settings_wallpaper_source), sourceBox)
        card.addView(sourceRow)
        val searchCap = capPick(ctx, ctx.getString(R.string.settings_wallpaper_search_action)) { openSearch() }
        card.addView(settingRow(ctx, ctx.getString(R.string.settings_wallpaper_search), searchCap))
        render()
    }

    fun refreshStatus() {
        if (!::pickCap.isInitialized) return
        render()
    }

    fun onPickerResult(uri: Uri?) {
        if (uri == null) {
            store.saveMode(WallpaperCatalog.modeAfterPickerCancel(previousMode, store.hasCustom()))
            render()
            return
        }
        val ctx = requireContext()
        io.execute {
            val incoming = store.incomingFile()
            val copied = ctx.contentResolver.openInputStream(uri)?.use {
                WallpaperBitmaps.copyStream(it, incoming)
            } == true
            val bitmap = if (copied) WallpaperBitmaps.decodeFile(incoming) else null
            val saved = bitmap != null && store.writeCustom(bitmap)
            bitmap?.recycle()
            incoming.delete()
            val hostActivity = activity ?: return@execute
            hostActivity.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (saved) {
                    store.saveMode(WallpaperMode.CUSTOM)
                    host.applyShellWallpaper()
                } else {
                    DiPlayDialog.showInfo(
                        ctx,
                        ctx.getString(R.string.settings_wallpaper),
                        ctx.getString(R.string.settings_wallpaper_decode_failed),
                    )
                }
                render()
            }
        }
    }

    private fun openSearch() {
        WallpaperSearchDialog.show(requireContext()) {
            host.applyShellWallpaper()
            render()
        }
    }

    private fun showModePicker(anchor: TextView) {
        val modes = listOf(WallpaperMode.DEFAULT, WallpaperMode.CUSTOM, WallpaperMode.DAILY)
        showDropdown(anchor, modes.map { modeLabel(it) }, modes.indexOf(store.mode()).coerceAtLeast(0)) { index ->
            when (val next = modes[index]) {
                WallpaperMode.CUSTOM -> if (store.hasCustom()) {
                    store.saveMode(WallpaperMode.CUSTOM)
                    host.applyShellWallpaper()
                } else {
                    previousMode = store.mode()
                    pickImage()
                }
                WallpaperMode.DAILY -> {
                    store.saveMode(next)
                    host.applyShellWallpaper()
                    host.refreshDailyWallpaper()
                }
                WallpaperMode.DEFAULT -> {
                    store.saveMode(next)
                    host.applyShellWallpaper()
                }
            }
            render()
        }
    }

    private fun showSourcePicker(anchor: TextView) {
        val sources = listOf(WallpaperDailySource.BING, WallpaperDailySource.WALLHAVEN)
        showDropdown(anchor, sources.map { sourceLabel(it) }, sources.indexOf(store.source()).coerceAtLeast(0)) { index ->
            store.saveSource(sources[index])
            host.applyShellWallpaper()
            host.refreshDailyWallpaper()
            render()
        }
    }

    private fun render() {
        val ctx = modeCap.context
        val mode = store.mode()
        val rows = WallpaperCatalog.rows(mode)
        modeCap.text = modeLabel(mode) + " ▾"
        pickCap.text = pickLabel() + " ▾"
        sourceCap.text = sourceLabel(store.source()) + " ▾"
        statusView.text = when (
            WallpaperCatalog.dailyStatus(store.dailyDay(), LocalDate.now().toString(), store.hasDaily())
        ) {
            com.shihab.diplay.wallpaper.DailyStatus.UPDATED_TODAY -> ctx.getString(R.string.settings_wallpaper_status_today)
            com.shihab.diplay.wallpaper.DailyStatus.KEEPING_PREVIOUS -> ctx.getString(R.string.settings_wallpaper_status_previous)
            com.shihab.diplay.wallpaper.DailyStatus.USING_DEFAULT -> ctx.getString(R.string.settings_wallpaper_status_default)
        }
        pickRow.visibility = if (rows.pick) View.VISIBLE else View.GONE
        sourceRow.visibility = if (rows.source) View.VISIBLE else View.GONE
    }

    private fun modeLabel(mode: WallpaperMode): String {
        val ctx = requireContext()
        return when (mode) {
            WallpaperMode.DEFAULT -> ctx.getString(R.string.settings_wallpaper_default)
            WallpaperMode.CUSTOM -> ctx.getString(R.string.settings_wallpaper_custom)
            WallpaperMode.DAILY -> ctx.getString(R.string.settings_wallpaper_daily)
        }
    }

    private fun sourceLabel(source: WallpaperDailySource): String {
        val ctx = requireContext()
        return when (source) {
            WallpaperDailySource.BING -> ctx.getString(R.string.settings_wallpaper_source_bing)
            WallpaperDailySource.WALLHAVEN -> ctx.getString(R.string.settings_wallpaper_source_wallhaven)
        }
    }

    private fun pickLabel(): String {
        val ctx = requireContext()
        return ctx.getString(if (store.hasCustom()) R.string.settings_wallpaper_picked else R.string.settings_wallpaper_unset)
    }
}
