package cn.olfact.web.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionEngineTest {

    private val template = "https://www.bing.com/search?q=%s"

    @Test
    fun `域名与本地地址判定为 URL`() {
        assertTrue(SuggestionEngine.isUrlLike("example.com"))
        assertTrue(SuggestionEngine.isUrlLike("https://example.com/a"))
        assertTrue(SuggestionEngine.isUrlLike("localhost:8080"))
        assertTrue(SuggestionEngine.isUrlLike("192.168.1.1:9090"))
        assertFalse(SuggestionEngine.isUrlLike("今天天气怎么样"))
        assertFalse(SuggestionEngine.isUrlLike("hello world"))
    }

    @Test
    fun `补全协议头`() {
        assertEquals("https://example.com", SuggestionEngine.toUrl("example.com", template))
        assertEquals("http://localhost:8080", SuggestionEngine.toUrl("localhost:8080", template))
        assertEquals("http://10.0.0.1", SuggestionEngine.toUrl("10.0.0.1", template))
        assertEquals("https://a.com/x?y=1", SuggestionEngine.toUrl("https://a.com/x?y=1", template))
    }

    @Test
    fun `非 URL 走搜索并编码`() {
        val url = SuggestionEngine.toUrl("中文 搜索", template)
        assertTrue(url.startsWith("https://www.bing.com/search?q="))
        assertFalse(url.contains(" "))
    }

    @Test
    fun `候选顺序为输入地址、历史、文本链、搜索`() {
        val suggestions = SuggestionEngine.suggestions(
            input = "example.com",
            history = listOf("https://example.com/from-history"),
            searchTemplate = template
        )
        assertEquals(UrlBarSuggestion.Kind.INPUT_URL, suggestions.first().kind)
        assertEquals(UrlBarSuggestion.Kind.SEARCH, suggestions.last().kind)
        assertTrue(suggestions.any { it.kind == UrlBarSuggestion.Kind.HISTORY })
    }

    @Test
    fun `粘贴一段带链接的文本时给出文本链候选`() {
        val suggestions = SuggestionEngine.suggestions(
            input = "参考资料 https://example.org/doc 里写的",
            searchTemplate = template
        )
        assertTrue(suggestions.any { it.kind == UrlBarSuggestion.Kind.TEXT_LINK && it.url == "https://example.org/doc" })
    }

    @Test
    fun `候选不重复且受上限约束`() {
        val suggestions = SuggestionEngine.suggestions(
            input = "https://a.com/1 https://a.com/2 https://a.com/3 https://a.com/4 https://a.com/5 https://a.com/6",
            searchTemplate = template,
            limit = 3
        )
        assertEquals(3, suggestions.size)
        assertEquals(suggestions.size, suggestions.map { it.url }.distinct().size)
    }

    @Test
    fun `空输入没有候选`() {
        assertTrue(SuggestionEngine.suggestions("   ").isEmpty())
    }
}
