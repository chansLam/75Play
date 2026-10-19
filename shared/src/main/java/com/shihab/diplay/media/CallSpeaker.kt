package com.shihab.diplay.media

import android.media.AudioManager

/**
 * Puts a call in communication mode on the built-in loudspeaker. A phone or tablet used as the head unit plays
 * that mode on its earpiece and records it with the handset microphone, so a CarPlay call was heard only close
 * to the screen (#701). This CS75 Plus 2020 runs Android 4.4.2, so it always uses the speakerphone flag rather
 * than the Android 12+ communication-device API the upstream also uses.
 */
internal class CallSpeaker private constructor(private val undo: () -> Unit) {
    fun restore() {
        runCatching(undo)
    }

    companion object {
        /** Null when this device refused the speakerphone; the call keeps its route. */
        fun on(manager: AudioManager): CallSpeaker? = runCatching {
            @Suppress("DEPRECATION")
            val before = manager.isSpeakerphoneOn
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = true
            @Suppress("DEPRECATION")
            CallSpeaker { manager.isSpeakerphoneOn = before }
        }.getOrNull()
    }
}
