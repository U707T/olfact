package cn.olfact.app.data

import android.os.Handler
import android.os.Looper

/** GM_* 桥的 JS 出口：由当前可见的渲染器注入。 */
object InjectionBus {
    @Volatile
    private var sink: ((String) -> Unit)? = null

    /** WebView 只能在 UI 线程操作：所有注入统一从主线程发出。 */
    private val main = Handler(Looper.getMainLooper())

    fun attach(evaluate: (String) -> Unit) {
        sink = evaluate
    }

    fun detach(evaluate: (String) -> Unit) {
        if (sink === evaluate) sink = null
    }

    fun evaluate(js: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sink?.invoke(js)
        } else {
            // GM_xmlhttpRequest 的回调在 olfact-gm-xhr 线程；直接 evaluate 属于未定义行为。
            main.post { sink?.invoke(js) }
        }
    }
}

// --------------------------------------------------------------------- 适配器
