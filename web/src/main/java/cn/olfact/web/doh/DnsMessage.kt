package cn.olfact.web.doh

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress

/** DNS 报文格式错误（对端返回了不可解析的内容）。 */
class DnsFormatException(message: String) : Exception(message)

/**
 * 客户端子网（EDNS0 option 8），国内池带上它可以让 CDN 返回就近节点。
 */
data class EcsSubnet(val address: ByteArray, val prefixLength: Int) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EcsSubnet) return false
        return prefixLength == other.prefixLength && address.contentEquals(other.address)
    }

    override fun hashCode(): Int = 31 * address.contentHashCode() + prefixLength

    companion object {
        /**
         * 解析 `116.24.0.0/16` 或 `2408:8000::/32`。非字面量地址一律返回 null（绝不做 DNS 查询）。
         */
        fun parse(text: String): EcsSubnet? {
            val raw = text.trim()
            if (raw.isEmpty()) return null
            val addrPart = raw.substringBefore('/')
            if (!isIpLiteral(addrPart)) return null
            val bytes = runCatching { InetAddress.getByName(addrPart).address }.getOrNull() ?: return null
            val defaultPrefix = if (bytes.size == 4) 24 else 56
            val prefix = raw.substringAfter('/', "").toIntOrNull() ?: defaultPrefix
            val clamped = prefix.coerceIn(0, bytes.size * 8)
            val byteLen = (clamped + 7) / 8
            val truncated = bytes.copyOf(byteLen)
            if (clamped % 8 != 0 && byteLen > 0) {
                val mask = (0xFF shl (8 - clamped % 8)) and 0xFF
                truncated[byteLen - 1] = (truncated[byteLen - 1].toInt() and mask).toByte()
            }
            return EcsSubnet(truncated, clamped)
        }

        fun isIpLiteral(host: String): Boolean {
            if (host.isEmpty()) return false
            if (host.contains(':')) return host.all { it.isDigit() || it in "abcdefABCDEF:." }
            val parts = host.split('.')
            return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) }
        }
    }
}

data class DnsRecord(val type: Int, val ttlSeconds: Int, val data: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is DnsRecord && type == other.type && ttlSeconds == other.ttlSeconds && data.contentEquals(other.data)

    override fun hashCode(): Int = (type * 31 + ttlSeconds) * 31 + data.contentHashCode()
}

data class DnsResponse(
    val id: Int,
    val rcode: Int,
    val truncated: Boolean,
    val records: List<DnsRecord>,
    val minTtlSeconds: Int
) {
    val isSuccess: Boolean get() = rcode == DnsMessage.RCODE_NOERROR
    val isNameError: Boolean get() = rcode == DnsMessage.RCODE_NXDOMAIN

    fun addresses(): List<InetAddress> = records.mapNotNull { record ->
        val expected = when (record.type) {
            DnsMessage.TYPE_A -> 4
            DnsMessage.TYPE_AAAA -> 16
            else -> return@mapNotNull null
        }
        if (record.data.size != expected) return@mapNotNull null
        runCatching { InetAddress.getByAddress(record.data) }.getOrNull()
    }.distinct()
}

/**
 * 最小可用的 RFC 1035 / RFC 8484 报文编解码，只做 A/AAAA 与 EDNS0(ECS)。
 * 自己实现是为了拿到 TTL（OkHttp 的 DnsOverHttps 不暴露 TTL），并避免多引一个依赖。
 */
object DnsMessage {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    private const val TYPE_OPT = 41
    private const val OPTION_ECS = 8
    private const val CLASS_IN = 1
    private const val FLAG_RD = 0x0100
    private const val FLAG_TC = 0x0200

