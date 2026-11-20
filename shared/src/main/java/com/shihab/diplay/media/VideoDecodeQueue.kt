package com.shihab.diplay.media

import android.view.Surface
import com.shihab.diplay.airplay.VideoCodec
import com.shihab.diplay.airplay.VideoFrameBuffers
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    class Frame(
        var nalus: ByteArray,
        var receivedNs: Long = System.nanoTime(),
        /** The iPhone's frame time and when the frame arrived (System.nanoTime), 0 when unknown. */
        var senderNanos: Long = 0L,
        var arrivalNanos: Long = 0L,
        /** Valid prefix of [nalus]. Storage from the frame pool may be larger than the frame. */
        var length: Int = nalus.size,
    ) : VideoJob {
        private var pooled = false

        internal fun recycle() {
            if (!pooled) return
            pooled = false
            nalus = EMPTY
            synchronized(POOL) {
                if (POOL.size < MAX_POOLED) POOL.addLast(this)
            }
        }

        companion object {
            private val POOL = ArrayDeque<Frame>()
            private val EMPTY = ByteArray(0)
            private const val MAX_POOLED = 24

            internal fun obtain(
                nalus: ByteArray,
                senderNanos: Long,
                arrivalNanos: Long,
                length: Int,
            ): Frame {
                val frame = synchronized(POOL) {
                    if (POOL.isEmpty()) null else POOL.removeFirst()
                } ?: Frame(nalus)
                frame.nalus = nalus
                frame.receivedNs = System.nanoTime()
                frame.senderNanos = senderNanos
                frame.arrivalNanos = arrivalNanos
                frame.length = length
                frame.pooled = true
                return frame
            }
        }
    }
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    /** Stop rendering to a surface that is going away; completes [request] once the codec has let go. */
    data class DetachSurface(val request: SurfaceDetachRequest) : VideoJob
    /** Ask the iPhone for a keyframe, so a surface that just came back gets a picture without waiting for motion. */
    data object RefreshPicture : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec, length: Int = bytes.size): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec, length)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    companion object {
        /**
         * A CarPlay connection starts with a burst of frames while the phone settles its display
         * mode. Twenty-four frames (about 0.8 s at 30 fps) absorb that burst without letting a
         * long backlog build; overflow still keeps the newest keyframe and the frames after it.
         */
        fun forDevice(): VideoDecodeQueue =
            VideoDecodeQueue(maxFrames = 24, maxBytes = 3 * 1024 * 1024)
    }
    private val jobs = ArrayDeque<VideoJob>()
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private var pendingFrames = 0
    private var pendingBytes = 0L

    fun offer(job: VideoJob): Unit = lock.withLock {
        if (job is VideoJob.Frame) {
            val bytes = job.length.coerceIn(0, job.nalus.size)
            if (pendingFrames >= maxFrames || pendingBytes + bytes > maxBytes) {
                val anchor = if (isRandomAccess(job.nalus, job.length)) {
                    discardFramesLocked()
                    ChainAnchor.Incoming
                } else {
                    trimBeforeLatestRandomAccess()
                }
                val exceedsBytes = pendingBytes + bytes > maxBytes && pendingFrames > 0
                if (anchor == ChainAnchor.None || (anchor != ChainAnchor.Incoming && exceedsBytes)) {
                    discardFramesLocked()
                    enqueue(VideoJob.Resync)
                }
            }
            // An oversized frame is an unusable reference chain, not retained in the queue.
            if (bytes > maxBytes) {
                if (VideoJob.Resync !in jobs) enqueue(VideoJob.Resync)
                VideoFrameBuffers.give(job.nalus)
                job.recycle()
                return
            }
            pendingFrames++
            pendingBytes += bytes
        }
        enqueue(job)
    }

    private fun enqueue(job: VideoJob) {
        jobs.addLast(job)
        available.signal()
    }

    fun pendingFrameCount(): Int = lock.withLock { pendingFrames }

    private enum class ChainAnchor { None, Incoming, Trimmed, Continuous }

    /**
     * Drops frames before the newest keyframe. A chain that already starts at a keyframe stays
     * intact: those frames still have to be decoded, and the display catches up separately.
     */
    private fun trimBeforeLatestRandomAccess(): ChainAnchor {
        var latest = -1
        var index = 0
        for (item in jobs) {
            if (item is VideoJob.Frame && isRandomAccess(item.nalus, item.length)) latest = index
            index++
        }
        if (latest < 0) return ChainAnchor.None
        if (latest == 0) return ChainAnchor.Continuous
        val iterator = jobs.iterator()
        var cursor = 0
        while (iterator.hasNext() && cursor < latest) {
            val item = iterator.next()
            if (item is VideoJob.Frame) {
                val itemBytes = item.length.coerceIn(0, item.nalus.size)
                VideoFrameBuffers.give(item.nalus)
                item.recycle()
                pendingFrames = maxOf(0, pendingFrames - 1)
                pendingBytes = maxOf(0L, pendingBytes - itemBytes)
                iterator.remove()
            } else if (item is VideoJob.Resync) iterator.remove()
            cursor++
        }
        return ChainAnchor.Trimmed
    }

    private fun isRandomAccess(nalus: ByteArray, length: Int): Boolean =
        MediaCodecSupport.isRandomAccess(nalus, VideoCodec.H264, length) ||
            MediaCodecSupport.isRandomAccess(nalus, VideoCodec.H265, length)

    fun discardFrames() = lock.withLock { discardFramesLocked() }

    private fun discardFramesLocked() {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item is VideoJob.Frame) {
                VideoFrameBuffers.give(item.nalus)
                item.recycle()
                iterator.remove()
            } else if (item is VideoJob.Resync) {
                iterator.remove()
            }
        }
        pendingFrames = 0
        pendingBytes = 0L
    }

    // Waiting must never hold the queue monitor: USB receive callbacks enqueue video frames.
    fun poll(timeoutMillis: Long): VideoJob? = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (jobs.isEmpty()) {
            if (remaining <= 0) return null
            remaining = available.awaitNanos(remaining)
        }
        val job = jobs.removeFirst()
        if (job is VideoJob.Frame) {
            pendingFrames--
            pendingBytes -= job.length.coerceIn(0, job.nalus.size)
        }
        job
    }

    fun drain(): List<VideoJob> = lock.withLock {
        val remaining = ArrayList(jobs)
        jobs.clear()
        pendingFrames = 0
        pendingBytes = 0L
        remaining
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
