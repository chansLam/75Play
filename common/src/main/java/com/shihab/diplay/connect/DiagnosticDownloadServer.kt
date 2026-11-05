// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.connect

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 只提供这一份诊断报告的短时 HTTP 服务。链接带一次性口令，下载成功、窗口关闭或三分钟后停止。
 */
internal class DiagnosticDownloadServer private constructor(
    private val socket: ServerSocket,
    private val body: ByteArray,
    private val fileName: String,
    private val token: String,
    val url: String,
    private val onDownloaded: () -> Unit,
) {
    @Volatile private var closed = false
    private val downloadCompleted = AtomicBoolean(false)

    init {
        Thread({
            while (!closed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    client.soTimeout = 4_000
                    val request = readHead(client.getInputStream())
                    val reply = diagnosticHttpReply(request, body, fileName, token)
                    val out = client.getOutputStream()
                    out.write(reply.header)
                    if (reply.body != null) out.write(reply.body)
                    out.flush()
                    if (reply.completedDownload && downloadCompleted.compareAndSet(false, true)) {
                        runCatching { onDownloaded() }
                        close()
                    }
                } catch (_: IOException) {
                } finally {
                    runCatching { client.close() }
                }
            }
        }, "diplay-report-http").apply { isDaemon = true; start() }
        Thread({
            val deadline = System.nanoTime() + LIFETIME_NANOS
            while (!closed && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
            close()
        }, "diplay-report-http-limit").apply { isDaemon = true; start() }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        private const val LIFETIME_NANOS = 180_000_000_000L
        private const val PREFERRED_PORT = 18765

        fun start(
            body: ByteArray,
            fileName: String,
            advertise: String? = null,
            onDownloaded: () -> Unit = {},
        ): DiagnosticDownloadServer? {
            val host = advertise ?: selectLanIpv4(localIpv4())?.hostAddress ?: return null
            val token = token()
            val socket = bind()
            val port = socket.localPort
            return DiagnosticDownloadServer(socket, body, fileName, token, "http://$host:$port/d/$token", onDownloaded)
        }

        private fun bind(): ServerSocket {
            val preferred = ServerSocket()
            try {
                preferred.bind(InetSocketAddress(PREFERRED_PORT))
                return preferred
            } catch (_: IOException) {
                runCatching { preferred.close() }
            }
            return ServerSocket(0)
        }

        private fun token(): String {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}

internal class DiagnosticHttpReply(
    val header: ByteArray,
    val body: ByteArray?,
    val completedDownload: Boolean = false,
)

internal fun diagnosticHttpReply(request: String, body: ByteArray, fileName: String, token: String): DiagnosticHttpReply {
    val line = request.lineSequence().firstOrNull()?.trim().orEmpty()
    val parts = line.split(' ')
    if (parts.size < 2) return httpStatus(400, "Bad Request")
    val method = parts[0]
    if (method != "GET" && method != "HEAD") return httpStatus(405, "Method Not Allowed")
    val path = parts[1].substringBefore('?')
    if (path != "/d/$token") return httpStatus(404, "Not Found")
    val safeName = fileName.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.ifEmpty { "report.txt" }
    val header = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("Content-Type: application/octet-stream\r\n")
        append("Content-Disposition: attachment; filename=\"")
        append(safeName)
        append("\"\r\n")
        append("Content-Length: ")
        append(body.size)
        append("\r\n")
        append("Connection: close\r\n")
        append("Cache-Control: no-store\r\n")
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)
    return DiagnosticHttpReply(header, if (method == "HEAD") null else body, completedDownload = method == "GET")
}

internal fun selectLanIpv4(addresses: List<InetAddress>): Inet4Address? =
    addresses.filterIsInstance<Inet4Address>().filter { address ->
        !address.isLoopbackAddress && !address.isLinkLocalAddress &&
            !address.isMulticastAddress && !address.isAnyLocalAddress
    }.minByOrNull { rankLanIpv4(it) }

internal fun localIpv4(): List<InetAddress> = runCatching {
    Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { nic ->
        if (!nic.isUp || nic.isLoopback) emptyList() else Collections.list(nic.inetAddresses)
    }
}.getOrDefault(emptyList())

private fun rankLanIpv4(address: Inet4Address): Int {
    val part = address.address
    val a = part[0].toInt() and 0xFF
    val b = part[1].toInt() and 0xFF
    val c = part[2].toInt() and 0xFF
    val d = part[3].toInt() and 0xFF
    return when {
        a == 192 && b == 168 && c == 43 && d == 1 -> 0
        a == 192 && b == 168 && d == 1 -> 1
        a == 192 && b == 168 -> 2
        a == 10 -> 3
        a == 172 && b in 16..31 -> 4
        else -> 5
    }
}

private fun httpStatus(code: Int, reason: String): DiagnosticHttpReply {
    val text = reason.toByteArray(Charsets.US_ASCII)
    val header = "HTTP/1.1 $code $reason\r\nContent-Type: text/plain\r\nContent-Length: ${text.size}\r\nConnection: close\r\n\r\n"
        .toByteArray(Charsets.US_ASCII)
    return DiagnosticHttpReply(header, text)
}

private fun readHead(input: InputStream): String {
    val buf = ByteArrayOutputStream()
    while (buf.size() < 8192) {
        val value = input.read()
        if (value < 0) break
        buf.write(value)
        val raw = buf.toByteArray()
        val n = raw.size
        if (n >= 4 && raw[n - 4] == '\r'.code.toByte() && raw[n - 3] == '\n'.code.toByte() &&
            raw[n - 2] == '\r'.code.toByte() && raw[n - 1] == '\n'.code.toByte()
        ) break
    }
    return buf.toByteArray().toString(Charsets.ISO_8859_1)
}
