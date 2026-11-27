package cn.olfact.core.script

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 审计 2026-11-24 回归：编辑器保存不能丢元数据、运行阶段必须真的能注入，
 * 以及「GM 桥没接线」时页面侧要能区分，而不是把 null 当成没有值。
 */
class ScriptRegressionTest {

    private val dirs = ArrayList<File>()

    @After
    fun tearDown() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = Files.createTempDirectory("olfact-script-reg").toFile().also { dirs.add(it) }

    private val sample = """
        // ==UserScript==
        // @name         Demo
        // @namespace    olfact
        // @version      1.0
        // @match        *://*.example.com/*
        // @run-at       document-start
        // @grant        GM_setValue
        // @exclude      *://*.example.com/private/*
        // ==/UserScript==
        console.log('hi');
    """.trimIndent()

    @Test
    fun `编辑保存不改动授权 排除与匹配`() {
        val dir = tempDir()
        val store = ScriptStore(dir)
        val id = store.install(sample)!!.meta.id
        assertTrue(store.find(id)!!.hasGmApi)

        val edited = store.find(id)!!.copy(name = "Demo2", version = "1.1")
        store.save(edited, "console.log('edited');")

        val after = store.find(id)!!
        assertEquals("Demo2", after.name)
        assertEquals("1.1", after.version)
        assertEquals(listOf("GM_setValue"), after.grants)
        assertEquals(listOf("*://*.example.com/private/*"), after.excludes)
        assertEquals(RunAt.DOCUMENT_START, after.runAt)
        assertEquals(listOf("*://*.example.com/*"), after.matches)
        assertTrue(store.body(id)!!.contains("edited"))
    }

    @Test
    fun `安装同名脚本视为更新并保留启用状态`() {
        val dir = tempDir()
        val store = ScriptStore(dir)
        val id = store.install(sample)!!.meta.id
        store.setEnabled(id, false)
        val again = store.install(sample.replace("1.0", "1.1"))
        assertEquals(id, again!!.meta.id)
        assertFalse("重新安装不能把用户停用的脚本又打开", store.find(id)!!.enabled)
        assertEquals(1, store.count())
    }

    @Test
    fun `document-end 与 document-body 走同一注入阶段`() {
        val dir = tempDir()
        val store = ScriptStore(dir)
        store.install(sample)
        store.install(sample.replace("document-start", "document-end").replace("@name         Demo", "@name         DemoEnd"))
        store.install(sample.replace("@run-at       document-start", "@run-at       document-body").replace("@name         Demo", "@name         DemoBody"))
        val bridge = GmBridge(GmValueStore(dir), okhttp3.OkHttpClient(), emit = {})
        val source = ScriptSourceImpl(store, bridge)

        // 浏览内核只在 document-end 回调里注入这一阶段；document-body 没有单独时机，
        // 不并进来就会永远不执行。
        assertEquals(1, source.documentStartScripts("https://a.example.com/").size)
        assertEquals(2, source.scriptsFor("https://a.example.com/", RunAt.DOCUMENT_END).size)
        assertEquals(0, source.scriptsFor("https://a.example.com/", RunAt.DOCUMENT_IDLE).size)
        bridge.shutdown()
    }

    @Test
    fun `没有 host 的页面不注入脚本`() {
        val dir = tempDir()
        val store = ScriptStore(dir)
        store.install(sample)
        val bridge = GmBridge(GmValueStore(dir), okhttp3.OkHttpClient(), emit = {})
        val source = ScriptSourceImpl(store, bridge)
        assertEquals(0, source.scriptsFor("data:text/html,<html></html>", RunAt.DOCUMENT_START).size)
        assertEquals(0, source.scriptsFor("about:blank", RunAt.DOCUMENT_START).size)
        assertEquals(1, source.scriptsFor("https://a.example.com/", RunAt.DOCUMENT_START).size)
        bridge.shutdown()
    }

