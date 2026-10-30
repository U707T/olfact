package cn.olfact.core.adblock

import cn.olfact.core.b4.B4Log
import java.io.File

/**
 * 引擎选择：先试 Rust（要求 `.so` 可加载），任何异常都落回 Kotlin。
 * 失败信息写进日志与 [AdBlockManager.state]，设置页能显示"降级中"。
 */
object AdBlockEngineFactory {

    fun create(
        rulesText: String,
        sourceId: String,
        engineCache: File? = null,
        preferNative: Boolean = true,
        maxKotlinRules: Int = 20_000
    ): AdBlockEngine {
        if (preferNative && AdBlockNative.available && rulesText.isNotBlank()) {
            val native = NativeAdBlockEngine.create(rulesText, engineCache)
            if (native != null) {
                B4Log.d("EngineFactory", "使用 Rust 引擎，规则 ${native.ruleCount} 条")
                return native
            }
        }
        val parsed = FilterListParser.parse(rulesText, sourceId)
        return KotlinAdBlockEngine(parsed, maxKotlinRules).also {
            B4Log.d("EngineFactory", "使用 Kotlin 引擎，规则 ${it.ruleCount} 条")
        }
    }
}
