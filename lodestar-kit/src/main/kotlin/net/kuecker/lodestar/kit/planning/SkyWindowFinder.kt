package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.catalog.*
import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.horizon.*
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil

/**
 * A patch of sky: an azimuth range and an altitude range, as a photographer frames "over that ridge" or "in that
 * gap between the trees".
 *
 * Altitudes are **apparent** (refracted), like the skyline of a [HorizonProfile] and what a camera sees. The ends of
 * both ranges belong to the window. The constructor is private; use [create], [around] or [skylinePoint].
 *
 * @property azimuth The azimuths of the window, from north through east.
 * @property minimumAltitude The lower edge in degrees, [-90, 90].
 * @property maximumAltitude The upper edge in degrees, [minimumAltitude, 90].
 */
@ConsistentCopyVisibility
data class SkyWindow private constructor(
    val azimuth: AzimuthRange,
    val minimumAltitude: Double,
    val maximumAltitude: Double,
) {
    companion object {
        /** The widest tolerance [around] and [skylinePoint] accept, in degrees: half the circle. */
        const val maximumAzimuthTolerance: Double = 180.0

        /** The azimuth tolerance [skylinePoint] uses when none is given, in degrees. */
        const val defaultSkylineTolerance: Double = 3.0

        /**
         * The window with the given ranges (Swift `init?(azimuth:minimumAltitude:maximumAltitude:)`); `null`
         * unless both altitudes are finite and `-90 <= minimumAltitude <= maximumAltitude <= 90`.
         */
        fun create(azimuth: AzimuthRange, minimumAltitude: Double, maximumAltitude: Double): SkyWindow? {
            if (!(minimumAltitude.isFinite() && maximumAltitude.isFinite())) return null
            if (!(minimumAltitude >= -90.0 && maximumAltitude <= 90.0 && minimumAltitude <= maximumAltitude)) return null
            return SkyWindow(azimuth, minimumAltitude, maximumAltitude)
        }

        /**
         * The window centred on [azimuth] and extending [tolerance] degrees to either side (the whole circle at
         * 180°); `null` when the azimuth or tolerance is not finite or the tolerance is outside
         * 0…[maximumAzimuthTolerance], or for altitudes [create] rejects.
         */
        fun around(
            azimuth: Double,
            tolerance: Double,
            minimumAltitude: Double,
            maximumAltitude: Double,
        ): SkyWindow? {
            if (!(azimuth.isFinite() && tolerance.isFinite())) return null
            if (!(tolerance >= 0.0 && tolerance <= maximumAzimuthTolerance)) return null
            return create(AzimuthRange(azimuth - tolerance, 2 * tolerance), minimumAltitude, maximumAltitude)
        }

        /**
         * The window just above the skyline at [azimuth]: [azimuthTolerance] degrees to either side, and from
         * `altitudeAbove.start` to `altitudeAbove.endInclusive` degrees above the skyline altitude at [azimuth]
         * (limited to −90…90°). `null` when a number is not finite, the tolerance is outside
         * 0…[maximumAzimuthTolerance] or [altitudeAbove] is empty or outside −90…90°.
         *
         * @param azimuth The azimuth of the gap or ridge in degrees (any angle).
         * @param horizon The recorded skyline.
         * @param azimuthTolerance Half the width of the window in degrees.
         * @param altitudeAbove The edges of the window above the skyline altitude, in degrees; 0…10 by default.
         */
        fun skylinePoint(
            azimuth: Double,
            horizon: HorizonProfile,
            azimuthTolerance: Double = defaultSkylineTolerance,
            altitudeAbove: ClosedFloatingPointRange<Double> = 0.0..10.0,
        ): SkyWindow? {
            if (!azimuth.isFinite() || altitudeAbove.isEmpty()) return null
            if (!(altitudeAbove.start.isFinite() && altitudeAbove.endInclusive.isFinite())) return null
            if (altitudeAbove.start < -90.0 || altitudeAbove.endInclusive > 90.0) return null
            val skyline = horizon.altitude(azimuth)
            return around(
                azimuth,
                azimuthTolerance,
                AstroMath.clamp(skyline + altitudeAbove.start, -90.0, 90.0),
                AstroMath.clamp(skyline + altitudeAbove.endInclusive, -90.0, 90.0),
            )
        }
    }
}

