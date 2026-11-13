package com.shihab.diplay

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CoagentBrightnessCallbackTest {
    @Test
    fun brightnessIsDeliveredAndTheReplyIsReadable() {
        val seen = mutableListOf<Int>()
        val callback = CoagentBrightnessCallback { seen += it }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(CoagentBrightnessCallback.DESCRIPTOR)
            data.writeInt(40)
            data.setDataPosition(0)
            assertTrue(callback.dispatch(data, reply))
            reply.setDataPosition(0)
            reply.readException()
            assertEquals(listOf(40), seen)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Test
    fun aMismatchedCallbackDoesNotEscape() {
        val seen = mutableListOf<Int>()
        val callback = CoagentBrightnessCallback { seen += it }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.example.Wrong")
            data.writeInt(7)
            data.setDataPosition(0)
            assertTrue(callback.dispatch(data, reply))
            reply.setDataPosition(0)
            reply.readException()
            assertTrue(seen.isEmpty())
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
