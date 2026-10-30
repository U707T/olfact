package cn.olfact.player.external

/**
 * 一个外部播放器目标。`activity` 为空时用 package 的 LAUNCHER/VIEW 解析，
 * 这样即使厂商换了 Activity 名也不会失效。
 */
data class ExternalPlayerTarget(
    val packageName: String,
    val label: String,
    val activity: String? = null,
    /** 少数播放器需要把 URL 放到私有的 extra 里。 */
    val urlExtraKey: String? = null,
    /** 需要把请求头原样带过去（用 Bundle 传）。 */
    val passHeaders: Boolean = false,
)
