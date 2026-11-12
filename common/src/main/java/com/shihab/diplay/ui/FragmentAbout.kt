package com.shihab.diplay.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.settings.GlassSettingsPage
import com.shihab.diplay.ui.settings.HardwareColumn
import com.shihab.diplay.ui.settings.SystemColumn

/** Vehicle and devices: maintenance, product details, and hardware stay visible. */
class FragmentAbout : Fragment() {

    private val systemColumn = SystemColumn(this)
    private val hardwareColumn = HardwareColumn(this)

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
                GlassSettingsPage.Section(ctx.getString(R.string.shell_category_maintenance)) { card: LinearLayout ->
                    systemColumn.fillMaintenance(card)
                },
                GlassSettingsPage.Section(ctx.getString(R.string.shell_category_product)) { card: LinearLayout ->
                    systemColumn.fillAbout(card)
                },
                GlassSettingsPage.Section(ctx.getString(R.string.shell_category_hardware)) { card: LinearLayout ->
                    hardwareColumn.fill(card)
                },
            ),
        )
        root.addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }
}
