package com.shihab.diplay.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.settings.GlassSettingsColumn
import com.shihab.diplay.ui.settings.HardwareColumn
import com.shihab.diplay.ui.settings.SystemColumn

/**
 * About page: app notes, update and diagnostics, then system and hardware facts.
 */
class FragmentAbout : Fragment() {

    private val systemColumn = SystemColumn(this)
    private val hardwareColumn = HardwareColumn(this)

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
                ctx.getString(R.string.about_title_system) to { card: LinearLayout ->
                    systemColumn.fillAbout(card)
                },
                ctx.getString(R.string.about_update_section) to { card: LinearLayout ->
                    systemColumn.fillMaintenance(card)
                },
                ctx.getString(R.string.about_title_hardware) to { card: LinearLayout ->
                    hardwareColumn.fill(card)
                },
            ),
        )
        root.addView(cols, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }
}
