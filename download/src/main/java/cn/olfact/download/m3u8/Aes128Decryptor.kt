package cn.olfact.download.m3u8

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** HLS 的 AES-128-CBC（PKCS7）。目前只支持这一种 METHOD。 */
object Aes128Decryptor {

    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 密钥必须是 16 字节，实际 ${key.size}" }
        val spec = IvParameterSpec(normalizeIv(iv))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        return cipher.doFinal(data)
    }

    fun parseIv(ivHex: String?, sequence: Long): ByteArray {
        if (ivHex.isNullOrBlank()) {
            // 没写 IV 时，用媒体序号当 128 位大端 IV（RFC 8216 §5.2）
            val iv = ByteArray(16)
            var value = sequence
            for (i in 15 downTo 8) {
                iv[i] = (value and 0xFF).toByte()
                value = value shr 8
            }
            return iv
        }
        val hex = ivHex.removePrefix("0x").removePrefix("0X")
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return normalizeIv(bytes)
    }

    private fun normalizeIv(iv: ByteArray): ByteArray {
        if (iv.size == 16) return iv
        val result = ByteArray(16)
        iv.copyInto(result, 16 - iv.size.coerceAtMost(16))
        return result
    }
}
