package com.shihab.diplay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitKeyEventsTest {
    @Test fun `coagent down and none are presses and up is a release`() {
        assertEquals(
            HeadUnitKeyPress("NEXT", pressed = true),
            press(HeadUnitKeyEvents.ACTION_KEY_CHANGED, strings = mapOf("Key_code" to "next", "Key_state" to "DOWN")),
        )
        assertEquals(
            HeadUnitKeyPress("VOLUP", pressed = true),
            press(HeadUnitKeyEvents.ACTION_KEY_CHANGED, strings = mapOf("Key_code" to "VOLUP", "Key_state" to "NONE")),
        )
        assertEquals(
            HeadUnitKeyPress("VOLUP", pressed = false),
            press(HeadUnitKeyEvents.ACTION_KEY_TEST, strings = mapOf("Key_code" to "VOLUP", "Key_state" to "UP")),
        )
    }

    @Test fun `other wheel broadcasts become stable tokens`() {
        assertEquals(
            HeadUnitKeyPress("c3:23", pressed = true),
            press(
                "android.intent.action.C3_HARDKEY",
                ints = mapOf("android.intent.extra.c3_hardkey_keycode" to 23),
            ),
        )
        assertEquals(
            HeadUnitKeyPress("mcu:4", pressed = true),
            press(
                "com.okl.android.MCU_KEY_IND_ACTION",
                ints = mapOf("key_value" to 4),
                strings = mapOf("key_state" to "PRESS_DOWN"),
            ),
        )
        assertNull(press("android.intent.action.BOOT_COMPLETED"))
    }

    @Test fun `okl and adayo numeric one and two are releases`() {
        listOf(
            "com.okl.android.MCU_KEY_IND_ACTION",
            "com.adayo.android.MCU_KEY_IND_ACTION",
        ).forEach { action ->
            assertEquals(
                HeadUnitKeyPress("mcu:4", pressed = true),
                press(action, ints = mapOf("key_value" to 4, "key_state" to 0)),
            )
            assertEquals(
                HeadUnitKeyPress("mcu:4", pressed = false),
                press(action, ints = mapOf("key_value" to 4, "key_state" to 1)),
            )
            assertEquals(
                HeadUnitKeyPress("mcu:4", pressed = false),
                press(action, ints = mapOf("key_value" to 4, "key_state" to 2)),
            )
        }
    }

    @Test fun `spd keys are edge only pulses`() {
        assertEquals(
            HeadUnitKeyPress("spd:7", pressed = true, edgeOnly = true),
            press("com.spd.system.key", ints = mapOf("extra_key_value" to 7)),
        )
    }

    @Test fun `broadcast repeats are suppressed for one second`() {
        val gate = SwcBroadcastRepeatGate()

        assertTrue(gate.allow("NEXT", 100L))
        assertFalse(gate.allow("NEXT", 1_099L))
        assertTrue(gate.allow("NEXT", 1_100L))
        assertTrue(gate.allow("PRE", 1_101L))
        gate.reset()
        assertTrue(gate.allow("PRE", 1_102L))
    }

    private fun press(
        action: String,
        strings: Map<String, String> = emptyMap(),
        ints: Map<String, Int> = emptyMap(),
    ): HeadUnitKeyPress? = HeadUnitKeyEvents.parseFields(
        action,
        { strings[it] },
        { name, default -> ints[name] ?: default },
    )
}
