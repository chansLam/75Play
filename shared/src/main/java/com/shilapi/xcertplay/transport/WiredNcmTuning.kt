package com.shilapi.xcertplay.transport

/**
 * Timing for the wired CDC-NCM data plane on API 19 head units.
 *
 * The transport layer has no [android.content.Context], so these stay as code defaults.
 */
object WiredNcmTuning {
    /**
     * Treat bulk-OUT NAK timeouts during early bring-up as "not ready" instead of counting
     * them toward the failure budget. The phone may keep NAKing OUT until the iAP2 CarPlay
     * data channel arms, while unsolicited mDNS/ICMPv6 already flow on IN.
     */
    var earlyGraceEnabled: Boolean = true

    /** Maximum enqueued frames tolerated as pre-ready NAKs before failures start counting. */
    var earlyGraceFrames: Int = 8

    /** Maximum milliseconds after bridge open tolerated as pre-ready NAKs. */
    var earlyGraceMillis: Long = 3_000L

    /** Hex-dump the first outbound NTB block (NTH16+NDP16+Ethernet header) on success/failure. */
    var logFirstOutboundHex: Boolean = true

    /** Per-Pair lockdown step wait (ms) while the iPhone trust dialog is expected. */
    var pairDialogStepTimeoutMillis: Long = 10_000L

    /**
     * On a synchronous -EPIPE, clear the endpoint halt and bounce the data interface through
     * alt=0 then back to alt=1, up to [syncStallRecoveryLimit] times. It never runs on the
     * healthy path.
     */
    var recoverSyncStall: Boolean = true

    /** Maximum number of EPIPE recovery attempts over the whole lifetime of one connection. */
    var syncStallRecoveryLimit: Int = 2

    /** Settle delay after an alt bounce before the first synchronous bulk retry. */
    var syncRecoverySettleMillis: Long = 150L

    /**
     * Do not submit bulk OUT until the phone's NETWORK_CONNECTION notification. A reconnect
     * that floods the endpoint while it is still down leaves the first write after connect
     * returning EINVAL and the pipe wedged.
     */
    var hardWaitNetworkConnection: Boolean = true

    /**
     * Unblock bulk OUT without NETWORK_CONNECTION after this many milliseconds, so a lost
     * status notification cannot stall writes forever. Observed connect delay is about 15 s.
     */
    var hardWaitFallbackMillis: Long = 30_000L
}
