// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WallpaperSearchTest {
    @Test fun blankQueryDoesNotCallTheClient() {
        var calls = 0
        val controller = WallpaperSearchController(WallpaperClient(WallpaperTransport {
            calls++
            WallpaperHttpResult(200, ByteArray(0), it, true)
        }))
        assertEquals(WallpaperSearchResult.NeedQuery, controller.search("  "))
        assertEquals(0, calls)
    }

    @Test fun transportFailureBecomesFailed() {
        val controller = WallpaperSearchController(WallpaperClient(WallpaperTransport {
            WallpaperHttpResult(500, ByteArray(0), it, false)
        }))
        assertEquals(WallpaperSearchResult.Failed, controller.search("road"))
    }

    @Test fun emptyDataBecomesNoneAndHitsStayHits() {
        val empty = WallpaperSearchController(WallpaperClient(WallpaperTransport {
            WallpaperHttpResult(200, """{"data":[]}""".toByteArray(), "https://wallhaven.cc/api/v1/search", true)
        }))
        assertEquals(WallpaperSearchResult.None, empty.search("road"))
        val hit = WallpaperSearchController(WallpaperClient(WallpaperTransport {
            val json = """{"data":[{"path":"https://w.wallhaven.cc/full/ab/a.jpg","thumbs":{"small":"https://th.wallhaven.cc/small/ab/a.jpg"}}]}"""
            WallpaperHttpResult(200, json.toByteArray(), "https://wallhaven.cc/api/v1/search", true)
        }))
        val hits = hit.search("road") as WallpaperSearchResult.Hits
        assertEquals(1, hits.images.size)
        assertTrue(hits.images[0].thumbUrl.contains("th.wallhaven.cc"))
    }

    @Test fun boardPutsBingFirstAndCapsAt24() {
        val board = WallpaperSearchBoard()
        val others = (1..24).map { WallpaperImage("https://w.wallhaven.cc/$it.jpg", "https://th.wallhaven.cc/$it.jpg") }
        board.add(WallpaperSearchSource.WALLHAVEN, others)
        assertFalse(board.shouldCancelOthers())
        assertEquals(24, board.visible().size)
        val bing = (1..3).map { WallpaperImage("https://photos.example/$it.jpg", "https://photos.example/$it.jpg", bingOriginal = true) }
        board.add(WallpaperSearchSource.BING, bing)
        assertEquals("https://photos.example/1.jpg", board.visible().first().fullUrl)
        assertEquals(24, board.visible().size)
        assertEquals("https://w.wallhaven.cc/21.jpg", board.visible().last().fullUrl)
        board.add(WallpaperSearchSource.BING, (4..24).map { WallpaperImage("https://photos.example/$it.jpg", "https://photos.example/$it.jpg", true) })
        assertTrue(board.shouldCancelOthers())
        assertEquals(24, board.visible().size)
        assertTrue(board.visible().all { it.fullUrl.contains("photos.example") })
    }

    @Test fun boardFailureDoesNotHideHitsAndAllFailuresStayFailed() {
        val mixed = WallpaperSearchBoard()
        mixed.add(WallpaperSearchSource.ZZZMH, listOf(WallpaperImage("https://imageai.zzzmh.cn/a.webp", "https://imageai.zzzmh.cn/t.webp")))
        mixed.finish(WallpaperSearchSource.ZZZMH, WallpaperSearchFinish.DONE)
        mixed.finish(WallpaperSearchSource.BING, WallpaperSearchFinish.FAILED)
        mixed.finish(WallpaperSearchSource.WALLHAVEN, WallpaperSearchFinish.FAILED)
        assertEquals(WallpaperSearchStatus.HITS, mixed.status())
        assertEquals(1, mixed.visible().size)

        val failed = WallpaperSearchBoard()
        WallpaperSearchSource.values().forEach { failed.finish(it, WallpaperSearchFinish.FAILED) }
        assertEquals(WallpaperSearchStatus.FAILED, failed.status())

        val empty = WallpaperSearchBoard()
        empty.finish(WallpaperSearchSource.BING, WallpaperSearchFinish.EMPTY)
        empty.finish(WallpaperSearchSource.WALLHAVEN, WallpaperSearchFinish.FAILED)
        empty.finish(WallpaperSearchSource.ZZZMH, WallpaperSearchFinish.FAILED)
        assertEquals(WallpaperSearchStatus.NONE, empty.status())
    }
}
