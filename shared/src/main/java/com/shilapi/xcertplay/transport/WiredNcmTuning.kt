package com.shilapi.xcertplay.transport

/**
 * A/B knobs for the wired CDC NCM bring-up on legacy (API 19 / Android 4.4) head units.
 *
 * The transport layer has no [android.content.Context] and DiPlayPreferences is common-module
 * internal, so these are plain mutable defaults rather than settings keys. The organizer can bind
 * them to a preferences screen later without changing this class. Every default preserves or
 * matches the spec-correct behavior; flip a knob only during a real-car A/B test and revert after
 * each run so the log shows which value was active.
 */
object WiredNcmTuning {
    /**
     * Send CDC NCM SET_NTB_FORMAT (class request 0x84, value 0 = NTB16) on the control interface
     * while the data interface is still at alt=0, before selecting alt=1.
     *
     * The kernel cdc_ncm driver always negotiates explicitly; a reconnect without a bus reset can
     * otherwise leave the device on a format the host does not encode. Our blocks are NTB16, so
     * value=0 always matches what we send. Disable only if the phone rejects the control request.
     */
    var negotiateNtbFormat: Boolean = true

    /** Send CDC NCM SET_CRC_MODE (0x8a, value 0 = no CRC appended) after GET_NTB_PARAMETERS. */
    var negotiateCrcMode: Boolean = true

    /**
     * Send CDC NCM SET_NTB_INPUT_SIZE (class request 0x1d) declaring the host's receive-side NTB
     * size, between SET_NTB_FORMAT and SET_CRC_MODE — the order Linux cdc_ncm uses.
     *
     * Without it iOS never arms its NCM data pipes for host-to-device traffic: inbound ICMPv6/mDNS
     * keeps arriving on bulk-IN while every host bulk-OUT URB stays NAKed forever
     * (submitted>0, completed=0, successBytes=0), so mDNS replies and TCP ACKs never leave the
     * head unit and CarPlay's video stream stays QUEUED — the "wired black screen" in report
     * 75Play-20261106-225501. Disable only if a phone rejects the request (result<0 in the log).
     */
    var negotiateNtbInputSize: Boolean = true

    /**
     * Retry SET_NTB_INPUT_SIZE with wLength=8 when the 4-byte form is refused.
     *
     * NCM 1.0 §6.2.7 makes the length depend on bmNetworkCapabilities D5 ("8-byte ntb input
     * size"): when D5 is set the host must send dwNtbInMaxSize as a 64-bit little-endian value,
     * and a 4-byte request is answered with STALL. iOS keeps returning -1 to the 4-byte form in
     * reports 235001/001909/005516, which is exactly the symptom of that length mismatch.
     */
    var negotiateNtbInputSize8Byte: Boolean = true

    /**
     * Repeat the OUT-data-stage requests (SET_MAX_DATAGRAM_SIZE, SET_NTB_INPUT_SIZE,
     * SET_ETHERNET_PACKET_FILTER) once the data interface is already at alt=1.
     *
     * Report 75Play-20261107-011616: every request carrying an OUT data stage returns -1 when it
     * is sent at alt=0, while every zero-length one returns 0. macOS drives the same phone with
     * these requests after the pipe is selected, so the ordering is the difference worth testing.
     */
    var renegotiateAfterAltSelection: Boolean = true

    /**
     * Re-send the OUT-data-stage class requests through usbfs USBDEVFS_CONTROL as well, so the
     * log carries both results for the same request.
     *
     * The framework returns -1 for every failure, which cannot separate a device STALL from a
     * timeout. After alt=1 the framework still failed all nine data-stage attempts while
     * zero-length requests kept succeeding (report 75Play-20261107-143817), so the ordering
     * theory is dead and the transport itself has to be inspected; usbfs returns -errno.
     */
    var probeRawUsbControl: Boolean = true

    /**
     * Send CDC SET_ETHERNET_PACKET_FILTER (class request 0x43, value 0x001C = directed + broadcast +
     * multicast) after SET_CRC_MODE — the last step of Linux cdc_ncm_setup().
     *
     * This is the step that works the data interface up at the device end: report
     * 75Play-20261106-235001 has SET_NTB_FORMAT and SET_CRC_MODE accepted, the host OUT URBs still
     * submitted=8/completed=0, and the iPhone keeping its bulk-OUT read queue empty. Some ECM/NCM
     * implementations only start reading that endpoint once the packet filter is armed.
     */
    var negotiatePacketFilter: Boolean = true

    /**
     * Send CDC SET_MAX_DATAGRAM_SIZE (class request 0x1E, wLength=2, value 1514) right before
     * SET_NTB_INPUT_SIZE.
     *
     * macOS sends it in exactly that slot when it brings up an iPhone's NCM data pipe (macOS is
     * the reference host that works), and this stack has never sent it: reports 225501/235001/001909
     * only contain GET_NTB_PARAMETERS, SET_NTB_FORMAT, SET_NTB_INPUT_SIZE, SET_CRC_MODE and
     * SET_ETHERNET_PACKET_FILTER. NCM 1.1 6.2.9 makes it the request that tells the device how
     * large a single datagram inside a host NTB may be, i.e. the OUT-side layout contract.
     */
    var negotiateMaxDatagramSize: Boolean = true

