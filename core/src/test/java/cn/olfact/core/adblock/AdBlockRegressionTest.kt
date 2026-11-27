package cn.olfact.core.adblock

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 审计 2026-11-24 的回归：订阅状态只落一份、白名单增删同步到设置/备份、加载不再复活已删订阅。
 */
class AdBlockRegressionTest {

    private val dirs = ArrayList<File>()

    @After
    fun tearDown() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("olfact-adblock-reg").toFile().also { dirs.add(it) }

    private fun AdBlockManager.canBlock(url: String, page: String) =
        shouldBlock(url, page, ResourceType.guess(url))

    @Test
    fun `删除订阅后重载不会复活`() {
        val dir = tempDir()
        val store = SubscriptionStore(dir)
        store.save(listOf(Subscription(id = "custom-hash", title = "自定义", url = "https://a.test/x.txt")))
        val manager = AdBlockManager(AdBlockConfig(dataDir = dir, preferNative = false))
        runBlocking { manager.refresh() }
        assertEquals(1, manager.state.value.subscriptions.size)

        manager.removeSubscription("custom-hash")
        assertEquals(0, manager.state.value.subscriptions.size)

        // 备份恢复 / 重进设置页都会重建 store 或 reload：删掉的订阅不能复活
        runBlocking { manager.reload() }
        assertEquals(0, manager.state.value.subscriptions.size)
        assertEquals(0, SubscriptionStore(dir).items.value.size)
        manager.close()
    }

    @Test
    fun `禁用订阅会重建引擎并即时生效`() = runBlocking {
        val dir = tempDir()
        val store = SubscriptionStore(dir)
        store.save(
            listOf(
                Subscription(id = "keep", title = "保留", enabled = true),
                Subscription(id = "drop", title = "关闭", enabled = true)
            )
        )
        store.writeRules("drop", "||drop.test^")
        val manager = AdBlockManager(AdBlockConfig(dataDir = dir, preferNative = false))
        manager.refresh()
        assertTrue(manager.canBlock("https://drop.test/a.js", "site.com"))

        manager.setSubscriptionEnabled("drop", false)
        // 设置项自己会启动一次后台重建；这里显式再刷一次，结果与后台那次一致（都读到 enabled=false）
        manager.refresh()
        assertFalse(manager.canBlock("https://drop.test/a.js", "site.com"))
        assertFalse(manager.state.value.subscriptions.first { it.id == "drop" }.enabled)
        manager.close()
    }

    @Test
    fun `白名单增删立即生效并出现在状态里`() {
        val dir = tempDir()
        val store = SubscriptionStore(dir)
        store.save(listOf(Subscription(id = "t", title = "t", enabled = true)))
        store.writeRules("t", "||ads.test^")
        val manager = AdBlockManager(AdBlockConfig(dataDir = dir, preferNative = false))
        runBlocking { manager.refresh() }

        assertTrue(manager.canBlock("https://ads.test/a.js", "site.com"))
        // 白名单按输入时解析出的 host 生效：粘贴整条 URL 只覆盖这一个 host
        manager.addWhitelist("https://www.site.com/path")
        assertEquals(setOf("www.site.com"), manager.state.value.whitelist)
        assertFalse(manager.canBlock("https://ads.test/a.js", "www.site.com"))
        assertTrue(manager.canBlock("https://ads.test/a.js", "site.com"))

        // *.site.com 写法覆盖主域与其子域
        manager.removeWhitelist("www.site.com")
        manager.addWhitelist("*.site.com")
        assertEquals(setOf("site.com"), manager.state.value.whitelist)
        assertFalse(manager.canBlock("https://ads.test/a.js", "site.com"))

        manager.removeWhitelist("site.com")
        assertTrue(manager.state.value.whitelist.isEmpty())
        assertTrue(manager.canBlock("https://ads.test/a.js", "site.com"))

        // 重建引擎后白名单仍然生效（白名单不随引擎重建丢失）
        manager.addWhitelist("site.com")
        runBlocking { manager.refresh() }
        assertFalse(manager.canBlock("https://ads.test/a.js", "site.com"))
        manager.close()
    }

    @Test
    fun `只把子域写进白名单时与删掉的条目组合成完整集合`() {
        val dir = tempDir()
        val manager = AdBlockManager(AdBlockConfig(dataDir = dir, preferNative = false))
        assertTrue(manager.addWhitelist("a.com"))
        assertTrue(manager.addWhitelist("b.com"))
        manager.removeWhitelist("a.com")
        assertEquals(listOf("b.com"), manager.state.value.whitelist.toList())
        manager.close()
    }

    @Test
    fun `missing index initializes built-in subscriptions`() {
        val dir = tempDir()
        val index = File(dir, "subscriptions.json")
        assertFalse(index.exists())

        val store = SubscriptionStore(dir)

        assertTrue(store.items.value.isNotEmpty())
        assertTrue(index.isFile)
    }

    @Test
    fun `index read error preserves current subscriptions`() {
        val dir = tempDir()
        val store = SubscriptionStore(dir)
        val custom = Subscription(id = "custom-keep", title = "自定义", url = "https://a.test/list.txt")
        store.save(listOf(custom))

        // A directory at the index path exists but cannot be read as a file.
        val index = File(dir, "subscriptions.json")
        assertTrue(index.delete())
        assertTrue(index.mkdir())

        assertEquals(listOf(custom), store.load())
        assertTrue(index.isDirectory)
    }

    @Test
    fun `更新摘要区分无订阅 全成功 部分失败和全失败`() {
        assertEquals(
            SubscriptionUpdater.UpdateOutcome.NOTHING_TO_UPDATE,
            SubscriptionUpdater.UpdateSummary(total = 0).outcome()
        )
        assertEquals(
            SubscriptionUpdater.UpdateOutcome.SUCCESS,
            SubscriptionUpdater.UpdateSummary(total = 2, updated = 1, notModified = 1).outcome()
        )
        assertEquals(
            SubscriptionUpdater.UpdateOutcome.PARTIAL_FAILURE,
            SubscriptionUpdater.UpdateSummary(total = 2, updated = 1, failed = 1).outcome()
        )
        assertEquals(
            SubscriptionUpdater.UpdateOutcome.ALL_FAILED,
            SubscriptionUpdater.UpdateSummary(total = 2, failed = 2).outcome()
        )
    }

    @Test
    fun `订阅更新失败的原因会保留在状态里`() = runBlocking {
        val dir = tempDir()
        val store = SubscriptionStore(dir)
        store.save(listOf(Subscription(id = "broken", title = "坏地址", url = "not a url", enabled = true)))
        val manager = AdBlockManager(AdBlockConfig(dataDir = dir, preferNative = false))
        val result = manager.updateAll(force = true)
        assertEquals(1, result.failed)
        val sub = manager.state.value.subscriptions.first { it.id == "broken" }
        assertTrue("失败原因要能显示给用户", !sub.lastError.isNullOrBlank())
        manager.close()
    }
}
