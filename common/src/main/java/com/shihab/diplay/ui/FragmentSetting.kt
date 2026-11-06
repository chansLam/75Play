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
import com.shihab.diplay.ui.settings.GlassSettingsPage
import com.shihab.diplay.ui.settings.VehicleColumn
import com.shihab.diplay.ui.settings.VideoSettingsColumn

/** Control centre: every settings group is visible in one full-width workbench. */
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
            setBackgroundColor(GlassColors.TRANSPARENT)
            setPadding(pad, pad, pad, pad)
        }
        val page = GlassSettingsPage.create(
            ctx,
            listOf(
                GlassSettingsPage.Section(ctx.getString(R.string.settings_display)) { card: LinearLayout ->
                    displayColumn.fill(card)
                },
                GlassSettingsPage.Section(ctx.getString(R.string.audio)) { card: LinearLayout ->
                    audioColumn.fill(card)
                },
                GlassSettingsPage.Section(ctx.getString(R.string.shell_category_picture_theme)) { card: LinearLayout ->
                    videoColumn.fill(card)
                },
                GlassSettingsPage.Section(ctx.getString(R.string.shell_category_vehicle_controls)) { card: LinearLayout ->
                    vehicleColumn.fill(card)
                },
            ),
        )
        root.addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

}
