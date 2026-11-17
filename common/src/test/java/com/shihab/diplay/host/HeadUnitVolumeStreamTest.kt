// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadUnitVolumeStreamTest {
    @Test fun callWinsOverEverything() {
        assertEquals(0, AudioManager.STREAM_VOICE_CALL)
        assertEquals(HeadUnitVolumeChannel.CALL, headUnitVolumeChannel(callActive = true, navigationActive = true, mediaActive = true))
        assertEquals(HeadUnitVolumeChannel.CALL, headUnitVolumeChannel(callActive = true, navigationActive = false, mediaActive = true))
    }

    @Test fun navigationWinsWhileAPromptIsPlayingOverMedia() {
        assertEquals(10, HEAD_UNIT_NAVIGATION_STREAM)
        assertEquals(HeadUnitVolumeChannel.NAVIGATION, headUnitVolumeChannel(callActive = false, navigationActive = true, mediaActive = true))
        assertEquals(HeadUnitVolumeChannel.NAVIGATION, headUnitVolumeChannel(callActive = false, navigationActive = true, mediaActive = false))
        assertEquals(HeadUnitVolumeChannel.NAVIGATION, headUnitVolumeChannel(callActive = null, navigationActive = true, mediaActive = null))
    }

    @Test fun mediaIsUsedWhenNavigationAndCallAreQuiet() {
        assertEquals(3, AudioManager.STREAM_MUSIC)
        assertEquals(HeadUnitVolumeChannel.MEDIA, headUnitVolumeChannel(callActive = false, navigationActive = false, mediaActive = true))
        assertEquals(HeadUnitVolumeChannel.MEDIA, headUnitVolumeChannel(callActive = null, navigationActive = null, mediaActive = true))
    }

    @Test fun silenceFallsBackToMediaSoTheKeyAlwaysResponds() {
        assertEquals(HeadUnitVolumeChannel.MEDIA, headUnitVolumeChannel(callActive = false, navigationActive = false, mediaActive = false))
        assertEquals(HeadUnitVolumeChannel.MEDIA, headUnitVolumeChannel(callActive = null, navigationActive = null, mediaActive = null))
        assertEquals(HeadUnitVolumeChannel.MEDIA, headUnitVolumeChannel(callActive = false, navigationActive = false, mediaActive = null))
    }
}
