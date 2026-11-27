package cn.olfact.app.ui.browser

/** One Activity Result chooser at a time; canceled callbacks never receive a later launch's result. */
internal class SingleFlightFileChooser<T> {
    private var outstanding = false
    private var pending: ((T?) -> Unit)? = null

    val hasOutstandingLaunch: Boolean get() = outstanding

    fun begin(callback: (T?) -> Unit): Boolean {
        if (outstanding) {
            cancelPending()
            runCatching { callback(null) }
            return false
        }
        outstanding = true
        pending = callback
        return true
    }

    /** The platform picker pauses our Activity; keep the outstanding callback alive. */
    fun onActivityPause() = Unit

    /** Complete the page callback but keep the launch outstanding until Activity Result returns. */
    fun cancelPending() {
        val callback = pending
        pending = null
        runCatching { callback?.invoke(null) }
    }

    fun complete(value: T?) {
        val callback = pending
        pending = null
        outstanding = false
        runCatching { callback?.invoke(value) }
    }
}

internal fun chooserUriAllowed(
    scheme: String?,
    authority: String?,
    ownAuthority: String?,
    readPermissionGranted: Boolean
): Boolean = scheme.equals("content", ignoreCase = true) &&
    !authority.isNullOrBlank() &&
    !authority.equals(ownAuthority, ignoreCase = true) &&
    readPermissionGranted

/** Convert the common HTML accept suffix form (.pdf/.jpg) to Android MIME filters. */
internal fun browserAcceptMimeTypes(acceptTypes: List<String>): List<String> {
    val suffixMime = mapOf(
        "pdf" to "application/pdf",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "json" to "application/json",
        "zip" to "application/zip",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
    val mimePattern = Regex("^[a-z0-9!#$&^_.+-]+/(?:\\*|[a-z0-9!#$&^_.+-]+)$", RegexOption.IGNORE_CASE)
    val result = LinkedHashSet<String>()
    acceptTypes.asSequence().flatMap { it.split(',').asSequence() }.forEach { raw ->
        val value = raw.trim().lowercase()
        if (value.isEmpty()) return@forEach
        val normalized = if (value.startsWith('.')) suffixMime[value.drop(1)] else null
        when {
            normalized != null -> result += normalized
            mimePattern.matches(value) -> result += value
        }
    }
    return result.takeIf { it.isNotEmpty() }?.toList() ?: listOf("*/*")
}
