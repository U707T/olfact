package cn.olfact.app.ui.settings.pages

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.databinding.FragmentPageBinding
import cn.olfact.app.nav.Nav
import cn.olfact.core.uikit.dpF

/** 协议页：用户协议 / 隐私政策 / 开源许可，纯静态文本。 */
class ProtocolFragment : Fragment(R.layout.fragment_page) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentPageBinding.bind(view)
        val id = requireArguments().getString(ARG_ID) ?: "terms"
        b.header.title = when (id) {
            "privacy" -> "隐私政策"
            "licenses" -> "开源许可"
            else -> "用户协议"
        }
        b.header.onBack = { Nav.pop() }

        b.content.addView(TextView(requireContext()).apply {
            text = when (id) {
                "privacy" -> PRIVACY
                "licenses" -> LICENSES
                else -> TERMS
            }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setLineSpacing(0f, 1.55f)
            setPadding(0, requireContext().dpF(8f), 0, requireContext().dpF(32f))
            setTextIsSelectable(true)
        })
    }

    companion object {
        private const val ARG_ID = "id"
        fun newInstance(id: String) = ProtocolFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, id) }
        }

        private const val TERMS = """嗅觉浏览器 用户协议（摘要）

1. 本应用是本地浏览器工具，不提供任何内容源。
2. 你访问的网页内容由对应网站提供，需遵守该网站的服务条款与当地法律。
3. 广告拦截、脚本注入等功能只作用于你自己的设备与浏览会话。
4. 本应用不上传浏览记录、书签与密钥；备份文件默认保存在本机。
5. 软件按「现状」提供，不对第三方页面的可用性作担保。"""

        private const val PRIVACY = """隐私政策（摘要）

我们收集什么：不收集。
存储在哪：设置、书签、历史、标签页均保存在应用私有目录；AI 密钥用 Keystore 加密。
网络请求：仅在你主动访问网页或使用 AI 功能时发起，不经过任何中转服务器。
DoH：开启后 DNS 查询直连你选择的上游（默认 doh.pub），不经过本应用服务器。
权限：相机仅用于扫码；位置权限默认关闭，网页申请时逐次询问。
删除数据：设置 → 隐私与安全 → 退出时清除数据 / 清除 Cookie，或直接清除应用数据。"""

        private const val LICENSES = """开源许可

AndroidX / AppCompat — Apache License 2.0
Kotlin / kotlinx.coroutines — Apache License 2.0
OkHttp — Apache License 2.0
Media3 (ExoPlayer) — Apache License 2.0
Coil — Apache License 2.0
adblock-rust (Brave) — Mozilla Public License 2.0

图标：部分沿用原版应用素材，仅用于界面复刻与个人使用。"""
    }
}
