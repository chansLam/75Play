package com.shihab.diplay.ui

import com.shihab.diplay.host.R
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.CarPlayNightMode

/**
 * 75Play 双主题色板（青蓝品牌色 + Slate 中性色）。
 *
 * API 19 无真实 blur：玻璃靠半透填充 + 描边 + 顶部高光模拟。
 * 主题由「主题模式」驱动：浅色 → 浅色令牌；其余 → 暗色。
 *
 * ## 设计原则
 * - 暗/浅共用同一色相家族，只调明度与饱和度，避免两套「互不相关」的蓝。
 * - 文字四级：Ink → Body → Muted → Dim；浅色整体加深一档，对抗车内实景底图。
 * - 暗/浅卡片都半透玻璃；弹层/下拉约 90% 实底挡实景。字色靠提亮/加深保可读。
 * - 交互强调色一律从 [ACCENT] 派生（软底 / 描边 / 渐变端），禁止各处硬编码旧 #37E0FF。
 *
 * ## 对照
 * | Token | 暗色 | 浅色 |
 * | --- | --- | --- |
 * | INK | `#F1F5F9` | `#020617` |
 * | BODY | `#CBD5E1` | `#0F172A` |
 * | MUTED | `#94A3B8` | `#1E293B` |
 * | DIM | `#64748B` | `#334155` |
 * | ACCENT | `#22D3EE` | `#0891B2` |
 * | ACCENT2 / CAP | `#67E8F9` | `#083344`（浅色值标签用近墨，避免青底上发飘） |
 * | SURFACE | `#121A26` | `#F8FAFC` |
 * | BACKGROUND | `#0A1018` | `#EEF2F6` |
 */
object GlassColors {
    /** 由 DiPlayActivity 依据主题模式 Controller 维护。 */
    @Volatile
    var isDark = true

    // ---- 文字（Slate 阶）----
    // 浅色用近黑墨，半透玻璃 + 车内实景下仍够对比（不改玻璃底）。
    val INK: Int get() = if (isDark) 0xFFF8FAFC.toInt() else 0xFF020617.toInt()
    /** 暗色提亮一档，对抗方向盘实景透底。 */
    val TEXT_BODY: Int get() = if (isDark) 0xFFE2E8F0.toInt() else 0xFF0F172A.toInt()
    val MUTED: Int get() = if (isDark) 0xFFCBD5E1.toInt() else 0xFF1E293B.toInt()
    val DIM: Int get() = if (isDark) 0xFF94A3B8.toInt() else 0xFF334155.toInt()
    val TEXT_PRIMARY: Int get() = INK
    val TEXT_MUTED: Int get() = MUTED

    // ---- 品牌强调（Cyan）----
    /** 主交互色：开关开、选中、链接。 */
    val ACCENT: Int get() = if (isDark) 0xFF22D3EE.toInt() else 0xFF0891B2.toInt()
    /** 暗色：更亮的渐变/高亮；浅色：深蓝墨，作值标签字。 */
    val ACCENT2: Int get() = if (isDark) 0xFFA5F3FC.toInt() else 0xFF083344.toInt()
    /** cap / 值胶囊上的字（浅色强制近墨，避免青底发白）。 */
    val CAP_TEXT: Int get() = if (isDark) ACCENT2 else 0xFF020617.toInt()
    val ACCENT_GRADIENT_START: Int get() = ACCENT
    val ACCENT_GRADIENT_END: Int get() = if (isDark) 0xFFCFFAFE.toInt() else 0xFF22D3EE.toInt()

    /** rgba(accent, ~18% 暗 / ~28% 浅)：pill / chip 底 */
    val ACCENT_SOFT_FILL: Int get() = withAlpha(ACCENT, if (isDark) 0x2E else 0x47)
    /** rgba(accent, ~28% 暗 / ~40% 浅)：cap 底，挡实景 */
    val ACCENT_CAP_FILL: Int get() = withAlpha(ACCENT, if (isDark) 0x47 else 0x66)
    /** rgba(accent, ~55% 暗 / ~70% 浅)：cap / pill 描边 */
    val ACCENT_SOFT_STROKE: Int get() = withAlpha(ACCENT, if (isDark) 0x8C else 0xB3)

    // ---- 状态 ----
    val WARNING: Int get() = if (isDark) 0xFFFB923C.toInt() else 0xFFC2410C.toInt()
    val SUCCESS: Int get() = if (isDark) 0xFF4ADE80.toInt() else 0xFF166534.toInt()
    val ALERT: Int get() = if (isDark) 0xFFFBBF24.toInt() else 0xFFB45309.toInt()

