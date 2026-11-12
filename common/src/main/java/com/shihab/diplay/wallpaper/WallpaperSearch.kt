// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

internal sealed class WallpaperSearchResult {
    data object NeedQuery : WallpaperSearchResult()
    data object Failed : WallpaperSearchResult()
    data object None : WallpaperSearchResult()
    data class Hits(val images: List<WallpaperImage>) : WallpaperSearchResult()
}

internal enum class WallpaperSearchSource { BING, WALLHAVEN, ZZZMH }

internal enum class WallpaperSearchFinish { FAILED, EMPTY, DONE }

internal enum class WallpaperSearchStatus { LOADING, HITS, NONE, FAILED }

internal class WallpaperSearchBoard {
    private val bing = ArrayList<WallpaperImage>()
    private val others = ArrayList<WallpaperImage>()
    private val finished = LinkedHashMap<WallpaperSearchSource, WallpaperSearchFinish>()

    fun add(source: WallpaperSearchSource, images: List<WallpaperImage>) {
        if (source == WallpaperSearchSource.BING) bing.addAll(images) else others.addAll(images)
    }

    fun finish(source: WallpaperSearchSource, kind: WallpaperSearchFinish) {
        finished[source] = kind
    }

    fun visible(): List<WallpaperImage> = (bing + others).take(WallpaperCatalog.SEARCH_LIMIT)

    fun shouldCancelOthers(): Boolean = bing.size >= WallpaperCatalog.SEARCH_LIMIT

    fun settled(): Boolean = shouldCancelOthers() || finished.size == WallpaperSearchSource.values().size

    fun status(): WallpaperSearchStatus {
        if (visible().isNotEmpty()) return WallpaperSearchStatus.HITS
        if (!settled()) return WallpaperSearchStatus.LOADING
        if (finished.values.any { it == WallpaperSearchFinish.EMPTY || it == WallpaperSearchFinish.DONE }) {
            return WallpaperSearchStatus.NONE
        }
        return WallpaperSearchStatus.FAILED
    }
}

internal class WallpaperSearchController(private val client: WallpaperClient) {
    fun search(query: String): WallpaperSearchResult {
        val url = WallpaperCatalog.wallhavenSearchUrl(query) ?: return WallpaperSearchResult.NeedQuery
        val json = client.text(url) ?: return WallpaperSearchResult.Failed
        return when (val parsed = WallpaperCatalog.parseWallhavenSearch(json)) {
            is WallpaperSearchParse.Hits -> WallpaperSearchResult.Hits(parsed.images)
            WallpaperSearchParse.Empty -> WallpaperSearchResult.None
            WallpaperSearchParse.Invalid -> WallpaperSearchResult.Failed
        }
    }
}
