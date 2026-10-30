package cn.olfact.app.data

import android.content.Context
import cn.olfact.core.api.PrefKey
import cn.olfact.core.api.SettingsKey
import cn.olfact.core.api.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 全部设置项。命名规则 `<模块>.<分组>.<项>`，与各模块共享。
 *
 * 这里只放「界面与通用」的项；DoH/拦截/下载/AI 的专用项放在对应面板里，
 * 但仍然走同一份 [SettingsRepository]，保证备份时能一次性导出。
 */
object K {
    // 外观
    val NIGHT_MODE = PrefKey("ui.night_mode", 0)            // 0 跟随系统 / 1 开 / 2 关
    val FONT_SCALE = PrefKey("ui.font_scale", 100)
    val BOTTOM_STYLE = PrefKey("ui.bottom_style", "pill")    // pill / icon
    val EYE_CARE = PrefKey("ui.eye_care", false)

    // 基本
    val RESTORE_TABS = PrefKey("ui.restore_tabs", "manual")  // always / manual / never
    val BACK_NO_RELOAD = PrefKey("ui.back_no_reload", true)
    val SUGGEST_SOURCE = PrefKey("ui.suggest_source", "baidu")
    val SEARCH_CLIPBOARD = PrefKey("ui.search_clipboard", true)
    val SEARCH_ENGINE = PrefKey("ui.search_engine", "baidu")
    val SEARCH_CUSTOM = PrefKey("ui.search_custom", "")
    val ALLOW_OPEN_APP = PrefKey("ui.allow_open_app", true)
    val ALLOW_LOCATION = PrefKey("ui.allow_location", false)
    val HISTORY_LIMIT = PrefKey("ui.history_limit", "1000")
    val CONTINUE_PLAY = PrefKey("ui.continue_play", true)
    val FORCE_NEW_WINDOW = PrefKey("ui.force_new_window", false)

    // 手势/外观细节
    val FULLSCREEN_SCROLL = PrefKey("ui.fullscreen_scroll", true)
    val SWIPE_BACK = PrefKey("ui.swipe_back", true)
    val DOUBLE_TAP_ZOOM = PrefKey("web.double_tap_zoom", false)
    val TEXT_ZOOM = PrefKey("web.text_zoom", 100)
    val NIGHT_WEB = PrefKey("web.night", "auto")
    val NO_IMAGE = PrefKey("web.no_image", false)
    val DESKTOP_MODE = PrefKey("web.desktop", false)
    val PAGE_ZOOM = PrefKey("web.page_zoom", 100)
    val JS_ENABLED = PrefKey("web.js", true)

    // 下载
    val DL_THREADS = PrefKey("dl.threads", 4)
    val DL_CONCURRENT = PrefKey("dl.concurrent", 2)
    val DL_WIFI_ONLY = PrefKey("dl.wifi_only", false)
    val DL_NOTIFY = PrefKey("dl.notify", true)
    val DL_AUTO_RENAME = PrefKey("dl.auto_rename", true)
    val DL_M3U8_MERGE = PrefKey("dl.m3u8_merge", true)

    // 嗅探/播放
    val SNIFF_ENABLED = PrefKey("sniff.enabled", true)
    val SNIFF_TIP = PrefKey("sniff.tip", true)
    val SNIFF_FILTER_SMALL = PrefKey("sniff.filter_small", true)
    val PLAYER_SPEED = PrefKey("player.speed", "1.0")
    val PLAYER_SKIP_INTRO = PrefKey("player.skip_intro", true)
    val PLAYER_GRAVITY_ROTATE = PrefKey("player.gravity_rotate", true)
    val PLAYER_PIP = PrefKey("player.pip", true)

    // 广告拦截
    val AD_ENABLED = PrefKey("ad.enabled", true)
    val AD_COSMETIC = PrefKey("ad.cosmetic", true)
    val AD_COUNT_TOTAL = PrefKey("ad.count_total", 0L)
    val AD_COUNT_TODAY = PrefKey("ad.count_today", 0L)
    val AD_COUNT_DAY = PrefKey("ad.count_day", "")

