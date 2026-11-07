package cn.olfact.ai.net

/**
 * 手写 SSE 分帧（RFC 8895 的常用子集）。
 * 不直接用 okhttp-sse 的原因：需要逐行可测、且要兼容国内一些代理发的
 * 非标准帧（缺 event、多行 data、空行结尾）。
 */
data class SseEvent(
    val data: String,
    val event: String? = null,
    val id: String? = null,
)

class SseFramer {

    private val data = StringBuilder()
    private var event: String? = null
    private var id: String? = null
    private var hasData = false

    /**
     * 跨事件保留的 last event id（SSE 规范里 `id:` 不清空，除非显式发一个空 id）。
     * 现在不重连用不到，但断流续传要靠它做 `Last-Event-ID`；之前每个事件都清掉，
     * 真要做续传时会以为服务端没发 id。
     */
    @Volatile
    var lastEventId: String? = null
        private set

    /** 喂一行（不含换行）。返回完整事件，或 null 表示还在攒。 */
    fun feedLine(rawLine: String): SseEvent? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null // 注释/心跳

        val colon = line.indexOf(':')
        val field = if (colon >= 0) line.substring(0, colon) else line
        var value = if (colon >= 0) line.substring(colon + 1) else ""
        if (value.startsWith(" ")) value = value.substring(1)

        when (field) {
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "event" -> event = value
            "id" -> {
                // 空 id 是规范里的「重置」；非空则同时更新跨事件保留值。
                id = value
                lastEventId = value.ifBlank { null }
            }
            "retry" -> Unit
            else -> Unit
        }
        return null
    }

    /** 流结束时把最后一个未闭合事件吐出来。 */
    fun end(): SseEvent? = flush()

    private fun flush(): SseEvent? {
        if (!hasData && event == null && id == null) return null
        val result = SseEvent(data.toString(), event, id)
        data.setLength(0)
        event = null
        id = null
        hasData = false
        return result
    }
}
