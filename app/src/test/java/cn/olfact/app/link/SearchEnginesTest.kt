package cn.olfact.app.link

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchEnginesTest {

    @Test
    fun `网址原样补 scheme`() {
        assertEquals("https://example.com/a", SearchEngines.urlFor("example.com/a", "baidu"))
    }

    @Test
    fun `完整HTTP网址保留路径查询和片段尾部标点`() {
        val urls = listOf(
            "https://en.wikipedia.org/wiki/Function_(mathematics)",
            "https://example.com/report!",
            "https://example.com/path?query=value!",
            "https://example.com/path#section)"
        )
        urls.forEach { assertEquals(it, SearchEngines.urlFor(it, "baidu")) }
    }

    @Test
    fun `普通词走默认引擎并做 URL 编码`() {
        assertEquals(
            "https://www.baidu.com/s?wd=%E7%88%B1%E6%83%85+%E7%94%B5%E5%BD%B1",
            SearchEngines.urlFor("爱情 电影", "baidu")
        )
    }

    @Test
    fun `切换引擎`() {
        assertEquals("https://www.bing.com/search?q=hi", SearchEngines.urlFor("hi", "bing"))
    }

    @Test
    fun `自定义模板替换占位符`() {
        assertEquals(
            "https://s.example.com/?q=hello",
            SearchEngines.urlFor("hello", "custom", "https://s.example.com/?q=%s")
        )
    }

    @Test
    fun `自定义模板缺少占位符时回退到默认引擎`() {
        // 不能拼成 https://s.example.com/searchhello 这种废链接
        assertEquals(
            "https://www.baidu.com/s?wd=hello",
            SearchEngines.urlFor("hello", "custom", "https://s.example.com/search")
        )
    }

    @Test
    fun `显式搜索不会直接导航到域名`() {
        assertEquals("https://www.bing.com/search?q=example.com", SearchEngines.searchUrlFor("example.com", "bing"))
    }

    @Test
    fun `非网页或缺主机的自定义模板回退默认引擎`() {
        for (template in listOf("file:///search?q=%s", "https:///search?q=%s", "https://exa mple.com/%s")) {
            assertEquals("https://www.baidu.com/s?wd=hello", SearchEngines.searchUrlFor("hello", "custom", template))
        }
        assertEquals("http://localhost:8080/search?q=a+b", SearchEngines.searchUrlFor("a b", "custom", "http://localhost:8080/search?q=%s"))
    }

    @Test
    fun `空白输入返回 about blank`() {
        assertEquals("about:blank", SearchEngines.urlFor("   ", "baidu"))
    }

    @Test
    fun `未知引擎 id 退回第一个引擎`() {
        assertEquals(
            "https://www.baidu.com/s?wd=hi",
            SearchEngines.urlFor("hi", "nope")
        )
    }

    @Test
    fun `带空格的整句不当成网址`() {
        val url = SearchEngines.urlFor("hello world", "bing")
        assertEquals(true, url.startsWith("https://www.bing.com/search?q="))
    }

    @Test
    fun `IP 地址直接打开而不是搜索`() {
        assertEquals("http://192.168.1.1", SearchEngines.urlFor("192.168.1.1", "baidu"))
        assertEquals("http://192.168.1.1:8080/admin", SearchEngines.urlFor("192.168.1.1:8080/admin", "baidu"))
    }

    @Test
    fun `localhost 直接打开而不是搜索`() {
        assertEquals("http://localhost:3000", SearchEngines.urlFor("localhost:3000", "baidu"))
    }
}
