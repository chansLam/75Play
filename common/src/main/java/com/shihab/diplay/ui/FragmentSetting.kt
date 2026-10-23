package com.shihab.diplay.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.settings.AudioSettingsColumn
import com.shihab.diplay.ui.settings.DisplaySettingsColumn
import com.shihab.diplay.ui.settings.GlassSettingsColumn
import com.shihab.diplay.ui.settings.VehicleColumn
import com.shihab.diplay.ui.settings.VideoSettingsColumn

/**
 * Settings page: display, audio, picture, then the behavior settings moved off the about page.
 * Reconnect-required rows still go through GlassSettingsRows.reconnectSwitchRow.
 */
class FragmentSetting : Fragment() {

    private val displayColumn = DisplaySettingsColumn(this)
    private val videoColumn = VideoSettingsColumn(this)
    private val audioColumn = AudioSettingsColumn(this)
    private val vehicleColumn = VehicleColumn(this)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val ctx = requireContext()
        val pad = LayoutMetrics.pagePad(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0)
            setPadding(pad, pad, pad, pad)
        }
        val cols = GlassSettingsColumn.stage(
            ctx,
            ctx.getString(R.string.settings_display) to { card: LinearLayout ->
                displayColumn.fill(card)
            },
            listOf(
                ctx.getString(R.string.audio) to { card: LinearLayout ->
                    audioColumn.fill(card)
                },
                ctx.getString(R.string.settings_advanced) to { card: LinearLayout ->
                    videoColumn.fill(card)
                },
                ctx.getString(R.string.about_title_vehicle) to { card: LinearLayout ->
                    vehicleColumn.fill(card)
                },
            ),
            listOf(1.35f, 0.75f),
        )
        root.addView(cols, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onResume() {
        super.onResume()
        // 本页被壳层 show/hide 缓存：从快捷面板等其它入口改了系统栏开关后回到本页时，
        // 按 prefs 重同步两个开关的视觉态（只刷新开关，不重建设置列、不破坏滚动位置）。
        displayColumn.syncSystemBarSwitchesFromPrefs()
    }

}
