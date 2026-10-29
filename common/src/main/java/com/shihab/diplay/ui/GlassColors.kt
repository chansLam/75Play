package com.shihab.diplay.ui

import com.shihab.diplay.host.R
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.CarPlayNightMode
import com.shihab.diplay.followSystemNight

/**
 * 75Play 双主题。两套都是能透出座舱底图的玻璃，不再用实心色块。
 *
 * 面板不描边。夜景是一层更深的炭色罩，日景是一层能透出底图的薄白，不要铺成实底。
 * 日景标题和正文是纯黑，次级字用同一支黑、只降低不透明度。主操作是系统蓝，蓝底上的字是白。
 *
 * ## 对照
 * | Token | 暗色 | 浅色 |
 * | --- | --- | --- |
 * | INK | `#F5F5F7` | `#000000` |
 * | BODY | `#E5E5EA` | `#000000` |
 * | MUTED | `#AEAEB2` | `#B3000000` |
 * | DIM | `#8E8E93` | `#80000000` |
 * | ACCENT | `#0A84FF` | `#007AFF` |
 * | SURFACE | `#CC1C1C1E` | `#BFFFFFFF` |
 * | BACKGROUND | `#000000` | `#F2F2F7` |
 */
object GlassColors {
    /** 由 DiPlayActivity 依据主题模式 Controller 维护。 */
    @Volatile
    var isDark = true

    // ---- 文字 ----
    /** 固定昼夜取色（不等当前 isDark）：WaitingScreenColors 等按显式 night 取色的场景用。 */
    fun inkFor(dark: Boolean): Int = if (dark) 0xFFF5F5F7.toInt() else 0xFF000000.toInt()
    val INK: Int get() = inkFor(isDark)
    val TEXT_BODY: Int get() = if (isDark) 0xFFE5E5EA.toInt() else 0xFF000000.toInt()
    val MUTED: Int get() = if (isDark) 0xFFAEAEB2.toInt() else 0xB3000000.toInt()
    fun dimFor(dark: Boolean): Int = if (dark) 0xFF8E8E93.toInt() else 0x80000000.toInt()
    val DIM: Int get() = dimFor(isDark)
    val TEXT_PRIMARY: Int get() = INK
    val TEXT_MUTED: Int get() = MUTED

