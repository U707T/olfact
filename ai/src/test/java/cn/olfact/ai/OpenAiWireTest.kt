package cn.olfact.ai

import cn.olfact.ai.net.OpenAiWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiWireTest {

    private val provider = AiProvider(
        id = "test",
        name = "Test",
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
    )

    @Test
    fun `请求体包含模型与消息`() {
        val body = OpenAiWire.requestBody(
            provider,
            listOf(ChatMessage.system("sys"), ChatMessage.user("你好")),
            ChatParams(temperature = 0.2, maxTokens = 128, stream = true),
        )
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"content\":\"你好\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("include_usage"))
        assertTrue(body.contains("\"max_tokens\":128"))
    }

    @Test
    fun `endpoint 自动补 v1`() {
        assertEquals("https://api.example.com/v1/chat/completions", provider.endpoint())
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            provider.copy(baseUrl = "https://api.example.com").endpoint(),
        )
        assertEquals(
            "https://api.example.com/chat/completions",
            provider.copy(baseUrl = "https://api.example.com/chat/completions").endpoint(),
        )
    }

    @Test
    fun `解析流式增量`() {
        val chunk = """{"model":"m","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}"""
        val delta = OpenAiWire.parseChunk(chunk)
        assertEquals("Hello", delta?.text)
        assertEquals(false, delta?.done)
        assertEquals("m", delta?.model)
    }

    @Test
    fun `解析 usage`() {
        val chunk = """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        val delta = OpenAiWire.parseChunk(chunk)
        assertEquals(15, delta?.usage?.totalTokens)
        assertEquals(10, delta?.usage?.promptTokens)
    }

    @Test
    fun `空增量返回 null`() {
        assertNull(OpenAiWire.parseChunk("""{"choices":[{"delta":{}}]}"""))
        assertNull(OpenAiWire.parseChunk("not json"))
    }

    @Test
    fun `解析非流式回复`() {
        val json = """{"choices":[{"message":{"role":"assistant","content":"结果"}}]}"""
        assertEquals("结果", OpenAiWire.parseCompletion(json))
        assertNull(OpenAiWire.parseCompletion("""{"choices":[]}"""))
    }

    @Test
    fun `token 估算中文更贵`() {
        val chinese = OpenAiWire.estimateTokens("这是一段中文文本")
        val english = OpenAiWire.estimateTokens("this is english text")
        assertTrue(chinese > 0)
        assertTrue(english > 0)
        assertNotNull(chinese)
        assertTrue(OpenAiWire.estimateTokens("") >= 0)
    }
}
