// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest

class DiagnosticDownloadServerTest {
    @Test fun servesTheReportOnlyAtTheTokenPath() {
        val body = "hello report\n".toByteArray(Charsets.UTF_8)
        val server = requireNotNull(DiagnosticDownloadServer.start(body, "75Play-test.txt", "127.0.0.1"))
        try {
            val saved = http(server.url)
            assertEquals(200, saved.first)
            assertEquals("hello report\n", saved.second.toString(Charsets.UTF_8))
            assertTrue(saved.third.contains("filename=\"75Play-test.txt\""))
            val missing = http(server.url + "nope")
            assertEquals(404, missing.first)
        } finally {
            server.close()
        }
    }

    @Test fun dropsCharactersThatWouldBreakTheDownloadHeader() {
        val reply = diagnosticHttpReply("GET /d/tok HTTP/1.1\r\nHost: x\r\n\r\n", "x".toByteArray(), "a\"b\r.txt", "tok")
        val head = reply.header.toString(Charsets.US_ASCII)
        assertTrue(head.contains("filename=\"ab.txt\""))
        assertFalse(head.contains("a\"b"))
        assertEquals("x", reply.body!!.toString(Charsets.US_ASCII))
    }

    @Test fun prefersTheHotspotGateway() {
        val picked = selectLanIpv4(listOf(
            InetAddress.getByName("10.0.2.15"),
            InetAddress.getByName("127.0.0.1"),
            InetAddress.getByName("192.168.1.20"),
            InetAddress.getByName("192.168.43.1"),
            InetAddress.getByName("169.254.1.1"),
        ))
        assertEquals("192.168.43.1", picked!!.hostAddress)
    }

    private fun http(url: String): Triple<Int, ByteArray, String> {
        var last: IOException? = null
        repeat(10) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val bytes = stream?.readBytes() ?: ByteArray(0)
                val disposition = conn.getHeaderField("Content-Disposition").orEmpty()
                conn.disconnect()
                return Triple(code, bytes, disposition)
            } catch (error: java.io.IOException) {
                last = error
                Thread.sleep(50)
            }
        }
        throw last ?: IllegalStateException("no response")
    }
}

class QrCodeTest {
    @Test fun finderPatternsStayDark() {
        val qr = QrCode.encode("http://192.168.43.1:18765/d/abcdef0123456789".toByteArray())
        assertTrue(qr.dark(0, 0))
        assertTrue(qr.dark(qr.size - 1, 0))
        assertTrue(qr.dark(0, qr.size - 1))
        assertFalse(qr.dark(1, 1))
    }

    @Test fun matchesTheStandardMatrix() {
        val qr = QrCode.encode("http://192.168.43.1:18765/d/abcdef0123456789".toByteArray())
        val rows = (0 until qr.size).joinToString("\n") { y ->
            (0 until qr.size).joinToString("") { x -> if (qr.dark(x, y)) "1" else "0" }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(rows.toByteArray()).joinToString("") { "%02x".format(it) }
        // 字节模式、纠错 M，与常见扫描器使用的标准填充一致。
        assertEquals(GOLDEN, digest)
    }

    private companion object {
        const val GOLDEN = "13b2925f148af67c6ad3aceffeb362e1cf6219407eccfc07ed3bde1b087990c2"
    }
}
