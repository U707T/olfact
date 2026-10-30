package cn.olfact.download.external

import android.content.Context
import android.content.Intent
import android.net.Uri
import cn.olfact.download.DownloadSpec

data class ExternalDownloader(
    val packageName: String,
    val label: String,
    /** 有些下载器要求 ACTION_SEND 而不是 ACTION_VIEW。 */
    val useSend: Boolean = false,
    /** 传 URL 的 extra key（null 表示只靠 data）。 */
    val urlExtraKey: String? = null,
)

/**
 * 委派给外部下载器（11 种）。全部是 deeplink / ACTION_VIEW，
 * 不依赖任何 SDK，没装就自动过滤掉。
 */
object ExternalDownloaders {

    val TARGETS: List<ExternalDownloader> = listOf(
        ExternalDownloader("com.dv.adm", "ADM"),
        ExternalDownloader("com.dv.adm.premium", "ADM Pro"),
        ExternalDownloader("idm.internet.download.manager", "1DM"),
        ExternalDownloader("idm.internet.download.manager.plus", "1DM+"),
        ExternalDownloader("com.freedownloadmanager.fdm", "Free Download Manager"),
        ExternalDownloader("com.gianlu.aria2app", "Aria2App"),
        ExternalDownloader("com.gianlu.aria2android", "Aria2 Android"),
        ExternalDownloader("com.xunlei.downloadprovider", "迅雷"),
        ExternalDownloader("com.zlobrynya.loader.droid", "Loader Droid"),
        ExternalDownloader("com.turbo.download.manager", "Turbo Download Manager"),
        ExternalDownloader("com.m3u8.downloader", "M3U8 Downloader"),
        ExternalDownloader("com.utorrent.client", "µTorrent"),
    )

    fun available(context: Context): List<ExternalDownloader> = TARGETS.filter { installed(context, it.packageName) }

    fun installed(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    fun buildIntent(spec: DownloadSpec, target: ExternalDownloader): Intent {
        val uri = Uri.parse(spec.url)
        return if (target.useSend) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, spec.url)
                setPackage(target.packageName)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                data = uri
                type = spec.mimeType ?: "application/octet-stream"
                setPackage(target.packageName)
                target.urlExtraKey?.let { putExtra(it, spec.url) }
                spec.referer?.let { putExtra("referer", it) }
                spec.userAgent?.let { putExtra("useragent", it) }
                putExtra("title", spec.fileName)
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 返回 true 表示成功唤起；false 表示目标没装 / 不接受这个 Intent。 */
    fun launch(context: Context, spec: DownloadSpec, target: ExternalDownloader): Boolean {
        if (!installed(context, target.packageName)) return false
        return runCatching {
            context.startActivity(buildIntent(spec, target))
            true
        }.getOrDefault(false)
    }

    /** 没有明确目标时弹系统选择器。 */
    fun launchChooser(context: Context, spec: DownloadSpec): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(spec.url)
            type = spec.mimeType ?: "application/octet-stream"
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching {
            context.startActivity(Intent.createChooser(intent, "选择下载器").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}
