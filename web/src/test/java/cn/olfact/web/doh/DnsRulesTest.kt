package cn.olfact.web.doh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsRulesTest {

    @Test
    fun `精确规则优先于通配与正则`() {
        val rules = DnsRules.parse(
            """
            re:.*example\.com
            *.example.com
            ads.example.com
            """.trimIndent()
        )
        val matched = rules.match("ads.example.com")
        assertNotNull(matched)
        assertEquals(DnsRule.PRIORITY_EXACT, matched!!.priority)
        assertEquals("ads.example.com", matched.text)

        val wildcardMatched = rules.match("cdn.example.com")
        assertEquals(DnsRule.PRIORITY_WILDCARD, wildcardMatched!!.priority)
    }

    @Test
    fun `星号通配不含自身，加号通配含自身`() {
        val wildcard = DnsRules.parse("*.google.com")
        assertFalse(wildcard.contains("google.com"))
        assertTrue(wildcard.contains("www.google.com"))
        assertTrue(wildcard.contains("a.b.google.com"))

        val plus = DnsRules.parse("+.google.com")
        assertTrue(plus.contains("google.com"))
        assertTrue(plus.contains("mail.google.com"))
        assertFalse(plus.contains("notgoogle.com"))
    }

    @Test
    fun `智能分流把国内域名交给系统 DNS`() {
        val rules = DnsRules.builtin(DohMode.SMART)
        assertFalse(rules.useDoh("www.baidu.com"))
        assertFalse(rules.useDoh("www.qq.com"))
        assertTrue(rules.useDoh("www.google.com"))
        assertTrue(rules.useDoh("x.com"))
    }

    @Test
    fun `仅指定域名模式默认走系统`() {
        val rules = DnsRules.parse("+.example.com", DohMode.ONLY_LISTED)
        assertTrue(rules.useDoh("www.example.com"))
        assertFalse(rules.useDoh("www.google.com"))
    }

    @Test
    fun `仅排除域名模式默认走 DoH`() {
        val rules = DnsRules.parse("+.cn.example.com", DohMode.EXCLUDE_LISTED)
        assertFalse(rules.useDoh("www.cn.example.com"))
        assertTrue(rules.useDoh("www.baidu.com"))
    }

    @Test
    fun `会话粘性强制走 DoH`() {
        val rules = DnsRules.parse("", DohMode.SMART)
        assertFalse(rules.useDoh("www.baidu.com"))
        assertTrue(rules.useDoh("www.baidu.com", sticky = true))
    }

    @Test
    fun `IP 字面量不进 DoH`() {
        val rules = DnsRules.builtin(DohMode.SMART)
        assertFalse(rules.useDoh("127.0.0.1"))
        assertFalse(rules.useDoh("192.168.1.1"))
    }

    @Test
    fun `注释与非法正则被忽略`() {
        val rules = DnsRules.parse(
            """
            # 注释
            ! 也算注释
            // 还是注释
            re:[unclosed
            good.com
            """.trimIndent()
        )
        assertEquals(1, rules.rules.size)
        assertEquals("good.com", rules.rules.first().text)
    }

    @Test
    fun `文本导入导出可往返`() {
        val original = DnsRules.parse("a.com\n*.b.com\n+.c.com\nre:^ad\\d+\\.net$")
        val round = DnsRules.parse(original.toText())
        assertEquals(original.rules.map { it.text }, round.rules.map { it.text })
    }

    @Test
    fun `内置清单可解析且数量合理`() {
        val builtin = DnsRules.builtin()
        assertTrue(builtin.rules.size > 40)
        assertTrue(builtin.contains("ytimg.com"))
    }

    @Test
    fun `加规则与删规则`() {
        val base = DnsRules.parse("")
        val added = base.plus("https://News.Example.com/path?q=1")
        assertTrue(added.contains("news.example.com"))
        assertTrue(added.contains("cdn.news.example.com"))

        val removed = added.minus("news.example.com")
        assertFalse(removed.contains("news.example.com"))
    }

    @Test
    fun `explain 带上命中规则`() {
        val rules = DnsRules.parse("+.example.com")
        assertTrue(rules.explain("www.example.com").contains("+.example.com"))
    }

    @Test
    fun `ECS 前缀按位截断`() {
        val ecs = EcsSubnet.parse("116.24.99.7/16")
        assertNotNull(ecs)
        assertEquals(16, ecs!!.prefixLength)
        assertEquals(2, ecs.address.size)
        assertEquals(116.toByte(), ecs.address[0])
        assertEquals(24.toByte(), ecs.address[1])

        assertNull(EcsSubnet.parse("not-an-ip/16"))
        assertNull(EcsSubnet.parse("example.com/24"))
    }
}
