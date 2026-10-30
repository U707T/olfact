package cn.olfact.ai.error

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object AiErrorMapper {

    fun fromHttp(code: Int, body: String?): AiError {
        val serverMessage = extractMessage(body)
        return when (code) {
            401, 403 -> AiError.Unauthorized(serverMessage ?: "API Key 无效或没有权限")
            404 -> AiError.Unknown(serverMessage ?: "接口地址不对（404），检查 baseUrl 是否包含 /v1")
            408 -> AiError.Timeout()
            413 -> AiError.ContextTooLong()
            429 -> AiError.RateLimited(retryAfterSeconds = null, message = serverMessage ?: "请求太频繁，稍后再试")
            500, 502, 503, 504 -> AiError.ServerError(code, serverMessage ?: "服务端错误（$code）")
            else -> if (code in 400..499) {
                AiError.Unknown(serverMessage ?: "请求被拒绝（$code）")
            } else {
                AiError.ServerError(code, serverMessage ?: "服务端错误（$code）")
            }
        }
    }

    fun fromThrowable(t: Throwable): AiError = when (t) {
        is AiError -> t
        is SocketTimeoutException, is InterruptedIOException -> AiError.Timeout()
        is UnknownHostException -> AiError.Network("域名解析失败，请检查网络或 DoH 设置")
        is IOException -> AiError.Network(t.message ?: "网络异常")
        else -> AiError.Unknown(t.message ?: t.javaClass.simpleName, t)
    }

    fun isRetryable(error: Throwable): Boolean = when (error) {
        is AiError.RateLimited, is AiError.Timeout, is AiError.ServerError, is AiError.Network -> true
        else -> false
    }

    /** 从 OpenAI 风格的 `{"error":{"message":"..."}}` 里拿可读文案。 */
    fun extractMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val marker = "\"message\""
        val index = body.indexOf(marker)
        if (index < 0) return body.take(200).takeIf { it.isNotBlank() }
        val start = body.indexOf('"', index + marker.length + 1)
        if (start < 0) return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                sb.append(body[i + 1])
                i += 2
                continue
            }
            if (c == '"') break
            sb.append(c)
            i++
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }
}
