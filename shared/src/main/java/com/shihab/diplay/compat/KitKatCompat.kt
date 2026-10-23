package com.shihab.diplay.compat

import java.util.concurrent.TimeUnit

/**
 * KitKat compatibility shims for platform methods introduced after API 19, ported from
 * changan-upstream 5805824 (lint NewApi sweep). Only the helpers this tree actually calls are
 * defined here; other KitKat guards use [realSdkInt] so a spoofed SDK_INT stays on the KitKat path.
 */

/**
 * [Process.waitFor(long, TimeUnit)] is API 26. Desugar may not rewrite it on all KitKat builds,
 * so poll [Process.exitValue] with a deadline instead.
 * @return true if the process exited before the timeout, false if still alive (caller should destroy).
 */
fun Process.waitForMillis(timeoutMs: Long): Boolean {
    require(timeoutMs >= 0L) { "timeoutMs must be non-negative" }
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (true) {
        try {
            exitValue()
            return true
        } catch (_: IllegalThreadStateException) {
            val remainingNs = deadline - System.nanoTime()
            if (remainingNs <= 0L) return false
            val sleepMs = minOf(50L, TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceAtLeast(1L))
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }
}
object Base64Compat {
    /** java.util.Base64.getEncoder() is API 26; android.util.Base64 has existed since API 1. */
    fun encodeToString(data: ByteArray): String = try {
        android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
    } catch (_: RuntimeException) {
        // Plain JVM unit tests do not mock android.util.Base64.
        java.util.Base64.getEncoder().encodeToString(data)
    }

    /** MIME encoder with a custom line length (java.util.Base64.getMimeEncoder(width, newline)). */
    fun encodeMimeToString64(data: ByteArray, lineLength: Int = 64): String {
        val flat = encodeToString(data)
        if (lineLength <= 0) return flat
        return flat.chunked(lineLength).joinToString("\n")
    }

    /** MIME decoding (lenient), which also accepts the forms iPhones send. */
    fun decode(text: String): ByteArray = try {
        android.util.Base64.decode(text, android.util.Base64.DEFAULT)
    } catch (_: RuntimeException) {
        java.util.Base64.getDecoder().decode(text)
    }
}

/** MediaCodec#getInputBuffer(int) is API 21; the deprecated buffers array covers KitKat. */
fun android.media.MediaCodec.inputBufferCompat(index: Int): java.nio.ByteBuffer? =
    if (realSdkAtLeast(21)) getInputBuffer(index)
    else @Suppress("DEPRECATION") inputBuffers?.get(index)

/** MediaCodec#getOutputBuffer(int) is API 21; the deprecated buffers array covers KitKat. */
fun android.media.MediaCodec.outputBufferCompat(index: Int): java.nio.ByteBuffer? =
    if (realSdkAtLeast(21)) getOutputBuffer(index)
    else @Suppress("DEPRECATION") outputBuffers?.get(index)
