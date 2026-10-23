package com.shihab.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WheelRootInjectorTest {
    @Test
    fun parseLabeledGeteventDown() {
        val line = "/dev/input/event2: EV_KEY       KEY_VOICECOMMAND            DOWN"
        assertEquals(
            "/dev/input/event2" to "KEY_VOICECOMMAND",
            WheelRootInjector.parseKeyDown(line),
        )
    }

    @Test
    fun parseHexGeteventDown() {
        val line = "/dev/input/event3: 0001 0074 00000001"
        assertEquals(
            "/dev/input/event3" to "0074",
            WheelRootInjector.parseKeyDown(line),
        )
    }

    @Test
    fun ignoresKeyUpAndNonKey() {
        assertNull(WheelRootInjector.parseKeyDown("/dev/input/event2: EV_KEY KEY_VOICECOMMAND UP"))
        assertNull(WheelRootInjector.parseKeyDown("/dev/input/event2: 0001 0074 00000000"))
        assertNull(WheelRootInjector.parseKeyDown("/dev/input/event2: 0003 0000 00000123"))
        assertNull(WheelRootInjector.parseKeyDown("add device 1: /dev/input/event2"))
    }
}
