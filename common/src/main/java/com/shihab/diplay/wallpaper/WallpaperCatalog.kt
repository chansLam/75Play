// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import org.json.JSONObject
import java.net.URLEncoder

internal enum class WallpaperMode { DEFAULT, CUSTOM, DAILY }

internal enum class WallpaperDailySource { BING, WALLHAVEN }

internal enum class DailyStatus { UPDATED_TODAY, KEEPING_PREVIOUS, USING_DEFAULT }

internal data class WallpaperImage(
    val fullUrl: String,
    val thumbUrl: String,
    val bingOriginal: Boolean = false,
)

internal data class ZzzCard(val detailUrl: String, val thumbUrl: String)

internal data class ZzzDetail(val fullUrl: String, val width: Int, val height: Int)

internal sealed class ZzzCardParse {
    data class Cards(val cards: List<ZzzCard>) : ZzzCardParse()
    data object Empty : ZzzCardParse()
    data object Invalid : ZzzCardParse()
}

internal sealed class WallpaperFetch {
    data class Ready(val image: WallpaperImage) : WallpaperFetch()
    data object Empty : WallpaperFetch()
    data object Invalid : WallpaperFetch()
}

internal sealed class WallpaperSearchParse {
    data class Hits(val images: List<WallpaperImage>) : WallpaperSearchParse()
    data object Empty : WallpaperSearchParse()
    data object Invalid : WallpaperSearchParse()
}

internal data class WallpaperRows(val pick: Boolean, val source: Boolean)

internal object WallpaperCatalog {
    const val MAX_LONG_EDGE = 1920
    const val SEARCH_LIMIT = 24
    const val JPEG_QUALITY = 85

    fun mode(stored: String?): WallpaperMode = when (stored) {
        "custom" -> WallpaperMode.CUSTOM
        "daily" -> WallpaperMode.DAILY
        else -> WallpaperMode.DEFAULT
    }

    fun source(stored: String?): WallpaperDailySource = when (stored) {
        "wallhaven" -> WallpaperDailySource.WALLHAVEN
        else -> WallpaperDailySource.BING
    }

    fun modeKey(mode: WallpaperMode): String = when (mode) {
        WallpaperMode.DEFAULT -> "default"
        WallpaperMode.CUSTOM -> "custom"
        WallpaperMode.DAILY -> "daily"
    }

    fun sourceKey(source: WallpaperDailySource): String = when (source) {
        WallpaperDailySource.BING -> "bing"
        WallpaperDailySource.WALLHAVEN -> "wallhaven"
    }

    fun bingArchiveUrl(): String =
        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN"

    fun wallhavenDailyUrl(): String =
        "https://wallhaven.cc/api/v1/search?categories=100&purity=100&sorting=toplist&topRange=1d&atleast=1920x720&page=1"

    fun coversScreen(imageWidth: Int, imageHeight: Int, screenWidth: Int, screenHeight: Int): Boolean {
        if (imageWidth <= 0 || imageHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return false
        val imageLong = maxOf(imageWidth, imageHeight)
        val imageShort = minOf(imageWidth, imageHeight)
        val screenLong = maxOf(screenWidth, screenHeight)
        val screenShort = minOf(screenWidth, screenHeight)
        return imageLong > screenLong && imageShort > screenShort
    }

    fun bingImageSearchUrl(query: String): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return "https://cn.bing.com/images/async?q=$encoded&first=1&count=$SEARCH_LIMIT" +
            "&adlt=strict&qft=+filterui:imagesize-large"
    }

