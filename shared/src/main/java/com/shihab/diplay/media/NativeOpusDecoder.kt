package com.shihab.diplay.media

import java.io.Closeable
import com.shihab.diplay.util.AppLog

/**
 * NDK libopus software decoder fallback for legacy head units (KitKat / API 19) that have no
 * MediaCodec Opus decoder. iOS sends telephony and navigation downlinks as OPUS regardless of
 * the negotiated format; without this fallback those streams are dropped and calls/navigation
 * are silent. Compiled into the same opus_microphone shared library as the encoder.
 */
internal class NativeOpusDecoder private constructor(
    private var handle: Long,
    private val channels: Int,
) : Closeable {
    val available: Boolean get() = handle != 0L

    @Synchronized fun decode(packet: ByteArray, offset: Int = 0, length: Int = packet.size - offset): ByteArray? {
        if (!available) return null
        return runCatching { nativeDecode(handle, packet, offset, length) }
            .onFailure { AppLog.w(TAG, "Native Opus decode failed", it) }
            .getOrNull()
    }

    @Synchronized override fun close() {
        val old = handle
        handle = 0L
        if (old != 0L) nativeDestroy(old)
    }

    companion object {
        private const val TAG = "xcertplay-opus"
        private val loaded: Boolean by lazy {
            runCatching { System.loadLibrary("opus_microphone"); true }
                .onFailure { AppLog.w(TAG, "Software Opus library unavailable", it) }
                .getOrDefault(false)
        }

        fun isAvailable(): Boolean = create(48_000, 1)?.use { it.available } == true

        fun create(sampleRate: Int, channels: Int): NativeOpusDecoder? {
            if (!loaded) return null
            val handle = runCatching { nativeCreate(sampleRate, channels) }
                .onFailure { AppLog.w(TAG, "Software Opus decoder init failed", it) }
                .getOrDefault(0L)
            return if (handle != 0L) NativeOpusDecoder(handle, channels) else null
        }

        @JvmStatic private external fun nativeCreate(sampleRate: Int, channels: Int): Long
        @JvmStatic private external fun nativeDecode(
            handle: Long,
            packet: ByteArray,
            offset: Int,
            length: Int,
        ): ByteArray?
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
