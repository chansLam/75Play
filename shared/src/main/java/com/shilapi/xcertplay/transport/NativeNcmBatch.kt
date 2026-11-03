package com.shilapi.xcertplay.transport

/**
 * Chooses the exact frame prefix handed to the native NTB16 encoder.
 *
 * Keeping this decision in Kotlin makes the capacity and oversized-frame policy testable without
 * a device, while the byte copy, alignment and URB submission stay off the managed heap.
 */
internal object NativeNcmBatch {
    internal data class Selection(
        val acceptedCount: Int,
        val blockBytes: Int,
    ) {
        val hasSubmission: Boolean get() = acceptedCount > 0
    }

    fun select(frames: List<ByteArray>, limits: NcmNtbLimits): Selection {
        val selected = NcmTxBatch.take(frames, limits)
        if (selected.isEmpty()) return Selection(acceptedCount = 0, blockBytes = 0)
        return Selection(
            acceptedCount = selected.size,
            blockBytes = Ntb16Codec.encodedLength(selected, limits.alignment),
        )
    }
}
