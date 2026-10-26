package com.shihab.diplay.settings

import android.content.Context
import android.graphics.Color

/**
 * 设置控件的两套呈现风格（与 GlassColors 暗/浅不同）：
 * - [CARD]：主应用面板内（系统蓝，对齐 GlassColors 暗色文字/强调）
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
        textPrimary = Color.rgb(0xF5, 0xF5, 0xF7),
        textSecondary = Color.rgb(0x8E, 0x8E, 0x93),
        accent = Color.rgb(0x0A, 0x84, 0xFF),
        accentTrack = Color.rgb(0x3A, 0x3A, 0x3C),
        trackOff = Color.rgb(0x48, 0x48, 0x4A),
        buttonText = Color.WHITE,
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
