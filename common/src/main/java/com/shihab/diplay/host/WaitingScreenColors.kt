package com.shihab.diplay.host

import android.graphics.Color

/**
 * CarPlay 出画前等待屏跟随 day/night，避免夜间刺眼。
 * 色值与 [com.shihab.diplay.ui.GlassColors] 暗/浅令牌对齐。
 */
internal data class WaitingScreenColors(val background: Int, val text: Int, val secondary: Int) {
    companion object {
        fun of(night: Boolean): WaitingScreenColors = if (night) {
            WaitingScreenColors(
                background = Color.rgb(0x0A, 0x10, 0x18), // GlassColors.BACKGROUND dark
                text = Color.rgb(0xF1, 0xF5, 0xF9),       // GlassColors.INK dark
                secondary = Color.rgb(0x94, 0xA3, 0xB8),  // GlassColors.MUTED dark
            )
        } else {
            WaitingScreenColors(
                background = Color.rgb(0xEE, 0xF2, 0xF6), // GlassColors.BACKGROUND light
                text = Color.rgb(0x02, 0x06, 0x17),       // GlassColors.INK light
                secondary = Color.rgb(0x1E, 0x29, 0x3B),  // GlassColors.MUTED light
            )
        }
    }
}
