// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.shell

/**
 * Owns shell page switching for [com.shihab.diplay.DiPlayActivity].
 *
 * Fragment swap / nav highlight stay in the Activity via [applyPage]; this class holds
 * page constants, current page, and the show / back / home entry points.
 */
class ShellNavigator(
    initialPage: String = HOME,
    private val applyPage: (String) -> Unit,
) {
    var page: String = initialPage
        private set

    fun showPage(target: String) {
        page = target
        applyPage(target)
    }

    fun navigateBack() = showPage(HOME)

    fun showHome() = showPage(HOME)

    fun isHome(): Boolean = page == HOME

    companion object {
        const val HOME = "home"
        const val SETTINGS = "settings"
        const val ABOUT = "about"
    }
}
