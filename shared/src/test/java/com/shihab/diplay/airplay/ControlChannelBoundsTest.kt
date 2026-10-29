package com.shihab.diplay.airplay

import java.net.ProtocolException
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlChannelBoundsTest {
    @Test
    fun aDeclaredObjectCountAboveTheDataIsRejected() {
        val encoded = BplistCodec.encode(mapOf("a" to "b"))
        val trailer = encoded.size - 32
        encoded[trailer + 15] = 0x7f
        try {
            BplistCodec.decode(encoded)
            throw AssertionError("hostile object count was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(true, error.message?.contains("bplist") == true)
        }
    }

    @Test
    fun anOversizedRtspHeaderIsRejected() {
        val header = "GET /info RTSP/1.0\r\n" + "X-Pad: " + "a".repeat(20 * 1024) + "\r\n\r\n"
        try {
            RtspMessage.parseMessages(header.toByteArray(Charsets.US_ASCII))
            throw AssertionError("oversized header was accepted")
        } catch (error: ProtocolException) {
            assertEquals(true, error.message?.contains("header") == true)
        }
    }

    @Test
    fun anOversizedContentLengthIsRejected() {
        val header = "POST /info RTSP/1.0\r\nContent-Length: 2000000\r\n\r\n"
        try {
            RtspMessage.parseMessages(header.toByteArray(Charsets.US_ASCII))
            throw AssertionError("oversized body was accepted")
        } catch (error: ProtocolException) {
            assertEquals(true, error.message?.contains("body") == true)
        }
    }
}
