package cn.olfact.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UserScriptTest {

    private val sample = """
        // ==UserScript==
        // @name         Olfact 去广告
        // @namespace    https://olfact.cn/scripts
        // @version      1.2.3
        // @description  去掉示例站的广告位
        // @match        *://*.example.com/*
        // @match        https://news.example.org/*
        // @exclude      *://*.example.com/private/*
        // @run-at       document-start
        // @grant        GM_addStyle
        // @grant        GM_setValue
        // ==/UserScript==

        document.querySelectorAll('.ad').forEach(function (el) { el.remove(); });
    """.trimIndent()

    @Test
    fun `解析元数据与脚本体`() {
        val parsed = UserScriptParser.parse(sample, "https://greasyfork.org/x.user.js")
        assertNotNull(parsed)
        val meta = parsed!!.meta
        assertEquals("Olfact 去广告", meta.name)
        assertEquals("https://olfact.cn/scripts", meta.namespace)
        assertEquals("1.2.3", meta.version)
        assertEquals(RunAt.DOCUMENT_START, meta.runAt)
        assertEquals(2, meta.matches.size)
        assertEquals(listOf("GM_addStyle", "GM_setValue"), meta.grants)
        assertTrue(meta.hasGmApi)
        assertTrue(parsed.body.contains("querySelectorAll"))
    }

    @Test
    fun `match 与 exclude 语义`() {
        val meta = UserScriptParser.parse(sample)!!.meta
        assertTrue(meta.matches("https://www.example.com/a"))
        assertTrue(meta.matches("http://example.com/"))
        assertTrue(meta.matches("https://news.example.org/2024"))
        assertFalse(meta.matches("https://www.example.com/private/x"))
        assertFalse(meta.matches("https://other.com/"))
    }

    @Test
    fun `通配 include 生效`() {
        val script = UserScript(
            id = "1",
            name = "t",
            includes = listOf("*://example.net/*"),
            excludes = listOf("*://example.net/skip*")
        )
        assertTrue(script.matches("https://example.net/a"))
        assertFalse(script.matches("https://example.net/skip/1"))
        assertFalse(script.matches("https://example.org/a"))
    }

    @Test
    fun `同名脚本 id 稳定`() {
        val a = UserScriptParser.parse(sample)!!.meta.id
        val b = UserScriptParser.parse(sample)!!.meta.id
        assertEquals(a, b)
    }

    @Test
    fun `裸脚本也能安装`() {
        val parsed = UserScriptParser.parse("document.body.style.background='#000';", "https://example.com/x.user.js")
        assertNotNull(parsed)
        assertEquals("example.com", parsed!!.meta.name)
        assertEquals(1, parsed.warnings.size)
    }

    @Test
    fun `未知 run-at 落回 document-end`() {
        val text = """
            // ==UserScript==
            // @name   x
            // @run-at weird-stage
            // ==/UserScript==
            console.log(1);
        """.trimIndent()
        assertEquals(RunAt.DOCUMENT_END, UserScriptParser.parse(text)!!.meta.runAt)
    }

    @Test
    fun `仓库安装 停用 删除 全流程`() {
        val dir = Files.createTempDirectory("olfact-scripts").toFile()
        val store = ScriptStore(dir)
        val installed = store.install(sample, "https://greasyfork.org/x.user.js")
        assertNotNull(installed)
        val id = installed!!.meta.id

        assertEquals(1, store.count())
        assertTrue(store.body(id)!!.contains("querySelectorAll"))
        assertEquals(1, store.enabledFor("https://www.example.com/a", RunAt.DOCUMENT_START).size)
        assertEquals(0, store.enabledFor("https://www.example.com/a", RunAt.DOCUMENT_END).size)

        store.setEnabled(id, false)
        assertEquals(0, store.enabledFor("https://www.example.com/a", RunAt.DOCUMENT_START).size)

        // 重新打开一个新 store：索引与脚本体都要能读回来
        val reopened = ScriptStore(dir)
        assertEquals(1, reopened.count())
        assertFalse(reopened.list()[0].enabled)

        assertTrue(reopened.delete(id))
        assertEquals(0, ScriptStore(dir).count())
        dir.deleteRecursively()
    }

    @Test
    fun `脚本源只返回 document-start 命中项`() {
        val dir = Files.createTempDirectory("olfact-scripts2").toFile()
        val store = ScriptStore(dir)
        store.install(sample)
        val bridge = GmBridge(GmValueStore(dir), okhttp3.OkHttpClient(), emit = {})
        val source = ScriptSourceImpl(store, bridge)

        val js = source.documentStartScripts("https://www.example.com/a")
        assertEquals(1, js.size)
        assertTrue(js[0].contains("GM_getValue"))
        assertTrue(js[0].trimEnd().endsWith("})();"))
        assertEquals(0, source.documentStartScripts("https://other.com/").size)

        dir.deleteRecursively()
        bridge.shutdown()
    }
}
