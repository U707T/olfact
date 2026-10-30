package cn.olfact.app.ui.settings

import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.K
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs
import cn.olfact.app.ui.settings.pages.AdBlockFragment
import cn.olfact.app.ui.settings.pages.AiFragment
import cn.olfact.app.ui.settings.pages.BackupFragment
import cn.olfact.app.ui.settings.pages.DohFragment
import cn.olfact.app.ui.settings.pages.LogFragment
import cn.olfact.app.ui.settings.pages.ProtocolFragment
import cn.olfact.app.ui.settings.pages.ScriptFragment
import cn.olfact.core.api.OlfactServices

/**
 * 全部「结构性」设置页的数据。文案对齐原版设置文案（见 docs/refs/18、19、17、15）。
 */
object SettingsSpecs {

    fun build(id: String): Spec = when (id) {
        "basic" -> basic()
        "appearance" -> appearance()
        "download" -> download()
        "sniffer" -> sniffer()
        "ua" -> ua()
        "site" -> site()
        "privacy" -> privacy()
        "more" -> more()
        "search" -> search()
        else -> Spec(title = "设置", sections = emptyList())
    }

    private fun basic() = Spec(
        title = "基本设置",
        sections = listOf(
            Section(
                listOf(
                    Row.Choice(K.RESTORE_TABS, "启动时恢复标签", listOf(
                        "always" to "自动恢复", "manual" to "手动恢复", "never" to "不恢复"
                    )),
                    Row.Toggle(K.BACK_NO_RELOAD, "返回不重载"),
                    Row.Nav("搜索引擎管理", target = { SettingsPageFragment.newInstance("search") }),
                    Row.Toggle(K.SEARCH_CLIPBOARD, "搜索自动提取剪贴板"),
                    Row.Choice(K.SUGGEST_SOURCE, "搜索建议来源", listOf(
                        "baidu" to "百度", "bing" to "必应", "google" to "Google", "off" to "关闭"
                    ))
                )
            ),
            Section(
                listOf(
                    Row.Toggle(K.ALLOW_OPEN_APP, "允许网页打开应用"),
                    Row.Toggle(K.ALLOW_LOCATION, "允许网页获取位置")
                )
            ),
            Section(
                listOf(
                    Row.Choice(K.HISTORY_LIMIT, "历史记录限制", listOf(
                        "100" to "100 条", "1000" to "1000 条", "5000" to "5000 条",
                        "100000" to "不限"
                    )),
                    Row.Toggle(K.CONTINUE_PLAY, "历史继续播放提示"),
                    Row.Toggle(K.FORCE_NEW_WINDOW, "强制新窗口打开")
                )
            )
        ),
        footer = "「返回不重载」依赖 web 内核的 bfcache 支持；关闭后每次返回都会重新请求页面。"
    )

    private fun appearance() = Spec(
        title = "外观/手势",
        sections = listOf(
            Section(
                listOf(
                    Row.IntChoice(K.NIGHT_MODE, "夜间模式", listOf(0 to "跟随系统", 1 to "开启", 2 to "关闭")),
                    Row.Toggle(K.EYE_CARE, "护眼模式"),
                    Row.Choice(K.BOTTOM_STYLE, "底部栏样式", listOf("pill" to "胶囊主页", "icon" to "纯图标")),
                    Row.IntChoice(K.FONT_SCALE, "字体缩放", listOf(90 to "90%", 100 to "100%", 110 to "110%", 120 to "120%"))
                )
            ),
            Section(
                listOf(
                    Row.Toggle(K.FULLSCREEN_SCROLL, "全屏滚动隐藏工具栏"),
                    Row.Toggle(K.SWIPE_BACK, "侧滑返回"),
                    Row.Toggle(K.DOUBLE_TAP_ZOOM, "网页双击缩放"),
                    Row.IntChoice(K.TEXT_ZOOM, "网页字号", listOf(80 to "80%", 90 to "90%", 100 to "100%", 110 to "110%", 130 to "130%"))
                )
            ),
            Section(
                listOf(
                    Row.Choice(K.NIGHT_WEB, "夜间网页", listOf("auto" to "跟随系统", "on" to "强制开启", "off" to "关闭")),
                    Row.Toggle(K.NO_IMAGE, "无图模式"),
                    Row.Toggle(K.DESKTOP_MODE, "桌面模式（默认 UA）"),
                    Row.Toggle(K.JS_ENABLED, "启用 JavaScript")
                ),
                footer = "UA 与站点级开关请在「UA 管理」「网站配置」里设置，会覆盖这里的全局默认。"
            )
        )
    )

    private fun download() = Spec(
        title = "下载相关",
        sections = listOf(
            Section(
                listOf(
                    Row.IntChoice(K.DL_THREADS, "单任务线程数", listOf(1 to "1", 2 to "2", 4 to "4", 8 to "8")),
                    Row.IntChoice(K.DL_CONCURRENT, "同时下载任务数", listOf(1 to "1", 2 to "2", 3 to "3", 5 to "5")),
                    Row.Toggle(K.DL_WIFI_ONLY, "仅 Wi-Fi 下载"),
                    Row.Toggle(K.DL_NOTIFY, "下载完成通知"),
                    Row.Toggle(K.DL_AUTO_RENAME, "同名文件自动重命名"),
                    Row.Toggle(K.DL_M3U8_MERGE, "M3U8 自动合并为 MP4")
                )
            ),
            Section(
                listOf(
                    Row.Value("下载目录", value = { "/Download/olfact" }),
                    Row.Action("清除已完成任务", summary = "只删记录，不删文件") {
                        OlfactServices.downloads?.clearFinished()
                        Nav.toast("已清除")
                    }
                )
            )
        ),
        footer = "M3U8 合并用 media3-muxer 做 TS→MP4 重封装；失败时自动降级为「仅拼接分片」，文件仍可播放。"
    )

