package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbEndpoint
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asynchronous CDC-NCM bulk pipe backed by the already-authorized usbfs descriptor.
 *
 * usbfs owns the buffers while URBs are in flight, so no Java array is pinned across a USB wait.
 * This is deliberately NCM-only: USBMUX continues using the framework connection sharing the fd.
 */
internal class UsbfsNcmIo private constructor(
    private var handle: Long,
) : NcmIo {
    private val closed = AtomicBoolean(false)

    override fun submitOut(block: ByteArray, timeoutMillis: Int): NcmSubmit {
        if (closed.get() || timeoutMillis <= 0) return NcmSubmit.Backpressured
        return if (nativeSubmitOut(handle, block)) {
            NcmSubmit.Accepted(
                bytes = block.size,
                completedNow = false,
                submittedChunks = 1,
                completedChunks = 0,
                successBytes = 0,
            )
        } else {
            NcmSubmit.Backpressured
        }
    }

    /**
     * Builds an NTB16 directly in the URB-owned buffer. This removes the transient managed NTB
     * allocation and its second JNI copy from the wired lwIP transmit path.
     */
    fun submitFrames(frames: List<ByteArray>, sequence: Int, limits: NcmNtbLimits): NcmSubmit {
        if (closed.get() || frames.isEmpty()) return NcmSubmit.Backpressured
        val bytes = nativeSubmitFrames(
            handle,
            frames.toTypedArray(),
            sequence,
            limits.maxBlockBytes,
            limits.alignment,
        )
        return if (bytes > 0) {
            NcmSubmit.Accepted(
                bytes = bytes,
                completedNow = false,
                submittedChunks = 1,
                completedChunks = 0,
                successBytes = 0,
            )
        } else {
            NcmSubmit.Backpressured
        }
    }

    override fun receive(timeoutMillis: Int, maxBytes: Int): ByteArray? {
        if (closed.get()) return null
        val target = ByteArray(maxBytes)
        val read = nativeReceive(handle, target, timeoutMillis)
        return if (read > 0) target.copyOf(read) else null
    }

    /**
     * Reassembles and validates NTB16 in native storage, returning only complete Ethernet frames.
     * Java therefore keeps the existing NDP/mDNS policy without first receiving raw USB chunks.
     */
    fun receiveFrames(timeoutMillis: Int): Array<ByteArray>? =
        if (closed.get()) null else nativeReceiveFrames(handle, timeoutMillis)

    /** Frames injected straight into lwIP since the previous call. */
    fun takeDirectFrames(): Int = if (closed.get()) 0 else nativeTakeDirectFrames(handle)

    override fun availableOutSlots(): Int =
        if (closed.get()) 0 else nativeAvailableOutSlots(handle)

    override fun diagnostics(): String =
        if (closed.get()) "closed" else nativeDiagnostics(handle)

    override fun abort() {
        if (!closed.get()) nativeAbort(handle)
    }

    internal fun nativeHandle(): Long = handle

    internal fun bindLwip(lwipHandle: Long): Boolean =
        !closed.get() && lwipHandle != 0L && nativeBindLwip(handle, lwipHandle)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        nativeClose(handle)
        handle = 0L
    }

    companion object {
        private const val URB_DEPTH = 8
        private const val READ_BUFFER_BYTES = 32 * 1024

        @Volatile var lastOpenFailure: String = "not attempted"
            private set

        fun open(connection: SharedUsbConnection, inEndpoint: UsbEndpoint, outEndpoint: UsbEndpoint): UsbfsNcmIo? {
            val fd = runCatching { connection.fileDescriptor }.getOrElse { error ->
                lastOpenFailure = "fd unavailable ${error.javaClass.simpleName}"
                -1
            }
            if (fd < 0) {
                if (lastOpenFailure == "not attempted") lastOpenFailure = "fd=$fd"
                return null
            }
            val opened = runCatching {
                nativeOpen(fd, inEndpoint.address, outEndpoint.address, READ_BUFFER_BYTES, URB_DEPTH)
            }
            val handle = opened.getOrDefault(0L)
            if (handle == 0L) {
                val errno = runCatching { nativeLastOpenErrno() }.getOrDefault(-1)
                val error = opened.exceptionOrNull()
                lastOpenFailure = "fd=$fd errno=$errno" +
                    (error?.let { " ${it.javaClass.simpleName}:${it.message}" } ?: "")
                return null
            }
            lastOpenFailure = ""
            return UsbfsNcmIo(handle)
        }

        init {
            runCatching { System.loadLibrary("usbfs_ncm_io") }
        }

        @JvmStatic private external fun nativeOpen(
            fileDescriptor: Int,
            inEndpointAddress: Int,
            outEndpointAddress: Int,
            readBufferBytes: Int,
            urbDepth: Int,
        ): Long

        @JvmStatic private external fun nativeLastOpenErrno(): Int

        @JvmStatic private external fun nativeSubmitOut(handle: Long, block: ByteArray): Boolean
        @JvmStatic private external fun nativeSubmitFrames(
            handle: Long,
            frames: Array<ByteArray>,
            sequence: Int,
            maxBlockBytes: Int,
            alignment: Int,
        ): Int
        @JvmStatic private external fun nativeReceiveFrames(
            handle: Long,
            timeoutMillis: Int,
        ): Array<ByteArray>?
        @JvmStatic private external fun nativeBindLwip(handle: Long, lwipHandle: Long): Boolean
        @JvmStatic private external fun nativeTakeDirectFrames(handle: Long): Int
        @JvmStatic private external fun nativeReceive(handle: Long, target: ByteArray, timeoutMillis: Int): Int
        @JvmStatic private external fun nativeAvailableOutSlots(handle: Long): Int
        @JvmStatic private external fun nativeDiagnostics(handle: Long): String
        @JvmStatic private external fun nativeAbort(handle: Long)
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
