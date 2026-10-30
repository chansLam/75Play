package com.shihab.diplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.AudioFormat
import com.shihab.diplay.airplay.BufferedAudioStream
import com.shihab.diplay.airplay.toHexString
import com.shihab.diplay.compat.inputBufferCompat
import com.shihab.diplay.compat.outputBufferCompat
import com.shihab.diplay.compat.realSdkInt
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import com.shihab.diplay.util.AppLog

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
internal class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val callChannel: Int,
    private val audioFocusCoordinator: MediaFocusManager,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    /** Receives the played call audio so the call microphone can cancel its echo. */
    private val echoReference: EchoReference? = null,
    voiceFilter: Boolean = false,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private val pcmChannels = if (format.channels >= 2) 2 else 1
    private val voiceFilter = if (voiceFilter && format.sampleRate > 0) VoiceFilter(format.sampleRate, pcmChannels) else null

    // Keep absent framework types out of Dalvik's erased lambda/field casts on KitKat.
    private var trackAttributes: Any? = null
    private var mappedChannel: AudioChannel? = null
    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    /** Reused while draining AAC output; allocating this per poll causes Dalvik GC hitches. */
    private val outputInfo = MediaCodec.BufferInfo()
    /** Reused RTP payload strip (after 12-byte header) for LPCM/Opus — avoids per-packet copyOfRange. */
    private var rtpPayloadScratch = ByteArray(0)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var shortOpusPackets = 0
    private var decoderUnavailablePackets = 0
    private var softwareOpusDecoder: NativeOpusDecoder? = null
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private var firstPcmWriteReported = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val packetsDroppedTotal = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    /** Music with sustained decoder backpressure must not keep playing minutes-old packets. */
    private val mediaLagGuard = if (format.audioType == "media") MediaPacketLagGuard(format.sampleRate) else null
    @Volatile private var newestRtpSample = 0
    private var lagRecoveryReports = 0
    private var clockTrim: MediaClockTrim? = null
    private var diagnosticStage = "starting"
    private var lastDecoderOutputMetadata: String? = null
    private var decoderOutputReports = 0
    // Issue #170: set once the platform AAC decoder is proven degenerate and swapped out.
    private var softwareAacFallbackDone = false
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) {
                val gap = (now - previous) / 1_000_000L
                var old = maxArrivalGapMs.get()
                while (gap > old && !maxArrivalGapMs.compareAndSet(old, gap)) old = maxArrivalGapMs.get()
            }
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started) {
                packetsDropped.incrementAndGet()
                packetsDroppedTotal.incrementAndGet()
            }
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                AppLog.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        }
    }

    override fun close() {
        running = false
        // Release this renderer's audio focus. Without this, a finished call leaves its track
        // in MediaFocusManager; media reopened afterwards never wins focus and stays silent.
        abandonAudioFocus()
        thread.interrupt()
    }

    private fun run() {
        try {
            runCatching { report("Audio: starting api=${Build.VERSION.SDK_INT} " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} " +
                "mapping=${if (advancedAudioChannelMapping) "automotive" else "mobile"} " +
                "mediaChannel=$mediaChannel navigationChannel=$navigationChannel focus=$audioFocusEnabled") }
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            clockTrim = if (mappedChannel == AudioChannel.MEDIA) {
                MediaClockTrim(
                    format.sampleRate,
                    (startThresholdBytes / frameBytes).toLong(),
                    measuresTarget = format.payloadType == BufferedAudioStream.STREAM_TYPE,
                )
            } else {
                null
            }
            diagnosticStage = "focus"
            requestAudioFocus()
            while (running) {
                diagnosticStage = "packet"
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Trim the head unit's audio clock toward the iPhone's so music does not drift.
                track?.let { if (playbackStarted) trimClock(it) }
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                diagnosticStage = "decoder-output"
                codec?.let(::drainCodec)
                diagnosticStage = "buffer-maintenance"
                maintainPlaybackBuffer()
                diagnosticStage = "stats"
                logStatsIfDue()
            }
        } catch (t: InterruptedException) {
            // Worker shut down.
            AppLog.w(TAG, "audio renderer worker interrupted", t)
        } catch (error: Exception) {
            if (running) {
                AppLog.e(TAG, "audio renderer worker failed", error)
                reportFailure(error)
            }
        } catch (error: LinkageError) {
            // A missing platform audio API must not kill the head-unit process.
            if (running) {
                AppLog.e(TAG, "audio renderer linkage", error)
                reportFailure(error)
            }
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
        }
    }

    private fun configureCodec(mime: String) {
        codec = createConfiguredDecoder(mime, forceSoftware = false)
    }

    /**
     * Builds, configures and starts an audio decoder without touching [codec], so the
     * degenerate-decoder fallback can construct the replacement before releasing the old one.
     */
    private fun createConfiguredDecoder(mime: String, forceSoftware: Boolean): MediaCodec? {
        diagnosticStage = "decoder-format"
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            AppLog.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        val softwareName = if (forceSoftware) findSoftwareDecoderName(mime) else null
        if (forceSoftware && softwareName == null) {
            report("Audio: no software decoder found for $mime on this device; keeping platform decoder")
            return null
        }
        return try {
            MediaCodecStartup.create(
                create = {
                    diagnosticStage = "decoder-create"
                    if (softwareName != null) MediaCodec.createByCodecName(softwareName)
                    else MediaCodec.createDecoderByType(mime)
                },
                configure = { diagnosticStage = "decoder-configure"; it.configure(mediaFormat, null, null, 0) },
                start = { diagnosticStage = "decoder-start"; it.start() },
                release = { it.release() },
            ).also {
                // A vendor name getter or diagnostic callback must not discard a started codec.
                runCatching {
                    val name = it.name
                    AppLog.i(TAG, "audio decoder configured mime=$mime name=$name")
                    report("Audio: decoder ready audioType=${format.audioType} codec=${format.codec} name=$name")
                }
            }
        } catch (error: Exception) {
            AppLog.e(TAG, "audio decoder configuration failed mime=$mime", error)
            reportFailure(error)
            null
        }
    }

    /**
     * Issue #170: some vendor AAC decoders (AutoChips AC822X, Android 4.2.2) accept the stream
     * but emit ~1 PCM frame per output buffer instead of ~1024, so the prebuffer start threshold is
     * never reached, the track never starts, and there is silence with no error anywhere. When the
     * decode yield is pathologically low after several seconds of streaming, swap the platform
     * decoder for the software one, once per renderer.
     */
    private fun maybeFallbackDegenerateDecoder() {
        if (!running) return
        if (format.codec != AudioCodecKind.AAC_LC) return
        if (softwareAacFallbackDone || playbackStarted) return
        if (inputQueued < DEGENERATE_DECODER_MIN_INPUTS) return
        // Less than 1/16th of the nominal yield.
        if (totalWrittenFrames >= inputQueued * 64L) return
        softwareAacFallbackDone = true
        val inputs = inputQueued
        val frames = totalWrittenFrames
        val replacement = createConfiguredDecoder(MediaFormat.MIMETYPE_AUDIO_AAC, forceSoftware = true)
            ?: return
        val old = codec
        codec = replacement
        runCatching { old?.stop() }
        runCatching { old?.release() }
        // Reset decode-side state; the (correctly configured) AudioTrack is untouched, and the
        // queued packets feed the new decoder from the live stream.
        inputQueued = 0
        inputDropped = 0
        outputBuffers = 0
        totalWrittenFrames = 0
        prebufferBytes = 0
        firstPcmLogged = false
        firstPcmWriteReported = false
        firstInputQueuedLogged = false
        lastDecoderOutputMetadata = null
        decoderOutputReports = 0
        report(
            "Audio: platform AAC decoder degenerate (frames=$frames from inputs=$inputs); " +
                "switched to software decoder",
        )
        AppLog.i(TAG, "audio AAC decoder fallback to software after degenerate platform output")
    }

    /**
     * Pre-Q mirror of the video path's software-decoder lookup: MediaCodecList(REGULAR_CODECS)
     * is API 21+, so on older releases scan the legacy list for the well-known Google software
     * decoder. Dalvik-safe: isSoftwareOnly is only touched on API 29+.
     */
    private fun findSoftwareDecoderName(mime: String): String? {
        if (realSdkInt() >= Build.VERSION_CODES.Q) {
            return runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                    !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
                }?.name
            }.getOrNull()
        }
        if (realSdkInt() >= 16) {
            @Suppress("DEPRECATION")
            return runCatching {
                (0 until MediaCodecList.getCodecCount()).mapNotNull { i ->
                    @Suppress("DEPRECATION")
                    MediaCodecList.getCodecInfoAt(i)
                }.firstOrNull {
                    !it.isEncoder && it.name.startsWith("OMX.google.") && mime in it.supportedTypes
                }?.name
            }.getOrNull()
        }
        return null
    }

    private fun createTrack() {
        diagnosticStage = "track-buffer-size"
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            AppLog.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            runCatching { report("Audio: track unavailable api=${Build.VERSION.SDK_INT} stage=$diagnosticStage " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} " +
                "channels=${format.channels} minBufferResult=$minBuffer") }
            return
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel)
        var attributes: Any? = null
        trackAttributes = attributes
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        bytesPerSecond = format.sampleRate * frameBytes
        val built: AudioTrack
        var routeLabel: String
        diagnosticStage = "track-build"
        if (selection.channel == AudioChannel.NAVIGATION) {
            val streamType = if (streamOverride != 0) streamOverride else 10
            routeLabel = "streamType=$streamType"
            @Suppress("DEPRECATION")
            built = AudioTrack(
                streamType, format.sampleRate, channelMask, encoding,
                plan.trackBufferBytes, AudioTrack.MODE_STREAM,
            )
            if (built.state != AudioTrack.STATE_INITIALIZED) {
                AppLog.w(TAG, "navigation streamType=$streamType rejected; not falling back to STREAM_MUSIC")
            }
        } else if (streamOverride == 0) {
            attributes = null
            routeLabel = "usage"
            val preferredStream = streamType()
            built = LegacyAudioFallback.build(
                createLegacy = {
                    @Suppress("DEPRECATION")
                    AudioTrack(preferredStream, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    routeLabel = "usage(fallback=STREAM_MUSIC)"
                    AppLog.w(TAG, "streamType=$preferredStream rejected by this ROM; falling back to STREAM_MUSIC")
                    @Suppress("DEPRECATION")
                    AudioTrack(AudioManager.STREAM_MUSIC, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
            )
        } else {
            val streamType = streamOverride
            routeLabel = "streamType=$streamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    @Suppress("DEPRECATION")
                    AudioTrack(streamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    diagnosticStage = "track-fallback-build"
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    AppLog.w(TAG, "streamType=$streamType rejected by this ROM; falling back to usage-based track")
                    attributes = null
                    val fallbackStream = streamType()
                    LegacyAudioFallback.build(
                        createLegacy = {
                            @Suppress("DEPRECATION")
                            AudioTrack(fallbackStream, format.sampleRate, channelMask, encoding,
                                plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                        },
                        isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                        release = { it.release() },
                        createFallback = {
                            AppLog.w(TAG, "streamType=$fallbackStream rejected by this ROM; falling back to STREAM_MUSIC")
                            @Suppress("DEPRECATION")
                            AudioTrack(AudioManager.STREAM_MUSIC, format.sampleRate, channelMask, encoding,
                                plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                        },
                    )
                },
            )
        }
        track = built
        diagnosticStage = "track-attributes"
        trackAttributes = null
        diagnosticStage = "track-capacity"
        val capacityBytes = plan.trackBufferBytes
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        val trackMetadata = runCatching {
            "api=${Build.VERSION.SDK_INT} trackState=${built.state} trackRate=${built.sampleRate} " +
                "legacyStream=${streamType()} " +
                "attributesSource=configured"
        }.getOrDefault("api=${Build.VERSION.SDK_INT} trackMetadata=unavailable")
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond} " +
            trackMetadata)
        // Issue #88: a near-zero STREAM_MUSIC volume is the most common "packets flow but
        // no sound" cause on head units; surface it loudly while the audio starts.
        audioFocusCoordinator.musicVolumeWarning()?.let { report(it) }
        AppLog.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        AppLog.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} ${AudioRouteLog.describe(selection.channel, selection.contentType, streamType())} " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled) "on" else "off"}",
        )
    }

    /** 0 uses usage-based routing; 1–20 attempt legacy stream types supported by the head unit. */
    private fun channelOverride(channel: AudioChannel): Int = when (channel) {
        // CS75 head unit: its loudspeaker follows the media-channel setting — user found
        // mediaChannel=10 makes audio come out with the head unit showing "NAV" volume, while
        // STREAM_MUSIC itself is silent. So route media through the user's media channel and
        // calls/navigation through their own channel settings (0 = fall back to mapped stream).
        AudioChannel.MEDIA -> mediaChannel
        AudioChannel.PHONE -> callChannel
        AudioChannel.NAVIGATION -> if (navigationChannel != 0) navigationChannel else 10
        // 0 falls through to the selection stream (notification), not silent STREAM_MUSIC.
        AudioChannel.ASSISTANT -> mediaChannel
    }

    private fun mappedSelection(): AudioChannelSelection {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        )
    }

    /**
     * Shares a sink-level focus request across all active non-navigation renderers.
     * Navigation guidance intentionally takes no focus: it overlays media without ducking it.
     */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        val attributes = trackAttributes
        if (channel == AudioChannel.NAVIGATION) {
            AppLog.i(TAG, "audio focus skipped channel=NAVIGATION; overlays without ducking")
            return
        }
        val override = channelOverride(channel)
        val stream = if (override != 0) override else mappedSelection().streamType
        track?.let { audioFocusCoordinator.acquire(it, channel, attributes, stream) }
    }

    private fun abandonAudioFocus() {
        track?.let(audioFocusCoordinator::release)
    }

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        newestRtpSample = packet.sample
        // Hold short Wi-Fi bursts intact. If compressed audio remains >1.5s behind for
        // another 1.5s, skip only stale undecoded packets to catch up to the live song.
        val removed = mediaLagGuard?.recover(queue, packet.sample, AudioPacket::sample, System.nanoTime()) ?: 0
        if (removed > 0) {
            packetsDropped.addAndGet(removed)
            packetsDroppedTotal.addAndGet(removed)
            if (lagRecoveryReports < MAX_REBUFFER_REPORTS) {
                lagRecoveryReports++
                report("Audio: stale media packet backlog recovered audioType=${format.audioType} " +
                    "packetsDiscarded=$removed remaining=${queue.size}")
            }
        }
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> {
                val payloadLen = copyRtpPayload(rtp)
                if (payloadLen > 0) writePcm(byteSwapS16(rtpPayloadScratch, payloadLen), 0, payloadLen)
            }
            AudioCodecKind.AAC_LC -> {
                if (rtp.size > 12) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        AppLog.i(TAG, "audio AAC access unit bytes=${rtp.size - 12}")
                    }
                    feedAac(rtp, 12, rtp.size - 12, timestampUs)
                }
            }
            AudioCodecKind.OPUS -> {
                val payloadLen = copyRtpPayload(rtp)
                if (payloadLen < MIN_OPUS_PACKET_BYTES) {
                    shortOpusPackets++
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        AppLog.i(
                            TAG,
                            "audio Opus skipping short packet bytes=$payloadLen",
                        )
                    }
                    return
                }
                feedCodec(rtpPayloadScratch, timestampUs, 0, payloadLen)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun decodeOpusSoftware(payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset) {
        val decoder = softwareOpusDecoder ?: NativeOpusDecoder.create(format.sampleRate, format.channels).also {
            softwareOpusDecoder = it
            if (it != null) {
                report("Audio: using software Opus decoder (no MediaCodec Opus on this device)")
            }
        }
        if (decoder == null) {
            decoderUnavailablePackets++
            if (decoderUnavailablePackets == 1) {
                report("Audio: Opus software decoder unavailable; OPUS streams will be silent")
            }
            return
        }
        val pcm = decoder.decode(payload, offset, length) ?: run { decoderUnavailablePackets++; return }
        if (pcm.isNotEmpty()) {
            outputBuffers++
            writePcm(pcm, 0, pcm.size)
        }
    }

    private fun feedAac(packet: ByteArray, offset: Int, length: Int, presentationTimeUs: Long) {
        val codec = codec ?: run {
            decoderUnavailablePackets++
            return
        }
        diagnosticStage = "decoder-input"
        val frameLength = length + 7
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainCodec(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                AppLog.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.inputBufferCompat(index) ?: return
        input.clear()
        if (frameLength <= input.remaining()) {
            MediaCodecSupport.putAdtsHeader(input, frameLength, format.sampleRate, format.channels)
            input.put(packet, offset, length)
            codec.queueInputBuffer(index, 0, frameLength, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                AppLog.i(TAG, "audio decoder first input codec=${format.codec} bytes=$frameLength")
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun feedCodec(
        payload: ByteArray,
        presentationTimeUs: Long,
        offset: Int = 0,
        length: Int = payload.size - offset,
    ) {
        val codec = codec ?: run {
            // KitKat has no MediaCodec Opus decoder, so telephony/navigation OPUS streams used to
            // be dropped (silent). Decode them in software with libopus instead.
            if (format.codec == AudioCodecKind.OPUS) {
                decodeOpusSoftware(payload, offset, length)
            } else {
                decoderUnavailablePackets++
            }
            return
        }
        diagnosticStage = "decoder-input"
        // Upstream #381: while a blocking track write holds this thread, decoded output fills
        // up and the decoder stops taking input. Dropping the packet after one 10 ms wait cut
        // ~21 ms of music every 10-20 s; drain the output and keep waiting instead.
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainCodec(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                AppLog.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.inputBufferCompat(index) ?: return
        input.clear()
        if (length <= input.remaining()) {
            input.put(payload, offset, length)
            codec.queueInputBuffer(index, 0, length, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                AppLog.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=$length",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    /** Copies RTP payload (after the 12-byte header) into [rtpPayloadScratch]; returns length. */
    private fun copyRtpPayload(rtp: ByteArray): Int {
        val payloadLen = rtp.size - 12
        if (payloadLen <= 0) return 0
        if (rtpPayloadScratch.size < payloadLen) rtpPayloadScratch = ByteArray(payloadLen)
        System.arraycopy(rtp, 12, rtpPayloadScratch, 0, payloadLen)
        return payloadLen
    }

    private fun drainCodec(codec: MediaCodec) {
        diagnosticStage = "decoder-output"
        val info = outputInfo
        while (running) {
            diagnosticStage = "decoder-output"
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Vendor metadata inspection must not interrupt otherwise working playback.
                    if (decoderOutputReports < 4) runCatching {
                        val outputFormat = codec.outputFormat
                        val metadata = "rate=${outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)} " +
                            "channels=${outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)} " +
                            "pcmEncoding=${outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)}"
                        if (metadata != lastDecoderOutputMetadata) {
                            lastDecoderOutputMetadata = metadata
                            decoderOutputReports++
                            report("Audio: decoded format audioType=${format.audioType} codec=${format.codec} $metadata")
                        }
                    }
                }
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            AppLog.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        @Suppress("DEPRECATION")
                        val output = codec.outputBufferCompat(index)
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        diagnosticStage = "track-write"
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            AppLog.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length",
            )
        }
        voiceFilter?.process(data, offset, length)
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            diagnosticStage = "track-write"
            val count = track.write(data, offset + written, writeLength)
            if (count > 0 && !firstPcmWriteReported) {
                firstPcmWriteReported = true
                report("Audio: first PCM write audioType=${format.audioType} bytes=$count api=${Build.VERSION.SDK_INT}")
                if (realSdkInt() <= 22) {
                    report("Audio: legacy output state " + audioFocusCoordinator.legacyOutputSnapshot(channelOverride(mappedChannel ?: AudioChannel.MEDIA)))
                }
            }
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                break
            }
            if (count < writeLength) partialWritesThisWindow++
            written += count
            val framesWritten = count / frameBytes
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    AppLog.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
            echoReference?.let { reference ->
                val pending = if (playbackStarted) {
                    totalWrittenFrames - (track.playbackHeadPosition.toLong() and 0xffffffffL)
                } else {
                    null
                }
                reference.append(data, offset + written - count, count, pcmChannels, pending, System.nanoTime())
            }
        }
    }

    private var headAtPlaybackStart = 0
    private var droppedAtLastTrim = 0

    private fun trimClock(track: AudioTrack) {
        val trim = clockTrim ?: return
        val now = System.nanoTime()
        val dropped = packetsDroppedTotal.get()
        if (dropped != droppedAtLastTrim) {
            droppedAtLastTrim = dropped
            trim.measureAgain()
        }
        val head = track.playbackHeadPosition
        trim.sample(
            bufferProgress.queuedBytes(head) / frameBytes,
            now,
            headMoving = head != headAtPlaybackStart,
        )
        val rate = trim.rateDue(now) ?: return
        val result = runCatching { track.setPlaybackRate(rate) }.getOrDefault(AudioTrack.ERROR)
        if (result != AudioTrack.SUCCESS) {
            clockTrim = null
            report("Audio: clock trim off audioType=${format.audioType} rate=$rate result=$result")
        }
    }

    private fun startPlayback(track: AudioTrack) {
        diagnosticStage = "track-play"
        underrunsAtPlaybackStart = track.compatUnderruns()
        headAtPlaybackStart = track.playbackHeadPosition
        clockTrim?.measureAgain()
        try {
            track.play()
            playbackStarted = true
        } catch (error: IllegalStateException) {
            AppLog.w(TAG, "AudioTrack play failed state=${track.state}", error)
        }
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(mappedChannel == AudioChannel.MEDIA, playbackStarted,
                track.compatUnderruns() > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition)) {
            // The hardware buffer has actually drained. Pause without flushing or discarding PCM,
            // then use the configured start threshold again when music resumes.
            try {
                track.pause()
            } catch (error: IllegalStateException) {
                AppLog.w(TAG, "AudioTrack pause failed state=${track.state}", error)
            }
            playbackStarted = false
            prebufferBytes = 0
            rebufferCount++
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = track?.compatUnderruns() ?: 0
        val lastRx = lastArrivalNs.get()
        val currentTrack = track
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        val line = "audio stats audioType=${format.audioType} channel=$mappedChannel " +
            "routeType=-1 codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${currentTrack?.sampleRate ?: format.sampleRate} " +
            "trackBufferFrames=-1 " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount " +
            "playbackRate=${clockTrim?.rate ?: -1} ended=$force"
        AppLog.i(STATS_TAG, line)
        report(line)
        if (format.codec != AudioCodecKind.LPCM) {
            // Keep this separate: the exported diagnostic recorder caps each line at 700 characters.
            val decoderLine = "Audio: decoder stats audioType=${format.audioType} codec=${format.codec} " +
                "inputQueuedTotal=$inputQueued inputDroppedTotal=$inputDropped " +
                "shortOpusPacketsTotal=$shortOpusPackets decoderUnavailablePacketsTotal=$decoderUnavailablePackets " +
                "outputBuffersTotal=$outputBuffers ended=$force"
            AppLog.i(STATS_TAG, decoderLine)
            runCatching { report(decoderLine) }
        }
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        writtenFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
        // Runs on the worker thread once per stats window; see Issue #170.
        diagnosticStage = "decoder-health"
        maybeFallbackDegenerateDecoder()
    }

    private fun reportFailure(error: Throwable) {
        runCatching { report("Audio: renderer failed api=${Build.VERSION.SDK_INT} " +
            "audioType=${format.audioType} codec=${format.codec} stage=$diagnosticStage " +
            MediaFailureSummary.describe(error)) }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray, length: Int = source.size): ByteArray {
        val limit = length.coerceIn(0, source.size)
        for (index in 0 until limit - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        abandonAudioFocus()
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (t: Exception) {
                // Best effort.
                AppLog.w(TAG, "audio codec stop failed", t)
            }
            try {
                codec.release()
            } catch (t: Exception) {
                // Best effort.
                AppLog.w(TAG, "audio codec release failed", t)
            }
        }
        val soft = softwareOpusDecoder
        softwareOpusDecoder = null
        soft?.close()
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (t: Exception) {                // Best effort.
                AppLog.w(TAG, "audio track pause failed", t)
            }
            try {
                track.flush()
            } catch (t: Exception) {
                // Best effort.
                AppLog.w(TAG, "audio track flush failed", t)
            }
            try {
                track.release()
            } catch (t: Exception) {
                // Best effort.
                AppLog.w(TAG, "audio track release failed", t)
            }
        }
    }

    private fun AudioTrack.compatUnderruns(): Int = 0

    private companion object {
        const val TAG = "xcertplay-usb"
        // AudioManager.STREAM_ACCESSIBILITY was added in API 26 (value 10). Keep compatibility for API < 26.
        const val STREAM_ACCESSIBILITY_COMPAT = 10
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val MAX_REBUFFER_REPORTS = 3
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        // Issue #170: inputs before the degenerate-decoder check may fire (~5 s of AAC-LC).
        const val DEGENERATE_DECODER_MIN_INPUTS = 240
    }
}

