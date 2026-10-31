package cn.olfact.app.data

import android.content.Context
import cn.olfact.core.Core
import cn.olfact.core.api.SettingsKey
import cn.olfact.core.api.SettingsRepository
import kotlinx.coroutines.flow.Flow

/**
 * 全部设置项。命名规则 `<模块>.<分组>.<项>`，与各模块共享。
 *
 * 这里只放「界面与通用」的项；DoH/拦截/下载/AI 的专用项放在对应面板里，
 * 但仍然走同一份 [SettingsRepository]，保证备份时能一次性导出。
 */
object K {
    // 外观
    val NIGHT_MODE = SettingsKey("ui.night_mode", 0)            // 0 跟随系统 / 1 开 / 2 关
    val FONT_SCALE = SettingsKey("ui.font_scale", 100)
    val BOTTOM_STYLE = SettingsKey("ui.bottom_style", "pill")    // pill / icon
    val EYE_CARE = SettingsKey("ui.eye_care", false)

    // 基本
    val RESTORE_TABS = SettingsKey("ui.restore_tabs", "manual")  // always / manual / never
    val BACK_NO_RELOAD = SettingsKey("ui.back_no_reload", true)
    val SUGGEST_SOURCE = SettingsKey("ui.suggest_source", "baidu")
    val SEARCH_CLIPBOARD = SettingsKey("ui.search_clipboard", true)
    val SEARCH_ENGINE = SettingsKey("ui.search_engine", "baidu")
    val SEARCH_CUSTOM = SettingsKey("ui.search_custom", "")
    val ALLOW_OPEN_APP = SettingsKey("ui.allow_open_app", true)
    val ALLOW_LOCATION = SettingsKey("ui.allow_location", false)
    val HISTORY_LIMIT = SettingsKey("ui.history_limit", "1000")
    val CONTINUE_PLAY = SettingsKey("ui.continue_play", true)
    val FORCE_NEW_WINDOW = SettingsKey("ui.force_new_window", false)

    // 手势/外观细节
    val FULLSCREEN_SCROLL = SettingsKey("ui.fullscreen_scroll", true)
    val SWIPE_BACK = SettingsKey("ui.swipe_back", true)
    val DOUBLE_TAP_ZOOM = SettingsKey("web.double_tap_zoom", false)
    val TEXT_ZOOM = SettingsKey("web.text_zoom", 100)
    val NIGHT_WEB = SettingsKey("web.night", "auto")
    val NO_IMAGE = SettingsKey("web.no_image", false)
    val DESKTOP_MODE = SettingsKey("web.desktop", false)
    val PAGE_ZOOM = SettingsKey("web.page_zoom", 100)
    val JS_ENABLED = SettingsKey("web.js", true)

    // 下载：key 与 download 模块共用一份定义，避免设置页与队列各存一套
    val DL_THREADS = cn.olfact.download.DownloadKeys.THREADS
    val DL_CONCURRENT = cn.olfact.download.DownloadKeys.CONCURRENT
    val DL_WIFI_ONLY = cn.olfact.download.DownloadKeys.WIFI_ONLY
    val DL_NOTIFY = cn.olfact.download.DownloadKeys.NOTIFY
    val DL_AUTO_RENAME = cn.olfact.download.DownloadKeys.AUTO_RENAME
    val DL_M3U8_MERGE = cn.olfact.download.DownloadKeys.M3U8_MERGE

    // 嗅探/播放
    val SNIFF_ENABLED = SettingsKey("sniff.enabled", true)
    val SNIFF_TIP = SettingsKey("sniff.tip", true)
    val SNIFF_FILTER_SMALL = SettingsKey("sniff.filter_small", true)
    val PLAYER_SPEED = SettingsKey("player.speed", "1.0")
    val PLAYER_SKIP_INTRO = SettingsKey("player.skip_intro", true)
    val PLAYER_GRAVITY_ROTATE = SettingsKey("player.gravity_rotate", true)
    val PLAYER_PIP = SettingsKey("player.pip", true)

