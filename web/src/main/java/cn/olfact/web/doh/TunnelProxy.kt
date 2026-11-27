package cn.olfact.web.doh

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
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
    private val onEvent: (String) -> Unit = {},
    private val connectAttempt: (Socket, InetAddress, Int, Int) -> Unit = { socket, address, port, timeoutMs ->
        socket.connect(InetSocketAddress(address, port), timeoutMs)
    },
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

    /** 转发线程单独一个池：处理线程会阻塞等它，共用一个池在满负载时会自锁。 */
    private var copyPool: ExecutorService? = null

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
        // 每条隧道至少占用 1 个处理线程 + 1 个上行线程；32 的固定池在真实网页
        // （同域 6 条连接、多域并发）下很容易排满，表现就是「开了 DoH 后网页变慢」。
        pool = Executors.newFixedThreadPool(POOL_SIZE) { runnable ->
            Thread(runnable, "olfact-tunnel").apply { isDaemon = true }
        }
        copyPool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "olfact-tunnel-copy").apply { isDaemon = true }
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
        copyPool?.shutdownNow()
        copyPool = null
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
            sockets.add(client)
            if (executor == null || !running.get()) {
                sockets.remove(client)
                runCatching { client.close() }
                break
            }
            try {
                executor.execute { handle(client) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                sockets.remove(client)
                runCatching { client.close() }
            }
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
                "GET", "POST", "HEAD", "PUT", "PATCH", "DELETE", "OPTIONS" -> handlePlain(client, input, header)
                else -> respond(client, 405, "Method Not Allowed")
            }
        } catch (e: Exception) {
            failures.incrementAndGet()
            onEvent("隧道连接异常: ${e.message}")
        } finally {
            active.decrementAndGet()
            runCatching { client.close() }
            sockets.remove(client)
        }
    }

    private fun handleConnect(client: Socket, clientIn: InputStream, authority: String) {
        val uri = runCatching { URI("http://$authority") }.getOrNull()
        val host = uri?.host?.removeSurrounding("[", "]")
        val port = uri?.port?.takeIf { it != -1 } ?: 443
        if (uri == null || host.isNullOrEmpty() || uri.rawUserInfo != null || !uri.rawPath.isNullOrEmpty() ||
            uri.rawQuery != null || uri.rawFragment != null
        ) {
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
        try {
            upstream.use {
                client.getOutputStream().apply {
                    write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                pipeBoth(client, clientIn, upstream)
            }
        } finally {
            sockets.remove(upstream)
        }
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
        try {
            upstream.use {
                upstream.getOutputStream().apply {
                    write(rewritten.toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                pipeBoth(client, clientIn, upstream)
            }
        } finally {
            sockets.remove(upstream)
        }
    }

    private fun connectUpstream(host: String, port: Int): Socket? {
        return try {
            val addresses = resolver(host)
            if (addresses.isEmpty()) return null
            var lastError: Exception? = null
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectTimeoutMs.coerceAtLeast(1).toLong())
            val candidates = interleaveFamilies(addresses)
            for ((index, address) in candidates.withIndex()) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) break
                val remainingCandidates = candidates.size - index
                val attemptNanos = (remainingNanos / remainingCandidates).coerceAtLeast(1L)
                val attemptMs = TimeUnit.NANOSECONDS.toMillis(attemptNanos + 999_999L)
                    .coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                // 每个地址一个 socket；将剩余期限分配给尚未尝试的地址，
                // 避免第一个黑洞 IP 吃掉总预算，阻断后续候选。
                val socket = Socket()
                sockets.add(socket)
                try {
                    if (!running.get()) {
                        socket.close()
                        sockets.remove(socket)
                        return null
                    }
                    socket.tcpNoDelay = true
                    connectAttempt(socket, address, port, attemptMs)
                    socket.soTimeout = idleTimeoutMs
                    return socket
                } catch (e: Exception) {
                    lastError = e
                    runCatching { socket.close() }
                    sockets.remove(socket)
                }
            }
            if (lastError != null) onEvent("连接 $host:$port 失败: ${lastError.message}")
            null
        } catch (e: Exception) {
            onEvent("解析 $host 失败: ${e.message}")
            null
        }
    }

    /** 在保留每族解析顺序的同时交错 IPv4/IPv6，最后追加未知族地址。 */
    private fun interleaveFamilies(addresses: List<InetAddress>): List<InetAddress> {
        val ipv4 = ArrayDeque(addresses.filter { it.address.size == 4 })
        val ipv6 = ArrayDeque(addresses.filter { it.address.size == 16 })
        val other = addresses.filter { it.address.size != 4 && it.address.size != 16 }
        val firstFamily = addresses.firstOrNull { it.address.size == 4 || it.address.size == 16 }
            ?.address?.size
        val result = ArrayList<InetAddress>(addresses.size)
        var preferV4 = firstFamily != 16
        while (ipv4.isNotEmpty() || ipv6.isNotEmpty()) {
            val preferred = if (preferV4) ipv4 else ipv6
            val alternate = if (preferV4) ipv6 else ipv4
            result += if (preferred.isNotEmpty()) preferred.removeFirst() else alternate.removeFirst()
            preferV4 = !preferV4
        }
        result += other
        return result
    }

    private fun pipeBoth(client: Socket, clientIn: InputStream, upstream: Socket) {
        val executor = copyPool ?: return
        val upstreamIn = BufferedInputStream(upstream.getInputStream())
        val clientOut = client.getOutputStream()
        val upstreamOut = upstream.getOutputStream()
        val lastActivityNanos = AtomicLong(System.nanoTime())
        val pollMs = readPollIntervalMs()
        // SO_TIMEOUT 只用于周期性检查；是否空闲由共享的双向活动时间决定。
        client.soTimeout = pollMs
        upstream.soTimeout = pollMs
        val upload = executor.submit {
            copy(clientIn, upstreamOut, upstream, client, lastActivityNanos, isUpload = true)
        }
        try {
            copy(upstreamIn, clientOut, client, upstream, lastActivityNanos, isUpload = false)
            // 下行结束后等待上行正常结束；copy 自己负责检测共同空闲和关闭两端。
            upload.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            upload.cancel(true)
        } catch (_: Exception) {
            upload.cancel(true)
        } finally {
            runCatching { upstream.close() }
            sockets.remove(upstream)
        }
    }

    private fun copy(
        from: InputStream,
        to: OutputStream,
        target: Socket,
        peer: Socket,
        lastActivityNanos: AtomicLong,
        isUpload: Boolean,
    ) {
        val buffer = ByteArray(16 * 1024)
        val idleNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs.coerceAtLeast(1).toLong())
        try {
            while (true) {
                val read = try {
                    from.read(buffer)
                } catch (_: SocketTimeoutException) {
                    if (System.nanoTime() - lastActivityNanos.get() >= idleNanos) {
                        // 只有双向都没有传输超过期限才终止整条隧道。
                        runCatching { target.close() }
                        runCatching { peer.close() }
                        break
                    }
                    continue
                }
                if (read < 0) break
                lastActivityNanos.set(System.nanoTime())
                to.write(buffer, 0, read)
                to.flush()
                if (isUpload) bytesUp.addAndGet(read.toLong()) else bytesDown.addAndGet(read.toLong())
            }
        } catch (_: Exception) {
            // 对端关闭 / stop，正常收尾
        } finally {
            runCatching { target.shutdownOutput() }
        }
    }

    private fun readPollIntervalMs(): Int =
        (idleTimeoutMs.coerceAtLeast(1) / 4).coerceIn(1, 500)

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
        if (matched != 4) throw java.io.IOException("代理请求头过长")
        return buffer.toString("ISO-8859-1")
    }

    companion object {
        private const val MAX_HEADER_BYTES = 16 * 1024

        /** 每条隧道约 2 个线程（处理 + 上行），留足网页并发所需。 */
        private const val POOL_SIZE = 64
    }
}
