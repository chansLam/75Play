package com.shihab.diplay.media

import android.media.AudioAttributes

/**
 * API 21+ audio usage labels. This class is loaded only after [com.shihab.diplay.compat.realSdkInt]
 * is at least 21, so Dalvik on KitKat never verifies [AudioAttributes].
 */
internal object AudioAttributeLabels {
    @JvmStatic
    fun describe(channel: AudioChannel, contentType: AudioContentType): String =
        "usage=${usage(channel)} contentType=${content(contentType)}"

    private fun usage(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun content(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }
}