    private fun sniffer() = Spec(
        title = "嗅探和播放器",
        sections = listOf(
            Section(
                listOf(
                    Row.Toggle(K.SNIFF_ENABLED, "启用视频嗅探"),
                    Row.Toggle(K.SNIFF_TIP, "发现视频时提示"),
                    Row.Toggle(K.SNIFF_FILTER_SMALL, "过滤小于 1 MB 的分片")
                )
            ),
            Section(
                listOf(
                    Row.Choice(K.PLAYER_SPEED, "默认倍速", listOf(
                        "0.75" to "0.75x", "1.0" to "1.0x", "1.25" to "1.25x", "1.5" to "1.5x", "2.0" to "2.0x"
                    )),
                    Row.Toggle(K.PLAYER_SKIP_INTRO, "自动跳过片头片尾"),
                    Row.Toggle(K.PLAYER_GRAVITY_ROTATE, "重力感应横屏"),
                    Row.Toggle(K.PLAYER_PIP, "允许画中画")
                )
            )
        ),
        footer = "嗅探结果只保留当前页面发起的请求；跨域分片由 web 内核负责带上 Referer，避免 403。"
    )

    private fun ua() = Spec(
        title = "UA 管理",
        sections = listOf(
            Section(
                listOf(
                    Row.Toggle(K.DESKTOP_MODE, "默认使用桌面 UA", "关闭 = Android 移动 UA")
                )
            ),
            Section(
                listOf(
                    Row.Value("当前 UA", value = {
                        if (AppSettings.get(K.DESKTOP_MODE)) DESKTOP_UA else MOBILE_UA
                    })
                )
            ),
            Section(
                listOf(
                    Row.Action("自定义 UA", summary = "按站点覆盖，留空则用全局默认") {
                        Dialogs.input(Nav.context()!!, "自定义 UA", MOBILE_UA) { _ -> Nav.toast("站点级 UA 由 web 模块持久化") }
                    }
                )
            )
        )
    )

    private fun site() = Spec(
        title = "网站配置",
        sections = listOf(
            Section(
                listOf(
                    Row.Choice(K.NIGHT_WEB, "全局夜间网页", listOf("auto" to "跟随系统", "on" to "强制开启", "off" to "关闭")),
                    Row.Toggle(K.NO_IMAGE, "全局无图"),
                    Row.Toggle(K.DESKTOP_MODE, "全局桌面模式"),
                    Row.Toggle(K.JS_ENABLED, "全局启用 JS")
                ),
                footer = "按站点的覆盖配置存放在 web 模块的 site_config 表里；本页只改全局默认。"
            )
        )
    )

    private fun privacy() = Spec(
        title = "隐私与安全",
        sections = listOf(
            Section(
                listOf(
                    Row.Toggle(K.PRIVACY_CLEAR_ON_EXIT, "退出时清除数据"),
                    Row.Toggle(K.PRIVACY_DO_NOT_TRACK, "发送 Do Not Track"),
                    Row.Toggle(K.PRIVACY_BLOCK_THIRD_PARTY, "阻止第三方 Cookie")
                )
            ),
            Section(
                listOf(
                    Row.Action("清除缓存") { Nav.toast("已清理（web 模块接入后统计真实占用）") },
                    Row.Action("清除 Cookie") { Nav.toast("已清理") },
                    Row.Action("系统权限管理") { Nav.toast("请在系统设置里管理权限") },
                    Row.Toggle(K.DEV_MODE, "开发者模式"),
                    Row.Nav("崩溃与网络日志", target = { LogFragment() })
                )
            )
        )
    )

    private fun more() = Spec(
        title = "更多设置",
        sections = listOf(
            Section(
                listOf(
                    Row.Choice(K.LANGUAGE, "语言", listOf("zh" to "简体中文", "en" to "English")),
                    Row.Toggle(K.NOTIFY, "通知"),
                    Row.Choice(K.LOG_LEVEL, "日志级别", listOf("error" to "仅错误", "info" to "常规", "debug" to "调试")),
                    Row.Toggle(K.WEB_LOG, "记录网页控制台日志")
                )
            ),
            Section(
                listOf(
                    Row.Nav("用户协议", target = { ProtocolFragment.newInstance("terms") }),
                    Row.Nav("隐私政策", target = { ProtocolFragment.newInstance("privacy") }),
                    Row.Nav("开源许可", target = { ProtocolFragment.newInstance("licenses") })
                )
            )
        )
    )

    private fun search() = Spec(
        title = "搜索引擎",
        sections = listOf(
            Section(
                listOf(
                    Row.Choice(K.SEARCH_ENGINE, "默认引擎", listOf(
                        "baidu" to "百度", "bing" to "必应", "google" to "Google", "custom" to "自定义"
                    )),
                    Row.Value("自定义模板", value = {
                        AppSettings.get(K.SEARCH_CUSTOM).ifBlank { "https://example.com/search?q=%s" }
                    }, onClick = {
                        Dialogs.input(Nav.context()!!, "搜索模板", AppSettings.get(K.SEARCH_CUSTOM), "https://…/search?q=%s") {
                            AppSettings.set(K.SEARCH_CUSTOM, it)
                            Nav.pop()
                        }
                    })
                ),
                footer = "模板里的 %s 会被替换成 URL 编码后的关键词。"
            )
        )
    )

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}
