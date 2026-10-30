package cn.olfact.web.browser

import android.webkit.WebView

/**
 * 长按命中结果。B5 用它弹原版那套菜单（复制链接 / 新标签打开 / 下载图片 / 嗅探媒体）。
 */
data class LongPressTarget(
    val kind: Kind,
    val url: String? = null,
    val text: String? = null,
    val imageUrl: String? = null
) {
    enum class Kind { LINK, IMAGE, IMAGE_LINK, TEXT, OTHER }

    companion object {
        fun from(result: WebView.HitTestResult?): LongPressTarget? {
            if (result == null) return null
            val extra = result.extra
            return when (result.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.ANCHOR_TYPE ->
                    LongPressTarget(Kind.LINK, url = extra)
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, WebView.HitTestResult.IMAGE_ANCHOR_TYPE ->
                    LongPressTarget(Kind.IMAGE_LINK, url = extra, imageUrl = extra)
                WebView.HitTestResult.IMAGE_TYPE ->
                    LongPressTarget(Kind.IMAGE, imageUrl = extra)
                WebView.HitTestResult.EDIT_TEXT_TYPE ->
                    LongPressTarget(Kind.TEXT, text = extra)
                else ->
                    if (extra.isNullOrBlank()) null else LongPressTarget(Kind.OTHER, text = extra)
            }
        }
    }
}

/** 下载请求，交给 download 模块。 */
data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long,
    val pageUrl: String?
)
