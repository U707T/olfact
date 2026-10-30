package cn.olfact.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore + AES-GCM。密钥不出 TEE，只用来加密 AI Key / WebDAV 密码这类小字符串。
 *
 * 密文格式：`[12 字节 IV][ciphertext+tag]`，整个再 Base64 存字符串。
 * 不引 security-crypto（已停止维护）。
 */
object KeystoreCrypto {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "olfact.master"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return ByteArray(iv.size + body.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(body, 0, it, iv.size, body.size)
        }
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "密文长度不合法：${blob.size}" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES)
        )
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    fun encryptString(plain: String): String =
        Base64.encodeToString(encrypt(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    fun decryptString(encoded: String): String =
        String(decrypt(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)

    /** 删除主密钥（"清空密钥材料"用），下次写入会重新生成。 */
    @Synchronized
    fun reset() {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }
}
