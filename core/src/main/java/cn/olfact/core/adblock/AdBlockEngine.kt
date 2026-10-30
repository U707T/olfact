package cn.olfact.core.adblock

import java.io.Closeable

enum class EngineKind {
    /** adblock-rust（CI 编的 libadblock_ffi.so） */
    RUST,

    /** 纯 Kotlin 降级匹配器（.so 缺失 / 加载失败时） */
    KOTLIN;

    val displayName: String
        get() = if (this == RUST) "Rust 引擎" else "Kotlin 降级引擎"
}

/**
 * 引擎抽象。两个实现必须行为一致（同一份规则、同样的输入给同样的结果），
 * 这样 CI 编不出 `.so` 时功能不中断，只是规则上限降到 2 万条。
 */
interface AdBlockEngine : Closeable {

    val kind: EngineKind

    val ruleCount: Int

    /**
     * @param url 请求 URL
     * @param sourceUrl 发起页 URL（null 表示未知，此时带 `third-party` 选项的规则一律不生效）
     * @param type 请求类型
     */
    fun check(url: String, sourceUrl: String?, type: ResourceType): Boolean

    /** 该页面命中的隐藏选择器（已去掉 `#@#` 例外）。 */
    fun cosmeticSelectors(pageUrl: String): List<String>

    override fun close() = Unit
}
