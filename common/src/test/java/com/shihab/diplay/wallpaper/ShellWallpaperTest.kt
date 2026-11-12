// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.graphics.Bitmap
import android.widget.LinearLayout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ShellWallpaperTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.filesDir.resolve("wallpaper").deleteRecursively()
    }

    @Test fun defaultModeUsesTheBuiltInBackground() {
        val root = LinearLayout(context)
        WallpaperStore(context).saveMode(WallpaperMode.DEFAULT)
        ShellWallpaper.apply(root)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(shadowOf(root.background).createdFromResId != 0)
    }

    @Test fun customModeInstallsTheCenterCropDrawable() {
        val store = WallpaperStore(context)
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        assertTrue(store.writeCustom(bitmap))
        bitmap.recycle()
        store.saveMode(WallpaperMode.CUSTOM)
        val root = LinearLayout(context)
        ShellWallpaper.apply(root)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(root.background is CenterCropBitmapDrawable)
    }

    @Test fun missingDailyFileLeavesNoCenterCrop() {
        WallpaperStore(context).saveMode(WallpaperMode.DAILY)
        val root = LinearLayout(context)
        ShellWallpaper.apply(root)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(root.background !is CenterCropBitmapDrawable)
        assertNull(WallpaperStore(context).activeFile())
    }
}
