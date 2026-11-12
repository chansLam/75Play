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
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WallpaperStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.filesDir.resolve("wallpaper").deleteRecursively()
    }

    @Test fun modeSourceAndDayRoundTrip() {
        val store = WallpaperStore(context)
        assertEquals(WallpaperMode.DEFAULT, store.mode())
        assertEquals(WallpaperDailySource.BING, store.source())
        assertNull(store.dailyDay())
        store.saveMode(WallpaperMode.DAILY)
        store.saveSource(WallpaperDailySource.WALLHAVEN)
        store.saveDailyDay("2026-11-12")
        assertEquals(WallpaperMode.DAILY, store.mode())
        assertEquals(WallpaperDailySource.WALLHAVEN, store.source())
        assertEquals("2026-11-12", store.dailyDay())
        store.saveSource(WallpaperDailySource.BING)
        assertEquals(WallpaperDailySource.BING, store.source())
        assertNull(store.dailyDay())
    }

    @Test fun activeFileFollowsMode() {
        val store = WallpaperStore(context)
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        assertTrue(store.writeCustom(bitmap))
        assertTrue(store.writeDaily(bitmap))
        bitmap.recycle()
        store.saveMode(WallpaperMode.DEFAULT)
        assertNull(store.activeFile())
        store.saveMode(WallpaperMode.CUSTOM)
        assertEquals("custom.jpg", store.activeFile()!!.name)
        store.saveMode(WallpaperMode.DAILY)
        assertEquals("daily.jpg", store.activeFile()!!.name)
    }

    @Test fun copyStreamHonorsTheCap() {
        val dest = context.filesDir.resolve("wallpaper/incoming.jpg")
        val ok = WallpaperBitmaps.copyStream(ByteArrayInputStream(ByteArray(4)), dest, maxBytes = 8)
        assertTrue(ok)
        assertEquals(4, dest.length())
        val tooBig = context.filesDir.resolve("wallpaper/too-big.jpg")
        assertFalse(WallpaperBitmaps.copyStream(ByteArrayInputStream(ByteArray(9)), tooBig, maxBytes = 8))
        assertFalse(tooBig.exists())
    }

    @Test fun jpegRoundTripKeepsADecodableFile() {
        val store = WallpaperStore(context)
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        assertTrue(store.writeCustom(bitmap))
        bitmap.recycle()
        store.saveMode(WallpaperMode.CUSTOM)
        val decoded = WallpaperBitmaps.decodeFile(store.activeFile()!!)
        assertTrue(decoded != null && decoded.width > 0 && decoded.height > 0)
        decoded!!.recycle()
    }
}
