package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbEndpoint

/**
 * Synchronous blocking NCM data plane over USBDEVFS_BULK.
 *
 * The asynchronous URB ring ([UsbfsNcmIo]) submits eight bulk-OUT URBs; on the i.MX6 KitKat
 * kernel those URBs are accepted but never complete (submitted>0, completed=0) while the phone
 * keeps NAKing. This class drives the same bulk endpoints with one blocking transfer at a time —
 * the path LIVI uses successfully — so each write returns the real byte count or a negative
 * errno instead of hanging in the ring. A long NTB is split into [SYNC_CHUNK_MAX_BYTES] chunks;
 * only the final chunk of a transfer may be short.
 *
 * A latched stall (-EPIPE) is handed to [recoverStall] once per chunk; when recovery reports
 * success the chunk is retried. Recovery never runs on the healthy path.
 */
internal class SyncUsbfsNcmIo(
    private val connection: SharedUsbConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val onDiagnostic: (String) -> Unit,
    private val recoverStall: () -> Boolean,
) : NcmIo {

    override fun submitOut(block: ByteArray, timeoutMillis: Int): NcmSubmit {
        var offset = 0
        var submittedChunks = 0
        var completedChunks = 0
        var successBytes = 0
        val startedNanos = System.nanoTime()
        while (offset < block.size) {
            val count = minOf(SYNC_CHUNK_MAX_BYTES, block.size - offset)
            val chunk = block.copyOfRange(offset, offset + count)
            var result = writeBulk(chunk, count, timeoutMillis)
            if (result == -EPIPE && WiredNcmTuning.recoverSyncStall && recoverStall()) {
                onDiagnostic(
                    "ncm sync bulk-out EPIPE recovered; retrying chunk offset=$offset count=$count",
                )
                result = writeBulk(chunk, count, timeoutMillis)
            }
            if (result < 0) {
                return NcmSubmit.Failed(
                    transferred = successBytes,
                    errno = if (result < -1) -result else 0,
                    elapsedNanos = System.nanoTime() - startedNanos,
                    submittedChunks = submittedChunks,
                    completedChunks = completedChunks,
                    successBytes = successBytes,
                )
            }
            submittedChunks++
            completedChunks++
            successBytes += result
            offset += result
        }
        return NcmSubmit.Accepted(
            bytes = successBytes,
            completedNow = true,
            submittedChunks = submittedChunks,
            completedChunks = completedChunks,
            successBytes = successBytes,
        )
    }

    override fun receive(timeoutMillis: Int, maxBytes: Int): ByteArray? {
        val size = maxBytes.coerceAtMost(SYNC_CHUNK_MAX_BYTES)
        val buffer = ByteArray(size)
        val transferred = readBulk(buffer, size, timeoutMillis)
        return if (transferred > 0) buffer.copyOf(transferred) else null
    }

    override fun availableOutSlots(): Int = 1

    override fun diagnostics(): String = "sync-usbfs"

    override fun abort() = Unit

    override fun close() = Unit

    private fun writeBulk(chunk: ByteArray, count: Int, timeoutMillis: Int): Int {
        val raw = LegacyUsbHostCompat.rawBulkTransfer(
            connection.fileDescriptor, outEndpoint.address, chunk, count, timeoutMillis,
        )
        if (raw != null) return raw
        // Native helper unavailable on this build; fall back to the framework bulk transfer.
        val written = connection.bulkTransfer(outEndpoint, chunk, count, timeoutMillis)
        return if (written >= 0) written else -1
    }

    private fun readBulk(buffer: ByteArray, count: Int, timeoutMillis: Int): Int {
        val raw = LegacyUsbHostCompat.rawBulkTransfer(
            connection.fileDescriptor, inEndpoint.address, buffer, count, timeoutMillis,
        )
        if (raw != null) return raw
        return connection.bulkTransfer(inEndpoint, buffer, count, timeoutMillis)
    }

    private companion object {
        /** KitKat usbfs rejects a single bulk transfer longer than 16384 bytes. */
        const val SYNC_CHUNK_MAX_BYTES = 16_384
        const val EPIPE = 32
    }
}
