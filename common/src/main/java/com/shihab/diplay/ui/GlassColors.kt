package com.shihab.diplay.ui

import com.shihab.diplay.host.R
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.CarPlayNightMode

/**
 * 75Play 双主题色板。浅色用浅色令牌，其余用暗色。
 *
 * 底是座舱照片，只在边上压一点，让照片露出来。
 * 面板是半透玻璃，细边带一点青。选中条和连接球用实心电光青，球外有一圈光。
 *
 * ## 对照
 * | Token | 暗色 | 浅色 |
 * | --- | --- | --- |
 * | INK | `#F7FBFF` | `#102033` |
 * | BODY | `#D6E4F2` | `#243044` |
 * | MUTED | `#A9BDD4` | `#5A6D80` |
 * | DIM | `#7E96B0` | `#7A8B9C` |
 * | ACCENT | `#2EC6FF` | `#1496E8` |
 * | SURFACE | `#B30A1428` | `#D6FFFFFF` |
 * | BACKGROUND | `#071018` | `#E7EEF5` |
 */
object GlassColors {
    /** 由 DiPlayActivity 依据主题模式 Controller 维护。 */
    @Volatile
    var isDark = true

    // ---- 文字 ----
    /** 固定昼夜取色（不等当前 isDark）：WaitingScreenColors 等按显式 night 取色的场景用。 */
    fun inkFor(dark: Boolean): Int = if (dark) 0xFFF7FBFF.toInt() else 0xFF102033.toInt()
    val INK: Int get() = inkFor(isDark)
    val TEXT_BODY: Int get() = if (isDark) 0xFFD6E4F2.toInt() else 0xFF243044.toInt()
    val MUTED: Int get() = if (isDark) 0xFFA9BDD4.toInt() else 0xFF5A6D80.toInt()
    fun dimFor(dark: Boolean): Int = if (dark) 0xFF7E96B0.toInt() else 0xFF7A8B9C.toInt()
    val DIM: Int get() = dimFor(isDark)
    val TEXT_PRIMARY: Int get() = INK
    val TEXT_MUTED: Int get() = MUTED

    // ---- 强调（青蓝）----
    /** 主交互色：实心选中条、开关、连接球。夜间是电光青，日间收深一档。 */
    val ACCENT: Int get() = if (isDark) 0xFF2EC6FF.toInt() else 0xFF1496E8.toInt()
    /** 选中胶囊和主按钮的上沿，比 [ACCENT] 更亮。 */
    val ACCENT_LIFT: Int get() = if (isDark) 0xFF5EDCFF.toInt() else 0xFF3EC4F5.toInt()
    /** 选中胶囊和主按钮的下沿。 */
    val ACCENT_DEEP: Int get() = if (isDark) 0xFF1496E0.toInt() else 0xFF0E86D4.toInt()
    /** 次级强调。空闲状态用中性字，不再用亮青。 */
    val ACCENT2: Int get() = if (isDark) 0xFFD6E4F2.toInt() else 0xFF102033.toInt()
    /** cap / 值胶囊上的字。 */
    val CAP_TEXT: Int get() = INK
    val ACCENT_GRADIENT_START: Int get() = ACCENT
    val ACCENT_GRADIENT_END: Int get() = ACCENT
    /** 连接球：上沿接近白，中间电光青，下沿收成深蓝。球外一圈 [ORB_GLOW]。 */
    val ORB_LENS_TOP: Int get() = 0xFFB8F4FF.toInt()
    val ORB_LENS_MID: Int get() = 0xFF1AB6F2.toInt()
    val ORB_LENS_BOTTOM: Int get() = 0xFF0676C4.toInt()
    val ORB_GLOW: Int get() = 0xFF2EC6FF.toInt()
    val ORB_RIM: Int get() = 0x99FFFFFF.toInt()
    val ORB_HIGHLIGHT: Int get() = 0x66FFFFFF
    val ORB_HIGHLIGHT_FADE: Int get() = 0x00FFFFFF

    /** 很淡的强调底，压在夜景上仍能分开。 */
    val ACCENT_SOFT_FILL: Int get() = withAlpha(ACCENT, if (isDark) 0x47 else 0x2E)
    val ACCENT_CAP_FILL: Int get() = withAlpha(ACCENT, if (isDark) 0x5C else 0x38)
    val ACCENT_SOFT_STROKE: Int get() = withAlpha(ACCENT, if (isDark) 0xB3 else 0xCC)

    // ---- 状态 ----
    val WARNING: Int get() = if (isDark) 0xFFFB923C.toInt() else 0xFFC2410C.toInt()
    val SUCCESS: Int get() = if (isDark) 0xFF4ADE80.toInt() else 0xFF15803D.toInt()
    val ALERT: Int get() = if (isDark) 0xFFFBBF24.toInt() else 0xFFB45309.toInt()

