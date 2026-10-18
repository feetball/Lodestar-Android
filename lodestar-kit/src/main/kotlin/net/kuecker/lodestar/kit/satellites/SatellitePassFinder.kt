package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A moment of a pass: where the satellite is in the sky.
 *
 * @property date The instant.
 * @property azimuth Azimuth from true north through east, [0, 360).
 * @property altitude Geometric altitude in degrees.
 * @property rangeKilometers Distance from the observer.
 * @property isSunlit Whether the satellite is in sunlight ([EarthShadow.sunlitThreshold]).
 * @property magnitude The apparent magnitude, if the satellite is lit and its intrinsic magnitude is known.
 */
data class SatellitePassPoint(
    val date: Instant,
    val azimuth: Double,
    val altitude: Double,
    val rangeKilometers: Double,
    val isSunlit: Boolean,
    val magnitude: Double?,
) {
    /** The point as a horizontal coordinate. */
    val horizontal: HorizontalCoordinate get() = HorizontalCoordinate(azimuth, altitude)

    /** The point as an ENU unit vector (x east, y north, z up). */
    val direction: Vector3 get() = horizontal.unitVector
}

/**
 * One pass of a satellite above the observer's minimum altitude.
 *
 * A pass that is already under way at the start of the search window, or still under way at its end, is cut
 * there: [rise] (or [set]) is then the window's edge and [isRiseCut] (or [isSetCut]) is `true`. The
 * [culmination] is the highest point of the part inside the window.
 *
 * @property rise Where the satellite reaches the minimum altitude.
 * @property culmination The highest point of the pass.
 * @property set Where the satellite drops below the minimum altitude.
 * @property visibleIntervals The parts of the pass in which the satellite is sunlit and the sky is dark
 * (chronological; usually zero or one).
 * @property brightest The brightest visible moment, if the intrinsic magnitude is known.
 * @property isRiseCut `true` when the pass began before the search window.
 * @property isSetCut `true` when the pass continues after the search window.
 */
data class SatellitePass(
    val rise: SatellitePassPoint,
    val culmination: SatellitePassPoint,
    val set: SatellitePassPoint,
    val visibleIntervals: List<DateInterval>,
    val brightest: SatellitePassPoint?,
    val isRiseCut: Boolean,
    val isSetCut: Boolean,
) {
    /** From [rise] to [set]. */
    val interval: DateInterval get() = DateInterval(rise.date, set.date)

    /** Seconds from [rise] to [set]. */
    val duration: Double get() = set.date.timeIntervalSince(rise.date)

    /** `true` when some part of the pass can be seen ([visibleIntervals] is not empty). */
    val isVisible: Boolean get() = visibleIntervals.isNotEmpty()

    /** The visible parts' first start and last end, or `null` for a pass that cannot be seen. */
    val visibleSpan: DateInterval?
        get() {
            val first = visibleIntervals.firstOrNull() ?: return null
            val last = visibleIntervals.last()
            return DateInterval(first.start, last.end)
        }
}

/**
 * Finds the passes of satellites over one observer in a time window.
 *
 * A *pass* is a stretch in which the satellite's geometric altitude is at least [minimumAltitude]; for each
 * the finder reports rise, culmination (highest point) and set, and the *visible* parts, in which the satellite
 * is also sunlit (at least half of the Sun's disc visible from it, see [EarthShadow]) while the Sun's
 * centre is lower than [darknessSunAltitude] for the observer.
 *
 * **Method.** The altitude is sampled with steps that adapt to the distance: a satellite that is `g` degrees
 * below the threshold at range `ρ` cannot reach it in less than `ρ·sin g / v` seconds (`v` is an upper bound of
 * its speed), so the step is that long, between 1 s and 15 minutes. Rise and set are bracketed by sampling every
 * 10 s inside a pass and refined by bisection to 0.01 s, the culmination by a golden-section search to
 * 0.05 s, the visible edges by sampling every 5 s (every 30 s for passes of an hour and more) and bisection to
 * 0.05 s, the brightest point from these samples refined by a golden-section search. No pass is missed (a pass shorter than a second at the very threshold
 * excepted).
 *
 * **Cost.** A low-Earth-orbit satellite over three days takes about 3 ms (a deep-space one up to 20 ms), i.e.
 * 100 satellites for three days about 0.4 s on a desktop and about a second on a phone. The window is limited to
 * [maximumWindowDays]. Creating the finder costs the Sun's hourly ephemeris for the window (about 0.5 ms for three
 * days); one finder serves any number of satellites and threads.
 *
 * **Accuracy.** The times are those of the SGP4 model (see [Sgp4Propagator]); against Skyfield, which uses
 * the same propagator, rise, culmination and set agree within a second.
 *
 * @property observer The observer.
 * @property interval The search window; clipped to [maximumWindowDays].
 * @property minimumAltitude The altitude in degrees that defines rise and set (default 10).
 * @property darknessSunAltitude The Sun altitude in degrees below which the sky counts as dark (default -6).
 */
