package cn.olfact.app.ui.settings.pages

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.olfact.app.R
import cn.olfact.app.data.AppBackup
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.K
import cn.olfact.app.data.WebDavSecret
import cn.olfact.app.databinding.FragmentPageBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs
import cn.olfact.app.ui.common.cardBottomMargin
import cn.olfact.app.ui.common.note
import cn.olfact.app.ui.common.sectionTitle
import cn.olfact.app.ui.common.setting
import cn.olfact.app.ui.common.settingGroup
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据备份：B4 的 zip + JSON 流水线（本地 / WebDAV / 定时）。
 *
 * 恢复走「整包校验 → 逐段合并」，校验失败不会动现有数据；WebDAV 密码用 Keystore 加密后落盘。
 */
class BackupFragment : Fragment(R.layout.fragment_page) {

    private var binding: FragmentPageBinding? = null
    private var busy = false
    private var needsRestart = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = FragmentPageBinding.bind(view)
        binding!!.header.title = "数据备份"
        binding!!.header.onBack = { Nav.pop() }
        render()
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        b.content.removeAllViews()

        val local = AppBackup.localFiles()
        val state = AppBackup.state()

        b.content.addView(sectionTitle("本地"))
        b.content.addView(settingGroup(
            setting("立即备份", if (busy) "进行中…" else "设置 / 书签 / 历史 / 脚本") {
                runBackup()
            },
            setting(
                "从最近备份恢复",
                local.firstOrNull()?.let { "${it.name.removePrefix("olfact-backup-")} · ${local.size} 份" } ?: "没有可用备份"
            ) {
                val file = AppBackup.latestLocal() ?: return@setting Nav.toast("还没有本地备份")
                val preview = AppBackup.preview(file)
                val summary = preview?.let {
                    "书签 ${it.bookmarks.size} / 历史 ${it.history.size} / 脚本 ${it.scripts.size} 项"
                } ?: "无法读取备份内容"
                Dialogs.confirm(ctx, "恢复备份", "$summary\n将合并进当前数据（设置会被覆盖），继续？") {
                    restore(file)
                }
            },
            setting(
                "上次结果",
                state?.let {
                    if (it.lastSuccess) "成功 · ${it.lastBytes / 1024} KB" else (it.lastError ?: "失败")
                } ?: "还没有备份过"
            ),
            setting(
                "上次备份时间",
                AppSettings.get(K.BACKUP_LAST_AT).takeIf { it > 0 }
                    ?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it)) }
                    ?: "还没有备份过"
            )
        ).also { cardBottomMargin(it) })

        if (needsRestart) {
            b.content.addView(settingGroup(
                setting("立即重启应用", "让恢复的设置与数据全部生效") { restartApp() }
            ).also { cardBottomMargin(it) })
        }

        b.content.addView(sectionTitle("WebDAV"))
        b.content.addView(settingGroup(
            setting("服务器", AppSettings.get(K.BACKUP_WEBDAV_URL).ifBlank { "未配置" }) {
                Dialogs.input(
                    ctx, "WebDAV 地址", AppSettings.get(K.BACKUP_WEBDAV_URL),
                    "https://dav.example.com/remote.php/dav/files/me"
                ) {
                    AppSettings.set(K.BACKUP_WEBDAV_URL, it.trim())
                    render()
                }
            },
            setting("账号", AppSettings.get(K.BACKUP_WEBDAV_USER).ifBlank { "未配置" }) {
                Dialogs.input(ctx, "账号", AppSettings.get(K.BACKUP_WEBDAV_USER)) {
                    AppSettings.set(K.BACKUP_WEBDAV_USER, it.trim())
                    render()
                }
            },
            setting("密码", WebDavSecret.masked()) {
                Dialogs.input(ctx, "密码（Keystore 加密保存）", "") { plain ->
                    WebDavSecret.write(plain)
                    render()
                }
            }
        ).also { cardBottomMargin(it) })

        b.content.addView(sectionTitle("定时"))
        b.content.addView(settingGroup(
            setting("自动备份", when (AppSettings.get(K.BACKUP_AUTO)) {
                "daily" -> "每天"
                "weekly" -> "每周"
                else -> "关闭"
            }) {
                val options = listOf("关闭", "每天", "每周")
                val current = options.indexOf(
                    when (AppSettings.get(K.BACKUP_AUTO)) {
                        "daily" -> "每天"
                        "weekly" -> "每周"
                        else -> "关闭"
                    }
                ).coerceAtLeast(0)
                Dialogs.choose(ctx, "自动备份", options, current) { idx ->
                    AppSettings.set(K.BACKUP_AUTO, listOf("off", "daily", "weekly")[idx])
                    AppBackup.syncSchedule(requireContext())
                    render()
                }
            }
        ).also { cardBottomMargin(it) })

        b.content.addView(note(
            "备份包是 zip + JSON：manifest（版本 + SHA256）+ 设置 / 书签 / 历史 / 脚本 / 订阅 / 白名单。\n" +
                "不包含 Cookie、页面缓存与 AI 密钥；恢复用「只增不减」合并，冲突按时间取新。"
        ))
    }

    private fun runBackup() {
        if (busy) return
        busy = true
        render()
        val hasWebDav = AppSettings.get(K.BACKUP_WEBDAV_URL).isNotBlank()
        Nav.toast(if (hasWebDav) "正在备份到 WebDAV…" else "正在备份…")
        viewLifecycleOwner.lifecycleScope.launch {
            val result = AppBackup.backupNow("manual")
            busy = false
            if (isAdded) {
                Nav.toast(
                    if (result.success) "备份完成 · ${result.bytes / 1024} KB"
                    else "备份失败：${result.error ?: "未知错误"}"
                )
                render()
            }
        }
    }

    private fun restore(file: java.io.File) {
        if (busy) return
        busy = true
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = AppBackup.restore(file)
            busy = false
            if (!isAdded) return@launch
            if (result.error != null) {
                Nav.toast("恢复失败：${result.error}")
            } else {
                needsRestart = true
                Nav.toast("已恢复 ${result.changed} 项，建议立即重启")
                render()
            }
        }
    }

    /** 恢复会替换 DataStore / 数据库连接，必须重启进程才彻底生效。 */
    private fun restartApp() {
        val ctx = requireContext()
        val intent = android.content.Intent(ctx, cn.olfact.app.MainActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        // 先用 AlarmManager 预约启动，再杀进程：避免 startActivity 后立刻 exit 的竞态。
        runCatching {
            val pending = android.app.PendingIntent.getActivity(
                ctx, 0, intent,
                android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = ctx.getSystemService(android.app.AlarmManager::class.java)
            alarm?.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 300, pending)
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
