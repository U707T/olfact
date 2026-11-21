package android.content

import java.io.File

/** Compilation-only Android boundary for the standalone JVM tests. */
open class Context {
    open val applicationContext: Context get() = this
    open fun getFilesDir(): File = error("Override filesDir in the test")
    open fun getCacheDir(): File = error("Override cacheDir in the test")
    @get:JvmName("filesDirProperty")
    val filesDir: File get() = getFilesDir()
    @get:JvmName("cacheDirProperty")
    val cacheDir: File get() = getCacheDir()
    open fun getExternalFilesDir(type: String?): File? = null
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("Preferences are not used by these tests")
    val contentResolver = ContentResolver()
    companion object { const val MODE_PRIVATE = 0 }
}
open class ContextWrapper(base: Context?) : Context()
class ContentResolver { fun delete(uri: android.net.Uri, selection: String?, args: Array<String>?): Int = 0 }
interface SharedPreferences {
    fun getFloat(key: String, default: Float): Float
    fun getLong(key: String, default: Long): Long
    val all: Map<String, *>
    fun edit(): Editor
    interface Editor {
        fun putFloat(key: String, value: Float): Editor
        fun putLong(key: String, value: Long): Editor
        fun remove(key: String): Editor
        fun apply()
    }
}
