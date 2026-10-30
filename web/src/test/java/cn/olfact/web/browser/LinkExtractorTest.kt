package cn.olfact.web.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkExtractorTest {

    @Test
    fun `提取 http 链接并去掉句尾标点`() {
        val candidates = LinkExtractor.extract("看这个 https://example.com/a/b?x=1，还有 https://foo.cn/p.")
        assertEquals(2, candidates.size)
        assertEquals("https://example.com/a/b?x=1", candidates[0].url)
        assertEquals("https://foo.cn/p", candidates[1].url)
    }

    @Test
    fun `www 开头补 http 前缀`() {
        val candidates = LinkExtractor.extract("访问 www.baidu.com/s?wd=x 看看")
        assertEquals(1, candidates.size)
        assertEquals("http://www.baidu.com/s?wd=x", candidates[0].url)
        assertEquals(LinkCandidate.Kind.WWW, candidates[0].kind)
    }

    @Test
    fun `裸域名也能识别`() {
        val candidates = LinkExtractor.extract("随手记一下 github.com/torvalds/linux 这个仓库")
        assertEquals(1, candidates.size)
        assertEquals("http://github.com/torvalds/linux", candidates[0].url)
        assertEquals(LinkCandidate.Kind.BARE_DOMAIN, candidates[0].kind)
    }

    @Test
    fun `邮箱里的域名不算链接`() {
        assertTrue(LinkExtractor.extract("联系我 someone@example.com").isEmpty())
    }

    @Test
    fun `同一链接不重复且最多五条`() {
        val text = (1..8).joinToString(" ") { "https://site$it.com/p" } + " https://site1.com/p"
        val candidates = LinkExtractor.extract(text)
        assertEquals(LinkExtractor.MAX_CANDIDATES, candidates.size)
        assertEquals(candidates.size, candidates.map { it.url }.distinct().size)
    }

    @Test
    fun `http 与裸域名重叠时不重复`() {
        val candidates = LinkExtractor.extract("https://example.com/path")
        assertEquals(1, candidates.size)
        assertEquals("https://example.com/path", candidates[0].url)
    }

    @Test
    fun `单个链接判定`() {
        assertEquals("https://example.com", LinkExtractor.singleUrl("example.com"))
        assertNull(LinkExtractor.singleUrl("hello world"))
        assertNull(LinkExtractor.singleUrl("看看 example.com 怎么样"))
    }

    @Test
    fun `中文标点作为边界`() {
        val candidates = LinkExtractor.extract("（https://example.com/x）后面还有字")
        assertEquals("https://example.com/x", candidates.first().url)
    }
}
