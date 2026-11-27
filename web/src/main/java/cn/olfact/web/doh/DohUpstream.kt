package cn.olfact.web.doh

/**
 * 一个 DoH 上游。区域决定它是进国内池还是国外池（TECH-PLAN §4.5-4）。
 *
 * [bootstrap] 是该 DoH 主机名对应的硬编码 IP，用于打破“解析 DoH 域名又要先有 DNS”的循环。
 */
data class DohUpstream(
    val id: String,
    val label: String,
    val url: String,
    val region: Region,
    val supportsEcs: Boolean,
    val bootstrap: List<String>
) {

    enum class Region { DOMESTIC, FOREIGN }

    val host: String get() = runCatching { java.net.URI(url).host?.removeSurrounding("[", "]").orEmpty() }.getOrDefault("")

    companion object {
        val ALI = DohUpstream(
            id = "ali",
            label = "阿里 DoH",
            url = "https://dns.alidns.com/dns-query",
            region = Region.DOMESTIC,
            supportsEcs = true,
            bootstrap = listOf("223.5.5.5", "223.6.6.6")
        )
        val TENCENT = DohUpstream(
            id = "tencent",
            label = "腾讯 DoH",
            url = "https://doh.pub/dns-query",
            region = Region.DOMESTIC,
            supportsEcs = true,
            bootstrap = listOf("1.12.12.12", "120.53.53.53")
        )
        val CLOUDFLARE = DohUpstream(
            id = "cloudflare",
            label = "Cloudflare",
            url = "https://cloudflare-dns.com/dns-query",
            region = Region.FOREIGN,
            supportsEcs = false,
            bootstrap = listOf("1.1.1.1", "1.0.0.1")
        )
        val QUAD9 = DohUpstream(
            id = "quad9",
            label = "Quad9",
            url = "https://dns.quad9.net/dns-query",
            region = Region.FOREIGN,
            supportsEcs = false,
            bootstrap = listOf("9.9.9.9", "149.112.112.112")
        )
        val GOOGLE = DohUpstream(
            id = "google",
            label = "Google",
            url = "https://dns.google/dns-query",
            region = Region.FOREIGN,
            supportsEcs = false,
            bootstrap = listOf("8.8.8.8", "8.8.4.4")
        )

        /** 启动时 5 路竞速的默认集合（国内 2 + 国外 3）。 */
        val DEFAULTS: List<DohUpstream> = listOf(ALI, TENCENT, CLOUDFLARE, QUAD9, GOOGLE)

        fun byId(id: String?): DohUpstream? = DEFAULTS.firstOrNull { it.id == id }

        /** 按 URL 找内置上游（忽略大小写、末尾斜杠与默认端口）。 */
        fun byUrl(url: String?): DohUpstream? {
            val target = normalizeUrl(url) ?: return null
            // 旧设置页曾把这个地址写成 Cloudflare 预设，保留已保存选择。
            if (target == "https://1.1.1.1/dns-query") return CLOUDFLARE
            return DEFAULTS.firstOrNull { normalizeUrl(it.url) == target }
        }

        /** 简单归一化：去空白、补 https、去末尾斜杠。 */
        fun normalizeUrl(raw: String?): String? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val withScheme = if (text.startsWith("http://", true) || text.startsWith("https://", true)) text else "https://$text"
            return runCatching {
                val uri = java.net.URI(withScheme)
                val scheme = uri.scheme.lowercase(java.util.Locale.ROOT)
                val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return null
                val port = if ((scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) -1 else uri.port
                buildString {
                    append(scheme).append("://")
                    uri.rawUserInfo?.let { append(it).append('@') }
                    append(host)
                    if (port != -1) append(':').append(port)
                    append(uri.rawPath.orEmpty().trimEnd('/'))
                    uri.rawQuery?.let { append('?').append(it) }
                    uri.rawFragment?.let { append('#').append(it) }
                }
            }.getOrNull()
        }

        /** bootstrap 表，供 [BootstrapDns] 使用。 */
        fun bootstrapTable(upstreams: List<DohUpstream> = DEFAULTS): Map<String, List<String>> =
            upstreams.associate { it.host to it.bootstrap }
    }
}
