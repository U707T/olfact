package cn.olfact.app.data

/**
 * 自定义 DoH 上游地址的解析与校验。
 *
 * 规则刻意保守：
 * - 不写协议时补 `https://`（DoH 标准就是 HTTPS）；
 * - 允许 `http://`，但只在本地地址（localhost / 127.0.0.1 / *.local）上放行，公网必须 https；
 * - 只给了域名没给路径时补 `/dns-query`（RFC 8484 的通行路径）；
 * - 主机名必须是域名（带点）或 IP，拒绝空格与乱七八糟的字符。
 */
object DohUpstreams {

    sealed interface Result {
        data class Ok(val url: String, val host: String, val local: Boolean) : Result
        data class Failed(val reason: String) : Result
    }

    private val URL_RE = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#\\s]+)(/[^?#\\s]*)?$")
    private val IPV4_RE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val HOST_RE = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    fun parse(raw: String?): Result {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Result.Failed("地址为空")
        if (text.any { it.isWhitespace() }) return Result.Failed("地址里不能有空格")

        val withScheme = if (text.contains("://")) text else "https://$text"
        val uri = runCatching { java.net.URI(withScheme) }.getOrNull()
            ?: return Result.Failed("格式应为 https://域名/路径")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return Result.Failed("只支持 https（本地可用 http）")
        val host = uri.host?.removeSurrounding("[", "]")?.lowercase()
            ?: return Result.Failed("主机名不合法")
        if (uri.rawUserInfo != null || uri.rawFragment != null) return Result.Failed("地址不能包含用户信息或片段")
        if (uri.port !in -1..65535 || uri.port == 0 || uri.rawAuthority?.endsWith(':') == true) return Result.Failed("端口不合法")
        val isIp = IPV4_RE.matches(host) || host.contains(':')
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        val privateV4 = IPV4_RE.matches(host) && octets.size == 4 && octets.all { it in 0..255 } && (
            octets[0] == 127 || octets[0] == 10 ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 169 && octets[1] == 254)
            )
        val privateV6 = host.contains(':') && runCatching {
            val address = java.net.InetAddress.getByName(host)
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
        }.getOrDefault(false)
        val isLocal = host == "localhost" || host.endsWith(".local") || privateV4 || privateV6
        if (!isIp && !isLocal && !HOST_RE.matches(host)) return Result.Failed("主机名不合法：$host")
        if (scheme == "http" && !isLocal) return Result.Failed("公网地址必须用 https")
        val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
        val authority = (if (host.contains(':')) "[$host]" else host) + (port?.let { ":$it" } ?: "")
        val path = uri.rawPath.orEmpty()
        val finalPath = if (path.isEmpty() || path == "/") "/dns-query" else path
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return Result.Ok("$scheme://$authority$finalPath$query", host, isLocal)
    }

    /** 只要 URL；不合法返回 null。 */
    fun normalize(raw: String?): String? = (parse(raw) as? Result.Ok)?.url
}
