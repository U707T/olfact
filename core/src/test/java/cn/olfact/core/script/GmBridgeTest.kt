package cn.olfact.core.script

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GmBridgeTest {

    private lateinit var dir: File
    private lateinit var bridge: GmBridge
    private val emitted = mutableListOf<String>()

    private val script = UserScript(id = "abcdef123456", name = "test", version = "1.0")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("olfact-gm").toFile()
        bridge = GmBridge(
            values = GmValueStore(dir),
            http = OkHttpClient(),
            emit = { synchronized(emitted) { emitted.add(it) } }
        )
    }

    @After
    fun tearDown() {
        bridge.shutdown()
        dir.deleteRecursively()
    }

    private fun args(vararg items: JsonElement): String = JsonArray(items.toList()).toString()

    @Test
    fun `get set delete keys 走 JSON 值`() {
        val id = script.id
        val value = JsonPrimitive("你好")
        assertEquals("", bridge.dispatch(id, "", "set", args(JsonPrimitive("k"), value)))
        assertEquals(value.toString(), bridge.dispatch(id, "", "get", args(JsonPrimitive("k"))))
        assertEquals("null", bridge.dispatch(id, "", "get", args(JsonPrimitive("missing"))))
        assertEquals("[\"k\"]", bridge.dispatch(id, "", "keys", "[]"))

        bridge.dispatch(id, "", "delete", args(JsonPrimitive("k")))
        assertEquals("null", bridge.dispatch(id, "", "get", args(JsonPrimitive("k"))))
    }

    @Test
    fun `值会跨实例持久化`() {
        bridge.dispatch(script.id, "", "set", args(JsonPrimitive("n"), JsonPrimitive(42)))
        val reopened = GmValueStore(dir)
        assertEquals("42", reopened.get(script.id, "n"))
    }

    @Test
    fun `prelude 暴露 GM API 且 IIFE 闭合`() {
        val js = bridge.wrap(script, "GM_log('hi');")
        assertTrue(js.contains("GM_getValue"))
        assertTrue(js.contains("GM_setValue"))
        assertTrue(js.contains("GM_xmlhttpRequest"))
        assertTrue(js.contains("GM_addStyle"))
        assertTrue(js.contains("GM_info"))
        assertTrue(js.contains("\"abcdef123456\""))
        assertTrue(js.contains("GM_log('hi');"))
        assertTrue(js.trimEnd().endsWith("})();"))
    }

    @Test
    fun `xhr 异步回传结果`() {
        val server = cn.olfact.core.testutil.MiniHttpServer { req ->
            if (req.path == "/hello") cn.olfact.core.testutil.MiniHttpServer.Response(
                200,
                "pong".toByteArray(),
                mapOf("Content-Type" to "text/plain")
            ) else cn.olfact.core.testutil.MiniHttpServer.Response(404)
        }.start()
        try {
            val latch = CountDownLatch(1)
            val detailsJson = """{"method":"GET","url":"${server.baseUrl}/hello"}"""
            bridge.dispatch(script.id, "c1", "xhr", args(kotlinx.serialization.json.Json.parseToJsonElement(detailsJson)))
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                val hit = synchronized(emitted) { emitted.firstOrNull { it.contains("pong") } }
                if (hit != null) {
                    latch.countDown()
                    break
                }
                Thread.sleep(50)
            }
            assertTrue("xhr 结果应回传到页面", latch.await(1, TimeUnit.SECONDS))
            val js = synchronized(emitted) { emitted.first { it.contains("pong") } }
            assertTrue(js.contains("__olfactGmResolve"))
            assertTrue(js.contains("status"))
        } finally {
            server.close()
        }
    }
}
