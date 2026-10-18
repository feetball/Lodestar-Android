package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.satellites.ElementSetGroup
import net.kuecker.lodestar.kit.satellites.Satellite
import net.kuecker.lodestar.kit.satellites.SatellitePass
import net.kuecker.lodestar.kit.satellites.SatellitePassFinder
import java.time.Instant

/** The two lists of the Passes screen. */
enum class SatellitePassFilter(val title: String) {
    /** Only the International Space Station. */
    ISS("ISS"),

    /** The ISS and every other satellite of the lists. */
    BRIGHT("Bright satellites"),
}

/** One visible pass of one satellite. */
class SatellitePassItem(
    val satellite: Satellite,
    val group: ElementSetGroup,
    val pass: SatellitePass,
) {
    /** Whether this is the International Space Station. */
    val isIss: Boolean get() = satellite.catalogNumber == SatelliteLibrary.ISS_CATALOG_NUMBER

    /** "ISS" for the station, else the name of the element set. */
    val name: String get() = if (isIss) "ISS" else satellite.name

    /** When the satellite becomes visible; the rise time for a pass without a visible part. */
    val start: Instant get() = pass.visibleSpan?.start ?: pass.rise.date

    /** When it stops being visible. */
    val end: Instant get() = pass.visibleSpan?.end ?: pass.set.date

    /** Tells passes apart: the satellite and the rise time. */
    val id: String get() = "${satellite.catalogNumber}@${pass.rise.date.toEpochMilli()}"

    /** Whether the element set is within its group's freshness limit at [now]. */
    fun isFresh(now: Instant): Boolean = group.isFresh(satellite.elements, now)
}

/** Finding and selecting the passes the screens show. Pure. */
object SatellitePasses {
    /** The Passes screen looks this many days ahead. */
    const val HORIZON_DAYS = 3

    /** Seconds in the pass window. */
    private const val HORIZON_SECONDS = HORIZON_DAYS * 86_400.0

    /**
     * Every visible pass (sunlit satellite, dark sky, above 10°) of the library's ISS and bright satellites in the next
     * [HORIZON_DAYS] days from [start], by the time it becomes visible. Takes about a second on a phone: run it off the
     * main thread.
     */
    fun compute(library: SatelliteLibrary, observer: Observer, start: Instant): List<SatellitePassItem> {
        val finder = SatellitePassFinder(observer, DateInterval(start, HORIZON_SECONDS))
        val items = ArrayList<SatellitePassItem>()
        for (entry in library.passSatellites) {
            for (pass in finder.passes(entry.satellite)) {
                if (pass.isVisible) items.add(SatellitePassItem(entry.satellite, entry.group, pass))
            }
        }
        items.sortBy { it.start }
        return items
    }

    /** The passes for a [filter] of the Passes screen, in the order given. */
    fun filtered(items: List<SatellitePassItem>, filter: SatellitePassFilter): List<SatellitePassItem> = when (filter) {
        SatellitePassFilter.ISS -> items.filter { it.isIss }
        SatellitePassFilter.BRIGHT -> items
    }

    /** The passes not over at [now]. */
    fun upcoming(items: List<SatellitePassItem>, now: Instant): List<SatellitePassItem> = items.filter { it.end > now }

    /**
     * The visible passes that fall in [night] and are not over at [now], the ISS first and each satellite's passes in
     * time order, at most [limit] of them.
     */
    fun tonight(items: List<SatellitePassItem>, night: DateInterval, now: Instant, limit: Int): List<SatellitePassItem> =
        upcoming(items, now)
            .filter { DateInterval(it.start, it.end).intersects(night) }
            .sortedWith(compareByDescending<SatellitePassItem> { it.isIss }.thenBy { it.start })
            .take(limit)
}
