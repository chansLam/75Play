package com.shihab.diplay.ui.theme

/** Functional motion durations for the glassmorphism shell, in milliseconds. */
object ShellMotion {
    const val PRESS = 100L
    const val STATE = 200L
    const val LAYOUT = 350L
    const val MODAL = 500L

    val durations: List<Long> = listOf(PRESS, STATE, LAYOUT, MODAL)
}
