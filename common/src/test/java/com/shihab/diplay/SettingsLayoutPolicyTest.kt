package com.shihab.diplay

import com.shihab.diplay.settings.SettingsInformationArchitecture
import com.shihab.diplay.settings.SettingsSection
import com.shihab.diplay.settings.SettingsShellPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** IA coverage for the glass three-page shell (replaces obsolete category-rail policy tests). */
class SettingsLayoutPolicyTest {
    @Test fun everySettingsSectionHasExactlyOneShellPage() {
        assertEquals(SettingsShellPage.entries.toSet(), SettingsInformationArchitecture.sectionsByPage.keys)
        val assignments = SettingsInformationArchitecture.sectionsByPage.values.flatten()
        assertEquals(SettingsSection.entries.toSet(), assignments.toSet())
        assertEquals(assignments.toSet().size, assignments.size)
    }

    @Test fun glassPagesMatchProductShell() {
        assertTrue(SettingsInformationArchitecture.sectionsByPage[SettingsShellPage.HOME]!!.contains(SettingsSection.CONNECTION_TABS))
        assertTrue(SettingsInformationArchitecture.sectionsByPage[SettingsShellPage.SETTINGS]!!.contains(SettingsSection.DISPLAY_AND_PERFORMANCE))
        assertTrue(SettingsInformationArchitecture.sectionsByPage[SettingsShellPage.ABOUT]!!.contains(SettingsSection.DIAGNOSTICS))
    }
}
