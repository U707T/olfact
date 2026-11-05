package cn.olfact.web.doh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * 网络变化监听：切 Wi-Fi / 换卡 / 重连后清缓存、重新竞速、重建隧道（TECH-PLAN §4.5-5）。
 * 500ms 防抖，避免连着切换时反复重建。
 */
class NetworkWatcher(
    context: Context,
    private val onChanged: (reason: String) -> Unit,
    private val debounceMs: Long = 500L,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var pendingReason: String? = null

    /**
     * 上一次的传输类型。
     *
     * `onCapabilitiesChanged` 并不是只在「换网」时回调：信号强弱、带宽估计变化都会触发。
     * 之前每来一次就清缓存 + 重新竞速 + 重建隧道，DoH 用户会看到隧道反复抖动。
     * 只有 WLAN/移动网络真的切换了才处理。
     */
    @Volatile
    private var lastTransport: String? = null

    private val dispatch = Runnable {
        val reason = pendingReason ?: return@Runnable
        pendingReason = null
        onChanged(reason)
    }

    fun start() {
        val manager = connectivityManager ?: return
        if (callback != null) return
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = schedule("网络可用")

            override fun onLost(network: Network) = schedule("网络断开")

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
                if (type == lastTransport) return
                lastTransport = type
                schedule(
                    when (type) {
                        "wifi" -> "切换到 Wi-Fi"
                        "cellular" -> "切换到移动网络"
                        "ethernet" -> "切换到有线网络"
                        else -> "网络变化"
                    }
                )
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(networkCallback) }
            .onSuccess { callback = networkCallback }
    }

    fun stop() {
        val manager = connectivityManager ?: return
        val networkCallback = callback ?: return
        runCatching { manager.unregisterNetworkCallback(networkCallback) }
        callback = null
        handler.removeCallbacks(dispatch)
        pendingReason = null
        lastTransport = null
    }

    private fun schedule(reason: String) {
        pendingReason = reason
        handler.removeCallbacks(dispatch)
        handler.postDelayed(dispatch, debounceMs)
    }
}
