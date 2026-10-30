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
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                if (wifi) schedule("切换到 Wi-Fi") else if (cellular) schedule("切换到移动网络")
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
    }

    private fun schedule(reason: String) {
        pendingReason = reason
        handler.removeCallbacks(dispatch)
        handler.postDelayed(dispatch, debounceMs)
    }
}
