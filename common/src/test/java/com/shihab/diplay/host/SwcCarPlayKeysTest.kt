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
    }

    @Test
    fun `volume and src keys keep their native function`() {
        assertNull(SwcCarPlayKeys.actionFor("KEY_SWC_VOL_INC"))
        assertNull(SwcCarPlayKeys.actionFor("KEY_SWC_VOL_DEC"))
        assertNull(SwcCarPlayKeys.actionFor("SRC"))
    }

    @Test
    fun `unknown and blank names are ignored`() {
        assertNull(SwcCarPlayKeys.actionFor(null))
        assertNull(SwcCarPlayKeys.actionFor(""))
        assertNull(SwcCarPlayKeys.actionFor("  "))
        assertNull(SwcCarPlayKeys.actionFor("MYSTERY_KEY"))
    }
}
