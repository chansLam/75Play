// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadUnitVolumeStreamTest {
    @Test fun navigationWinsWhileAPromptIsPlayingOverMedia() {
        assertEquals(10, HEAD_UNIT_NAVIGATION_STREAM)
        assertEquals(HEAD_UNIT_NAVIGATION_STREAM, headUnitVolumeStream(navigationActive = true, mediaActive = true))
        assertEquals(HEAD_UNIT_NAVIGATION_STREAM, headUnitVolumeStream(navigationActive = true, mediaActive = false))
        assertEquals(HEAD_UNIT_NAVIGATION_STREAM, headUnitVolumeStream(navigationActive = true, mediaActive = null))
    }

    @Test fun mediaIsUsedWhenNavigationIsQuiet() {
        assertEquals(3, AudioManager.STREAM_MUSIC)
        assertEquals(AudioManager.STREAM_MUSIC, headUnitVolumeStream(navigationActive = false, mediaActive = true))
        assertEquals(AudioManager.STREAM_MUSIC, headUnitVolumeStream(navigationActive = null, mediaActive = true))
    }

    @Test fun silenceFallsBackToTheHeadUnitMasterVolume() {
        assertNull(headUnitVolumeStream(navigationActive = false, mediaActive = false))
        assertNull(headUnitVolumeStream(navigationActive = null, mediaActive = null))
        assertNull(headUnitVolumeStream(navigationActive = false, mediaActive = null))
    }
}
