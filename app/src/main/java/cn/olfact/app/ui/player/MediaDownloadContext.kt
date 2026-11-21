package cn.olfact.app.ui.player

/** Request metadata needed by downloads of sniffed, referer-protected media. */
internal data class MediaDownloadContext(
    val pageUrl: String,
    val referer: String,
    val userAgent: String?,
    val mimeType: String?,
    val headers: Map<String, String>
)

internal fun mediaDownloadContext(
    sniffedPageUrl: String?,
    currentPageUrl: String,
    headers: Map<String, String>,
    mimeType: String?
): MediaDownloadContext {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.takeIf { it.isNotBlank() }

    val pageUrl = sniffedPageUrl?.takeIf { it.isNotBlank() } ?: currentPageUrl
    return MediaDownloadContext(
        pageUrl = pageUrl,
        referer = header("Referer") ?: pageUrl,
        userAgent = header("User-Agent"),
        mimeType = mimeType,
        headers = headers.toMap()
    )
}
