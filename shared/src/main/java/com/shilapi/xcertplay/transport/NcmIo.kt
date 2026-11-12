package com.shilapi.xcertplay.transport

import java.io.Closeable

/** Session data plane for one claimed NCM bulk IN/OUT pair. */
internal interface NcmIo : Closeable {
    fun submitOut(block: ByteArray, timeoutMillis: Int): NcmSubmit
    fun receive(timeoutMillis: Int, maxBytes: Int): ByteArray?
    fun abort()
}

internal sealed class NcmSubmit {
    class Accepted(
        val bytes: Int,
        val completedNow: Boolean,
        val submittedChunks: Int,
        val completedChunks: Int,
        val successBytes: Int,
    ) : NcmSubmit()

    class Failed(
        val transferred: Int,
        val errno: Int,
        val elapsedNanos: Long,
        val submittedChunks: Int,
        val completedChunks: Int,
        val successBytes: Int,
    ) : NcmSubmit()
}