    // 广告拦截
    val AD_ENABLED = SettingsKey("ad.enabled", true)
    val AD_COSMETIC = SettingsKey("ad.cosmetic", true)
    val AD_COUNT_TOTAL = SettingsKey("ad.count_total", 0L)
    val AD_COUNT_TODAY = SettingsKey("ad.count_today", 0L)
    val AD_COUNT_DAY = SettingsKey("ad.count_day", "")

    // 脚本
    val SCRIPT_ENABLED = SettingsKey("script.enabled", true)
    val SCRIPT_GM_BRIDGE = SettingsKey("script.gm_bridge", true)

    // DoH
    val DOH_ENABLED = SettingsKey("doh.enabled", false)
    val DOH_MODE = SettingsKey("doh.mode", "smart")
    /** "auto" = 内置五路竞速；否则是内置上游的 URL 或自定义 URL。 */
    val DOH_UPSTREAM = SettingsKey("doh.upstream", "auto")

    /** 自定义 DoH 上游（用户自己填的 URL），与 DOH_UPSTREAM 分开存，便于一键切回内置。 */
    val DOH_UPSTREAM_CUSTOM = SettingsKey("doh.upstream_custom", "")
    val DOH_RULES = SettingsKey("doh.rules", DEFAULT_DOH_RULES)
    val DOH_STICKY = SettingsKey("doh.sticky", true)

    // AI
    val AI_SUMMARY = SettingsKey("ai.summary", true)
    val AI_TRANSLATE = SettingsKey("ai.translate", false)
    val AI_SELECTION = SettingsKey("ai.selection", true)

    // 备份
    val BACKUP_WEBDAV_URL = SettingsKey("backup.webdav_url", "")
    val BACKUP_WEBDAV_USER = SettingsKey("backup.webdav_user", "")
    val BACKUP_WEBDAV_PASS = SettingsKey("backup.webdav_pass", "")
    val BACKUP_AUTO = SettingsKey("backup.auto", "off")           // off / daily / weekly
    val BACKUP_LAST_AT = SettingsKey("backup.last_at", 0L)

    // 隐私
    val PRIVACY_CLEAR_ON_EXIT = SettingsKey("privacy.clear_on_exit", false)
    val PRIVACY_DO_NOT_TRACK = SettingsKey("privacy.dnt", true)
    val PRIVACY_BLOCK_THIRD_PARTY = SettingsKey("privacy.block_third_party", false)

    // 更多
    val LANGUAGE = SettingsKey("more.language", "zh")
    val NOTIFY = SettingsKey("more.notify", true)
    val DEV_MODE = SettingsKey("more.dev_mode", false)
    val LOG_LEVEL = SettingsKey("more.log_level", "info")
    val WEB_LOG = SettingsKey("more.web_log", false)
}

private const val DEFAULT_DOH_RULES = """# 一行一条，支持 example.com / *.google.com / +.blogspot.com / re:^ads?\..*\.cn$
google.com
youtube.com
twitter.com
github.com
+.blogspot.com
*.wikipedia.org"""

/** 全局设置门面：初始化一次，其余代码只读。 */
object AppSettings {

    lateinit var repo: SettingsRepository
        private set

    fun init(context: Context) {
        if (::repo.isInitialized) return
        // 统一走 core 的 DataStore 单例：备份、各模块、界面读的是同一份设置，
        // 不再维护第二套 SharedPreferences（会分裂数据源）。
        Core.init(context)
        repo = Core.settings
        cn.olfact.core.api.OlfactServices.settings = repo
    }

    fun <T> get(key: SettingsKey<T>): T = repo.get(key)

    fun <T> set(key: SettingsKey<T>, value: T) = repo.set(key, value)

    val changes: Flow<String> get() = repo.changes
}
