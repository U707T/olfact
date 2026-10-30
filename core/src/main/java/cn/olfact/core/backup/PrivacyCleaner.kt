package cn.olfact.core.backup

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import cn.olfact.core.b4.B4Log
import java.io.File

/**
 * 隐私清理：缓存 / Cookie / WebStorage / 表单记录。
 *
 * 所有系统 API 都包在 runCatching 里——不同 ROM 的 WebView 实现差异很大，
 * 清不掉某一项也不能让"退出清数据"整个失败。
 */
class PrivacyCleaner(private val context: Context) {

    /** 可插拔的清理项（搜索记录、历史等由各自模块实现）。 */
    fun interface Cleanable {
        fun clear(): Int
    }

    data class Report(
        val cacheBytes: Long = 0,
        val cookiesCleared: Boolean = false,
        val webStorageCleared: Boolean = false,
        val extraItems: Int = 0
    )

    fun cacheSize(): Long = dirSize(context.cacheDir) + dirSize(File(context.filesDir, "webview_cache"))

    fun clearCache(): Long {
        val before = cacheSize()
        deleteChildren(context.cacheDir)
        runCatching { android.webkit.WebView(context).clearCache(true) }
            .onFailure { B4Log.w(TAG, "WebView.clearCache 失败", it) }
        return before
    }

    fun clearCookies(): Boolean = runCatching {
        val manager = CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
        true
    }.getOrElse {
        B4Log.w(TAG, "清 Cookie 失败", it)
        false
    }

    fun clearWebStorage(): Boolean = runCatching {
        WebStorage.getInstance().deleteAllData()
        true
    }.getOrElse {
        B4Log.w(TAG, "清 WebStorage 失败", it)
        false
    }

    fun clearFormData(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        WebViewDatabase.getInstance(context).clearFormData()
        @Suppress("DEPRECATION")
        WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
        true
    }.getOrElse { false }

    fun clearAll(extras: List<Cleanable> = emptyList()): Report {
        val bytes = clearCache()
        val cookies = clearCookies()
        val storage = clearWebStorage() and clearFormData()
        var extra = 0
        for (cleanable in extras) {
            extra += runCatching { cleanable.clear() }.getOrDefault(0)
        }
        val report = Report(bytes, cookies, storage, extra)
        B4Log.d(TAG, "清理完成：${bytes / 1024} KB，Cookie=$cookies，WebStorage=$storage，额外 $extra 项")
        return report
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it) } ?: 0
    }

    private fun deleteChildren(dir: File) {
        dir.listFiles()?.forEach { file ->
            runCatching { file.deleteRecursively() }
        }
    }

    companion object {
        private const val TAG = "Privacy"
    }
}
