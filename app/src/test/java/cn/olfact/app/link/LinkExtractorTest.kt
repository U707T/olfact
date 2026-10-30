package cn.olfact.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkExtractorTest {

    @Test
    fun `一段话里的三个链接都能提出来`() {
        val text = "看这三个 https://a.com/x 和 www.b.cn/y 还有 c.org/z?id=1 都挺有意思"
        val links = LinkExtractor.extract(text)
        assertEquals(3, links.size)
        assertEquals("https://a.com/x", links[0].url)
        assertEquals("https://www.b.cn/y", links[1].url)
        assertEquals("https://c.org/z?id=1", links[2].url)
    }

    @Test
    fun `重复链接只留一条`() {
        val links = LinkExtractor.extract("https://a.com/1 和 https://a.com/1")
        assertEquals(1, links.size)
    }

    @Test
    fun `版本号不是链接`() {
        assertTrue(LinkExtractor.extract("版本 1.2.3 发布了，请看 readme").isEmpty())
    }

    @Test
    fun `邮箱不会退化成域名`() {
        assertTrue(LinkExtractor.extract("联系 test@example.com 谢谢").isEmpty())
    }

    @Test
    fun `文件名不会被当成域名`() {
        assertTrue(LinkExtractor.extract("打开 index.html 和 demo.mp4").isEmpty())
    }

    @Test
    fun `中文标点会被去掉`() {
        val links = LinkExtractor.extract("地址是 https://a.com/x。")
        assertEquals("https://a.com/x", links.single().url)
    }

    @Test
    fun `最多五条`() {
        val text = (1..9).joinToString(" ") { "https://e$it.com/p" }
        assertEquals(5, LinkExtractor.extract(text).size)
    }

    @Test
    fun `地址栏判断`() {
        assertTrue(LinkExtractor.looksLikeUrl("example.com"))
        assertTrue(LinkExtractor.looksLikeUrl("https://example.com/a"))
        assertFalse(LinkExtractor.looksLikeUrl("怎么 用 正则"))
        assertFalse(LinkExtractor.looksLikeUrl("a b"))
    }

    @Test
    fun `搜索词会走模板`() {
        val url = SearchEngines.urlFor("嗅觉 浏览器", "bing")
        assertTrue(url.startsWith("https://www.bing.com/search?q="))
    }
}
