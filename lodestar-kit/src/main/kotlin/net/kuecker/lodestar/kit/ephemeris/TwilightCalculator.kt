package net.kuecker.lodestar.kit.ephemeris

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Sunset, the three twilights and sunrise around one night at a place.
 *
 * The "night starting on" a local date runs from local noon of that date to local noon of the next
 * day (23 or 25 hours across a daylight-saving change). Events are `null` when the Sun does not
 * cross the corresponding altitude in that window, as in the polar summer or winter.
 *
 * @property sunset The Sun's centre passes −0.8333° (the upper limb touches the horizon, with refraction).
 * @property civilDusk The Sun reaches −6° in the evening.
 * @property nauticalDusk The Sun reaches −12° in the evening.
 * @property astronomicalDusk The Sun reaches −18° in the evening: the sky is fully dark from here.
 * @property astronomicalDawn The Sun climbs back through −18° in the morning.
 * @property nauticalDawn The Sun climbs back through −12°.
 * @property civilDawn The Sun climbs back through −6°.
 * @property sunrise The Sun's centre climbs back through −0.8333°.
 * @property interval Local noon of the starting date to local noon of the next day.
 * @property solarMidnight The instant of the Sun's lowest altitude in the interval (solar midnight).
 * @property sunAltitudeAtMidnight The Sun's geometric altitude at [solarMidnight], in degrees.
 */
data class NightTwilight(
    val sunset: Instant?,
    val civilDusk: Instant?,
    val nauticalDusk: Instant?,
    val astronomicalDusk: Instant?,
    val astronomicalDawn: Instant?,
    val nauticalDawn: Instant?,
    val civilDawn: Instant?,
    val sunrise: Instant?,
    val interval: DateInterval,
    val solarMidnight: Instant,
    val sunAltitudeAtMidnight: Double,
) {
    /**
     * The period between astronomical dusk and dawn, when the Sun is below −18°.
     *
     * `null` when the Sun does not get that low (white nights). When it stays below −18° through
     * the whole interval (polar winter) this is the whole [interval].
     */
    val astronomicalNight: DateInterval?
        get() {
            val dusk = astronomicalDusk
            val dawn = astronomicalDawn
            return when {
                dusk != null && dawn != null && dawn > dusk -> DateInterval(dusk, dawn)
                dusk != null && dawn == null -> DateInterval(dusk, interval.end)
                dusk == null && dawn != null -> DateInterval(interval.start, dawn)
                else ->
                    if (sunAltitudeAtMidnight < TwilightCalculator.astronomicalAltitude &&
                        astronomicalDusk == null && astronomicalDawn == null
                    ) {
                        interval
                    } else {
                        null
                    }
            }
        }
}

/** Computes sunset, twilight and sunrise times. */
object TwilightCalculator {
    /** Sun altitude at the end of civil twilight, in degrees. */
    const val civilAltitude: Double = -6.0

    /** Sun altitude at the end of nautical twilight, in degrees. */
    const val nauticalAltitude: Double = -12.0

    /** Sun altitude at the end of astronomical twilight, in degrees. */
    const val astronomicalAltitude: Double = -18.0

    /**
     * The twilight times of the night that starts on the local calendar day containing [localDate].
     *
     * @param localDate Any instant on the desired date in [timeZone].
     * @param observer The place of observation.
     * @param timeZone The time zone that defines the local date and noon. The Gregorian calendar is used.
     */
    fun night(localDate: Instant, observer: Observer, timeZone: ZoneId): NightTwilight {
        val interval = nightInterval(localDate, timeZone)
        val evaluate = RiseSetCalculator.evaluator(SolarSystemBody.SUN, observer)
        val profile = RiseSetProfile(interval, RiseSetCalculator.maximumSampleInterval, evaluate)
        val lowest = profile.lowestPoint(RiseSetCalculator.defaultTolerance, evaluate)
        val midnight = instantFromTimeIntervalSinceReferenceDate(lowest.time)

        // The evening (setting) and morning (rising) crossings of a threshold on either side of midnight.
        fun crossings(threshold: Double): EveningMorning {
            val found = profile.crossings(threshold, RiseSetCalculator.defaultTolerance, evaluate)
            return EveningMorning(
                found.lastOrNull { it.kind == AltitudeCrossing.Kind.SETTING && it.time <= midnight }?.time,
                found.firstOrNull { it.kind == AltitudeCrossing.Kind.RISING && it.time >= midnight }?.time,
            )
        }
        val horizon = crossings(RiseSetCalculator.sunHorizonAltitude)
        val civil = crossings(civilAltitude)
        val nautical = crossings(nauticalAltitude)
        val astronomical = crossings(astronomicalAltitude)
        return NightTwilight(
            sunset = horizon.evening,
            civilDusk = civil.evening,
            nauticalDusk = nautical.evening,
            astronomicalDusk = astronomical.evening,
            astronomicalDawn = astronomical.morning,
            nauticalDawn = nautical.morning,
            civilDawn = civil.morning,
            sunrise = horizon.morning,
            interval = interval,
            solarMidnight = midnight,
            sunAltitudeAtMidnight = lowest.altitude,
        )
    }

    /**
     * Local noon on the calendar day of [date] to local noon on the next day. A date outside the years
     * 1 to 4001 (`distantPast ... distantFuture`), or one that is not finite, is replaced by
     * the nearest limit (or the reference date), where the calendar arithmetic is well defined.
     *
     * Swift: `nightInterval(startingOn:timeZone:)`.
     */
    internal fun nightInterval(date: Instant, timeZone: ZoneId): DateInterval {
        val seconds = date.timeIntervalSinceReferenceDate
        val usable = if (seconds.isFinite()) {
            // Swift min(max(date, .distantPast), .distantFuture)
            val atLeastPast = if (distantPast >= date) distantPast else date
            if (distantFuture < atLeastPast) distantFuture else atLeastPast
        } else {
            instantFromTimeIntervalSinceReferenceDate(0.0)
        }
        val dayStart = usable.atZone(timeZone).toLocalDate().atStartOfDay(timeZone)
        val noon = dayStart.with(LocalTime.NOON)
        val nextNoon = noon.plusDays(1)
        return DateInterval(noon.toInstant(), nextNoon.toInstant())
    }

    /** The evening and morning crossings of one threshold (Swift: the tuple `(evening:morning:)`). */
    private class EveningMorning(val evening: Instant?, val morning: Instant?)
}
