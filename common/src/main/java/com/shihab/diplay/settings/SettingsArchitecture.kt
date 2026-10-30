package com.shihab.diplay.settings

/**
 * Information-architecture map for the glass shell UI.
 *
 * Not a render path — [com.shihab.diplay.ui.FragmentHome], [com.shihab.diplay.ui.FragmentSetting],
 * and [com.shihab.diplay.ui.FragmentAbout] own the UI. This object documents which settings
 * sections belong on which shell page so tests and AGENTS.md stay aligned. When the shell
 * regroups columns, update this map (do not invent UI from it).
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
    ADVANCED_MEDIA,
    AUDIO_ROUTING,
    VEHICLE_CONTROLS,
    WHEEL_KEYS,
    LANGUAGE,
    SYSTEM,
    DIAGNOSTICS,
    ABOUT,
}

/**
 * Maps glass shell pages to setting sections (matches live columns as of v2.0.2+).
 *
 * - Home: connection centre + app grid ([com.shihab.diplay.ui.FragmentHome])
 * - Settings: display, audio, then picture with the other behavior rows under it
 *   ([com.shihab.diplay.ui.FragmentSetting] → Display, Audio, Video + Vehicle)
 * - About: app notes, update and diagnostics, then hardware facts
 *   ([com.shihab.diplay.ui.FragmentAbout] → three columns)
 */
internal object SettingsInformationArchitecture {
    val sectionsByPage: Map<SettingsShellPage, Set<SettingsSection>> = mapOf(
        SettingsShellPage.HOME to setOf(
            SettingsSection.CONNECTION_TABS,
            SettingsSection.HEAD_UNIT_APPS,
        ),
        SettingsShellPage.SETTINGS to setOf(
            SettingsSection.DISPLAY_AND_PERFORMANCE,
            SettingsSection.ADVANCED_MEDIA,
            SettingsSection.AUDIO_ROUTING,
            SettingsSection.VEHICLE_CONTROLS,
            SettingsSection.WHEEL_KEYS,
            SettingsSection.LANGUAGE,
        ),
        SettingsShellPage.ABOUT to setOf(
            SettingsSection.SYSTEM,
            SettingsSection.DIAGNOSTICS,
            SettingsSection.ABOUT,
        ),
    )
}
