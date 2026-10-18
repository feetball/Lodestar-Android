package net.kuecker.lodestar.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.BuildConfig
import net.kuecker.lodestar.kit.comets.SmallBody
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.random.Random

/**
 * What Settings ▸ Online Data shows about one feed.
 *
 * @property lastUpdateMillis When the feed's data was last confirmed current (the oldest of its files); `null` for never.
 * @property error A sentence about the last attempt when it failed or is waiting; the saved data stays in use.
 * @property nextRequestMillis The earliest time "Update now" is allowed again, when that is in the future.
 */
data class OnlineFeedStatus(
    val isEnabled: Boolean = false,
    val lastUpdateMillis: Long? = null,
    val isUpdating: Boolean = false,
    val error: String? = null,
    val nextRequestMillis: Long? = null,
)

/**
 * The optional downloads (satellite elements, Starlink elements, comets, the cloud forecast) and what comes of them.
 *
 * Everything is off until the user switches a feed on. A feed is refreshed when the app comes to the foreground, when it
 * is switched on and when a screen asks for a forecast; there are no background jobs. Downloads, parsing and satellite
 * construction run off the main thread, one at a time; results appear in the Compose state of this class ([statuses],
 * [satellites], [forecast]) and in the catalog (comets). A failed or unreadable download keeps the saved data. Turning a
 * feed off deletes its saved data and the copy in memory.
 *
 * Create it once (the app model does); all members are for the main thread.
 */
