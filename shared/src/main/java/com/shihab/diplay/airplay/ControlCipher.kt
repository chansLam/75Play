package com.shihab.diplay.airplay

/**
 * ChaCha20-Poly1305 framing for the CarPlay control channel.
 *
 * Each frame is [2-byte little-endian ciphertext length][ciphertext][16-byte tag]; the length
 * header is the AEAD associated data and each direction uses an 8-byte little-endian nonce
 * counter plus its own key.
 */
class ControlCipher(private val readKey: ByteArray, private val writeKey: ByteArray) {
    data class Decrypted(val data: ByteArray, val rest: ByteArray)

    private var readCounter = 0L
    private var writeCounter = 0L
    private val readNonce = ByteArray(12)

    fun decrypt(buffer: ByteArray): Decrypted {
        val plain = StreamBuffer(buffer.size.coerceAtLeast(1))
        val consumed = decryptInto(buffer, buffer.size, plain)
        return Decrypted(plain.toByteArray(), buffer.copyOfRange(consumed, buffer.size))
    }

    /**
     * Opens every complete frame in the first [length] bytes of [source] and appends the plaintext
     * to [plain]. Returns how many ciphertext bytes were consumed. A tag failure leaves [plain]
     * unchanged and does not advance the read counter past the bad frame.
     */
    internal fun decryptInto(source: ByteArray, length: Int, plain: StreamBuffer): Int {
        val marked = plain.size
        var offset = 0
        try {
            while (length - offset >= HEADER_SIZE) {
                val payload = readU16Le(source, offset)
                val frameLength = HEADER_SIZE + payload + TAG_SIZE
                if (length - offset < frameLength) break
                plain.reserve(payload)
                val written = AirPlayCrypto.chachaOpenRangeInto(
                    readKey,
                    nonce(readCounter),
                    source,
                    offset,
                    HEADER_SIZE,
                    source,
                    offset + HEADER_SIZE,
                    payload + TAG_SIZE,
                    plain.bytes,
                    plain.size,
                )
                plain.commit(written)
                readCounter++
                offset += frameLength
            }
            return offset
        } catch (error: Exception) {
            plain.truncate(marked)
            throw error
        }
    }

    private fun nonce(counter: Long): ByteArray {
        var value = counter
        for (index in 4 until readNonce.size) {
            readNonce[index] = value.toByte()
            value = value ushr 8
        }
        return readNonce
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val output = ArrayList<ByteArray>()
        var offset = 0
        do {
            val chunk = plaintext.copyOfRange(offset, minOf(offset + MAX_PAYLOAD, plaintext.size))
            val header = byteArrayOf(chunk.size.toByte(), (chunk.size ushr 8).toByte())
            val ciphertextAndTag = AirPlayCrypto.chachaSeal(writeKey, AirPlayCrypto.nonce64(writeCounter), chunk, header)
            output.add(header)
            output.add(ciphertextAndTag)
            writeCounter++
            offset += MAX_PAYLOAD
        } while (offset < plaintext.size)
        return concatBytes(*output.toTypedArray())
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16
        const val MAX_PAYLOAD = 0x4000
    }
}
