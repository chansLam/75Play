package com.shihab.diplay.airplay

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPacketBuffersTest {
    @Test
    fun returnedPacketIsReusedForTheSameSize() {
        val first = AudioPacketBuffers.take(320)
        AudioPacketBuffers.give(first)
        val second = AudioPacketBuffers.take(320)
        assertSame(first, second)
        AudioPacketBuffers.give(second)
    }

    @Test
    fun oversizedPacketStaysOutsideThePool() {
        val large = AudioPacketBuffers.take(20_000)
        AudioPacketBuffers.give(large)
        val again = AudioPacketBuffers.take(20_000)
        assertTrue(large !== again)
    }
}
