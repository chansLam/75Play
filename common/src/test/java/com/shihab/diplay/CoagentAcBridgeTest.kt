package com.shihab.diplay

import android.os.Parcel
import com.shihab.diplay.host.CoagentAcBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/**
 * tx27 after readException and the present flag is AirConditionInfo.writeToParcel.
 * Each enum is API 19 writeSerializable: a class-name string plus a byte array.
 */
class CoagentAcBridgeTest {

    private fun reply(
        outside: Float = 27.5f,
        inside: Float = 24.0f,
        left: Float = 22.5f,
        right: Float = 23.5f,
    ): Parcel {
        val reply = mock(Parcel::class.java)
        `when`(reply.readString()).thenReturn("com.incall.proxy.can.AirConditionInfo\$Switch")
        `when`(reply.dataPosition()).thenReturn(0)
        `when`(reply.readInt()).thenReturn(1)
        `when`(reply.dataAvail()).thenReturn(1)
        `when`(reply.createByteArray()).thenReturn(byteArrayOf(0x01))
        `when`(reply.readByte()).thenReturn(1.toByte())
        `when`(reply.readFloat()).thenReturn(outside, inside, left, right)
        return reply
    }

    @Test
    fun parsesWriteSerializablePrefixWithoutLoadingOemEnums() {
        val parcel = reply()
        val parsed = CoagentAcBridge.parseInfoExact(parcel)
        assertNotNull(parsed)
        assertEquals(27.5f, parsed!!.outside, 0.001f)
        assertEquals(24.0f, parsed.inside, 0.001f)
        assertEquals(22.5f, parsed.left, 0.001f)
        assertEquals(23.5f, parsed.right, 0.001f)
    }

    @Test
    fun keepsLoAndHiSetpoints() {
        val parsed = CoagentAcBridge.parseInfoExact(reply(left = -1f, right = -2f))
        assertNotNull(parsed)
        assertEquals(-1f, parsed!!.left, 0.001f)
        assertEquals(-2f, parsed.right, 0.001f)
    }

    @Test
    fun returnsNullWhenAnEnumNameIsMissing() {
        val broken = mock(Parcel::class.java)
        `when`(broken.readString()).thenReturn(null)
        assertNull(CoagentAcBridge.parseInfoExact(broken))
    }

    @Test
    fun rejectsAnUnboundedSerializablePayload() {
        val reply = mock(Parcel::class.java)
        `when`(reply.readString()).thenReturn("com.incall.proxy.can.AirConditionInfo\$Switch")
        `when`(reply.dataPosition()).thenReturn(0)
        `when`(reply.readInt()).thenReturn(1_000_000)
        `when`(reply.dataAvail()).thenReturn(1_000_000)
        assertNull(CoagentAcBridge.parseInfoExact(reply))
        verify(reply, never()).createByteArray()
    }
}
