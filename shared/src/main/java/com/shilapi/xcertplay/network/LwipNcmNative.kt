package com.shilapi.xcertplay.network

/**
 * JNI bindings for userspace lwIP NCM relay.
 */
class LwipNcmNative(
    private val onTxFrameCallback: (ByteArray) -> Boolean,
) {
    private var nativeHandle: Long = 0

    init {
        try {
            System.loadLibrary("lwip_ncm_relay")
        } catch (_: UnsatisfiedLinkError) {
            // Expected in host-side unit tests without NDK shared library loaded
        }
    }

    @Synchronized
    fun create(hostMac: ByteArray, linkLocal: String): Boolean {
        if (nativeHandle != 0L) return true
        return try {
            nativeHandle = nativeCreate(hostMac, linkLocal)
            nativeHandle != 0L
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    @Synchronized
    fun inputFrame(frame: ByteArray, length: Int = frame.size) {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeInputFrame(handle, frame, length)
        }
    }

    /**
     * Binds lwIP's native transmit ring to an already-open usbfs context. Control frames can still
     * use the Java queue; both paths share that context's URB lock and NTB sequence.
     */
    internal fun lwipHandle(): Long = nativeHandle

    @Synchronized
    internal fun bindUsbfs(ioHandle: Long, limits: com.shilapi.xcertplay.transport.NcmNtbLimits): Boolean {
        val handle = nativeHandle
        if (handle == 0L || ioHandle == 0L) return false
        return try {
            nativeBindUsbfs(handle, ioHandle, limits.maxBlockBytes, limits.alignment, limits.maxDatagrams)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    @Synchronized
    fun registerPort(port: Int, isTcp: Boolean) {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeRegisterPort(handle, port, isTcp)
        }
    }

    @Synchronized
    fun destroy() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            nativeDestroy(handle)
        }
    }

    @Synchronized
    fun getSnapshot(): String {
        val handle = nativeHandle
        if (handle == 0L) return "lwip uninitialized"
        return try {
            nativeGetSnapshot(handle)
        } catch (_: UnsatisfiedLinkError) {
            "lwip native unavailable"
        }
    }

    /** Called under the native core lock; must not acquire the native-handle monitor. */
    fun onTxFrame(frame: ByteArray): Boolean = onTxFrameCallback(frame)

    private external fun nativeCreate(hostMac: ByteArray, linkLocal: String): Long
    private external fun nativeBindUsbfs(
        handle: Long,
        ioHandle: Long,
        maxBlockBytes: Int,
        alignment: Int,
        maxDatagrams: Int,
    ): Boolean
    private external fun nativeInputFrame(handle: Long, frame: ByteArray, length: Int)
    private external fun nativeRegisterPort(handle: Long, port: Int, isTcp: Boolean)
    private external fun nativeGetSnapshot(handle: Long): String
    private external fun nativeDestroy(handle: Long)
}
