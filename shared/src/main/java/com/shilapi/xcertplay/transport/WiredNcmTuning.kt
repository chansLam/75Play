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
}
