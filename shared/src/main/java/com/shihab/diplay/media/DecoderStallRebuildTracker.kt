package com.shihab.diplay.media

/**
 * Counts consecutive video-decoder output stalls.
 *
 * The first stalls only ask for a keyframe. Once the count reaches [REBUILD_AFTER_STALLS],
 * the next recovery rebuilds the decoder: a wedged codec never produces the keyframe that
 * would have cleared the wait.
 *
 * A rendered frame resets the count. Reaching the threshold also resets it, so a decoder
 * that stays wedged is rebuilt once per threshold, not on every stall.
 */
class DecoderStallRebuildTracker {
    private var consecutiveStalls = 0

    /** Records one output stall; returns true when this stall forces a rebuild. */
    fun onStall(): Boolean {
        consecutiveStalls++
        if (consecutiveStalls >= REBUILD_AFTER_STALLS) {
            consecutiveStalls = 0
            return true
        }
        return false
    }

    /** A rendered frame breaks the stall sequence. */
    fun onOutput() {
        consecutiveStalls = 0
    }

    /** A fresh pipeline restarts the count. */
    fun reset() {
        consecutiveStalls = 0
    }

    companion object {
        const val REBUILD_AFTER_STALLS = 3
    }
}
