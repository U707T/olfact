package android.net
class Uri private constructor(private val value: String) {
    private val parsed = runCatching { java.net.URI(value) }.getOrNull()
    val host: String? get() = parsed?.host
    val scheme: String? get() = parsed?.scheme
    override fun toString() = value
    companion object { fun parse(value: String) = Uri(value) }
}
class Network
class NetworkCapabilities {
    fun hasTransport(type: Int) = false
    companion object { const val TRANSPORT_WIFI = 1; const val TRANSPORT_CELLULAR = 0; const val TRANSPORT_ETHERNET = 3 }
}
class ConnectivityManager {
    open class NetworkCallback {
        open fun onAvailable(network: Network) {}
        open fun onLost(network: Network) {}
        open fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {}
    }
    fun registerDefaultNetworkCallback(callback: NetworkCallback) {}
    fun unregisterNetworkCallback(callback: NetworkCallback) {}
}
