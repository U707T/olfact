package cn.olfact.app.data

import android.content.Context
import cn.olfact.core.adblock.AdBlockManager
import cn.olfact.core.api.AdBlockController
import cn.olfact.core.api.AdBlockStats
import cn.olfact.core.api.AdBlockSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** `core/api` 拦截面板 → B4 的 `AdBlockManager`。 */
class AdBlockAdapter(private val manager: AdBlockManager) : AdBlockController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun stats(): AdBlockStats {
        val s = manager.state.value
        return AdBlockStats(
            enabled = s.enabled,
            totalBlocked = s.stats.total,
            todayBlocked = s.stats.today,
            ruleCount = s.ruleCount,
            subscriptionCount = s.subscriptions.count { it.enabled },
            lastUpdatedAt = s.lastRefreshAt,
            engineReady = s.ruleCount > 0,
            engineName = s.engineLabel
        )
    }

    override fun setEnabled(enabled: Boolean) = manager.setEnabled(enabled)

    override fun subscriptions(): List<AdBlockSubscription> =
        manager.state.value.subscriptions.map {
            AdBlockSubscription(it.id, it.title, it.url, it.enabled, it.ruleCount)
        }

    override fun setSubscriptionEnabled(id: String, enabled: Boolean) =
        manager.setSubscriptionEnabled(id, enabled)

    override fun addSubscription(name: String, url: String) {
        manager.addSubscription(name, url)
    }

    override fun removeSubscription(id: String) = manager.removeSubscription(id)

    override fun refresh() {
        scope.launch { runCatching { manager.updateAll(force = true) } }
    }

    override fun refreshAsync(onDone: (Boolean, String) -> Unit) {
        scope.launch {
            val result = runCatching { manager.updateAll(force = true) }
            val ruleCount = manager.state.value.ruleCount
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { onDone(true, "规则已更新 · $ruleCount 条") },
                    onFailure = { onDone(false, it.message ?: "更新失败") }
                )
            }
        }
    }

    override fun whitelist(): List<String> = manager.whitelist()

    override fun setWhitelist(hosts: List<String>) {
        val target = hosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        manager.whitelist().filterNot { it in target }.forEach { manager.removeWhitelist(it) }
        target.filterNot { manager.isWhitelisted(it) }.forEach { manager.addWhitelist(it) }
    }
}
