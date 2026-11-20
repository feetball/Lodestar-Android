package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.ephemeris.RiseSetCalculator
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.topocentricEquatorial
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant

/**
 * The transits of a target across the local meridian that bracket an instant.
 *
 * @property next The first transit after the instant, or `null` when none falls within [MeridianClock.searchHalfSpan]
 * of it: the Moon's transits are 24 h 50 min apart, so the one on the far side of a transit that has just passed can
 * lie beyond a day; a target that never transits (a celestial pole, a comet whose orbit cannot be evaluated) has none.
 * @property previous The last transit at or before the instant, with the same limits as [next].
 */
data class MeridianTransits(val next: Instant?, val previous: Instant?)

/**
 * How long a German equatorial mount can keep tracking before it must flip, and whether it must already.
 *
 * @property secondsUntilLimit Seconds of Universal Time until the hour angle reaches the mount's meridian limit at the
 * sidereal rate; negative once the limit is behind the mount (the time since it was passed, negated).
 */
data class MeridianFlipWindow(val secondsUntilLimit: Double) {
    /** `true` when the target has reached or passed the meridian limit: the mount has to flip. */
    val isPastLimit: Boolean get() = secondsUntilLimit <= 0.0
}

/**
 * The hour angle of a target and the time to its meridian transit, for equatorial mounts that flip at the meridian
 * (Swift `MeridianClock`).
 *
 * The hour angle is the angle between the local meridian and the target's hour circle, measured along the equator:
 * zero at the transit, negative in the east (the target has yet to transit), positive in the west (it has transited).
 * Everything here uses the apparent, topocentric place of date, the one the mount's own coordinates refer to.
 *
 * A transit is the instant the hour angle is zero: for a star it is also the instant of greatest altitude (the upper
 * culmination of `RiseSetCalculator`); for a body that moves in declination (the Moon above all, whose greatest
 * altitude can come minutes away from the transit) it is the hour angle that counts for a mount.
 *
 * Overloads: the Swift `transit(forJ2000:properMotion:around:observer:)`, `transit(for:around:observer:)` for a body and
 * `transit(for:around:observer:)` for a catalog object (declared next to `CelestialObject`) share one name here and
 * differ by the type of the first parameter.
 */
object MeridianClock {
    /** The transit search looks this far (one day, in seconds) before and after the instant. */
    const val searchHalfSpan: Double = 86_400.0

    /** The meridian limits [flipWindow] accepts, in degrees past the meridian; others are clamped into it. */
    val limitDegreesRange: ClosedFloatingPointRange<Double> = 0.0..30.0

    /** The rate at which the hour angle of a fixed point grows, in degrees per second of Universal Time. */
    const val siderealDegreesPerSecond: Double = 360.985_647_366_29 / JulianDate.secondsPerDay

    /**
     * The local hour angle of an apparent place, in hours, signed with west positive, in (−12, 12].
     *
     * Costs one [SkyFrame] (a few microseconds).
     *
     * @param apparent The place in the true equator and equinox of date (`SkyFrame.apparentPlace`,
     * `SkyFrame.topocentricEquatorial`, `apparentEquatorial(obj)`).
     * @return The hour angle, or NaN when [apparent] is not finite.
     */
    fun hourAngle(apparent: EquatorialCoordinate, date: Instant, observer: Observer): Double {
        if (!apparent.rightAscension.isFinite()) return Double.NaN
        return SkyFrame(date, observer).hourAngle(apparent.rightAscension) / 15.0
    }

    /**
     * The transits of a fixed object, found within [searchHalfSpan] of [date].
     *
     * Costs about 300 sky frames (a few milliseconds).
     *
     * @param position The J2000 catalog place of the object.
     * @param properMotion Its proper motion, if known.
     */
    fun transit(
        position: EquatorialCoordinate,
        properMotion: ProperMotion? = null,
        date: Instant,
        observer: Observer,
    ): MeridianTransits =
        transits(date) { instant ->
            val frame = SkyFrame(instant, observer)
            frame.hourAngle(frame.apparentPlace(position, properMotion).rightAscension)
        }

    /**
     * The transits of a solar-system [body], found within [searchHalfSpan] of [date].
     *
     * Costs about 300 ephemeris evaluations (up to a few tens of milliseconds for the Moon).
     */
    fun transit(body: SolarSystemBody, date: Instant, observer: Observer): MeridianTransits =
        transits(date) { instant ->
            val frame = SkyFrame(instant, observer)
            frame.hourAngle(frame.topocentricEquatorial(body).rightAscension)
        }

    /**
     * The time until a mount that may track [limitDegrees] past the meridian must flip, at the sidereal rate.
     *
     * @param hourAngle The hour angle of the target in hours, west positive (as [hourAngle] returns it); values outside
     * (−12, 12] are wrapped into it.
     * @param limitDegrees How far past the meridian the mount may track, in degrees of hour angle (a German equatorial
     * mount: 0 flips at the meridian, 10 allows 40 minutes of sidereal time); clamped to [limitDegreesRange].
     * @return `null` when an argument is not finite.
     */
    fun flipWindow(hourAngle: Double, limitDegrees: Double): MeridianFlipWindow? {
        if (!hourAngle.isFinite() || !limitDegrees.isFinite()) return null
        val limit = AstroMath.clamp(limitDegrees, limitDegreesRange.start, limitDegreesRange.endInclusive)
        val angle = AstroMath.normalizeSignedDegrees(hourAngle * 15.0)
        return MeridianFlipWindow((limit - angle) / siderealDegreesPerSecond)
    }

    /**
     * The transits within [searchHalfSpan] of [date] of a target whose hour angle in degrees at an instant is
     * [hourAngleDegrees] (NaN where it cannot be evaluated).
     *
     * The search is `RiseSetCalculator.upperCulminations` on cos(hour angle), which has exactly one maximum per transit
     * whatever the target does in declination.
     */
    internal fun transits(date: Instant, hourAngleDegrees: (Instant) -> Double): MeridianTransits {
        val window = DateInterval(date.addingTimeInterval(-searchHalfSpan), date.addingTimeInterval(searchHalfSpan))
        val times = RiseSetCalculator.upperCulminations({ AstroMath.cosDeg(hourAngleDegrees(it)) }, window).map { it.time }
        return MeridianTransits(next = times.firstOrNull { it > date }, previous = times.lastOrNull { it <= date })
    }
}
