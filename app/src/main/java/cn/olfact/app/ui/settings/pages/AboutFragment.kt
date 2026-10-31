package cn.olfact.app.ui.settings.pages

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.BuildConfig
import cn.olfact.app.MainActivity
import cn.olfact.app.R
import cn.olfact.app.databinding.FragmentPageBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.cardBottomMargin
import cn.olfact.app.ui.common.note
import cn.olfact.app.ui.common.sectionTitle
import cn.olfact.app.ui.common.setting
import cn.olfact.app.ui.common.settingGroup
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.dpF

/** 关于与帮助：品牌区、默认浏览器入口、模块接入状态、协议入口。 */
class AboutFragment : Fragment(R.layout.fragment_page) {

    private var binding: FragmentPageBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentPageBinding.bind(view)
        binding = b
        b.header.title = "关于与帮助"
        b.header.onBack = { Nav.pop() }
        render()
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        b.content.removeAllViews()

        val hero = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, requireContext().dpF(24f), 0, requireContext().dpF(8f))
        }
        hero.addView(ImageView(ctx).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(requireContext().dpF(72f), requireContext().dpF(72f))
        })
        hero.addView(TextView(ctx).apply {
            text = getString(R.string.app_name)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, requireContext().dpF(10f), 0, 0)
        })
        hero.addView(TextView(ctx).apply {
            text = "v${BuildConfig.VERSION_NAME} · ${getString(R.string.app_slogan)}"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(0, requireContext().dpF(4f), 0, 0)
        })
        b.content.addView(hero)

        val activity = requireActivity()
        val isDefault = (activity as? MainActivity)?.isDefaultBrowser() == true
        b.content.addView(settingGroup(
            setting(
                "设为默认浏览器",
                if (isDefault) "已生效" else "点击申请",
                if (isDefault) "外部链接会直接进本应用" else "通过系统角色管理申请 ROLE_BROWSER"
            ) {
                (activity as? MainActivity)?.requestDefaultBrowser() ?: Nav.toast("当前环境不支持")
            }
        ).also { cardBottomMargin(it) })

        b.content.addView(sectionTitle("模块状态"))
        val modules = OlfactServices.modules()
        val group = cn.olfact.core.uikit.SettingGroup(ctx)
        modules.forEach { (name, ready) ->
            group.addRow(setting(name, if (ready) "已接入" else "未接入").also {
                it.setValueColor(if (ready) R.color.text_accent else R.color.text_hint)
            }, dividerInsetDp = 16)
        }
        b.content.addView(group)
        cardBottomMargin(group)

        b.content.addView(sectionTitle("其它"))
        b.content.addView(settingGroup(
            setting("用户协议") { Nav.push(ProtocolFragment.newInstance("terms")) },
            setting("隐私政策") { Nav.push(ProtocolFragment.newInstance("privacy")) },
            setting("开源许可") { Nav.push(ProtocolFragment.newInstance("licenses")) },
            setting("问题反馈") {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/U707T/olfact/issues")))
                }.onFailure { Nav.toast("没有可用的浏览器") }
            }
        ).also { cardBottomMargin(it) })

        b.content.addView(note("构建信息：versionCode ${BuildConfig.VERSION_CODE} · ${BuildConfig.BUILD_TYPE} · minSdk 24 · targetSdk 37"))
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
