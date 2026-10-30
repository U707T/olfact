package cn.olfact.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地备份：把「设置 + 书签 + 历史 + 标签页」打包成一个 JSON。
 *
 * 格式与旧版 `.hiker` 不兼容（规则引擎已裁掉，没有兼容对象），
 * 未来 WebDAV 上传的也是这份 JSON 的 zip。
 */
object BackupManager {

    private const val SETTINGS = "olfact_settings"
    private const val DATA = "olfact_data"

    fun export(context: Context): File {
        val root = JSONObject()
        root.put("schema", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("settings", dump(context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).all))
        root.put("data", dump(context.getSharedPreferences(DATA, Context.MODE_PRIVATE).all))

        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
        val file = File(context.filesDir, "olfact-backup-$stamp.json")
        file.writeText(root.toString(2))
        AppSettings.set(K.BACKUP_LAST_AT, System.currentTimeMillis())
        return file
    }

    /** @return 恢复了几项 */
    fun restore(context: Context, json: String): Int {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return 0
        var count = 0
        count += load(context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE), root.optJSONObject("settings"))
        count += load(context.getSharedPreferences(DATA, Context.MODE_PRIVATE), root.optJSONObject("data"))
        return count
    }

    fun latest(context: Context): File? =
        context.filesDir.listFiles { f -> f.name.startsWith("olfact-backup-") }?.maxByOrNull { it.lastModified() }

    private fun dump(all: Map<String, *>): JSONObject = JSONObject().apply {
        all.forEach { (k, v) -> put(k, v) }
    }

    private fun load(prefs: android.content.SharedPreferences, obj: JSONObject?): Int {
        obj ?: return 0
        val editor = prefs.edit()
        var count = 0
        obj.keys().forEach { key ->
            when (val v = obj.get(key)) {
                is Boolean -> editor.putBoolean(key, v)
                is Int -> editor.putInt(key, v)
                is Long -> editor.putLong(key, v)
                is Double -> editor.putFloat(key, v.toFloat())
                is String -> editor.putString(key, v)
                else -> return@forEach
            }
            count++
        }
        editor.apply()
        return count
    }
}
