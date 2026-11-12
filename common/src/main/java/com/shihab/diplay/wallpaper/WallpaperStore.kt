// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.content.Context
import android.graphics.Bitmap
import com.shihab.diplay.DiPlayPreferences
import java.io.File

internal class WallpaperStore(context: Context) {
    private val app = context.applicationContext
    private val directory: File get() = File(app.filesDir, "wallpaper")
    private val customFile = File(directory, "custom.jpg")
    private val dailyFile = File(directory, "daily.jpg")

    fun mode(): WallpaperMode = WallpaperCatalog.mode(DiPlayPreferences.wallpaperMode(app))
    fun saveMode(mode: WallpaperMode) {
        DiPlayPreferences.saveWallpaperMode(app, WallpaperCatalog.modeKey(mode))
    }
    fun source(): WallpaperDailySource = WallpaperCatalog.source(DiPlayPreferences.wallpaperDailySource(app))
    fun saveSource(source: WallpaperDailySource) {
        DiPlayPreferences.saveWallpaperDailySource(app, WallpaperCatalog.sourceKey(source))
        DiPlayPreferences.saveWallpaperDailyDay(app, null)
    }
    fun dailyDay(): String? = DiPlayPreferences.wallpaperDailyDay(app)
    fun saveDailyDay(day: String) {
        DiPlayPreferences.saveWallpaperDailyDay(app, day)
    }
    fun hasCustom(): Boolean = customFile.isFile
    fun hasDaily(): Boolean = dailyFile.isFile
    fun incomingFile(): File = File(directory, "incoming.jpg")
    fun activeFile(): File? = when (mode()) {
        WallpaperMode.CUSTOM -> customFile.takeIf { it.isFile }
        WallpaperMode.DAILY -> dailyFile.takeIf { it.isFile }
        WallpaperMode.DEFAULT -> null
    }
    fun writeCustom(bitmap: Bitmap): Boolean = WallpaperBitmaps.writeJpeg(customFile, bitmap)
    fun writeDaily(bitmap: Bitmap): Boolean = WallpaperBitmaps.writeJpeg(dailyFile, bitmap)
}
