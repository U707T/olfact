package cn.olfact.app.data

import cn.olfact.app.ui.player.SnifferFeed
import cn.olfact.core.api.MediaItem
import cn.olfact.core.api.MediaSniffer

/** One sink per renderer. Late requests from a destroyed renderer cannot repopulate its feed. */
class SnifferSink(private val tabId: Long) : MediaSniffer {
    private var closed = false

    @Synchronized
    override fun onMediaDetected(item: MediaItem) {
        if (!closed && AppSettings.get(K.SNIFF_ENABLED)) SnifferFeed.add(tabId, item)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        SnifferFeed.clearTab(tabId)
    }
}