    // ---- 强调（系统蓝）----
    /** 主交互色：选中胶囊、开关、连接钮。夜间用 iOS 深色模式蓝，日间用系统蓝。 */
    val ACCENT: Int get() = if (isDark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    /** 选中胶囊和主按钮的上沿。 */
    val ACCENT_LIFT: Int get() = if (isDark) 0xFF64B4FF.toInt() else 0xFF4DA2FF.toInt()
    /** 选中胶囊和主按钮的下沿。 */
    val ACCENT_DEEP: Int get() = if (isDark) 0xFF0060CC.toInt() else 0xFF0056C7.toInt()
    /** 次级强调。空闲状态用中性字。 */
    val ACCENT2: Int get() = if (isDark) 0xFFE5E5EA.toInt() else 0xFF000000.toInt()
    /** cap / 值胶囊上的字。 */
    val CAP_TEXT: Int get() = INK
    val ACCENT_GRADIENT_START: Int get() = ACCENT
    val ACCENT_GRADIENT_END: Int get() = ACCENT
    /** 玻璃高光。连接钮自己画圆片，这些留给仍引用它们的旧绘制。 */
    val ORB_LENS_TOP: Int get() = 0xF2FFFFFF.toInt()
    val ORB_LENS_MID: Int get() = ACCENT
    val ORB_LENS_BOTTOM: Int get() = ACCENT_DEEP
    val ORB_GLOW: Int get() = ACCENT
    val ORB_RIM: Int get() = plateRim()
    val ORB_HIGHLIGHT: Int get() = plateSheen()
    val ORB_HIGHLIGHT_FADE: Int get() = 0x00FFFFFF

    /** 值胶囊的蓝底。夜景更深，日景是白霜上的一层淡蓝。 */
    val ACCENT_SOFT_FILL: Int get() = withAlpha(ACCENT, if (isDark) 0x55 else 0x24)
    val ACCENT_CAP_FILL: Int get() = withAlpha(ACCENT, if (isDark) 0x72 else 0x3D)
    val ACCENT_SOFT_STROKE: Int get() = withAlpha(ACCENT, if (isDark) 0xCC else 0xE6)

    // ---- 状态 ----
    val WARNING: Int get() = if (isDark) 0xFFFF9F0A.toInt() else 0xFFC93400.toInt()
    val SUCCESS: Int get() = if (isDark) 0xFF30D158.toInt() else 0xFF248A3D.toInt()
    val ALERT: Int get() = if (isDark) 0xFFFFD60A.toInt() else 0xFFB8860B.toInt()

    // ---- 导航 / 按钮字 ----
    /** 选中导航是一块蓝玻璃。日景淡一些，黑字才站得住。 */
    val NAV_SELECTED: Int get() = withAlpha(ACCENT, if (isDark) 0x73 else 0x47)
    /** 系统蓝主按钮上的白字，两套主题共用。 */
    val NAV_SELECTED_TEXT: Int get() = 0xFFFFFFFF.toInt()
    val NAV_UNSELECTED: Int get() = if (isDark) 0xFFAEAEB2.toInt() else 0xCC000000.toInt()
    val BTN_SECONDARY_TEXT: Int get() = INK
    /** 连接方式分段的轨道。夜景提亮，日景在白霜上压一层淡墨，选中项另用实心蓝。 */
    val CHIP_IDLE_FILL: Int get() = if (isDark) 0x33FFFFFF else 0x14000000
    val DIVIDER: Int get() = if (isDark) 0x33FFFFFF else 0x381C1C1E
    val OVERLAY: Int get() = if (isDark) 0x99000000.toInt() else 0x59000000
    fun backgroundFor(dark: Boolean): Int = if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    val BACKGROUND: Int get() = backgroundFor(isDark)
    /** 导航条。界面用 [dockBg]。 */
    val DOCK: Int get() = if (isDark) 0xCC1C1C1E.toInt() else 0xBFFFFFFF.toInt()
    /** 顶栏里的小胶囊槽。夜景提亮，日景在白霜上压一层淡墨。 */
    val GLASS_CHIP: Int get() = if (isDark) 0x3DFFFFFF else 0x16000000
    /** 发丝边。焦点改用 [ACCENT] 加粗。 */
    val GLASS_RIM: Int get() = plateRim()
    /** 1dp 分隔线，两头淡出。浅色用墨线，否则会消失在日景里。 */
    val GLASS_EDGE: Int get() = if (isDark) 0x2EFFFFFF else 0x28000000
    val SURFACE: Int get() = DOCK
    /** 未选中的小控件凹槽（勾选框等），不是卡片底。 */
    val CARD: Int get() = if (isDark) 0x24FFFFFF else 0x12000000
    val CARD_ALT: Int get() = if (isDark) 0x14FFFFFF else 0x0A000000
    val INPUT: Int get() = if (isDark) 0x66000000 else 0x14000000

    val BTN_PRIMARY: Int get() = if (isDark) R.drawable.btn_primary else R.drawable.btn_primary_light
    val BTN_PRIMARY_BORDER: Int get() = ACCENT
    val BTN_SECONDARY: Int get() = if (isDark) R.drawable.btn_secondary else R.drawable.btn_secondary_light
    val BTN_DANGER: Int get() = if (isDark) R.drawable.btn_danger else R.drawable.btn_danger_light
    val BTN_DANGER_BORDER: Int get() = if (isDark) 0xFFFF6961.toInt() else 0xFFFF3B30.toInt()
    val BTN_SUCCESS: Int get() = if (isDark) 0xFF14532D.toInt() else 0xFFDCFCE7.toInt()
    val BTN_SUCCESS_BORDER: Int get() = if (isDark) 0xFF166534.toInt() else 0xFF86EFAC.toInt()

    /** 次级按钮的玻璃底。不描边，靠填充从面板上浮起来。 */
    val GHOST_FILL: Int get() = if (isDark) 0x33FFFFFF else 0x14000000
    val GHOST_STROKE: Int get() = plateRim()

    /** 面板填充。夜景更沉。日景留出底图，不要铺成一片白。 */
    fun plateFill(): Int = if (isDark) 0xB31C1C1E.toInt() else 0xA0FFFFFF.toInt()
    /** 玻璃上沿。和 [plateFill] 相同，避免再盖出半截。 */
    fun plateTop(): Int = plateFill()
    /** 玻璃下沿。和 [plateFill] 相同。 */
    fun plateBottom(): Int = plateFill()
    /** 不再单独铺高光。保留给仍引用它的绘制，浓度跟玻璃本身一致。 */
    fun plateSheen(): Int = plateFill()
    /** 发丝边。暗色是白边，浅色是墨边，否则会融进日景。 */
    fun plateRim(): Int = if (isDark) 0x73FFFFFF else 0x801C1C1E.toInt()

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

    fun ghostDrawable(context: android.content.Context, cornerRadiusDp: Int = 14, stroke: Int = GHOST_STROKE): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(GHOST_FILL)
            if (stroke == ACCENT) setStroke(maxOf(1, dp(context, 2)), stroke)
            cornerRadius = dp(context, cornerRadiusDp).toFloat()
        }

    /** 依据设置页主题模式（五档）更新 isDark，返回是否变化。 */
    fun syncWithNightMode(context: android.content.Context): Boolean {
        val mode = AirPlayPersistence.loadCarPlayNightMode(context)
        val dark = when (mode) {
            CarPlayNightMode.NIGHT -> true
            CarPlayNightMode.DAY -> false
            CarPlayNightMode.SYSTEM -> context.followSystemNight() == true
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


    // ---- 弹层 / 系统应用浮层（压在主屏之上，仍留一点底图）----
    /** 弹层面板：比卡片更实，字才读得清，浅色仍留一点底图。 */
    val POPUP_FILL: Int get() = if (isDark) 0xF21C1C1E.toInt() else 0xF7FFFFFF.toInt()
    /** 弹层不再另描一圈。方向盘焦点仍用 [ACCENT]。 */
    val POPUP_STROKE: Int get() = 0x00000000
    /** 弹层背后的压暗。 */
    val POPUP_SCRIM: Int get() = if (isDark) 0x99000000.toInt() else 0x59000000
    /** 弹层内胶囊/次级动作钮的半透明底（关闭胶囊、Done 钮）。 */
    val POPUP_CHIP_FILL: Int get() = if (isDark) 0x33FFFFFF else 0x33007AFF

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
