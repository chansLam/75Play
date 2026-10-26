package com.shihab.diplay

import android.app.Activity

/**
 * Points the focused activity's volume keys at the navigation stream while a prompt is
 * playing, and back at the media stream when it ends. Music and navigation stay on
 * different AudioTrack streams; this only chooses which one the knob edits.
 */
internal object CarPlayVolumeTarget {
    @Volatile private var navigationStream: Int? = null
    @Volatile private var activity: Activity? = null

    fun navigation(stream: Int?) {
        navigationStream = stream
        val current = activity ?: return
        current.runOnUiThread { apply(current) }
    }

    fun attach(host: Activity) {
        activity = host
        host.runOnUiThread { apply(host) }
    }

    fun detach(host: Activity) {
        if (activity === host) activity = null
    }

    private fun apply(host: Activity) {
        if (activity !== host) return
        val media = AirPlayPersistence.loadMediaAudioChannel(host).let { if (it != 0) it else 5 }
        host.volumeControlStream = navigationStream ?: media
    }
}
