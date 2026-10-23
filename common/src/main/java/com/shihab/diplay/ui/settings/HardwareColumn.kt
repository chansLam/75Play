// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.widget.LinearLayout
import androidx.fragment.app.Fragment

internal class HardwareColumn(page: Fragment) : AboutColumnKit(page) {
    fun fill(card: LinearLayout) = AboutInfoBlock.fill(card)
}
