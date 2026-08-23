package me.rerere.rikkahub.ui.pages.chat.tavern

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal const val TAVERN_RESOURCE_ORIGIN = "https://rikkahub.local/resource/"

internal class TavernConversationResourceRegistry(
    private val context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val uri: Uri, val mime: String)

    private val entries = ExpiringResourceTokenStore<Entry>(nowMillis)
    private val allowedFileRoots = buildList {
        add(context.filesDir)
        add(context.cacheDir)
        context.externalCacheDir?.let(::add)
        context.getExternalFilesDirs(null).filterNotNull().forEach(::add)
    }.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }

    fun map(rawUrl: String, mime: String? = null): String {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return ""
        when (uri.scheme?.lowercase()) {
            "http", "https", "data", "blob" -> return rawUrl
            "content" -> Unit
            "file" -> if (!isAllowedFile(uri)) return ""
            else -> return ""
        }
        val resolvedMime = mime?.takeIf { it.isNotBlank() }
            ?: context.contentResolver.getType(uri)
            ?: "application/octet-stream"
        val token = entries.put(Entry(uri, resolvedMime))
        return TAVERN_RESOURCE_ORIGIN + token
    }

    fun intercept(rawUrl: String): WebResourceResponse? {
        val token = rawUrl.takeIf { it.startsWith(TAVERN_RESOURCE_ORIGIN) }
            ?.removePrefix(TAVERN_RESOURCE_ORIGIN)
            ?.takeIf { it.matches(TOKEN_REGEX) }
            ?: return null
        val entry = entries.get(token) ?: return blockedResponse()
        val stream = runCatching {
            when (entry.uri.scheme?.lowercase()) {
                "content" -> context.contentResolver.openInputStream(entry.uri)
                "file" -> entry.uri.path?.let(::File)?.takeIf(::isAllowedFile)?.let(::FileInputStream)
                else -> null
            }
        }.getOrNull() ?: return blockedResponse()
        return WebResourceResponse(entry.mime, null, stream)
    }

    fun originalUri(rawUrl: String): Uri? {
        val token = rawUrl.takeIf { it.startsWith(TAVERN_RESOURCE_ORIGIN) }
            ?.removePrefix(TAVERN_RESOURCE_ORIGIN)
            ?.takeIf { it.matches(TOKEN_REGEX) }
            ?: return null
        return entries.get(token)?.uri
    }

    fun clear() = entries.clear()

    private fun isAllowedFile(uri: Uri): Boolean = uri.path?.let(::File)?.let(::isAllowedFile) == true

    private fun isAllowedFile(file: File): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return isPathWithinRoots(canonical, allowedFileRoots)
    }

    companion object {
        internal const val TOKEN_TTL_MILLIS = 10 * 60 * 1000L
        private val TOKEN_REGEX = Regex("[0-9a-fA-F-]{36}")
    }
}

internal class ExpiringResourceTokenStore<T>(
    private val nowMillis: () -> Long,
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
    private val ttlMillis: Long = TavernConversationResourceRegistry.TOKEN_TTL_MILLIS,
) {
    private data class Lease<T>(val value: T, val expiresAt: Long)
    private val leases = ConcurrentHashMap<String, Lease<T>>()

    fun put(value: T): String {
        purgeExpired()
        val token = tokenFactory()
        leases[token] = Lease(value, nowMillis() + ttlMillis)
        return token
    }

    fun get(token: String): T? {
        val lease = leases[token] ?: return null
        if (lease.expiresAt <= nowMillis()) {
            leases.remove(token)
            return null
        }
        return lease.value
    }

    fun clear() = leases.clear()

    private fun purgeExpired() {
        val now = nowMillis()
        leases.entries.removeIf { it.value.expiresAt <= now }
    }
}

internal fun isPathWithinRoots(file: File, roots: List<File>): Boolean = roots.any { root ->
    file.path == root.path || file.path.startsWith(root.path + File.separator)
}
