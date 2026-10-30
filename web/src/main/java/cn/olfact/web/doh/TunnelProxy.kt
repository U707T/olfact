package cn.olfact.web.doh

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * L2 本地隧道（TECH-PLAN §4.1）。
 *
 * 只做两件事：把 `CONNECT host:443` 用 DoH 解析后建 TCP 隧道，或者转发明文
 * `GET http://host/...`。**不碰 TLS 内容，不做 MITM，不装 CA**。
 * 因此 HTTP/3（UDP 443）会绕过这里，属于已知边界。
 */
class TunnelProxy(
    private val resolver: (String) -> List<InetAddress>,
    private val connectTimeoutMs: Int = 6_000,
    private val idleTimeoutMs: Int = 30_000,
    private val allowedPorts: Set<Int> = setOf(80, 443),
    private val onEvent: (String) -> Unit = {}
) {

    data class Stats(
        val connections: Long,
        val activeConnections: Int,
        val bytesUp: Long,
        val bytesDown: Long,
        val failures: Long
    )

    private val running = AtomicBoolean(false)
    private val connections = AtomicLong()
    private val active = AtomicInteger()
    private val bytesUp = AtomicLong()
    private val bytesDown = AtomicLong()
    private val failures = AtomicLong()

    private val sockets: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ExecutorService? = null

    val port: Int get() = server?.localPort ?: -1

    fun isRunning(): Boolean =
        running.get() && server?.isClosed == false && acceptThread?.isAlive == true

    /** @return 实际监听端口，失败抛 IOException */
    @Throws(java.io.IOException::class)
    fun start(preferredPort: Int = 0): Int {
        if (isRunning()) return port
        val serverSocket = ServerSocket()
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferredPort), 64)
        server = serverSocket
        pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "olfact-tunnel").apply { isDaemon = true }
        }
        running.set(true)
        acceptThread = Thread({ acceptLoop(serverSocket) }, "olfact-tunnel-accept").apply {
            isDaemon = true
            start()
        }
        onEvent("隧道已启动 127.0.0.1:${serverSocket.localPort}")
        return serverSocket.localPort
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        pool?.shutdownNow()
        pool = null
        onEvent("隧道已停止")
    }

    fun stats(): Stats = Stats(
        connections = connections.get(),
        activeConnections = active.get(),
        bytesUp = bytesUp.get(),
        bytesDown = bytesDown.get(),
        failures = failures.get()
    )

    /** 自检：能否连上本地监听端口。 */
    fun healthCheck(timeoutMs: Int = 800): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    private fun acceptLoop(serverSocket: ServerSocket) {
        while (running.get() && !serverSocket.isClosed) {
            val client = try {
                serverSocket.accept()
            } catch (e: Exception) {
                if (running.get()) onEvent("accept 失败: ${e.message}")
                break
            }
            val executor = pool
            if (executor == null) {
                runCatching { client.close() }
                break
            }
            executor.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        connections.incrementAndGet()
        active.incrementAndGet()
        try {
            client.soTimeout = idleTimeoutMs
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val header = readHeader(input) ?: return
            val requestLine = header.lineSequence().firstOrNull()?.trim().orEmpty()
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respond(client, 400, "Bad Request")
                return
            }
            when (parts[0].uppercase()) {
                "CONNECT" -> handleConnect(client, input, parts[1])
                "GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS" -> handlePlain(client, input, header)
                else -> respond(client, 405, "Method Not Allowed")
            }
        } catch (e: Exception) {
            failures.incrementAndGet()
            onEvent("隧道连接异常: ${e.message}")
        } finally {
            active.decrementAndGet()
            runCatching { client.close() }
        }
    }

    private fun handleConnect(client: Socket, clientIn: InputStream, authority: String) {
        val host = authority.substringBeforeLast(':', authority)
        val port = authority.substringAfterLast(':', "443").toIntOrNull() ?: 443
        if (host.isEmpty()) {
            respond(client, 400, "Bad Authority")
            return
        }
        if (port !in allowedPorts) {
            failures.incrementAndGet()
            respond(client, 403, "Port Not Allowed")
            return
        }
        val upstream = connectUpstream(host, port)
        if (upstream == null) {
            failures.incrementAndGet()
            respond(client, 502, "Bad Gateway")
            return
        }
        client.getOutputStream().apply {
            write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        pipeBoth(client, clientIn, upstream)
    }

    private fun handlePlain(client: Socket, clientIn: InputStream, header: String) {
        val lines = header.split("\r\n")
        val parts = lines.first().split(' ')
        val target = parts.getOrNull(1).orEmpty()
        if (!target.startsWith("http://", ignoreCase = true)) {
            respond(client, 400, "Only plain http is proxied")
            return
        }
        val uri = runCatching { URI(target) }.getOrNull()
        val host = uri?.host
        if (uri == null || host.isNullOrEmpty()) {
            respond(client, 400, "Bad URI")
            return
        }
        val port = if (uri.port > 0) uri.port else 80
        if (port !in allowedPorts) {
            failures.incrementAndGet()
            respond(client, 403, "Port Not Allowed")
            return
        }
        val upstream = connectUpstream(host, port)
        if (upstream == null) {
            failures.incrementAndGet()
            respond(client, 502, "Bad Gateway")
            return
        }
        val originForm = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val rewritten = buildString {
            append(parts[0]).append(' ').append(originForm).append(' ')
                .append(parts.getOrElse(2) { "HTTP/1.1" }).append("\r\n")
            lines.drop(1).forEach { line ->
                if (line.isEmpty()) return@forEach
                val name = line.substringBefore(':').trim().lowercase()
                if (name == "proxy-connection" || name == "connection" || name == "proxy-authorization") return@forEach
                append(line).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        upstream.getOutputStream().apply {
            write(rewritten.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        pipeBoth(client, clientIn, upstream)
    }

    private fun connectUpstream(host: String, port: Int): Socket? {
        return try {
            val addresses = resolver(host)
            if (addresses.isEmpty()) return null
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.soTimeout = idleTimeoutMs
            var lastError: Exception? = null
            for (address in addresses.take(2)) {
                try {
                    socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
                    sockets.add(socket)
                    return socket
                } catch (e: Exception) {
                    lastError = e
                }
            }
            runCatching { socket.close() }
            if (lastError != null) onEvent("连接 $host:$port 失败: ${lastError.message}")
            null
        } catch (e: Exception) {
            onEvent("解析 $host 失败: ${e.message}")
            null
        }
    }

    private fun pipeBoth(client: Socket, clientIn: InputStream, upstream: Socket) {
        val executor = pool ?: return
        val upstreamIn = BufferedInputStream(upstream.getInputStream())
        val clientOut = client.getOutputStream()
        val upstreamOut = upstream.getOutputStream()
        val upload = executor.submit { copy(clientIn, upstreamOut, upstream, true) }
        val download = executor.submit { copy(upstreamIn, clientOut, client, false) }
        runCatching { upload.get(10, TimeUnit.MINUTES) }
        runCatching { download.get(10, TimeUnit.MINUTES) }
        runCatching { upstream.close() }
        sockets.remove(upstream)
    }

    private fun copy(from: InputStream, to: OutputStream, target: Socket, isUpload: Boolean) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                to.write(buffer, 0, read)
                to.flush()
                if (isUpload) bytesUp.addAndGet(read.toLong()) else bytesDown.addAndGet(read.toLong())
            }
        } catch (_: Exception) {
            // 对端关闭 / 超时，正常路径
        } finally {
            runCatching { target.shutdownOutput() }
        }
    }

    private fun respond(client: Socket, code: Int, message: String) {
        runCatching {
            val body = "$code $message"
            client.getOutputStream().apply {
                write(
                    ("HTTP/1.1 $code $message\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\n" +
                        "Connection: close\r\n\r\n$body").toByteArray(Charsets.UTF_8)
                )
                flush()
            }
        }
    }

    private fun readHeader(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(512)
        var matched = 0
        while (buffer.size() < MAX_HEADER_BYTES) {
            val byte = input.read()
            if (byte < 0) return null
            buffer.write(byte)
            // 状态机匹配 CRLFCRLF：0 -> \r -> 1 -> \n -> 2 -> \r -> 3 -> \n -> 4
            matched = when {
                byte == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                byte == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                byte == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        return buffer.toString("ISO-8859-1")
    }

    companion object {
        private const val MAX_HEADER_BYTES = 16 * 1024
    }
}
