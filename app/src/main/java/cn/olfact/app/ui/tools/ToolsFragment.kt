package cn.olfact.app.ui.tools

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.databinding.FragmentPageBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.cardBottomMargin
import cn.olfact.app.ui.common.note
import cn.olfact.app.ui.common.sectionTitle
import cn.olfact.app.ui.common.setting
import cn.olfact.app.ui.common.settingGroup
import cn.olfact.app.ui.settings.pages.LogFragment

/** 工具箱：扫一扫 / 当前页二维码 / 网络日志。之前「工具箱」直接跳设置里的协议页，名不副实。 */
class ToolsFragment : Fragment(R.layout.fragment_page) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentPageBinding.bind(view)
        b.header.title = "工具箱"
        b.header.onBack = { Nav.pop() }

        b.content.removeAllViews()
        b.content.addView(sectionTitle("工具"))
        b.content.addView(settingGroup(
            setting("扫一扫", "识别二维码 / 条形码，可搜索或复制结果") {
                runCatching { startActivity(Intent(requireContext(), ScanActivity::class.java)) }
                    .onFailure { Nav.toast("相机不可用：${it.message}") }
            },
            setting("分享当前页二维码", "把当前网址生成二维码") {
                val url = BrowserSession.current()?.url
                if (url.isNullOrBlank()) {
                    Nav.toast("当前没有打开的网页")
                } else {
                    Nav.push(QrShareFragment.newInstance(url))
                }
            },
            setting("网络日志", "崩溃与网络请求记录") { Nav.push(LogFragment()) }
        ).also { cardBottomMargin(it) })

        b.content.addView(note("扫一扫使用 CameraX + ZXing；二维码分享只生成当前页 URL，不上传任何内容。"))
    }
}
