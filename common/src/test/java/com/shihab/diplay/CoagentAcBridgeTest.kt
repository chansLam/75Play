package com.shihab.diplay

import android.os.Parcel
import com.shihab.diplay.host.CoagentAcBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

/**
 * Pure-JVM replay of the `getAirConditionInfo()` (tx27) reply header captured from the
 * S401AUTO head unit in /tmp/75play-20261102-214234.log.
 *
 * The firmware writes: [present=1][className length=0x2c/44][UTF-16 className
 * "com.incall.proxy.can.AirConditionInfo" (37 chars + 2-byte terminator = 76 bytes)][payload].
 * The 44-byte length prefix does NOT match the 76-byte span that is actually present, so a
 * plain Parcel.readString() would consume only 44 bytes and desync the serializable layout.
 * parseInfoExact must therefore skip the exact known span ((37+1)*2 = 76 bytes) and then walk
 * the decompiled writeToParcel layout: 4 serializables, 1 byte, 3 serializables, 2 floats,
 * 3 serializables, 2 floats.
 */
class CoagentAcBridgeTest {

    private fun mockReply(
        classNameLen: Int,
        outside: Float = 27.5f,
        inside: Float = 24.0f,
        left: Float = 22.5f,
        right: Float = 23.5f,
    ): Parcel {
        val reply = mock(Parcel::class.java)
        `when`(reply.readInt()).thenReturn(classNameLen)
        // Data position semantics: refreshSnapshot consumed the 4-byte present flag before
        // parseInfoExact, which then consumed the 4-byte class-length field itself.
        `when`(reply.dataPosition()).thenReturn(8)
        `when`(reply.readSerializable()).thenReturn("serializable-enum")
        `when`(reply.readByte()).thenReturn(1.toByte())
        `when`(reply.readFloat()).thenReturn(outside, inside, left, right)
        return reply
    }

    @Test
    fun skipsExactClassNameSpanAndParsesExactLayoutWithAnomalousFirmwareLength() {
        // The real device writes 0x2c (44) although the className spans 76 bytes.
        val reply = mockReply(classNameLen = 44)
        val parsed = CoagentAcBridge.parseInfoExact(reply)
        assertNotNull(parsed)
        assertEquals(27.5f, parsed!!.outside, 0.001f)
        assertEquals(24.0f, parsed.inside, 0.001f)
        assertEquals(22.5f, parsed.left, 0.001f)
        assertEquals(23.5f, parsed.right, 0.001f)
        // After the 4-byte length field, the skip must advance by the full 76-byte className
        // span, not by the (wrong) 44 the firmware wrote.
        verify(reply).setDataPosition(8 + ("com.incall.proxy.can.AirConditionInfo".length + 1) * 2)
    }

    @Test
    fun parsesExactLayoutWithStandardClassLength() {
        val reply = mockReply(classNameLen = 76) // standard (37+1)*2
        val parsed = CoagentAcBridge.parseInfoExact(reply)
        assertNotNull(parsed)
        assertEquals(27.5f, parsed!!.outside, 0.001f)
        assertEquals(23.5f, parsed.right, 0.001f)
    }

    @Test
    fun rejectsNonsenseClassLength() {
        assertNull(CoagentAcBridge.parseInfoExact(mockReply(classNameLen = -1)))
    }

    @Test
    fun returnsNullOnLayoutMismatch() {
        val reply = mock(Parcel::class.java)
        `when`(reply.readInt()).thenReturn(44)
        `when`(reply.dataPosition()).thenReturn(4)
        // A serializable read failure anywhere makes the whole snapshot a no-op, never a crash.
        `when`(reply.readSerializable()).thenThrow(RuntimeException("garbage stream"))
        assertNull(CoagentAcBridge.parseInfoExact(reply))
    }
}
