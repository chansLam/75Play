// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import java.net.URI
import java.util.Locale

internal object WallpaperHosts {
    fun allowed(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "www.bing.com" ||
            host == "bing.com" ||
            host == "cn.bing.com" ||
            host == "bz.zzzmh.cn" ||
            host == "imageai.zzzmh.cn" ||
            host == "wallhaven.cc" ||
            host == "w.wallhaven.cc" ||
            host == "th.wallhaven.cc" ||
            host.endsWith(".bing.net")
    }

    /** 必应图片搜索解析出的原图：任意 https 主机，但不能带账号。 */
    fun bingOriginal(url: String): Boolean = hostOf(url) != null

    fun allows(url: String, original: Boolean): Boolean =
        if (original) bingOriginal(url) else allowed(url)

    private fun hostOf(url: String): String? {
        val uri = try {
            URI(url)
        } catch (error: Exception) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.userInfo.isNullOrEmpty()) return null
        return uri.host?.lowercase(Locale.US)
    }
}
