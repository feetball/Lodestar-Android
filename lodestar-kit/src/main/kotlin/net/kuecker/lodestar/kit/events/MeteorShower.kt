package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.floor

/**
 * One shower of the IMO Working List of Visual Meteor Showers.
 *
 * Source: International Meteor Organization, *2026 Meteor Shower Calendar* (IMO INFO 3-25, edited by Jürgen
 * Rendtel), archived copy https://web.archive.org/web/20251223170423/https://imo.net/files/meteor-shower/cal2026.pdf
 * (sha256 fde5388889ebda9fe13436d793da5e9935ae46b99edf20e0b19f7fe32ce1ed9f):
 * Table 5 (details "correct according to the best information available in June 2025") for the activity period, the
 * solar longitude of the maximum (equinox J2000.0), the radiant at the maximum, the entry velocity V∞, the population
 * index r and the ZHR; and Table 6 (radiant positions through the year) for the daily drift, which is the least-squares
 * slope of the tabulated α and δ (the Draconids, with one tabulated position, and the Ursids, with two, are given the
 * slope those positions allow). Radiant places are taken as J2000. The list is hand-entered here; showers with a ZHR
 * below 5, a variable ZHR, an activity of one night or no observable radiant are not included.
 *
 * @property code The IAU three-letter code, e.g. "PER".
 * @property iauNumber The IAU Meteor Data Center number, e.g. 7.
 * @property name The English name, e.g. "Perseids".
 * @property activityStartMonth Month of the first day of the activity period (1…12).
 * @property activityStartDay Day of the first day of the activity period.
 * @property activityEndMonth Month of the last day of the activity period.
 * @property activityEndDay Day of the last day of the activity period.
 * @property peakSolarLongitude Solar longitude of the maximum in degrees (equinox J2000.0); fixes the date every year.
 * @property zhr The zenithal hourly rate at the maximum.
 * @property radiant The radiant (J2000) at the maximum.
 * @property radiantDriftRightAscension Daily drift of the radiant's right ascension in degrees per day.
 * @property radiantDriftDeclination Daily drift of the radiant's declination in degrees per day.
 * @property velocity The atmospheric entry velocity V∞ in km/s.
 * @property populationIndex The population index r.
 */
