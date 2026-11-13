package com.shilapi.xcertplay.transport

/**
 * CDC NCM OUT limits from GET_NTB_PARAMETERS. A missing or implausible reply keeps the caller
 * on [DEFAULT]; it never invents a multi-megabyte transfer.
 */
internal data class NcmNtbLimits(
    val maxBlockBytes: Int,
    val alignment: Int,
    val maxDatagrams: Int,
) {
    companion object {
        val DEFAULT = NcmNtbLimits(maxBlockBytes = 2048, alignment = 4, maxDatagrams = 8)

        fun parse(buffer: ByteArray, read: Int): NcmNtbLimits? {
            if (read < 28 || buffer.size < 28) return null
            val outMax = readU32(buffer, 16).toLong() and 0xffff_ffffL
            if (outMax !in 64..65535) return null
            val alignmentRaw = readU16(buffer, 24)
            val alignment = when {
                alignmentRaw == 0 -> 1
                alignmentRaw and (alignmentRaw - 1) == 0 -> alignmentRaw
                else -> 4
            }
            val datagramsRaw = readU16(buffer, 26)
            val datagrams = if (datagramsRaw in 1..32) datagramsRaw else DEFAULT.maxDatagrams
            return NcmNtbLimits(outMax.toInt(), alignment, datagrams)
        }

        private fun readU16(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

        private fun readU32(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 0xff) or
                ((source[offset + 1].toInt() and 0xff) shl 8) or
                ((source[offset + 2].toInt() and 0xff) shl 16) or
                ((source[offset + 3].toInt() and 0xff) shl 24)
    }
}

/** Longest frame prefix that fits one negotiated NTB16. */
internal object NcmTxBatch {
    fun take(frames: List<ByteArray>, limits: NcmNtbLimits): List<ByteArray> {
        val chosen = ArrayList<ByteArray>(frames.size)
        for (frame in frames) {
            if (chosen.size >= limits.maxDatagrams) break
            val candidate = ArrayList<ByteArray>(chosen.size + 1)
            candidate.addAll(chosen)
            candidate.add(frame)
            val length = runCatching { Ntb16Codec.encodedLength(candidate, limits.alignment) }.getOrNull()
                ?: break
            if (length > limits.maxBlockBytes) break
            chosen.add(frame)
        }
        return chosen
    }
}

/**
 * Inbound silence on the NCM data interface.
 *
 * A neighbor-discovery or mDNS frame often arrives before the phone sends NETWORK_CONNECTION.
 * The data path can then sit quiet for well over 8 seconds while iAP2 still starts CarPlay.
 * That gap is not a dead bulk-IN pipe. The silence clock starts only once the phone has marked
 * the link up, and it starts from that moment rather than from the earlier discovery frame.
 */
internal class NcmReadStall(
    private val stallNanos: Long,
) {
    private var linkUpAtNs = 0L

    /**
     * Quiet time since the link was up and the last inbound frame, or 0 when this poll
     * must not fail the session.
     */
    fun fatalQuietNanos(nowNs: Long, networkConnected: Boolean, inboundSeen: Boolean, lastFrameNs: Long): Long {
        if (!networkConnected || !inboundSeen || lastFrameNs == 0L) {
            linkUpAtNs = 0L
            return 0L
        }
        if (linkUpAtNs == 0L) linkUpAtNs = nowNs
        val quiet = nowNs - maxOf(lastFrameNs, linkUpAtNs)
        return if (quiet >= stallNanos) quiet else 0L
    }
}

/** Host-side OUT rejection timing. Never requests a bus reset. */
internal object NcmSessionHealth {
    /**
     * A data path that has already written successfully and then rejects OUT within 50ms
     * is a dead host pipe. The first such rejection still bounces the data alt.
     */
    fun immediateRejection(elapsedMs: Long): Boolean = elapsedMs in 0..50
}
