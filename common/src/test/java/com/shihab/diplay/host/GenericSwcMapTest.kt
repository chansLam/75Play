package com.shihab.diplay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenericSwcMapTest {
    @Test
    fun `round trip keeps android key codes and head unit names`() {
        val bindings = mapOf(
            GenericSwcAction.TRACK_NEXT to GenericSwcCode.Android(87),
            GenericSwcAction.VOLUME_UP to GenericSwcCode.Name("VOLUP"),
            GenericSwcAction.MAP_VIEW to GenericSwcCode.Android(84),
        )
        assertEquals(bindings, GenericSwcMap.decode(GenericSwcMap.encode(bindings)))
    }

    @Test
    fun `one key code belongs to one action`() {
        val first = GenericSwcMap.assign(emptyMap(), GenericSwcAction.TRACK_NEXT, GenericSwcCode.Android(87))
        val second = GenericSwcMap.assign(first, GenericSwcAction.TRACK_PREV, GenericSwcCode.Android(87))
        assertNull(second[GenericSwcAction.TRACK_NEXT])
        assertEquals(GenericSwcAction.TRACK_PREV, GenericSwcMap.actionForKeyCode(second, 87))
    }

    @Test
    fun `one broadcast name belongs to one action`() {
        val first = GenericSwcMap.assign(emptyMap(), GenericSwcAction.VOLUME_UP, GenericSwcCode.Name("volup"))
        val second = GenericSwcMap.assign(first, GenericSwcAction.VOLUME_DOWN, GenericSwcCode.Name("VOLUP"))
        assertNull(second[GenericSwcAction.VOLUME_UP])
        assertEquals(GenericSwcAction.VOLUME_DOWN, GenericSwcMap.actionForName(second, "VolUp"))
    }

    @Test
    fun `an unlearned key code is ignored`() {
        assertNull(GenericSwcMap.actionForKeyCode(emptyMap(), 87))
        assertNull(GenericSwcMap.actionForName(emptyMap(), "NEXT"))
        assertEquals(
            emptyMap<GenericSwcAction, GenericSwcCode>(),
            GenericSwcMap.assign(emptyMap(), GenericSwcAction.MUTE, GenericSwcCode.Android(0)),
        )
    }

    @Test
    fun `broken text is dropped and a head unit name is kept`() {
        val decoded = GenericSwcMap.decode("NOPE=k:1;TRACK_NEXT=k:0;VOLUME_DOWN=n:VOLDOWN;MUTE=k:91;bogus")
        assertEquals(
            mapOf(
                GenericSwcAction.VOLUME_DOWN to GenericSwcCode.Name("VOLDOWN"),
                GenericSwcAction.MUTE to GenericSwcCode.Android(91),
            ),
            decoded,
        )
    }
}
