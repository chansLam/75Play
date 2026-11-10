package com.shihab.diplay.media

/**
 * i.MX6 scans the buffer out as soon as MediaCodec renders it. A second render inside the same
 * refresh overwrites that buffer, which is the broken picture at 60 fps and the occasional one at
 * 30 fps (only when the decoder catches up and releases two frames back to back).
 *
 * The gate allows one shown frame per refresh. Older frames of a burst are not shown; the caller
 * passes [newerReady] for those. The newest frame is shown once the refresh has elapsed.
 */
internal class ScanoutGate(
    private val minGapNs: Long = MIN_GAP_NS,
) {
    private var lastShownNs = NEVER

    fun allow(nowNs: Long): Boolean {
        if (lastShownNs != NEVER && nowNs - lastShownNs < minGapNs) return false
        lastShownNs = nowNs
        return true
    }

    fun reset() {
        lastShownNs = NEVER
    }

    companion object {
        /** Shorter than a 60 fps period, longer than two releases in one drain. */
        const val MIN_GAP_NS = 12_000_000L
        private const val NEVER = Long.MIN_VALUE
    }
}

internal fun showOnScanout(
    gate: ScanoutGate,
    nowNs: Long,
    toScreen: Boolean,
    catchUp: Boolean,
    newerReady: Boolean,
    collapseBursts: Boolean,
): Boolean {
    if (!toScreen || catchUp || newerReady) return false
    if (!collapseBursts) return true
    return gate.allow(nowNs)
}