data class MeteorShower(
    val code: String,
    val iauNumber: Int,
    val name: String,
    val activityStartMonth: Int,
    val activityStartDay: Int,
    val activityEndMonth: Int,
    val activityEndDay: Int,
    val peakSolarLongitude: Double,
    val zhr: Int,
    val radiant: EquatorialCoordinate,
    val radiantDriftRightAscension: Double,
    val radiantDriftDeclination: Double,
    val velocity: Double,
    val populationIndex: Double,
) {
    /**
     * The instant of the maximum nearest to [date] (Swift `peak(near:)`): when the Sun's apparent ecliptic longitude
     * (equinox J2000.0) equals [peakSolarLongitude]. The result is within half a year of [date]; the cost is a
     * handful of solar positions.
     */
    fun peak(date: Instant): Instant = EventSearch.instant(peakUT(EventSearch.ut(date)))

    /**
     * The radiant (J2000) on [date] (Swift `radiant(at:)`): the radiant at the maximum of the nearest return moved by
     * the daily drift, for at most the length of the activity period on either side of the maximum.
     */
    fun radiant(date: Instant): EquatorialCoordinate {
        val ut = EventSearch.ut(date)
        val peakUT = peakUT(ut)
        val interval = activityInterval(peakUT)
        val days = AstroMath.clamp(ut - peakUT, interval.first - peakUT, interval.second - peakUT)
        return EquatorialCoordinate(
            radiant.rightAscension + radiantDriftRightAscension * days,
            radiant.declination + radiantDriftDeclination * days,
        )
    }

    /**
     * The activity period around the maximum at [peak] (Swift `activityPeriod(aroundPeak:)`), from 00:00 UT of the
     * first day to the end of the last.
     */
    fun activityPeriod(peak: Instant): DateInterval {
        val interval = activityInterval(EventSearch.ut(peak))
        return DateInterval(EventSearch.instant(interval.first), EventSearch.instant(interval.second))
    }

    /** `true` when [date] lies in the activity period around the nearest maximum (Swift `isActive(at:)`). */
    fun isActive(date: Instant): Boolean {
        val ut = EventSearch.ut(date)
        val interval = activityInterval(peakUT(ut))
        return ut >= interval.first && ut <= interval.second
    }

    internal fun peakUT(near: Double): Double {
        val longitude = EventSearch.sunLongitudeJ2000(JulianDate(near))
        val delta = AstroMath.normalizeSignedDegrees(peakSolarLongitude - longitude)
        return EventSearch.solveSolarLongitude(
            peakSolarLongitude, near + delta / EventSearch.meanSolarRate,
        ) { EventSearch.sunLongitudeJ2000(it) }
    }

    /** The first and last UT Julian Date of the activity period that contains the maximum at [peakUT]. */
    internal fun activityInterval(peakUT: Double): Pair<Double, Double> {
        if (!peakUT.isFinite()) return Pair(Double.NaN, Double.NaN)
        val year = floor(JulianDate.decimalYear(peakUT)).toInt()
        var start = Double.NEGATIVE_INFINITY
        var end = Double.POSITIVE_INFINITY
        for (candidate in year - 1..year + 1) {
            val first = JulianDate.julianDay(candidate, activityStartMonth, activityStartDay)
            if (first <= peakUT && first > start) start = first
            val last = JulianDate.julianDay(candidate, activityEndMonth, activityEndDay) + 1
            if (last >= peakUT && last < end) end = last
        }
        return Pair(start, end)
    }

    companion object {
        /** Every shower of the list, in order of the maximum through the calendar year. */
        val all: List<MeteorShower> = listOf(
            MeteorShower(
                "QUA", 10, "Quadrantids", 12, 28, 1, 12, 283.15, 80,
                EquatorialCoordinate(230.0, 49.0), 0.68, -0.19, 41.0, 2.1,
            ),
            MeteorShower(
                "ACE", 102, "α-Centaurids", 1, 31, 2, 20, 319.4, 6,
                EquatorialCoordinate(211.0, -58.0), 1.21, -0.29, 58.0, 2.0,
            ),
            MeteorShower(
                "LYR", 6, "April Lyrids", 4, 14, 4, 30, 32.32, 18,
                EquatorialCoordinate(271.0, 34.0), 1.06, 0.0, 49.0, 2.1,
            ),
            MeteorShower(
                "ETA", 31, "η-Aquariids", 4, 19, 5, 28, 45.5, 50,
                EquatorialCoordinate(338.0, -1.0), 0.85, 0.40, 66.0, 2.4,
            ),
            MeteorShower(
                "ARI", 171, "Daytime Arietids", 5, 14, 6, 24, 76.7, 30,
                EquatorialCoordinate(43.0, 24.0), 1.0, 0.0, 38.0, 2.8,
            ),
            MeteorShower(
                "SDA", 5, "Southern δ-Aquariids", 7, 12, 8, 23, 128.0, 25,
                EquatorialCoordinate(340.0, -16.0), 0.75, 0.21, 41.0, 2.5,
            ),
            MeteorShower(
                "CAP", 1, "α-Capricornids", 7, 3, 8, 15, 128.0, 5,
                EquatorialCoordinate(307.0, -10.0), 0.91, 0.27, 23.0, 2.5,
            ),
            MeteorShower(
                "PER", 7, "Perseids", 7, 17, 8, 24, 140.0, 100,
                EquatorialCoordinate(48.0, 58.0), 1.42, 0.20, 59.0, 2.2,
            ),
            MeteorShower(
                "AUR", 206, "Aurigids", 8, 28, 9, 5, 158.6, 6,
                EquatorialCoordinate(91.0, 39.0), 1.05, -0.05, 66.0, 2.5,
            ),
            MeteorShower(
                "SPE", 208, "September ε-Perseids", 9, 5, 9, 21, 166.7, 8,
                EquatorialCoordinate(48.0, 40.0), 1.06, 0.06, 64.0, 2.5,
            ),
            MeteorShower(
                "DRA", 9, "Draconids", 10, 6, 10, 10, 195.4, 5,
                EquatorialCoordinate(262.0, 54.0), 0.0, 0.0, 20.0, 2.6,
            ),
            MeteorShower(
                "ORI", 8, "Orionids", 10, 2, 11, 7, 208.0, 20,
                EquatorialCoordinate(95.0, 16.0), 0.65, 0.08, 66.0, 2.5,
            ),
            MeteorShower(
                "STA", 2, "Southern Taurids", 9, 20, 11, 20, 223.0, 7,
                EquatorialCoordinate(52.0, 15.0), 0.76, 0.19, 27.0, 2.3,
            ),
            MeteorShower(
                "NTA", 17, "Northern Taurids", 10, 20, 12, 10, 230.0, 5,
                EquatorialCoordinate(58.0, 22.0), 0.87, 0.16, 29.0, 2.3,
            ),
            MeteorShower(
                "LEO", 13, "Leonids", 11, 6, 11, 30, 235.27, 15,
                EquatorialCoordinate(152.0, 22.0), 0.60, -0.26, 71.0, 2.5,
            ),
            MeteorShower(
                "HYD", 16, "σ-Hydrids", 12, 3, 12, 20, 257.0, 7,
                EquatorialCoordinate(125.0, 2.0), 0.80, -0.20, 58.0, 3.0,
            ),
            MeteorShower(
                "GEM", 4, "Geminids", 12, 4, 12, 20, 262.2, 150,
                EquatorialCoordinate(112.0, 33.0), 1.00, -0.06, 35.0, 2.6,
            ),
            MeteorShower(
                "URS", 15, "Ursids", 12, 17, 12, 26, 270.7, 10,
                EquatorialCoordinate(217.0, 76.0), 0.0, -0.40, 33.0, 2.8,
            ),
        )

        /** The shower with the IAU [code] (case-insensitive), or `null`. */
        fun withCode(code: String): MeteorShower? = all.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

/**
 * The maximum of one shower in one year.
 *
 * @property shower The shower.
 * @property activityStart The first instant of the activity period of this return (00:00 UT of the first day).
 * @property activityEnd The last instant of the activity period (the end of the last day, UT).
 * @property moonIlluminatedFraction The illuminated fraction of the Moon at the maximum, [0, 1]; a bright Moon hides
 * the fainter meteors.
 * @property radiant The radiant (J2000) at the maximum: aim at it with [AimTarget.Radiant] or
 * [MeteorShower.radiant] for another date.
 */
data class MeteorShowerPeak(
    val shower: MeteorShower,
    val activityStart: Instant,
    val activityEnd: Instant,
    val moonIlluminatedFraction: Double,
    val radiant: EquatorialCoordinate,
)

/**
 * Finds the maxima of the [MeteorShower] list in an interval of time.
 *
 * Cost: a few solar positions per shower and year.
 */
object MeteorShowerFinder {
    /** The maxima from [from] to [to] (both inclusive), as events without visibility, in time order. */
    fun events(from: Instant, to: Instant): List<AstronomicalEvent> {
        if (!EventSearch.isUsable(from, to)) return emptyList()
        val fromUT = EventSearch.ut(from)
        val toUT = EventSearch.ut(EventSearch.limitedEnd(from, to))
        val found = ArrayList<AstronomicalEvent>()
        for (shower in MeteorShower.all) {
            var peak = shower.peakUT(fromUT)
            while (peak < fromUT) {
                peak = shower.peakUT(peak + EventSearch.tropicalYearDays)
            }
            while (peak <= toUT) {
                found.add(event(shower, peak))
                peak = shower.peakUT(peak + EventSearch.tropicalYearDays)
            }
        }
        return found.sortedBy { it.date }
    }

    private fun event(shower: MeteorShower, peakUT: Double): AstronomicalEvent {
        val jd = JulianDate(peakUT)
        val interval = shower.activityInterval(peakUT)
        val peak = MeteorShowerPeak(
            shower = shower,
            activityStart = EventSearch.instant(interval.first),
            activityEnd = EventSearch.instant(interval.second),
            moonIlluminatedFraction = Ephemeris.state(SolarSystemBody.MOON, jd).illuminatedFraction ?: 0.0,
            radiant = shower.radiant,
        )
        return AstronomicalEvent(jd.date, EventDetails.MeteorShower(peak), null)
    }
}
