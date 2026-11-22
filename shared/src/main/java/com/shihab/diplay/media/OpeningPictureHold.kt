package com.shihab.diplay.media

/**
 * Frames that arrive before the host surface exists.
 *
 * Wireless CarPlay finishes SETUP in the connect service, so the opening IDR is often
 * delivered while [CarPlayHostActivity](the view) does not exist yet. Dropping it leaves a
 * still home screen black: iOS will not send another IDR until asked, and asking in the
 * first second resets an IDR that is still in flight. This hold keeps the chain and tells
 * the decoder which of those two cases it is in once the surface arrives.
 */
internal class OpeningPictureHold(
    private val maxFrames: Int = 24,
) {
    class HeldFrame(val bytes: ByteArray, val presentNs: Long)
    class Replay(val frames: List<HeldFrame>, val askKeyframe: Boolean)

    private val frames = ArrayList<HeldFrame>()
    private var sawFrame = false
    private var chainBroken = false

    fun accept(isKeyframe: Boolean, copy: ByteArray, presentNs: Long) {
        sawFrame = true
        if (isKeyframe) {
            frames.clear()
            chainBroken = false
            frames.add(HeldFrame(copy, presentNs))
            return
        }
        if (frames.isEmpty() || chainBroken) {
            chainBroken = true
            return
        }
        if (frames.size >= maxFrames) {
            chainBroken = true
            if (frames.size > 1) {
                val key = frames[0]
                frames.clear()
                frames.add(key)
            }
            return
        }
        frames.add(HeldFrame(copy, presentNs))
    }

    /** True when the opening picture was missed and cannot be replayed as a whole chain. */
    fun needsKeyframeNow(): Boolean = sawFrame && (chainBroken || frames.isEmpty())

    fun hasPending(): Boolean = frames.isNotEmpty() || needsKeyframeNow()

    fun snapshot(): Replay {
        val replay = Replay(ArrayList(frames), needsKeyframeNow())
        frames.clear()
        sawFrame = false
        chainBroken = false
        return replay
    }

    /** A complete held chain, including a lone IDR after overflow, can paint a returning surface. */
    fun hasCompleteChain(): Boolean = frames.isNotEmpty()
}

/**
 * Persistent compressed replay for a SurfaceView that is destroyed while returning to the shell.
 * The latest GOP is retained across detach/attach, so KitKat can paint locally instead of waiting a
 * network round trip for iOS to produce another IDR. Live frames replace it normally; replay itself
 * does not write back into this buffer.
 */
internal class SurfacePictureReplay(
    private val maxFrames: Int = 24,
    private val maxBytes: Int = 3 * 1024 * 1024,
) {
    class Frame(val bytes: ByteArray, val length: Int)

    private val frames = ArrayList<Frame>()
    private var heldBytes = 0
    private var chainBroken = false

    fun accept(isKeyframe: Boolean, source: ByteArray, length: Int) {
        val count = length.coerceIn(0, source.size)
        if (isKeyframe) {
            clear()
            chainBroken = false
        }
        if (count == 0 || (frames.isEmpty() && !isKeyframe) || chainBroken) return
        if (count > maxBytes) {
            clear()
            chainBroken = true
            return
        }
        if (frames.size >= maxFrames || heldBytes + count > maxBytes) {
            // An IDR alone paints the last known screen. Predicted frames beyond the cap cannot be
            // replayed as a complete chain, so wait for the next IDR instead of retaining a partial GOP.
            val anchor = frames.firstOrNull() ?: return
            frames.drop(1).forEach { com.shihab.diplay.airplay.VideoFrameBuffers.give(it.bytes) }
            frames.clear()
            frames.add(anchor)
            heldBytes = anchor.length
            chainBroken = true
            return
        }
        val copy = com.shihab.diplay.airplay.VideoFrameBuffers.take(count)
        source.copyInto(copy, 0, 0, count)
        frames.add(Frame(copy, count))
        heldBytes += count
    }

    /** Non-consuming: another quick leave/re-enter before a new frame can replay the same picture. */
    fun snapshot(): List<Frame> = ArrayList(frames)

    fun clear() {
        frames.forEach { com.shihab.diplay.airplay.VideoFrameBuffers.give(it.bytes) }
        frames.clear()
        heldBytes = 0
        chainBroken = false
    }
}

/**
 * Before anything has been shown, an old frame is still the opening picture. Discarding it
 * because the decoder was busy (typical of a Wi-Fi burst during configure) is what leaves
 * the screen black. After the first frame, a stall still drops a stale backlog.
 */
internal fun discardUnrenderedBacklog(
    renderedOnce: Boolean,
    renderingNow: Boolean,
    ageNs: Long,
    maxAgeNs: Long,
): Boolean = renderedOnce && !renderingNow && ageNs > maxAgeNs
