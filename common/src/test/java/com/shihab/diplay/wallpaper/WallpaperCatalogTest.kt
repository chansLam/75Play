// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WallpaperCatalogTest {
    @Test fun unknownModeAndSourceFallBack() {
        assertEquals(WallpaperMode.DEFAULT, WallpaperCatalog.mode(null))
        assertEquals(WallpaperMode.DEFAULT, WallpaperCatalog.mode("future"))
        assertEquals(WallpaperMode.CUSTOM, WallpaperCatalog.mode("custom"))
        assertEquals(WallpaperMode.DAILY, WallpaperCatalog.mode("daily"))
        assertEquals(WallpaperDailySource.BING, WallpaperCatalog.source(null))
        assertEquals(WallpaperDailySource.BING, WallpaperCatalog.source("nope"))
        assertEquals(WallpaperDailySource.WALLHAVEN, WallpaperCatalog.source("wallhaven"))
        assertEquals("default", WallpaperCatalog.modeKey(WallpaperMode.DEFAULT))
        assertEquals("custom", WallpaperCatalog.modeKey(WallpaperMode.CUSTOM))
        assertEquals("daily", WallpaperCatalog.modeKey(WallpaperMode.DAILY))
        assertEquals("bing", WallpaperCatalog.sourceKey(WallpaperDailySource.BING))
        assertEquals("wallhaven", WallpaperCatalog.sourceKey(WallpaperDailySource.WALLHAVEN))
    }

    @Test fun dailyDownloadFollowsModeAndLocalDay() {
        assertFalse(WallpaperCatalog.needsDailyDownload(WallpaperMode.DEFAULT, null, "2026-11-12"))
        assertFalse(WallpaperCatalog.needsDailyDownload(WallpaperMode.CUSTOM, null, "2026-11-12"))
        assertTrue(WallpaperCatalog.needsDailyDownload(WallpaperMode.DAILY, null, "2026-11-12"))
        assertTrue(WallpaperCatalog.needsDailyDownload(WallpaperMode.DAILY, "2026-11-11", "2026-11-12"))
        assertFalse(WallpaperCatalog.needsDailyDownload(WallpaperMode.DAILY, "2026-11-12", "2026-11-12"))
    }

    @Test fun urlsCarryTheRequiredFilters() {
        assertTrue(WallpaperCatalog.bingArchiveUrl().startsWith("https://www.bing.com/HPImageArchive.aspx?"))
        assertTrue(WallpaperCatalog.bingArchiveUrl().contains("idx=0"))
        assertTrue(WallpaperCatalog.bingArchiveUrl().contains("n=1"))
        assertTrue(WallpaperCatalog.bingArchiveUrl().contains("mkt=zh-CN"))
        val daily = WallpaperCatalog.wallhavenDailyUrl()
        assertTrue(daily.contains("purity=100"))
        assertTrue(daily.contains("categories=100"))
        assertTrue(daily.contains("sorting=toplist"))
        assertTrue(daily.contains("topRange=1d"))
        assertNull(WallpaperCatalog.wallhavenSearchUrl("  "))
        val search = WallpaperCatalog.wallhavenSearchUrl(" mountain ")!!
        assertTrue(search.contains("q=mountain"))
        assertTrue(search.contains("purity=100"))
        assertTrue(search.contains("categories=100"))
        assertTrue(search.contains("sorting=relevance"))
        assertTrue(search.contains("atleast=1280x720"))
    }

    @Test fun bingParserBuildsThe1920Image() {
        val json = """{"images":[{"urlbase":"/th?id=OHR.Example_ZH-CN1"}]}"""
        val ready = WallpaperCatalog.parseBing(json) as WallpaperFetch.Ready
        assertEquals("https://www.bing.com/th?id=OHR.Example_ZH-CN1_1920x1080.jpg", ready.image.fullUrl)
        assertEquals(WallpaperFetch.Empty, WallpaperCatalog.parseBing("""{"images":[]}"""))
        assertEquals(WallpaperFetch.Invalid, WallpaperCatalog.parseBing("""{"images":[{}]}"""))
        assertEquals(WallpaperFetch.Invalid, WallpaperCatalog.parseBing("not-json"))
    }

    @Test fun wallhavenParserKeepsOnlyAllowedImages() {
        val json = """
            {"data":[
              {"path":"https://w.wallhaven.cc/full/ab/wallhaven-abc.jpg","thumbs":{"small":"https://th.wallhaven.cc/small/ab/abc.jpg"}},
              {"path":"http://insecure.example/a.jpg","thumbs":{"small":"https://th.wallhaven.cc/small/ab/nope.jpg"}},
              {"path":"https://w.wallhaven.cc/full/ab/second.jpg"}
            ]}
        """.trimIndent()
        val hits = WallpaperCatalog.parseWallhavenSearch(json) as WallpaperSearchParse.Hits
        assertEquals(2, hits.images.size)
        assertEquals("https://th.wallhaven.cc/small/ab/abc.jpg", hits.images[0].thumbUrl)
        assertEquals("https://w.wallhaven.cc/full/ab/second.jpg", hits.images[1].thumbUrl)
        val daily = WallpaperCatalog.parseWallhavenDaily(json) as WallpaperFetch.Ready
        assertEquals(hits.images[0].fullUrl, daily.image.fullUrl)
        assertEquals(WallpaperSearchParse.Empty, WallpaperCatalog.parseWallhavenSearch("""{"data":[]}"""))
        assertEquals(WallpaperFetch.Empty, WallpaperCatalog.parseWallhavenDaily("""{"data":[]}"""))
        assertEquals(WallpaperSearchParse.Invalid, WallpaperCatalog.parseWallhavenSearch("[]"))
        assertEquals(WallpaperFetch.Invalid, WallpaperCatalog.parseWallhavenDaily("""{"nope":1}"""))
    }

    @Test fun searchStopsAt24() {
        val items = (1..30).joinToString(",") {
            """{"path":"https://w.wallhaven.cc/full/ab/$it.jpg","thumbs":{"small":"https://th.wallhaven.cc/small/ab/$it.jpg"}}"""
        }
        val hits = WallpaperCatalog.parseWallhavenSearch("""{"data":[$items]}""") as WallpaperSearchParse.Hits
        assertEquals(24, hits.images.size)
    }

    @Test fun sampleSizeUsesPowersOfTwo() {
        assertEquals(1, WallpaperCatalog.sampleSize(1920, 720))
        assertEquals(1, WallpaperCatalog.sampleSize(100, 100))
        assertEquals(1, WallpaperCatalog.sampleSize(0, 0))
        assertEquals(2, WallpaperCatalog.sampleSize(3840, 2160))
        assertEquals(4, WallpaperCatalog.sampleSize(4000, 1000))
    }

    @Test fun searchAndPickerDecisions() {
        assertEquals(WallpaperMode.CUSTOM, WallpaperCatalog.modeAfterSearch())
        assertEquals(WallpaperMode.DAILY, WallpaperCatalog.modeAfterPickerCancel(WallpaperMode.DAILY, false))
        assertEquals(WallpaperMode.DEFAULT, WallpaperCatalog.modeAfterPickerCancel(WallpaperMode.DEFAULT, false))
        assertEquals(WallpaperMode.CUSTOM, WallpaperCatalog.modeAfterPickerCancel(WallpaperMode.DAILY, true))
    }

    @Test fun rowsAndDailyStatus() {
        assertEquals(WallpaperRows(pick = false, source = false), WallpaperCatalog.rows(WallpaperMode.DEFAULT))
        assertEquals(WallpaperRows(pick = true, source = false), WallpaperCatalog.rows(WallpaperMode.CUSTOM))
        assertEquals(WallpaperRows(pick = false, source = true), WallpaperCatalog.rows(WallpaperMode.DAILY))
        assertEquals(DailyStatus.UPDATED_TODAY, WallpaperCatalog.dailyStatus("2026-11-12", "2026-11-12", true))
        assertEquals(DailyStatus.KEEPING_PREVIOUS, WallpaperCatalog.dailyStatus("2026-11-11", "2026-11-12", true))
        assertEquals(DailyStatus.USING_DEFAULT, WallpaperCatalog.dailyStatus(null, "2026-11-12", false))
        assertEquals(DailyStatus.USING_DEFAULT, WallpaperCatalog.dailyStatus("2026-11-12", "2026-11-12", false))
    }

    @Test fun hostsAllowOnlyTheWallpaperSites() {
        assertTrue(WallpaperHosts.allowed("https://www.bing.com/HPImageArchive.aspx?format=js"))
        assertTrue(WallpaperHosts.allowed("https://bing.com/th?id=1"))
        assertTrue(WallpaperHosts.allowed("https://ts1.explicit.bing.net/th?id=1"))
        assertTrue(WallpaperHosts.allowed("https://cn.bing.com/images/async"))
        assertTrue(WallpaperHosts.allowed("https://bz.zzzmh.cn/search?q=a"))
        assertTrue(WallpaperHosts.allowed("https://imageai.zzzmh.cn/wallpaper/desktop/view/thumb/a.webp"))
        assertTrue(WallpaperHosts.allowed("https://wallhaven.cc/api/v1/search"))
        assertTrue(WallpaperHosts.allowed("https://w.wallhaven.cc/full/ab/a.jpg"))
        assertTrue(WallpaperHosts.allowed("https://th.wallhaven.cc/small/ab/a.jpg"))
        assertFalse(WallpaperHosts.allowed("http://www.bing.com/a"))
        assertFalse(WallpaperHosts.allowed("https://evil.example/a"))
        assertFalse(WallpaperHosts.allowed("https://www.bing.com.evil.com/a"))
        assertFalse(WallpaperHosts.allowed("https://notbing.net/a"))
        assertFalse(WallpaperHosts.allowed("https://user:pass@www.bing.com/a"))
        assertFalse(WallpaperHosts.allowed("not a url"))
        assertTrue(WallpaperHosts.bingOriginal("https://photos.example/a.jpg"))
        assertFalse(WallpaperHosts.allowed("https://photos.example/a.jpg"))
        assertFalse(WallpaperHosts.bingOriginal("http://photos.example/a.jpg"))
        assertFalse(WallpaperHosts.bingOriginal("https://user:pass@photos.example/a.jpg"))
    }

    @Test fun screenCoverRequiresBothSidesStrictlyLarger() {
        assertTrue(WallpaperCatalog.coversScreen(1921, 721, 1920, 720))
        assertFalse(WallpaperCatalog.coversScreen(1920, 1080, 1920, 720))
        assertFalse(WallpaperCatalog.coversScreen(1080, 1920, 720, 1920))
        assertTrue(WallpaperCatalog.coversScreen(2400, 1081, 1080, 1920))
        assertFalse(WallpaperCatalog.coversScreen(0, 100, 10, 10))
        assertFalse(WallpaperCatalog.coversScreen(100, 100, 0, 10))
    }

    @Test fun bingImageSearchReadsHttpsOriginals() {
        val url = WallpaperCatalog.bingImageSearchUrl(" mountain ")!!
        assertTrue(url.startsWith("https://cn.bing.com/images/async?"))
        assertTrue(url.contains("q=mountain"))
        assertTrue(url.contains("adlt=strict"))
        val html = """<a m="{&quot;murl&quot;:&quot;https://photos.example/a.jpg&quot;,&quot;turl&quot;:&quot;https://ts1.explicit.bing.net/th?id=1&quot;}"></a>""" +
            """<a m="{&quot;murl&quot;:&quot;http://photos.example/b.jpg&quot;,&quot;turl&quot;:&quot;https://ts1.explicit.bing.net/th?id=2&quot;}"></a>"""
        val hits = WallpaperCatalog.parseBingImageSearch(html) as WallpaperSearchParse.Hits
        assertEquals(1, hits.images.size)
        assertEquals("https://photos.example/a.jpg", hits.images[0].fullUrl)
        assertTrue(hits.images[0].bingOriginal)
        assertTrue(hits.images[0].thumbUrl.contains("bing.net"))
        assertEquals(WallpaperSearchParse.Empty, WallpaperCatalog.parseBingImageSearch("<html></html>"))
        assertEquals(WallpaperSearchParse.Invalid, WallpaperCatalog.parseBingImageSearch("  "))
    }

    @Test fun wallhavenSearchDropsImagesThatDoNotCoverTheScreen() {
        val json = """
            {"data":[
              {"path":"https://w.wallhaven.cc/full/ab/big.jpg","dimension_x":3000,"dimension_y":2000,"thumbs":{"small":"https://th.wallhaven.cc/small/ab/big.jpg"}},
              {"path":"https://w.wallhaven.cc/full/ab/exact.jpg","dimension_x":1920,"dimension_y":1080,"thumbs":{"small":"https://th.wallhaven.cc/small/ab/exact.jpg"}},
              {"path":"https://w.wallhaven.cc/full/ab/missing.jpg","thumbs":{"small":"https://th.wallhaven.cc/small/ab/missing.jpg"}}
            ]}
        """.trimIndent()
        val hits = WallpaperCatalog.parseWallhavenSearchSized(json, 1920, 720) as WallpaperSearchParse.Hits
        assertEquals(1, hits.images.size)
        assertEquals("https://w.wallhaven.cc/full/ab/big.jpg", hits.images[0].fullUrl)
        assertEquals(WallpaperSearchParse.Empty, WallpaperCatalog.parseWallhavenSearchSized("""{"data":[{"path":"https://w.wallhaven.cc/full/ab/a.jpg","dimension_x":100,"dimension_y":100}]}""", 1920, 720))
        assertEquals(WallpaperSearchParse.Invalid, WallpaperCatalog.parseWallhavenSearchSized("""{"nope":1}""", 1920, 720))
        val daily = WallpaperCatalog.parseWallhavenDaily("""{"data":[{"path":"https://w.wallhaven.cc/full/ab/exact.jpg","dimension_x":1920,"dimension_y":1080}]}""") as WallpaperFetch.Ready
        assertEquals("https://w.wallhaven.cc/full/ab/exact.jpg", daily.image.fullUrl)
    }

    @Test fun zzzSearchPairsDetailPagesAndSelectedTier() {
        val page = """<a class="card" href="/wallpaper/abc123" data-pid="abc123"><img src="https://imageai.zzzmh.cn/wallpaper/desktop/view/thumb/fff.webp" alt=""></a>"""
        val cards = WallpaperCatalog.parseZzzSearch(page) as ZzzCardParse.Cards
        assertEquals("https://bz.zzzmh.cn/wallpaper/abc123", cards.cards[0].detailUrl)
        assertTrue(cards.cards[0].thumbUrl.contains("/view/thumb/"))
        val detail = """<button class="tier on" data-tier="1920 × 1200" type="button"></button><img src="https://imageai.zzzmh.cn/wallpaper/desktop/view/large/abc.webp">"""
        val parsed = WallpaperCatalog.parseZzzDetail(detail)!!
        assertEquals(1920, parsed.width)
        assertEquals(1200, parsed.height)
        assertTrue(parsed.fullUrl.contains("/view/large/"))
        assertEquals(null, WallpaperCatalog.parseZzzDetail("""<button class="tier" data-tier="1920 × 1200"></button>"""))
        assertEquals(ZzzCardParse.Empty, WallpaperCatalog.parseZzzSearch("<html></html>"))
        assertEquals(ZzzCardParse.Invalid, WallpaperCatalog.parseZzzSearch(""))
    }
}
