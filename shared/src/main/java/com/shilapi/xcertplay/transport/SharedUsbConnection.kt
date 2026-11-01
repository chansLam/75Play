package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer

/**
 * The USB verbs the iAP2 session and the NCM bridge need from a device connection,
 * abstracted so the wired stack can run on either transport:
 *
 *  - the framework [UsbDeviceConnection] (today's default, via [SharedUsbDeviceConnection]);
 *  - a raw usbfs fd opened directly (issue #266 direct mode, via [RawSharedUsbConnection])
 *    for ROMs whose UsbManager never lists the iPhone.
 *
 * Reference counting mirrors the original shared-connection contract: USBMUX retains
 * one reference, NCM retains another, and the underlying transport closes only when
 * the last reference is released.
 *
 * Async [UsbRequest] verbs are framework-only: a raw connection reports
 * [supportsAsyncRequests] = false and its consumers must use the synchronous paths
 * (the same paths legacy Android uses below API 24).
 */
internal interface SharedUsbConnection {
    /** The underlying usbfs file descriptor, or -1 when unavailable. */
    val fileDescriptor: Int

    /** The framework connection when this is the framework transport; null when raw. */
    val frameworkConnection: UsbDeviceConnection?

    /** False on raw connections: callers must use synchronous bulk reads. */
    val supportsAsyncRequests: Boolean

    fun controlTransfer(
        requestType: Int, request: Int, value: Int, index: Int,
        buffer: ByteArray?, length: Int, timeoutMillis: Int,
    ): Int

    fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeoutMillis: Int): Int

    fun bulkTransfer(
        endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int,
    ): Int

    /**
     * The same bulk transfer issued through the raw usbfs ioctl, returning the byte count or a
     * negative errno.
     *
     * The framework reports every failed bulk transfer as a bare -1, which makes a NAK from the
     * accessory (ETIMEDOUT) indistinguishable from a stale endpoint (ENODEV) and from a latched
     * stall (EPIPE) — three faults with completely different fixes. Only call this when the
     * transfer is known not to have reached the wire: re-issuing a transfer that timed out could
     * put a second copy of the same data on the bus.
     */
    fun bulkTransferErrno(
        endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int,
    ): Int = bulkTransfer(endpoint, buffer, offset, length, timeoutMillis)

    fun claimInterface(usbInterface: UsbInterface, force: Boolean): Boolean
    fun releaseInterface(usbInterface: UsbInterface): Boolean
    fun setInterface(usbInterface: UsbInterface): Boolean

    fun queue(request: UsbRequest, buffer: ByteBuffer): Boolean
    fun await(request: UsbRequest, timeoutMillis: Long): UsbRequest
    fun forget(request: UsbRequest)

    fun retain(): SharedUsbConnection
    fun release()
}
