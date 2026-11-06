package com.shihab.diplay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwcCarPlayKeysTest {
    @Test
    fun `maps the five wheel keys to CarPlay actions`() {
        assertEquals(SwcCarPlayAction.TRACK_NEXT, SwcCarPlayKeys.actionFor("NEXT"))
        assertEquals(SwcCarPlayAction.TRACK_PREV, SwcCarPlayKeys.actionFor("PRE"))
        assertEquals(SwcCarPlayAction.ANSWER_CALL, SwcCarPlayKeys.actionFor("TEL"))
        assertEquals(SwcCarPlayAction.END_CALL, SwcCarPlayKeys.actionFor("HANDUP"))
        assertEquals(SwcCarPlayAction.SIRI, SwcCarPlayKeys.actionFor("VR"))
        assertEquals(SwcWheelOutcome.TRACK_NEXT, SwcCarPlayKeys.outcomeFor("NEXT"))
        assertEquals(SwcWheelOutcome.SIRI, SwcCarPlayKeys.outcomeFor("VR"))
    }

    @Test
    fun `volume keys drive the head unit because key test mode swallows them`() {
        assertNull(SwcCarPlayKeys.actionFor("KEY_SWC_VOL_INC"))
        assertNull(SwcCarPlayKeys.actionFor("VOL_DEC"))
        assertEquals(SwcWheelOutcome.VOLUME_UP, SwcCarPlayKeys.outcomeFor("KEY_SWC_VOL_INC"))
        assertEquals(SwcWheelOutcome.VOLUME_UP, SwcCarPlayKeys.outcomeFor("VOLUP"))
        assertEquals(SwcWheelOutcome.VOLUME_DOWN, SwcCarPlayKeys.outcomeFor("KEY_SWC_VOL_DEC"))
        assertEquals(SwcWheelOutcome.VOLUME_DOWN, SwcCarPlayKeys.outcomeFor("VOLDOWN"))
        for (name in listOf("VOLUME_UP", "VOL_DEC")) {
            assertEquals(SwcWheelOutcome.RELEASE, SwcCarPlayKeys.outcomeFor(name))
        }
        assertEquals(SwcWheelOutcome.MAP_VIEW, SwcCarPlayKeys.outcomeFor("SRC"))
        assertEquals(SwcWheelOutcome.MAP_VIEW, SwcCarPlayKeys.outcomeFor("KEY_SWC_SRC"))
        // SRC 是缩放的修饰键：按住时音量键才缩地图，单独按音量仍归车机。
        assertEquals(false, SwcCarPlayKeys.volumePressIsZoom(srcHeld = false))
        assertEquals(true, SwcCarPlayKeys.volumePressIsZoom(srcHeld = true))
    }

    @Test
    fun `mode and unknown names are released`() {
        for (name in listOf("Model", "MODE", "MODEL", "KEY_SWC_MODE", "KEY_MODE", "MYSTERY_KEY")) {
            assertNull(SwcCarPlayKeys.actionFor(name))
            assertEquals(SwcWheelOutcome.RELEASE, SwcCarPlayKeys.outcomeFor(name))
        }
    }

    @Test
    fun `blank names are ignored`() {
        assertNull(SwcCarPlayKeys.actionFor(null))
        assertNull(SwcCarPlayKeys.actionFor(""))
        assertNull(SwcCarPlayKeys.actionFor("  "))
        assertNull(SwcCarPlayKeys.outcomeFor(null))
        assertNull(SwcCarPlayKeys.outcomeFor("  "))
    }
}
