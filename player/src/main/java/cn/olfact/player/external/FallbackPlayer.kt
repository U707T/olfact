package cn.olfact.player.external

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import cn.olfact.player.MediaKind
import cn.olfact.player.PlaybackSource

/**
 * 内置播放器起不来时的兜底：委派给系统里装的播放器。
 * 包名清单参照原版 PlayerChooser（20+），全部走 ACTION_VIEW，
 * 不支持列表里的包时自动退回系统 chooser。
 */
object FallbackPlayer {

    /** 20+ 常见播放器。顺序即 chooser 中的优先顺序。 */
    val TARGETS: List<ExternalPlayerTarget> = listOf(
        ExternalPlayerTarget("com.mxtech.videoplayer.ad", "MX Player"),
        ExternalPlayerTarget("com.mxtech.videoplayer.pro", "MX Player Pro"),
        ExternalPlayerTarget("org.videolan.vlc", "VLC"),
        ExternalPlayerTarget("com.brouken.player", "Just Player"),
        ExternalPlayerTarget("is.xyz.mpv", "mpv-android"),
        ExternalPlayerTarget("com.kmplayer", "KMPlayer"),
        ExternalPlayerTarget("com.bsplayer.bspandroid.free", "BSPlayer"),
        ExternalPlayerTarget("com.clov4r.android.nil", "MoboPlayer"),
        ExternalPlayerTarget("com.newin.nplayer.pro", "nPlayer"),
        ExternalPlayerTarget("com.playerxtreme.android", "PlayerXtreme"),
        ExternalPlayerTarget("com.archos.mediacenter.video", "Archos Video"),
        ExternalPlayerTarget("com.instantbits.cast.webvideo", "Web Video Cast"),
        ExternalPlayerTarget("com.inshot.xplayer", "XPlayer"),
        ExternalPlayerTarget("com.moviestar.android", "Moviestar"),
        ExternalPlayerTarget("com.playit.videoplayer", "PlayIt"),
        ExternalPlayerTarget("videoplayer.vd.video.music.player", "VD Player"),
        ExternalPlayerTarget("com.hdplayer.videoplayer", "HD Player"),
        ExternalPlayerTarget("video.player.videoplayer", "Video Player"),
        ExternalPlayerTarget("com.mediaplayer.video.player", "Media Player"),
        ExternalPlayerTarget("com.videoplayer.hdplayer", "HD Video Player"),
        ExternalPlayerTarget("com.xiaomi.mitv.videoplayer", "小米视频"),
        ExternalPlayerTarget("com.tencent.qqlive", "腾讯视频"),
        ExternalPlayerTarget("tv.danmaku.bili", "哔哩哔哩"),
        ExternalPlayerTarget("com.google.android.videos", "Google TV"),
    )

    /** 只返回当前设备真的装了的。 */
    fun availableTargets(context: Context): List<ExternalPlayerTarget> =
        TARGETS.filter { installed(context, it.packageName) }

    fun installed(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    fun buildIntent(context: Context, source: PlaybackSource, target: ExternalPlayerTarget? = null): Intent {
        val mime = MediaKind.externalMime(source.kind, source.mimeType)
        val base = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(source.url), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (source.title != null) base.putExtra(Intent.EXTRA_TITLE, source.title)
        if (target != null) {
            if (target.activity != null) {
                base.setClassName(target.packageName, target.activity)
            } else {
                base.setPackage(target.packageName)
            }
            if (target.urlExtraKey != null) base.putExtra(target.urlExtraKey, source.url)
            if (target.passHeaders && source.headers.isNotEmpty()) {
                base.putExtra("headers", Bundle().apply { source.headers.forEach { (k, v) -> putString(k, v) } })
            }
        }
        return base
    }

    /**
     * 交给外部播放器。返回 true 表示真的启动了；false 表示没装任何可用播放器
     * （调用方应提示"没有可用的外部播放器"）。
     */
    fun open(context: Context, source: PlaybackSource, target: ExternalPlayerTarget? = null): Boolean {
        if (target != null) {
            val intent = buildIntent(context, source, target)
            return runCatching { context.startActivity(intent); true }.getOrDefault(false)
        }
        val intent = buildIntent(context, source, null)
        val chooser = Intent.createChooser(intent, "选择播放器")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 不要用 resolveActivity 当门禁：Android 11+ 没有 <queries> 时它返回 null，
        // 明明能弹选择器也会被判成「没有可用播放器」。直接尝试启动，失败再返回 false。
        return runCatching { context.startActivity(chooser); true }.getOrDefault(false)
    }

    fun chooserLabel(target: ExternalPlayerTarget): String =
        if (TextUtils.isEmpty(target.label)) target.packageName else target.label
}