/**
 * What [SkyWindowFinder] follows across the sky (Swift enum with payloads).
 */
sealed interface SkyWindowTarget {
    /** The Sun, the Moon or a planet. */
    data class Body(val body: SolarSystemBody) : SkyWindowTarget

    /** A fixed place: a J2000 position with an optional proper motion. */
    data class Fixed(val coordinate: EquatorialCoordinate, val properMotion: ProperMotion? = null) : SkyWindowTarget

    /** The Galactic Centre, the core of the Milky Way (see [MilkyWayCoreVisibility.galacticCentre]). */
    data object MilkyWayCore : SkyWindowTarget

    /** Any object of the catalog, a custom target, a comet or an asteroid. */
    data class CatalogObject(val obj: CelestialObject) : SkyWindowTarget
}

/**
 * How dark the sky must be for a match, judged by the altitude of the Sun's centre.
 *
 * @property rawValue The stable name of the case.
 * @property sunAltitudeLimit The Sun must be below this altitude in degrees; `null` for no condition.
 */
enum class SkyWindowDarkness(val rawValue: String, val sunAltitudeLimit: Double?) {
    /** The Sun is more than 18° below the horizon. */
    ASTRONOMICAL("astronomical", TwilightCalculator.astronomicalAltitude),

    /** The Sun is more than 12° below the horizon. */
    NAUTICAL("nautical", TwilightCalculator.nauticalAltitude),

    /** The Sun is more than 6° below the horizon: any darkness at all, twilight included. */
    CIVIL("civil", TwilightCalculator.civilAltitude),

    /** No condition on the Sun: for the Sun and the Moon themselves, and for daytime alignments. */
    ANY_TIME("anyTime", null),
    ;

