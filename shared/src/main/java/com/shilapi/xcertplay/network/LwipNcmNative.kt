package com.shilapi.xcertplay.network

/**
 * JNI bindings for userspace lwIP NCM relay.
 */
class LwipNcmNative(
    private val onTxFrameCallback: (ByteArray) -> Unit,
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
    fun onTxFrame(frame: ByteArray) {
        onTxFrameCallback(frame)
    }

    private external fun nativeCreate(hostMac: ByteArray, linkLocal: String): Long
    private external fun nativeInputFrame(handle: Long, frame: ByteArray, length: Int)
    private external fun nativeRegisterPort(handle: Long, port: Int, isTcp: Boolean)
    private external fun nativeGetSnapshot(handle: Long): String
    private external fun nativeDestroy(handle: Long)
}
