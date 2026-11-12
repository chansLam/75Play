package com.shihab.diplay.ui.theme

/** Five liquid-glass shape roles in density-independent pixels. */
object ShellShape {
    const val EXTRA_SMALL = 8
    const val SMALL = 12
    const val MEDIUM = 16
    const val LARGE = 20
    const val EXTRA_LARGE = 24

    val scale: List<Int> = listOf(EXTRA_SMALL, SMALL, MEDIUM, LARGE, EXTRA_LARGE)
}

/** Outline widths are separate from the 4dp spacing grid. */
object ShellStroke {
    const val HAIRLINE = 1
    const val FOCUS = 2
}
