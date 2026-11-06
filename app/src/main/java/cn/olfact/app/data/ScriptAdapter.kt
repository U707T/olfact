package cn.olfact.app.data

import android.content.Context
import cn.olfact.core.api.ScriptStore
import cn.olfact.core.api.UserScript
import cn.olfact.core.script.GmBridge
import cn.olfact.core.script.GmValueStore
import cn.olfact.core.script.RunAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** `core/api` 脚本面板 → B4 的 `core.script.ScriptStore`。 */
class ScriptAdapter(
    private val store: cn.olfact.core.script.ScriptStore,
    /** 删除脚本时一并清掉它的 GM_* 存储，避免残留（重装同名脚本会读到旧值）。 */
    private val gmValues: GmValueStore? = null
) : ScriptStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun all(): List<UserScript> = store.list().map { meta ->
        UserScript(
            id = meta.id,
            name = meta.name,
            version = meta.version.ifBlank { "1.0" },
            matches = meta.matches,
            enabled = meta.enabled,
            code = store.body(meta.id).orEmpty(),
            runAtStart = meta.runAt == RunAt.DOCUMENT_START
        )
    }

    override fun count(): Int = store.count()

    override fun save(script: UserScript) {
        val existing = store.find(script.id)
        val meta = cn.olfact.core.script.UserScript(
            id = script.id,
            name = script.name,
            version = script.version,
            matches = script.matches.ifEmpty { listOf("*://*/*") },
            runAt = if (script.runAtStart) RunAt.DOCUMENT_START else RunAt.DOCUMENT_END,
            enabled = script.enabled,
            installedAt = existing?.installedAt ?: 0L
        )
        store.save(meta, script.code)
    }

    override fun remove(id: String) {
        store.delete(id)
        gmValues?.clear(id)
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        store.setEnabled(id, enabled)
    }

    override fun installFromUrl(url: String, onDone: (Boolean, String) -> Unit) {
        scope.launch {
            val parsed = store.installFromUrl(url, GmBridge.defaultClient())
            withContext(Dispatchers.Main) {
                if (parsed != null) onDone(true, parsed.name)
                else onDone(false, "下载或解析失败，请确认链接指向 .user.js")
            }
        }
    }
}