    @Test
    fun `未接原生桥时 GM 值 API 返回 undefined 而不是 null`() {
        val dir = tempDir()
        val store = ScriptStore(dir)
        val id = store.install(sample)!!.meta.id
        val bridge = GmBridge(GmValueStore(dir), okhttp3.OkHttpClient(), emit = {})
        val js = bridge.wrap(store.find(id)!!, store.body(id).orEmpty())

        // 页面里没有 __olfactGmBridge（当前实现不做 addJavascriptInterface），
        // 旧 prelude 让 GM_getValue 返回调用方默认值 —— 与「真的没存过值」无法区分。
        assertTrue(js.contains("if (!__bridge) return undefined;"))
        assertTrue(js.contains("__gmUnavailable(\"GM_setValue\")"))
        assertTrue(js.contains("__gmUnavailable(\"GM_deleteValue\")"))
        assertTrue(js.contains("if (!__bridge) return [];"))
        bridge.shutdown()
    }

    @Test
    fun `编辑器里粘贴带头的完整脚本会采用头部声明`() {
        val dir = tempDir()
        val store = ScriptStore(dir)
        // 界面新建脚本给的是「未命名脚本 / *://*/*」；粘贴整份 .user.js 后，
        // 头部才是脚本真正想匹配的页面，不能被占位元数据冲掉。
        val placeholder = cn.olfact.core.script.UserScript(
            id = "pasted-1",
            name = "未命名脚本",
            matches = listOf("*://*/*")
        )
        store.save(placeholder, sample)
        val saved = store.find("pasted-1")!!
        assertEquals("Demo", saved.name)
        assertEquals(listOf("*://*.example.com/*"), saved.matches)
        assertEquals(RunAt.DOCUMENT_START, saved.runAt)
        assertEquals(listOf("GM_setValue"), saved.grants)
        assertTrue(saved.matches("https://a.example.com/x"))
        assertFalse("头部 @exclude 必须继续生效", saved.matches("https://a.example.com/private/x"))
    }

    @Test
    fun `索引提交失败会恢复旧正文并删除未提交正文`() {
        val dir = tempDir()
        val store = ScriptStore(dir)
        val original = cn.olfact.core.script.UserScript(id = "rollback", name = "Original")
        store.save(original, "old-body()")

        val index = File(dir, "index.json")
        assertTrue(index.delete())
        assertTrue(index.mkdir())

        var existingSaveFailed = false
        try {
            store.save(original.copy(name = "Changed"), "new-body()")
        } catch (_: Exception) {
            existingSaveFailed = true
        }
        assertTrue(existingSaveFailed)
        assertEquals("old-body()", store.body(original.id))
        assertEquals("Original", store.find(original.id)?.name)

        val fresh = cn.olfact.core.script.UserScript(id = "not-committed", name = "Fresh")
        var newSaveFailed = false
        try {
            store.save(fresh, "new-script()")
        } catch (_: Exception) {
            newSaveFailed = true
        }
        assertTrue(newSaveFailed)
        assertFalse(store.bodyFile(fresh.id).exists())
        assertEquals(null, store.find(fresh.id))
    }

    @Test
    fun `头部没写的字段保持调用方设置`() {
        val dir = tempDir()
        val store = ScriptStore(dir)
        val plain = "// ==UserScript==\n// @name         OnlyName\n// ==/UserScript==\nconsole.log(1);"
        store.save(
            cn.olfact.core.script.UserScript(id = "keep-1", name = "占位", matches = listOf("https://keep.test/*"), runAt = RunAt.DOCUMENT_IDLE),
            plain
        )
        val saved = store.find("keep-1")!!
        assertEquals("OnlyName", saved.name)
        assertEquals(listOf("https://keep.test/*"), saved.matches)
        assertEquals(RunAt.DOCUMENT_IDLE, saved.runAt)
    }

    @Test
    fun `match pattern 的合法地址仍能命中`() {
        val script = UserScript(id = "1", name = "t", matches = listOf("<all_urls>"))
        assertTrue(script.matches("https://example.com/a"))
        assertNotNull(UrlPatterns.matchesGlob("*://*/*", "https://x.test/a"))
        assertTrue(UserScript(id = "2", name = "t").matches("http://a.test/"))
    }
}
