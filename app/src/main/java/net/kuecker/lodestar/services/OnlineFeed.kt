package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.satellites.ElementSetGroup
import java.util.Locale

/**
 * The optional online data feeds (Settings ▸ Online Data). Every feed is off until the user switches it on, and every
 * screen works without it.
 */
enum class OnlineFeed(val key: String, val title: String) {
    /** CelesTrak `stations` and `visual`: the ISS and the other bright satellites. */
    SATELLITE_PASSES("satellites", "Satellite passes"),

    /** CelesTrak `starlink`: the streak warning in Aim. */
    STARLINK("starlink", "Starlink streak warnings"),

    /** MPC `CometEls.txt`: the comets that join the object catalog. */
    COMETS("comets", "Comets"),

    /** MET Norway Locationforecast 2.0 `complete`: hourly cloud cover. */
    CLOUDS("clouds", "Cloud forecast"),
    ;

    /** The files this feed downloads. */
    val resources: List<OnlineResource> get() = OnlineResource.entries.filter { it.feed == this }

    companion object {
        /** The feed whose [key] is given, or `null`. */
        fun fromKey(key: String): OnlineFeed? = entries.firstOrNull { it.key == key }
    }
}

private const val HOUR_MILLIS = 3_600_000L
private const val DAY_MILLIS = 24 * HOUR_MILLIS

/** CelesTrak's rule: never download a group more often than this. */
private val celestrakMinimumMillis = (ElementSetGroup.minimumDownloadIntervalHours * HOUR_MILLIS).toLong()

/**
 * One downloadable file, with the rules every request for it follows.
 *
 * @property key Names the cache file and the bookkeeping entries.
 * @property host The provider's host name, for messages.
 * @property maximumBytes The most bytes a response may have (the download is abandoned beyond it).
 * @property refreshIntervalMillis How old the data may get before it is downloaded again when the app is used.
 * @property minimumIntervalMillis The shortest time between two requests after a successful one, for the automatic
 * refresh and for "Update now" alike.
 * @property retryIntervalMillis The shortest time between a failed request and the next one.
 * @property requiresUnmetered Whether the download waits for an unmetered network (Wi-Fi).
 * @property staleDataDays Older data than this (days since its newest element set) is replaced as soon as the
 * request rules allow, whatever [refreshIntervalMillis] says; `null` for none.
 */
enum class OnlineResource(
    val feed: OnlineFeed,
    val key: String,
    val host: String,
    val maximumBytes: Int,
    val refreshIntervalMillis: Long,
    val minimumIntervalMillis: Long,
    val retryIntervalMillis: Long,
    val requiresUnmetered: Boolean = false,
    val staleDataDays: Double? = null,
) {
    /** Space stations: 21 records, about 9 KB. */
    STATIONS(
        OnlineFeed.SATELLITE_PASSES, "celestrak-stations", "celestrak.org", 512 * 1024,
        refreshIntervalMillis = (ElementSetGroup.STATIONS.refreshIntervalHours * HOUR_MILLIS).toLong(),
        minimumIntervalMillis = celestrakMinimumMillis, retryIntervalMillis = celestrakMinimumMillis,
        staleDataDays = 2.0,
    ),

    /** The brightest satellites: about 160 records, 65 KB. */
    VISUAL(
        OnlineFeed.SATELLITE_PASSES, "celestrak-visual", "celestrak.org", 1024 * 1024,
        refreshIntervalMillis = (ElementSetGroup.VISUAL.refreshIntervalHours * HOUR_MILLIS).toLong(),
        minimumIntervalMillis = celestrakMinimumMillis, retryIntervalMillis = celestrakMinimumMillis,
    ),

    /** The Starlink constellation: about 11,000 records, 4.7 MB. */
    STARLINK(
        OnlineFeed.STARLINK, "celestrak-starlink", "celestrak.org", 16 * 1024 * 1024,
        refreshIntervalMillis = (ElementSetGroup.STARLINK.refreshIntervalHours * HOUR_MILLIS).toLong(),
        minimumIntervalMillis = celestrakMinimumMillis, retryIntervalMillis = celestrakMinimumMillis,
        requiresUnmetered = true,
    ),

    /** The Minor Planet Center's comet elements: about 960 lines, 164 KB, at most once a day. */
    COMETS(
        OnlineFeed.COMETS, "mpc-comets", "www.minorplanetcenter.net", 2 * 1024 * 1024,
        refreshIntervalMillis = DAY_MILLIS, minimumIntervalMillis = DAY_MILLIS, retryIntervalMillis = HOUR_MILLIS,
    ),

    /** MET Norway's forecast for one grid point: about 64 KB. The server's `Expires` header sets the next request. */
    FORECAST(
        OnlineFeed.CLOUDS, "met-forecast", "api.met.no", 1024 * 1024,
        refreshIntervalMillis = HOUR_MILLIS, minimumIntervalMillis = 10 * 60_000L, retryIntervalMillis = 10 * 60_000L,
    ),
    ;

    /** The CelesTrak group of a satellite resource, or `null`. */
    val group: ElementSetGroup?
        get() = when (this) {
            STATIONS -> ElementSetGroup.STATIONS
            VISUAL -> ElementSetGroup.VISUAL
            STARLINK -> ElementSetGroup.STARLINK
            COMETS, FORECAST -> null
        }

    /**
     * The HTTPS address. [latitude] and [longitude] are used by the forecast only and are rounded to
     * [OnlineCoordinates.stepDegrees] here, whatever the caller passes.
     */
    fun url(latitude: Double? = null, longitude: Double? = null): String = when (this) {
        STATIONS, VISUAL, STARLINK -> "https://celestrak.org/NORAD/elements/gp.php?${group?.query}"
        COMETS -> "https://www.minorplanetcenter.net/iau/MPCORB/CometEls.txt"
        FORECAST -> {
            require(latitude != null && longitude != null) { "The forecast needs a location." }
            "https://api.met.no/weatherapi/locationforecast/2.0/complete?" + OnlineCoordinates.query(latitude, longitude)
        }
    }
}

/** The coordinates the cloud forecast sends: rounded to 0.1° (about 10 km), which is also MET Norway's cache key. */
object OnlineCoordinates {
    /** The rounding step in degrees. */
    const val stepDegrees = 0.1

    /** [degrees] rounded to the step, as text with one decimal and no "-0.0". */
    fun rounded(degrees: Double): String {
        val value = Math.round(degrees * 10.0) / 10.0
        val text = String.format(Locale.ROOT, "%.1f", value)
        return if (text == "-0.0") "0.0" else text
    }

    /** `lat=..&lon=..` for the request. */
    fun query(latitude: Double, longitude: Double): String = "lat=${rounded(latitude)}&lon=${rounded(longitude)}"

    /** Tells two places apart exactly as far as the request does: "32.8,-96.8". */
    fun key(latitude: Double, longitude: Double): String = "${rounded(latitude)},${rounded(longitude)}"
}
