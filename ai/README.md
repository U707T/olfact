# ai · 供应商 / 网页总结 / 划词翻译

只改本目录。对外入口：`AiFacade.get(context)`。

## 类地图

| 文件 | 作用 |
|---|---|
| `AiProvider` / `ProviderStore` | 多供应商（OpenAI 兼容）+ 当前选择；apiKey 单独加密存储 |
| `crypto/KeystoreSecretBox` | Android Keystore AES-GCM（手写 30 行，不引 security-crypto） |
| `net/SseFramer` | 手写 SSE 分帧（多行 data、注释、CRLF、未闭合事件） |
| `net/OpenAiWire` | 请求体/增量/usage/错误体编解码（kotlinx JSON，无插件） |
| `net/ChatClient` | 流式（可取消、只重试"首字节前"的失败）+ 非流式 |
| `error/AiError` / `AiErrorMapper` | 401/429/超时/内容过长 → 可读中文提示，标注可重试性 |
| `summarize/ReadabilityExtractor` | 去噪 + 段落打分抽正文（纯 Kotlin，无 jsoup） |
| `summarize/TextChunker` | 按段落/句子切块，带重叠 |
| `summarize/WebSummarizer` | 长文分块摘要 → 合并成稿，全程 `Flow<SummaryEvent>` |
| `translate/Translator` | 划词/整页翻译，长文自动分块；中英目标自动判定 |
| `translate/SelectionTranslator` | 划词浮层（PopupWindow，流式刷新、可复制） |
| `translate/BilingualInjector` | 整页双语对照的 JS 生成器（B2 负责注入） |

## 用法

```kotlin
val ai = AiFacade.get(context)
val provider = ai.providers.selected() ?: return
val key = ai.providers.apiKey(provider)

ai.summarizer.summarize(provider, key, ReadabilityExtractor.extract(html, url))
    .collect { event ->
        when (event) {
            is SummaryEvent.Progress -> showStage(event.stage, event.index, event.total)
            is SummaryEvent.Delta -> appendToCard(event.text)
            is SummaryEvent.Done -> renderBullets(event.result.bullets)
            is SummaryEvent.Failed -> showError((event.error as? AiError)?.message ?: "总结失败")
        }
    }
```

整页翻译（B2 配合）：

```kotlin
webView.evaluateJavascript(BilingualInjector.collectParagraphsScript()) { json -> /* 解析成 List<String> */ }
// 分批调用 translator.translateText(...) 得到译文
webView.evaluateJavascript(BilingualInjector.applyTranslationsScript(translationsJson), null)
webView.evaluateJavascript(BilingualInjector.clearScript(), null)   // 还原
```

## 验证

```bash
./gradlew :ai:assembleDebug --offline
./gradlew :ai:testDebugUnitTest --offline   # 29 个用例
```
