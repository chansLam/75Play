// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WallpaperDailyRefreshTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.filesDir.resolve("wallpaper").deleteRecursively()
    }

    @Test fun defaultModeDoesNotDownload() {
        val store = WallpaperStore(context)
        store.saveMode(WallpaperMode.DEFAULT)
        var calls = 0
        WallpaperDailyRefresh.refreshIfDue(
            context,
            today = { "2026-11-12" },
            client = countingClient { calls++ },
            runner = { it.run() },
            poster = { it.run() },
        ) {}
        assertEquals(0, calls)
        assertNull(store.dailyDay())
    }

    @Test fun sameDayDoesNotDownload() {
        val store = WallpaperStore(context)
        store.saveMode(WallpaperMode.DAILY)
        store.saveDailyDay("2026-11-12")
        var calls = 0
        WallpaperDailyRefresh.refreshIfDue(
            context,
            today = { "2026-11-12" },
            client = countingClient { calls++ },
            runner = { it.run() },
            poster = { it.run() },
        ) {}
        assertEquals(0, calls)
    }

    @Test fun failedDownloadKeepsThePreviousFile() {
        val store = WallpaperStore(context)
        store.saveMode(WallpaperMode.DAILY)
        val existing = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertTrue(store.writeDaily(existing))
        existing.recycle()
        val before = store.activeFile()!!.readBytes()
        WallpaperDailyRefresh.refreshIfDue(
            context,
            today = { "2026-11-12" },
            client = WallpaperClient(WallpaperTransport { WallpaperHttpResult(500, ByteArray(0), it, false) }),
            runner = { it.run() },
            poster = { it.run() },
        ) {}
        assertNull(store.dailyDay())
        assertTrue(store.activeFile()!!.readBytes().contentEquals(before))
    }

    @Test fun bingSuccessStoresToday() {
        val store = WallpaperStore(context)
        store.saveMode(WallpaperMode.DAILY)
        val jpeg = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out))
            bitmap.recycle()
        }.toByteArray()
        val imageUrl = "https://www.bing.com/th?id=OHR.Example_ZH-CN1_1920x1080.jpg"
        val client = WallpaperClient(WallpaperTransport { url ->
            val body = if (url == imageUrl) {
                jpeg
            } else {
                """{"images":[{"urlbase":"/th?id=OHR.Example_ZH-CN1"}]}""".toByteArray()
            }
            WallpaperHttpResult(200, body, url, true)
        })
        var applied = 0
        WallpaperDailyRefresh.refreshIfDue(
            context,
            today = { "2026-11-12" },
            client = client,
            runner = { it.run() },
            poster = { it.run() },
        ) { applied++ }
        assertEquals("2026-11-12", store.dailyDay())
        assertTrue(store.hasDaily())
        assertEquals(1, applied)
    }

    @Test fun downloadCompletingAfterModeSwitchedDoesNotOverwrite() {
        val store = WallpaperStore(context)
        store.saveMode(WallpaperMode.DAILY)
        val jpeg = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out))
            bitmap.recycle()
        }.toByteArray()
        val imageUrl = "https://www.bing.com/th?id=OHR.Example_ZH-CN1_1920x1080.jpg"
        val client = WallpaperClient(WallpaperTransport { url ->
            val body = if (url == imageUrl) jpeg
            else """{"images":[{"urlbase":"/th?id=OHR.Example_ZH-CN1"}]}""".toByteArray()
            WallpaperHttpResult(200, body, url, true)
        })
        WallpaperDailyRefresh.refreshIfDue(
            context,
            today = { "2026-11-12" },
            client = client,
            runner = { backgroundTask ->
                // Switch mode while download is in-flight before background execution finishes
                store.saveMode(WallpaperMode.CUSTOM)
                backgroundTask.run()
            },
            poster = { it.run() },
        ) {}
        assertNull(store.dailyDay())
        assertFalse(store.hasDaily())
    }

    private fun countingClient(onCall: () -> Unit) = WallpaperClient(WallpaperTransport {
        onCall()
        WallpaperHttpResult(200, ByteArray(0), it, true)
    })
}
