package net.kuecker.lodestar.kit.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Why a full Moon is special.
 *
 * @property rawValue The name used as the serialized form.
 */
@Serializable
enum class FullMoonCategory(val rawValue: String) {
    /** The full Moon is unusually close: its geocentric distance is at most [FullMoonSpecialFinder.supermoonMaximumDistanceKilometers]. */
    @SerialName("supermoon")
    SUPERMOON("supermoon"),

    /** The full Moon is unusually far: its geocentric distance is at least [FullMoonSpecialFinder.micromoonMinimumDistanceKilometers]. */
    @SerialName("micromoon")
    MICROMOON("micromoon"),

    /** The second full Moon of a calendar month in the chosen time zone. */
    @SerialName("blueMoon")
    BLUE_MOON("blueMoon"),
    ;

    /** The English name, e.g. "Supermoon". */
    val displayName: String
        get() = when (this) {
            SUPERMOON -> "Supermoon"
            MICROMOON -> "Micromoon"
            BLUE_MOON -> "Blue Moon"
        }

    companion object {
        /** The category whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): FullMoonCategory? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * A full Moon that is a supermoon, a micromoon or a blue moon.
 *
 * @property categories Why it is special: one to three categories in the order of [FullMoonCategory].
 * @property distanceKilometers The Moon's geocentric distance at the full Moon in kilometres.
 * @property angularDiameterArcseconds The Moon's geocentric angular diameter at the full Moon in arcseconds.
 */
data class FullMoonSpecial(
    val categories: List<FullMoonCategory>,
    val distanceKilometers: Double,
    val angularDiameterArcseconds: Double,
)

/**
 * Finds the supermoons, micromoons and blue moons.
 *
 * **Definitions.** The full Moon is the instant of lunar elongation 180°. A *supermoon* is a full Moon whose
 * geocentric centre distance is at most [supermoonMaximumDistanceKilometers] (362 500 km). This is the working form
 * of R. Nolle's "within 90 % of its closest perigee" rule: the full Moon falls within about a day of a perigee of
 * the closest kind, and the cut reproduces the usual lists (for 2026: 3 January at 362 312 km, 24 November and
 * 24 December). A *micromoon* is the mirror image, at least [micromoonMinimumDistanceKilometers] (400 600 km), which
 * is as far from the extreme apogee (406 720 km) as the supermoon cut is from the extreme perigee (356 371 km;
 * J. Meeus, *Astronomical Algorithms*, ch. 50). A *blue moon* is the second of two full Moons in one calendar month
 * of the given time zone (the "monthly" blue moon).
 *
 * Cost for one year: about 10 ms (the phases are shared with the eclipse finders when [EventCalculator] runs them).
 */
object FullMoonSpecialFinder {
    /** Largest geocentric distance of a supermoon, in kilometres. */
    const val supermoonMaximumDistanceKilometers: Double = 362_500.0

    /** Smallest geocentric distance of a micromoon, in kilometres. */
    const val micromoonMinimumDistanceKilometers: Double = 400_600.0

    /**
     * The special full Moons from [from] to [to] (both inclusive), as events without visibility, in time order.
     * [timeZone] decides which full Moons share a calendar month.
     */
    fun events(from: Instant, to: Instant, timeZone: ZoneId = ZoneOffset.UTC): List<AstronomicalEvent> {
        if (!EventSearch.isUsable(from, to)) return emptyList()
        val end = EventSearch.limitedEnd(from, to)
        return events(from, end, timeZone, PhaseTable(monthStart(from, timeZone), end))
    }

    /** The finder with the full Moons already known; [phases] must start at or before the start of the month of [from]. */
    internal fun events(from: Instant, to: Instant, timeZone: ZoneId, phases: PhaseTable): List<AstronomicalEvent> {
        val found = ArrayList<AstronomicalEvent>()
        var lastMonth = Int.MIN_VALUE
        var countInMonth = 0
        for (full in phases.fullMoons) {
            val key = monthKey(full, timeZone)
            if (key != lastMonth) {
                lastMonth = key
                countInMonth = 0
            }
            countInMonth += 1
            if (full < from || full > to) continue
            val jd = JulianDate(full)
            val distance = Ephemeris.geocentricJ2000(SolarSystemBody.MOON, jd).length * SkyFrame.kilometersPerAU
            val categories = ArrayList<FullMoonCategory>()
            if (distance <= supermoonMaximumDistanceKilometers) categories.add(FullMoonCategory.SUPERMOON)
            if (distance >= micromoonMinimumDistanceKilometers) categories.add(FullMoonCategory.MICROMOON)
            if (countInMonth == 2) categories.add(FullMoonCategory.BLUE_MOON)
            if (categories.isEmpty()) continue
            val diameter = Ephemeris.state(SolarSystemBody.MOON, jd).angularDiameterArcseconds
            found.add(
                AstronomicalEvent(full, EventDetails.FullMoonSpecial(FullMoonSpecial(categories, distance, diameter)), null),
            )
        }
        return found
    }

    /** The start of the calendar month of [date] in [timeZone]. */
    internal fun monthStart(date: Instant, timeZone: ZoneId): Instant =
        date.atZone(timeZone).withDayOfMonth(1).toLocalDate().atStartOfDay(timeZone).toInstant()

    /** A number that is the same for all instants of one calendar month in [timeZone] and grows with time. */
    private fun monthKey(date: Instant, timeZone: ZoneId): Int {
        val local = date.atZone(timeZone)
        return local.year * 12 + local.monthValue - 1
    }
}
