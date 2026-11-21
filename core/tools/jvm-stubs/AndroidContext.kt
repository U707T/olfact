package android.content

import java.io.File
import java.io.InputStream

open class Context {
    val applicationContext: Context get() = this
    val filesDir: File get() = error("Android Context is unavailable in JVM tests")
    val assets = AssetManager()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("SharedPreferences unavailable")
    companion object { const val MODE_PRIVATE = 0 }
}

class AssetManager {
    fun open(path: String): InputStream = error("Android assets unavailable: $path")
}

interface SharedPreferences {
    fun getString(key: String, defaultValue: String?): String?
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String): Editor
        fun remove(key: String): Editor
        fun apply()
        fun commit(): Boolean
    }
}