class SatellitePassFinder(
    val observer: Observer,
    val interval: DateInterval,
    val minimumAltitude: Double = defaultMinimumAltitude,
    val darknessSunAltitude: Double = defaultDarknessSunAltitude,
) {
    private val startTime: Double = interval.start.timeIntervalSinceReferenceDate
    private val endTime: Double = min(
        interval.end.timeIntervalSinceReferenceDate,
        interval.start.timeIntervalSinceReferenceDate + maximumWindowDays * JulianDate.secondsPerDay,
    )
    private val threshold: Double =
        if (minimumAltitude.isFinite()) AstroMath.clamp(minimumAltitude, -5.0, 85.0) else defaultMinimumAltitude
    private val evaluator: SatelliteEvaluator =
        SatelliteEvaluator(observer, SatelliteSunTrack.create(startTime, max(startTime, endTime)))

    /**
     * All passes of [satellite] that overlap the window, in chronological order. A satellite that SGP4 cannot
     * propagate (a decayed one, or one far from its epoch) yields none.
     */
    fun passes(satellite: Satellite): List<SatellitePass> {
        if (!(endTime > startTime) || !startTime.isFinite() || !endTime.isFinite()) return emptyList()
        return SatellitePassSearch(satellite, evaluator, startTime, endTime, threshold, darknessSunAltitude).run()
    }

    companion object {
        /** The default minimum altitude of a pass, 10°. */
        const val defaultMinimumAltitude: Double = 10.0

        /** The default Sun altitude below which the sky is dark, -6° (the end of civil twilight). */
        const val defaultDarknessSunAltitude: Double = -6.0

        /** The longest window searched, in days. */
        const val maximumWindowDays: Double = 30.0
    }
}

/** A function of one variable that does not box its argument. */
internal fun interface SatelliteScalarFunction {
    fun value(x: Double): Double
}

/** Root and maximum finding on smooth functions of time. */
internal object SatelliteSearch {
    /** The root of [function] in `[low, high]` given `function(low) < 0 <= function(high)`, by bisection. */
    fun bisect(function: SatelliteScalarFunction, low: Double, high: Double, tolerance: Double): Double {
        var a = low
        var b = high
        var iterations = 0
        while (b - a > tolerance && iterations < 60) {
            val middle = 0.5 * (a + b)
            if (function.value(middle) >= 0.0) b = middle else a = middle
            iterations += 1
        }
        return 0.5 * (a + b)
    }

    /** The maximizer of the unimodal [function] on `[low, high]` by golden-section search. */
    fun maximize(function: SatelliteScalarFunction, low: Double, high: Double, tolerance: Double): Double {
        val ratio = (sqrt(5.0) - 1.0) / 2.0
        var a = low
        var b = high
        var c = b - ratio * (b - a)
        var d = a + ratio * (b - a)
        var fc = function.value(c)
        var fd = function.value(d)
        var iterations = 0
        while (b - a > tolerance && iterations < 80) {
            if (fc > fd) {
                b = d
                d = c
                fd = fc
                c = b - ratio * (b - a)
                fc = function.value(c)
            } else {
                a = c
                c = d
                fc = fd
                d = a + ratio * (b - a)
                fd = function.value(d)
            }
            iterations += 1
        }
        return 0.5 * (a + b)
    }
}

