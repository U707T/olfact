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
        val match = URL_RE.matchEntire(withScheme) ?: return Result.Failed("格式应为 https://域名/路径")

        val scheme = match.groupValues[1].lowercase()
        if (scheme != "https" && scheme != "http") return Result.Failed("只支持 https（本地可用 http）")

        var authority = match.groupValues[2].lowercase()
        // 去掉默认端口，避免同一个上游写成两种形式
        if (scheme == "https" && authority.endsWith(":443")) authority = authority.removeSuffix(":443")
        if (scheme == "http" && authority.endsWith(":80")) authority = authority.removeSuffix(":80")
        val host = authority.substringBefore(':')
        if (host.isEmpty()) return Result.Failed("缺少主机名")

        val isIp = IPV4_RE.matches(host) || host.contains(':')
        val isLocal = host == "localhost" || host == "127.0.0.1" || host.endsWith(".local")
        if (!isIp && !isLocal && !HOST_RE.matches(host)) return Result.Failed("主机名不合法：$host")
        if (scheme == "http" && !isIp && !isLocal) {
            return Result.Failed("公网地址必须用 https")
        }

        val path = match.groupValues[3]
        val finalPath = if (path.isEmpty() || path == "/") "/dns-query" else path
        return Result.Ok(
            url = "$scheme://$authority$finalPath",
            host = host,
            local = isLocal
        )
    }

    /** 只要 URL；不合法返回 null。 */
    fun normalize(raw: String?): String? = (parse(raw) as? Result.Ok)?.url
}
