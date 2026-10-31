package cn.olfact.app.ui.settings

import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.K
import cn.olfact.app.data.RuntimeFlags
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs
import cn.olfact.app.ui.settings.pages.AdBlockFragment
import cn.olfact.app.ui.settings.pages.AiFragment
import cn.olfact.app.ui.settings.pages.BackupFragment
import cn.olfact.app.ui.settings.pages.DohFragment
import cn.olfact.app.ui.settings.pages.LogFragment
import cn.olfact.app.ui.settings.pages.ProtocolFragment
import cn.olfact.app.ui.settings.pages.ScriptFragment
import cn.olfact.core.Core
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.backup.PrivacyCleaner

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

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    private fun basic() = Spec(
        title = "基本设置",
        sections = listOf(
            Section(
                listOf(
                    Row.Choice(K.RESTORE_TABS, "启动时恢复标签", listOf(
                        "always" to "自动恢复", "manual" to "手动恢复", "never" to "不恢复"
                    )),
                    Row.Nav("搜索引擎管理", target = { SettingsPageFragment.newInstance("search") })
                )
            ),
            Section(
                listOf(
                    Row.Toggle(K.ALLOW_OPEN_APP, "允许网页打开应用")
                )
            ),
            Section(
                listOf(
                    Row.Choice(K.HISTORY_LIMIT, "历史记录限制", listOf(
                        "100" to "100 条", "1000" to "1000 条", "5000" to "5000 条",
                        "100000" to "不限"
                    )),
                    Row.Action("恢复默认设置", summary = "所有设置回到初始值；书签/历史/脚本不受影响") {
                        val ctx = Nav.context()
                        if (ctx != null) {
                            Dialogs.confirm(
                                ctx,
                                "恢复默认设置",
                                "所有设置项将回到初始值，书签、历史、脚本与下载不受影响。继续？",
                                danger = true
                            ) {
                                AppSettings.reset()
                                (ctx as? android.app.Activity)?.recreate()
                            }
                        }
                    }
                )
            )
        )
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
                    Row.Toggle(K.DL_NOTIFY, "下载通知（进度/完成/失败）")
                )
            ),
            Section(
                listOf(
                    Row.Value(
                        "下载目录",
                        value = { "应用专属目录" },
                        summary = "Android/data/${Core.context.packageName}/files/Download/olfact"
                    ),
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
                    Row.Toggle(K.SNIFF_FILTER_SMALL, "过滤小于 256 KB 的媒体")
                )
            ),
            Section(
                listOf(
                    Row.Choice(K.PLAYER_SPEED, "默认倍速", listOf(
                        "0.75" to "0.75x", "1.0" to "1.0x", "1.25" to "1.25x", "1.5" to "1.5x", "2.0" to "2.0x"
                    )),
                    Row.Toggle(K.PLAYER_PIP, "允许画中画")
                )
            )
        ),
        footer = "嗅探结果只保留当前页面发起的请求；跨域分片由浏览器内核负责带上 Referer，避免 403。"
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
                        AppSettings.get(K.WEB_CUSTOM_UA).ifBlank {
                            if (AppSettings.get(K.DESKTOP_MODE)) DESKTOP_UA else MOBILE_UA
                        }
                    })
                )
            ),
            Section(
                listOf(
                    Row.Action("自定义 UA", summary = "留空则用全局默认；保存后刷新页面生效") {
                        val ctx = Nav.context()
                        if (ctx != null) {
                            Dialogs.input(ctx, "自定义 UA", AppSettings.get(K.WEB_CUSTOM_UA), "留空恢复默认") { ua ->
                                AppSettings.set(K.WEB_CUSTOM_UA, ua.trim())
                                RuntimeFlags.reloadPagesOnResume = true
                                Nav.toast(if (ua.isBlank()) "已恢复默认 UA" else "已保存，刷新页面后生效")
                            }
                        }
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
                footer = "本页改的是全局默认；按站点的覆盖配置暂未开放入口，后续会加到「UA 管理」同级的站点列表里。"
            )
        )
    )

    private fun privacy() = Spec(
        title = "隐私与安全",
        sections = listOf(
            Section(
                listOf(
                    Row.Toggle(K.PRIVACY_CLEAR_ON_EXIT, "退出时清除数据"),
                    Row.Toggle(K.PRIVACY_BLOCK_THIRD_PARTY, "阻止第三方 Cookie")
                )
            ),
            Section(
                buildList {
                    add(Row.Action("清除缓存", summary = "只清网页缓存，不影响书签/历史") {
                        val freed = runCatching { PrivacyCleaner(Core.context).clearCache() }.getOrDefault(0L)
                        Nav.toast(if (freed > 0) "已清理 ${formatBytes(freed)} 缓存" else "缓存已是空的")
                    })
                    add(Row.Action("清除 Cookie") {
                        val ok = runCatching { PrivacyCleaner(Core.context).clearCookies() }.getOrDefault(false)
                        Nav.toast(if (ok) "Cookie 已清除，已打开的网页需刷新" else "清除失败，请稍后重试")
                    })
                    add(Row.Action("系统权限管理") {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${Core.context.packageName}")
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { Core.context.startActivity(intent) }
                            .onFailure { Nav.toast("打不开系统设置，请手动到「设置 → 应用」里管理") }
                    })
                    add(Row.Toggle(K.DEV_MODE, "开发者模式"))
                    // 日志入口只在开发者模式显示，避免普通用户被日志页吓到
                    if (AppSettings.get(K.DEV_MODE)) {
                        add(Row.Nav("崩溃与网络日志", target = { LogFragment() }))
                    }
                }
            )
        )
    )

    private fun more() = Spec(
        title = "更多设置",
        sections = listOf(
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
