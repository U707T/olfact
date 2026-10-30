package cn.olfact.app.ui.settings.pages

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.BackupManager
import cn.olfact.app.data.K
import cn.olfact.app.databinding.FragmentPageBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs
import cn.olfact.app.ui.common.cardBottomMargin
import cn.olfact.app.ui.common.note
import cn.olfact.app.ui.common.sectionTitle
import cn.olfact.app.ui.common.setting
import cn.olfact.app.ui.common.settingGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 数据备份：本地导出/恢复 + WebDAV 配置 + 定时备份。 */
class BackupFragment : Fragment(R.layout.fragment_page) {

    private var binding: FragmentPageBinding? = null

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

        val last = AppSettings.get(K.BACKUP_LAST_AT)
        val latest = BackupManager.latest(ctx)

        b.content.addView(sectionTitle("本地"))
        b.content.addView(settingGroup(
            setting("立即备份", if (last > 0) "上次：${time(last)}" else "还没备份过") {
                val file = BackupManager.export(ctx)
                Nav.toast("已备份到 ${file.name}")
                render()
            },
            setting("从最近备份恢复", latest?.name ?: "没有可用备份") {
                val f = BackupManager.latest(ctx) ?: return@setting Nav.toast("没有备份文件")
                Dialogs.confirm(ctx, "恢复备份", "将覆盖当前设置与书签，确定继续？") {
                    val n = BackupManager.restore(ctx, f.readText())
                    Nav.toast("已恢复 $n 项，建议重启应用")
                }
            }
        ).also { cardBottomMargin(it) })

        b.content.addView(sectionTitle("WebDAV"))
        b.content.addView(settingGroup(
            setting("服务器", AppSettings.get(K.BACKUP_WEBDAV_URL).ifBlank { "未配置" }) {
                Dialogs.input(Nav.context()!!, "WebDAV 地址", AppSettings.get(K.BACKUP_WEBDAV_URL), "https://dav.example.com/remote.php/dav/files/me") {
                    AppSettings.set(K.BACKUP_WEBDAV_URL, it); render()
                }
            },
            setting("账号", AppSettings.get(K.BACKUP_WEBDAV_USER).ifBlank { "未配置" }) {
                Dialogs.input(Nav.context()!!, "账号", AppSettings.get(K.BACKUP_WEBDAV_USER)) {
                    AppSettings.set(K.BACKUP_WEBDAV_USER, it); render()
                }
            },
            setting("密码", if (AppSettings.get(K.BACKUP_WEBDAV_PASS).isEmpty()) "未配置" else "••••••") {
                Dialogs.input(Nav.context()!!, "密码", AppSettings.get(K.BACKUP_WEBDAV_PASS)) {
                    AppSettings.set(K.BACKUP_WEBDAV_PASS, it); render()
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
                Dialogs.choose(Nav.context()!!, "自动备份", listOf("关闭", "每天", "每周"), 0) { idx ->
                    AppSettings.set(K.BACKUP_AUTO, listOf("off", "daily", "weekly")[idx]); render()
                }
            }
        ).also { cardBottomMargin(it) })

        b.content.addView(note("备份内容是 zip + JSON（设置 / 书签 / 历史 / 标签页），不含 Cookie 与 AI 密钥。恢复后建议重启应用。"))
    }

    private fun time(at: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
