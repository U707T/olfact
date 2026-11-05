package cn.olfact.core.backup

import cn.olfact.core.script.RunAt
import cn.olfact.core.script.ScriptStore
import cn.olfact.core.script.UserScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScriptBackupSectionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(name: String) = ScriptStore(File(temp.newFolder(name), "userscripts"))

    private fun script(id: String) = UserScript(
        id = id,
        name = "脚本 $id",
        matches = listOf("*://*/*"),
        runAt = RunAt.DOCUMENT_START,
        enabled = true,
        updatedAt = 1_000L
    )

    @Test
    fun `没有 UserScript 头的脚本体也能恢复`() {
        val source = store("a")
        val plainBody = "// 内置编辑器保存的纯 JS\n(function(){console.log('hi')})();"
        source.save(script("plain"), plainBody)

        val backup = ScriptBackupSection(source).exportItems()
        assertEquals(1, backup.size)

        val target = store("b")
        val report = ScriptBackupSection(target).importItems(backup, MergeOptions())

        assertEquals(1, report.added)
        val restored = target.find("plain")
        assertNotNull("恢复后脚本应该存在", restored)
        assertEquals(plainBody, target.body("plain"))
        assertTrue(restored!!.enabled)
    }

    @Test
    fun `重复导入不会重复计数`() {
        val source = store("c")
        source.save(script("dup"), "console.log(1)")
        val section = ScriptBackupSection(source)
        val items = section.exportItems()

        val report = section.importItems(items, MergeOptions())
        assertEquals(0, report.added)
        assertEquals(1, report.skipped)
    }

    @Test
    fun `preferIncoming 时按 updatedAt 覆盖旧脚本`() {
        val source = store("d")
        source.restoreOne(script("s").copy(updatedAt = 5_000L), "new()")

        val target = store("e")
        target.restoreOne(script("s").copy(updatedAt = 1_000L), "old()")

        val report = ScriptBackupSection(target)
            .importItems(ScriptBackupSection(source).exportItems(), MergeOptions(preferIncoming = true))

        assertEquals(1, report.updated)
        assertEquals("new()", target.body("s"))
    }

    @Test
    fun `恢复后保留备份里的更新时间`() {
        val source = store("f")
        source.restoreOne(script("t").copy(updatedAt = 4_242L), "x()")

        val target = store("g")
        ScriptBackupSection(target).importItems(ScriptBackupSection(source).exportItems(), MergeOptions())

        assertEquals(4_242L, target.find("t")!!.updatedAt)
    }
}
