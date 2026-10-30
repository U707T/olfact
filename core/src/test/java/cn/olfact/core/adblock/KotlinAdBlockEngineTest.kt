package cn.olfact.core.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinAdBlockEngineTest {

    private fun engine(text: String) = KotlinAdBlockEngine(FilterListParser.parse(text, "test"))

    @Test
    fun `域名锚点命中主域与子域`() {
        val e = engine("||doubleclick.net^")
        assertTrue(e.check("https://doubleclick.net/ad.js", "https://example.com/", ResourceType.SCRIPT))
        assertTrue(e.check("https://ads.doubleclick.net/pixel.gif", "https://example.com/", ResourceType.IMAGE))
        assertFalse(e.check("https://notdoubleclick.net/ad.js", "https://example.com/", ResourceType.SCRIPT))
        assertFalse(e.check("https://doubleclick.net.evil.com/x.js", "https://example.com/", ResourceType.SCRIPT))
    }

    @Test
    fun `例外规则优先于普通规则`() {
        val e = engine(
            """
            ||example.com/ads/
            @@||example.com/ads/ok.js
            """.trimIndent()
        )
        assertTrue(e.check("https://example.com/ads/banner.png", "https://example.com/", ResourceType.IMAGE))
        assertFalse(e.check("https://example.com/ads/ok.js", "https://example.com/", ResourceType.SCRIPT))
    }

    @Test
    fun `third-party 选项按注册域判断`() {
        val e = engine("||tracker.com^\$third-party")
        assertTrue(e.check("https://tracker.com/t.js", "https://news.com/", ResourceType.SCRIPT))
        assertFalse(e.check("https://tracker.com/t.js", "https://tracker.com/", ResourceType.SCRIPT))
        // 子域同站不算三方
        assertFalse(e.check("https://tracker.com/t.js", "https://m.tracker.com/", ResourceType.SCRIPT))
    }

    @Test
    fun `类型选项只拦指定类型`() {
        val e = engine("||cdn-x.com^\$image")
        assertTrue(e.check("https://cdn-x.com/a.png", "https://site.com/", ResourceType.IMAGE))
        assertFalse(e.check("https://cdn-x.com/a.js", "https://site.com/", ResourceType.SCRIPT))
    }

    @Test
    fun `domain 限定只在指定站点生效`() {
        val e = engine("||adnet.com^\$domain=shop.com")
        assertTrue(e.check("https://adnet.com/x.js", "https://shop.com/", ResourceType.SCRIPT))
        assertFalse(e.check("https://adnet.com/x.js", "https://blog.com/", ResourceType.SCRIPT))
    }

    @Test
    fun `元素隐藏按域名生效并支持例外`() {
        val e = engine(
            """
            example.com##.ad-box
            ##.generic-ad
            example.com#@#.generic-ad
            """.trimIndent()
        )
        val selectors = e.cosmeticSelectors("https://example.com/article")
        assertTrue(selectors.contains(".ad-box"))
        assertFalse("域名例外应去掉通用规则", selectors.contains(".generic-ad"))

        val other = e.cosmeticSelectors("https://other.com/")
        assertTrue(other.contains(".generic-ad"))
        assertFalse(other.contains(".ad-box"))
    }

    @Test
    fun `正则规则可用`() {
        val e = engine("""/banner\d+\.js/""")
        assertTrue(e.check("https://x.com/banner42.js", "https://x.com/", ResourceType.SCRIPT))
        assertFalse(e.check("https://x.com/banner.js", "https://x.com/", ResourceType.SCRIPT))
    }

    @Test
    fun `规则超上限时会截断但保留例外`() {
        val sb = StringBuilder()
        for (i in 0 until 50) sb.append("||ad$i.example.com^\n")
        sb.append("@@||ad0.example.com^\n")
        val parsed = FilterListParser.parse(sb.toString(), "test")
        val e = KotlinAdBlockEngine(parsed, maxRules = 10)
        assertTrue(e.ruleCount <= 11)
        // 例外规则被优先保留：ad0 不应被拦
        assertFalse(e.check("https://ad0.example.com/a.js", "https://site.com/", ResourceType.SCRIPT))
    }
}
