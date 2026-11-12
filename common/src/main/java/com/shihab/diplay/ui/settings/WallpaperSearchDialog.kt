// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.DiPlayDialog
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.LayoutMetrics
import com.shihab.diplay.wallpaper.OkHttpWallpaperTransport
import com.shihab.diplay.wallpaper.WallpaperBitmaps
import com.shihab.diplay.wallpaper.WallpaperCatalog
import com.shihab.diplay.wallpaper.WallpaperClient
import com.shihab.diplay.wallpaper.WallpaperSearchParse
import com.shihab.diplay.wallpaper.WallpaperHosts
import com.shihab.diplay.wallpaper.WallpaperImage
import com.shihab.diplay.wallpaper.WallpaperSearchBoard
import com.shihab.diplay.wallpaper.WallpaperSearchFinish
import com.shihab.diplay.wallpaper.WallpaperSearchSource
import com.shihab.diplay.wallpaper.WallpaperSearchStatus
import com.shihab.diplay.wallpaper.WallpaperStore
import com.shihab.diplay.wallpaper.ZzzCardParse
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal object WallpaperSearchDialog {
    fun show(context: Context, onApplied: () -> Unit) {
        val transport = OkHttpWallpaperTransport(context)
        val client = WallpaperClient(transport)
        val closed = AtomicBoolean(false)
        val generation = AtomicInteger()
        var shownPreview: android.app.Dialog? = null
        val pool = Executors.newFixedThreadPool(3)
        val imagePool = Executors.newFixedThreadPool(2)
        val main = Handler(Looper.getMainLooper())
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(context).apply {
            setSingleLine(true)
            hint = context.getString(R.string.settings_wallpaper_search_hint)
            setTextColor(GlassColors.TEXT_PRIMARY)
            setHintTextColor(GlassColors.TEXT_MUTED)
            setBackgroundResource(GlassColors.inputBg())
            val padH = LayoutMetrics.capPadH(context)
            val padV = LayoutMetrics.capPadV(context)
            setPadding(padH, padV, padH, padV)
        }
        val search = Button(context).apply {
            text = context.getString(R.string.settings_wallpaper_search_action)
            setBackgroundResource(GlassColors.btnPrimary())
            setTextColor(GlassColors.NAV_SELECTED_TEXT)
            minHeight = LayoutMetrics.rowMinH(context)
            minimumHeight = LayoutMetrics.rowMinH(context)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(search)
        }
        val progress = ProgressBar(context).apply { visibility = View.GONE }
        val status = TextView(context).apply {
            text = context.getString(R.string.settings_wallpaper_search_hint)
            setTextColor(GlassColors.TEXT_MUTED)
            textSize = LayoutMetrics.settingsCapTextSize(context)
        }
        val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(header)
        content.addView(progress)
        content.addView(status)
        content.addView(results)
        val dialog = DiPlayDialog.showCustom(
            context,
            context.getString(R.string.settings_wallpaper_search),
            content,
            fillMaxHeight = true,
            allowFocus = true,
        )
        fun alive() = !closed.get() && dialog.isShowing
        dialog.setOnDismissListener {
            closed.set(true)
            shownPreview?.dismiss()
            shownPreview = null
            transport.cancel()
            pool.shutdownNow()
            imagePool.shutdownNow()
        }
        search.setOnClickListener {
            val ticket = generation.incrementAndGet()
            val query = input.text?.toString().orEmpty()
            shownPreview?.dismiss()
            shownPreview = null
            transport.cancel()
            results.removeAllViews()
            progress.visibility = View.GONE
            if (query.isBlank()) {
                status.visibility = View.VISIBLE
                status.text = context.getString(R.string.settings_wallpaper_search_hint)
                return@setOnClickListener
            }
            val metrics = context.resources.displayMetrics
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels
            val board = WallpaperSearchBoard()
            val stopOthers = AtomicBoolean(false)
            val cells = LinkedHashMap<String, View>()
            fun current() = alive() && generation.get() == ticket
            var previewControl: DiPlayDialog.WallpaperPreview? = null
            var currentUrl: String? = null
            var loadTicket = 0
            var loading = false
            var locked = false
            fun updateEnds() {
                val shown = previewControl ?: return
                val images = board.visible()
                val index = images.indexOfFirst { it.fullUrl == currentUrl }
                shown.setEnds(index > 0, index >= 0 && index < images.lastIndex)
            }
            fun loadNeighbor(delta: Int) {
                if (locked || loading) return
                val shown = previewControl ?: return
                if (!shown.dialog.isShowing) return
                val images = board.visible()
                val index = images.indexOfFirst { it.fullUrl == currentUrl }
                val nextIndex = index + delta
                if (index < 0 || nextIndex !in images.indices) {
                    updateEnds()
                    return
                }
                val target = images[nextIndex]
                val ticketNow = ++loadTicket
                loading = true
                shown.setBusy(true)
                imagePool.execute {
                    val bytes = client.bytes(target.fullUrl, target.bingOriginal)
                    val size = bytes?.let { WallpaperBitmaps.bounds(it) }
                    val largeEnough = size != null && WallpaperCatalog.coversScreen(size.first, size.second, screenW, screenH)
                    val decoded = if (largeEnough && current()) bytes?.let { WallpaperBitmaps.decode(it) } else null
                    main.post {
                        if (!current() || previewControl !== shown || !shown.dialog.isShowing || ticketNow != loadTicket) {
                            decoded?.recycle()
                            return@post
                        }
                        loading = false
                        shown.setBusy(false)
                        if (decoded == null) {
                            val message = if (size != null && !largeEnough) {
                                context.getString(R.string.settings_wallpaper_too_small)
                            } else {
                                context.getString(R.string.settings_wallpaper_thumb_failed)
                            }
                            shown.showNote(message)
                            return@post
                        }
                        currentUrl = target.fullUrl
                        shown.replace(decoded)
                        updateEnds()
                    }
                }
            }
            fun openPreview(image: WallpaperImage, bitmap: Bitmap) {
                shownPreview?.dismiss()
                currentUrl = image.fullUrl
                locked = false
                loading = false
                loadTicket++
                val shown = DiPlayDialog.showImagePreview(
                    context,
                    bitmap,
                    onConfirm = { currentBitmap ->
                        locked = true
                        loading = false
                        loadTicket++
                        imagePool.execute {
                            val saved = current() && WallpaperStore(context).writeCustom(currentBitmap)
                            main.post {
                                if (!current()) return@post
                                if (!saved) {
                                    locked = false
                                    previewControl?.unlock()
                                    previewControl?.showNote(context.getString(R.string.settings_wallpaper_thumb_failed))
                                    return@post
                                }
                                WallpaperStore(context).saveMode(WallpaperCatalog.modeAfterSearch())
                                onApplied()
                                dialog.dismiss()
                            }
                        }
                    },
                    onStep = { delta -> loadNeighbor(delta) },
                )
                previewControl = shown
                shownPreview = shown.dialog
                updateEnds()
            }
            fun render() {
                if (!current()) return
                updateEnds()
                val images = board.visible()
                val keep = images.map { it.fullUrl }.toSet()
                cells.keys.retainAll(keep)
                results.removeAllViews()
                var row: LinearLayout? = null
                images.forEachIndexed { index, image ->
                    if (index % 4 == 0) {
                        row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                        results.addView(row)
                    }
                    val cell = cells.getOrPut(image.fullUrl) {
                        searchCell(context, image, client, imagePool, main, ::current, screenW, screenH) { ready, readyBitmap ->
                            openPreview(ready, readyBitmap)
                        }
                    }
                    (cell.parent as? ViewGroup)?.removeView(cell)
                    row!!.addView(cell)
                }
                val waiting = !board.settled()
                progress.visibility = if (waiting) View.VISIBLE else View.GONE
                if (images.isNotEmpty()) {
                    status.visibility = View.GONE
                    return
                }
                when (board.status()) {
                    WallpaperSearchStatus.LOADING -> status.visibility = View.GONE
                    WallpaperSearchStatus.NONE -> {
                        status.visibility = View.VISIBLE
                        status.text = context.getString(R.string.settings_wallpaper_search_empty)
                    }
                    WallpaperSearchStatus.FAILED -> {
                        status.visibility = View.VISIBLE
                        status.text = context.getString(R.string.settings_wallpaper_search_failed)
                    }
                    WallpaperSearchStatus.HITS -> status.visibility = View.GONE
                }
            }
            progress.visibility = View.VISIBLE
            status.visibility = View.GONE
            pool.execute {
                if (!current()) return@execute
                val url = WallpaperCatalog.bingImageSearchUrl(query) ?: return@execute
                val html = client.text(url)
                if (!current()) return@execute
                if (html == null) {
                    main.post { if (current()) { board.finish(WallpaperSearchSource.BING, WallpaperSearchFinish.FAILED); render() } }
                    return@execute
                }
                when (val parsed = WallpaperCatalog.parseBingImageSearch(html)) {
                    WallpaperSearchParse.Invalid -> main.post {
                        if (current()) { board.finish(WallpaperSearchSource.BING, WallpaperSearchFinish.FAILED); render() }
                    }
                    WallpaperSearchParse.Empty -> main.post {
                        if (current()) { board.finish(WallpaperSearchSource.BING, WallpaperSearchFinish.EMPTY); render() }
                    }
                    is WallpaperSearchParse.Hits -> {
                        var accepted = 0
                        for (image in parsed.images) {
                            if (!current() || accepted >= WallpaperCatalog.SEARCH_LIMIT) break
                            val bytes = client.bytes(image.fullUrl, true) ?: continue
                            val size = WallpaperBitmaps.bounds(bytes) ?: continue
                            if (!WallpaperCatalog.coversScreen(size.first, size.second, screenW, screenH)) continue
                            accepted++
                            if (accepted >= WallpaperCatalog.SEARCH_LIMIT) stopOthers.set(true)
                            main.post {
                                if (!current()) return@post
                                board.add(WallpaperSearchSource.BING, listOf(image))
                                if (board.shouldCancelOthers()) stopOthers.set(true)
                                render()
                            }
                        }
                        val kind = if (accepted == 0) WallpaperSearchFinish.EMPTY else WallpaperSearchFinish.DONE
                        main.post { if (current()) { board.finish(WallpaperSearchSource.BING, kind); render() } }
                    }
                }
            }
            pool.execute {
                if (!current()) return@execute
                if (stopOthers.get()) {
                    main.post { if (current()) { board.finish(WallpaperSearchSource.WALLHAVEN, WallpaperSearchFinish.EMPTY); render() } }
                    return@execute
                }
                val url = WallpaperCatalog.wallhavenSearchUrl(query) ?: return@execute
                val json = client.text(url)
                main.post {
                    if (!current()) return@post
                    if (json == null) {
                        board.finish(WallpaperSearchSource.WALLHAVEN, WallpaperSearchFinish.FAILED)
                    } else if (stopOthers.get()) {
                        board.finish(WallpaperSearchSource.WALLHAVEN, WallpaperSearchFinish.EMPTY)
                    } else when (val parsed = WallpaperCatalog.parseWallhavenSearchSized(json, screenW, screenH)) {
                        WallpaperSearchParse.Invalid -> board.finish(WallpaperSearchSource.WALLHAVEN, WallpaperSearchFinish.FAILED)
                        WallpaperSearchParse.Empty -> board.finish(WallpaperSearchSource.WALLHAVEN, WallpaperSearchFinish.EMPTY)
                        is WallpaperSearchParse.Hits -> {
                            board.add(WallpaperSearchSource.WALLHAVEN, parsed.images)
                            board.finish(WallpaperSearchSource.WALLHAVEN, WallpaperSearchFinish.DONE)
                        }
                    }
                    render()
                }
            }
            pool.execute {
                if (!current()) return@execute
                val url = WallpaperCatalog.zzzSearchUrl(query) ?: return@execute
                if (stopOthers.get()) {
                    main.post { if (current()) { board.finish(WallpaperSearchSource.ZZZMH, WallpaperSearchFinish.EMPTY); render() } }
                    return@execute
                }
                val html = client.text(url)
                if (!current()) return@execute
                if (html == null) {
                    main.post { if (current()) { board.finish(WallpaperSearchSource.ZZZMH, WallpaperSearchFinish.FAILED); render() } }
                    return@execute
                }
                when (val parsed = WallpaperCatalog.parseZzzSearch(html)) {
                    ZzzCardParse.Invalid -> main.post {
                        if (current()) { board.finish(WallpaperSearchSource.ZZZMH, WallpaperSearchFinish.FAILED); render() }
                    }
                    ZzzCardParse.Empty -> main.post {
                        if (current()) { board.finish(WallpaperSearchSource.ZZZMH, WallpaperSearchFinish.EMPTY); render() }
                    }
                    is ZzzCardParse.Cards -> {
                        var accepted = 0
                        for (card in parsed.cards) {
                            if (!current() || stopOthers.get()) break
                            val detailHtml = client.text(card.detailUrl) ?: continue
                            val detail = WallpaperCatalog.parseZzzDetail(detailHtml) ?: continue
                            if (!WallpaperCatalog.coversScreen(detail.width, detail.height, screenW, screenH)) continue
                            accepted++
                            val image = WallpaperImage(detail.fullUrl, card.thumbUrl)
                            main.post {
                                if (!current() || stopOthers.get()) return@post
                                board.add(WallpaperSearchSource.ZZZMH, listOf(image))
                                render()
                            }
                        }
                        val kind = if (accepted == 0) WallpaperSearchFinish.EMPTY else WallpaperSearchFinish.DONE
                        main.post { if (current()) { board.finish(WallpaperSearchSource.ZZZMH, kind); render() } }
                    }
                }
            }
        }
    }

    private fun searchCell(
        context: Context,
        image: WallpaperImage,
        client: WallpaperClient,
        pool: java.util.concurrent.ExecutorService,
        main: Handler,
        alive: () -> Boolean,
        screenW: Int,
        screenH: Int,
        onReady: (WallpaperImage, Bitmap) -> Unit,
    ): View {
        val edge = LayoutMetrics.wallpaperThumbPx(context)
        val note = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(GlassColors.TEXT_MUTED)
            textSize = LayoutMetrics.ghostTextSize(context)
            visibility = View.GONE
        }
        val spinner = ProgressBar(context).apply { visibility = View.GONE }
        val thumb = ImageView(context).apply {
            contentDescription = context.getString(R.string.settings_wallpaper_thumb_cd)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setOnClickListener {
                note.visibility = View.GONE
                spinner.visibility = View.VISIBLE
                pool.execute {
                    val bytes = client.bytes(image.fullUrl, image.bingOriginal)
                    val size = bytes?.let { WallpaperBitmaps.bounds(it) }
                    val largeEnough = size != null && WallpaperCatalog.coversScreen(size.first, size.second, screenW, screenH)
                    val bitmap = if (largeEnough && alive()) bytes?.let { WallpaperBitmaps.decode(it) } else null
                    main.post {
                        if (!alive()) {
                            bitmap?.recycle()
                            return@post
                        }
                        spinner.visibility = View.GONE
                        if (size != null && !largeEnough) {
                            note.text = context.getString(R.string.settings_wallpaper_too_small)
                            note.visibility = View.VISIBLE
                            return@post
                        }
                        if (bitmap == null) {
                            note.text = context.getString(R.string.settings_wallpaper_thumb_failed)
                            note.visibility = View.VISIBLE
                            return@post
                        }
                        onReady(image, bitmap)
                    }
                }
            }
        }
        val cell = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(edge, edge)
            addView(thumb, FrameLayout.LayoutParams(edge, edge))
            addView(spinner, FrameLayout.LayoutParams(edge, edge, Gravity.CENTER))
            addView(note, FrameLayout.LayoutParams(edge, edge, Gravity.CENTER))
        }
        pool.execute {
            val bytes = client.bytes(image.thumbUrl, image.bingOriginal && !WallpaperHosts.allowed(image.thumbUrl))
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            main.post {
                if (!alive()) {
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap == null) {
                    note.text = context.getString(R.string.settings_wallpaper_thumb_failed)
                    note.visibility = View.VISIBLE
                } else {
                    thumb.setImageBitmap(bitmap)
                }
            }
        }
        return cell
    }
}