class OnlineDataService(
    context: Context,
    private val scope: CoroutineScope,
    private val catalog: CatalogService,
    private val locationProvider: () -> GeographicLocation?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val store: OnlineStore = PreferencesOnlineStore(appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))
    private val cache: OnlineCache by lazy { OnlineCache(File(appContext.noBackupFilesDir, "online")) }
    private val refresher: OnlineRefresher by lazy {
        OnlineRefresher(cache, OnlineDownloader(OnlineUserAgent.make(BuildConfig.VERSION_NAME)), store, clock)
    }
    private val work = Mutex()
    private val loaded = HashSet<OnlineFeed>()
    private val flagsRead = CompletableDeferred<Unit>()

    /** Added to a server's `Expires`, once per installation, so that the app's users do not all ask in the same minute. */
    private val expiresJitterMillis = Random.nextLong(0, MAXIMUM_JITTER_MILLIS)

    /** The state of every feed. */
    var statuses by mutableStateOf(OnlineFeed.entries.associateWith { OnlineFeedStatus() })
        private set

    /** The satellites in memory (empty while the feeds are off). */
    var satellites by mutableStateOf(SatelliteLibrary.EMPTY)
        private set

    /** The cloud forecast in memory, for the place it was requested for. */
    var forecast by mutableStateOf<CloudForecast?>(null)
        private set

    /** Whether the user has read the first-time explanation. */
    var hasExplained by mutableStateOf(false)
        private set

    init {
        scope.launch {
            val flags = withContext(Dispatchers.IO) { OnlineFeed.entries.associateWith { store.getBoolean(OnlineStoreKeys.enabled(it)) } }
            val explained = withContext(Dispatchers.IO) { store.getBoolean(OnlineStoreKeys.explained) }
            hasExplained = explained
            statuses = statuses.mapValues { (feed, status) -> status.copy(isEnabled = flags.getValue(feed)) }
            flagsRead.complete(Unit)
        }
    }

    /** Whether [feed] is switched on. */
    fun isEnabled(feed: OnlineFeed): Boolean = statuses.getValue(feed).isEnabled

    /** Remembers that the first-time explanation was shown. */
    fun markExplained() {
        hasExplained = true
        scope.launch(Dispatchers.IO) { store.putBoolean(OnlineStoreKeys.explained, true) }
    }

    /** Switches [feed] on (and downloads it at once) or off (and deletes what it downloaded). */
    fun setEnabled(feed: OnlineFeed, enabled: Boolean) {
        if (isEnabled(feed) == enabled) return
        update(feed) { OnlineFeedStatus(isEnabled = enabled) }
        scope.launch(Dispatchers.IO) { store.putBoolean(OnlineStoreKeys.enabled(feed), enabled) }
        if (enabled) {
            scope.launch { work.withLock { loadLocked(feed); refreshLocked(feed, manual = false) } }
        } else {
            clearMemory(feed)
            scope.launch {
                work.withLock {
                    withContext(Dispatchers.IO) { feed.resources.forEach { cache.delete(it.key) } }
                    loaded.remove(feed)
                }
            }
        }
    }

    /** Downloads [feed] now, if its request rules allow it. */
    fun updateNow(feed: OnlineFeed) {
        if (!isEnabled(feed)) return
        scope.launch { work.withLock { loadLocked(feed); refreshLocked(feed, manual = true) } }
    }

    /** Loads the saved data of the feeds that are on and refreshes the stale ones. Call when the app comes to the foreground. */
    fun onForeground() {
        scope.launch {
            flagsRead.await()
            work.withLock {
                for (feed in OnlineFeed.entries) {
                    if (!isEnabled(feed)) continue
                    loadLocked(feed)
                    refreshLocked(feed, manual = false)
                }
            }
        }
    }

    /** Asks for the forecast of the current place if the one in memory is for another place or has expired. */
    fun refreshForecast() {
        if (!isEnabled(OnlineFeed.CLOUDS)) return
        scope.launch { work.withLock { loadLocked(OnlineFeed.CLOUDS); refreshLocked(OnlineFeed.CLOUDS, manual = false) } }
    }

    /**
     * Takes [bytes] as if [resource] had just been downloaded: validates them, saves them and publishes the data, without
     * a request. The feed must be on. For the screenshot harness and tests; returns whether the bytes were usable.
     */
    suspend fun install(resource: OnlineResource, bytes: ByteArray, key: String? = null): Boolean = work.withLock {
        if (!isEnabled(resource.feed)) return@withLock false
        val now = clock()
        try {
            val parsed = withContext(Dispatchers.IO) { parser(resource).invoke(bytes) }
            val meta = OnlineCacheMeta(savedAtMillis = now, dataEpochMillis = parsed.dataEpochMillis, key = key)
            withContext(Dispatchers.IO) { cache.write(resource.key, bytes, meta) }
            publish(resource, parsed.value)
            publishStatus(resource.feed)
            true
        } catch (_: OnlineDataException) {
            false
        } catch (_: IOException) {
            false
        }
    }

    // Loading

    private suspend fun loadLocked(feed: OnlineFeed) {
        if (!loaded.add(feed)) return
        for (resource in feed.resources) {
            val saved = withContext(Dispatchers.IO) { refresher.loadCached(resource, parser(resource)) }
            if (saved != null && isEnabled(feed)) publish(resource, saved.first)
        }
        publishStatus(feed)
    }

    // Refreshing

    private suspend fun refreshLocked(feed: OnlineFeed, manual: Boolean) {
        for (resource in feed.resources) {
            if (!isEnabled(feed)) return
            refreshResource(feed, resource, manual)
        }
        publishStatus(feed)
    }

    private suspend fun refreshResource(feed: OnlineFeed, resource: OnlineResource, manual: Boolean) {
        val now = clock()
        val place = if (resource == OnlineResource.FORECAST) locationProvider() else null
        if (resource == OnlineResource.FORECAST && place == null) {
            update(feed) { it.copy(error = "Choose an observing location to get a cloud forecast.") }
            return
        }
        val key = place?.let { OnlineCoordinates.key(it.latitude, it.longitude) }
        val state = withContext(Dispatchers.IO) { refresher.state(resource) }
        if (!OnlineSchedule.mayRequest(resource, state, now, key, expiresJitterMillis)) return
        if (!manual && !OnlineSchedule.isDue(resource, state, now, key)) return
        if (!isOnline()) {
            update(feed) { it.copy(error = "No network connection.") }
            return
        }
        if (resource.requiresUnmetered && !isUnmetered()) {
            update(feed) {
                it.copy(error = "Waiting for an unmetered network such as Wi-Fi: this download is too large for mobile data.")
            }
            return
        }
        update(feed) { it.copy(isUpdating = true) }
        val url = if (place != null) resource.url(place.latitude, place.longitude) else resource.url()
        val outcome = withContext(Dispatchers.IO) { refresher.refresh(resource, url, key, parser(resource)) }
        update(feed) { it.copy(isUpdating = false) }
        when (outcome) {
            is OnlineRefreshOutcome.Updated -> {
                if (isEnabled(feed)) {
                    publish(resource, outcome.value)
                    update(feed) { it.copy(error = null) }
                } else {
                    // Switched off while the download was running: nothing of it stays.
                    withContext(Dispatchers.IO) { cache.delete(resource.key) }
                }
            }
            is OnlineRefreshOutcome.NotModified -> update(feed) { it.copy(error = null) }
            is OnlineRefreshOutcome.Failed -> update(feed) { it.copy(error = failureText(outcome, state)) }
        }
        publishStatus(feed)
    }

    private fun failureText(outcome: OnlineRefreshOutcome.Failed, before: OnlineResourceState): String {
        val kept = if (before.meta != null) " The saved data is kept." else ""
        return "The last update failed. ${outcome.message}$kept"
    }

    /** How [resource]'s bytes are validated and turned into the data in memory. */
    private fun parser(resource: OnlineResource): (ByteArray) -> OnlineParsed<Any> = when (resource) {
        OnlineResource.STATIONS, OnlineResource.VISUAL, OnlineResource.STARLINK -> { bytes ->
            val group = checkNotNull(resource.group)
            val parsed = SatelliteGroup.parse(group, bytes, Instant.ofEpochMilli(clock()))
            OnlineParsed(parsed.value, parsed.dataEpochMillis)
        }
        OnlineResource.COMETS -> { bytes -> OnlineCometData.parse(bytes).let { OnlineParsed(it.value, it.dataEpochMillis) } }
        OnlineResource.FORECAST -> { bytes -> ForecastParser.parse(bytes).let { OnlineParsed(it.value, it.dataEpochMillis) } }
    }

    /** Puts parsed data where the screens read it. Main thread. */
    private fun publish(resource: OnlineResource, value: Any) {
        when (resource) {
            OnlineResource.STATIONS, OnlineResource.VISUAL, OnlineResource.STARLINK ->
                (value as? SatelliteGroup)?.let { satellites = satellites.with(it) }
            OnlineResource.COMETS ->
                (value as? List<*>)?.filterIsInstance<SmallBody>()?.let(catalog::setDownloadedSmallBodies)
            OnlineResource.FORECAST -> (value as? CloudForecast)?.let { forecast = it }
        }
    }

    private suspend fun publishStatus(feed: OnlineFeed) {
        val states = withContext(Dispatchers.IO) { feed.resources.map { it to refresher.state(it) } }
        val updates = states.mapNotNull { it.second.meta?.savedAtMillis }
        val next = states.maxOf { (resource, state) ->
            OnlineSchedule.earliestRequestMillis(resource, state, forecastKey(resource), expiresJitterMillis)
        }
        update(feed) {
            it.copy(lastUpdateMillis = updates.minOrNull(), nextRequestMillis = next.takeIf { millis -> millis > clock() })
        }
    }

    private fun forecastKey(resource: OnlineResource): String? {
        if (resource != OnlineResource.FORECAST) return null
        val place = locationProvider() ?: return null
        return OnlineCoordinates.key(place.latitude, place.longitude)
    }

    // State

    private fun update(feed: OnlineFeed, change: (OnlineFeedStatus) -> OnlineFeedStatus) {
        statuses = statuses + (feed to change(statuses.getValue(feed)))
    }

    private fun clearMemory(feed: OnlineFeed) {
        when (feed) {
            OnlineFeed.SATELLITE_PASSES, OnlineFeed.STARLINK -> satellites = satellites.without(feed)
            OnlineFeed.COMETS -> catalog.setDownloadedSmallBodies(emptyList())
            OnlineFeed.CLOUDS -> forecast = null
        }
    }

    // Network

    private fun capabilities(): NetworkCapabilities? {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return manager.getNetworkCapabilities(manager.activeNetwork ?: return null)
    }

    private fun isOnline(): Boolean = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun isUnmetered(): Boolean = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    private companion object {
        const val PREFERENCES = "lodestar.online"
        const val MAXIMUM_JITTER_MILLIS = 5 * 60_000L
    }
}
