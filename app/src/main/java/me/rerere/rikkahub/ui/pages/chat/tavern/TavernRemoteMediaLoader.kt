package me.rerere.rikkahub.ui.pages.chat.tavern

import android.webkit.WebResourceResponse
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal const val TAVERN_MEDIA_MAX_BYTES = 15L * 1024 * 1024
internal const val TAVERN_MEDIA_CACHE_BYTES = 48L * 1024 * 1024

internal data class TavernRemoteMediaPayload(
    val mimeType: String,
    val bytes: ByteArray,
    val responseHeaders: Map<String, String>,
)

internal fun interface TavernRemoteMediaFetcher {
    fun fetch(rawUrl: String, requestHeaders: Map<String, String>): TavernRemoteMediaPayload?
}

internal fun isLikelyTavernImageRequest(rawUrl: String, accept: String?): Boolean {
    val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return false
    if (accept?.contains("image/", ignoreCase = true) == true) return true
    val extension = uri.path.orEmpty().substringAfterLast('/', "").substringAfterLast('.', "")
        .lowercase(Locale.ROOT)
    return extension in IMAGE_EXTENSIONS
}

internal fun validateTavernImageMetadata(contentType: String?, contentLength: Long): String? {
    val mime = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
    if (!mime.startsWith("image/")) return null
    if (contentLength > TAVERN_MEDIA_MAX_BYTES) return null
    return mime
}

internal class TavernRemoteMediaLoader private constructor(
    private val fetcher: TavernRemoteMediaFetcher,
    private val closeFetcher: () -> Unit,
) : Closeable {
    private val inFlight = ConcurrentHashMap<String, FutureTask<TavernRemoteMediaPayload?>>()
    private val closed = AtomicBoolean(false)

    internal fun load(rawUrl: String, requestHeaders: Map<String, String>): TavernRemoteMediaPayload? {
        if (closed.get()) return null
        if (!isLikelyTavernImageRequest(rawUrl, requestHeaders.headerValue("Accept"))) return null
        val task = FutureTask { fetcher.fetch(rawUrl, requestHeaders) }
        val activeTask = inFlight.putIfAbsent(rawUrl, task) ?: task.also { it.run() }
        return try {
            activeTask.get(32, TimeUnit.SECONDS)
        } catch (_: Exception) {
            activeTask.cancel(true)
            null
        } finally {
            inFlight.remove(rawUrl, activeTask)
        }
    }

    fun intercept(rawUrl: String, requestHeaders: Map<String, String>): WebResourceResponse? {
        val payload = load(rawUrl, requestHeaders) ?: return null
        return WebResourceResponse(
            payload.mimeType,
            null,
            200,
            "OK",
            payload.responseHeaders,
            ByteArrayInputStream(payload.bytes),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        inFlight.values.forEach { it.cancel(true) }
        inFlight.clear()
        closeFetcher()
    }

    companion object {
        fun create(cacheDir: File, baseClient: OkHttpClient): TavernRemoteMediaLoader {
            val fetcher = OkHttpTavernRemoteMediaFetcher(cacheDir, baseClient)
            return TavernRemoteMediaLoader(fetcher, fetcher::close)
        }

        fun createUncached(baseClient: OkHttpClient): TavernRemoteMediaLoader {
            val fetcher = OkHttpTavernRemoteMediaFetcher(null, baseClient)
            return TavernRemoteMediaLoader(fetcher, fetcher::close)
        }

        internal fun forTest(fetcher: TavernRemoteMediaFetcher) = TavernRemoteMediaLoader(fetcher) {}
    }
}

private class OkHttpTavernRemoteMediaFetcher(
    cacheDir: File?,
    baseClient: OkHttpClient,
) : TavernRemoteMediaFetcher, Closeable {
    private val closed = AtomicBoolean(false)
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val client = baseClient.newBuilder()
        .apply {
            if (cacheDir != null) {
                cache(Cache(File(cacheDir, "tavern_remote_media"), TAVERN_MEDIA_CACHE_BYTES))
            }
        }
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun fetch(rawUrl: String, requestHeaders: Map<String, String>): TavernRemoteMediaPayload? {
        if (closed.get()) return null
        val request = Request.Builder().url(rawUrl).get().apply {
            requestHeaders.headerValue("Accept")?.let { header("Accept", it) }
            requestHeaders.headerValue("Accept-Language")?.let { header("Accept-Language", it) }
        }.build()
        return try {
            execute(request)
        } catch (_: Exception) {
            runCatching {
                execute(request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build())
            }.getOrNull()
        }
    }

    private fun execute(request: Request): TavernRemoteMediaPayload? {
        if (closed.get()) return null
        val call = client.newCall(request)
        activeCalls += call
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) return null
                if (response.request.url.scheme !in setOf("http", "https")) return null
                val body = response.body
                val mime = validateTavernImageMetadata(
                    contentType = body.contentType()?.toString(),
                    contentLength = body.contentLength(),
                ) ?: return null
                val bytes = body.byteStream().use(::readBoundedImageBytes) ?: return null
                TavernRemoteMediaPayload(
                    mimeType = mime,
                    bytes = bytes,
                    responseHeaders = response.headers.toMap().filterKeys { name ->
                        name.lowercase(Locale.ROOT) !in STRIPPED_RESPONSE_HEADERS
                    },
                )
            }
        } finally {
            activeCalls -= call
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeCalls.forEach(Call::cancel)
        activeCalls.clear()
        runCatching { client.cache?.close() }
    }
}

private fun Map<String, String>.headerValue(name: String): String? = entries
    .firstOrNull { it.key.equals(name, ignoreCase = true) }
    ?.value

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "svg")
private val STRIPPED_RESPONSE_HEADERS = setOf("content-encoding", "content-length", "transfer-encoding", "connection")

private fun readBoundedImageBytes(input: java.io.InputStream): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > TAVERN_MEDIA_MAX_BYTES) return null
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
