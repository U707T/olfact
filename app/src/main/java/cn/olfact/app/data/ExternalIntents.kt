package cn.olfact.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri

/** 外部协议（intent://、market://…）交给系统。 */
internal fun openExternal(context: Context, url: String): Boolean {
    // 「允许网页打开应用」关闭时，只放行 http(s)，其它 scheme（intent://、market://…）一律不拉起。
    if (!AppSettings.get(K.ALLOW_OPEN_APP) && !url.startsWith("http://") && !url.startsWith("https://")) {
        return false
    }
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
