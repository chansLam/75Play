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
