package cn.olfact.core.adblock

import cn.olfact.core.testutil.MiniHttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SubscriptionUpdaterTest {

    private lateinit var dir: File
    private lateinit var store: SubscriptionStore
    private lateinit var server: MiniHttpServer

    private val rules = """
        ! 远程清单
        ||remote-ads.example^
        example.org##.remote-banner
    """.trimIndent()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("olfact-subs").toFile()
        store = SubscriptionStore(dir)
        server = MiniHttpServer { req ->
            when (req.path) {
                "/list.txt" -> {
                    if (req.headers["if-none-match"] == "\"v1\"") {
                        MiniHttpServer.Response(304)
                    } else {
                        MiniHttpServer.Response(
                            200,
                            rules.toByteArray(),
                            mapOf("ETag" to "\"v1\"", "Content-Type" to "text/plain")
                        )
                    }
                }
                "/broken.txt" -> MiniHttpServer.Response(200, "<!DOCTYPE html><html>登录后继续</html>".toByteArray())
                else -> MiniHttpServer.Response(404)
            }
        }.start()
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun remote(id: String = "remote", path: String = "/list.txt") = Subscription(
        id = id,
        title = "远程清单",
        url = "${server.baseUrl}$path",
        enabled = true,
        order = 9
    )

    @Test
    fun `首次下载 第二次走 304`() = runBlocking {
        store.save(listOf(remote()))
        val updater = SubscriptionUpdater(store, OkHttpClient(), assetReader = null)

        val first = updater.update(store.items.value.first { it.id == "remote" })
        assertTrue("首次应下载成功：${first.error}", first.updated)
        assertEquals(SubscriptionUpdater.countRules(rules), first.ruleCount)
        assertNotNull(store.readRules("remote"))

        val second = updater.update(store.items.value.first { it.id == "remote" })
        assertTrue(second.notModified)
        assertFalse(second.updated)
        assertEquals("\"v1\"", store.items.value.first { it.id == "remote" }.etag)
    }

    @Test
    fun `HTML 错误页不会写进规则库`() = runBlocking {
        store.save(listOf(remote(id = "bad", path = "/broken.txt")))
        val updater = SubscriptionUpdater(store, OkHttpClient(), assetReader = null)
        val result = updater.update(store.items.value.first { it.id == "bad" })
        assertTrue(result.error != null)
        assertEquals(null, store.readRules("bad"))
        assertNotNull(store.items.value.first { it.id == "bad" }.lastError)
    }

    @Test
    fun `内置清单会从 asset 落地`() = runBlocking {
        val assetText = File("src/main/assets/adblock/builtin-ublite.txt").takeIf { it.isFile }
            ?.readText() ?: File("core/src/main/assets/adblock/builtin-ublite.txt").readText()
        val updater = SubscriptionUpdater(
            store,
            OkHttpClient(),
            assetReader = { name -> if (name == "builtin-ublite.txt") assetText else null }
        )
        updater.ensureBuiltinsInstalled()
        val builtin = store.items.value.first { it.id == "builtin-ublite" }
        assertTrue(builtin.ruleCount > 30)
        assertNotNull(store.readRules("builtin-ublite"))
    }
}
