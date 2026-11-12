// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import com.shihab.diplay.ui.GlassColors
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object ShellWallpaper {
    private val generation = AtomicInteger()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var shown: Bitmap? = null

    fun apply(root: View) {
        val ticket = generation.incrementAndGet()
        val file = WallpaperStore(root.context).activeFile()
        if (file == null) {
            root.setBackgroundResource(GlassColors.bgHome())
            recycleShown()
            return
        }
        runInWorker {
            val bitmap = WallpaperBitmaps.decodeFile(file)
            mainHandler.post {
                if (ticket != generation.get()) {
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap == null) {
                    root.setBackgroundResource(GlassColors.bgHome())
                    return@post
                }
                val previous = shown
                shown = bitmap
                root.background = CenterCropBitmapDrawable(bitmap)
                if (previous != null && previous !== bitmap && !previous.isRecycled) previous.recycle()
            }
        }
    }

    private fun runInWorker(block: () -> Unit) {
        if (isRobolectricUnitTest()) {
            mainHandler.post(block)
        } else {
            executor.execute(block)
        }
    }

    private fun isRobolectricUnitTest(): Boolean = try {
        Class.forName("org.robolectric.RuntimeEnvironment")
        true
    } catch (error: ClassNotFoundException) {
        false
    }

    private fun recycleShown() {
        val previous = shown
        shown = null
        if (previous != null && !previous.isRecycled) previous.recycle()
    }
}
