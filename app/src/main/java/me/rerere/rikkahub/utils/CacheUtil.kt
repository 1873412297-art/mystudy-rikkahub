package me.rerere.rikkahub.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A simple thread-safe cache implementation with expiration support.
 * This is a lightweight alternative to Guava Cache to avoid concurrency issues.
 *
 * 除写入过期外还带最大容量上限：超出 [maxSize] 时按写入时间淘汰最旧条目，
 * 防止大量不同 key（如异常正则 pattern）在过期窗口内无限累积。
 */
class SimpleCache<K, V>(
    private val expireAfterWriteMillis: Long,
    private val maxSize: Int = DEFAULT_MAX_SIZE,
) {
    private data class CacheEntry<V>(
        val value: V,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(expireAfterWriteMillis: Long): Boolean {
            return System.currentTimeMillis() - timestamp > expireAfterWriteMillis
        }
    }

    private val cache = ConcurrentHashMap<K, CacheEntry<V>>()

    fun getIfPresent(key: K): V? {
        val entry = cache[key] ?: return null
        return if (entry.isExpired(expireAfterWriteMillis)) {
            cache.remove(key)
            null
        } else {
            entry.value
        }
    }

    fun put(key: K, value: V) {
        cache[key] = CacheEntry(value)
        evictOverflowIfNeeded()
    }

    /**
     * 容量超限时的最旧条目淘汰。并发下允许少量误差（容量是软上限），
     * 用条件 remove 避免误删刚好被并发刷新的同 key 条目。
     */
    private fun evictOverflowIfNeeded() {
        val overflow = cache.size - maxSize
        if (overflow <= 0) return
        cache.entries
            .sortedBy { it.value.timestamp }
            .take(overflow)
            .forEach { cache.remove(it.key, it.value) }
    }

    fun invalidate(key: K) {
        cache.remove(key)
    }

    fun invalidateAll() {
        cache.clear()
    }

    fun cleanUp() {
        cache.entries.removeIf { it.value.isExpired(expireAfterWriteMillis) }
    }

    fun size(): Int = cache.size

    companion object {
        /** 默认容量上限：窗口期内最多保留的条目数 */
        private const val DEFAULT_MAX_SIZE = 128

        fun <K, V> builder() = Builder<K, V>()
    }

    class Builder<K, V> {
        private var expireAfterWriteMillis: Long = Long.MAX_VALUE
        private var maxSize: Int = DEFAULT_MAX_SIZE

        fun expireAfterWrite(duration: Long, unit: TimeUnit): Builder<K, V> {
            expireAfterWriteMillis = unit.toMillis(duration)
            return this
        }

        fun maximumSize(size: Int): Builder<K, V> {
            maxSize = size.coerceAtLeast(1)
            return this
        }

        fun build(): SimpleCache<K, V> {
            return SimpleCache(expireAfterWriteMillis, maxSize)
        }
    }
}
