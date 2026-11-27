package cn.olfact.app.ui.settings.pages

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CoroutineStart
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
    private var pickingDocument = false
    private var operationGeneration = 0L

    // SAF 仅用系统授予的本次文档 URI 权限；操作随视图取消，不跨进程保存 URI。
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        pickingDocument = false
        render()
        if (uri != null && binding != null) {
            val app = requireContext().applicationContext
            runOperation {
                AppBackup.exportLocal(app, uri)
                Nav.toast("ZIP 已导出，请妥善保存")
            }
        }
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickingDocument = false
        render()
        if (uri != null && binding != null) {
            val app = requireContext().applicationContext
            Dialogs.confirm(requireContext(), "从 ZIP 恢复", "将先校验备份，再合并进当前数据（设置会被覆盖），继续？") {
                restore { AppBackup.restore(app, uri) }
            }
        }
    }

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
            setting("立即备份", if (busy) "进行中…" else "按当前本地 / WebDAV 目标备份") {
                runBackup()
            },
            setting("导出本地 ZIP", "另存到系统文件；不受 WebDAV 配置影响") {
                launchDocumentPicker(export = true)
            },
            setting("选择 ZIP 恢复", "从系统文件中选择备份") {
                launchDocumentPicker(export = false)
            },
            setting(
                "选择本地备份恢复",
                local.firstOrNull()?.let { "${it.name.removePrefix("olfact-backup-")} · ${local.size} 份" } ?: "没有可用备份"
            ) {
                if (busy || pickingDocument) return@setting
                if (local.isEmpty()) return@setting Nav.toast("还没有本地备份")
                Dialogs.choose(ctx, "选择本地备份", local.map { it.name }, 0) { index ->
                    previewAndRestore(local[index])
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
                state?.lastRunAt?.takeIf { it > 0 }
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
                    val url = it.trim()
                    val parsed = runCatching { java.net.URI(url) }.getOrNull()
                    if (url.isNotEmpty() && (parsed?.scheme?.lowercase() !in setOf("http", "https") ||
                            parsed?.host == null || parsed.userInfo != null || parsed.fragment != null)) {
                        Nav.toast("请输入有效的 HTTP(S) WebDAV 地址，不要在地址中填写账号密码")
                    } else runOperation { AppBackup.setRemoteTarget(K.BACKUP_WEBDAV_URL, url) }
                }
            },
            setting("账号", AppSettings.get(K.BACKUP_WEBDAV_USER).ifBlank { "未配置" }) {
                Dialogs.input(ctx, "账号", AppSettings.get(K.BACKUP_WEBDAV_USER)) {
                    runOperation { AppBackup.setRemoteTarget(K.BACKUP_WEBDAV_USER, it.trim()) }
                }
            },
            setting("密码", WebDavSecret.masked()) {
                Dialogs.input(ctx, "密码（Keystore 加密保存）", hint = "留空可清除密码", password = true) { plain ->
                    runOperation { WebDavSecret.write(plain) }
                }
            },
            setting("测试连接") {
                runOperation { Nav.toast(if (AppBackup.testRemote()) "WebDAV 连接成功" else "连接失败，请检查地址、账号或密码") }
            },
            setting("从 WebDAV 恢复最近备份") {
                runOperation {
                    val entry = AppBackup.latestRemote()
                    if (entry == null) Nav.toast("WebDAV 没有可用备份")
                    else Dialogs.confirm(ctx, "恢复 WebDAV 备份", "${entry.name}\n将合并进当前数据（设置会被覆盖），继续？") {
                        restore { AppBackup.restoreRemote(entry) }
                    }
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
            "备份包是 zip + JSON：manifest（版本 + SHA256）+ 设置 / 书签 / 历史 / 收藏 / 脚本 / 订阅 / 白名单。\n" +
                "不包含 Cookie、页面缓存、AI API 密钥与 WebDAV 密码；换设备需重新配置凭据。恢复用「只增不减」合并，冲突按时间取新。"
        ))
    }

    private fun launchDocumentPicker(export: Boolean) {
        if (busy || pickingDocument) return
        pickingDocument = true
        try {
            if (export) exportDocument.launch(cn.olfact.core.backup.BackupPipeline.fileName(System.currentTimeMillis()))
            else importDocument.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        } catch (e: Exception) {
            pickingDocument = false
            Nav.toast("无法打开系统文件选择器：${e.message ?: "请稍后重试"}")
        }
    }

    private fun previewAndRestore(file: java.io.File) = runOperation {
        val summary = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AppBackup.preview(file)?.let {
                "书签 ${it.bookmarks.size} / 历史 ${it.history.size} / 收藏 ${it.favorites.size} / 脚本 ${it.scripts.size} 项"
            }
        }
        if (summary == null) Nav.toast("无法读取备份内容，请选择其他 ZIP")
        else if (binding != null) Dialogs.confirm(requireContext(), "恢复备份", "$summary\n将合并进当前数据（设置会被覆盖），继续？") {
            restore(file)
        }
    }

    private fun runBackup() = runOperation {
        val hasWebDav = AppSettings.get(K.BACKUP_WEBDAV_URL).isNotBlank()
        Nav.toast(if (hasWebDav) "正在备份到 WebDAV…" else "正在备份…")
        val result = AppBackup.backupNow("manual")
        Nav.toast(
            if (result.success) "备份完成 · ${result.bytes / 1024} KB"
            else "备份失败：${result.error ?: "未知错误"}"
        )
    }

    private fun restore(file: java.io.File) = restore { AppBackup.restore(file) }

    private fun restore(action: suspend () -> cn.olfact.core.backup.ImportResult) = runOperation {
        val result = action()
        if (result.error != null) Nav.toast("恢复失败：${result.error}")
        else {
            needsRestart = true
            Nav.toast("已恢复 ${result.changed} 项")
        }
    }

    private fun runOperation(action: suspend () -> Unit) {
        val owner = viewLifecycleOwnerLiveData.value ?: return
        if (busy || pickingDocument) return
        val generation = ++operationGeneration
        busy = true
        render()
        owner.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                action()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Nav.toast("操作失败：${e.message ?: "请稍后重试"}")
            } finally {
                if (generation == operationGeneration) {
                    busy = false
                    if (isAdded && binding != null) render()
                }
            }
        }
    }

    private fun restartApp() {
        needsRestart = false
        requireActivity().recreate()
    }

    override fun onDestroyView() {
        ++operationGeneration
        busy = false
        binding = null
        super.onDestroyView()
    }
}
