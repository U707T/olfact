package cn.olfact.core.testutil

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 测试用极简 HTTP/1.1 服务器。
 *
 * 为什么不用 `com.sun.net.httpserver`：Kotlin 编译 Android 单测时看不到 jdk.httpserver 模块，
 * 而 `java.net.ServerSocket` 在 java.base 里，任何环境都能编译运行。
 */
class MiniHttpServer(private val handler: (Request) -> Response) : Closeable {

    data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    data class Response(
        val code: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap()
    )

    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private var thread: Thread? = null

    val port: Int get() = serverSocket.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun start(): MiniHttpServer {
        val t = Thread({
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    break
                }
                runCatching { handle(socket) }
            }
        }, "mini-http")
        t.isDaemon = true
        t.start()
        thread = t
        return this
    }

    override fun close() {
        runCatching { serverSocket.close() }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 10_000
            val input = BufferedInputStream(s.getInputStream())
            val headerBytes = readHeaders(input) ?: return
            val text = String(headerBytes, Charsets.ISO_8859_1)
            val lines = text.split("\r\n").filter { it.isNotEmpty() }
            val requestLine = lines.firstOrNull() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]
            val headers = lines.drop(1)
                .filter { it.contains(':') }
                .associate {
                    it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
                }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length > 0) input.readNBytes(length) else ByteArray(0)

            val response = handler(Request(method, path, headers, body))
            val out = s.getOutputStream()
            val head = StringBuilder()
                .append("HTTP/1.1 ").append(response.code).append(' ').append(reason(response.code)).append("\r\n")
                .append("Content-Length: ").append(response.body.size).append("\r\n")
                .append("Connection: close\r\n")
            response.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            head.append("\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (response.body.isNotEmpty()) out.write(response.body)
            out.flush()
        }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        207 -> "Multi-Status"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "OK"
    }

    private fun readHeaders(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream(512)
        val tail = IntArray(4) { -1 }
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toByteArray()
            buffer.write(b)
            tail[0] = tail[1]; tail[1] = tail[2]; tail[2] = tail[3]; tail[3] = b
            if (tail[0] == '\r'.code && tail[1] == '\n'.code && tail[2] == '\r'.code && tail[3] == '\n'.code) {
                return buffer.toByteArray()
            }
            if (buffer.size() > 64 * 1024) return null
        }
    }
}
