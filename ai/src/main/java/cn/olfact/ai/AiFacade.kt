package cn.olfact.ai

import android.content.Context
import cn.olfact.ai.crypto.KeystoreSecretBox
import cn.olfact.ai.crypto.SecretBox
import cn.olfact.ai.net.ChatClient
import cn.olfact.ai.summarize.WebSummarizer
import cn.olfact.ai.translate.Translator
import java.io.File

/**
 * ai 模块对外入口。
 * app 侧：`AiFacade.get(context).providers.selected()` 拿当前供应商，
 * `summarizer.summarize(...)` / `translator.translate(...)` 直接消费 Flow。
 */
class AiFacade private constructor(context: Context) {

    val secrets: SecretBox = KeystoreSecretBox(context.applicationContext)

    val providers: ProviderStore = ProviderStore(
        File(context.applicationContext.filesDir, "ai/providers.json"),
        secrets,
    )

    val chat: ChatClient = ChatClient()
    val summarizer: WebSummarizer = WebSummarizer(chat)
    val translator: Translator = Translator(chat)

    companion object {
        @Volatile
        private var instance: AiFacade? = null

        fun get(context: Context): AiFacade =
            instance ?: synchronized(this) {
                instance ?: AiFacade(context.applicationContext).also { instance = it }
            }
    }
}
