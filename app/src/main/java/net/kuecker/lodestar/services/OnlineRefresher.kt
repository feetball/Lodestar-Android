package net.kuecker.lodestar.services

import java.io.IOException

/** Thrown by a parser that was handed bytes it cannot use; the download is then discarded and the cache kept. */
class OnlineDataException(message: String) : Exception(message)

/** The result of parsing a download: the value, and the newest orbit epoch in it if it has one. */
class OnlineParsed<T : Any>(val value: T, val dataEpochMillis: Long? = null)

/** The outcome of [OnlineRefresher.refresh]. */
sealed interface OnlineRefreshOutcome<out T : Any> {
    /** New data was downloaded, parsed and cached. */
    class Updated<T : Any>(val value: T, val meta: OnlineCacheMeta) : OnlineRefreshOutcome<T>

    /** The server said the cached data is current. */
    class NotModified(val meta: OnlineCacheMeta) : OnlineRefreshOutcome<Nothing>

    /** Nothing changed; whatever was cached stays. */
    class Failed(val failure: OnlineFailure, val message: String) : OnlineRefreshOutcome<Nothing>
}

/**
 * One refresh of one resource: request (conditionally), validate with a parser, then replace the cache. A failed
 * request, an empty answer or an answer the parser rejects never touches the cached data. Records every request in the
 * [store] before it is made, so the request rules hold even across crashes and across turning the feed off and on.
 * Blocking: call it from a background dispatcher.
 */
class OnlineRefresher(
    private val cache: OnlineCache,
    private val downloader: OnlineDownloader,
    private val store: OnlineStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What the schedule needs to know about [resource]. */
    fun state(resource: OnlineResource): OnlineResourceState = OnlineResourceState(
        lastAttemptMillis = store.getLong(OnlineStoreKeys.attempt(resource)),
        lastAttemptFailed = store.getBoolean(OnlineStoreKeys.attemptFailed(resource)),
        meta = cache.readMeta(resource.key),
    )

    /**
     * Requests [url] for [resource]. [key] names the copy wanted (the forecast's rounded coordinates); the validators
     * of the cached copy are sent only when it is the same copy. [parse] throws [OnlineDataException] for bytes it
     * rejects.
     */
    fun <T : Any> refresh(
        resource: OnlineResource,
        url: String,
        key: String? = null,
        parse: (ByteArray) -> OnlineParsed<T>,
    ): OnlineRefreshOutcome<T> {
        val cached = cache.readMeta(resource.key)?.takeIf { it.key == key }
        store.putLong(OnlineStoreKeys.attempt(resource), clock())
        store.putBoolean(OnlineStoreKeys.attemptFailed(resource), true)
        val result = downloader.fetch(OnlineRequest(url, resource.maximumBytes, cached?.etag, cached?.lastModified))
        return when (result) {
            is OnlineResult.Failed -> OnlineRefreshOutcome.Failed(result.failure, result.message)
            is OnlineResult.NotModified -> notModified(resource, cached, result)
            is OnlineResult.Downloaded -> downloaded(resource, key, parse, result)
        }
    }

    /** The cached data parsed with [parse], or `null` when there is none or it no longer parses (it is then removed). */
    fun <T : Any> loadCached(resource: OnlineResource, parse: (ByteArray) -> OnlineParsed<T>): Pair<T, OnlineCacheMeta>? {
        val entry = cache.read(resource.key) ?: return null
        return try {
            parse(entry.bytes).value to entry.meta
        } catch (_: OnlineDataException) {
            cache.delete(resource.key)
            null
        }
    }

    private fun notModified(
        resource: OnlineResource,
        cached: OnlineCacheMeta?,
        result: OnlineResult.NotModified,
    ): OnlineRefreshOutcome<Nothing> {
        if (cached == null) return OnlineRefreshOutcome.Failed(OnlineFailure.EMPTY, OnlineFailure.EMPTY.message)
        val meta = cached.copy(savedAtMillis = clock(), expiresMillis = result.expiresMillis ?: cached.expiresMillis)
        return try {
            cache.updateMeta(resource.key, meta)
            store.putBoolean(OnlineStoreKeys.attemptFailed(resource), false)
            OnlineRefreshOutcome.NotModified(meta)
        } catch (_: IOException) {
            OnlineRefreshOutcome.Failed(OnlineFailure.STORAGE, OnlineFailure.STORAGE.message)
        }
    }

    private fun <T : Any> downloaded(
        resource: OnlineResource,
        key: String?,
        parse: (ByteArray) -> OnlineParsed<T>,
        result: OnlineResult.Downloaded,
    ): OnlineRefreshOutcome<T> {
        val parsed = try {
            parse(result.bytes)
        } catch (_: OnlineDataException) {
            return OnlineRefreshOutcome.Failed(OnlineFailure.UNREADABLE, OnlineFailure.UNREADABLE.message)
        }
        val meta = OnlineCacheMeta(
            savedAtMillis = clock(), etag = result.etag, lastModified = result.lastModified,
            expiresMillis = result.expiresMillis, dataEpochMillis = parsed.dataEpochMillis, key = key,
        )
        return try {
            cache.write(resource.key, result.bytes, meta)
            store.putBoolean(OnlineStoreKeys.attemptFailed(resource), false)
            OnlineRefreshOutcome.Updated(parsed.value, meta)
        } catch (_: IOException) {
            OnlineRefreshOutcome.Failed(OnlineFailure.STORAGE, OnlineFailure.STORAGE.message)
        }
    }
}
