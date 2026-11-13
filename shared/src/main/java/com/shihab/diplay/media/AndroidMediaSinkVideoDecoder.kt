package com.shihab.diplay.media

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.shihab.diplay.airplay.VideoCodec
import com.shihab.diplay.airplay.VideoFrameBuffers
import com.shihab.diplay.compat.inputBufferCompat
import com.shihab.diplay.compat.realSdkInt
import java.io.Closeable
import java.nio.ByteBuffer
import com.shihab.diplay.util.AppLog

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
internal class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    statsLabel: String? = null,
    pacingDelayNanos: Long = 0L,
    /** Called on the worker as its last step, after it has released its codec. */
    private val onExit: (VideoDecoder) -> Unit = {},
) : Closeable {
    private val pacer = FramePacer()
    private val pacingDelay = if (pacingDelayNanos > 0) PacingDelay(pacingDelayNanos) else null
    // Per queued presentation time (decode thread only): when it was queued, and whether it is a paced
    // frame's local time rather than the queue time.
    private val queuedPresentationUs = LongArray(64)
    private val queuedAtNanos = LongArray(64)
    private val queuedPaced = BooleanArray(64)
    // Set on the paced frames queued just before a pause in the iPhone's frames: the decoder releases a
    // frame only once about two more are queued, so they waited for that pause, which no display delay
    // can hide.
    private val queuedBeforePause = BooleanArray(64)
    private val recentPacedSlots = IntArray(PAUSE_HELD_FRAMES) { -1 }
    private var queuedSlot = 0
    // The decoder releases a frame only once later input arrives, so a frame queued before a still-screen
    // gap waits for the next one; such waits are not decode time and are left out of the stats.
    private var lastQueuedNanos = 0L
    private var resumedAtNanos = 0L
    private var lastQueuedLocalNanos = 0L
    private val queue = VideoDecodeQueue.forDevice()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var parking: ParkingOutput? = null
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var failureReports = 0
    // KitKat (API 19) i.MX6 decoders often ignore csd-0/csd-1; the first frame must
    // carry SPS/PPS in-band or it fails to decode. Set on configure.
    private var inBandSpsPps: ByteArray? = null
    // Reused on the decoder thread. A fresh BufferInfo per drain is a Dalvik GC hitch at 30 fps.
    private val outputInfo = MediaCodec.BufferInfo()
    // Reused Annex-B conversion + optional in-band SPS/PPS prepend (decoder thread only).
    private var annexScratch = ByteArray(0)
    private var feedScratch = ByteArray(0)
    private val referenceChain = VideoReferenceChain()
    // i.MX6 scanout: collapse back-to-back renders. Newer devices keep the paced path.
    private val collapseScanoutBursts = realSdkInt() <= Build.VERSION_CODES.KITKAT
    private val scanout = ScanoutGate()
    private var lastKeyFrameRequestNs = 0L
    /**
     * A new surface reconfigures the codec before the iPhone's opening IDR arrives.
     * Requesting a keyframe in that gap makes iOS reset the encoder; the IDR already on the
     * wire is the picture, and everything after it trickles in. Hold the request so a fresh
     * SETUP can play, and let a reused mid-GOP ask once this window passes.
     */
    @Volatile private var holdInitialKeyFrameUntilNs = 0L
    private var consecutiveInputWithoutOutput = 0
    private val stallRebuildTracker = DecoderStallRebuildTracker()
    private var lastOutputRenderedNs = 0L
    private var loggedNoSurfaceConfigure = false
    private var loggedNoSurfaceFrames = false
    private var surfaceAttachedAtNs = if (surface != null) System.nanoTime() else 0L
    /** Frames that arrived before the host had a surface. Worker thread only. */
    private val openingHold = OpeningPictureHold()
    private var replayingHold = false
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == MAIN_SCREEN_TYPE) "" else " stream=$streamType")
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true }

    fun start() { thread.start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray, byteCount: Int = nalus.size, senderNanos: Long = 0L, arrivalNanos: Long = 0L) {
        val count = byteCount.coerceIn(0, nalus.size)
        stats.onReceived(count)
        // Pacing runs on the worker, in queue order: callbacks of a replaced stream may still deliver here.
        queue.offer(VideoJob.Frame(nalus, senderNanos = senderNanos, arrivalNanos = arrivalNanos, length = count))
    }

    fun setSurface(surface: Surface?) {
        // Replacing a surface that is already showing: arm the grace before the worker runs,
        // so a keyframe ask queued in the same turn cannot reset an opening IDR. The first
        // surface is different — the picture may already have been missed — and the worker
        // decides after it looks at what was held.
        if (surface != null && outputSurface != null) {
            holdInitialKeyFrameUntilNs = System.nanoTime() + INITIAL_PICTURE_GRACE_NS
        }
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    /** Asks the worker to stop rendering to [surface], parking the codec when [park]. */
    fun requestDetach(surface: Surface, park: Boolean): SurfaceDetachRequest {
        check(Thread.currentThread() !== thread) { "detach from the decoder worker" }
        return SurfaceDetachRequest(surface, park).also { queue.offer(VideoJob.DetachSurface(it)) }
    }

    /** True once [request] is confirmed or the worker has exited; false when [deadlineNanos] passed first. */
    fun awaitDetach(request: SurfaceDetachRequest, deadlineNanos: Long): Boolean =
        request.await(deadlineNanos - System.nanoTime(), workerAlive = thread::isAlive)

    /** True once the worker has exited, so its codec is released. */
    val exited: Boolean get() = !thread.isAlive

    /**
     * After [close], waits until [deadlineNanos] for the worker to release its codec and exit. An
     * interrupt does not end the wait early; it is restored before returning.
     */
    fun awaitExitUntil(deadlineNanos: Long): Boolean {
        var interrupted = false
        try {
            while (thread.isAlive) {
                val leftMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (leftMillis <= 0) break
                try { thread.join(leftMillis) } catch (_: InterruptedException) { interrupted = true }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    fun refreshPicture() {
        queue.offer(VideoJob.RefreshPicture)
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            try {
                                if (outputSurface == null) {
                                    // Wireless SETUP delivers the opening IDR before the host
                                    // surface exists. Dropping it leaves a still screen black.
                                    holdWithoutSurface(job)
                                } else {
                                    val now = System.nanoTime()
                                    val rendering = lastOutputRenderedNs != 0L &&
                                        now - lastOutputRenderedNs < MIN_OUTPUT_STALL_NS
                                    if (discardUnrenderedBacklog(
                                            renderedOnce = lastOutputRenderedNs != 0L,
                                            renderingNow = rendering,
                                            ageNs = now - job.receivedNs,
                                            maxAgeNs = MAX_FRAME_AGE_NS,
                                        )
                                    ) {
                                        queue.discardFrames()
                                        recover("video backlog exceeded 800 ms")
                                    } else {
                                        // A late frame is still fed. Skipping it breaks every following
                                        // predicted frame and the picture freezes until the next keyframe.
                                        feed(job.nalus, job.length, localTimeOf(job))
                                    }
                                }
                            } finally {
                                VideoFrameBuffers.give(job.nalus)
                            }
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.DetachSurface -> detach(job.request)
                        // Once per new surface, so it skips the rate limit an earlier request may still hold.
                        // Ask even before the codec exists: the keyframe can be the first thing it decodes.
                        is VideoJob.RefreshPicture -> {
                            // Surface attach asks immediately, then changeSurface holds the first
                            // second so that request does not reset an opening IDR.
                            if (holdInitialKeyFrameUntilNs == 0L || System.nanoTime() >= holdInitialKeyFrameUntilNs) {
                                holdInitialKeyFrameUntilNs = 0L
                                lastKeyFrameRequestNs = System.nanoTime()
                                requestKeyFrame()
                            }
                        }
                        is VideoJob.Resync -> recoverBacklog("video queue overflow")
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    maybeRebuildAfterOutputStall()
                    stats.logIfDue()?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    if (running) AppLog.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    releaseDecoder()
                    referenceChain.reset()
                    requestKeyFrameIfDue()
                } catch (error: LinkageError) {
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    throw error
                }
            }
        } catch (t: InterruptedException) {
            // Worker shut down.
            AppLog.w(TAG, "video decoder worker interrupted", t)
        } finally {
            releaseDecoder()
            // The codec is gone, so nothing renders to any surface or to the parking consumer any more.
            parking?.close()
            parking = null
            queue.drain().forEach { job ->
                when (job) {
                    is VideoJob.DetachSurface -> job.request.complete(DetachOutcome.RELEASED)
                    is VideoJob.Frame -> VideoFrameBuffers.give(job.nalus)
                    else -> Unit
                }
            }
            runCatching { onExit(this) }
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                AppLog.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        val surface = outputSurface
        if (surface == null) {
            if (!loggedNoSurfaceConfigure) {
                loggedNoSurfaceConfigure = true
                report("configure deferred: no surface")
            }
            return
        }
        loggedNoSurfaceConfigure = false
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) VIDEO_HEVC_MIME else VIDEO_AVC_MIME
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        // Old KitKat (API 19) decoders often ignore csd-0/csd-1; prepend SPS/PPS
        // in-band to the first frame so it decodes.
        inBandSpsPps = if (codec != VideoCodec.H265 && realSdkInt() <= Build.VERSION_CODES.KITKAT) {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            if (sps.isNotEmpty() && pps.isNotEmpty()) {
                START_CODE + sps + START_CODE + pps
            } else null
        } else null
        // Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
        // parameters with BAD_VALUE. Fall back to a minimal format, then to software.
        val attempts = listOf(
            DecoderAttempt(codecName = null, tuned = true),
            DecoderAttempt(codecName = null, tuned = false),
        ) + softwareDecoderName(mime)?.let { listOf(DecoderAttempt(it, tuned = false)) }.orEmpty()
        var next: MediaCodec? = null
        for (attempt in attempts) {
            next = tryConfigure(mime, csd, surface, attempt)
            if (next != null) break
        }
        if (next == null) {
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        decoder = next
        renderedFrameLogged = false
        submittedFrameLogged = false
        if (next != null) {
            val decoderName = safeDecoderName(next)
            report("decoder=$decoderName mime=$mime size=${width}x$height")
            AppLog.i(
                TAG,
                "video decoder configured name=$decoderName mime=$mime size=${width}x$height",
            )
            replayOpeningHold()
        }
    }

    /** MediaCodec.getName() is optional and reached through an API-19-safe reflection helper. */
    private fun safeDecoderName(codec: MediaCodec): String =
        runCatching {
            platformMediaCodec("decoderName", arrayOf(MediaCodec::class.java), codec) as String
        }.getOrDefault("unknown")

    private data class DecoderAttempt(val codecName: String?, val tuned: Boolean)

    private fun buildFormat(mime: String, csd: List<ByteArray>, tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                runCatching {
                    platformMediaCodec("setRealtimePriority", arrayOf(MediaFormat::class.java), this)
                }
            }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        return try {
            val format = buildFormat(mime, csd, attempt.tuned)
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            if (attempt.tuned) {
                runCatching {
                    platformMediaCodec(
                        "enableLowLatency",
                        arrayOf(MediaCodec::class.java, MediaFormat::class.java, String::class.java),
                        codec,
                        format,
                        mime,
                    )
                }
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            codec
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            reportFailure("stage=configure tuned=${attempt.tuned} mime=$mime", error)
            AppLog.w(
                TAG,
                "video decoder configure failed name=${attempt.codecName ?: "default"} " +
                    "tuned=${attempt.tuned} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    /**
     * Software fallback uses the API 16 codec list and the standard `OMX.google.` prefix.
     */
    private fun softwareDecoderName(mime: String): String? = runCatching {
        platformMediaCodec("softwareDecoderName", arrayOf(String::class.java), mime) as String?
    }.getOrNull()

    private fun createDecoder(mime: String): MediaCodec {
        if (mime == VIDEO_HEVC_MIME && preferSoftwareHevcDecoder) {
            val software = runCatching {
                platformMediaCodec("softwareDecoderName", arrayOf(String::class.java), mime) as String?
            }.getOrNull()
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software)
                } catch (error: Exception) {
                    AppLog.w(TAG, "software HEVC decoder unavailable name=$software", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun localTimeOf(frame: VideoJob.Frame): Long =
        if (pacingDelay != null && frame.senderNanos > 0 && frame.arrivalNanos > 0) {
            pacer.localTime(frame.senderNanos, frame.arrivalNanos)
        } else 0L

    /** Completes [request] once the codec no longer renders to its surface, whatever happens here. */
    private fun detach(request: SurfaceDetachRequest) {
        var outcome = DetachOutcome.RELEASED
        try {
            outcome = detachAction(outputSurface, request.surface, request.park, decoder != null)
            when (outcome) {
                DetachOutcome.NOT_RENDERING -> Unit
                DetachOutcome.PARKED -> {
                    // KitKat ImageReader rejects the codec color format (getNumPlanesFromFormat).
                    // Building a parking surface there throws and leaves the picture black on return.
                    // Release the decoder; the rebuilt SurfaceView asks for a keyframe.
                    val codec = checkNotNull(decoder)
                    val canMoveSurface = runCatching {
                        platformMediaCodec(
                            "supportsOutputSurface",
                            arrayOf(MediaCodec::class.java),
                            codec,
                        ) as Boolean
                    }.getOrDefault(false)
                    if (!canMoveSurface) {
                        outcome = DetachOutcome.RELEASED
                        outputSurface = null
                        releaseDecoder()
                        AppLog.i(TAG, "video decoder released on surface detach")
                    } else {
                        val consumer = parking ?: ParkingOutput(width, height).also { parking = it }
                        try {
                            platformMediaCodec(
                                "setOutputSurface",
                                arrayOf(MediaCodec::class.java, Surface::class.java),
                                codec,
                                consumer.surface,
                            )
                            outputSurface = consumer.surface
                            AppLog.i(TAG, "video decoder parked off screen")
                        } catch (error: Exception) {
                            AppLog.w(TAG, "video decoder could not park; releasing it", error)
                            outcome = DetachOutcome.RELEASED
                            outputSurface = null
                            releaseDecoder()
                        }
                    }
                }
                DetachOutcome.RELEASED -> {
                    outputSurface = null
                    releaseDecoder()
                    AppLog.i(TAG, "video decoder detached from surface")
                }
            }
        } catch (error: Exception) {
            outcome = DetachOutcome.RELEASED
            outputSurface = null
            releaseDecoder()
            throw error
        } finally {
            request.complete(outcome)
        }
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        surfaceAttachedAtNs = if (surface != null) System.nanoTime() else 0L
        if (surface != null) {
            loggedNoSurfaceConfigure = false
            loggedNoSurfaceFrames = false
        }
        stallRebuildTracker.reset()
        consecutiveInputWithoutOutput = 0
        // A picture that was already missed must ask now. Waiting out the grace assumes the
        // opening IDR is still on the wire; after it was held or lost, that wait is a black screen.
        val askNow = surface != null && openingHold.needsKeyframeNow()
        if (surface != null && !askNow) {
            holdInitialKeyFrameUntilNs = System.nanoTime() + INITIAL_PICTURE_GRACE_NS
        } else if (askNow) {
            holdInitialKeyFrameUntilNs = 0L
        }
        if (surface == null) {
            releaseDecoder()
            AppLog.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                platformMediaCodec(
                    "setOutputSurface",
                    arrayOf(MediaCodec::class.java, Surface::class.java),
                    codec,
                    surface,
                )
                // A new surface is a new picture. Report the next shown frame again so the host
                // can uncover; setOutputSurface itself does not reset the codec.
                renderedFrameLogged = false
                AppLog.i(TAG, "video decoder output surface updated")
                replayOpeningHold()
                return
            } catch (error: Exception) {
                AppLog.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        val pending = lastConfig
        if (pending != null) {
            configureDecoder(pending)
            // A freshly configured codec has no reference frames. A reused stream is mid-GOP and
            // needs a keyframe, but a stream that just started is already sending one. Asking in
            // this gap resets the iPhone encoder and the picture sticks on that first frame.
            // A chain that was already missed asks inside replayOpeningHold instead.
            if (decoder != null && !askNow) {
                holdInitialKeyFrameUntilNs = System.nanoTime() + INITIAL_PICTURE_GRACE_NS
            }
        }
        replayOpeningHold()
    }

    private fun holdWithoutSurface(job: VideoJob.Frame) {
        val count = job.length.coerceIn(0, job.nalus.size)
        val codec = lastConfig?.codec
        val keyframe = if (codec != null) {
            MediaCodecSupport.isRandomAccess(job.nalus, codec, count)
        } else {
            MediaCodecSupport.isRandomAccess(job.nalus, VideoCodec.H264, count) ||
                MediaCodecSupport.isRandomAccess(job.nalus, VideoCodec.H265, count)
        }
        openingHold.accept(keyframe, job.nalus.copyOf(count), localTimeOf(job))
        if (!loggedNoSurfaceFrames) {
            loggedNoSurfaceFrames = true
            report("frames held: waiting for the video surface")
        }
    }

    /** Feed what arrived before the surface, then ask only when that chain cannot be shown. */
    private fun replayOpeningHold() {
        if (replayingHold || outputSurface == null || lastConfig == null || !openingHold.hasPending()) return
        val replay = openingHold.snapshot()
        if (replay.frames.isNotEmpty()) {
            report("replaying ${replay.frames.size} frames held before the video surface")
        }
        replayingHold = true
        try {
            for (frame in replay.frames) feed(frame.bytes, frame.bytes.size, frame.presentNs)
            if (replay.askKeyframe) {
                // The held keyframe can paint the screen, but the frames after it were not a
                // contiguous chain. Reject live predicted frames until the new IDR arrives.
                referenceChain.reset()
                holdInitialKeyFrameUntilNs = 0L
                requestKeyFrameIfDue()
                // One request is enough. A second one in this turn (the surface refresh) resets
                // the encoder and the new IDR never becomes the picture.
                holdInitialKeyFrameUntilNs = System.nanoTime() + INITIAL_PICTURE_GRACE_NS
                report("opening picture missed; requested a keyframe")
            }
        } finally {
            replayingHold = false
        }
    }

    /**
     * Keep decoding while the host view does not exist only when the runtime exposes the optional
     * surface-switch method. KitKat returns false and waits for the view's own surface.
     */
    private fun ensureParkingSurface(): Boolean {
        val codec = decoder ?: return false
        val canMoveSurface = runCatching {
            platformMediaCodec(
                "supportsOutputSurface",
                arrayOf(MediaCodec::class.java),
                codec,
            ) as Boolean
        }.getOrDefault(false)
        if (!canMoveSurface) return false
        return try {
            val consumer = parking ?: ParkingOutput(width, height).also { parking = it }
            outputSurface = consumer.surface
            true
        } catch (error: Exception) {
            AppLog.w(TAG, "video decoder could not start on a parking surface", error)
            false
        }
    }

    private fun feed(nalus: ByteArray, length: Int, presentNs: Long = 0L) {
        val count = length.coerceIn(0, nalus.size)
        val annex = MediaCodecSupport.toAnnexBFrame(nalus, count, annexScratch)
        if (annex.bytes !== nalus && annex.bytes !== annexScratch && annex.length > 0) {
            annexScratch = annex.bytes
        }
        val annexB = annex.bytes
        val annexLength = annex.length
        val config = lastConfig ?: return
        if (outputSurface == null) {
            if (!loggedNoSurfaceFrames) {
                loggedNoSurfaceFrames = true
                report("frames dropped: no surface")
            }
            return
        }
        if (annex.isEmpty()) { recover("invalid video access unit"); return }
        if (!referenceChain.accepts(annexB, config.codec, annexLength)) {
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            AppLog.i(
                TAG,
                "video decoder first input avcc=$count annexB=$annexLength",
            )
        }
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) { recover("video decoder input stalled"); return }
        val input = codec.inputBufferCompat(index)
        checkNotNull(input) { "Decoder input buffer unavailable" }
        input.clear()
        val spsPps = inBandSpsPps
        val frameData: ByteArray
        val frameLength: Int
        if (spsPps != null) {
            inBandSpsPps = null
            AppLog.i(TAG, "video decoder prepending SPS/PPS in-band to first frame (${spsPps.size} bytes)")
            frameLength = spsPps.size + annexLength
            if (feedScratch.size < frameLength) feedScratch = ByteArray(frameLength)
            frameData = feedScratch
            spsPps.copyInto(frameData)
            annexB.copyInto(frameData, spsPps.size, 0, annexLength)
        } else {
            frameData = annexB
            frameLength = annexLength
        }
        if (frameLength <= input.remaining()) {
            input.put(frameData, 0, frameLength)
            // A paced frame carries its local time; MediaCodec returns it with the decoded output.
            val presentationUs = if (presentNs > 0) presentNs / 1000 else System.nanoTime() / 1000
            codec.queueInputBuffer(index, 0, frameLength, presentationUs, 0)
            val queuedNow = System.nanoTime()
            if (lastQueuedNanos != 0L && queuedNow - lastQueuedNanos > STILL_GAP_NS) resumedAtNanos = queuedNow
            lastQueuedNanos = queuedNow
            queuedPresentationUs[queuedSlot] = presentationUs
            queuedAtNanos[queuedSlot] = queuedNow
            queuedPaced[queuedSlot] = presentNs > 0
            queuedBeforePause[queuedSlot] = false
            if (presentNs > 0) {
                // A gap between consecutive iPhone frame times, not a decoder backlog, marks a pause.
                if (lastQueuedLocalNanos != 0L && presentNs - lastQueuedLocalNanos > SENDER_PAUSE_NS) {
                    recentPacedSlots.forEach { if (it >= 0) queuedBeforePause[it] = true }
                }
                lastQueuedLocalNanos = presentNs
                recentPacedSlots.copyInto(recentPacedSlots, 1, 0, PAUSE_HELD_FRAMES - 1)
                recentPacedSlots[0] = queuedSlot
            }
            queuedSlot = (queuedSlot + 1) % queuedPresentationUs.size
            referenceChain.onQueued()
            consecutiveInputWithoutOutput++
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            recover("video frame exceeded codec input capacity")
            return
        }
        drainOutput(codec)
    }

    private fun maybeRebuildAfterOutputStall() {
        if (consecutiveInputWithoutOutput <= 0 || outputSurface == null || lastConfig == null) return
        val now = System.nanoTime()
        if (surfaceAttachedAtNs == 0L || now - surfaceAttachedAtNs < SURFACE_WARMUP_NS) return
        val sinceOutput = if (lastOutputRenderedNs == 0L) Long.MAX_VALUE else now - lastOutputRenderedNs
        if (sinceOutput < MIN_OUTPUT_STALL_NS) return
        val forceRebuild = stallRebuildTracker.onStall()
        val reason = "video decoder output stalled: $consecutiveInputWithoutOutput frames queued without output" +
            if (forceRebuild) " (repeated stalls; forcing decoder rebuild)" else ""
        consecutiveInputWithoutOutput = 0
        if (forceRebuild) recover(reason) else recoverBacklog(reason)
    }

    private fun recover(reason: String) {
        AppLog.w(TAG, "Video recovery: $reason; waiting for keyframe")
        holdInitialKeyFrameUntilNs = 0L
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    /** Clear backlog and request an I-frame without destroying MediaCodec (avoids black-screen loops). */
    private fun recoverBacklog(reason: String) {
        AppLog.w(TAG, "Video backlog recovery: $reason; requesting keyframe without destroying decoder")
        holdInitialKeyFrameUntilNs = 0L
        stats.onRecovery()
        report("backlog: $reason; requesting keyframe")
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (holdInitialKeyFrameUntilNs != 0L) {
            if (now < holdInitialKeyFrameUntilNs) return
            holdInitialKeyFrameUntilNs = 0L
        }
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun queuedSlotOf(presentationUs: Long): Int =
        queuedPresentationUs.indices.firstOrNull { queuedPresentationUs[it] == presentationUs } ?: -1

    private data class HeldOutput(
        val index: Int,
        val presentationUs: Long,
        val flags: Int,
        val size: Int,
        val dequeuedNs: Long,
    )

    private fun drainOutput(codec: MediaCodec) {
        val info = outputInfo
        // On KitKat, hold the newest ready buffer across a non-blocking dequeue. If another
        // output is already waiting, the older one is returned without rendering so the scanout
        // buffer is not overwritten. The hold is one dequeue, not a sleep.
        var held: HeldOutput? = null
        fun releaseHeld(newerReady: Boolean): Boolean {
            val frame = held ?: return false
            held = null
            return releaseOutput(codec, frame, newerReady)
        }
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    releaseHeld(false)
                    return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val frame = HeldOutput(
                        index,
                        info.presentationTimeUs,
                        info.flags,
                        info.size,
                        System.nanoTime(),
                    )
                    if (collapseScanoutBursts) {
                        if (held != null && releaseHeld(true)) {
                            codec.releaseOutputBuffer(frame.index, false)
                            return
                        }
                        held = frame
                    } else if (releaseOutput(codec, frame, newerReady = false)) {
                        return
                    }
                }
                else -> {
                    releaseHeld(false)
                    return
                }
            }
        }
        releaseHeld(false)
    }

    /** @return true when the drain loop must stop (end of stream, or the worker was interrupted). */
    private fun releaseOutput(codec: MediaCodec, frame: HeldOutput, newerReady: Boolean): Boolean {
        val render = outputSurface != null
        // Parked output goes to the offscreen consumer: it keeps the codec's state, but it is
        // not shown, so it is neither paced nor counted.
        val shown = render && outputSurface !== parking?.surface
        val now = frame.dequeuedNs
        val slot = queuedSlotOf(frame.presentationUs)
        val queued = if (slot >= 0) queuedAtNanos[slot] else 0L
        val heldOverGap = queued in 1 until resumedAtNanos
        if (queued > 0 && !heldOverGap) stats.onDecodeLatency(now - queued)
        val delay = pacingDelay
        val paced = shown && delay != null && slot >= 0 && queuedPaced[slot]
        val localNs = frame.presentationUs * 1000
        val pauseHeld = paced && queuedBeforePause[slot]
        val targetNs = if (paced) {
            if (!heldOverGap && !pauseHeld) delay.onFrame(now - localNs)
            stats.onPacingDelay(delay.nanos)
            localNs + delay.nanos
        } else 0L
        // API 19 has no timed releaseOutputBuffer. Newer devices wait here. On KitKat the wait
        // keeps a decoded buffer checked out while the i.MX6 VPU decodes the next input into the
        // frame already on screen, so this path releases immediately and the scanout gate drops
        // the extra frame instead.
        if (!collapseScanoutBursts && paced && targetNs > 0L) {
            while (running) {
                val ahead = targetNs - System.nanoTime()
                if (ahead <= 1_000_000L || ahead > MAX_PACING_AHEAD_NS) break
                try {
                    Thread.sleep((ahead / 1_000_000L).coerceAtMost(20L))
                } catch (_: InterruptedException) {
                    running = false
                    codec.releaseOutputBuffer(frame.index, false)
                    return true
                }
            }
        }
        if (paced && !heldOverGap && !pauseHeld && System.nanoTime() > targetNs + 5_000_000L) {
            stats.onLate()
        }
        // Decode every frame, but do not display a frame that is already late while a
        // newer one is queued. The reference chain stays intact and the picture catches up.
        val catchUp = paced && targetNs > 0L &&
            System.nanoTime() > targetNs + CATCH_UP_LATENESS_NS &&
            queue.pendingFrameCount() > 0
        val show = showOnScanout(
            scanout,
            System.nanoTime(),
            toScreen = shown,
            catchUp = catchUp,
            newerReady = newerReady,
            collapseBursts = collapseScanoutBursts,
        )
        codec.releaseOutputBuffer(frame.index, if (shown) show else render)
        consecutiveInputWithoutOutput = 0
        stallRebuildTracker.onOutput()
        if (shown) lastOutputRenderedNs = now
        if (show) stats.onRendered()
        if (show && !renderedFrameLogged) {
            renderedFrameLogged = true
            report("first frame rendered")
            AppLog.i(TAG, "video decoder rendered first frame bytes=${frame.size}")
        }
        return frame.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }

    private fun logOutputFormat(format: MediaFormat) {
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull("width")}x${format.intOrNull("height")} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull("stride")} slice=${format.intOrNull("slice-height")} " +
            "color=${format.intOrNull("color-standard")}/${format.intOrNull("color-range")}/${format.intOrNull("color-transfer")}")
        AppLog.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull("width")}x" +
                "${format.intOrNull("height")} " +
                "stride=${format.intOrNull("stride")} " +
                "slice=${format.intOrNull("slice-height")} " +
                "standard=${format.intOrNull("color-standard")} " +
                "range=${format.intOrNull("color-range")} " +
                "transfer=${format.intOrNull("color-transfer")}",
        )
    }

    private fun reportFailure(context: String, error: Throwable) {
        // A bad decoder can fail again on every frame; exported detail stays bounded per worker.
        if (failureReports >= 8) return
        failureReports++
        runCatching { report("decoder failed api=${Build.VERSION.SDK_INT} $context " +
            MediaFailureSummary.describe(error)) }
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        scanout.reset()
        if (codec != null) {
            try {
                codec.stop()
            } catch (t: Exception) {
                // Best effort.
                AppLog.w(TAG, "video codec stop failed", t)
            }
            try {
                codec.release()
            } catch (t: Exception) {
                // Best effort.
                AppLog.w(TAG, "video codec release failed", t)
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val VIDEO_AVC_MIME = "video/avc"
        const val VIDEO_HEVC_MIME = "video/hevc"
        // KitKat / i.MX6: 8 MB KEY_MAX_INPUT_SIZE reservations stress 2 GB Dalvik heaps.
        const val MAX_INPUT_SIZE = 3 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        // A paced frame more than this ahead is treated as a bad mapping and shown at once.
        const val MAX_PACING_AHEAD_NS = 1_000_000_000L
        // Longer input gaps are a still screen, not decoding work.
        const val STILL_GAP_NS = 500_000_000L
        // Consecutive frames this far apart in iPhone time are a pause in its frames: about four frame
        // intervals at 30 fps.
        const val SENDER_PAUSE_NS = 120_000_000L
        // Frames before a pause that wait for it: the decoder holds about two, sometimes three.
        const val PAUSE_HELD_FRAMES = 3
        const val MIN_OUTPUT_STALL_NS = 1_500_000_000L
        const val SURFACE_WARMUP_NS = 3_500_000_000L
        // Long enough for the opening IDR of a fresh SETUP to be queued (this car delivered it
        // about 300 ms after the decoder came up) and short enough that a reused GOP still asks.
        const val INITIAL_PICTURE_GRACE_NS = 1_000_000_000L
        const val MAX_FRAME_AGE_NS = 800_000_000L
        const val CATCH_UP_LATENESS_NS = 80_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

internal fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

