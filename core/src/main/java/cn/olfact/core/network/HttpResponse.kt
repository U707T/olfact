package cn.olfact.core.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Keep cancellation attached to an OkHttp call until its response body has been consumed. */
suspend fun <T> Call.withCancellableResponse(read: suspend (Response) -> T): T =
    withContext(Dispatchers.IO) {
        coroutineScope {
            // A completion listener on the reader's Job runs too late if a blocking read is stuck.
            val finished = AtomicBoolean(false)
            val cancelCall = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) this@withCancellableResponse.cancel()
                }
            }
            try {
                execute().use { response -> read(response) }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                throw error
            } finally {
                finished.set(true)
                cancelCall.cancel()
            }
        }
    }

class ResponseTooLargeException(maxBytes: Long) : IOException("响应超过 ${maxBytes / 1024} KB")

/** Limit decompressed bytes before OkHttp's string()/bytes() allocates the full body. */
fun ResponseBody.requireAtMost(maxBytes: Long): ResponseBody {
    if (contentLength() > maxBytes || source().request(maxBytes + 1)) throw ResponseTooLargeException(maxBytes)
    return this
}
