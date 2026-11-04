package com.shihab.diplay.media

import android.media.AudioFormat
import android.media.AudioRecord

/** Loaded by name only when the real SDK is at least 23. */
internal object Api23AudioRecord {
    @JvmStatic
    fun create(source: Int, sampleRate: Int, channelMask: Int, bufferSize: Int): AudioRecord =
        AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
}
