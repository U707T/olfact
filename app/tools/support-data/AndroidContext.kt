package android.content

/** In-memory SharedPreferences substitute; no Android storage, apply scheduling or lifecycle. */
open class Context {
    val applicationContext: Context get() = this
    private val preferences = HashMap<String, SharedPreferences>()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences.getOrPut(name) { SharedPreferences() }
    fun getSystemService(name: String): Any? = null
    companion object { const val MODE_PRIVATE = 0; const val CONNECTIVITY_SERVICE = "connectivity" }
}
class SharedPreferences {
    private val values = HashMap<String, String?>()
    fun getString(key: String, fallback: String?): String? = if (values.containsKey(key)) values[key] else fallback
    fun edit() = Editor()
    inner class Editor {
        private val pending = HashMap<String, String?>()
        fun putString(key: String, value: String?) = apply { pending[key] = value }
        fun apply() { values.putAll(pending) }
    }
}
