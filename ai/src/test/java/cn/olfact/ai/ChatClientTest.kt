package cn.olfact.ai

import cn.olfact.ai.error.AiError
import cn.olfact.ai.net.ChatClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

class ChatClientTest {
    private val local = AiProvider("test", "Test", "http://127.0.0.1/v1", "model")
    private val messages = listOf(ChatMessage.user("test"))
    private val textFrame = """data: {"choices":[{"delta":{"content":"answer"},"finish_reason":null}],"usage":null}""" + "\n\n"
    private val finishFrame = """data: {"choices":[{"delta":null,"finish_reason":"stop"}]}""" + "\n\n"
    private val usageFrame = """data: {"choices":[],"usage":{"total_tokens":12}}""" + "\n\n"

    private fun response(text: String, inspect: (String?) -> Unit = {}): ChatClient = responseForRequest {
        inspect(it)
        text
    }

    private fun responseForRequest(body: (String?) -> String): ChatClient {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val text = body(request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(text.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        return ChatClient(client)
    }

    @Test
    fun `usage after finish_reason and final unterminated done frame are accepted`() = runBlocking {
        val client = response(textFrame + finishFrame + usageFrame + "data: [DONE]") { assertNull(it) }
        val chunks = client.stream(local, null, messages).toList()
        assertEquals("answer", chunks.joinToString("") { it.text })
        assertEquals(12, chunks.firstNotNullOf { it.usage }.totalTokens)
        assertTrue(chunks.last().done)
        assertEquals("stop", chunks.last().finishReason)
        assertFalse(chunks.dropLast(1).any { it.done })
    }

    @Test
    fun `partial EOF without finish is an error and is never retried`() = runBlocking {
        var requests = 0
        val client = response(textFrame) { requests++ }
        val chunks = mutableListOf<ChatDelta>()
        val error = runCatching { client.stream(local, null, messages).toList(chunks) }.exceptionOrNull()
        assertTrue(error is AiError.Network)
        assertEquals(1, requests)
        assertEquals("answer", chunks.joinToString("") { it.text })
        assertFalse(chunks.any { it.done })
    }

    @Test
    fun `partial EOF preserves a delta even when the producer fails before the collector resumes`() = runBlocking {
        val producer = AtomicReference<Job>()
        val startProducer = CompletableDeferred<Unit>()
        val resumeCollector = CompletableDeferred<Unit>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                val job = checkNotNull(context[Job])
                producer.compareAndSet(null, job)
                val gate = if (job === producer.get()) startProducer else resumeCollector
                gate.invokeOnCompletion { Dispatchers.Default.dispatch(context, block) }
            }
        }
        val requests = AtomicInteger()
        val client = response(textFrame) { requests.incrementAndGet() }
        val chunks = mutableListOf<ChatDelta>()
        val result = async(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            runCatching { client.stream(local, null, messages).toList(chunks) }
        }
        try {
            // The collector is suspended on receive. Let the producer hand off the delta and
            // terminate on EOF, but don't let the receiver's continuation run until then.
            startProducer.complete(Unit)
            withTimeout(4_000) { checkNotNull(producer.get()).join() }
            assertTrue(chunks.isEmpty())
        } finally {
            resumeCollector.complete(Unit)
        }
        val error = withTimeout(6_000) { result.await() }.exceptionOrNull()
        assertTrue(error is AiError.Network)
        assertEquals(1, requests.get())
        assertEquals(listOf(ChatDelta(text = "answer")), chunks)
    }

    @Test
    fun `EOF before any text retries even after a usage delta`() = runBlocking {
        val requests = AtomicInteger()
        val client = responseForRequest {
            if (requests.incrementAndGet() == 1) usageFrame else textFrame + finishFrame
        }
        val chunks = withTimeout(4_000) { client.stream(local, null, messages).toList() }
        assertEquals(2, requests.get())
        assertEquals(12, chunks.first().usage?.totalTokens)
        assertEquals("answer", chunks.joinToString("") { it.text })
        assertTrue(chunks.last().done)
    }

    @Test
    fun `clean EOF after an explicit finish is accepted for compatible providers`() = runBlocking {
        assertTrue(response(textFrame + finishFrame).stream(local, null, messages).toList().last().done)
    }

    @Test
    fun `successful HTTP status does not hide provider error or blank completion`() = runBlocking {
        val errorFrame = """data: {"error":{"message":"model unavailable"}}"""
        val streamError = runCatching { response(errorFrame).stream(local, null, messages).toList() }.exceptionOrNull()
        assertEquals("model unavailable", streamError?.message)
        val completion = """{"choices":[{"message":{"content":" "}}]}"""
        assertTrue(runCatching { response(completion).complete(local, null, messages) }.isFailure)
    }

    @Test
    fun `stream cancellation closes a socket whose body has stalled`() = runBlocking {
        stalledResponse(streaming = true)
    }

    @Test
    fun `non-streaming cancellation closes a socket whose body has stalled`() = runBlocking {
        stalledResponse(streaming = false)
    }

    private suspend fun stalledResponse(streaming: Boolean) = kotlinx.coroutines.coroutineScope {
        val headers = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Boolean>()
        val firstDelta = CompletableDeferred<Unit>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val testServer = thread(isDaemon = true, name = "ai-stalled-response") {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 8_000
                        val reader = socket.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: return@thread
                            if (line.isEmpty()) break
                            if (line.startsWith("content-length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { reader.read() }
                        val body = if (streaming) textFrame else "{"
                        val output = socket.getOutputStream()
                        output.write(("HTTP/1.1 200 OK\r\nContent-Length: 4000\r\n\r\n" + body).toByteArray())
                        output.flush()
                        disconnected.complete(reader.read() == -1)
                    }
                } catch (error: Exception) {
                    disconnected.completeExceptionally(error)
                }
            }
            val client = ChatClient(OkHttpClient.Builder()
                .readTimeout(8, TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun responseHeadersEnd(call: Call, response: Response) { headers.complete(Unit) }
                }).build())
            val provider = local.copy(baseUrl = "http://127.0.0.1:${server.localPort}/v1")
            val job = launch {
                if (streaming) client.stream(provider, null, messages).collect { if (it.text.isNotEmpty()) firstDelta.complete(Unit) }
                else client.complete(provider, null, messages)
            }
            withTimeout(4_000) {
                if (streaming) firstDelta.await() else headers.await()
                job.cancelAndJoin()
                assertTrue(disconnected.await())
            }
            testServer.join(500)
        }
    }
}
