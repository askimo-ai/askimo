/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.cache

import io.github.reactivecircus.cache4k.Cache

/**
 * Wraps a cache4k [Cache] so [getOrPut] and [invalidate]/[invalidateAll] share one monitor.
 *
 * cache4k never coordinates `invalidate` with a concurrent `get(key, loader)` — even its own
 * suspend loader only dedupes same-key loads, so an invalidate racing an in-flight load can be
 * silently overwritten by that load's `put()`, leaving a stale entry until the next invalidation
 * or TTL expiry. This wrapper closes that gap: invalidation blocks until any in-flight load
 * finishes publishing, then evicts it immediately. Fine as long as loads aren't hot-path.
 */
class SynchronizedCache<K : Any, V : Any>(
    private val delegate: Cache<K, V>,
) {
    private val lock = Any()

    /** Returns the cached value for [key], or null if absent/expired/evicted. */
    fun get(key: K): V? = delegate.get(key)

    /**
     * Returns the cached value for [key] if present, otherwise synchronously creates it via
     * [loader], caches it, and returns it.
     */
    fun getOrPut(key: K, loader: () -> V): V = delegate.get(key) ?: synchronized(lock) {
        delegate.get(key) ?: loader().also { delegate.put(key, it) }
    }

    /** Associates [value] with [key], replacing any existing mapping. */
    fun put(key: K, value: V) {
        synchronized(lock) { delegate.put(key, value) }
    }

    /** Discards any cached value for [key]. */
    fun invalidate(key: K) {
        synchronized(lock) { delegate.invalidate(key) }
    }

    /** Discards all entries in the cache. */
    fun invalidateAll() {
        synchronized(lock) { delegate.invalidateAll() }
    }
}
