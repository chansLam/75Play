// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

internal data class WallpaperHttpResult(
    val status: Int,
    val body: ByteArray,
    val finalUrl: String,
    val ok: Boolean,
)

internal fun interface WallpaperTransport {
    fun get(url: String): WallpaperHttpResult

    fun get(url: String, bingOriginal: Boolean): WallpaperHttpResult = get(url)
}

internal class WallpaperClient(private val transport: WallpaperTransport) {
    fun text(url: String): String? = bytes(url)?.toString(Charsets.UTF_8)

    fun bytes(url: String, bingOriginal: Boolean = false): ByteArray? {
        if (!WallpaperHosts.allows(url, bingOriginal)) return null
        val result = try {
            transport.get(url, bingOriginal)
        } catch (error: Exception) {
            return null
        } catch (error: OutOfMemoryError) {
            return null
        }
        if (!result.ok || result.status !in 200..299) return null
        if (!WallpaperHosts.allows(result.finalUrl, bingOriginal)) return null
        if (result.body.size > MAX_BODY) return null
        return result.body
    }

    companion object {
        const val MAX_BODY = 12 * 1024 * 1024
    }
}
