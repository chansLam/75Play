package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ntb16CodecTest {
    @Test
    fun buildFramesCarriesMultipleFramesInOneNtb() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6, 7)

        val block = Ntb16Codec.buildFrames(
            frames = listOf(first, second),
            sequence = 9,
            maxBlockBytes = 128,
            alignment = 4,
        )

        assertEquals(listOf(first.toList(), second.toList()), Ntb16Codec.parse(block).map { it.toList() })
        assertTrue(block.size <= 128)
    }

    @Test
    fun buildFramesRejectsFramesThatExceedConfiguredNtbSize() {
        try {
            Ntb16Codec.buildFrames(
                frames = listOf(ByteArray(100)),
                sequence = 0,
                maxBlockBytes = 32,
                alignment = 4,
            )
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected oversized frame to be rejected")
    }
}