/** One satellite's pass search. Local state only. */
internal class SatellitePassSearch(
    private val satellite: Satellite,
    private val evaluator: SatelliteEvaluator,
    private val startTime: Double,
    private val endTime: Double,
    private val threshold: Double,
    private val darkness: Double,
) {
    private val propagator = satellite.propagator
    private val geometry = evaluator.geometry
    private val state = DoubleArray(6)
    private val maximumSpeed = propagator.maximumSpeedKilometersPerSecond

    /** The topocentric sample at [time], or `null` if SGP4 fails there. */
    private fun sample(time: Double): SatelliteTopocentric? {
        val minutes = (time - propagator.epochReferenceSeconds) / 60.0
        if (propagator.propagateRaw(minutes, state) != 0) return null
        return geometry.look(state[0], state[1], state[2], state[3], state[4], state[5], evaluator.gmst(time))
    }

    /** The geometric altitude at [time]; very low (-1000) where SGP4 fails, so that failures count as "below". */
    private fun altitude(time: Double): Double = sample(time)?.altitude ?: failedAltitude

    fun run(): List<SatellitePass> {
        val passes = ArrayList<SatellitePass>()
        var time = startTime
        while (time < endTime) {
            val current = sample(time)
            val altitude = current?.altitude ?: failedAltitude
            if (altitude >= threshold) {
                // Already above the threshold: the pass is under way at the window's start (or begins right
                // after the previous one ended).
                val followed = followPass(time, isRiseCut = time == startTime)
                passes.add(followed.first)
                time = followed.second + 1.0
                continue
            }
            val next = min(time + safeStep(current, altitude), endTime)
            if (altitude(next) >= threshold) {
                val riseTime = SatelliteSearch.bisect({ altitude(it) - threshold }, time, next, rootTolerance)
                val followed = followPass(riseTime, isRiseCut = false)
                passes.add(followed.first)
                time = followed.second + 1.0
            } else {
                time = next
            }
        }
        return passes
    }

    /** The longest step after which the satellite cannot have reached the threshold, in seconds. */
    private fun safeStep(current: SatelliteTopocentric?, altitude: Double): Double {
        if (current == null) return failureStep
        val gap = min(threshold - altitude, 90.0)
        val seconds = safetyFactor * current.range * sin(gap * PI / 180.0) / maximumSpeed
        return seconds.coerceIn(minimumStep, maximumStep)
    }

    /**
     * Follows a pass from [riseTime] to its set (or the end of the window) and returns it with the time it ended.
     */
    private fun followPass(riseTime: Double, isRiseCut: Boolean): Pair<SatellitePass, Double> {
        val times = ArrayList<Double>()
        val altitudes = ArrayList<Double>()
        times.add(riseTime)
        altitudes.add(altitude(riseTime))
        var setTime = endTime
        var isSetCut = true
        var current = riseTime
        while (true) {
            val age = current - riseTime
            val next = min(current + (if (age <= 1_800.0) passStep else longPassStep), endTime)
            if (next <= current) break
            val nextAltitude = altitude(next)
            if (nextAltitude < threshold) {
                setTime = SatelliteSearch.bisect({ threshold - altitude(it) }, current, next, rootTolerance)
                // bisect finds where `threshold - altitude` turns from negative to non-negative: the set.
                isSetCut = false
                times.add(setTime)
                altitudes.add(threshold)
                break
            }
            times.add(next)
            altitudes.add(nextAltitude)
            current = next
            if (next >= endTime) break
        }

        var best = 0
        for (index in altitudes.indices) {
            if (altitudes[index] > altitudes[best]) best = index
        }
        val low = times[max(best - 1, 0)]
        val high = times[min(best + 1, times.size - 1)]
        val culminationTime = if (high > low) {
            SatelliteSearch.maximize({ altitude(it) }, low, high, culminationTolerance)
        } else {
            times[best]
        }
        val riseDate = pointAt(riseTime)
        val culmination = pointAt(culminationTime)
        val setDate = pointAt(setTime)
        val visible = visibleParts(riseTime, setTime)
        val pass = SatellitePass(
            rise = riseDate,
            culmination = culmination,
            set = setDate,
            visibleIntervals = visible.intervals,
            brightest = visible.brightest,
            isRiseCut = isRiseCut,
            isSetCut = isSetCut,
        )
        return Pair(pass, setTime)
    }

    private fun pointAt(time: Double): SatellitePassPoint {
        val look = evaluator.look(satellite, time, state)
        if (look != null) {
            return SatellitePassPoint(
                date = look.date,
                azimuth = look.azimuth,
                altitude = look.altitude,
                rangeKilometers = look.rangeKilometers,
                isSunlit = look.isSunlit,
                magnitude = look.magnitude,
            )
        }
        return SatellitePassPoint(instantFromTimeIntervalSinceReferenceDate(time), 0.0, threshold, 0.0, false, null)
    }

    /** The apparent magnitude at [time]; positive infinity where it is unknown. */
    private fun magnitude(time: Double): Double = evaluator.look(satellite, time, state)?.magnitude ?: Double.POSITIVE_INFINITY

    private class Visibility(val intervals: List<DateInterval>, val brightest: SatellitePassPoint?)

    /** Whether the satellite can be seen at [time] (sunlit, observer dark); the satellite is above the threshold. */
    private fun isVisible(time: Double): Boolean {
        val minutes = (time - propagator.epochReferenceSeconds) / 60.0
        if (propagator.propagateRaw(minutes, state) != 0) return false
        val sun = evaluator.sunPosition(time)
        val fraction = EarthShadow.illuminatedFraction(state[0], state[1], state[2], sun.x, sun.y, sun.z)
        if (fraction < EarthShadow.sunlitThreshold) return false
        return evaluator.sunAltitude(sun, evaluator.gmst(time)) < darkness
    }

    private fun visibleParts(riseTime: Double, setTime: Double): Visibility {
        val duration = setTime - riseTime
        if (duration <= 0.0) return Visibility(emptyList(), null)
        // A pass in full daylight cannot be seen. The Sun's altitude changes by at most 15 degrees an hour, so
        // with samples at most ten minutes apart it stays within 1.25 degrees of the lowest sample in between.
        val checks = max(3, ceil(duration / daylightSpacing).toInt() + 1)
        var allLight = true
        for (index in 0 until checks) {
            val time = riseTime + duration * index / (checks - 1)
            if (evaluator.sunAltitude(evaluator.sunPosition(time), evaluator.gmst(time)) < darkness + daylightMargin) {
                allLight = false
                break
            }
        }
        if (allLight) return Visibility(emptyList(), null)

        val spacing = min(max(visibilityStep, duration / visibilitySamples), longVisibilityStep)
        val count = max(1, ceil(duration / spacing).toInt())
        val step = duration / count
        val intervals = ArrayList<DateInterval>()
        var intervalStart = Double.NaN
        var previousTime = riseTime
        var previous = isVisible(riseTime)
        if (previous) intervalStart = riseTime
        var brightestTime = Double.NaN
        var brightestMagnitude = Double.POSITIVE_INFINITY
        if (previous && satellite.intrinsicMagnitude != null) {
            val look = evaluator.look(satellite, riseTime, state)
            val magnitude = look?.magnitude
            if (magnitude != null && magnitude < brightestMagnitude) {
                brightestMagnitude = magnitude
                brightestTime = riseTime
            }
        }
        for (index in 1..count) {
            val time = if (index == count) setTime else riseTime + index * step
            val visible = isVisible(time)
            if (visible != previous) {
                val edge = SatelliteSearch.bisect(
                    { if (isVisible(it) == visible) 1.0 else -1.0 },
                    previousTime, time, visibilityTolerance,
                )
                if (visible) {
                    intervalStart = edge
                } else {
                    intervals.add(DateInterval(instantFromTimeIntervalSinceReferenceDate(intervalStart), instantFromTimeIntervalSinceReferenceDate(edge)))
                }
            }
            if (visible && satellite.intrinsicMagnitude != null) {
                val magnitude = evaluator.look(satellite, time, state)?.magnitude
                if (magnitude != null && magnitude < brightestMagnitude) {
                    brightestMagnitude = magnitude
                    brightestTime = time
                }
            }
            previous = visible
            previousTime = time
        }
        if (previous) {
            intervals.add(DateInterval(instantFromTimeIntervalSinceReferenceDate(intervalStart), instantFromTimeIntervalSinceReferenceDate(setTime)))
        }
        if (brightestTime.isNaN()) return Visibility(intervals, null)
        // Refine the brightest sample within its neighbours and its visible part.
        val part = intervals.firstOrNull {
            val begin = it.start.timeIntervalSinceReferenceDate
            val finish = it.end.timeIntervalSinceReferenceDate
            brightestTime >= begin - 1.0e-6 && brightestTime <= finish + 1.0e-6
        }
        if (part != null) {
            val low = max(brightestTime - step, part.start.timeIntervalSinceReferenceDate)
            val high = min(brightestTime + step, part.end.timeIntervalSinceReferenceDate)
            if (high > low) {
                val refined = SatelliteSearch.maximize({ -magnitude(it) }, low, high, culminationTolerance)
                if (magnitude(refined) < brightestMagnitude) brightestTime = refined
            }
        }
        return Visibility(intervals, pointAt(brightestTime))
    }

    private companion object {
        const val failedAltitude: Double = -1_000.0
        const val failureStep: Double = 120.0
        const val minimumStep: Double = 1.0
        const val maximumStep: Double = 900.0
        const val safetyFactor: Double = 0.9
        const val passStep: Double = 10.0
        const val longPassStep: Double = 60.0
        const val rootTolerance: Double = 0.01
        const val culminationTolerance: Double = 0.05
        const val visibilityStep: Double = 5.0
        const val visibilityTolerance: Double = 0.05
        const val daylightMargin: Double = 1.5
        const val daylightSpacing: Double = 600.0
        const val visibilitySamples: Double = 120.0
        const val longVisibilityStep: Double = 30.0
    }
}