    // ---- 导航 / 按钮字 ----
    val NAV_SELECTED: Int get() = ACCENT_SOFT_FILL
    /** 青蓝主按钮上的深青墨，两套主题共用。 */
    val NAV_SELECTED_TEXT: Int get() = 0xFF042F2E.toInt()
    val NAV_UNSELECTED: Int get() = if (isDark) 0xFFA9BDD4.toInt() else 0xFF3E5164.toInt()
    val BTN_SECONDARY_TEXT: Int get() = INK
    /** 连接方式 Tab 的轨道。选中项另用实心 [ACCENT]。 */
    val CHIP_IDLE_FILL: Int get() = if (isDark) 0x66101830 else 0x66FFFFFF
    val DIVIDER: Int get() = if (isDark) 0x332E8CFF else 0x33203348
    val OVERLAY: Int get() = if (isDark) 0xC0060A12.toInt() else 0x66102033
    fun backgroundFor(dark: Boolean): Int = if (dark) 0xFF071018.toInt() else 0xFFE7EEF5.toInt()
    val BACKGROUND: Int get() = backgroundFor(isDark)
    /** 导航条的兜底色。界面用 [dockBg]。 */
    val DOCK: Int get() = if (isDark) 0xB30A1428.toInt() else 0xD6FFFFFF.toInt()
    /** 顶栏里的小胶囊槽。 */
    val GLASS_CHIP: Int get() = if (isDark) 0x66203358 else 0xB8FFFFFF.toInt()
    /** 玻璃细边，带一点青，和夜景玻璃的亮边一致。 */
    val GLASS_RIM: Int get() = if (isDark) 0x4D7EB8FF else 0x99FFFFFF.toInt()
    /** 1dp 分隔线，跟着面板透。 */
    val GLASS_EDGE: Int get() = DIVIDER
    val SURFACE: Int get() = DOCK
    /** 未选中的小控件凹槽（勾选框等），不是卡片底。 */
    val CARD: Int get() = if (isDark) 0xA60C1830.toInt() else 0xC4FFFFFF.toInt()
    val CARD_ALT: Int get() = if (isDark) 0x8C081428.toInt() else 0x99FFFFFF.toInt()
    val INPUT: Int get() = if (isDark) 0xCC0A1424.toInt() else 0xE6FFFFFF.toInt()

    val BTN_PRIMARY: Int get() = if (isDark) R.drawable.btn_primary else R.drawable.btn_primary_light
    val BTN_PRIMARY_BORDER: Int get() = ACCENT
    val BTN_SECONDARY: Int get() = if (isDark) R.drawable.btn_secondary else R.drawable.btn_secondary_light
    val BTN_DANGER: Int get() = if (isDark) R.drawable.btn_danger else R.drawable.btn_danger_light
    val BTN_DANGER_BORDER: Int get() = if (isDark) 0xFF7F1D1D.toInt() else 0xFFFECACA.toInt()
    val BTN_SUCCESS: Int get() = if (isDark) 0xFF14532D.toInt() else 0xFFDCFCE7.toInt()
    val BTN_SUCCESS_BORDER: Int get() = if (isDark) 0xFF166534.toInt() else 0xFF86EFAC.toInt()

    /** 次级按钮的玻璃底。描边用 [GHOST_STROKE]，焦点传入 [ACCENT] 时加粗。 */
    val GHOST_FILL: Int get() = if (isDark) 0x8C12203A.toInt() else 0xB8FFFFFF.toInt()
    val GHOST_STROKE: Int get() = GLASS_RIM

    fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun bgHome(): Int = if (isDark) R.drawable.bg_home else R.drawable.bg_home_light
    fun dockBg(): Int = if (isDark) R.drawable.glass_bar else R.drawable.glass_bar_light
    fun cardBg(): Int = if (isDark) R.drawable.card_bg else R.drawable.card_bg_light
    fun dialogBg(): Int = if (isDark) R.drawable.dialog_bg else R.drawable.dialog_bg_light
    fun inputBg(): Int = if (isDark) R.drawable.input_bg else R.drawable.input_bg_light
    fun btnPrimary(): Int = BTN_PRIMARY
    fun btnSecondary(): Int = BTN_SECONDARY
    fun btnDanger(): Int = BTN_DANGER
    fun appTileBg(): Int = if (isDark) R.drawable.app_tile_bg else R.drawable.app_tile_bg_light
    fun navSelected(): Int = if (isDark) R.drawable.nav_item_selected else R.drawable.nav_item_selected_light
    fun toastBg(): Int = if (isDark) R.drawable.toast_bg else R.drawable.toast_bg_light

    fun ghostDrawable(context: android.content.Context, cornerRadiusDp: Int = 10, stroke: Int = GHOST_STROKE): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(if (isDark) 0xA6182C4E.toInt() else 0xE6FFFFFF.toInt(), GHOST_FILL),
        ).apply {
            // 焦点态传入 ACCENT：描边加粗到 2dp，方向盘选中时一眼可见。
            setStroke(maxOf(1, dp(context, if (stroke == ACCENT) 2 else 1)), stroke)
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


    // ---- 弹层 / 系统应用浮层（深色浮板，压在主屏之上）----
    /** 弹层面板底：近不透明蓝炭，比 [CARD] 更实，KitKat 无模糊也能压住底图。 */
    val POPUP_FILL: Int get() = if (isDark) 0xF2141C28.toInt() else 0xF4FFFFFF.toInt()
    /** 弹层细边：夜间淡青，日间淡墨。 */
    val POPUP_STROKE: Int get() = if (isDark) 0x33A8D4FF else 0x33203348
    /** 弹层背后的压暗 scrim。 */
    val POPUP_SCRIM: Int get() = if (isDark) 0xB3000000.toInt() else 0x80000000.toInt()
    /** 弹层内胶囊/次级动作钮的半透明底（关闭胶囊、Done 钮）。 */
    val POPUP_CHIP_FILL: Int get() = if (isDark) 0x28FFFFFF else 0x33102033

    // ---- 应用更新弹窗（固定 navy/cyan 品牌色，不随昼夜切换）----
    val UPDATE_CYAN: Int get() = 0xFF0ECDFA.toInt()
    val UPDATE_BLUE: Int get() = 0xFF0096F4.toInt()
    val UPDATE_WHITE: Int get() = 0xFFE0F5FF.toInt()
    val UPDATE_MUTED: Int get() = 0xFFA4BFDC.toInt()
    val UPDATE_FADED: Int get() = 0xFF6985A4.toInt()
    val UPDATE_DIVIDER: Int get() = 0xFF0F5278.toInt()
    val UPDATE_DARK: Int get() = 0xFF011125.toInt()
    private fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
}
