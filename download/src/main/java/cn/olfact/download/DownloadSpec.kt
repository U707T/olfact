package cn.olfact.download

import java.util.UUID

/**
 * 一次下载的全部输入。`id` 由 URL + 文件名派生，重复添加同一个地址会命中同一条记录
 * （除非 [allowDuplicate] 为 true）。
 */
data class DownloadSpec(
    val url: String,
    val kind: DownloadKind = DownloadKind.detect(url),
    val fileName: String,
    val subDir: String = "",
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val referer: String? = null,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    /** 嗅探阶段已知的大小，-1 表示未知。 */
    val sizeHint: Long = -1L,
    /** 分块下载线程数，0 表示用队列默认值。 */
    val threadCount: Int = 0,
    /** 分块大小，0 表示用队列默认值。 */
    val chunkSizeBytes: Long = 0L,
    val allowDuplicate: Boolean = false,
) {
    val dedupKey: String
        get() = "${url}|${subDir}|${fileName}".lowercase()

    fun newId(): String = UUID.randomUUID().toString()
}
