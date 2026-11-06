package com.shihab.diplay.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.CarPlayNightMode
import com.shihab.diplay.followSystemNight
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.theme.ShellColors
import com.shihab.diplay.ui.theme.ShellShape
import com.shihab.diplay.ui.theme.ShellStroke
import com.shihab.diplay.ui.theme.ShellUpdateColors

/** Compatibility facade that sources every shell role from the approved theme palette. */
object GlassColors {
    @Volatile
    var isDark = true

    private val scheme get() = ShellColors.forNight(isDark)
    private fun scheme(dark: Boolean) = ShellColors.forNight(dark)

    fun inkFor(dark: Boolean): Int = scheme(dark).onSurface
    val INK: Int get() = scheme.onSurface
    val TEXT_BODY: Int get() = scheme.onSurface
    val MUTED: Int get() = scheme.onSurfaceVariant
    fun dimFor(dark: Boolean): Int = scheme(dark).outline
    val DIM: Int get() = scheme.outline
    val TEXT_PRIMARY: Int get() = scheme.onSurface
    val TEXT_MUTED: Int get() = scheme.onSurfaceVariant

    val ACCENT: Int get() = scheme.primary
    val ACCENT_LIFT: Int get() = scheme.primary
    val ACCENT_DEEP: Int get() = scheme.primaryContainer
    val ACCENT2: Int get() = scheme.secondary
    val CAP_TEXT: Int get() = scheme.onPrimaryContainer
    val ACCENT_GRADIENT_START: Int get() = scheme.primary
    val ACCENT_GRADIENT_END: Int get() = scheme.primary
    val ORB_LENS_TOP: Int get() = scheme.primary
    val ORB_LENS_MID: Int get() = scheme.primary
    val ORB_LENS_BOTTOM: Int get() = scheme.primary
    val ORB_GLOW: Int get() = scheme.primary
    val ORB_RIM: Int get() = scheme.outlineVariant
    val ORB_HIGHLIGHT: Int get() = scheme.primary
    val ORB_HIGHLIGHT_FADE: Int get() = ShellColors.TRANSPARENT

    val ACCENT_SOFT_FILL: Int get() = scheme.primaryContainer
    val ACCENT_CAP_FILL: Int get() = scheme.primaryContainer
    val ACCENT_SOFT_STROKE: Int get() = scheme.primary
    val PRIMARY_CONTAINER_TEXT: Int get() = scheme.onPrimaryContainer
    val CONTROL_HANDLE: Int get() = scheme.onPrimary
    val TRANSPARENT: Int get() = ShellColors.TRANSPARENT

    val WARNING: Int get() = scheme.warning
    val SUCCESS: Int get() = scheme.success
    val SUCCESS_TEXT: Int get() = scheme.onSuccessContainer
    val ALERT: Int get() = scheme.warning

    val NAV_SELECTED: Int get() = scheme.primaryContainer
    val NAV_SELECTED_TEXT: Int get() = scheme.onPrimary
    val NAV_SELECTED_CONTAINER_TEXT: Int get() = scheme.onPrimaryContainer
    val NAV_UNSELECTED: Int get() = scheme.onSurfaceVariant
    val BTN_SECONDARY_TEXT: Int get() = scheme.onSurface
    val CHIP_IDLE_FILL: Int get() = scheme.surfaceVariant
    val DIVIDER: Int get() = scheme.outlineVariant
    val OVERLAY: Int get() = scheme.scrim
    fun backgroundFor(dark: Boolean): Int = scheme(dark).background
    val BACKGROUND: Int get() = scheme.background
    val DOCK: Int get() = scheme.surfaceContainerLow
    val GLASS_CHIP: Int get() = scheme.surfaceVariant
    val GLASS_RIM: Int get() = scheme.outlineVariant
    val GLASS_EDGE: Int get() = scheme.outlineVariant
    val SURFACE: Int get() = scheme.surfaceContainerLow
    val CARD: Int get() = scheme.surfaceContainerHigh
    val CARD_ALT: Int get() = scheme.surfaceContainer
    val INPUT: Int get() = scheme.surfaceContainerHighest

    val BTN_PRIMARY: Int get() = if (isDark) R.drawable.btn_primary else R.drawable.btn_primary_light
    val BTN_PRIMARY_BORDER: Int get() = scheme.primary
    val BTN_SECONDARY: Int get() = if (isDark) R.drawable.btn_secondary else R.drawable.btn_secondary_light
    val BTN_DANGER: Int get() = if (isDark) R.drawable.btn_danger else R.drawable.btn_danger_light
    val BTN_DANGER_BORDER: Int get() = scheme.error
    val BTN_DANGER_TEXT: Int get() = scheme.onErrorContainer
    val BTN_SUCCESS: Int get() = scheme.successContainer
    val BTN_SUCCESS_BORDER: Int get() = scheme.success

    val GHOST_FILL: Int get() = scheme.surfaceContainerHighest
    val GHOST_STROKE: Int get() = scheme.outlineVariant

    fun plateFill(): Int = scheme.surfaceContainer
    fun plateTop(): Int = scheme.surfaceContainer
    fun plateBottom(): Int = scheme.surfaceContainer
    fun plateSheen(): Int = scheme.surfaceContainer
    fun plateRim(): Int = scheme.outlineVariant

    fun dp(context: Context, value: Int): Int =
        LayoutMetrics.dp(context, value)

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

    fun ghostDrawable(
        context: Context,
        cornerRadiusDp: Int = ShellShape.SMALL,
        stroke: Int = GHOST_STROKE,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(GHOST_FILL)
        if (stroke != TRANSPARENT) {
            val width = if (stroke == ACCENT) ShellStroke.FOCUS else ShellStroke.HAIRLINE
            setStroke(maxOf(1, dp(context, width)), stroke)
        }
        setCornerRadius(dp(context, cornerRadiusDp).toFloat())
    }

    fun syncWithNightMode(context: Context): Boolean {
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
        if (dark == isDark) return false
        isDark = dark
        return true
    }

    val POPUP_FILL: Int get() = scheme.surfaceContainerHigh
    val POPUP_STROKE: Int get() = ShellColors.TRANSPARENT
    val POPUP_SCRIM: Int get() = scheme.scrim
    val POPUP_CHIP_FILL: Int get() = scheme.secondaryContainer

    val UPDATE_CYAN: Int get() = ShellUpdateColors.CYAN
    val UPDATE_BLUE: Int get() = ShellUpdateColors.BLUE
    val UPDATE_WHITE: Int get() = ShellUpdateColors.WHITE
    val UPDATE_MUTED: Int get() = ShellUpdateColors.MUTED
    val UPDATE_FADED: Int get() = ShellUpdateColors.FADED
    val UPDATE_DIVIDER: Int get() = ShellUpdateColors.DIVIDER
    val UPDATE_DARK: Int get() = ShellUpdateColors.DARK

    fun withAlpha(argb: Int, alpha: Int): Int = ShellColors.withAlpha(argb, alpha)
}
