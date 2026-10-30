package cn.olfact.download

import cn.olfact.download.m3u8.Aes128Decryptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Aes128DecryptorTest {

    private val key = ByteArray(16) { it.toByte() }
    private val iv = ByteArray(16) { (15 - it).toByte() }

    @Test
    fun `解密自己加密的数据`() {
        val plain = "olfact m3u8 segment".toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plain)

        assertArrayEquals(plain, Aes128Decryptor.decrypt(encrypted, key, iv))
    }

    @Test
    fun `IV 十六进制解析`() {
        val parsed = Aes128Decryptor.parseIv("0x000102030405060708090A0B0C0D0E0F", 0)
        assertArrayEquals(ByteArray(16) { it.toByte() }, parsed)
    }

    @Test
    fun `无 IV 时用序号做大端填充`() {
        val parsed = Aes128Decryptor.parseIv(null, 258)
        // 258 = 0x0102，写进最后两个字节
        assertEquals(1, parsed[14].toInt())
        assertEquals(2, parsed[15].toInt())
        assertEquals(16, parsed.size)
    }
}
