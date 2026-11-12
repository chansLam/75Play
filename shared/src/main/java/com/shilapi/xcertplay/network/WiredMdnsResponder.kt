package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Issue #148: answers the iPhone's mDNS queries for the wired AirPlay service with
 * unicast responses built at the packet layer.
 *
 * Why packet-layer unicast instead of JmDNS: the wired VPN only installs the unicast
 * fe80::/10 route, so the kernel routes JmDNS's multicast (ff02::fb) announcements out
 * the physical interface — they never enter the TUN fd and never cross the NCM bridge
 * to the iPhone. The iPhone keeps sending mDNS queries and never opens TCP/7000
 * ("No AirPlay TCP after CarPlay StartSession"). A unicast answer to the querier's
 * address/port takes the ordinary unicast path the bridge already forwards, and a
 * QU-bit query explicitly asks for a unicast reply (RFC 6762 section 5.4).
 *
 * The caller feeds inbound IPv6 packets via [answerFrames]; each returned Ethernet
 * frame goes straight back over NCM to the peer. Queries for anything else return
 * an empty list and stay on the normal TUN path.
 *
 * Issue #163: the responder used to answer every query with a unicast response
 * only. Per RFC 6762 section 5.4 a querier that does NOT set the QU bit expects
 * a multicast response, and some stacks ignore a unicast answer to a non-QU
 * query — the exact signature in #163 (unicast answers sent, iPhone keeps
 * querying, no TCP/7000). So a non-QU query now also gets a multicast copy to
 * ff02::fb; a QU query keeps the unicast-only answer. The unicast copy is always
 * sent, so the previously working path cannot regress.
 */
