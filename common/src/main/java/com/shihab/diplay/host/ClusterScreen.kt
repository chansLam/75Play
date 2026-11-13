// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import com.shihab.diplay.airplay.AirPlayDisplayConfig

/**
 * A secondary panel the head unit exposes for the instrument cluster. The centre display is never
 * a candidate: cars without this panel keep the existing session (arrows only, no alt stream).
 */
internal data class ClusterCandidate(
    val displayId: Int,
    val widthPixels: Int,
    val heightPixels: Int,
)

internal object ClusterScreen {
    /** Shorter than this is not an instrument cluster (a status strip, a badge, the main panel). */
    const val MIN_SIDE_PX = 160

    /** Long edge advertised to the iPhone. The view still fills the panel; the decoder stays bounded. */
    const val MAX_STREAM_LONG_EDGE = 1280

    /** CarPlay's cluster map. The instruction-card URL is the arrow card, which the CAN broadcast already covers. */
    const val MAP_URL = "maps:/car/instrumentcluster"

    /**
     * The route's presentation display wins. Otherwise the first presentation-category display that
     * is not the centre screen. Nothing usable yields null.
     */
    fun select(
        defaultDisplayId: Int,
        router: ClusterCandidate?,
        presentation: List<ClusterCandidate>,
    ): ClusterCandidate? {
        if (usable(router, defaultDisplayId)) return router
        return presentation.firstOrNull { usable(it, defaultDisplayId) }
    }

    /** One session-log line: which panel was chosen, or why every candidate was refused. */
    fun decision(
        defaultDisplayId: Int,
        router: ClusterCandidate?,
        presentation: List<ClusterCandidate>,
    ): String {
        val chosen = select(defaultDisplayId, router, presentation)
        if (chosen != null) {
            val via = if (router != null && router.displayId == chosen.displayId && usable(router, defaultDisplayId)) {
                "router"
            } else {
                "category"
            }
            return "$via id=${chosen.displayId} ${chosen.widthPixels}x${chosen.heightPixels}"
        }
        val parts = ArrayList<String>()
        when {
            router == null -> parts += "router has no presentation display"
            else -> parts += "router rejected: ${rejection(router, defaultDisplayId)}"
        }
        if (presentation.isEmpty()) parts += "category empty"
        else presentation.forEach { parts += "category rejected: ${rejection(it, defaultDisplayId)}" }
        parts += "arrows only"
        return parts.joinToString("; ")
    }

    /** Null when [candidate] may be the cluster. Otherwise a short reason for the session log. */
    fun rejection(candidate: ClusterCandidate, defaultDisplayId: Int): String = when {
        candidate.displayId == defaultDisplayId ->
            "centre id=${candidate.displayId} ${candidate.widthPixels}x${candidate.heightPixels}"
        candidate.widthPixels < MIN_SIDE_PX || candidate.heightPixels < MIN_SIDE_PX ->
            "below ${MIN_SIDE_PX}px id=${candidate.displayId} ${candidate.widthPixels}x${candidate.heightPixels}"
        else -> "usable id=${candidate.displayId} ${candidate.widthPixels}x${candidate.heightPixels}"
    }

    fun streamConfig(panel: ClusterCandidate, fps: Int): AirPlayDisplayConfig? {
        val (width, height) = streamPixels(panel.widthPixels, panel.heightPixels) ?: return null
        return AirPlayDisplayConfig(
            widthPixels = width,
            heightPixels = height,
            fps = fps,
            // No touch and no knobs: the cluster only shows the map. The centre screen keeps input.
            primaryInputDevice = 1,
            features = 0,
            initialUrl = MAP_URL,
        )
    }

    fun streamPixels(width: Int, height: Int): Pair<Int, Int>? {
        if (width < 2 || height < 2) return null
        val longEdge = maxOf(width, height)
        val scale = if (longEdge <= MAX_STREAM_LONG_EDGE) 1.0 else MAX_STREAM_LONG_EDGE.toDouble() / longEdge
        val scaledWidth = even((width * scale).toInt())
        val scaledHeight = even((height * scale).toInt())
        if (scaledWidth < 2 || scaledHeight < 2) return null
        return scaledWidth to scaledHeight
    }

    private fun usable(candidate: ClusterCandidate?, defaultDisplayId: Int): Boolean {
        candidate ?: return false
        return candidate.displayId != defaultDisplayId &&
            candidate.widthPixels >= MIN_SIDE_PX &&
            candidate.heightPixels >= MIN_SIDE_PX
    }

    private fun even(pixels: Int): Int = pixels / 2 * 2
}
