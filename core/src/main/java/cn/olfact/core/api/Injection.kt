package cn.olfact.core.api

/**
 * 一条 UserScript（油猴脚本）。[matches] 是「scheme://host/path」匹配串，支持 * 通配。
 */
data class UserScript(
    val id: String,
    val name: String,
    val version: String = "1.0",
    val matches: List<String> = emptyList(),
    val enabled: Boolean = true,
    val code: String = "",
    val runAtStart: Boolean = true
)

/**
 * 注入源。B4 提供脚本内容，B2 负责 `addDocumentStartJavaScript`。
 */
interface ScriptSource {
    fun documentStartScripts(url: String): List<String>
}

/** 脚本管理面板的数据源（B4 实现）。 */
interface ScriptStore {
    fun all(): List<UserScript>
    fun save(script: UserScript)
    fun remove(id: String)
    fun setEnabled(id: String, enabled: Boolean)
}
