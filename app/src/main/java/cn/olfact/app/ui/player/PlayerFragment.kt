package cn.olfact.app.ui.player

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.data.FavoriteStore
import cn.olfact.app.data.K
import cn.olfact.app.databinding.FragmentPlayerBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.dpF
import cn.olfact.player.PlaybackIntents
import cn.olfact.player.PlaybackOptions
import cn.olfact.player.PlaybackSource

/**
 * 播放详情页：起播入口 + 收藏/下载/复制等操作。
 *
 * 真正的播放器是 player 模块的 `VideoPlayerActivity`；本页不伪造进度条，
 * 点「播放」才把 `PlaybackSource` 交给它。
 */
class PlayerFragment : Fragment(R.layout.fragment_player) {

    private var binding: FragmentPlayerBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentPlayerBinding.bind(view)
        binding = b
        val url = requireArguments().getString(ARG_URL).orEmpty()
        val title = requireArguments().getString(ARG_TITLE).orEmpty().ifBlank { url.substringAfterLast('/') }

        b.back.setOnClickListener { Nav.pop() }
        b.replayBox.setOnClickListener { startPlayback(url, title) }
        b.playPause.setOnClickListener { startPlayback(url, title) }
        // 画中画由 player 模块的 VideoPlayerActivity 处理；这里不是 PiP 宿主，隐藏入口避免假动作。
        b.pip.visibility = View.GONE

        // 这里不是真正的播放器（真正的控制条在 player 模块的 Activity 里）。
        // 隐藏假的进度条/全屏按钮，避免用户以为拖动或全屏应该在这里生效。
        b.seek.visibility = View.GONE
        b.fullscreen.visibility = View.GONE
        b.position.text = ""
        b.duration.text = ""

        buildContent(b.content, url, title)
    }

    /** 起播交给 B3 的 Media3 播放器（独立 Activity，支持 PiP / 倍速 / 断点续播）。 */
    private fun startPlayback(url: String, title: String) {
        if (url.isBlank()) return
        val page = BrowserSession.current()
        val source = PlaybackSource(
            url = url,
            title = title.ifBlank { null },
            pageUrl = page?.url,
            pageTitle = page?.title,
            referer = page?.url,
            headers = page?.url?.let { mapOf("Referer" to it) }.orEmpty(),
            live = url.substringBefore('?').endsWith(".m3u8")
        )
        val speed = AppSettings.get(K.PLAYER_SPEED).toFloatOrNull()?.coerceIn(0.5f, 2.0f) ?: 1f
        // rememberSpeed=false：让「默认倍速」每次都生效；播放器内的临时调速仍可用。
        val options = PlaybackOptions(
            speed = speed,
            rememberSpeed = false,
            autoPipOnLeave = AppSettings.get(K.PLAYER_PIP)
        )
        runCatching { PlaybackIntents.play(requireContext(), source, options) }
            .onFailure { Nav.toast("播放器启动失败：${it.message}") }
    }

    private fun buildContent(container: LinearLayout, url: String, title: String) {
        val ctx = requireContext()
        container.removeAllViews()

        // 当前播放
        val current = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, cn.olfact.core.R.drawable.uikit_bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        current.addView(TextView(ctx).apply {
            text = "当前播放：$title"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        current.addView(actionRow(
            listOf(
                Action(R.drawable.ic_play, "播放") { startPlayback(url, title) },
                Action(R.drawable.ic_list, "嗅探结果") { SnifferSheet.show(requireActivity()) },
                Action(R.drawable.ic_download, "下载") {
                    OlfactServices.downloads?.enqueue(url, title)
                    Nav.toast("已加入下载")
                }
            )
        ))
        container.addView(current, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        // 选择操作
        container.addView(sectionCard("选择操作", listOf(
            Action(R.drawable.ic_star, "收藏") {
                val added = FavoriteStore.add(requireContext(), title, url)
                Nav.toast(if (added) "已收藏" else "已经在收藏里了")
            },
            Action(R.drawable.ic_star, "我的收藏") {
                // 从收藏页进播放时，再点「我的收藏」应回到收藏页，而不是再叠一层
                if (!Nav.popIfPreviousIs(FavoritesFragment::class.java)) {
                    Nav.push(FavoritesFragment())
                }
            },
            Action(R.drawable.ic_copy, "复制") {
                val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                Nav.toast("已复制媒体地址")
            }
        )))

        // 更多操作：只留真实生效的默认倍速
        container.addView(sectionCard("更多操作", listOf(
            Action(R.drawable.ic_play, "默认倍速") { speedDialog() }
        )))

        container.addView(TextView(ctx).apply {
            text = "媒体地址：$url"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_hint))
            setPadding(dp(4), dp(8), dp(4), 0)
        })
    }

    private fun sectionCard(title: String, actions: List<Action>): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, cn.olfact.core.R.drawable.uikit_bg_card)
            setPadding(dp(16), dp(12), dp(16), dp(10))
        }
        card.addView(TextView(ctx).apply {
            text = title
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(actionRow(actions))
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        return card
    }

    private data class Action(val icon: Int, val label: String, val onClick: () -> Unit)

    private fun actionRow(actions: List<Action>): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.forEach { action ->
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { action.onClick() }
                addView(ImageView(ctx).apply {
                    setImageResource(action.icon)
                    imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_primary)
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                })
                addView(TextView(ctx).apply {
                    text = action.label
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    private fun speedDialog() {
        val options = listOf("0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        Dialogs.choose(requireContext(), "播放速度", options, 1) { index ->
            AppSettings.set(K.PLAYER_SPEED, options[index].removeSuffix("x"))
            Nav.toast("默认倍速：${options[index]}")
        }
    }

    private fun dp(v: Int) = requireContext().dpF(v.toFloat())

    companion object {
        private const val ARG_URL = "url"
        private const val ARG_TITLE = "title"
        fun newInstance(url: String, title: String) = PlayerFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_URL, url)
                putString(ARG_TITLE, title)
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
