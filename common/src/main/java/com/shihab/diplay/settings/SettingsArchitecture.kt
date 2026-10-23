package com.shihab.diplay.settings

/**
 * Information-architecture map for the glass shell UI.
 *
 * Not a render path — [com.shihab.diplay.ui.FragmentHome], [com.shihab.diplay.ui.FragmentSetting],
 * and [com.shihab.diplay.ui.FragmentAbout] own the UI. This object documents which settings
 * sections belong on which shell page so tests and AGENTS.md stay aligned.
 */
internal enum class SettingsShellPage {
    HOME,
    SETTINGS,
    ABOUT,
}

/** A logical settings card. Every entry needs one page in [SettingsInformationArchitecture]. */
internal enum class SettingsSection {
    CONNECTION_TABS,
    HEAD_UNIT_APPS,
    DISPLAY_AND_PERFORMANCE,
    EXPERIMENTAL_DISPLAY,
    ADVANCED_MEDIA,
    AUDIO_ROUTING,
    VEHICLE_CONTROLS,
    WHEEL_KEYS,
    SYSTEM,
    DIAGNOSTICS,
    ABOUT,
    LANGUAGE,
}

/**
 * Maps glass shell pages to setting sections.
 *
 * - Home: connection centre + app grid
 * - Settings: display as the lead column, audio stacked over advanced video
 * - About (车辆): vehicle as the lead column, system stacked over hardware
 */
internal object SettingsInformationArchitecture {
    val sectionsByPage: Map<SettingsShellPage, Set<SettingsSection>> = mapOf(
        SettingsShellPage.HOME to setOf(
            SettingsSection.CONNECTION_TABS,
            SettingsSection.HEAD_UNIT_APPS,
        ),
        SettingsShellPage.SETTINGS to setOf(
            SettingsSection.DISPLAY_AND_PERFORMANCE,
            SettingsSection.EXPERIMENTAL_DISPLAY,
            SettingsSection.ADVANCED_MEDIA,
            SettingsSection.AUDIO_ROUTING,
        ),
        SettingsShellPage.ABOUT to setOf(
            SettingsSection.VEHICLE_CONTROLS,
            SettingsSection.WHEEL_KEYS,
            SettingsSection.SYSTEM,
            SettingsSection.DIAGNOSTICS,
            SettingsSection.ABOUT,
            SettingsSection.LANGUAGE,
        ),
    )
}
