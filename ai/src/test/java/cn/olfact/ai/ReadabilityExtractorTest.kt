package cn.olfact.ai

import cn.olfact.ai.summarize.ReadabilityExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadabilityExtractorTest {

    private val html = """
        <html>
        <head>
          <title>测试文章 - 示例站</title>
          <meta property="og:site_name" content="示例站">
          <style>body{color:red}</style>
          <script>var x = "<p>假段落</p>";</script>
        </head>
        <body>
          <nav>首页 关于 登录</nav>
          <article>
            <h1>真正的标题</h1>
            <p>第一段正文，包含足够多的字符来通过长度门槛，用于测试正文抽取是否正常工作。</p>
            <p>第二段正文，同样包含足够多的字符，确保打分时会保留这一段落。</p>
            <p>短</p>
          </article>
          <footer>版权所有</footer>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun `抽取标题与正文`() {
        val article = ReadabilityExtractor.extract(html, "https://example.com/a")
        assertEquals("测试文章 - 示例站", article.title)
        assertEquals("示例站", article.siteName)
        assertTrue(article.text.contains("第一段正文"))
        assertTrue(article.text.contains("第二段正文"))
        assertFalse("script 内容不应出现", article.text.contains("假段落"))
        assertFalse("导航不应出现", article.text.contains("登录"))
        assertFalse("页脚不应出现", article.text.contains("版权所有"))
        assertFalse("过短段落应被丢弃", article.text.contains("短"))
    }

    @Test
    fun `没有 p 标签时退回全文`() {
        val article = ReadabilityExtractor.extract("<html><body><div>纯文本内容</div></body></html>")
        assertTrue(article.text.contains("纯文本内容"))
    }

    @Test
    fun `实体解码`() {
        assertEquals("A & B < C", ReadabilityExtractor.decodeEntities("A &amp; B &lt; C"))
        assertEquals("你好", ReadabilityExtractor.decodeEntities("&#20320;&#22909;"))
    }
}
