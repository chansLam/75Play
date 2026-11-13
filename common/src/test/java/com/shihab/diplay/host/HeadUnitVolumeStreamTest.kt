// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadUnitVolumeStreamTest {
    @Test fun callWinsOverEverything() {
        assertEquals(0, AudioManager.STREAM_VOICE_CALL)
        assertEquals(AudioManager.STREAM_VOICE_CALL,
            headUnitVolumeStream(navigationActive = true, mediaActive = true, callActive = true))
        assertEquals(AudioManager.STREAM_VOICE_CALL,
            headUnitVolumeStream(navigationActive = false, mediaActive = false, callActive = true))
    }

    @Test fun navigationWinsWhileAPromptIsPlayingOverMedia() {
        assertEquals(10, HEAD_UNIT_NAVIGATION_STREAM)
        assertEquals(HEAD_UNIT_NAVIGATION_STREAM,
            headUnitVolumeStream(navigationActive = true, mediaActive = true, callActive = false))
        assertEquals(HEAD_UNIT_NAVIGATION_STREAM,
            headUnitVolumeStream(navigationActive = true, mediaActive = false, callActive = null))
        assertEquals(HEAD_UNIT_NAVIGATION_STREAM,
            headUnitVolumeStream(navigationActive = true, mediaActive = null, callActive = false))
    }

    @Test fun mediaIsUsedWhenNavigationIsQuiet() {
        assertEquals(3, AudioManager.STREAM_MUSIC)
        assertEquals(AudioManager.STREAM_MUSIC,
            headUnitVolumeStream(navigationActive = false, mediaActive = true, callActive = false))
        assertEquals(AudioManager.STREAM_MUSIC,
            headUnitVolumeStream(navigationActive = null, mediaActive = true, callActive = null))
    }

    @Test fun silenceFallsBackToTheHeadUnitMasterVolume() {
        assertNull(headUnitVolumeStream(navigationActive = false, mediaActive = false, callActive = false))
        assertNull(headUnitVolumeStream(navigationActive = null, mediaActive = null, callActive = null))
        assertNull(headUnitVolumeStream(navigationActive = false, mediaActive = null, callActive = false))
    }
}
