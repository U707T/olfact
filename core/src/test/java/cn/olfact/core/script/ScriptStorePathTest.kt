package cn.olfact.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScriptStorePathTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): ScriptStore = ScriptStore(File(temp.newFolder(), "userscripts"))

    private fun script(id: String) = UserScript(id = id, name = "s", matches = listOf("*://*/*"))

    @Test
    fun `带路径分隔符的 id 不会写脚本目录外`() {
        val root = temp.newFolder("root")
        val store = ScriptStore(File(root, "userscripts"))
        store.save(script("../../evil"), "console.log(1)")

        val scriptsDir = File(root, "userscripts/scripts").canonicalFile
        val written = scriptsDir.listFiles()?.map { it.canonicalFile }.orEmpty()
        assertTrue("目录里应该有落盘的脚本", written.isNotEmpty())
        written.forEach {
            assertTrue("写到了目录外：${it.path}", it.path.startsWith(scriptsDir.path + File.separator))
        }
        // 读路径用同一个 id，仍然能读回脚本体
        assertEquals("console.log(1)", store.body("../../evil"))
    }

    @Test
    fun `正常 id 文件名不变`() {
        val store = store()
        store.save(script("abc-123_x"), "x()")
        assertNotNull(store.find("abc-123_x"))
        assertEquals("x()", store.body("abc-123_x"))
    }

    @Test
    fun `空 id 有兜底文件名`() {
        val store = store()
        store.save(script(""), "y()")
        assertEquals("y()", store.body(""))
    }
}
