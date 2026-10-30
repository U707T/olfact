package cn.olfact.core.script

import cn.olfact.core.api.ScriptSource

data class InjectedScript(
    val meta: UserScript,
    val js: String
)

/**
 * [ScriptSource] 实现。B2 只在 document-start 阶段调 [documentStartScripts]；
 * document-end / idle 阶段的注入用 [scriptsFor]，避免 B4 反向依赖 `web/`。
 */
class ScriptSourceImpl(
    private val store: ScriptStore,
    private val bridge: GmBridge
) : ScriptSource {

    override fun documentStartScripts(url: String): List<String> =
        scriptsFor(url, RunAt.DOCUMENT_START).map { it.js }

    fun scriptsFor(url: String, runAt: RunAt): List<InjectedScript> {
        if (url.isBlank()) return emptyList()
        val hits = store.enabledFor(url, runAt)
        if (hits.isEmpty()) return emptyList()
        val out = ArrayList<InjectedScript>(hits.size)
        for (script in hits) {
            val body = store.body(script.id) ?: continue
            out.add(InjectedScript(script, bridge.wrap(script, body)))
        }
        return out
    }

    fun allFor(url: String): List<InjectedScript> {
        val out = ArrayList<InjectedScript>(4)
        for (runAt in RunAt.entries) out.addAll(scriptsFor(url, runAt))
        return out
    }
}
