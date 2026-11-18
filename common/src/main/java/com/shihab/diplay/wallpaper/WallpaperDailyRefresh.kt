// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object WallpaperDailyRefresh {
    private val busy = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    fun refreshIfDue(
        context: Context,
        today: () -> String = { LocalDate.now().toString() },
        client: WallpaperClient? = null,
        runner: (Runnable) -> Unit = { executor.execute(it) },
        poster: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
        onApplied: () -> Unit,
    ) {
        val app = context.applicationContext
        val store = WallpaperStore(app)
        val day = today()
        if (!WallpaperCatalog.needsDailyDownload(store.mode(), store.dailyDay(), day)) return
        if (!busy.compareAndSet(false, true)) return
        val http = client ?: WallpaperClient(OkHttpWallpaperTransport(app))
        runner(Runnable {
            try {
                val bytes = download(store.source(), http)
                val bitmap = bytes?.let { WallpaperBitmaps.decode(it) }
                if (bitmap != null && store.mode() == WallpaperMode.DAILY && store.writeDaily(bitmap)) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    store.saveDailyDay(day)
                } else {
                    bitmap?.recycle()
                }
            } finally {
                busy.set(false)
            }
            poster(Runnable(onApplied))
        })
    }

    private fun download(source: WallpaperDailySource, client: WallpaperClient): ByteArray? {
        if (source == WallpaperDailySource.WALLHAVEN) {
            return downloadJson(
                client,
                WallpaperCatalog.wallhavenDailyUrl(),
                WallpaperCatalog::parseWallhavenDaily,
            )
        }
        // 优先保留标题/版权等元数据的来源，再退回原 Bing 与直接图片接口。
        downloadJson(client, WallpaperCatalog.peapixDailyUrl(), WallpaperCatalog::parsePeapixDaily)?.let { return it }
        downloadJson(client, WallpaperCatalog.vsaaDailyUrl(), WallpaperCatalog::parseVsaaDaily)?.let { return it }
        downloadJson(client, WallpaperCatalog.bingArchiveUrl(), WallpaperCatalog::parseBing)?.let { return it }
        return client.bytes(WallpaperCatalog.yumusDailyUrl())
    }

    private fun downloadJson(
        client: WallpaperClient,
        url: String,
        parser: (String) -> WallpaperFetch,
    ): ByteArray? {
        val json = client.text(url) ?: return null
        val image = (parser(json) as? WallpaperFetch.Ready)?.image ?: return null
        return client.bytes(image.fullUrl)
    }
}
