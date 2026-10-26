package com.shihab.diplay.airplay

import java.nio.ByteBuffer

/** JNI ChaCha20-Poly1305. Load failure leaves [available] false so callers use BouncyCastle. */
internal object NativeChaCha {
    val available: Boolean = runCatching {
        System.loadLibrary("airplay_chacha")
        true
    }.getOrDefault(false)

    @JvmStatic
    external fun nativeSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray?

    @JvmStatic
    external fun nativeOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray?

    /** Decrypts [sealedLength] bytes at [sealedOffset] of [sealed]. Null when the tag does not match. */
    @JvmStatic
    external fun nativeOpenRange(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        aadOffset: Int,
        aadLength: Int,
        sealed: ByteArray,
        sealedOffset: Int,
        sealedLength: Int,
    ): ByteArray?

    /**
     * Decrypts [inputLength] bytes at the start of direct [input] into the start of direct [output].
     * Returns the plaintext length, or -1 when the tag does not match.
     */
    @JvmStatic
    external fun nativeOpenDirect(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteBuffer,
        inputLength: Int,
        output: ByteBuffer,
    ): Int
}
