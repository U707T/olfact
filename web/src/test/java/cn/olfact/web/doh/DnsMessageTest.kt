package cn.olfact.web.doh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class DnsMessageTest {

    private fun buildResponse(
        id: Int,
        rcode: Int,
        answers: List<Triple<Int, Int, ByteArray>>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeShort(id)
        d.writeShort(0x8180 or rcode)
        d.writeShort(1)
        d.writeShort(answers.size)
        d.writeShort(0)
        d.writeShort(0)
        listOf("www", "example", "com").forEach { label ->
            d.writeByte(label.length)
            d.write(label.toByteArray())
        }
        d.writeByte(0)
        d.writeShort(1)
        d.writeShort(1)
        answers.forEach { (type, ttl, data) ->
            d.writeByte(0xC0)   // 压缩指针
            d.writeByte(12)
            d.writeShort(type)
            d.writeShort(1)
            d.writeInt(ttl)
            d.writeShort(data.size)
            d.write(data)
        }
        d.flush()
        return out.toByteArray()
    }

    @Test
    fun `构造的查询报文头部正确`() {
        val query = DnsMessage.buildQuery(0x1234, "www.example.com", DnsMessage.TYPE_A)
        assertEquals(0x12, query[0].toInt() and 0xFF)
        assertEquals(0x34, query[1].toInt() and 0xFF)
        assertEquals(0x01, query[2].toInt() and 0xFF)   // RD
        assertEquals(0x00, query[3].toInt() and 0xFF)
        assertEquals(0, query[4].toInt())              // QDCOUNT 高字节
        assertEquals(1, query[5].toInt())
        assertEquals(0, query[11].toInt())             // 无 EDNS 时 ARCOUNT = 0
        assertEquals(3, query[12].toInt())             // 第一个 label 长度
        assertEquals('w'.code, query[13].toInt())
    }

    @Test
    fun `带 ECS 的查询多一个 OPT 记录`() {
        val ecs = EcsSubnet.parse("116.24.0.0/16")
        val withEcs = DnsMessage.buildQuery(1, "example.com", DnsMessage.TYPE_A, ecs)
        val without = DnsMessage.buildQuery(1, "example.com", DnsMessage.TYPE_A, null)
        assertTrue(withEcs.size > without.size)
        assertEquals(1, withEcs[11].toInt())          // ARCOUNT = 1
        assertEquals(0, without[11].toInt())
    }

    @Test
    fun `解析 A 记录与最小 TTL`() {
        val response = DnsMessage.parseResponse(
            buildResponse(0x1234, 0, listOf(Triple(1, 300, byteArrayOf(93, 184.toByte(), 216.toByte(), 34))))
        )
        assertEquals(0x1234, response.id)
        assertTrue(response.isSuccess)
        assertFalse(response.truncated)
        assertEquals(300, response.minTtlSeconds)
        val addresses = response.addresses()
        assertEquals(1, addresses.size)
        assertEquals("93.184.216.34", addresses.first().hostAddress)
    }

    @Test
    fun `解析 NXDOMAIN`() {
        val response = DnsMessage.parseResponse(buildResponse(7, DnsMessage.RCODE_NXDOMAIN, emptyList()))
        assertTrue(response.isNameError)
        assertTrue(response.addresses().isEmpty())
        assertEquals(0, response.minTtlSeconds)
    }

    @Test
    fun `截断的报文抛格式异常`() {
        val full = buildResponse(1, 0, listOf(Triple(1, 60, byteArrayOf(1, 2, 3, 4))))
        val truncated = full.copyOfRange(0, full.size - 6)
        var thrown = false
        try {
            DnsMessage.parseResponse(truncated)
        } catch (e: DnsFormatException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `AAAA 记录解析成十六进制地址`() {
        val v6 = byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        val response = DnsMessage.parseResponse(buildResponse(9, 0, listOf(Triple(28, 120, v6))))
        assertEquals("2001:db8:0:0:0:0:0:1", response.addresses().first().hostAddress)
        assertEquals(120, response.minTtlSeconds)
    }
}
