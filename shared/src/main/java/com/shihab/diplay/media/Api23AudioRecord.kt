package com.shihab.diplay.media

import android.media.AudioFormat
import android.media.AudioRecord
import java.lang.reflect.InvocationTargetException

/** Optional builder path implemented with reflection so this class links on API 19. */
internal object Api23AudioRecord {
    @JvmStatic
    fun create(source: Int, sampleRate: Int, channelMask: Int, bufferSize: Int): AudioRecord? {
        return try {
            val formatBuilderClass = Class.forName("android.media.AudioFormat\$Builder")
            var formatBuilder = formatBuilderClass.getConstructor().newInstance()
            formatBuilder = formatBuilderClass.getMethod(
                "setEncoding",
                Int::class.javaPrimitiveType,
            ).invoke(formatBuilder, AudioFormat.ENCODING_PCM_16BIT)
            formatBuilder = formatBuilderClass.getMethod(
                "setSampleRate",
                Int::class.javaPrimitiveType,
            ).invoke(formatBuilder, sampleRate)
            formatBuilder = formatBuilderClass.getMethod(
                "setChannelMask",
                Int::class.javaPrimitiveType,
            ).invoke(formatBuilder, channelMask)
            val format = formatBuilderClass.getMethod("build").invoke(formatBuilder) as AudioFormat

            val recordBuilderClass = Class.forName("android.media.AudioRecord\$Builder")
            var recordBuilder = recordBuilderClass.getConstructor().newInstance()
            recordBuilder = recordBuilderClass.getMethod(
                "setAudioSource",
                Int::class.javaPrimitiveType,
            ).invoke(recordBuilder, source)
            recordBuilder = recordBuilderClass.getMethod(
                "setAudioFormat",
                AudioFormat::class.java,
            ).invoke(recordBuilder, format)
            recordBuilder = recordBuilderClass.getMethod(
                "setBufferSizeInBytes",
                Int::class.javaPrimitiveType,
            ).invoke(recordBuilder, bufferSize)
            recordBuilderClass.getMethod("build").invoke(recordBuilder) as AudioRecord
        } catch (error: InvocationTargetException) {
            throw error.cause ?: error
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
}