    // ---- 导航 / 按钮字 ----
    val NAV_SELECTED: Int get() = ACCENT_SOFT_FILL
    /** 青蓝选中底上的字：深青墨，两侧主题共用。 */
    val NAV_SELECTED_TEXT: Int get() = 0xFF042F2E.toInt()
    /** 未选中导航：浅色直接用 Ink；暗色用 Body。 */
    val NAV_UNSELECTED: Int get() = if (isDark) 0xFFCBD5E1.toInt() else 0xFF0F172A.toInt()
    val BTN_SECONDARY_TEXT: Int get() = if (isDark) 0xFFF1F5F9.toInt() else 0xFF0F172A.toInt()
    /** 未选中连接方式 Tab 等闲置芯片底。 */
    val CHIP_IDLE_FILL: Int get() = if (isDark) 0x3DFFFFFF.toInt() else 0x66FFFFFF.toInt()

    // ---- 面 / 分隔 ----
    val DIVIDER: Int get() = if (isDark) 0x28FFFFFF.toInt() else 0x330F172A.toInt()
    val OVERLAY: Int get() = if (isDark) 0xB3000000.toInt() else 0x730F172A.toInt()
    /** 纯色兜底（无背景图时）。 */
    val BACKGROUND: Int get() = if (isDark) 0xFF0A1018.toInt() else 0xFFEEF2F6.toInt()
    /** 弹层基准实色（drawable 约 90% alpha）。 */
    val SURFACE: Int get() = if (isDark) 0xFF121A26.toInt() else 0xFFF8FAFC.toInt()
    val CARD: Int get() = if (isDark) 0x3D2A4A6E.toInt() else 0x554A7AA0.toInt()
    val CARD_ALT: Int get() = if (isDark) 0x33182030.toInt() else 0x403D6688.toInt()
    val INPUT: Int get() = if (isDark) 0xE60F1620.toInt() else 0xE6D0DCEB.toInt()

    val BTN_PRIMARY: Int get() = if (isDark) R.drawable.btn_primary else R.drawable.btn_primary_light
    val BTN_PRIMARY_BORDER: Int get() = if (isDark) 0xFF334155.toInt() else 0xFFBAE6FD.toInt()
    val BTN_SECONDARY: Int get() = if (isDark) R.drawable.btn_secondary else R.drawable.btn_secondary_light
    val BTN_DANGER: Int get() = if (isDark) R.drawable.btn_danger else R.drawable.btn_danger_light
    val BTN_DANGER_BORDER: Int get() = if (isDark) 0xFF7F1D1D.toInt() else 0xFFFECACA.toInt()
    val BTN_SUCCESS: Int get() = if (isDark) 0xFF14532D.toInt() else 0xFFDCFCE7.toInt()
    val BTN_SUCCESS_BORDER: Int get() = if (isDark) 0xFF166534.toInt() else 0xFF86EFAC.toInt()

    /**
     * 次级/幽灵按钮：必须比卡片底更亮一档 + 可见描边，否则在玻璃上会「消失」。
     * 暗色用抬升白底；浅色用实一点的 slate 芯片；描边跟 ACCENT 软描边同系。
     */
    val GHOST_FILL: Int get() = if (isDark) 0xCC243044.toInt() else 0xE6E8EEF4.toInt()
    val GHOST_STROKE: Int get() = if (isDark) withAlpha(ACCENT, 0xB3) else withAlpha(ACCENT, 0xA0)

    fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun bgHome(): Int = if (isDark) R.drawable.bg_home else R.drawable.bg_home_light
    fun cardBg(): Int = if (isDark) R.drawable.card_bg else R.drawable.card_bg_light
    fun dialogBg(): Int = if (isDark) R.drawable.dialog_bg else R.drawable.dialog_bg_light
    fun inputBg(): Int = if (isDark) R.drawable.input_bg else R.drawable.input_bg_light
    fun btnPrimary(): Int = BTN_PRIMARY
    fun btnSecondary(): Int = BTN_SECONDARY
    fun btnDanger(): Int = BTN_DANGER
    fun appTileBg(): Int = if (isDark) R.drawable.app_tile_bg else R.drawable.app_tile_bg_light
    fun navSelected(): Int = if (isDark) R.drawable.nav_item_selected else R.drawable.nav_item_selected_light
    fun toastBg(): Int = if (isDark) R.drawable.toast_bg else R.drawable.toast_bg_light

    fun ghostDrawable(context: android.content.Context, cornerRadiusDp: Int = 12): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(GHOST_FILL)
            // 约 1.5dp：短屏/低密度至少 2px，保证青描边可见
            setStroke(maxOf(2, (1.5f * context.resources.displayMetrics.density).toInt()), GHOST_STROKE)
            cornerRadius = dp(context, cornerRadiusDp).toFloat()
        }

    /** 依据设置页主题模式（五档）更新 isDark，返回是否变化。 */
    fun syncWithNightMode(context: android.content.Context): Boolean {
        val mode = AirPlayPersistence.loadCarPlayNightMode(context)
        val dark = when (mode) {
            CarPlayNightMode.NIGHT -> true
            CarPlayNightMode.DAY -> false
            else -> {
                val uiMode = context.resources.configuration.uiMode
                (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
        if (dark != isDark) {
            isDark = dark
            return true
        }
        return false
    }

    private fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
}
