package cn.olfact.core.api

/**
 * UserScript 注入源（契约见 `docs/tasks/README.md` §3）。
 *
 * 实现：`cn.olfact.core.script.ScriptSourceImpl`；注入动作（`addDocumentStartJavaScript`）由 B2 负责。
 * 返回的每一项都是可直接注入的完整 JS（已包好 GM_* 前置代码与 IIFE）。
 *
 * [B4 临时落地] 本文件与契约逐字一致；B1 若产出同签名版本，直接覆盖此文件即可。
 */
interface ScriptSource {
    /** 命中该 URL 且在 `@run-at document-start` 的脚本，返回顺序即注入顺序。 */
    fun documentStartScripts(url: String): List<String>
}
