package com.shilapi.xcertplay.transport

/**
 * Minimal NTB16 codec for the iPhone NCM data link.
 *
 * Only the 16-bit transfer-block layout used by LIVI's `iap2-usbmux/src/ntb.rs` is implemented:
 * one NTH16 header, one NDP16 table and the USB short-packet pad byte. There is no NTB32 or CRC
 * handling here.
 */
object Ntb16Codec {
    const val NTH16_SIG = 0x484d434e
    const val NDP16_SIG = 0x304d434e

    private const val NTH_LENGTH = 12
    private const val NDP_LENGTH = 16
    private const val DATAGRAM_INDEX = 28
    private const val USB_PACKET_SIZE = 512

    /** Wraps one Ethernet frame in one NTB16 block, padding an exact 512-byte boundary. */
    fun build(frame: ByteArray, sequence: Int): ByteArray {
        return buildFrames(
            frames = listOf(frame),
            sequence = sequence,
            maxBlockBytes = MAX_NTB16_BYTES,
            alignment = 1,
        )
    }

    /**
     * Encodes multiple Ethernet frames in one NTB16. NCM peers advertise their OUT size and
     * alignment in GET_NTB_PARAMETERS; callers split at [maxBlockBytes] before invoking this.
     */
    fun buildFrames(
        frames: List<ByteArray>,
        sequence: Int,
        maxBlockBytes: Int,
        alignment: Int,
    ): ByteArray {
        require(frames.isNotEmpty()) { "at least one Ethernet frame is required" }
        require(sequence in 0..0xffff) { "sequence must fit in u16" }
        require(maxBlockBytes in MIN_NTB16_BYTES..MAX_NTB16_BYTES) { "invalid NTB16 max size" }
        require(alignment > 0 && alignment and (alignment - 1) == 0) {
            "NCM alignment must be a positive power of two"
        }
        require(frames.size <= MAX_DATAGRAMS) { "too many NTB16 datagrams" }
        val blockLength = encodedLength(frames, alignment)
        val offsets = datagramOffsets(frames, alignment)
        require(blockLength <= maxBlockBytes) { "frames exceed configured NTB16 size" }
        require(blockLength <= MAX_NTB16_BYTES) { "NTB16 length field overflow" }

        val block = ByteArray(blockLength)
        putU32(block, 0, NTH16_SIG)
        putU16(block, 4, NTH_LENGTH)
        putU16(block, 6, sequence)
        putU16(block, 8, blockLength)
        putU16(block, 10, NTH_LENGTH)

        putU32(block, 12, NDP16_SIG)
        putU16(block, 16, NDP_HEADER_LENGTH + (frames.size + 1) * NDP_ENTRY_LENGTH)
        putU16(block, 18, 0)
        for ((index, frame) in frames.withIndex()) {
            val entry = NTH_LENGTH + NDP_HEADER_LENGTH + index * NDP_ENTRY_LENGTH
            putU16(block, entry, offsets[index])
            putU16(block, entry + 2, frame.size)
            frame.copyInto(block, offsets[index])
        }
        // A transfer that ends exactly on a USB packet boundary is read as a short packet.
        return if (block.size % USB_PACKET_SIZE == 0) block + byteArrayOf(0) else block
    }

    /** NTB16 length before the optional USB short-packet pad. */
    fun encodedLength(frames: List<ByteArray>, alignment: Int): Int {
        require(frames.isNotEmpty()) { "at least one Ethernet frame is required" }
        require(alignment > 0 && alignment and (alignment - 1) == 0) {
            "NCM alignment must be a positive power of two"
        }
        require(frames.size <= MAX_DATAGRAMS) { "too many NTB16 datagrams" }
        return datagramOffsets(frames, alignment).let { offsets ->
            val last = frames.lastIndex
            align(offsets[last] + frames[last].size, alignment)
        }
    }

    /**
     * Extracts the Ethernet frames carried by one NTB16 block.
     *
     * Wire garbage returns an empty list instead of throwing; malformed entries are skipped where
     * a bounds check fails. A step guard stops a malformed NDP pointer chain from cycling.
     */
    fun parse(block: ByteArray): List<ByteArray> = parse(block, 0, block.size)

    /** Parses one NTB16 block from [offset] without copying the containing buffer. */
    fun parse(block: ByteArray, offset: Int, length: Int): List<ByteArray> {
        require(offset >= 0 && length >= 0 && offset <= block.size - length) {
            "NTB16 range is outside the source buffer"
        }
        val end = offset + length
        if (length < NTH_LENGTH || readU32(block, offset) != NTH16_SIG) return emptyList()

        val datagrams = ArrayList<ByteArray>(1)
        var ndpOffset = offset + readU16(block, offset + 10)
        var hops = 0
        val maxHops = maxOf(1, length / 4)
        while (ndpOffset != 0 && hops < maxHops) {
            if (ndpOffset < offset || ndpOffset + 12 > end) break
            if (readU32(block, ndpOffset) and 0x00ffffff != NDP16_SIG and 0x00ffffff) break
            val ndpLength = readU16(block, ndpOffset + 4)
            val nextNdp = readU16(block, ndpOffset + 6)
            var entry = ndpOffset + 8
            val ndpEnd = minOf(ndpOffset + ndpLength, end)
            while (entry + 4 <= ndpEnd) {
                val datagramIndex = readU16(block, entry)
                val datagramLength = readU16(block, entry + 2)
                if (datagramIndex == 0 || datagramLength == 0) break
                val datagramStart = offset + datagramIndex
                val datagramEnd = datagramStart + datagramLength
                if (datagramStart >= offset && datagramStart <= end && datagramEnd <= end) {
                    datagrams.add(block.copyOfRange(datagramStart, datagramEnd))
                }
                entry += 4
            }
            ndpOffset = if (nextNdp == 0) 0 else offset + nextNdp
            hops++
        }
        return datagrams
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    private fun datagramOffsets(frames: List<ByteArray>, alignment: Int): IntArray {
        val ndpLength = NDP_HEADER_LENGTH + (frames.size + 1) * NDP_ENTRY_LENGTH
        var nextOffset = align(NTH_LENGTH + ndpLength, alignment)
        return IntArray(frames.size) { index ->
            val frame = frames[index]
            require(frame.isNotEmpty()) { "frame must not be empty" }
            require(frame.size <= MAX_DATAGRAM_BYTES) { "frame exceeds the NTB16 length field" }
            val offset = nextOffset
            nextOffset = align(nextOffset + frame.size, alignment)
            offset
        }
    }

    private fun align(value: Int, alignment: Int): Int =
        (value + alignment - 1) and -alignment

    private const val MAX_DATAGRAM_BYTES = 0xffff - DATAGRAM_INDEX
    private const val MAX_NTB16_BYTES = 0xffff
    private const val MIN_NTB16_BYTES = 28
    private const val MAX_DATAGRAMS = 255
    private const val NDP_HEADER_LENGTH = 8
    private const val NDP_ENTRY_LENGTH = 4
}
