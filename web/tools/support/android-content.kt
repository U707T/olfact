package android.content

open class Context {
    val applicationContext: Context get() = this
    fun getSystemService(name: String): Any? = null
    companion object { const val CONNECTIVITY_SERVICE = "connectivity" }
}
