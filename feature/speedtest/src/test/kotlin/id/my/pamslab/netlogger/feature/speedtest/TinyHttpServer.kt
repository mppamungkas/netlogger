package id.my.pamslab.netlogger.feature.speedtest

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal HTTP/1.1 server for tests (the JDK's HttpServer is not on the Android unit-test classpath). One request per
 * connection, answered with `Connection: close`. A 3xx status redirects to /portal on the same server (like an
 * operator's captive portal), so a client that follows redirects would get a 200 from there.
 */
class TinyHttpServer(private val handler: (path: String, body: InputStream, contentLength: Long) -> Pair<Int, Long>) {

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    @Volatile private var running = true

    /** Sent as a CF-RAY header with every response when set (as Cloudflare does). */
    @Volatile var cfRay: String? = null

    /** Pause after every 64 kB block of a response body: a slow link. */
    @Volatile var blockDelayMs: Long = 0

    init {
        thread(isDaemon = true) {
            while (running) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) = client.use { s ->
        try {
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            var length = 0L
            while (true) {
                val header = readLine(input) ?: return
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toLong()
            }
            val path = requestLine.split(' ')[1].substringBefore('?')
            val (status, responseBytes) = handler(path, LimitedStream(input, length), length)
            val out = s.getOutputStream()
            val location = if (status in 300..399) "Location: http://127.0.0.1:$port/portal\r\n" else ""
            val ray = cfRay?.let { "CF-RAY: $it\r\n" }.orEmpty()
            out.write("HTTP/1.1 $status X\r\n${location}${ray}Content-Length: $responseBytes\r\nConnection: close\r\n\r\n".toByteArray())
            val block = ByteArray(64 * 1024) { it.toByte() }
            var left = responseBytes
            while (left > 0) {
                val n = minOf(left, block.size.toLong()).toInt()
                out.write(block, 0, n)
                left -= n
                if (blockDelayMs > 0) { out.flush(); Thread.sleep(blockDelayMs) }
            }
            out.flush()
        } catch (_: Exception) {
            // Client cancelled mid-transfer.
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    fun stop() {
        running = false
        socket.close()
    }

    private class LimitedStream(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else input.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }
}
