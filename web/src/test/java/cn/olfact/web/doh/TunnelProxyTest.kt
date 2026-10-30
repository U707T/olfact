package cn.olfact.web.doh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 隧道端到端：本地起一个 echo 服务，隧道只允许连它，验证
 * 1) CONNECT 握手返回 200 并双向透传；2) 明文 GET 被改写成 origin-form。
 */
class TunnelProxyTest {

    private lateinit var echo: ServerSocket
    private lateinit var tunnel: TunnelProxy

    @Before
    fun setUp() {
        echo = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!echo.isClosed) {
                val client = runCatching { echo.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use { socket ->
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            output.flush()
                        }
                    }
                }
            }
        }
        tunnel = TunnelProxy(
            resolver = { listOf(InetAddress.getLoopbackAddress()) },
            allowedPorts = setOf(echo.localPort),
            connectTimeoutMs = 2_000,
            idleTimeoutMs = 5_000
        )
    }

    @After
    fun tearDown() {
        tunnel.stop()
        runCatching { echo.close() }
    }

    @Test(timeout = 15_000)
    fun `CONNECT 建隧道并双向透传`() {
        val port = tunnel.start(0)
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("CONNECT example.com:${echo.localPort} HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray())
                flush()
            }
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val statusLine = reader.readLine()
            assertTrue("实际响应：$statusLine", statusLine.contains("200"))

            socket.getOutputStream().apply {
                write("ping".toByteArray())
                flush()
            }
            val echoed = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = socket.getInputStream().read(echoed, read, 4 - read)
                if (n < 0) break
                read += n
            }
            assertEquals("ping", String(echoed, 0, read))
        }
        assertEquals(1L, tunnel.stats().connections)
    }

    @Test(timeout = 15_000)
    fun `非白名单端口被拒绝`() {
        val port = tunnel.start(0)
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("CONNECT example.com:8443 HTTP/1.1\r\n\r\n".toByteArray())
                flush()
            }
            val statusLine = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
            assertTrue("实际响应：$statusLine", statusLine.contains("403"))
        }
    }

    @Test(timeout = 15_000)
    fun `明文 GET 改写成 origin-form`() {
        val port = tunnel.start(0)
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(
                    ("GET http://example.com:${echo.localPort}/a/b?c=1 HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "Proxy-Connection: keep-alive\r\n\r\n").toByteArray()
                )
                flush()
            }
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine()
            assertEquals("GET /a/b?c=1 HTTP/1.1", requestLine)
        }
    }
}