    companion object {
        /** The case whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): SkyWindowDarkness? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * What a match may ask of the Moon.
 */
sealed interface SkyWindowMoonRule {
    /** The Moon does not matter. */
    data object Unrestricted : SkyWindowMoonRule

    /** The Moon's upper limb is below the horizon (the moment [NightSummary.moon] counts as down). */
    data object BelowHorizon : SkyWindowMoonRule

    /**
     * The sunlit fraction of the Moon's disc is at most [fraction], [0, 1], whether or not the Moon is up. This
     * judges the phase only; use [BelowHorizon] to ask for the Moon to be down.
     */
    data class IlluminatedAtMost(val fraction: Double) : SkyWindowMoonRule
}

/**
 * Why a search could not run (Swift `SkyWindowError`).
 */
sealed class SkyWindowError(message: String) : Exception(message) {
    /** [from] or [to] is not a usable date, or [to] precedes [from]. */
    data class InvalidRange(val from: Instant, val to: Instant) :
        SkyWindowError("The range from $from to $to is not usable.")

    /** The range touches more than [limit] nights ([SkyWindowFinder.maximumNightCount]). */
    data class RangeTooLong(val limit: Int) : SkyWindowError("The range covers more than $limit nights.")

    /** The target's position is not a finite place on the sky. */
    data object InvalidTarget : SkyWindowError("The target has no usable position.")

    /** The illuminated fraction of the Moon rule is not a number in [0, 1]. */
    data class InvalidMoonRule(val fraction: Double) :
        SkyWindowError("The illuminated fraction $fraction is not in [0, 1].")
}

/**
 * The Moon at the middle of a match.
 *
 * @property altitude The apparent altitude of the Moon's centre in degrees.
 * @property illuminatedFraction The sunlit fraction of the disc, [0, 1].
 * @property isUp Whether the Moon's upper limb is above the horizon (−0.5667°, as `RiseSetCalculator`).
 */
data class SkyWindowMoonState(val altitude: Double, val illuminatedFraction: Double, val isUp: Boolean)

/**
 * One stretch of one night in which the target stands in the window.
 *
 * @property night The night (local noon to local noon) the match belongs to.
 * @property interval The first and last moment of the stretch, good to [SkyWindowFinder.refinementTolerance]
 * seconds, inside the dark time and the search range. A match never crosses local noon, where nights are cut.
 * @property azimuth The target's azimuth at [midpoint] in degrees.
 * @property altitude The target's apparent altitude at [midpoint] in degrees.
 * @property moon The Moon at [midpoint].
 * @property isAboveSkyline Whether the target's apparent centre is at or above the skyline at [midpoint]
 * (always `true` over the flat horizon unless the target is below it).
 */
data class SkyWindowMatch(
    val night: DateInterval,
    val interval: DateInterval,
    val azimuth: Double,
    val altitude: Double,
    val moon: SkyWindowMoonState,
    val isAboveSkyline: Boolean,
) {
    /** The instant half way between the start and the end of [interval]. */
    val midpoint: Instant get() = interval.start.addingTimeInterval(interval.duration / 2)
}

/**
 * Finds when a target stands over a patch of sky: "when does the Milky Way core stand over that ridge?".
 *
 * For every night (local noon to local noon, as [NightPlanner]) the search samples the target every
 * [sampleInterval] seconds and keeps the moments at which it is inside the [SkyWindow], the Sun is dark enough and
 * the Moon rule holds; each entry and exit is then refined by bisection to [refinementTolerance] seconds. A
 * crossing shorter than the sampling interval that falls between two samples can be missed; the narrowest window
 * that is certain to be found at the fastest azimuth rate is a few degrees wide.
 *
 * Positions come from the horizon module's sky tracks (frames shared per six hours, the Moon and Sun interpolated
 * from ephemeris nodes), geometric and then refracted with the observer's atmosphere. Cost: a night is 288 samples,
 * of which only those with the Sun low enough evaluate the target (about 55 000 horizontal transforms for a year of
 * astronomical darkness at mid-latitudes; 105 000 with [SkyWindowDarkness.ANY_TIME]), plus about 6 evaluations per
 * entry or exit. A fixed target or a planet takes a few hundred milliseconds for a year, a Moon rule about the
 * same again, and a comet or asteroid, whose orbit is propagated for every sample, a few seconds. Limits: a range
 * touches at most [maximumNightCount] nights and the result holds at most [maximumMatchCount] matches (the
 * earliest). A call shares no state and may run on any thread.
 */
object SkyWindowFinder {
    /** The longest gap between samples, in seconds. */
    const val sampleInterval: Double = 300.0

    /** The resolution of the entry and exit times, in seconds. */
    const val refinementTolerance: Double = 10.0

    /** The most nights a range may touch. */
    const val maximumNightCount: Int = 400

    /** The most matches returned; a result of this size may have been cut short. */
    const val maximumMatchCount: Int = 200

    /**
     * The stretches in which [target] stands in [window] while the sky is dark enough and the Moon rule holds,
     * in time order.
     *
     * @param target What to follow.
     * @param window The patch of sky.
     * @param observer The place of observation; its atmosphere refracts the altitudes.
     * @param horizon The skyline used to set [SkyWindowMatch.isAboveSkyline]; `null` is the flat horizon.
     * @param from The start of the search range.
     * @param to The end of the search range.
     * @param darkness How low the Sun must be; [SkyWindowDarkness.ASTRONOMICAL] by default.
     * @param moon What the Moon must do; no condition by default.
     * @param timeZone The time zone that defines the nights.
     * @throws SkyWindowError.InvalidRange for an unusable range, [SkyWindowError.RangeTooLong] beyond
     * [maximumNightCount] nights, [SkyWindowError.InvalidTarget] for a target without a finite position and
     * [SkyWindowError.InvalidMoonRule] for a fraction outside [0, 1].
     */
    fun matches(
        target: SkyWindowTarget,
        window: SkyWindow,
        observer: Observer,
        horizon: HorizonProfile? = null,
        from: Instant,
        to: Instant,
        darkness: SkyWindowDarkness = SkyWindowDarkness.ASTRONOMICAL,
        moon: SkyWindowMoonRule = SkyWindowMoonRule.Unrestricted,
        timeZone: ZoneId,
    ): List<SkyWindowMatch> {
        val rule = moon
        if (rule is SkyWindowMoonRule.IlluminatedAtMost && !(rule.fraction >= 0.0 && rule.fraction <= 1.0)) {
            throw SkyWindowError.InvalidMoonRule(rule.fraction)
        }
        val start = from.timeIntervalSinceReferenceDate
        val end = to.timeIntervalSinceReferenceDate
        if (!(start.isFinite() && end.isFinite() && end >= start)) throw SkyWindowError.InvalidRange(from, to)
        val nights = nightsTouching(from, to, timeZone)
        val search = SkyWindowSearch(target, window, observer, horizon, darkness, rule)
        val found = ArrayList<SkyWindowMatch>()
        for (night in nights) {
            val rangeStart = maxOf(night.start.timeIntervalSinceReferenceDate, start)
            val rangeEnd = minOf(night.end.timeIntervalSinceReferenceDate, end)
            if (!(rangeEnd > rangeStart)) continue
            search.scan(night, rangeStart, rangeEnd, found)
            if (found.size >= maximumMatchCount) break
        }
        return found
    }

    /** The nights, local noon to local noon, that touch [from]…[to] (at least one second long). */
    private fun nightsTouching(from: Instant, to: Instant, timeZone: ZoneId): List<DateInterval> {
        val nights = ArrayList<DateInterval>()
        var night = TwilightCalculator.nightInterval(from, timeZone)
        if (from < night.start) {
            night = TwilightCalculator.nightInterval(night.start.addingTimeInterval(-86_400.0), timeZone)
        }
        while (night.start < to) {
            if (nights.size == maximumNightCount) throw SkyWindowError.RangeTooLong(maximumNightCount)
            nights.add(night)
            night = TwilightCalculator.nightInterval(night.end, timeZone)
        }
        return nights
    }

    /** The state of one search: the tracks of the target, the Sun and the Moon, and the test every sample goes through. */
    private class SkyWindowSearch(
        private val target: SkyWindowTarget,
        private val window: SkyWindow,
        private val observer: Observer,
        horizon: HorizonProfile?,
        private val darkness: SkyWindowDarkness,
        private val rule: SkyWindowMoonRule,
    ) {
        private val frames = HorizonSkyFrames(observer)
        private val skyline = HorizonVisibility(horizon ?: HorizonProfile.flat)
        private val track: HorizonSkyTrack = makeTrack()
        private val sun: HorizonSkyTrack = HorizonBodyTrack(frames, SolarSystemBody.SUN)
        private val moonTrack: HorizonSkyTrack = HorizonBodyTrack(frames, SolarSystemBody.MOON)

        private fun makeTrack(): HorizonSkyTrack = when (target) {
            is SkyWindowTarget.Body -> HorizonBodyTrack(frames, target.body)
            is SkyWindowTarget.Fixed -> fixedTrack(target.coordinate, target.properMotion)
            SkyWindowTarget.MilkyWayCore -> fixedTrack(MilkyWayCoreVisibility.galacticCentre, null)
            is SkyWindowTarget.CatalogObject -> when (val position = target.obj.position) {
                is ObjectPosition.Fixed -> fixedTrack(position.coordinate, position.properMotion)
                is ObjectPosition.SolarSystem -> HorizonBodyTrack(frames, position.body)
                is ObjectPosition.CelestialPole, is ObjectPosition.SmallBodyOrbit ->
                    HorizonFunctionTrack { SkyFrame(it, observer).horizontal(target.obj, false) }
            }
        }

        private fun fixedTrack(coordinate: EquatorialCoordinate, properMotion: ProperMotion?): HorizonSkyTrack {
            if (!coordinate.unitVector.isFinite) throw SkyWindowError.InvalidTarget
            return HorizonFixedTrack(frames, coordinate, properMotion)
        }

        /** Whether the target is in the window, the sky dark enough and the Moon rule met at [time] (seconds since the reference date). */
        fun isMatch(time: Double): Boolean {
            val limit = darkness.sunAltitudeLimit
            if (limit != null) {
                sun.evaluate(time, false)
                if (!(sun.altitude < limit)) return false
            }
            track.evaluate(time, true)
            val azimuth = track.azimuth
            if (!(azimuth.isFinite() && window.azimuth.contains(azimuth))) return false
            val altitude = apparentAltitude()
            if (!(altitude >= window.minimumAltitude && altitude <= window.maximumAltitude)) return false
            return when (rule) {
                SkyWindowMoonRule.Unrestricted -> true
                SkyWindowMoonRule.BelowHorizon -> !isMoonUp(time)
                is SkyWindowMoonRule.IlluminatedAtMost ->
                    MoonPhaseCalculator.info(instantFromTimeIntervalSinceReferenceDate(time)).illuminatedFraction <= rule.fraction
            }
        }

        private fun apparentAltitude(): Double = Refraction.apparentAltitude(track.altitude, observer.atmosphere)

        private fun isMoonUp(time: Double): Boolean {
            moonTrack.evaluate(time, false)
            return moonTrack.altitude + moonTrack.limbAllowance >= RiseSetCalculator.standardHorizonAltitude
        }

        /**
         * The time of the edge of a stretch, within [refinementTolerance]: [inside] is a sample that matches,
         * [outside] the neighbouring one that does not; returns the matching time closest to the edge.
         */
        private fun edge(inside: Double, outside: Double): Double {
            var matching = inside
            var other = outside
            while (abs(other - matching) > refinementTolerance) {
                val middle = (matching + other) / 2
                if (isMatch(middle)) matching = middle else other = middle
            }
            return matching
        }

        /** Adds the matches of one night's [rangeStart]…[rangeEnd] (seconds since the reference date) to [found]. */
        fun scan(night: DateInterval, rangeStart: Double, rangeEnd: Double, found: MutableList<SkyWindowMatch>) {
            val duration = rangeEnd - rangeStart
            val gapCount = ceil(duration / sampleInterval).toInt()
            val step = duration / gapCount.toDouble()
            fun timeAt(index: Int): Double = if (index == gapCount) rangeEnd else rangeStart + index.toDouble() * step
            var first = -1
            for (index in 0..gapCount) {
                val inside = isMatch(timeAt(index))
                if (inside && first < 0) first = index
                if (inside && index < gapCount) continue
                if (first < 0) continue
                val last = if (inside) index else index - 1
                val entry = if (first == 0) timeAt(0) else edge(timeAt(first), timeAt(first - 1))
                val exit = if (last == gapCount) timeAt(gapCount) else edge(timeAt(last), timeAt(last + 1))
                found.add(match(night, entry, exit))
                if (found.size >= maximumMatchCount) return
                first = -1
            }
        }

        private fun match(night: DateInterval, entry: Double, exit: Double): SkyWindowMatch {
            val middle = entry + (exit - entry) / 2
            track.evaluate(middle, true)
            val altitude = apparentAltitude()
            val azimuth = track.azimuth
            moonTrack.evaluate(middle, false)
            val moonAltitude = Refraction.apparentAltitude(moonTrack.altitude, observer.atmosphere)
            val moon = SkyWindowMoonState(
                altitude = moonAltitude,
                illuminatedFraction =
                    MoonPhaseCalculator.info(instantFromTimeIntervalSinceReferenceDate(middle)).illuminatedFraction,
                isUp = moonTrack.altitude + moonTrack.limbAllowance >= RiseSetCalculator.standardHorizonAltitude,
            )
            return SkyWindowMatch(
                night = night,
                interval = DateInterval(
                    instantFromTimeIntervalSinceReferenceDate(entry),
                    instantFromTimeIntervalSinceReferenceDate(exit),
                ),
                azimuth = azimuth,
                altitude = altitude,
                moon = moon,
                isAboveSkyline = skyline.margin(altitude, azimuth, true) >= 0,
            )
        }
    }
}
