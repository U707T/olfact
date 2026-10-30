package cn.olfact.core.api

/**
 * 一条 UserScript（油猴脚本）的界面视图。[matches] 是「scheme://host/path」匹配串，支持 * 通配。
 *
 * 与 B4 的 `cn.olfact.core.script.UserScript` 是**两套模型**：那边是完整元数据
 * （namespace/runAt/grants/…），这里是设置页只需要的最小集。映射在 app 的
 * `ModuleBackends.ScriptAdapter` 里做，避免 UI 依赖 core 内部实现。
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

/** 脚本管理面板的数据源。 */
interface ScriptStore {
    fun all(): List<UserScript>
    fun save(script: UserScript)
    fun remove(id: String)
    fun setEnabled(id: String, enabled: Boolean)
}
