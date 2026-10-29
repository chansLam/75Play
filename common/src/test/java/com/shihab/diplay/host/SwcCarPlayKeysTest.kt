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
    fun `volume is adjusted locally after the press is swallowed`() {
        assertNull(SwcCarPlayKeys.actionFor("KEY_SWC_VOL_INC"))
        assertNull(SwcCarPlayKeys.actionFor("VOL_DEC"))
        assertEquals(SwcWheelOutcome.VOLUME_UP, SwcCarPlayKeys.outcomeFor("KEY_SWC_VOL_INC"))
        assertEquals(SwcWheelOutcome.VOLUME_UP, SwcCarPlayKeys.outcomeFor("VOLUME_UP"))
        assertEquals(SwcWheelOutcome.VOLUME_DOWN, SwcCarPlayKeys.outcomeFor("KEY_SWC_VOL_DEC"))
        assertEquals(SwcWheelOutcome.VOLUME_DOWN, SwcCarPlayKeys.outcomeFor("VOL_DEC"))
    }

    @Test
    fun `mode and unknown names are released`() {
        for (name in listOf("Model", "MODE", "MODEL", "KEY_SWC_MODE", "KEY_MODE", "SRC", "MYSTERY_KEY")) {
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
