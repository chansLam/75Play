package com.shihab.diplay.airplay

/**
 * Reused RTP packets for audio that the receiver does not keep after playback.
 * A buffer may be larger than the bytes that are valid; callers track that length
 * separately and return the array only after every reader has finished with it.
 */
internal object AudioPacketBuffers {
    private const val MAX_KEPT = 48
    private const val MAX_CAPACITY = 8192
    private val free = ArrayDeque<ByteArray>()
    private val owned = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ByteArray, Boolean>())

    fun take(size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        if (size > MAX_CAPACITY) return ByteArray(size)
        synchronized(free) {
            val index = free.indexOfFirst { it.size == size }
            if (index >= 0) return free.removeAt(index)
            val created = ByteArray(size)
            owned.add(created)
            return created
        }
    }

    fun give(buffer: ByteArray) {
        if (buffer.isEmpty() || buffer.size > MAX_CAPACITY) return
        synchronized(free) {
            if (buffer !in owned || free.any { it === buffer }) return
            if (free.size >= MAX_KEPT) owned.remove(free.removeFirst())
            free.addLast(buffer)
        }
    }
}
