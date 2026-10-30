package cn.olfact.core.backup

import cn.olfact.core.b4.B4Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

data class DavEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

class WebDavException(val status: Int, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 极简 WebDAV 客户端（OkHttp 直写，不引 sardine）。
 *
 * 只做备份需要的五个动作：PROPFIND 列表 / PUT / GET / DELETE / MKCOL。
 * 认证用预置 Basic 头（坚果云、Nextcloud、nginx-dav 都吃这一套），不做 Digest。
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    private val client: OkHttpClient = defaultClient()
) {

    private val base = baseUrl.trim().trimEnd('/')
    private val authHeader: String? =
        if (!username.isNullOrEmpty()) Credentials.basic(username, password ?: "") else null

    // ------------------------------------------------------------------ 操作

    suspend fun list(path: String = "", depth: Int = 1): List<DavEntry> = withContext(Dispatchers.IO) {
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = newRequest(path, isDirectory = true)
            .header("Depth", depth.toString())
            .method("PROPFIND", body)
            .build()
        execute(request).use { resp ->
            if (resp.code == 404) return@withContext emptyList()
            if (resp.code != 207 && !resp.isSuccessful) throw WebDavException(resp.code, "PROPFIND 失败：${resp.code}")
            val xml = resp.body.string()
            parseMultiStatus(xml, path)
        }
    }

    suspend fun put(path: String, bytes: ByteArray, contentType: String = "application/zip"): Boolean =
        withContext(Dispatchers.IO) {
            val request = newRequest(path, isDirectory = false)
                .put(bytes.toRequestBody(contentType.toMediaType()))
                .build()
            execute(request).use { resp ->
                when {
                    resp.isSuccessful -> true
                    resp.code == 401 || resp.code == 403 -> throw WebDavException(resp.code, "WebDAV 认证失败（${resp.code}）")
                    else -> throw WebDavException(resp.code, "PUT 失败：${resp.code}")
                }
            }
        }

    suspend fun get(path: String): ByteArray = withContext(Dispatchers.IO) {
        val request = newRequest(path, isDirectory = false).get().build()
        execute(request).use { resp ->
            if (!resp.isSuccessful) throw WebDavException(resp.code, "GET 失败：${resp.code}")
            resp.body.bytes()
        }
    }

    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val request = newRequest(path, isDirectory = isDirectoryPath(path)).delete().build()
        execute(request).use { resp ->
            resp.isSuccessful || resp.code == 404
        }
    }

    suspend fun mkcol(path: String): Boolean = withContext(Dispatchers.IO) {
        val request = newRequest(path, isDirectory = true).method("MKCOL", null).build()
        execute(request).use { resp ->
            resp.isSuccessful || resp.code == 405 // 已存在
        }
    }

    suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val request = newRequest(path, isDirectory = isDirectoryPath(path))
            .header("Depth", "0")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        execute(request).use { resp -> resp.code == 207 || resp.isSuccessful }
    }

    /** 设置页"测试连接"。 */
    suspend fun test(): Boolean = try {
        list("", 0)
        true
    } catch (t: Throwable) {
        B4Log.w("WebDav", "连接测试失败", t)
        false
    }

    // ------------------------------------------------------------------ 内部

    private fun isDirectoryPath(path: String): Boolean = path.isEmpty() || path.endsWith("/")

    private fun newRequest(path: String, isDirectory: Boolean): Request.Builder {
        val builder = Request.Builder().url(urlFor(path, isDirectory))
        authHeader?.let { builder.header("Authorization", it) }
        builder.header("User-Agent", "olfact/0.1 (webdav)")
        return builder
    }

    private fun urlFor(path: String, isDirectory: Boolean): String {
        if (path.isEmpty()) return base + "/"
        val encoded = path.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(it) }
        val suffix = if (isDirectory) "/" else ""
        return "$base/$encoded$suffix"
    }

    private fun encodeSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun execute(request: Request): Response = client.newCall(request).execute()

    // ---------------------------------------------------------------- XML

    private fun parseMultiStatus(xml: String, requestedPath: String): List<DavEntry> {
        if (xml.isBlank()) return emptyList()
        val doc: Document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (t: Throwable) {
            throw WebDavException(0, "PROPFIND 返回的不是合法 XML", t)
        }

        val responses = elements(doc.documentElement, "response")
        val out = ArrayList<DavEntry>(responses.size)
        val requested = normalizePath(requestedPath)
        for (resp in responses) {
            val href = textOf(elements(resp, "href").firstOrNull()) ?: continue
            val path = normalizeHref(href, requestedPath)
            if (path == requested) continue
            val okProps = elements(resp, "propstat").firstOrNull { propstatOk(it) }
            val prop = okProps?.let { elements(it, "prop").firstOrNull() }
            val isDir = prop?.let { elements(it, "resourcetype").firstOrNull() }
                ?.let { elements(it, "collection").isNotEmpty() } ?: path.endsWith("/")
            val size = prop?.let { elements(it, "getcontentlength").firstOrNull() }
                ?.let { textOf(it)?.toLongOrNull() } ?: 0L
            val modified = prop?.let { elements(it, "getlastmodified").firstOrNull() }
                ?.let { textOf(it) }?.let { parseHttpDate(it) } ?: 0L
            val clean = path.trimEnd('/')
            out.add(DavEntry(clean, clean.substringAfterLast('/'), isDir, size, modified))
        }
        return out
    }

    private fun propstatOk(propstat: Element): Boolean {
        val status = textOf(elements(propstat, "status").firstOrNull()) ?: return true
        return status.contains(" 200 ") || status.endsWith(" 200")
    }

    private fun normalizeHref(href: String, requestedPath: String): String {
        var path = href.trim()
        if (path.startsWith("http://") || path.startsWith("https://")) {
            // 去掉 scheme://host，只留路径
            val idx = path.indexOf('/', path.indexOf("://") + 3)
            path = if (idx >= 0) path.substring(idx) else "/"
        }
        path = runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        val basePath = runCatching { java.net.URI(base).path }.getOrNull().orEmpty().trimEnd('/')
        if (basePath.isNotEmpty() && path.startsWith(basePath)) path = path.substring(basePath.length)
        return normalizePath(path)
    }

    private fun normalizePath(path: String): String {
        var p = path.trim()
        if (!p.startsWith("/")) p = "/$p"
        p = p.replace("//", "/")
        return p.trimEnd('/').ifEmpty { "/" }
    }

    private fun elements(parent: Element?, localName: String): List<Element> {
        if (parent == null) return emptyList()
        val out = ArrayList<Element>(4)
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            val name = el.localName ?: el.nodeName.substringAfter(':')
            if (name.equals(localName, ignoreCase = true)) out.add(el)
        }
        return out
    }

    private fun textOf(element: Element?): String? = element?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseHttpDate(value: String): Long = try {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        format.parse(value)?.time ?: 0L
    } catch (t: Throwable) {
        0L
    }

    companion object {
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/>" +
                "</d:prop></d:propfind>"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
