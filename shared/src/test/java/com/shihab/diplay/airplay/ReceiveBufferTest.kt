package com.shihab.diplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ReceiveBufferTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test fun appendGrowsWithoutLosingBytesAndDropsALargeEmptyBuffer() {
        val buffer = StreamBuffer(4)
        val chunk = "abcdef".toByteArray()
        buffer.append(chunk, 3)
        buffer.append(chunk, chunk.size)
        assertArrayEquals("abcabcdef".toByteArray(), buffer.toByteArray())
        buffer.consume(3)
        assertArrayEquals("abcdef".toByteArray(), buffer.toByteArray())
        val block = ByteArray(1024)
        while (buffer.bytes.size <= 64 * 1024) buffer.append(block, block.size)
        buffer.consume(buffer.size)
        assertEquals(0, buffer.size)
        assertEquals(64 * 1024, buffer.bytes.size)
    }

    @Test fun framedReadsSurviveASplitAndABadTagDoesNotConsume() {
        val writer = ControlCipher(key, key)
        val wire = writer.encrypt("one".toByteArray()) + writer.encrypt("two".toByteArray())
        val reader = ControlCipher(key, key)
        val sealed = StreamBuffer()
        val plain = StreamBuffer()
        sealed.append(wire, 3)
        assertEquals(0, reader.decryptInto(sealed.bytes, sealed.size, plain))
        sealed.append(wire.copyOfRange(3, wire.size), wire.size - 3)
        val consumed = reader.decryptInto(sealed.bytes, sealed.size, plain)
        assertEquals(sealed.size, consumed)
        assertArrayEquals("onetwo".toByteArray(), plain.toByteArray())

        val bad = ControlCipher(key, key).encrypt("hello".toByteArray())
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0x01).toByte()
        val kept = StreamBuffer()
        kept.append(byteArrayOf(9), 1)
        val fresh = ControlCipher(key, key)
        try {
            fresh.decryptInto(bad, bad.size, kept)
            fail("bad tag was accepted")
        } catch (_: Exception) {
        }
        assertEquals(1, kept.size)
        val again = ControlCipher(key, key).encrypt("hello".toByteArray())
        assertEquals(again.size, fresh.decryptInto(again, again.size, kept))
        assertArrayEquals(byteArrayOf(9) + "hello".toByteArray(), kept.toByteArray())
    }

    @Test fun scanStopsAtTheValidLengthAndHoldsAPartialBody() {
        val message = "GET /info RTSP/1.0\r\nContent-Length: 0\r\n\r\n"
        val raw = (message + "POST").toByteArray()
        val scanned = RtspMessage.scanMessages(raw, message.length)
        assertEquals(1, scanned.messages.size)
        assertEquals(message.length, scanned.consumed)
        assertEquals("GET", scanned.messages[0].method)
        assertEquals("/info", scanned.messages[0].path)

        val partial = "POST /x RTSP/1.0\r\nContent-Length: 4\r\n\r\nab".toByteArray()
        val held = RtspMessage.scanMessages(partial, partial.size)
        assertEquals(0, held.messages.size)
        assertEquals(0, held.consumed)
    }
}
