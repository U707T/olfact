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
    private val originPage get() = requireArguments().getString(ARG_PAGE).orEmpty()
    private val savedMediaType get() = requireArguments().getString(ARG_MEDIA_TYPE)
        ?.let { runCatching { cn.olfact.core.api.MediaType.valueOf(it) }.getOrNull() }
    private val savedMimeType get() = requireArguments().getString(ARG_MIME_TYPE)

    private fun sniffedItem(url: String) = requireArguments().getLong(ARG_TAB, -1L)
        .takeIf { it > 0L && AppSettings.get(K.SNIFF_ENABLED) }
        ?.let { SnifferFeed.find(it, originPage, url) }

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
        val sniffed = sniffedItem(url)
        val request = mediaDownloadContext(
            sniffed?.pageUrl,
            originPage,
            sniffed?.headers.orEmpty(),
            sniffed?.mimeType ?: savedMimeType
        )
        val mediaType = sniffed?.type ?: savedMediaType
        val source = PlaybackSource(
            url = url,
            kind = when (mediaType) {
                cn.olfact.core.api.MediaType.HLS -> cn.olfact.player.MediaKind.HLS
                cn.olfact.core.api.MediaType.DASH -> cn.olfact.player.MediaKind.DASH
                else -> cn.olfact.player.MediaKind.detect(url, request.mimeType)
            },
            title = title.ifBlank { null },
            pageUrl = request.pageUrl.ifBlank { null },
            pageTitle = sniffed?.pageTitle,
            mimeType = request.mimeType,
            referer = request.referer.ifBlank { null },
            headers = request.headers,
            isPrivate = requireArguments().getBoolean(ARG_PRIVATE)
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
                    // 媒体直链可能带 Cookie / Authorization / 专用 UA；和嗅探面板一样透传完整元数据。
                    val center = OlfactServices.downloads ?: return@Action Nav.toast("下载模块尚未就绪")
                    val sniffed = sniffedItem(url)
                    val request = mediaDownloadContext(
                        sniffedPageUrl = sniffed?.pageUrl,
                        currentPageUrl = originPage,
                        headers = sniffed?.headers.orEmpty(),
                        mimeType = sniffed?.mimeType ?: savedMimeType
                    )
                    center.enqueue(
                        url = url,
                        name = title,
                        pageUrl = request.pageUrl,
                        referer = request.referer,
                        userAgent = request.userAgent,
                        mimeType = request.mimeType,
                        headers = request.headers
                    )
                }
            )
        ))
        container.addView(current, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        // 选择操作
        val selectActions = mutableListOf(
            Action(R.drawable.ic_star, "收藏") {
                val sniffed = sniffedItem(url)
                val inferredType = sniffed?.type ?: savedMediaType ?: cn.olfact.core.api.MediaType.fromUrl(url)
                val mediaType = inferredType.takeIf { it != cn.olfact.core.api.MediaType.OTHER }?.name
                val result = FavoriteStore.saveOrEnrich(
                    requireContext(), title, url,
                    sourcePage = if (requireArguments().getBoolean(ARG_PRIVATE)) null else originPage,
                    mediaType = mediaType,
                    mimeType = sniffed?.mimeType ?: savedMimeType
                )
                Nav.toast(when (result) {
                    FavoriteStore.SaveResult.ADDED -> "已收藏"
                    FavoriteStore.SaveResult.UPDATED -> "已补全收藏信息"
                    FavoriteStore.SaveResult.UNCHANGED -> "已经在收藏里了"
                })
            },
            Action(R.drawable.ic_star, "我的收藏") {
                // 从收藏页进播放时，再点「我的收藏」应回到收藏页，而不是再叠一层
                if (!Nav.popIfPreviousIs(FavoritesFragment::class.java)) Nav.push(FavoritesFragment())
            },
            Action(R.drawable.ic_copy, "复制") {
                val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                Nav.toast("已复制媒体地址")
            }
        )
        if (originPage.isNotBlank()) {
            selectActions.add(Action(R.drawable.ic_globe, "回来源页重嗅探") { openSourcePage() })
        }
        container.addView(sectionCard("选择操作", selectActions))

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

    private fun openSourcePage() {
        val source = originPage.takeIf { it.isNotBlank() } ?: return
        val tab = if (requireArguments().getBoolean(ARG_PRIVATE)) {
            cn.olfact.app.data.BrowserSession.open(source, incognito = true)
        } else {
            cn.olfact.app.data.BrowserSession.openOrReuseBlank(source)
        }
        Nav.root(cn.olfact.app.ui.browser.BrowserFragment.newInstance(tab.id))
        Nav.toast("已返回来源页，可重新嗅探媒体")
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
        val current = AppSettings.get(K.PLAYER_SPEED).toFloatOrNull() ?: 1f
        // 默认选中当前倍速，而不是永远停在 1.0x。
        val checked = options.indexOfFirst {
            kotlin.math.abs(it.removeSuffix("x").toFloatOrNull()?.minus(current) ?: 9f) < 0.01f
        }.coerceAtLeast(0)
        Dialogs.choose(requireContext(), "播放速度", options, checked) { index ->
            AppSettings.set(K.PLAYER_SPEED, options[index].removeSuffix("x"))
            Nav.toast("默认倍速：${options[index]}")
        }
    }

    private fun dp(v: Int) = requireContext().dpF(v.toFloat())

    companion object {
        private const val ARG_URL = "url"
        private const val ARG_TITLE = "title"
        private const val ARG_TAB = "sourceTab"
        private const val ARG_PAGE = "sourcePage"
        private const val ARG_PRIVATE = "privateSource"
        private const val ARG_MEDIA_TYPE = "favoriteMediaType"
        private const val ARG_MIME_TYPE = "favoriteMimeType"

        fun newInstance(
            url: String,
            title: String,
            tabId: Long = -1L,
            pageUrl: String? = null,
            isPrivate: Boolean = false,
            mediaType: String? = null,
            mimeType: String? = null
        ) = PlayerFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                    putString(ARG_TITLE, title)
                    putLong(ARG_TAB, tabId)
                    putString(ARG_PAGE, pageUrl)
                    putBoolean(ARG_PRIVATE, isPrivate)
                    putString(ARG_MEDIA_TYPE, mediaType)
                    putString(ARG_MIME_TYPE, mimeType)
                }
            }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
