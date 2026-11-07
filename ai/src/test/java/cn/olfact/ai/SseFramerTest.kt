package cn.olfact.ai

import cn.olfact.ai.net.SseFramer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseFramerTest {

    @Test
    fun `单行 data 事件`() {
        val framer = SseFramer()
        assertNull(framer.feedLine("data: {\"a\":1}"))
        assertEquals("{\"a\":1}", framer.feedLine("")?.data)
    }

    @Test
    fun `多行 data 用换行拼接`() {
        val framer = SseFramer()
        framer.feedLine("data: line1")
        framer.feedLine("data: line2")
        assertEquals("line1\nline2", framer.feedLine("")?.data)
    }

    @Test
    fun `注释与空行被忽略`() {
        val framer = SseFramer()
        assertNull(framer.feedLine(": keep-alive"))
        assertNull(framer.feedLine(""))
        assertNull(framer.end())
    }

    @Test
    fun `event 与 id 字段`() {
        val framer = SseFramer()
        framer.feedLine("event: message")
        framer.feedLine("id: 42")
        framer.feedLine("data: hi")
        val event = framer.feedLine("")
        assertEquals("message", event?.event)
        assertEquals("42", event?.id)
        assertEquals("hi", event?.data)
    }

    @Test
    fun `流结束时吐出未闭合事件`() {
        val framer = SseFramer()
        framer.feedLine("data: [DONE]")
        assertEquals("[DONE]", framer.end()?.data)
        assertNull(framer.end())
    }

    @Test
    fun `兼容 CRLF`() {
        val framer = SseFramer()
        framer.feedLine("data: x\r")
        assertEquals("x", framer.feedLine("\r")?.data)
    }

    @Test
    fun `无冒号的字段行不会崩`() {
        val framer = SseFramer()
        assertNull(framer.feedLine("data"))
        assertEquals("", framer.feedLine("")?.data)
    }

    @Test
    fun `lastEventId 跨事件保留，空 id 才重置`() {
        val framer = SseFramer()
        framer.feedLine("id: 42")
        framer.feedLine("data: a")
        assertEquals("42", framer.feedLine("")?.id)
        // 下一个事件没有再发 id：事件自身不带 id，但续传锚点仍是 42
        framer.feedLine("data: b")
        assertEquals("b", framer.feedLine("")?.data)
        assertEquals("42", framer.lastEventId)

        // 规范里的「发一个空 id」＝重置
        framer.feedLine("id: ")
        framer.feedLine("data: c")
        framer.feedLine("")
        assertNull(framer.lastEventId)
    }
}
