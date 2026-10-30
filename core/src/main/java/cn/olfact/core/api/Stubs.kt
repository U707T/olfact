package cn.olfact.core.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 契约的**空实现**。作用：B1 阶段全仓可编译；B2~B4 未就绪时，
 * 上层拿到的默认行为是"不拦截、不注入、不显示媒体"。
 *
 * 各批次实现完成后在装配处替换，不要修改这些对象本身。
 */

object NoopMediaSniffer : MediaSniffer {
    override fun onMediaDetected(item: MediaItem) = Unit

    override fun currentPageTitle(): String = ""

    override fun currentPageUrl(): String = ""
}

object NoopAdBlockHook : AdBlockHook {
    override fun shouldBlock(url: String, pageHost: String): Boolean = false

    override fun cosmeticCss(pageHost: String): String = ""

    override fun recordBlocked() = Unit
}

object EmptyScriptSource : ScriptSource {
    override fun documentStartScripts(url: String): List<String> = emptyList()
}

object NoopBrowserHost : BrowserHost {
    override fun newTab(url: String?) = Unit

    override fun currentUrl(): String? = null

    override fun openSettings() = Unit
}

/** 纯内存实现，仅用于单测 / 预览，UI 装配请用 [cn.olfact.core.storage.DataStoreSettingsRepository]。 */
object InMemorySettingsRepository : SettingsRepository {
    private val values = ConcurrentHashMap<String, Any>()
    private val emitter = MutableSharedFlow<String>(extraBufferCapacity = 64)

    override val changes: Flow<String> = emitter

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: SettingsKey<T>): T = (values[key.name] as? T) ?: key.default

    override fun <T> set(key: SettingsKey<T>, value: T) {
        values[key.name] = value as Any
        emitter.tryEmit(key.name)
    }
}
