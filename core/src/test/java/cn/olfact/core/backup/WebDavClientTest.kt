package cn.olfact.core.backup

import cn.olfact.core.testutil.MiniHttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * 假 WebDAV 服务器（JDK ServerSocket 实现），覆盖 PROPFIND / PUT / GET / DELETE / MKCOL。
 */
class WebDavClientTest {

    private lateinit var server: MiniHttpServer
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val seenAuth = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MiniHttpServer { req -> handle(req) }.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun handle(req: MiniHttpServer.Request): MiniHttpServer.Response {
        synchronized(seenAuth) { seenAuth.add(req.headers["authorization"] ?: "") }
        val path = req.path.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        return when (req.method.uppercase()) {
            "PROPFIND" -> {
                val depth = req.headers["depth"] ?: "1"
                if (depth == "0" && path != "/dav" && !files.containsKey(path)) {
                    MiniHttpServer.Response(404)
                } else {
                    MiniHttpServer.Response(207, multiStatus(path))
                }
            }
            "PUT" -> {
                files[path] = req.body
                MiniHttpServer.Response(201)
            }
            "GET" -> files[path]?.let { MiniHttpServer.Response(200, it) } ?: MiniHttpServer.Response(404)
            "DELETE" -> {
                files.remove(path)
                MiniHttpServer.Response(204)
            }
            "MKCOL" -> MiniHttpServer.Response(201)
            else -> MiniHttpServer.Response(405)
        }
    }

    private fun multiStatus(path: String): ByteArray {
        val children = files.keys
            .filter { it.startsWith("$path/") && !it.removePrefix("$path/").contains('/') }
            .sorted()
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<D:multistatus xmlns:D=\"DAV:\">")
        sb.append("<D:response><D:href>$path/</D:href><D:propstat><D:prop>")
        sb.append("<D:resourcetype><D:collection/></D:resourcetype></D:prop>")
        sb.append("<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
        for (child in children) {
            val size = files[child]!!.size
            sb.append("<D:response><D:href>$child</D:href><D:propstat><D:prop>")
            sb.append("<D:resourcetype/><D:getcontentlength>$size</D:getcontentlength>")
            sb.append("<D:getlastmodified>Wed, 01 Jan 2031 00:00:00 GMT</D:getlastmodified>")
            sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
        }
        sb.append("</D:multistatus>")
        return sb.toString().toByteArray()
    }

    @Test
    fun `上传 列表 下载 删除 全流程`() = runBlocking {
        val client = WebDavClient("${server.baseUrl}/dav", "user", "pass")
        assertTrue(client.mkcol("olfact"))

        val payload = "backup-bytes".toByteArray()
        assertTrue(client.put("olfact/a.zip", payload))
        assertTrue(client.put("olfact/b.zip", "second".toByteArray()))

        val entries = client.list("olfact")
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.name == "a.zip" && it.size == payload.size.toLong() })
        assertFalse(entries.any { it.isDirectory })

        assertArrayEquals(payload, client.get("olfact/a.zip"))
        assertTrue(client.exists("olfact/a.zip"))

        assertTrue(client.delete("olfact/a.zip"))
        assertEquals(1, client.list("olfact").size)

        assertTrue("必须带 Basic 头", seenAuth.all { it.startsWith("Basic ") })
    }

    @Test
    fun `列表为空时不抛异常`() = runBlocking {
        val client = WebDavClient("${server.baseUrl}/dav")
        assertEquals(0, client.list("nope").size)
        assertFalse(client.exists("nope/x.zip"))
    }
}
