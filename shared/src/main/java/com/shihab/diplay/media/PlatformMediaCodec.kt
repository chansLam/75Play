package com.shihab.diplay.media

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface

/** Optional platform codec features accessed without linking API 20+ methods or constants. */
internal object PlatformMediaCodec {
    @JvmStatic
    fun decoderName(codec: MediaCodec): String =
        codec.javaClass.getMethod("getName").invoke(codec) as String

    @JvmStatic
    fun setRealtimePriority(format: MediaFormat) {
        format.setInteger("priority", 0)
    }

    @JvmStatic
    fun enableLowLatency(codec: MediaCodec, format: MediaFormat, mime: String) {
        val codecInfo = codec.javaClass.getMethod("getCodecInfo").invoke(codec)
        val capabilities = codecInfo.javaClass
            .getMethod("getCapabilitiesForType", String::class.java)
            .invoke(codecInfo, mime)
        val supported = capabilities.javaClass
            .getMethod("isFeatureSupported", String::class.java)
            .invoke(capabilities, "low-latency") as Boolean
        if (supported) {
            format.setInteger("low-latency", 1)
        }
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun softwareDecoderName(mime: String): String? =
        (0 until MediaCodecList.getCodecCount()).firstNotNullOfOrNull { index ->
            val info = MediaCodecList.getCodecInfoAt(index)
            info.name.takeIf {
                !info.isEncoder && it.startsWith("OMX.google.") && mime in info.supportedTypes
            }
        }

    @JvmStatic
    fun setOutputSurface(codec: MediaCodec, surface: Surface) {
        codec.javaClass.getMethod("setOutputSurface", Surface::class.java).invoke(codec, surface)
    }

    @JvmStatic
    fun supportsOutputSurface(codec: MediaCodec): Boolean = runCatching {
        codec.javaClass.getMethod("setOutputSurface", Surface::class.java)
    }.isSuccess
}

internal fun platformMediaCodec(method: String, types: Array<out Class<*>>, vararg args: Any?): Any? =
    Class.forName("com.shihab.diplay.media.PlatformMediaCodec")
        .getMethod(method, *Array(types.size) { types[it] })
        .invoke(null, *args)
