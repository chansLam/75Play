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
import com.shihab.diplay.ui.settings.GlassSettingsRows
import com.shihab.diplay.ui.settings.VehicleColumn
import com.shihab.diplay.ui.settings.VideoSettingsColumn

/**
 * Settings page: three cockpit columns. Picture and the former “other” rows share the last
 * column so a short card does not leave a hole over the wallpaper.
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
        val cols = GlassSettingsColumn.columns(
            ctx,
            listOf(
                ctx.getString(R.string.settings_display) to { card: LinearLayout ->
                    displayColumn.fill(card)
                },
                ctx.getString(R.string.audio) to { card: LinearLayout ->
                    audioColumn.fill(card)
                },
                ctx.getString(R.string.settings_advanced) to { card: LinearLayout ->
                    videoColumn.fill(card)
                    card.addView(GlassSettingsRows.divider(card.context))
                    GlassSettingsRows.cardTitle(card, ctx.getString(R.string.about_title_vehicle))
                    vehicleColumn.fill(card)
                },
            ),
        )
        root.addView(cols, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

}
