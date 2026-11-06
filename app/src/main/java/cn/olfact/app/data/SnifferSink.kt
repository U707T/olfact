package cn.olfact.app.data

import cn.olfact.app.ui.player.SnifferFeed

/** 嗅探回调落点：B2 在 `shouldInterceptRequest` 命中媒体时调用。 */
object SnifferSink : cn.olfact.core.api.MediaSniffer {

    /** 小于这个体积的媒体多半是广告/预览片，默认过滤（可在设置里关）。 */
    private const val SMALL_MEDIA_BYTES = 256L * 1024

    override fun onMediaDetected(item: cn.olfact.core.api.MediaItem) {
        if (!AppSettings.get(K.SNIFF_ENABLED)) return
        if (AppSettings.get(K.SNIFF_FILTER_SMALL)) {
            val length = item.contentLength
            if (length != null && length in 1..SMALL_MEDIA_BYTES) return
        }
        SnifferFeed.add(item)
    }

    override fun currentPageTitle(): String = BrowserSession.current()?.title.orEmpty()

    override fun currentPageUrl(): String = BrowserSession.current()?.url.orEmpty()
}
