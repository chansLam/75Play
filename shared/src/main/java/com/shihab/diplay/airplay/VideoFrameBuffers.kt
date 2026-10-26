package com.shihab.diplay.airplay

/**
 * Reused plaintext buffers for screen frames. A frame's storage may be larger than the bytes that
 * are valid; callers track that length separately and return the array only after the decoder has
 * copied it.
 */
internal object VideoFrameBuffers {
    private const val MAX_KEPT = 12
    private const val MAX_CAPACITY = 1024 * 1024
    private val free = ArrayDeque<ByteArray>()
    private val owned = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ByteArray, Boolean>())

    fun take(size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        if (size > MAX_CAPACITY) return ByteArray(size)
        synchronized(free) {
            var best = -1
            for (index in free.indices) {
                val capacity = free[index].size
                if (capacity >= size && (best < 0 || capacity < free[best].size)) best = index
            }
            if (best >= 0) return free.removeAt(best)
            var capacity = 4096
            while (capacity < size && capacity < MAX_CAPACITY) capacity *= 2
            val created = ByteArray(if (capacity < size) size else capacity)
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
