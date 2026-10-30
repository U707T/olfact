package cn.olfact.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 极简 JSON 持久化。数据量都在「几十条」级别，用 SharedPreferences + org.json 足够，
 * 不为此引入 Room 或序列化库（体积预算优先）。
 */
internal object JsonStore {

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("olfact_data", Context.MODE_PRIVATE)

    fun readArray(context: Context, key: String): JSONArray =
        runCatching { JSONArray(prefs(context).getString(key, "[]")) }.getOrElse { JSONArray() }

    fun writeArray(context: Context, key: String, array: JSONArray) {
        prefs(context).edit().putString(key, array.toString()).apply()
    }

    fun objOf(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
    }
}
