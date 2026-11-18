package com.shihab.diplay.airplay

import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "CarPlayVideo Settings App" data stream (type 130, [VideoInCar.SETTINGS_CHANNEL_UUID]) the iPhone
 * opens once video in car is declared; it tears the session down when this stream is refused. Framing
 * as the iAP tunnel: ChaCha20-Poly1305 frames with a 2-byte little-endian length, carrying packages
 * with a 32-byte header (size, 12-byte type such as "sync"/"rply", 4-byte command, 8-byte sequence
 * number) and a body. Each "sync" is answered with an empty "rply", as AirPlay data streams expect;
 * the bodies are not needed to play.
 */
class VideoSettingsChannel(
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
    private val expectedPeer: InetAddress,
    private val log: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val cipher = ControlCipher(readKey, writeKey)
    private var server: ServerSocket? = null
    private var socket: Socket? = null

    fun listen(bindAddress: InetAddress): Int {
        val bound = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
        }
        server = bound
        Thread({ accept(bound) }, "diplay-video-settings").apply { isDaemon = true }.start()
        return bound.localPort
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.acceptFrom(expectedPeer) {
                log("video settings channel rejected sender outside the control session")
            }
            socket = accepted
            log("video settings channel connected")
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) log("video settings channel ended: ${error.message}")
        } finally {
            close()
        }
    }

    private fun run(sock: Socket) {
        val input = sock.getInputStream()
        val output = sock.getOutputStream()
        val buffer = ByteArray(16 * 1024)
        val sealed = StreamBuffer(16 * 1024)
        val plain = StreamBuffer(16 * 1024)
        while (!closed.get()) {
            val read = input.read(buffer)
            if (read < 0) return
            sealed.append(buffer, read)
            val consumed = cipher.decryptInto(sealed.bytes, sealed.size, plain)
            sealed.consume(consumed)
            plain.consume(handlePackages(plain.bytes, plain.size, output))
        }
    }

    private fun handlePackages(buffer: ByteArray, length: Int, output: OutputStream): Int {
        var offset = 0
        while (length - offset >= HEADER) {
            val size = u32(buffer, offset)
            if (size < HEADER || size > MAX_PACKAGE) throw IllegalStateException("bad package size $size")
            if (length - offset < size) break
            if (ascii(buffer, offset + 4, 12) == "sync") {
                val seq = ByteArray(8)
                buffer.copyInto(seq, 0, offset + 20, offset + 28)
                reply(output, seq)
            }
            offset += size
        }
        return offset
    }

    private fun reply(output: OutputStream, seq: ByteArray) {
        val header = ByteArray(HEADER)
        header[3] = HEADER.toByte()
        "rply".toByteArray(Charsets.US_ASCII).copyInto(header, 4)
        seq.copyInto(header, 20)
        output.write(cipher.encrypt(header))
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        runCatching { server?.close() }
    }

    private fun u32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun ascii(b: ByteArray, o: Int, n: Int) =
        String(b, o, n, Charsets.US_ASCII).trimEnd('\u0000')

    private companion object {
        const val HEADER = 32
        const val MAX_PACKAGE = 8 * 1024 * 1024
    }
}
