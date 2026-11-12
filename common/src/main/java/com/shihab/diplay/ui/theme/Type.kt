package com.shihab.diplay.ui.theme

/** Compact type scale using API 19 system fonts with full CJK fallback. Values are scaled pixels. */
object ShellType {
    const val DISPLAY_FAMILY = "sans-serif"
    const val BODY_FAMILY = "sans-serif"
    const val MONO_FAMILY = "monospace"

    const val CAPTION = 13f
    const val LABEL = 15f
    const val BODY = 17f
    const val TITLE = 20f
    const val HEADING = 24f
    const val DISPLAY = 32f
    const val HERO = 40f

    val scale: List<Float> = listOf(CAPTION, LABEL, BODY, TITLE, HEADING, DISPLAY, HERO)
}
