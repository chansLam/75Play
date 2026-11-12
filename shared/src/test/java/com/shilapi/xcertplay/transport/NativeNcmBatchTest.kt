package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeNcmBatchTest {
    @Test fun acceptsOnlyThePrefixThatFitsTheNativeNtb() {
        val frames = listOf(ByteArray(40), ByteArray(40), ByteArray(40))
        val result = NativeNcmBatch.select(frames, NcmNtbLimits(128, 4, 8))

        assertEquals(2, result.acceptedCount)
        assertTrue(result.blockBytes <= 128)
    }

    @Test fun rejectsAnOversizedFirstFrameWithoutSubmittingIt() {
        val result = NativeNcmBatch.select(listOf(ByteArray(200)), NcmNtbLimits(128, 4, 8))

        assertEquals(0, result.acceptedCount)
        assertFalse(result.hasSubmission)
    }
}
