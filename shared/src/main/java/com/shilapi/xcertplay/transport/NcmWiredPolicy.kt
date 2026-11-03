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

internal data class NcmOutSnapshot(
    val queued: Int,
    val submitted: Int,
    val completed: Int,
    val cancelling: Int,
    val reclaimFailed: Int,
    val stall: Int,
    val nak: Int,
    val noCompletion: Int,
    val latencyMs: Int,
    val replug: Boolean,
    val successBytes: Long,
) {
    companion object {
        fun parse(line: String): NcmOutSnapshot? {
            if (!line.contains("completed=")) return null
            fun value(key: String): Long =
                Regex("""(?:^|\s)$key=(-?\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            return NcmOutSnapshot(
                queued = value("queued").toInt(),
                submitted = value("submitted").toInt(),
                completed = value("completed").toInt(),
                cancelling = value("cancelling").toInt(),
                reclaimFailed = value("reclaimFailed").toInt(),
                stall = value("stall").toInt(),
                nak = value("nak").toInt(),
                noCompletion = value("noCompletion").toInt(),
                latencyMs = value("latencyMs").toInt(),
                replug = value("replug") != 0L,
                successBytes = value("successBytes"),
            )
        }
    }
}

internal enum class NcmOutAction { CONTINUE, DISCARD, REPLUG }

/** Maps an asynchronous OUT snapshot to a session action. Never requests a bus reset. */
internal object NcmSessionHealth {
    fun decide(snapshot: NcmOutSnapshot): NcmOutAction = when {
        snapshot.replug || snapshot.reclaimFailed > 0 -> NcmOutAction.REPLUG
        snapshot.noCompletion > 0 -> NcmOutAction.DISCARD
        else -> NcmOutAction.CONTINUE
    }
}