    // 脚本
    val SCRIPT_ENABLED = PrefKey("script.enabled", true)
    val SCRIPT_GM_BRIDGE = PrefKey("script.gm_bridge", true)

    // DoH
    val DOH_ENABLED = PrefKey("doh.enabled", false)
    val DOH_MODE = PrefKey("doh.mode", "smart")
    val DOH_UPSTREAM = PrefKey("doh.upstream", "https://doh.pub/dns-query")
    val DOH_RULES = PrefKey("doh.rules", DEFAULT_DOH_RULES)
    val DOH_STICKY = PrefKey("doh.sticky", true)

    // AI
    val AI_SUMMARY = PrefKey("ai.summary", true)
    val AI_TRANSLATE = PrefKey("ai.translate", false)
    val AI_SELECTION = PrefKey("ai.selection", true)

    // 备份
    val BACKUP_WEBDAV_URL = PrefKey("backup.webdav_url", "")
    val BACKUP_WEBDAV_USER = PrefKey("backup.webdav_user", "")
    val BACKUP_WEBDAV_PASS = PrefKey("backup.webdav_pass", "")
    val BACKUP_AUTO = PrefKey("backup.auto", "off")           // off / daily / weekly
    val BACKUP_LAST_AT = PrefKey("backup.last_at", 0L)

    // 隐私
    val PRIVACY_CLEAR_ON_EXIT = PrefKey("privacy.clear_on_exit", false)
    val PRIVACY_DO_NOT_TRACK = PrefKey("privacy.dnt", true)
    val PRIVACY_BLOCK_THIRD_PARTY = PrefKey("privacy.block_third_party", false)

    // 更多
    val LANGUAGE = PrefKey("more.language", "zh")
    val NOTIFY = PrefKey("more.notify", true)
    val DEV_MODE = PrefKey("more.dev_mode", false)
    val LOG_LEVEL = PrefKey("more.log_level", "info")
    val WEB_LOG = PrefKey("more.web_log", false)
}

private const val DEFAULT_DOH_RULES = """# 一行一条，支持 example.com / *.google.com / +.blogspot.com / re:^ads?\..*\.cn$
google.com
youtube.com
twitter.com
github.com
+.blogspot.com
*.wikipedia.org"""

/** SharedPreferences 实现。值类型在写入时由运行时类型决定，读取时按 key 的默认值类型还原。 */
class PrefsSettings(context: Context) : SettingsRepository {

    private val prefs = context.applicationContext.getSharedPreferences("olfact_settings", Context.MODE_PRIVATE)
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val changes: Flow<String> = _changes

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: SettingsKey<T>): T {
        val def = key.def
        val raw: Any? = when (def) {
            is Boolean -> prefs.getBoolean(key.key, def)
            is Int -> prefs.getInt(key.key, def)
            is Long -> prefs.getLong(key.key, def)
            is Float -> prefs.getFloat(key.key, def)
            is String -> prefs.getString(key.key, def)
            else -> def
        }
        return (raw ?: def) as T
    }

    override fun <T> set(key: SettingsKey<T>, value: T) {
        val e = prefs.edit()
        when (value) {
            is Boolean -> e.putBoolean(key.key, value)
            is Int -> e.putInt(key.key, value)
            is Long -> e.putLong(key.key, value)
            is Float -> e.putFloat(key.key, value)
            is String -> e.putString(key.key, value)
            else -> return
        }
        e.apply()
        _changes.tryEmit(key.key)
    }
}

/** 全局设置门面：初始化一次，其余代码只读。 */
object AppSettings {

    lateinit var repo: SettingsRepository
        private set

    fun init(context: Context) {
        if (::repo.isInitialized) return
        repo = PrefsSettings(context)
        cn.olfact.core.api.OlfactServices.settings = repo
    }

    fun <T> get(key: SettingsKey<T>): T = repo.get(key)

    fun <T> set(key: SettingsKey<T>, value: T) = repo.set(key, value)

    val changes: Flow<String> get() = repo.changes
}