class WiredMdnsResponder(
    deviceName: String,
    hostLinkLocal: String,
    private val airPlayPort: Int,
    private val txtRecords: Map<String, String>,
) {
    private val hostAddressBytes: ByteArray = run {
        val bytes = try {
            java.net.InetAddress.getByName(hostLinkLocal).address
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid hostLinkLocal: $hostLinkLocal", error)
        }
        require(bytes.size == 16) { "hostLinkLocal must be an IPv6 literal" }
        bytes
    }
    private val serviceInstance = "$deviceName._airplay._tcp.local"
    private val serviceInstanceLower = serviceInstance.lowercase()
    /**
     * Issue #152: the iPhone also discovers the audio endpoint via _raop._tcp.
     * Answer those queries with a parallel RAOP service instance so the follow-up
     * SRV/TXT lookups resolve to this accessory.
     */
    private val raopInstance = "$deviceName._raop._tcp.local"
    private val raopInstanceLower = raopInstance.lowercase()
    private val hostName = "${sanitizeHostName(deviceName)}.local"
    private val hostNameLower = hostName.lowercase()

    init {
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
    }

    /**
     * Inspects one inbound IPv6 packet ([offset]/[length] view into [frame]). When it is a
     * UDP mDNS query for this accessory's AirPlay service, returns the complete Ethernet II
     * frames answering it (unicast to the querier, plus a multicast copy to ff02::fb
     * when no question carries the QU bit); otherwise returns an empty list.
     */
    fun answerFrames(
        frame: ByteArray,
        offset: Int,
        length: Int,
        hostMac: ByteArray,
        peerMac: ByteArray,
    ): List<ByteArray> {
        if (length < IPV6_HEADER_BYTES + UDP_HEADER_BYTES + DNS_HEADER_BYTES) return emptyList()
        if (offset < 0 || offset + length > frame.size) return emptyList()
        if ((frame[offset].toInt() ushr 4 and 0x0f) != 6) return emptyList()
        if ((frame[offset + IPV6_NEXT_HEADER_OFFSET].toInt() and 0xff) != UDP_NEXT_HEADER) return emptyList()
        val dstPort = u16(frame, offset + IPV6_HEADER_BYTES + 2)
        if (dstPort != MDNS_PORT) return emptyList()
        // Only answer link-local multicast queries or queries sent to us directly.
        val dstFirst = frame[offset + IPV6_DESTINATION_OFFSET].toInt() and 0xff
        val dstIsMulticast = dstFirst == 0xff
        val dstIsUs = (offset + IPV6_DESTINATION_OFFSET + 16 <= offset + length) &&
            frame.copyOfRange(offset + IPV6_DESTINATION_OFFSET, offset + IPV6_DESTINATION_OFFSET + 16)
                .contentEquals(hostAddressBytes)
        if (!dstIsMulticast && !dstIsUs) return emptyList()
        val udpLength = u16(frame, offset + IPV6_HEADER_BYTES + 4)
        // Issue #199: NCM frames can arrive with truncated tails on some head units
        // while the UDP header still claims the full datagram length. The DNS
        // questions are usually intact (only trailing bytes are cut), so parse the
        // bytes that actually arrived instead of dropping the query silently.
        // Downstream parsing stays bounds-checked; well-formed frames are unaffected.
        val dnsLength = minOf(udpLength - UDP_HEADER_BYTES, length - IPV6_HEADER_BYTES - UDP_HEADER_BYTES)
        if (dnsLength < DNS_HEADER_BYTES) return emptyList()
        val srcPort = u16(frame, offset + IPV6_HEADER_BYTES)
        val srcAddress = frame.copyOfRange(offset + IPV6_SOURCE_OFFSET, offset + IPV6_SOURCE_OFFSET + 16)
        val query = frame.copyOfRange(
            offset + IPV6_HEADER_BYTES + UDP_HEADER_BYTES,
            offset + IPV6_HEADER_BYTES + UDP_HEADER_BYTES + dnsLength,
        )
        val (dnsPayload, anyQu) = buildDnsPayload(query) ?: return emptyList()
        val frames = ArrayList<ByteArray>(2)
        frames += EthernetIpv6Codec.build(hostMac, peerMac, buildUdpIpv6(dnsPayload, srcAddress, srcPort))
        if (!anyQu) {
            frames += EthernetIpv6Codec.build(
                hostMac,
                MDNS_MULTICAST_MAC,
                buildUdpIpv6(dnsPayload, MDNS_MULTICAST_IPV6, MDNS_PORT),
            )
        }
        return frames
    }

    /**
     * Returns the first question name of an mDNS query packet, or null when the
     * packet is not an mDNS query. Lets the caller log what the iPhone actually
     * asked for when no answer matched (issue #152).
     */
    fun unmatchedQueryName(frame: ByteArray, offset: Int, length: Int): String? {
        if (length < IPV6_HEADER_BYTES + UDP_HEADER_BYTES + DNS_HEADER_BYTES) return null
        if (offset < 0 || offset + length > frame.size) return null
        if ((frame[offset].toInt() ushr 4 and 0x0f) != 6) return null
        if ((frame[offset + IPV6_NEXT_HEADER_OFFSET].toInt() and 0xff) != UDP_NEXT_HEADER) return null
        if (u16(frame, offset + IPV6_HEADER_BYTES + 2) != MDNS_PORT) return null
        val udpLength = u16(frame, offset + IPV6_HEADER_BYTES + 4)
        // Issue #199: same truncated-tail tolerance as answerFrames, so a
        // truncated query we cannot answer still gets its name logged.
        val dnsLength = minOf(udpLength - UDP_HEADER_BYTES, length - IPV6_HEADER_BYTES - UDP_HEADER_BYTES)
        if (dnsLength < DNS_HEADER_BYTES) return null
        val query = frame.copyOfRange(
            offset + IPV6_HEADER_BYTES + UDP_HEADER_BYTES,
            offset + IPV6_HEADER_BYTES + UDP_HEADER_BYTES + dnsLength,
        )
        val parsed = parseQuestions(query) ?: return null
        return parsed.questions.firstOrNull()?.name
    }

    /**
     * Builds the DNS answer payload for one mDNS query, or null when the query does
     * not ask for this accessory's AirPlay service. The second element reports
     * whether any question carried the QU bit (RFC 6762 section 5.4: QU set means
     * the querier wants a unicast reply; QU clear means it expects multicast).
     */
    private fun buildDnsPayload(query: ByteArray): Pair<ByteArray, Boolean>? {
        val parsed = parseQuestions(query) ?: return null
        val anyQu = parsed.questions.any { it.qclass and QU_BIT != 0 }
        var wantPtr = false
        var wantRaopPtr = false
        var wantServicesEnum = false
        var wantAaaa = false
        val srvTxtInstances = LinkedHashSet<String>()
        for (question in parsed.questions) {
            val name = question.name.lowercase()
            when {
                name == SERVICES_ENUM &&
                    (question.type == QTYPE_PTR || question.type == QTYPE_ANY) -> {
                    wantServicesEnum = true
                }
                name == AIRPLAY_SERVICE &&
                    (question.type == QTYPE_PTR || question.type == QTYPE_ANY) -> {
                    wantPtr = true
                    srvTxtInstances += serviceInstance
                    wantAaaa = true
                }
                name == RAOP_SERVICE &&
                    (question.type == QTYPE_PTR || question.type == QTYPE_ANY) -> {
                    wantRaopPtr = true
                    srvTxtInstances += raopInstance
                    wantAaaa = true
                }
                (name == serviceInstanceLower || name == raopInstanceLower) &&
                    (question.type == QTYPE_SRV || question.type == QTYPE_TXT || question.type == QTYPE_ANY) -> {
                    srvTxtInstances += if (name == raopInstanceLower) raopInstance else serviceInstance
                    wantAaaa = true
                }
                name == hostNameLower &&
                    (question.type == QTYPE_AAAA || question.type == QTYPE_ANY) -> {
                    wantAaaa = true
                }
            }
        }
        if (!wantPtr && !wantRaopPtr && !wantServicesEnum && srvTxtInstances.isEmpty() && !wantAaaa) return null

        val answers = ArrayList<ByteArray>(4)
        if (wantServicesEnum) {
            answers += dnsRecord(encodeName(SERVICES_ENUM), QTYPE_PTR, encodeName(AIRPLAY_SERVICE))
            answers += dnsRecord(encodeName(SERVICES_ENUM), QTYPE_PTR, encodeName(RAOP_SERVICE))
        }
        if (wantPtr) {
            answers += dnsRecord(encodeName(AIRPLAY_SERVICE), QTYPE_PTR, encodeName(serviceInstance))
        }
        if (wantRaopPtr) {
            answers += dnsRecord(encodeName(RAOP_SERVICE), QTYPE_PTR, encodeName(raopInstance))
        }
        for (instance in srvTxtInstances) {
            answers += dnsRecord(encodeName(instance), QTYPE_SRV, srvRdata())
            answers += dnsRecord(encodeName(instance), QTYPE_TXT, txtRdata())
        }
        if (wantAaaa) {
            answers += dnsRecord(encodeName(hostName), QTYPE_AAAA, hostAddressBytes)
        }

        val out = ByteArrayOutputStream()
        out.write(query[0].toInt() and 0xff)
        out.write(query[1].toInt() and 0xff)
        val queryFlags = u16(query, 2)
        val flags = 0x8400 or (queryFlags and 0x0100) // QR + AA, copy RD
        out.write(flags ushr 8)
        out.write(flags)
        out.write(query[4].toInt() and 0xff) // QDCOUNT echoed
        out.write(query[5].toInt() and 0xff)
        out.write(answers.size ushr 8)
        out.write(answers.size)
        out.write(0) // NSCOUNT
        out.write(0)
        out.write(0) // ARCOUNT
        out.write(0)
        out.write(query, DNS_HEADER_BYTES, parsed.questionsEnd - DNS_HEADER_BYTES) // questions verbatim
        for (answer in answers) out.write(answer)
        return out.toByteArray() to anyQu
    }

    private fun srvRdata(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0) // priority
        out.write(0)
        out.write(0) // weight
        out.write(0)
        out.write(airPlayPort ushr 8)
        out.write(airPlayPort)
        out.write(encodeName(hostName))
        return out.toByteArray()
    }

    private fun txtRdata(): ByteArray {
        val out = ByteArrayOutputStream()
        for ((key, value) in txtRecords) {
            var bytes = "$key=$value".toByteArray(StandardCharsets.UTF_8)
            if (bytes.isEmpty()) continue
            // A DNS character-string caps at 255 bytes; split longer values.
            while (bytes.size > 255) {
                out.write(255)
                out.write(bytes, 0, 255)
                bytes = bytes.copyOfRange(255, bytes.size)
            }
            out.write(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun buildUdpIpv6(dns: ByteArray, dst: ByteArray, dstPort: Int): ByteArray {
        val udpLength = UDP_HEADER_BYTES + dns.size
        val packet = ByteArray(IPV6_HEADER_BYTES + udpLength)
        packet[0] = 0x60.toByte()
        packet[4] = (udpLength ushr 8).toByte()
        packet[5] = udpLength.toByte()
        packet[IPV6_NEXT_HEADER_OFFSET] = UDP_NEXT_HEADER.toByte()
        packet[7] = 255.toByte() // hop limit: mDNS uses 255
        hostAddressBytes.copyInto(packet, IPV6_SOURCE_OFFSET)
        dst.copyInto(packet, IPV6_DESTINATION_OFFSET)
        val udp = IPV6_HEADER_BYTES
        packet[udp] = (MDNS_PORT ushr 8).toByte()
        packet[udp + 1] = MDNS_PORT.toByte()
        packet[udp + 2] = (dstPort ushr 8).toByte()
        packet[udp + 3] = dstPort.toByte()
        packet[udp + 4] = (udpLength ushr 8).toByte()
        packet[udp + 5] = udpLength.toByte()
        // checksum at udp+6/udp+7 stays zero while computing
        dns.copyInto(packet, udp + UDP_HEADER_BYTES)
        val checksum = udpChecksum(packet, dst, udpLength)
        packet[udp + 6] = (checksum ushr 8).toByte()
        packet[udp + 7] = checksum.toByte()
        return packet
    }

    /** RFC 2460 section 8.1 UDP checksum over the IPv6 pseudo-header. */
    private fun udpChecksum(packet: ByteArray, dst: ByteArray, udpLength: Int): Int {
        var sum = 0L
        fun addWords(bytes: ByteArray, offset: Int, length: Int) {
            var pos = offset
            val end = offset + length
            while (pos + 1 < end) {
                sum += (((bytes[pos].toInt() and 0xff) shl 8) or (bytes[pos + 1].toInt() and 0xff)).toLong()
                pos += 2
            }
            if (pos < end) sum += ((bytes[pos].toInt() and 0xff) shl 8).toLong()
        }
        addWords(packet, IPV6_SOURCE_OFFSET, 16)
        addWords(dst, 0, 16)
        sum += udpLength.toLong() // upper-layer length as 32 bits; high half is zero
        sum += UDP_NEXT_HEADER.toLong() // three zero bytes + next header
        addWords(packet, IPV6_HEADER_BYTES, udpLength) // UDP header (checksum zero) + payload
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        val checksum = sum.inv().toInt() and 0xffff
        // A computed zero means "no checksum" for UDP; transmit all-ones instead.
        return if (checksum == 0) 0xffff else checksum
    }

    private data class ParsedQuery(val questions: List<Question>, val questionsEnd: Int)
    private data class Question(val name: String, val type: Int, val qclass: Int)

    private fun parseQuestions(query: ByteArray): ParsedQuery? {
        if (query.size < DNS_HEADER_BYTES) return null
        if (u16(query, 2) and 0x8000 != 0) return null // not a query
        val qdCount = u16(query, 4)
        if (qdCount == 0 || qdCount > MAX_QUESTIONS) return null
        val questions = ArrayList<Question>(qdCount)
        var pos = DNS_HEADER_BYTES
        repeat(qdCount) {
            val (name, next) = readName(query, pos) ?: return null
            if (next + 4 > query.size) return null
            questions += Question(name, u16(query, next), u16(query, next + 2))
            pos = next + 4
        }
        return ParsedQuery(questions, pos)
    }

    /** Reads a (possibly compressed) DNS name; returns the dotted name and the offset after it. */
    private fun readName(packet: ByteArray, offset: Int): Pair<String, Int>? {
        val labels = ArrayList<String>(4)
        var pos = offset
        var next = offset
        var jumped = false
        var steps = 0
        while (true) {
            if (++steps > MAX_NAME_STEPS || pos >= packet.size) return null
            val len = packet[pos].toInt() and 0xff
            when {
                len == 0 -> {
                    if (!jumped) next = pos + 1
                    break
                }
                len and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= packet.size) return null
                    if (!jumped) next = pos + 2
                    jumped = true
                    pos = ((len and 0x3F) shl 8) or (packet[pos + 1].toInt() and 0xff)
                }
                len and 0xC0 != 0 -> return null
                else -> {
                    if (pos + 1 + len > packet.size) return null
                    labels += String(packet, pos + 1, len, StandardCharsets.UTF_8)
                    pos += 1 + len
                }
            }
        }
        return labels.joinToString(".") to next
    }

    private fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (rawLabel in name.split('.')) {
            val bytes = utf8Truncate(rawLabel, MAX_LABEL_BYTES)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun dnsRecord(name: ByteArray, type: Int, rdata: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(name)
        out.write(type ushr 8)
        out.write(type)
        out.write(0) // CLASS IN
        out.write(1)
        out.write(TTL_SECONDS ushr 24)
        out.write(TTL_SECONDS ushr 16)
        out.write(TTL_SECONDS ushr 8)
        out.write(TTL_SECONDS)
        out.write(rdata.size ushr 8)
        out.write(rdata.size)
        out.write(rdata)
        return out.toByteArray()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun utf8Truncate(text: String, maxBytes: Int): ByteArray {
        // Truncate on a code-point boundary so multi-byte sequences stay valid.
        var end = 0
        var bytes = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val charBytes = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8).size
            if (bytes + charBytes > maxBytes) break
            bytes += charBytes
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end).toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        private const val MDNS_PORT = 5353
        private const val QU_BIT = 0x8000
        /** mDNS link-local multicast group ff02::fb (RFC 6762). */
        private val MDNS_MULTICAST_IPV6 = byteArrayOf(
            0xff.toByte(), 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xfb.toByte(),
        )
        /** Ethernet multicast MAC for ff02::fb (33:33:00:00:00:fb). */
        private val MDNS_MULTICAST_MAC = byteArrayOf(0x33, 0x33, 0, 0, 0, 0xfb.toByte())
        private const val IPV6_HEADER_BYTES = 40
        private const val IPV6_NEXT_HEADER_OFFSET = 6
        private const val IPV6_SOURCE_OFFSET = 8
        private const val IPV6_DESTINATION_OFFSET = 24
        private const val UDP_NEXT_HEADER = 17
        private const val UDP_HEADER_BYTES = 8
        private const val DNS_HEADER_BYTES = 12
        private const val QTYPE_PTR = 12
        private const val QTYPE_TXT = 16
        private const val QTYPE_AAAA = 28
        private const val QTYPE_SRV = 33
        private const val QTYPE_ANY = 255
        private const val TTL_SECONDS = 120
        private const val MAX_QUESTIONS = 16
        private const val MAX_NAME_STEPS = 32
        private const val MAX_LABEL_BYTES = 63
        private const val AIRPLAY_SERVICE = "_airplay._tcp.local"
        private const val RAOP_SERVICE = "_raop._tcp.local"
        private const val SERVICES_ENUM = "_services._dns-sd._udp.local"

        private fun sanitizeHostName(deviceName: String): String {
            val cleaned = deviceName.map { c ->
                if (c.isLetterOrDigit() || c == '-') c else '-'
            }.joinToString("").trim('-').ifEmpty { "diplay" }
            return cleaned.lowercase()
        }
    }
}
