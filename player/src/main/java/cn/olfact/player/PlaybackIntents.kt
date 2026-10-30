package cn.olfact.player

import android.content.Context
import android.content.Intent

/**
 * 跨模块唯一入口：其它模块拿不到 [PlaybackSource] 的构造细节也没关系，
 * 只要调用 [play] 或 [intent] 即可。
 */
object PlaybackIntents {

    const val EXTRA_URL = "cn.olfact.player.extra.URL"
    const val EXTRA_KIND = "cn.olfact.player.extra.KIND"
    const val EXTRA_TITLE = "cn.olfact.player.extra.TITLE"
    const val EXTRA_PAGE_URL = "cn.olfact.player.extra.PAGE_URL"
    const val EXTRA_PAGE_TITLE = "cn.olfact.player.extra.PAGE_TITLE"
    const val EXTRA_MIME = "cn.olfact.player.extra.MIME"
    const val EXTRA_USER_AGENT = "cn.olfact.player.extra.UA"
    const val EXTRA_REFERER = "cn.olfact.player.extra.REFERER"
    const val EXTRA_HEADER_KEYS = "cn.olfact.player.extra.HEADER_KEYS"
    const val EXTRA_HEADER_VALUES = "cn.olfact.player.extra.HEADER_VALUES"
    const val EXTRA_LIVE = "cn.olfact.player.extra.LIVE"
    const val EXTRA_PRIVATE = "cn.olfact.player.extra.PRIVATE"
    const val EXTRA_DURATION = "cn.olfact.player.extra.DURATION"
    const val EXTRA_START_POSITION = "cn.olfact.player.extra.START_POSITION"
    const val EXTRA_SPEED = "cn.olfact.player.extra.SPEED"
    const val EXTRA_SKIP_INTRO = "cn.olfact.player.extra.SKIP_INTRO"
    const val EXTRA_SKIP_OUTRO = "cn.olfact.player.extra.SKIP_OUTRO"
    const val EXTRA_LOOP = "cn.olfact.player.extra.LOOP"
    const val EXTRA_AUTO_PIP = "cn.olfact.player.extra.AUTO_PIP"
    const val EXTRA_REMEMBER_SPEED = "cn.olfact.player.extra.REMEMBER_SPEED"
    const val EXTRA_REMEMBER_POSITION = "cn.olfact.player.extra.REMEMBER_POSITION"

    fun intent(
        context: Context,
        source: PlaybackSource,
        options: PlaybackOptions = PlaybackOptions.DEFAULT,
    ): Intent = Intent(context, VideoPlayerActivity::class.java)
        .putExtra(EXTRA_URL, source.url)
        .putExtra(EXTRA_KIND, source.kind.wireName)
        .putExtra(EXTRA_TITLE, source.title)
        .putExtra(EXTRA_PAGE_URL, source.pageUrl)
        .putExtra(EXTRA_PAGE_TITLE, source.pageTitle)
        .putExtra(EXTRA_MIME, source.mimeType)
        .putExtra(EXTRA_USER_AGENT, source.userAgent)
        .putExtra(EXTRA_REFERER, source.referer)
        .putExtra(EXTRA_HEADER_KEYS, source.headers.keys.toTypedArray())
        .putExtra(EXTRA_HEADER_VALUES, source.headers.values.toTypedArray())
        .putExtra(EXTRA_LIVE, source.live)
        .putExtra(EXTRA_PRIVATE, source.isPrivate)
        .putExtra(EXTRA_DURATION, source.durationHintMs)
        .putExtra(EXTRA_START_POSITION, options.startPositionMs)
        .putExtra(EXTRA_SPEED, options.speed)
        .putExtra(EXTRA_SKIP_INTRO, options.skipIntroMs)
        .putExtra(EXTRA_SKIP_OUTRO, options.skipOutroMs)
        .putExtra(EXTRA_LOOP, options.loop)
        .putExtra(EXTRA_AUTO_PIP, options.autoPipOnLeave)
        .putExtra(EXTRA_REMEMBER_SPEED, options.rememberSpeed)
        .putExtra(EXTRA_REMEMBER_POSITION, options.rememberPosition)

    fun play(context: Context, source: PlaybackSource, options: PlaybackOptions = PlaybackOptions.DEFAULT) {
        context.startActivity(intent(context, source, options))
    }

    fun readSource(intent: Intent): PlaybackSource? {
        val url = intent.getStringExtra(EXTRA_URL) ?: return null
        val keys = intent.getStringArrayExtra(EXTRA_HEADER_KEYS).orEmpty()
        val values = intent.getStringArrayExtra(EXTRA_HEADER_VALUES).orEmpty()
        val headers = buildMap {
            keys.forEachIndexed { index, key -> values.getOrNull(index)?.let { put(key, it) } }
        }
        return PlaybackSource(
            url = url,
            kind = MediaKind.fromWire(intent.getStringExtra(EXTRA_KIND)),
            title = intent.getStringExtra(EXTRA_TITLE),
            pageUrl = intent.getStringExtra(EXTRA_PAGE_URL),
            pageTitle = intent.getStringExtra(EXTRA_PAGE_TITLE),
            mimeType = intent.getStringExtra(EXTRA_MIME),
            headers = headers,
            userAgent = intent.getStringExtra(EXTRA_USER_AGENT),
            referer = intent.getStringExtra(EXTRA_REFERER),
            durationHintMs = intent.getLongExtra(EXTRA_DURATION, PlaybackSource.TIME_UNSET),
            live = intent.getBooleanExtra(EXTRA_LIVE, false),
            isPrivate = intent.getBooleanExtra(EXTRA_PRIVATE, false),
        )
    }

    fun readOptions(intent: Intent): PlaybackOptions = PlaybackOptions(
        startPositionMs = intent.getLongExtra(EXTRA_START_POSITION, 0L),
        speed = intent.getFloatExtra(EXTRA_SPEED, 1f),
        skipIntroMs = intent.getLongExtra(EXTRA_SKIP_INTRO, 0L),
        skipOutroMs = intent.getLongExtra(EXTRA_SKIP_OUTRO, 0L),
        loop = intent.getBooleanExtra(EXTRA_LOOP, false),
        autoPipOnLeave = intent.getBooleanExtra(EXTRA_AUTO_PIP, true),
        rememberSpeed = intent.getBooleanExtra(EXTRA_REMEMBER_SPEED, true),
        rememberPosition = intent.getBooleanExtra(EXTRA_REMEMBER_POSITION, true),
    )
}
