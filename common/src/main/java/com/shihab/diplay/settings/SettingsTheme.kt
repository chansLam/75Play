package com.shihab.diplay.settings

import android.content.Context
import android.graphics.Color

/**
 * 设置控件的两套呈现风格（与 GlassColors 暗/浅不同）：
 * - [CARD]：主应用玻璃卡片内（青蓝，对齐 GlassColors 暗色文字/强调）
 * - [OVERLAY]：CarPlay 浮层菜单（绿色，夜间 HUD 可读；与 MENU_* 一致）
 */
enum class SettingsTheme(
    val isOverlay: Boolean,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val accentTrack: Int,
    val trackOff: Int,
    val buttonText: Int,
) {
    CARD(
        isOverlay = false,
        textPrimary = Color.rgb(0xF1, 0xF5, 0xF9),   // GlassColors.INK dark
        textSecondary = Color.rgb(0x94, 0xA3, 0xB8), // GlassColors.MUTED dark
        accent = Color.rgb(0x22, 0xD3, 0xEE),         // GlassColors.ACCENT dark
        accentTrack = Color.rgb(0x15, 0x5E, 0x75),
        trackOff = Color.rgb(0x33, 0x41, 0x55),
        buttonText = Color.rgb(0x04, 0x2F, 0x2E),     // GlassColors.NAV_SELECTED_TEXT
    ),
    OVERLAY(
        isOverlay = true,
        textPrimary = Color.WHITE,
        textSecondary = Color.rgb(170, 180, 190),
        accent = Color.rgb(127, 205, 154),
        accentTrack = Color.rgb(78, 143, 102),
        trackOff = Color.rgb(64, 74, 80),
        buttonText = Color.rgb(8, 17, 11),
    );

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
