package com.shihab.diplay.media

import com.shihab.diplay.compat.realSdkInt

/** Route line for audio startup. KitKat logs the legacy stream and does not load API 21 types. */
internal object AudioRouteLog {
    fun describe(channel: AudioChannel, contentType: AudioContentType, stream: Int): String {
        if (realSdkInt() < 21) return "stream=$stream"
        return runCatching {
            Class.forName("com.shihab.diplay.media.AudioAttributeLabels")
                .getMethod("describe", AudioChannel::class.java, AudioContentType::class.java)
                .invoke(null, channel, contentType) as String
        }.getOrElse { "stream=$stream" }
    }
}
