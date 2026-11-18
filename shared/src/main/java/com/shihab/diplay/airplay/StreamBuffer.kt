package com.shihab.diplay.airplay

/**
 * One growable byte buffer for a stream read loop.
 *
 * Appends copy into the existing array. Capacity doubles, so a large package is not recopied on
 * every socket read. [consume] slides a short tail to the front and drops a buffer that has grown
 * for a multi-megabyte package once it is empty again.
 */
internal class StreamBuffer(initialCapacity: Int = 4096) {
    var bytes: ByteArray = ByteArray(initialCapacity.coerceAtLeast(1))
        private set
    var size: Int = 0
        private set

    fun append(src: ByteArray, length: Int) {
        if (length <= 0) return
        reserve(length)
        src.copyInto(bytes, size, 0, length)
        size += length
    }

    /** Makes room for [additional] more bytes without changing [size]. May replace [bytes]. */
    fun reserve(additional: Int) {
        if (additional <= 0) return
        val needed = size + additional
        if (bytes.size >= needed) return
        var capacity = bytes.size
        while (capacity < needed) {
            val grown = capacity shl 1
            capacity = if (grown <= capacity) needed else grown
        }
        bytes = bytes.copyOf(capacity)
    }

    fun commit(written: Int) {
        if (written < 0 || size + written > bytes.size) {
            throw IndexOutOfBoundsException("commit $written at $size/${bytes.size}")
        }
        size += written
    }

    fun consume(count: Int) {
        if (count <= 0) return
        if (count >= size) {
            size = 0
        } else {
            bytes.copyInto(bytes, 0, count, size)
            size -= count
        }
        if (size == 0 && bytes.size > RELEASE_CAPACITY) bytes = ByteArray(RELEASE_CAPACITY)
    }

    fun truncate(to: Int) {
        size = to.coerceIn(0, size)
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private companion object {
        const val RELEASE_CAPACITY = 64 * 1024
    }
}
