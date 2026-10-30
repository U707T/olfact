package cn.olfact.ai

import cn.olfact.ai.error.AiError
import cn.olfact.ai.error.AiErrorMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AiErrorMapperTest {

    @Test
    fun `401 映射为 key 无效`() {
        val error = AiErrorMapper.fromHttp(401, """{"error":{"message":"Invalid API key"}}""")
        assertTrue(error is AiError.Unauthorized)
        assertEquals("Invalid API key", error.message)
    }

    @Test
    fun `429 可重试`() {
        val error = AiErrorMapper.fromHttp(429, null)
        assertTrue(error is AiError.RateLimited)
        assertTrue(AiErrorMapper.isRetryable(error))
    }

    @Test
    fun `500 是服务端错误且可重试`() {
        val error = AiErrorMapper.fromHttp(503, "upstream down")
        assertTrue(error is AiError.ServerError)
        assertEquals(503, (error as AiError.ServerError).code)
    }

    @Test
    fun `超时与 DNS 失败映射`() {
        assertTrue(AiErrorMapper.fromThrowable(SocketTimeoutException()) is AiError.Timeout)
        assertTrue(AiErrorMapper.fromThrowable(UnknownHostException()) is AiError.Network)
    }

    @Test
    fun `400 不可重试`() {
        val error = AiErrorMapper.fromHttp(400, """{"error":{"message":"bad request"}}""")
        assertFalse(AiErrorMapper.isRetryable(error))
        assertEquals("bad request", error.message)
    }

    @Test
    fun `message 里带转义引号也能取出来`() {
        val body = """{"error":{"message":"expected \"model\" field"}}"""
        assertEquals("expected \"model\" field", AiErrorMapper.extractMessage(body))
    }

    @Test
    fun `非 JSON 错误体截断展示`() {
        assertEquals("Bad Gateway", AiErrorMapper.extractMessage("Bad Gateway"))
    }
}