    fun zzzSearchUrl(query: String): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return "https://bz.zzzmh.cn/search?q=$encoded"
    }

    fun wallhavenSearchUrl(query: String): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return "https://wallhaven.cc/api/v1/search?q=$encoded&categories=100&purity=100" +
            "&atleast=1280x720&sorting=relevance&page=1"
    }

    fun parseBing(json: String): WallpaperFetch {
        val root = jsonObject(json) ?: return WallpaperFetch.Invalid
        val images = root.optJSONArray("images") ?: return WallpaperFetch.Invalid
        if (images.length() == 0) return WallpaperFetch.Empty
        val urlbase = images.optJSONObject(0)?.optString("urlbase").orEmpty()
        if (urlbase.isBlank()) return WallpaperFetch.Invalid
        val full = "https://www.bing.com$urlbase" + "_1920x1080.jpg"
        if (!WallpaperHosts.allowed(full)) return WallpaperFetch.Invalid
        return WallpaperFetch.Ready(WallpaperImage(full, full))
    }

    fun parseWallhavenDaily(json: String): WallpaperFetch = when (val parsed = parseWallhavenSearch(json)) {
        is WallpaperSearchParse.Hits -> WallpaperFetch.Ready(parsed.images.first())
        WallpaperSearchParse.Empty -> WallpaperFetch.Empty
        WallpaperSearchParse.Invalid -> WallpaperFetch.Invalid
    }

    fun parseWallhavenSearch(json: String): WallpaperSearchParse {
        val root = jsonObject(json) ?: return WallpaperSearchParse.Invalid
        if (!root.has("data")) return WallpaperSearchParse.Invalid
        val data = root.optJSONArray("data") ?: return WallpaperSearchParse.Invalid
        if (data.length() == 0) return WallpaperSearchParse.Empty
        val images = ArrayList<WallpaperImage>(WallpaperCatalog.SEARCH_LIMIT)
        for (index in 0 until data.length()) {
            if (images.size >= SEARCH_LIMIT) break
            val item = data.optJSONObject(index) ?: continue
            val path = item.optString("path")
            if (!WallpaperHosts.allowed(path)) continue
            val thumb = item.optJSONObject("thumbs")?.optString("small").orEmpty()
            val thumbUrl = if (WallpaperHosts.allowed(thumb)) thumb else path
            images.add(WallpaperImage(path, thumbUrl))
        }
        if (images.isEmpty()) return WallpaperSearchParse.Invalid
        return WallpaperSearchParse.Hits(images)
    }

    /** 搜索专用。尺寸不够或缺少尺寸的条目丢掉；一张都不剩时算空结果。每日解析不要调用它。 */
    fun parseWallhavenSearchSized(
        json: String,
        screenWidth: Int,
        screenHeight: Int,
    ): WallpaperSearchParse {
        val root = jsonObject(json) ?: return WallpaperSearchParse.Invalid
        if (!root.has("data")) return WallpaperSearchParse.Invalid
        val data = root.optJSONArray("data") ?: return WallpaperSearchParse.Invalid
        if (data.length() == 0) return WallpaperSearchParse.Empty
        val images = ArrayList<WallpaperImage>(SEARCH_LIMIT)
        for (index in 0 until data.length()) {
            if (images.size >= SEARCH_LIMIT) break
            val item = data.optJSONObject(index) ?: continue
            val path = item.optString("path")
            if (!WallpaperHosts.allowed(path)) continue
            if (!coversScreen(item.optInt("dimension_x"), item.optInt("dimension_y"), screenWidth, screenHeight)) {
                continue
            }
            val thumb = item.optJSONObject("thumbs")?.optString("small").orEmpty()
            val thumbUrl = if (WallpaperHosts.allowed(thumb)) thumb else path
            images.add(WallpaperImage(path, thumbUrl))
        }
        if (images.isEmpty()) return WallpaperSearchParse.Empty
        return WallpaperSearchParse.Hits(images)
    }

    fun parseBingImageSearch(html: String): WallpaperSearchParse {
        if (html.isBlank()) return WallpaperSearchParse.Invalid
        val images = ArrayList<WallpaperImage>(SEARCH_LIMIT)
        for (match in Regex("""\sm="([^"]*)"""").findAll(html)) {
            if (images.size >= SEARCH_LIMIT) break
            val item = jsonObject(unescapeHtml(match.groupValues[1])) ?: continue
            val full = item.optString("murl")
            if (!WallpaperHosts.bingOriginal(full)) continue
            val thumbRaw = item.optString("turl")
            val thumb = if (WallpaperHosts.allowed(thumbRaw)) thumbRaw else full
            images.add(WallpaperImage(full, thumb, bingOriginal = true))
        }
        if (images.isEmpty()) return WallpaperSearchParse.Empty
        return WallpaperSearchParse.Hits(images)
    }

    fun parseZzzSearch(html: String): ZzzCardParse {
        if (html.isBlank()) return ZzzCardParse.Invalid
        val cards = ArrayList<ZzzCard>(SEARCH_LIMIT)
        val pattern = Regex(
            """href="(/wallpaper/([A-Za-z0-9]+))"[^>]*>[\s\S]*?src="(https://imageai\.zzzmh\.cn/[^"]+)"""",
        )
        for (match in pattern.findAll(html)) {
            if (cards.size >= SEARCH_LIMIT) break
            val thumb = match.groupValues[3]
            if (!thumb.contains("/view/thumb/") || !WallpaperHosts.allowed(thumb)) continue
            val detail = "https://bz.zzzmh.cn${match.groupValues[1]}"
            if (!WallpaperHosts.allowed(detail)) continue
            cards.add(ZzzCard(detail, thumb))
        }
        if (cards.isEmpty()) return ZzzCardParse.Empty
        return ZzzCardParse.Cards(cards)
    }

    fun parseZzzDetail(html: String): ZzzDetail? {
        if (html.isBlank()) return null
        val button = Regex("""<button\b[^>]*>""").findAll(html).firstOrNull { tag ->
            val cls = Regex("""class="([^"]*)"""").find(tag.value)?.groupValues?.get(1).orEmpty()
            cls.split(Regex("""\s+""")).contains("on") && tag.value.contains("data-tier=")
        } ?: return null
        val tier = Regex("""data-tier="([^"]+)"""").find(button.value)?.groupValues?.get(1) ?: return null
        val dims = Regex("""(\d+)\s*[×xX]\s*(\d+)""").find(tier) ?: return null
        val width = dims.groupValues[1].toIntOrNull() ?: return null
        val height = dims.groupValues[2].toIntOrNull() ?: return null
        val large = Regex("""https://imageai\.zzzmh\.cn/[^"'\s]+/view/large/[^"'\s]+\.webp""")
            .find(html)?.value ?: return null
        if (!WallpaperHosts.allowed(large)) return null
        return ZzzDetail(large, width, height)
    }

    fun needsDailyDownload(mode: WallpaperMode, savedDay: String?, today: String): Boolean =
        mode == WallpaperMode.DAILY && savedDay != today

    fun sampleSize(width: Int, height: Int): Int {
        val longEdge = maxOf(width, height)
        if (longEdge <= MAX_LONG_EDGE) return 1
        var sample = 1
        while (sample <= longEdge / 2 && longEdge / sample > MAX_LONG_EDGE) {
            val next = sample * 2
            if (next <= sample) break
            sample = next
        }
        return sample
    }

    fun modeAfterSearch(): WallpaperMode = WallpaperMode.CUSTOM

    fun modeAfterPickerCancel(previous: WallpaperMode, hasCustomFile: Boolean): WallpaperMode =
        if (hasCustomFile) WallpaperMode.CUSTOM else previous

    fun rows(mode: WallpaperMode): WallpaperRows = WallpaperRows(
        pick = mode == WallpaperMode.CUSTOM,
        source = mode == WallpaperMode.DAILY,
    )

    fun dailyStatus(savedDay: String?, today: String, hasDailyFile: Boolean): DailyStatus = when {
        !hasDailyFile -> DailyStatus.USING_DEFAULT
        savedDay == today -> DailyStatus.UPDATED_TODAY
        else -> DailyStatus.KEEPING_PREVIOUS
    }

    private fun unescapeHtml(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    private fun jsonObject(json: String): JSONObject? = try {
        JSONObject(json)
    } catch (error: Exception) {
        null
    }
}