    const val RCODE_NOERROR = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    fun buildQuery(id: Int, name: String, type: Int, ecs: EcsSubnet? = null): ByteArray {
        val out = ByteArrayOutputStream(96)
        val d = DataOutputStream(out)
        d.writeShort(id and 0xFFFF)
        d.writeShort(FLAG_RD)
        d.writeShort(1)                                  // QDCOUNT
        d.writeShort(0)                                  // ANCOUNT
        d.writeShort(0)                                  // NSCOUNT
        d.writeShort(if (ecs != null) 1 else 0)          // ARCOUNT
        writeName(d, name)
        d.writeShort(type)
        d.writeShort(CLASS_IN)
        if (ecs != null) {
            d.writeByte(0)                               // 根域名
            d.writeShort(TYPE_OPT)
            d.writeShort(1232)                           // UDP payload size
            d.writeInt(0)                                // ext-rcode / version / flags
            val rdata = ByteArrayOutputStream(20)
            val rd = DataOutputStream(rdata)
            rd.writeShort(OPTION_ECS)
            rd.writeShort(4 + ecs.address.size)
            rd.writeShort(if (ecs.address.size == 4) 1 else 2)
            rd.writeByte(ecs.prefixLength)
            rd.writeByte(0)                              // scope prefix
            rd.write(ecs.address)
            rd.flush()
            val payload = rdata.toByteArray()
            d.writeShort(payload.size)
            d.write(payload)
        }
        d.flush()
        return out.toByteArray()
    }

    fun parseResponse(bytes: ByteArray): DnsResponse {
        val r = Reader(bytes)
        val id = r.u16()
        val flags = r.u16()
        val qdCount = r.u16()
        val anCount = r.u16()
        r.u16() // NSCOUNT
        r.u16() // ARCOUNT
        repeat(qdCount) {
            r.name()
            r.skip(4)
        }
        val records = ArrayList<DnsRecord>(anCount)
        repeat(anCount) {
            r.name()
            val type = r.u16()
            r.u16()  // class
            val ttl = r.u32()
            val rdLength = r.u16()
            val data = r.bytes(rdLength)
            records += DnsRecord(type, ttl.toInt(), data)
        }
        val addressTtls = records.filter { it.type == TYPE_A || it.type == TYPE_AAAA }.map { it.ttlSeconds }
        return DnsResponse(
            id = id,
            rcode = flags and 0x0F,
            truncated = (flags and FLAG_TC) != 0,
            records = records,
            minTtlSeconds = addressTtls.minOrNull() ?: 0
        )
    }

    private fun writeName(d: DataOutputStream, name: String) {
        val trimmed = name.trim().trimEnd('.')
        if (trimmed.isEmpty()) {
            d.writeByte(0)
            return
        }
        for (label in trimmed.split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..63) { "非法 DNS label: $label" }
            d.writeByte(bytes.size)
            d.write(bytes)
        }
        d.writeByte(0)
    }

    private class Reader(private val buf: ByteArray) {
        private var pos = 0

        fun u16(): Int = (u8() shl 8) or u8()

        fun u8(): Int {
            if (pos >= buf.size) throw DnsFormatException("报文提前结束")
            return buf[pos++].toInt() and 0xFF
        }

        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        fun skip(n: Int) {
            if (pos + n > buf.size) throw DnsFormatException("跳过 $n 字节越界")
            pos += n
        }

        fun bytes(n: Int): ByteArray {
            if (pos + n > buf.size) throw DnsFormatException("读取 $n 字节越界")
            val out = buf.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun name(): String {
            val sb = StringBuilder()
            var jumped = false
            var resume = 0
            var hops = 0
            while (true) {
                if (pos >= buf.size) throw DnsFormatException("域名越界")
                val len = buf[pos].toInt() and 0xFF
                when {
                    len == 0 -> {
                        pos++
                        break
                    }
                    len and 0xC0 == 0xC0 -> {
                        if (pos + 1 >= buf.size) throw DnsFormatException("压缩指针越界")
                        if (!jumped) resume = pos + 2
                        val pointer = ((len and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                        if (pointer >= buf.size) throw DnsFormatException("压缩指针指向越界")
                        pos = pointer
                        jumped = true
                        if (++hops > 16) throw DnsFormatException("压缩指针成环")
                    }
                    else -> {
                        pos++
                        if (pos + len > buf.size) throw DnsFormatException("label 越界")
                        sb.append(String(buf, pos, len, Charsets.UTF_8)).append('.')
                        pos += len
                    }
                }
            }
            if (jumped) pos = resume
            return sb.toString().trimEnd('.')
        }
    }
}