    /**
     * On the first bulk-OUT wedge, push one 64-byte block through a synchronous framework
     * bulkTransfer and log what it returns. >0 means the endpoint is writable and the async
     * native path is the suspect; -1 means the device is not reading the endpoint at all. The
     * probe block is not part of the frame stream and the phone drops it.
     */
    var probeOutOnWedge: Boolean = true

    /**
     * Treat bulk-OUT NAK timeouts during the early bring-up as "not ready" instead of counting
     * them toward the 25-failure wedge budget. The phone may keep NAKing OUT until the iAP2
     * CarPlay data channel arms, while unsolicited mDNS/ICMPv6 already flow on IN — which today
     * closes the "not ready" window prematurely (inboundFrameSeen) and burns the failure budget on
     * healthy bring-up delays.
     */
    var earlyGraceEnabled: Boolean = true

    /** Maximum enqueued frames tolerated as pre-ready NAKs before failures start counting. */
    var earlyGraceFrames: Int = 8

    /** Maximum milliseconds after bridge open tolerated as pre-ready NAKs. */
    var earlyGraceMillis: Long = 3_000L

    /**
     * Re-select the data alternate setting once during the early grace window instead of only at
     * failure #5. Experimental: on a half-built alt=1 pipe this may re-arm OUT; on a healthy link
     * it adds a harmless SET_INTERFACE.
     */
    var reselectAltOnEarlyNak: Boolean = false

    /** Extra settle delay (ms) after SET_INTERFACE before the first bulk-OUT write; 0 = off. */
    var settleAfterSetInterfaceMillis: Long = 0L

    /** Hex-dump the first outbound NTB block (NTH16+NDP16+Ethernet header) on success/failure. */
    var logFirstOutboundHex: Boolean = true

    /** Per-Pair lockdown step wait (ms) while the iPhone trust dialog is expected. */
    var pairDialogStepTimeoutMillis: Long = 10_000L

    /**
     * Best-effort iAP2 EndSession (0xE001) on the CSM channel during wired teardown.
     *
     * Default OFF: this codebase does not catalogue the 0xE001 opcode or its body, and a malformed
     * session-control message on a dying channel could keep the iPhone waiting instead of speeding
     * its teardown. Enable only during an A/B close-test; the send is wrapped best-effort and never
     * blocks teardown either way.
     */
    var endSessionOnClose: Boolean = false

    /* =========================================================================================
     * Combined three-stage bring-up switches (aligned with the working CherryUSB + LIVI hosts).
     * The individual knobs above stay in place for granular A/B; these master switches select the
     * end-to-end path that ships in one test build so the real car only needs one run.
     * ========================================================================================= */

    /**
     * Stage 1 — minimal control plane. When true, only GET_NTB_PARAMETERS (read-only) is issued
     * and then the data interface is selected at alt=1; every SET_* is skipped (CherryUSB brings
     * the link up without any SET). When false, the individual negotiate* knobs below apply.
     */
    var minimalControlPlane: Boolean = true

    /**
     * Stage 2 — synchronous blocking data plane. When true, bulk IN/OUT use USBDEVFS_BULK
     * (LIVI's blocking transfer) instead of the asynchronous URB ring whose OUT URBs never
     * complete on the i.MX6 KitKat kernel. When false, UsbfsNcmIo's async ring is used.
     */
    var useSyncBulkDataPlane: Boolean = true

    /**
     * Stage 3 — conditional recovery on a synchronous -EPIPE. On a latched stall, clear the
     * endpoint halt and bounce the data interface through alt=0 then back to alt=1, up to
     * [syncStallRecoveryLimit] times. It never runs on the healthy path.
     */
    var recoverSyncStall: Boolean = true

    /** Timeout for a single synchronous USBDEVFS_BULK transfer. */
    var syncBulkTimeoutMillis: Int = 3_000

    /** Maximum number of EPIPE recovery attempts over the whole lifetime of one connection. */
    var syncStallRecoveryLimit: Int = 2

    /** Settle delay after an alt bounce before the first synchronous bulk retry. */
    var syncRecoverySettleMillis: Long = 150L

    /**
     * Hard-wait for the NCM NETWORK_CONNECTION notification before submitting any bulk-OUT URB
     * (CherryUSB's get_connect_status gate). Report 75Play-20261108-125509 shows the discriminator:
     * a healthy connection opens already connected=1 and never writes while disconnected, whereas a
     * reconnect starts at connected=0 and, while the old code still issued a real bulk every 10 ms,
     * fired ~1500 failed URBs in the 14 s before connected=1 — after which the first write returned
     * EINVAL and the endpoint stayed wedged. When true, sendBatch returns before touching the USB
     * endpoint while the link is down instead of flooding it.
     */
    var hardWaitNetworkConnection: Boolean = true

    /**
     * Fallback that unblocks bulk OUT even without a NETWORK_CONNECTION after this many ms since
     * bridge open, so a lost/missing status notification can never cause a permanent write stall.
     * The observed connected delay is ~15 s; 30 s leaves headroom while bounding the wait.
     */
    var hardWaitFallbackMillis: Long = 30_000L
}
