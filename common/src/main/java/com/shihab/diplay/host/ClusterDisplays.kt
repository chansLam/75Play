// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaRouter
import android.os.Handler
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

/**
 * The system presentation display, when this head unit has one. [probe] always returns session-log
 * lines, including the cars that have no secondary screen.
 */
internal object ClusterDisplays {
    data class Found(val candidate: ClusterCandidate, val display: Display)
    data class Probe(val found: Found?, val lines: List<String>)

    fun probe(context: Context): Probe {
        val lines = ArrayList<String>()
        return try {
            val window = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val defaultId = window?.defaultDisplay?.displayId
            if (defaultId == null) {
                lines += "Cluster probe: default display missing"
                return Probe(null, lines)
            }
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val categoryIds = categoryDisplays(manager).map { it.displayId }.toSet()
            val all = manager?.displays?.toList().orEmpty()
            lines += "Cluster probe defaultId=$defaultId displays=${all.size} category=${categoryIds.size}"
            if (manager == null) lines += "Cluster displays: DisplayManager missing"
            all.forEach { display ->
                lines += "Cluster display ${describe(display)} category=${display.displayId in categoryIds}"
            }
            val router = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
            val routerDisplay = if (router == null) {
                lines += "Cluster router: MediaRouter missing"
                null
            } else {
                describeRoutes(router, lines)
            }
            val category = categoryDisplays(manager)
            val routerCandidate = routerDisplay?.let(::candidate)
            val decision = ClusterScreen.decision(defaultId, routerCandidate, category.map(::candidate))
            lines += "Cluster decision: $decision"
            val chosen = ClusterScreen.select(defaultId, routerCandidate, category.map(::candidate))
            val display = chosen?.let { pick ->
                routerDisplay?.takeIf { it.displayId == pick.displayId }
                    ?: category.firstOrNull { it.displayId == pick.displayId }
            }
            if (chosen == null || display == null) {
                lines += "Cluster result: no alt stream"
                Probe(null, lines)
            } else {
                Probe(Found(chosen, display), lines)
            }
        } catch (error: Exception) {
            lines += "Cluster probe failed: ${failureText(error)}"
            Probe(null, lines)
        }
    }

    /**
     * Later display add/remove/change events. A 2.0T cluster panel that appears after the session
     * has already been advertised shows up here.
     */
    fun watch(context: Context, handler: Handler, emit: (String) -> Unit): () -> Unit {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (manager == null) {
            emit("Cluster watch: DisplayManager missing")
            return {}
        }
        val seen = HashMap<Int, String>()
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                val display = manager.getDisplay(displayId)
                if (display == null) {
                    emit("Cluster display added id=$displayId missing")
                    return
                }
                val described = describe(display)
                seen[displayId] = described
                emit("Cluster display added $described category=${inCategory(manager, displayId)}")
            }

            override fun onDisplayRemoved(displayId: Int) {
                seen.remove(displayId)
                emit("Cluster display removed id=$displayId")
            }

            override fun onDisplayChanged(displayId: Int) {
                val display = manager.getDisplay(displayId)
                if (display == null) {
                    emit("Cluster display changed id=$displayId missing")
                    return
                }
                val described = describe(display)
                if (seen[displayId] == described) return
                seen[displayId] = described
                emit("Cluster display changed $described category=${inCategory(manager, displayId)}")
            }
        }
        try {
            manager.registerDisplayListener(listener, handler)
        } catch (error: Exception) {
            emit("Cluster watch failed: ${failureText(error)}")
            return {}
        }
        return {
            runCatching { manager.unregisterDisplayListener(listener) }
            Unit
        }
    }

    fun failureText(error: Throwable): String {
        val at = error.stackTrace.take(3).joinToString(" < ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        return "${error.javaClass.simpleName} ${error.message.orEmpty()} at $at".take(240)
    }

    private fun describeRoutes(router: MediaRouter, lines: MutableList<String>): Display? {
        val selected = try {
            router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO)
        } catch (error: Exception) {
            lines += "Cluster router selected failed: ${error.javaClass.simpleName}"
            null
        }
        val count = router.routeCount.coerceAtMost(MAX_ROUTES)
        if (count == 0) lines += "Cluster router: no routes"
        for (index in 0 until count) {
            val route = router.getRouteAt(index)
            val presentation = try {
                route.presentationDisplay
            } catch (error: Exception) {
                lines += "Cluster route[$index] presentation failed: ${error.javaClass.simpleName}"
                null
            }
            lines += "Cluster route[$index] name=\"${clip(route.name)}\" types=0x${route.supportedTypes.toString(16)} " +
                "selected=${route == selected} presentation=${presentation?.let(::describe) ?: "none"}"
        }
        if (router.routeCount > MAX_ROUTES) lines += "Cluster router: ${router.routeCount - MAX_ROUTES} more routes omitted"
        return selected?.presentationDisplay
    }

    private fun inCategory(manager: DisplayManager, displayId: Int): Boolean =
        categoryDisplays(manager).any { it.displayId == displayId }

    private fun categoryDisplays(manager: DisplayManager?): List<Display> {
        if (manager == null) return emptyList()
        return manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.toList() ?: emptyList()
    }

    private fun candidate(display: Display): ClusterCandidate {
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return ClusterCandidate(display.displayId, metrics.widthPixels, metrics.heightPixels)
    }

    private fun describe(display: Display): String {
        val size = candidate(display)
        val name = try {
            clip(display.name)
        } catch (_: Exception) {
            "?"
        }
        return "id=${size.displayId} name=\"$name\" ${size.widthPixels}x${size.heightPixels} flags=${flagNames(display.flags)}"
    }

    private fun flagNames(flags: Int): String {
        val names = ArrayList<String>()
        if (flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) names += "protected"
        if (flags and Display.FLAG_SECURE != 0) names += "secure"
        if (flags and Display.FLAG_PRIVATE != 0) names += "private"
        if (flags and Display.FLAG_PRESENTATION != 0) names += "presentation"
        val known = Display.FLAG_SUPPORTS_PROTECTED_BUFFERS or Display.FLAG_SECURE or
            Display.FLAG_PRIVATE or Display.FLAG_PRESENTATION
        val extra = flags and known.inv()
        if (extra != 0) names += "0x${extra.toString(16)}"
        return if (names.isEmpty()) "none" else names.joinToString("+")
    }

    private fun clip(value: CharSequence?): String =
        value?.toString()?.replace('\n', ' ')?.take(48).orEmpty()

    private const val MAX_ROUTES = 8
}
