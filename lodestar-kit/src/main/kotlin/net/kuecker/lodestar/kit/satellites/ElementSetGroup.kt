package net.kuecker.lodestar.kit.satellites

import java.time.Instant

/**
 * The CelesTrak GP groups the satellite features use, with the freshness limits the app should apply. The engine
 * itself never downloads anything: the app fetches `https://celestrak.org/NORAD/elements/gp.php?<query>` and hands
 * the bytes to [ElementSetParser.parseJson].
 *
 * CelesTrak refreshes each group about every two hours and answers a request for unchanged data with HTTP 403,
 * so do not download a group more often than [minimumDownloadIntervalHours].
 *
 * @property rawValue The `GROUP=` value of the CelesTrak query, e.g. "stations".
 * @property maximumAgeDays Elements older than this (days from epoch to now) are too old for predictions that
 * must be right to a few seconds; show a warning or refresh.
 * @property refreshIntervalHours How often to download a fresh copy while the screen is in use.
 * @property typicalRecordCount About how many element sets the group holds (2026), for sizing the cache.
 */
enum class ElementSetGroup(
    val rawValue: String,
    val maximumAgeDays: Double,
    val refreshIntervalHours: Double,
    val typicalRecordCount: Int,
) {
    /** Space stations (ISS, Tiangong and visiting vehicles): the bright, predictable targets. */
    STATIONS("stations", maximumAgeDays = 3.0, refreshIntervalHours = 12.0, typicalRecordCount = 25),

    /** The brightest satellites and rocket bodies (about 150): the "visual" group. */
    VISUAL("visual", maximumAgeDays = 7.0, refreshIntervalHours = 24.0, typicalRecordCount = 160),

    /** The Starlink constellation: for the streak warning. */
    STARLINK("starlink", maximumAgeDays = 3.0, refreshIntervalHours = 24.0, typicalRecordCount = 11_000),
    ;

    /** The `GROUP=` value of the CelesTrak query (same as [rawValue]). */
    val groupName: String get() = rawValue

    /** The query string for the GP endpoint, e.g. `GROUP=stations&FORMAT=json`. */
    val query: String get() = "GROUP=$rawValue&FORMAT=json"

    /** `true` when [elements] is at most [maximumAgeDays] old at [date] (a future epoch counts as fresh). */
    fun isFresh(elements: OrbitalElements, date: Instant): Boolean = elements.ageDays(date) <= maximumAgeDays

    companion object {
        /** The shortest sensible time between two downloads of the same group, in hours. */
        const val minimumDownloadIntervalHours: Double = 2.0

        /** The group whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): ElementSetGroup? = entries.firstOrNull { it.rawValue == raw }
    }
}
