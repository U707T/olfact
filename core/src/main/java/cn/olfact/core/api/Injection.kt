package cn.olfact.core.api

/**
 * 文档开始前注入的脚本来源。由 B4 提供实现，B2 负责调用 addDocumentStartJavaScript 注入。
 */
interface ScriptSource {
    /**
     * @param url 即将加载的页面地址
     * @return 需要在该页面 document start 阶段执行的脚本，按顺序执行
     */
    fun documentStartScripts(url: String): List<String>
}
