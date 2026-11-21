package cn.olfact.core.backup

import cn.olfact.core.b4.B4Log
import cn.olfact.core.net.AppDns
import cn.olfact.core.network.requireAtMost
import cn.olfact.core.network.withCancellableResponse
import kotlinx.coroutines.CancellationException
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
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

/** Backup WebDAV client: PROPFIND, PUT, GET, DELETE and MKCOL with Basic authentication. */
class WebDavClient(
    baseUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    private val client: OkHttpClient = defaultClient()
) {
    private val base by lazy { baseUrl.trim().toHttpUrl() }
    private val authHeader: String? =
        if (!username.isNullOrEmpty()) Credentials.basic(username, password ?: "") else null

    suspend fun list(path: String = "", depth: Int = 1): List<DavEntry> = propfind(path, depth, missingIsEmpty = true)

    private suspend fun propfind(path: String, depth: Int, missingIsEmpty: Boolean): List<DavEntry> {
        require(depth >= 0)
        val request = propfindRequest(path, depth, isDirectory = true)
        return client.newCall(request).withCancellableResponse { response ->
            if (response.code == 404 && missingIsEmpty) return@withCancellableResponse emptyList()
            if (response.code != 207) throw WebDavException(response.code, "PROPFIND 失败：${response.code}")
            val xml = response.body.requireAtMost(MAX_LIST_BYTES).string()
            parseMultiStatus(xml, response.request.url, path, depth)
        }
    }

    suspend fun put(path: String, bytes: ByteArray, contentType: String = "application/zip"): Boolean {
        val request = newRequest(path, isDirectory = false).put(bytes.toRequestBody(contentType.toMediaType())).build()
        return client.newCall(request).withCancellableResponse { response ->
            when {
                response.isSuccessful -> true
                response.code == 401 || response.code == 403 -> throw WebDavException(response.code, "WebDAV 认证失败（${response.code}）")
                else -> throw WebDavException(response.code, "PUT 失败：${response.code}")
            }
        }
    }

    suspend fun get(path: String): ByteArray {
        val request = newRequest(path, isDirectory = false).get().build()
        return client.newCall(request).withCancellableResponse { response ->
            if (!response.isSuccessful) throw WebDavException(response.code, "GET 失败：${response.code}")
            response.body.requireAtMost(MAX_BACKUP_BYTES).bytes()
        }
    }

    suspend fun delete(path: String): Boolean {
        val request = newRequest(path, isDirectory = isDirectoryPath(path)).delete().build()
        return client.newCall(request).withCancellableResponse { it.isSuccessful || it.code == 404 }
    }

    suspend fun mkcol(path: String): Boolean {
        val request = newRequest(path, isDirectory = true).method("MKCOL", null).build()
        return client.newCall(request).withCancellableResponse { it.isSuccessful || it.code == 405 }
    }

    suspend fun exists(path: String): Boolean {
        val request = propfindRequest(path, depth = 0, isDirectory = isDirectoryPath(path))
        return client.newCall(request).withCancellableResponse { it.code == 207 }
    }

    /** 设置页「测试连接」：不存在的根目录不能当成可连接的空目录。 */
    suspend fun test(): Boolean = try {
        propfind("", 0, missingIsEmpty = false)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        B4Log.w("WebDav", "连接测试失败", e)
        false
    }

    private fun propfindRequest(path: String, depth: Int, isDirectory: Boolean): Request =
        newRequest(path, isDirectory)
            .header("Depth", depth.toString())
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()

    private fun isDirectoryPath(path: String): Boolean = path.isEmpty() || path.endsWith("/")

    private fun newRequest(path: String, isDirectory: Boolean): Request.Builder {
        val builder = Request.Builder().url(urlFor(path, isDirectory))
        authHeader?.let { builder.header("Authorization", it) }
        builder.header("User-Agent", "olfact/0.1 (webdav)")
        return builder
    }

    private fun urlFor(path: String, isDirectory: Boolean): HttpUrl {
        val segments = path.split('/').filter { it.isNotEmpty() }
        require(segments.all(::safeSegment)) { "非法 WebDAV 路径" }
        val builder = base.newBuilder().encodedPath(base.encodedPath.trimEnd('/').ifEmpty { "/" }).fragment(null)
        segments.forEach { builder.addPathSegment(it) }
        if (isDirectory && (segments.isNotEmpty() || base.encodedPath.trimEnd('/').isNotEmpty())) builder.addPathSegment("")
        return builder.build()
    }

    private fun parseMultiStatus(xml: String, requestedUrl: HttpUrl, requestedPath: String, depth: Int): List<DavEntry> {
        val document: Document = try {
            if (xml.contains("<!DOCTYPE")) throw SAXException("DOCTYPE is not allowed")
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw SAXException("External entities are not allowed") }
            builder.parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            throw WebDavException(0, "PROPFIND 返回的不是合法 XML", e)
        }
        val root = document.documentElement
        if (root.localName != "multistatus" || root.namespaceURI != "DAV:") {
            throw WebDavException(0, "PROPFIND 未返回 WebDAV 列表")
        }
        val parentSegments = requestedUrl.pathSegments.filter { it.isNotEmpty() }
        val requestedPrefix = requestedPath.split('/').filter { it.isNotEmpty() }
        val out = LinkedHashMap<String, DavEntry>()
        for (response in elements(root, "response")) {
            val href = textOf(elements(response, "href").firstOrNull()) ?: continue
            val childUrl = requestedUrl.resolve(href) ?: continue
            if (childUrl.scheme != requestedUrl.scheme || childUrl.host != requestedUrl.host || childUrl.port != requestedUrl.port) continue
            val childSegments = childUrl.pathSegments.filter { it.isNotEmpty() }
            if (childSegments.take(parentSegments.size) != parentSegments) continue
            val relative = childSegments.drop(parentSegments.size)
            if (relative.isEmpty() || relative.size > depth || !relative.all(::safeSegment)) continue
            val properties = elements(response, "propstat").firstOrNull { propstatOk(it) }
                ?.let { elements(it, "prop").firstOrNull() } ?: continue
            val isDirectory = elements(properties, "resourcetype").firstOrNull()
                ?.let { elements(it, "collection").isNotEmpty() } ?: childUrl.encodedPath.endsWith("/")
            val size = textOf(elements(properties, "getcontentlength").firstOrNull())?.toLongOrNull() ?: 0L
            val modified = textOf(elements(properties, "getlastmodified").firstOrNull())?.let(::parseHttpDate) ?: 0L
            val path = (requestedPrefix + relative).joinToString("/", prefix = "/")
            out[path] = DavEntry(path, relative.last(), isDirectory, size, modified)
        }
        return out.values.toList()
    }

    private fun safeSegment(segment: String) =
        segment != "." && segment != ".." && segment.none { it == '/' || it == '\\' || it.isISOControl() }

    private fun propstatOk(propstat: Element): Boolean {
        val status = textOf(elements(propstat, "status").firstOrNull()) ?: return true
        return status.contains(" 200 ") || status.endsWith(" 200")
    }

    private fun elements(parent: Element, localName: String): List<Element> {
        val children = parent.childNodes
        return (0 until children.length).mapNotNull { index ->
            val element = children.item(index).takeIf { it.nodeType == Node.ELEMENT_NODE } as? Element
            element?.takeIf { (it.localName ?: it.nodeName.substringAfter(':')).equals(localName, ignoreCase = true) }
        }
    }

    private fun textOf(element: Element?): String? = element?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseHttpDate(value: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.parse(value)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

    companion object {
        private const val MAX_LIST_BYTES = 4L * 1024 * 1024
        private const val MAX_BACKUP_BYTES = 65L * 1024 * 1024
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/>" +
                "</d:prop></d:propfind>"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(AppDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
