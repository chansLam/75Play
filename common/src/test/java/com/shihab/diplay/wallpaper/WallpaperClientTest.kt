// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WallpaperClientTest {
    @Test fun skipsTransportWhenTheUrlIsRefused() {
        var calls = 0
        val client = WallpaperClient(WallpaperTransport {
            calls++
            WallpaperHttpResult(200, byteArrayOf(1), it, true)
        })
        assertNull(client.bytes("http://evil.example/a"))
        assertEquals(0, calls)
    }

    @Test fun skipsBodyWhenTheFinalHostIsRefused() {
        val client = WallpaperClient(WallpaperTransport {
            WallpaperHttpResult(200, "ok".toByteArray(), "https://evil.example/landed", true)
        })
        assertNull(client.text("https://www.bing.com/start"))
    }

    @Test fun returnsBodyForAnAllowedFinalHost() {
        val client = WallpaperClient(WallpaperTransport {
            WallpaperHttpResult(200, "png".toByteArray(), "https://w.wallhaven.cc/full/ab/a.jpg", true)
        })
        assertEquals("png", client.text("https://wallhaven.cc/api/v1/search"))
    }

    @Test fun rejectsOversizedBodies() {
        val client = WallpaperClient(WallpaperTransport {
            WallpaperHttpResult(200, ByteArray(WallpaperClient.MAX_BODY + 1), "https://www.bing.com/a.jpg", true)
        })
        assertNull(client.bytes("https://www.bing.com/a.jpg"))
    }

    @Test fun bingOriginalMayLandOutsideTheHostList() {
        var calls = 0
        val client = WallpaperClient(WallpaperTransport {
            calls++
            WallpaperHttpResult(200, "jpg".toByteArray(), "https://photos.example/a.jpg", true)
        })
        assertEquals("jpg", client.bytes("https://photos.example/a.jpg", bingOriginal = true)?.toString(Charsets.UTF_8))
        assertEquals(1, calls)
        assertNull(client.bytes("https://photos.example/a.jpg"))
        assertEquals(1, calls)
        assertNull(client.bytes("http://photos.example/a.jpg", bingOriginal = true))
    }

    @Test fun rejectsFailedStatus() {
        val client = WallpaperClient(WallpaperTransport {
            WallpaperHttpResult(404, byteArrayOf(1), "https://www.bing.com/missing", false)
        })
        assertNull(client.bytes("https://www.bing.com/missing"))
    }
}
