package com.shilapi.xcertplay.network

import android.util.Log
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmTxBatch
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Userspace NCM bridge backed by lwIP.
 *
 * Operates entirely in user space without requiring Android's VpnService or a Linux TUN interface.
 * Incoming Ethernet frames from USB CDC-NCM are passed to lwIP, which handles IPv6 Link-Local
 * addressing, NDP, TCP handshakes and TCP byte stream relaying directly into local loopback (127.0.0.1).
 */
class LwipNcmBridge(
    private val ncm: NcmUsbBridge,
    private val hostMac: ByteArray,
    private val hostLinkLocal: String,
    private val onDebugLog: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit,
    @Volatile var mdnsResponder: WiredMdnsResponder? = null,
) : Closeable {
    init {
        require(hostMac.size == EthernetIpv6Codec.MAC_BYTES) { "hostMac must be 6 bytes" }
    }

    @Volatile
    private var peerMac: ByteArray? = null
    private val hostLinkLocalBytes = InetAddress.getByName(hostLinkLocal).address
    private val fe80_1_Bytes = byteArrayOf(
        0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
    )
    private val allNodesIpv6 = byteArrayOf(
        0xff.toByte(), 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01,
    )
    private val allNodesMac = byteArrayOf(0x33, 0x33, 0, 0, 0, 0x01)

    private var loggedInbound = false
    private var inboundLogBudget = 16
    private var reportInboundBudget = 8
    private var reportTcpBudget = 8
    private var mdnsAnswerLogBudget = 6
    private var unmatchedMdnsLogBudget = 6

    // Issue #262 diagnostics: keep "the responder built an answer" separate from "the answer
    // was actually queued for USB", so one failing log can pinpoint the broken link:
    // answered>0 & txSent==answered -> answers crossed the bridge, fault is iPhone-side;
    // answered>0 & txDropped>0 -> answers died at the USB TX queue; unmatched>0 -> queries
    // arrived but matched nothing; calls==0 -> the iPhone never queried at all.
    private val mdnsAnswerCalls = AtomicInteger(0)
    private val mdnsAnsweredQueries = AtomicInteger(0)
    private val mdnsTxSent = AtomicInteger(0)
    private val mdnsTxDropped = AtomicInteger(0)
    private val mdnsUnmatchedQueries = AtomicInteger(0)
    /** Last unmatched query name, kept in the summary: the per-name log lines are
     *  budgeted and can be trimmed from a report; the tally must carry the evidence. */
    @Volatile private var lastUnmatchedName: String? = null

    private fun mdnsDiagSummary(): String =
        "ncm mdns diag calls=${mdnsAnswerCalls.get()} answered=${mdnsAnsweredQueries.get()} " +
            "txSent=${mdnsTxSent.get()} txDropped=${mdnsTxDropped.get()} unmatched=${mdnsUnmatchedQueries.get()}" +
            (lastUnmatchedName?.let { " lastUnmatched=$it" } ?: "")

    private fun maybeLogMdnsDiag() {
        val events = mdnsAnsweredQueries.get() + mdnsUnmatchedQueries.get()
        if (events > 0 && events % MDNS_DIAG_LOG_EVERY == 0) {
            val msg = mdnsDiagSummary()
            Log.i(TAG, msg)
            runCatching { onDebugLog(msg) }
        }
    }

    val injectedTcpPackets = AtomicInteger(0)
    private var lwipInjectLogBudget = 8

    @Volatile
    private var lastNaTimeNanos = 0L
    private val running = AtomicBoolean(false)
    private lateinit var ncmRxThread: Thread
    private lateinit var ncmTxThread: Thread
    // Issue #187: lwIP's linkoutput runs on the native worker thread while holding the native
    // ctx lock; doing blocking USB bulkTransfer there (up to 2s) convoys every other native
    // call and can wedge a fragile USB host driver (whole-unit freeze on T3 cold-start wired).
    // Hand TX frames to a dedicated thread instead; a full queue makes linkoutput return ERR_MEM
    // so lwIP's TCP window applies backpressure instead of silently dropping ACKs.
    private val txQueue = LinkedBlockingQueue<ByteArray>(TX_QUEUE_CAPACITY)
    private val droppedTxFrames = AtomicInteger(0)
    private var txDropLogBudget = 4

    private val lwipNative = LwipNcmNative(
        onTxFrameCallback = ::onLwipTxFrame,
    )

    fun start() {
        check(running.compareAndSet(false, true)) { "bridge is already started" }

        val created = lwipNative.create(hostMac, hostLinkLocal)
        if (!created) {
            val err = IOException("Failed to initialize userspace lwIP stack")
            onError(err)
            throw err
        }
        sendUnsolicitedNeighborAdvertisement(force = true)

        ncmRxThread = Thread(::runNcmRx, "ncm-lwip-rx").apply {
            isDaemon = true
            start()
        }
        txQueue.clear()
        ncmTxThread = Thread(::runNcmTx, "ncm-lwip-tx").apply {
            isDaemon = true
            start()
        }
    }

    /** Ports with a live listener this session; TCP to any other port is stale traffic. */
    private val registeredTcpPorts = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** Issue #270: TCP packets aimed at a previous session's dataPort, counted for diagnosis. */
    val stalePortTcpPackets = AtomicInteger(0)
    private var stalePortLogBudget = 4

    fun registerPort(port: Int, isTcp: Boolean, uplink: Boolean = false) {
        lwipNative.registerPort(port, isTcp, uplink)
        if (isTcp) registeredTcpPorts += port
    }

    /** Forwards 127.0.0.1 datagrams for [port] to the iPhone. The port is the phone's, not ours. */
    fun registerUdpUplink(port: Int) = registerPort(port, isTcp = false, uplink = true)

    private fun enqueueTxFrame(frame: ByteArray, tag: String = "lwip"): Boolean {
        if (!running.get()) return false
        val queued = txQueue.offer(frame)
            if (!queued) {
                droppedTxFrames.incrementAndGet()
                if (txDropLogBudget > 0) {
                    txDropLogBudget--
                    runCatching { onDebugLog("ncm $tag tx backpressure bytes=${frame.size}") }
                }
            }
        return queued
    }

    private fun onLwipTxFrame(frame: ByteArray): Boolean {
        if (!running.get()) return false
        val queued = txQueue.offer(frame)
        if (!queued) {
            val count = droppedTxFrames.incrementAndGet()
            if (count % 16 == 1) {
                runCatching { onDebugLog("ncm lwip tx backpressure count=$count bytes=${frame.size}") }
            }
        }
        return queued
    }

    private fun runNcmTx() {
        try {
            val pending = ArrayList<ByteArray>()
            while (running.get() || pending.isNotEmpty()) {
                if (pending.isEmpty()) {
                    val first = txQueue.poll(TX_POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
                    pending.add(first)
                }
                while (pending.size < ncm.ntbLimits.maxDatagrams) {
                    val extra = txQueue.poll() ?: break
                    pending.add(extra)
                }
                val batch = NcmTxBatch.take(pending, ncm.ntbLimits)
                if (batch.isEmpty()) {
                    runCatching { onDebugLog("ncm drop oversized frame bytes=${pending.first().size}") }
                    pending.removeAt(0)
                    continue
                }
                try {
                    val accepted = ncm.sendBatch(batch, WRITE_TIMEOUT_MILLIS)
                    if (accepted > 0) {
                        repeat(accepted) { pending.removeAt(0) }
                    } else if (running.get()) {
                        Thread.sleep(TX_BACKPRESSURE_RETRY_MILLIS)
                    } else {
                        break
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "ncm send lwip tx frame failed: ${error.message}")
                    pending.clear()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun runNcmRx() {
        try {
            while (running.get()) {
                val frame = ncm.recv(READ_TIMEOUT_MILLIS)
                if (frame == null) {
                    if (peerMac == null) {
                        sendUnsolicitedNeighborAdvertisement()
                    }
                    continue
                }

                val ipv6 = EthernetIpv6Codec.parseIpv6View(frame) ?: continue
                peerMac = ipv6.sourceMac

                val ns = EthernetIpv6Codec.parseNeighborSolicitation(frame, ipv6.payloadOffset, ipv6.payloadLength)
                if (ns != null) {
                    val isDad = ns.sourceIp.all { it == 0.toByte() }
                    val isHostAddress = ns.targetIp.contentEquals(hostLinkLocalBytes) ||
                        ns.targetIp.contentEquals(fe80_1_Bytes)
                    val isLinkLocal = (ns.targetIp[0].toInt() and 0xff) == 0xfe &&
                        (ns.targetIp[1].toInt() and 0xc0) == 0x80
                    val shouldAnswer = if (isDad) {
                        isHostAddress
                    } else {
                        isHostAddress || (isLinkLocal && !ns.targetIp.contentEquals(ns.sourceIp))
                    }

                    if (shouldAnswer) {
                        val replyDstMac = if (isDad) allNodesMac else (ns.sourceMac ?: ipv6.sourceMac)
                        val replyDstIp = if (isDad) allNodesIpv6 else ns.sourceIp
                        val naFrame = EthernetIpv6Codec.buildNeighborAdvertisement(
                            hostMac = hostMac,
                            peerMac = replyDstMac,
                            hostIpv6 = ns.targetIp,
                            peerIpv6 = replyDstIp,
                            solicited = !isDad,
                        )
                        enqueueTxFrame(naFrame, tag = "na-reply")
                        val targetStr = runCatching { InetAddress.getByAddress(ns.targetIp).hostAddress }.getOrDefault("unknown")
                        val peerStr = runCatching { InetAddress.getByAddress(replyDstIp).hostAddress }.getOrDefault("unknown")
                        val naMsg = "ncm replied ${if (isDad) "unsolicited" else "solicited"} NA for $targetStr to $peerStr (${replyDstMac.macString()})"
                        Log.i(TAG, naMsg)
                        runCatching { onDebugLog(naMsg) }
                        continue
                    } else {
                        val targetStr = runCatching { InetAddress.getByAddress(ns.targetIp).hostAddress }.getOrDefault("unknown")
                        Log.d(TAG, "ncm ignoring DAD NS for peer candidate $targetStr")
                    }
                }

                val responder = mdnsResponder
                if (responder != null) mdnsAnswerCalls.incrementAndGet()
                val mdnsAnswers = responder?.answerFrames(
                    frame, ipv6.payloadOffset, ipv6.payloadLength, hostMac, ipv6.sourceMac,
                ) ?: emptyList()
                if (mdnsAnswers.isNotEmpty()) {
                    mdnsAnsweredQueries.incrementAndGet()
                    var enqueued = 0
                    for (mdnsAnswer in mdnsAnswers) {
                        if (enqueueTxFrame(mdnsAnswer, tag = "mdns")) {
                            mdnsTxSent.incrementAndGet()
                            enqueued++
                        } else {
                            mdnsTxDropped.incrementAndGet()
                        }
                    }
                    if (enqueued < mdnsAnswers.size) {
                        // Do not claim "answered" when the answer never reached the TX queue
                        // (#262): the drop is the diagnosis, not a success.
                        runCatching {
                            onDebugLog(
                                "ncm mdns answer TX dropped enqueued=$enqueued of=${mdnsAnswers.size} " +
                                    mdnsDiagSummary(),
                            )
                        }
                    }
                    if (mdnsAnswerLogBudget > 0) {
                        mdnsAnswerLogBudget--
                        val answerMsg = "WIRED-CHAIN [15/16] mDNS _airplay query answered frames=${mdnsAnswers.size} " +
                            "bytes=${mdnsAnswers.sumOf { it.size }} enqueued=$enqueued"
                        Log.i(TAG, answerMsg)
                        runCatching { onDebugLog(answerMsg) }
                    }
                    maybeLogMdnsDiag()
                    sendRouterAdvertisement(targetMac = ipv6.sourceMac)
                    sendUnsolicitedNeighborAdvertisement(force = true, targetMac = ipv6.sourceMac)
                    continue
                }

                if (responder != null) {
                    val queryName = responder.unmatchedQueryName(frame, ipv6.payloadOffset, ipv6.payloadLength)
                    if (queryName != null) {
                        mdnsUnmatchedQueries.incrementAndGet()
                        lastUnmatchedName = queryName
                        if (unmatchedMdnsLogBudget > 0) {
                            unmatchedMdnsLogBudget--
                            runCatching { onDebugLog("ncm wired mdns query not answered name=$queryName") }
                        }
                        maybeLogMdnsDiag()
                    }
                }

                if (!loggedInbound) {
                    loggedInbound = true
                    val inMsg = "ncm first inbound ipv6 bytes=${ipv6.payloadLength} peer=${ipv6.sourceMac.macString()}"
                    Log.i(TAG, inMsg)
                    runCatching { onDebugLog(inMsg) }
                    sendRouterAdvertisement(targetMac = ipv6.sourceMac)
                    sendUnsolicitedNeighborAdvertisement(force = true, targetMac = ipv6.sourceMac)
                }

                if (inboundLogBudget > 0) {
                    inboundLogBudget--
                    Log.i(TAG, "ncm inbound ${frame.summary(ipv6.payloadOffset)}")
                }

                val inboundNextHeader =
                    if (ipv6.payloadLength >= 40) (frame[ipv6.payloadOffset + 6].toInt() and 0xff) else -1
                if (inboundNextHeader == 6 && reportTcpBudget > 0) {
                    reportTcpBudget--
                    runCatching { onDebugLog("ncm inbound diag ${frame.diagnosticSummary(ipv6.payloadOffset)}") }
                } else if (reportInboundBudget > 0) {
                    reportInboundBudget--
                    runCatching { onDebugLog("ncm inbound diag ${frame.diagnosticSummary(ipv6.payloadOffset)}") }
                }

                if (inboundNextHeader == 58 && ipv6.payloadLength >= 48) {
                    val icmpType = frame[ipv6.payloadOffset + 40].toInt() and 0xff
                    if (icmpType == 133) {
                        Log.i(TAG, "ncm inbound Router Solicitation from iPhone; sending RA and NA")
                        runCatching { onDebugLog("ncm inbound RS from peer ${ipv6.sourceMac.macString()}; sending RA") }
                        val peerSrcIp = frame.copyOfRange(ipv6.payloadOffset + 8, ipv6.payloadOffset + 24)
                        sendRouterAdvertisement(targetMac = ipv6.sourceMac, targetIp = peerSrcIp)
                        sendUnsolicitedNeighborAdvertisement(force = true, targetMac = ipv6.sourceMac)
                    }
                }

                val isTcp = inboundNextHeader == 6
                if (isTcp) {
                    val tcpCount = injectedTcpPackets.incrementAndGet()
                    if (tcpCount == 1) {
                        val tcpMsg = "WIRED-CHAIN [16/16] first TCP packet passed to lwIP (iPhone -> 7000)"
                        Log.i(TAG, tcpMsg)
                        runCatching { onDebugLog(tcpMsg) }
                    }
                    // Issue #270: after a session ends, the iPhone keeps sending TCP to the
                    // previous session's video dataPort during the next attempt. Those packets
                    // have no listener now; count them so a failing reconnect log shows whether
                    // stale-session traffic is drowning the new attempt's SYN to 7000.
                    if (ipv6.payloadLength >= 44 && registeredTcpPorts.isNotEmpty()) {
                        val dstPort = ((frame[ipv6.payloadOffset + 42].toInt() and 0xff) shl 8) or
                            (frame[ipv6.payloadOffset + 43].toInt() and 0xff)
                        if (dstPort !in registeredTcpPorts) {
                            val stale = stalePortTcpPackets.incrementAndGet()
                            if (stalePortLogBudget > 0) {
                                stalePortLogBudget--
                                runCatching {
                                    onDebugLog("ncm stale-session tcp dstPort=$dstPort count=$stale (no listener this session)")
                                }
                            }
                        }
                    }
                }

                lwipNative.inputFrame(frame)

                if (isTcp && lwipInjectLogBudget > 0) {
                    lwipInjectLogBudget--
                    val dstPort = if (ipv6.payloadLength >= 44) {
                        ((frame[ipv6.payloadOffset + 42].toInt() and 0xff) shl 8) or
                            (frame[ipv6.payloadOffset + 43].toInt() and 0xff)
                    } else -1
                    val flags = if (ipv6.payloadLength >= 54) frame[ipv6.payloadOffset + 40 + 13].toInt() and 0xff else 0
                    val isSyn = (flags and 0x02) != 0
                    val dstLocal = ipv6.payloadLength >= 40 &&
                        (frame.copyOfRange(ipv6.payloadOffset + 24, ipv6.payloadOffset + 40).contentEquals(hostLinkLocalBytes) ||
                         frame.copyOfRange(ipv6.payloadOffset + 24, ipv6.payloadOffset + 40).contentEquals(fe80_1_Bytes))
                    runCatching {
                        onDebugLog(
                            "ncm lwip inject tcp bytes=${ipv6.payloadLength} dstPort=$dstPort${if (isSyn) " [SYN]" else ""} dstLocal=$dstLocal ok",
                        )
                    }
                }
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    fun tcpSnapshot(): String = lwipNative.getSnapshot()

    fun sendRouterAdvertisement(targetMac: ByteArray? = null, targetIp: ByteArray? = null) {
        if (!running.get()) return
        try {
            val dstMac = targetMac ?: peerMac ?: allNodesMac
            val dstIp = targetIp ?: allNodesIpv6
            val frame = EthernetIpv6Codec.buildRouterAdvertisement(
                hostMac = hostMac,
                peerMac = dstMac,
                hostIpv6 = hostLinkLocalBytes,
                peerIpv6 = dstIp,
            )
            enqueueTxFrame(frame, tag = "ra")
            val raMsg = "ncm queued Router Advertisement for $hostLinkLocal to ${dstMac.macString()}"
            Log.i(TAG, raMsg)
            runCatching { onDebugLog(raMsg) }
        } catch (error: Exception) {
            Log.w(TAG, "ncm RA queue failed: ${error.message}")
        }
    }

    fun sendUnsolicitedNeighborAdvertisement(force: Boolean = false, targetMac: ByteArray? = null) {
        if (!running.get()) return
        val now = System.nanoTime()
        synchronized(this) {
            if (!force && now - lastNaTimeNanos < NA_RETRY_INTERVAL_NANOS) return
            lastNaTimeNanos = now
        }
        try {
            val dstMac = targetMac ?: peerMac ?: allNodesMac
            val frame = EthernetIpv6Codec.buildNeighborAdvertisement(
                hostMac = hostMac,
                peerMac = dstMac,
                hostIpv6 = hostLinkLocalBytes,
                peerIpv6 = allNodesIpv6,
                solicited = false,
            )
            enqueueTxFrame(frame, tag = "unsolicited-na")
            val naMsg = "ncm queued unsolicited neighbor advertisement for $hostLinkLocal"
            Log.i(TAG, naMsg)
            runCatching { onDebugLog(naMsg) }
        } catch (error: Exception) {
            Log.w(TAG, "ncm unsolicited NA queue failed: ${error.message}")
        }
    }

    fun markAborted() {
        running.set(false)
        txQueue.clear()
        ncm.markAborted()
    }

    private val closed = AtomicBoolean(false)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        // Final mDNS tally for the report (#262): survives even when nothing else logged.
        if (mdnsAnswerCalls.get() > 0 || mdnsUnmatchedQueries.get() > 0) {
            val msg = mdnsDiagSummary()
            Log.i(TAG, msg)
            runCatching { onDebugLog(msg) }
        }
        txQueue.clear()
        // Stop the TX thread first so no new USB writes start; remaining queued frames
        // are dropped with the link going down. Guard for a start() that threw before
        // the thread was created.
        if (::ncmTxThread.isInitialized) join(ncmTxThread)
        // Wait for the RX thread to finish its in-flight read and exit BEFORE closing NCM
        // and releasing interfaces. Releasing an interface while a native bulkTransfer ioctl
        // is active causes an uninterruptible driver deadlock on Allwinner T3 kernels.
        if (::ncmRxThread.isInitialized) join(ncmRxThread)
        lwipNative.destroy()
        ncm.close()
    }

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) thread.interrupt()
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ByteArray.summary(offset: Int): String {
        val payloadBytes = size - offset
        if (payloadBytes < 40) return "truncated bytes=$payloadBytes"
        val source = InetAddress.getByAddress(copyOfRange(offset + 8, offset + 24)).hostAddress
        val destination = InetAddress.getByAddress(copyOfRange(offset + 24, offset + 40)).hostAddress
        val nextHeader = this[offset + 6].toInt() and 0xff
        val detail = when {
            nextHeader == 6 && payloadBytes >= 44 -> " tcp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 17 && payloadBytes >= 44 -> " udp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 58 && payloadBytes >= 41 -> " icmp6=${this[offset + 40].toInt() and 0xff}"
            else -> ""
        }
        return "bytes=$payloadBytes src=$source dst=$destination next=$nextHeader$detail"
    }

    private fun ByteArray.u16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private companion object {
        const val TAG = "xcertplay-usb"
        const val READ_TIMEOUT_MILLIS = 200L
        const val WRITE_TIMEOUT_MILLIS = 500
        const val JOIN_TIMEOUT_MILLIS = 500L
        const val NA_RETRY_INTERVAL_NANOS = 500_000_000L
        const val TX_QUEUE_CAPACITY = 64
        const val TX_POLL_MILLIS = 200L
        const val TX_BACKPRESSURE_RETRY_MILLIS = 10L
        /** Emit the mDNS diagnostic summary after every N answered/unmatched queries (#262). */
        const val MDNS_DIAG_LOG_EVERY = 5
    }
}

/**
 * IP-redacted L4 summary of one inbound IPv6 packet for the user-exported diagnostic
 * report (issue #95): enough to tell "TCP SYN to port 7000 arrived" from "no TCP at all",
 * without leaking addresses. [offset] is the IPv6 header start within the Ethernet frame.
 */
internal fun ByteArray.diagnosticSummary(offset: Int): String {
    fun u16(at: Int): Int = ((this[at].toInt() and 0xff) shl 8) or (this[at + 1].toInt() and 0xff)
    val payloadBytes = size - offset
    if (payloadBytes < 44) return "bytes=$payloadBytes"
    val nextHeader = this[offset + 6].toInt() and 0xff
    val detail = when (nextHeader) {
        6 -> {
            val flags = if (payloadBytes >= 54) (this[offset + 40 + 13].toInt() and 0xff) else 0
            val syn = (flags and 0x02) != 0
            " tcp=->${u16(offset + 42)}${if (syn) "[SYN]" else ""}"
        }
        17 -> {
            val dstPort = u16(offset + 42)
            val mdnsTag = if (dstPort == 5353 && payloadBytes >= 48 + 4) {
                val qr = (this[offset + 48 + 2].toInt() and 0x80) != 0
                if (qr) "(mdns-resp)" else "(mdns-query)"
            } else ""
            " udp=->$dstPort$mdnsTag"
        }
        58 -> {
            val icmpType = this[offset + 40].toInt() and 0xff
            val tag = if (payloadBytes >= 64 && icmpType == 135) "(NS)"
            else if (payloadBytes >= 48 && icmpType == 133) "(RS)"
            else if (payloadBytes >= 64 && icmpType == 136) "(NA)"
            else ""
            " icmp6=$icmpType$tag"
        }
        else -> ""
    }
    return "bytes=$payloadBytes next=$nextHeader$detail"
}
