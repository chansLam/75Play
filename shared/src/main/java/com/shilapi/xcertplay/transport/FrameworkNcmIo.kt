package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbEndpoint
import com.shilapi.xcertplay.compat.realSdkInt

/**
 * Diagnostic fallback that uses the framework bulk transfer. API 19 prefers [UsbfsNcmIo] because
 * this path can block inside a JNI critical section for the whole URB timeout.
 */
internal class FrameworkNcmIo(
    private val connection: SharedUsbConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
) : NcmIo {
    override fun submitOut(block: ByteArray, timeoutMillis: Int): NcmSubmit {
        var failedAfterNanos = 0L
        var errno = 0
        var submittedChunks = 0
        var completedChunks = 0
        var successBytes = 0
        val transferred = writeUsbChunks(block.size, realSdkInt(), timeoutMillis) { offset, count, timeout ->
            val started = System.nanoTime()
            submittedChunks++
            val written = connection.bulkTransfer(outEndpoint, block, offset, count, timeout)
            if (written > 0) {
                completedChunks++
                successBytes += written
                return@writeUsbChunks written
            }
            failedAfterNanos = System.nanoTime() - started
            if (failedAfterNanos < timeout.toLong() * SLOW_FAILURE_NANOS_PER_MILLIS) {
                val probed = connection.bulkTransferErrno(outEndpoint, block, offset, count, timeout)
                if (probed > 0) {
                    completedChunks++
                    successBytes += probed
                    return@writeUsbChunks probed
                }
                errno = if (probed < -1) -probed else 0
            }
            written
        }
        return if (transferred == block.size) {
            NcmSubmit.Accepted(transferred, completedNow = true, submittedChunks, completedChunks, successBytes)
        } else {
            NcmSubmit.Failed(transferred, errno, failedAfterNanos, submittedChunks, completedChunks, successBytes)
        }
    }

    override fun receive(timeoutMillis: Int, maxBytes: Int): ByteArray? {
        val buffer = ByteArray(maxBytes)
        val transferred = connection.bulkTransfer(inEndpoint, buffer, buffer.size, timeoutMillis)
        return if (transferred > 0) buffer.copyOf(transferred) else null
    }

    override fun availableOutSlots(): Int = 1

    override fun diagnostics(): String = "framework"

    override fun abort() = Unit

    override fun close() = Unit

    private companion object {
        const val SLOW_FAILURE_NANOS_PER_MILLIS = 900_000L
    }
}
