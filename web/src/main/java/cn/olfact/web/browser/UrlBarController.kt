package cn.olfact.web.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 地址栏控制器：输入判定、200ms 防抖搜索建议、文本提链、提交跳转。
 *
 * 不持有 View，B5 把 EditText 的 textChanged 转进来即可，便于换皮与单测。
 */
class UrlBarController(
    private val scope: CoroutineScope,
    private val configProvider: () -> BrowserConfig = { BrowserConfig() },
    private val historyProvider: suspend (String) -> List<String> = { emptyList() },
    private val onCommit: (String) -> Unit = {},
    private val debounceMs: Long = 200L
) {

    private val _suggestions = MutableStateFlow<List<UrlBarSuggestion>>(emptyList())
    val suggestions: StateFlow<List<UrlBarSuggestion>> = _suggestions.asStateFlow()

    private var pending: Job? = null

    fun onTextChanged(text: String) {
        pending?.cancel()
        if (text.isBlank()) {
            _suggestions.value = emptyList()
            return
        }
        pending = scope.launch {
            delay(debounceMs)
            val history = runCatching { historyProvider(text) }.getOrDefault(emptyList())
            _suggestions.value = SuggestionEngine.suggestions(
                input = text,
                history = history,
                searchTemplate = configProvider().searchTemplate
            )
        }
    }

    /** 回车/点候选：返回真正要加载的地址。 */
    fun onSubmit(text: String): String {
        val url = SuggestionEngine.toUrl(text, configProvider().searchTemplate)
        if (url.isNotEmpty()) onCommit(url)
        return url
    }

    /** 粘贴一段带链接的文本时的候选（最多 5 条）。 */
    fun textLinks(text: String): List<LinkCandidate> = LinkExtractor.extract(text)

    fun clear() {
        pending?.cancel()
        _suggestions.value = emptyList()
    }
}
