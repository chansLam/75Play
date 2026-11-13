// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.shihab.diplay.util.AppLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

internal object WallpaperBitmaps {
    const val PICK_MAX_BYTES = 25 * 1024 * 1024

    fun copyStream(stream: InputStream, dest: File, maxBytes: Int = PICK_MAX_BYTES): Boolean {
        dest.parentFile?.mkdirs()
        return try {
            var exceeded = false
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        exceeded = true
                        break
                    }
                    out.write(buffer, 0, read)
                }
            }
            if (exceeded) {
                dest.delete()
                return false
            }
            true
        } catch (error: Exception) {
            dest.delete()
            false
        }
    }

    fun bounds(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return options.outWidth to options.outHeight
    }

    fun decode(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = WallpaperCatalog.sampleSize(bounds.outWidth, bounds.outHeight)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    } catch (error: OutOfMemoryError) {
        AppLog.w("Wallpaper", "decode oom bytes=${bytes.size}")
        null
    }

    fun decodeFile(file: File): Bitmap? = try {
        if (!file.isFile) null
        else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
            else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = WallpaperCatalog.sampleSize(bounds.outWidth, bounds.outHeight)
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
            }
        }
    } catch (error: OutOfMemoryError) {
        AppLog.w("Wallpaper", "decode oom file")
        null
    }

    fun writeJpeg(file: File, bitmap: Bitmap): Boolean {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        return try {
            FileOutputStream(part).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, WallpaperCatalog.JPEG_QUALITY, out)) {
                    part.delete()
                    return false
                }
            }
            if (file.exists() && !file.delete()) {
                part.delete()
                return false
            }
            if (!part.renameTo(file)) {
                part.delete()
                return false
            }
            true
        } catch (error: Exception) {
            part.delete()
            false
        }
    }

    fun readCapped(stream: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
