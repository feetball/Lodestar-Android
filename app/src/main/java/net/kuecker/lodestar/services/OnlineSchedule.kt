package net.kuecker.lodestar.services

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Small persistent values of the online data: which feeds are on, and when each resource was last requested. Backed by
 * the device-local preferences `lodestar.online`, which the backup rules leave out.
 */
interface OnlineStore {
    fun getBoolean(key: String): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long)
}

/** [OnlineStore] in `SharedPreferences`. */
class PreferencesOnlineStore(private val preferences: SharedPreferences) : OnlineStore {
    override fun getBoolean(key: String): Boolean = preferences.getBoolean(key, false)

    override fun putBoolean(key: String, value: Boolean) = preferences.edit { putBoolean(key, value) }

    override fun getLong(key: String): Long? = if (preferences.contains(key)) preferences.getLong(key, 0L) else null

    override fun putLong(key: String, value: Long) = preferences.edit { putLong(key, value) }
}

/** The keys of [OnlineStore]. */
object OnlineStoreKeys {
    fun enabled(feed: OnlineFeed) = "enabled.${feed.key}"
    fun attempt(resource: OnlineResource) = "attempt.${resource.key}"
    fun attemptFailed(resource: OnlineResource) = "attemptFailed.${resource.key}"

    /** Set once the user has read the first-time explanation. */
    const val explained = "explained"
}

/** What the schedule knows about one resource. */
data class OnlineResourceState(
    /** When it was last requested, successful or not. Survives turning the feed off, so toggling cannot bypass the interval. */
    val lastAttemptMillis: Long?,
    /** Whether that request failed. */
    val lastAttemptFailed: Boolean,
    /** The cached data's metadata, `null` without data. */
    val meta: OnlineCacheMeta?,
)

/**
 * The rules for when a resource may be requested and when it is due. Pure.
 *
 * - Never two requests closer than [OnlineResource.minimumIntervalMillis] (after a success) or
 *   [OnlineResource.retryIntervalMillis] (after a failure): for CelesTrak 2 hours, which is
 *   `ElementSetGroup.minimumDownloadIntervalHours`. "Update now" follows the same rule.
 * - A forecast of the same place also waits for the server's `Expires`.
 * - Data is due when there is none, when it is for another place, when it is older than the refresh interval, when the
 *   server's `Expires` has passed, or when its newest orbit epoch is older than [OnlineResource.staleDataDays].
 */
object OnlineSchedule {
    /**
     * The earliest time a request is allowed, in epoch milliseconds; 0 when there is no restriction. [key] is the wanted
     * copy. [expiresJitterMillis] is added to a server's `Expires` so that the installations of the app do not all ask at
     * the same minute (MET Norway asks for that).
     */
    fun earliestRequestMillis(
        resource: OnlineResource,
        state: OnlineResourceState,
        key: String? = null,
        expiresJitterMillis: Long = 0L,
    ): Long {
        val last = state.lastAttemptMillis ?: return 0L
        val interval = if (state.lastAttemptFailed) resource.retryIntervalMillis else resource.minimumIntervalMillis
        var earliest = last + interval
        val meta = state.meta
        if (meta != null && meta.key == key) meta.expiresMillis?.let { earliest = maxOf(earliest, it + expiresJitterMillis) }
        return earliest
    }

    /** Whether a request is allowed at [now]. */
    fun mayRequest(
        resource: OnlineResource,
        state: OnlineResourceState,
        now: Long,
        key: String? = null,
        expiresJitterMillis: Long = 0L,
    ): Boolean = now >= earliestRequestMillis(resource, state, key, expiresJitterMillis)

    /** Whether the data should be replaced at [now], ignoring the request rules. */
    fun isDue(resource: OnlineResource, state: OnlineResourceState, now: Long, key: String? = null): Boolean {
        val meta = state.meta ?: return true
        if (key != null && meta.key != key) return true
        if (now - meta.savedAtMillis >= resource.refreshIntervalMillis) return true
        // A server that says when its data expires (MET Norway) decides: the data is due as soon as it has expired.
        meta.expiresMillis?.let { if (now >= it) return true }
        val staleDays = resource.staleDataDays
        val epoch = meta.dataEpochMillis
        if (staleDays != null && epoch != null && now - epoch >= staleDays * 86_400_000.0) return true
        return false
    }

    /** Whether the automatic refresh should request now: due and allowed. */
    fun shouldRefresh(
        resource: OnlineResource,
        state: OnlineResourceState,
        now: Long,
        key: String? = null,
        expiresJitterMillis: Long = 0L,
    ): Boolean = isDue(resource, state, now, key) && mayRequest(resource, state, now, key, expiresJitterMillis)
}
