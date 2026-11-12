package com.shihab.diplay.ui.theme

/** API 19 View theme entry point. Dynamic wallpaper color is intentionally unsupported. */
object ShellTheme {
    const val BRAND_SEED = "#1856FF"
    const val DYNAMIC_COLOR = false

    fun colors(night: Boolean): ShellColorScheme = ShellColors.forNight(night)
}
