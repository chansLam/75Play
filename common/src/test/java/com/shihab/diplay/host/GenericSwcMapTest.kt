package com.shihab.diplay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenericSwcMapTest {
    @Test
    fun `round trip keeps android key codes`() {
        val bindings = mapOf(
            GenericSwcAction.TRACK_NEXT to 87,
            GenericSwcAction.VOLUME_UP to 24,
            GenericSwcAction.MAP_VIEW to 84,
        )
        assertEquals(bindings, GenericSwcMap.decode(GenericSwcMap.encode(bindings)))
    }

    @Test
    fun `one key code belongs to one action`() {
        val first = GenericSwcMap.assign(emptyMap(), GenericSwcAction.TRACK_NEXT, 87)
        val second = GenericSwcMap.assign(first, GenericSwcAction.TRACK_PREV, 87)
        assertNull(second[GenericSwcAction.TRACK_NEXT])
        assertEquals(GenericSwcAction.TRACK_PREV, GenericSwcMap.actionForKeyCode(second, 87))
    }

    @Test
    fun `an unlearned key code is ignored`() {
        assertNull(GenericSwcMap.actionForKeyCode(emptyMap(), 87))
        assertEquals(emptyMap<GenericSwcAction, Int>(), GenericSwcMap.assign(emptyMap(), GenericSwcAction.MUTE, 0))
    }

    @Test
    fun `head unit names and broken text are dropped`() {
        val decoded = GenericSwcMap.decode("NOPE=k:1;TRACK_NEXT=k:0;VOLUME_DOWN=n:VOLDOWN;MUTE=k:91;bogus")
        assertEquals(mapOf(GenericSwcAction.MUTE to 91), decoded)
    }
}
