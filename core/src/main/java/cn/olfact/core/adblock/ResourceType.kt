package cn.olfact.core.adblock

/**
 * 请求类型。数值与 Rust 侧 `adblock::request::ResourceType` 的映射在 [NativeAdBlockEngine] 里统一维护，
 * 不要随手改枚举顺序。
 */
enum class ResourceType(val code: Int, val optionName: String) {
    DOCUMENT(0, "document"),
    SUBDOCUMENT(1, "subdocument"),
    STYLESHEET(2, "stylesheet"),
    SCRIPT(3, "script"),
    IMAGE(4, "image"),
    FONT(5, "font"),
    MEDIA(6, "media"),
    OBJECT(7, "object"),
    XMLHTTPREQUEST(8, "xmlhttprequest"),
    PING(9, "ping"),
    WEBSOCKET(10, "websocket"),
    OTHER(11, "other");

    companion object {
        private val byOption: Map<String, ResourceType> = entries.associateBy { it.optionName }
        private val byCode: Map<Int, ResourceType> = entries.associateBy { it.code }

        fun fromOption(name: String): ResourceType? = byOption[name]

        fun fromCode(code: Int): ResourceType = byCode[code] ?: OTHER

        /** WebView 只给 URL/headers，没有类型时按后缀猜一个，够用且不会误伤。 */
        fun guess(url: String): ResourceType {
            val path = url.substringBefore('#').substringBefore('?').lowercase()
            val ext = path.substringAfterLast('.', "")
            return when {
                path.endsWith(".m3u8") || path.endsWith(".mpd") -> MEDIA
                ext in IMAGE_EXT -> IMAGE
                ext in SCRIPT_EXT -> SCRIPT
                ext in STYLE_EXT -> STYLESHEET
                ext in FONT_EXT -> FONT
                ext in MEDIA_EXT -> MEDIA
                ext == "json" || ext == "xml" -> XMLHTTPREQUEST
                else -> OTHER
            }
        }

        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "avif")
        private val SCRIPT_EXT = setOf("js", "mjs", "jsx", "ts")
        private val STYLE_EXT = setOf("css")
        private val FONT_EXT = setOf("woff", "woff2", "ttf", "otf", "eot")
        private val MEDIA_EXT = setOf("mp4", "m4v", "webm", "ts", "mp3", "aac", "m4a", "flv", "mkv")
    }
}
