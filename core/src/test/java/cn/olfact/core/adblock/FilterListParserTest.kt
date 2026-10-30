package cn.olfact.core.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterListParserTest {

    @Test
    fun `解析网络规则与选项`() {
        val text = """
            [Adblock Plus 2.0]
            ! 注释
            ||doubleclick.net^
            @@||example.com/ok.js
            ||tracker.com^${'$'}third-party
            ||cdn.com^${'$'}image,domain=a.com|~b.a.com
            /banner\d+\.js/
        """.trimIndent()

        val result = FilterListParser.parse(text, "test")
        assertEquals(5, result.networkRules.size)
        assertEquals(0, result.cosmeticRules.size)

        val tpl = result.networkRules.first { it.pattern.startsWith("||tracker") }
        assertEquals(true, tpl.thirdParty)

        val cdn = result.networkRules.first { it.pattern.startsWith("||cdn") }
        assertEquals(setOf(ResourceType.IMAGE), cdn.types)
        assertEquals(listOf("a.com"), cdn.domainsInclude)
        assertEquals(listOf("b.a.com"), cdn.domainsExcluded)
    }

    @Test
    fun `不支持的过程化过滤器与 scriptlet 会被丢弃`() {
        val text = """
            ##+js(set-constant, foo, bar)
            example.com##div:has-text(广告)
            example.com#?#div:has(.ad)
            example.com##.normal
        """.trimIndent()
        val result = FilterListParser.parse(text, "test")
        assertEquals(1, result.cosmeticRules.size)
        assertEquals(".normal", result.cosmeticRules[0].selector)
        assertEquals(3, result.skipped)
    }

    @Test
    fun `未知选项整条丢弃，避免误杀`() {
        val result = FilterListParser.parse("||a.com^\${'$'}unknown-option", "test")
        assertEquals(0, result.networkRules.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `badfilter 会把对应规则关掉`() {
        val text = """
            ||ads.example.com^
            ||ads.example.com^${'$'}badfilter
        """.trimIndent()
        val result = FilterListParser.parse(text, "test")
        assertEquals(0, result.networkRules.size)
    }

    @Test
    fun `hosts 文件写法转成域名锚点`() {
        val result = FilterListParser.parse("0.0.0.0 ads.hosts.test\n127.0.0.1 tracker.hosts.test", "test")
        assertEquals(2, result.networkRules.size)
        assertTrue(result.networkRules.all { it.pattern.startsWith("||") && it.pattern.endsWith("^") })
    }

    @Test
    fun `非法正则不会让解析崩溃`() {
        val result = FilterListParser.parse("/[unclosed/", "test")
        assertEquals(0, result.networkRules.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `内置资产能被解析出规则`() {
        val text = readAsset("builtin-ublite.txt")
        val result = FilterListParser.parse(text, "asset")
        assertTrue("内置清单应解析出网络规则", result.networkRules.size > 30)
        assertTrue("内置清单应解析出元素隐藏规则", result.cosmeticRules.size > 5)
        assertNotNull(result.networkRules.firstOrNull { it.pattern.contains("doubleclick") })
        assertNull(result.networkRules.firstOrNull { it.raw.startsWith("!") })
    }

    private fun readAsset(name: String): String {
        val candidates = listOf(
            java.io.File("src/main/assets/adblock/$name"),
            java.io.File("core/src/main/assets/adblock/$name")
        )
        val file = candidates.firstOrNull { it.isFile } ?: error("找不到 asset $name，工作目录=${java.io.File(".").absolutePath}")
        return file.readText()
    }
}
