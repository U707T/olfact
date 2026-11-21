package android.security.keystore

import java.security.spec.AlgorithmParameterSpec

class KeyGenParameterSpec : AlgorithmParameterSpec {
    class Builder(alias: String, purposes: Int) {
        fun setBlockModes(vararg modes: String) = this
        fun setEncryptionPaddings(vararg paddings: String) = this
        fun setKeySize(size: Int) = this
        fun build(): KeyGenParameterSpec = error("Android Keystore is unavailable in JVM tests")
    }
}

object KeyProperties {
    const val KEY_ALGORITHM_AES = "AES"
    const val PURPOSE_ENCRYPT = 1
    const val PURPOSE_DECRYPT = 2
    const val BLOCK_MODE_GCM = "GCM"
    const val ENCRYPTION_PADDING_NONE = "NoPadding"
}
