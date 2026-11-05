package cn.olfact.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * 把 [PlaybackSource] 变成 Media3 的 MediaItem + DataSource。
 * UA / Referer / 自定义头都在这里落地——很多站点的 m3u8 少了 Referer 直接 403。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MediaItemFactory(private val defaultUserAgent: String? = null) {

    fun httpDataSourceFactory(source: PlaybackSource): DefaultHttpDataSource.Factory {
        val headers = LinkedHashMap<String, String>()
        source.headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank() &&
                !key.equals("User-Agent", true) && !key.equals("Referer", true)
            ) {
                headers[key] = value
            }
        }
        source.referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }

        val ua = source.userAgent
            ?: source.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: defaultUserAgent

        return DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setDefaultRequestProperties(headers)
            .apply { if (!ua.isNullOrBlank()) setUserAgent(ua) }
    }

    fun dataSourceFactory(source: PlaybackSource): DataSource.Factory = httpDataSourceFactory(source)

    fun mediaSourceFactory(source: PlaybackSource): DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(dataSourceFactory(source))

    fun build(source: PlaybackSource): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(source.title ?: source.pageTitle)
            .setArtist(source.pageUrl?.let { hostOf(it) })
            .build()
        return MediaItem.Builder()
            .setUri(source.url)
            .setMediaMetadata(metadata)
            .apply { source.resolvedMimeType()?.let { setMimeType(it) } }
            .build()
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
    }
}
