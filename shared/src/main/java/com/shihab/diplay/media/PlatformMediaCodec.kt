package com.shihab.diplay.media

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface

/** High-API codec helpers. Callers load this class by name only after the real SDK allows it. */
internal object PlatformMediaCodec {
    @JvmStatic
    fun decoderName(codec: MediaCodec): String = codec.name

    @JvmStatic
    fun setRealtimePriority(format: MediaFormat) {
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
    }

    @JvmStatic
    fun enableLowLatency(codec: MediaCodec, format: MediaFormat, mime: String) {
        if (codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
    }

    @JvmStatic
    fun softwareDecoderName(mime: String): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
        }?.name

    @JvmStatic
    fun setOutputSurface(codec: MediaCodec, surface: Surface) {
        codec.setOutputSurface(surface)
    }
}

internal fun platformMediaCodec(method: String, types: Array<out Class<*>>, vararg args: Any?): Any? =
    Class.forName("com.shihab.diplay.media.PlatformMediaCodec")
        .getMethod(method, *Array(types.size) { types[it] })
        .invoke(null, *args)
