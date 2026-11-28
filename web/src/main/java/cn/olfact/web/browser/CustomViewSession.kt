package cn.olfact.web.browser

/** Main-thread custom-view lifetime. Clear ownership before calling either side back. */
internal class CustomViewSession<T>(
    private val present: (T, () -> Unit) -> Boolean,
    private val dismiss: () -> Unit
) {
    private class Request(val hidden: () -> Unit)
    private var active: Request? = null
    private var closing = false

    fun show(view: T, hidden: () -> Unit) {
        if (active != null || closing) {
            runCatching(hidden)
            return
        }
        val request = Request(hidden)
        active = request
        val accepted = runCatching { present(view) { finish(request) } }.getOrDefault(false)
        if (!accepted) finish(request)
    }

    fun hide(): Boolean {
        val request = active ?: return false
        finish(request)
        return true
    }

    private fun finish(request: Request) {
        if (active !== request) return
        active = null
        closing = true
        try {
            // Also remove a partially attached presentation after a host exception/rejection.
            runCatching(dismiss)
            runCatching(request.hidden)
        } finally {
            closing = false
        }
    }
}
