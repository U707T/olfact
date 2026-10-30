package cn.olfact.core.adblock

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AdBlockManagerTest {

    private lateinit var dir: File
    private lateinit var manager: AdBlockManager

    private val rules = """
        ||doubleclick.net^
        example.com##.ad-banner
        @@||example.com/ok.js
    """.trimIndent()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("olfact-adblock").toFile()
        val store = SubscriptionStore(dir)
        store.save(listOf(Subscription(id = "test", title = "测试", enabled = true)))
        store.writeRules("test", rules)
        manager = AdBlockManager(
            AdBlockConfig(
                dataDir = dir,
                assetReader = null,
                preferNative = false
            )
        )
        runBlocking { manager.refresh() }
    }

    @After
    fun tearDown() {
        manager.close()
        dir.deleteRecursively()
    }

    @Test
    fun `shouldBlock 与 cosmeticCss 走同一份规则`() {
        assertTrue(manager.shouldBlock("https://doubleclick.net/x.js", "example.com"))
        assertFalse(manager.shouldBlock("https://example.com/ok.js", "example.com"))
        assertTrue(manager.cosmeticCss("example.com").contains(".ad-banner{display:none!important}"))
        assertEquals("", manager.cosmeticCss("other.com").let { if (it.contains("ad-banner")) "命中" else "" })
    }

    @Test
    fun `白名单优先于订阅`() {
        manager.addWhitelist("https://example.com/path")
        assertFalse(manager.shouldBlock("https://doubleclick.net/x.js", "example.com"))
        assertTrue(manager.isWhitelisted("m.example.com"))
        manager.removeWhitelist("example.com")
        assertTrue(manager.shouldBlock("https://doubleclick.net/x.js", "example.com"))
    }

    @Test
    fun `block 会累计计数，shouldBlock 不计数`() {
        val before = manager.state.value.stats.today
        manager.shouldBlock("https://doubleclick.net/a.js", "example.com")
        assertEquals(before, manager.state.value.stats.today)

        manager.block("https://doubleclick.net/b.js", "example.com")
        manager.flush()
        assertEquals(before + 1, manager.statsTodayForTest())
    }

    @Test
    fun `关闭总开关后全部放行`() {
        manager.setEnabled(false)
        assertFalse(manager.block("https://doubleclick.net/x.js", "example.com"))
        assertEquals("", manager.cosmeticCss("example.com"))
        manager.setEnabled(true)
    }

    @Test
    fun `非 http 协议不参与匹配`() {
        assertFalse(manager.shouldBlock("data:text/html,doubleclick.net", "example.com"))
        assertFalse(manager.shouldBlock("about:blank", "example.com"))
    }

    @Test
    fun `状态里能看到引擎类型与规则数`() {
        val state = manager.state.value
        assertEquals(EngineKind.KOTLIN, state.engineKind)
        assertTrue(state.ruleCount >= 2)
        assertEquals(1, state.subscriptions.size)
    }

    @Test
    fun `reload 能从磁盘读到外部写入的订阅`() {
        val other = SubscriptionStore(dir)
        other.save(other.items.value + Subscription(id = "added", title = "新增", enabled = true))
        other.writeRules("added", "||added-later.test^")
        assertFalse(manager.shouldBlock("https://added-later.test/x.js", "site.com"))

        runBlocking { manager.reload() }
        assertTrue(manager.shouldBlock("https://added-later.test/x.js", "site.com"))
        assertEquals(2, manager.state.value.subscriptions.count { it.id == "added" || it.id == "test" })
    }

    @Test
    fun `so 缺失时自动降级且拦截不中断`() {
        val dir2 = Files.createTempDirectory("olfact-adblock-fallback").toFile()
        val store = SubscriptionStore(dir2)
        store.save(listOf(Subscription(id = "t", title = "t", enabled = true)))
        store.writeRules("t", "||degrade.test^")

        val fallback = AdBlockManager(AdBlockConfig(dataDir = dir2, preferNative = true))
        runBlocking { fallback.refresh() }
        // 单测环境没有 libadblock_ffi.so，必然走 Kotlin 引擎
        assertEquals(EngineKind.KOTLIN, fallback.state.value.engineKind)
        assertFalse(fallback.state.value.nativeAvailable)
        assertTrue(fallback.shouldBlock("https://degrade.test/a.js", "site.com"))
        fallback.close()
        dir2.deleteRecursively()
    }

    private fun AdBlockManager.statsTodayForTest(): Long {
        // state 里的 stats 有节流，直接读盘更可靠
        manager.flush()
        return BlockStats(File(dir, "stats.json")).snapshot().today
    }
}
