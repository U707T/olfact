package cn.olfact.ai.error

/**
 * 面向用户的错误：message 直接可以显示在卡片上，不给用户看堆栈。
 */
sealed class AiError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class Unauthorized(message: String = "API Key 无效或已过期，请到 AI 设置里重新填写") : AiError(message)

    class RateLimited(val retryAfterSeconds: Int? = null, message: String = "请求太频繁，稍后再试") :
        AiError(message)

    class Timeout(message: String = "请求超时，请检查网络或换一个供应商") : AiError(message)

    class ContextTooLong(message: String = "内容太长，超出模型上下文，已自动截断后再试") : AiError(message)

    class ServerError(val code: Int, message: String) : AiError(message)

    class Network(message: String = "网络不可用，请检查连接") : AiError(message)

    class ProviderNotConfigured(message: String = "还没有配置 AI 供应商") : AiError(message)

    class Canceled(message: String = "已取消") : AiError(message)

    class Unknown(message: String, cause: Throwable? = null) : AiError(message, cause)
}
