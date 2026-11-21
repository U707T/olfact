package android.util

object Base64 {
    const val NO_WRAP = 2
    fun encodeToString(input: ByteArray, flags: Int): String = java.util.Base64.getEncoder().encodeToString(input)
    fun decode(input: String, flags: Int): ByteArray = java.util.Base64.getDecoder().decode(input)
}
