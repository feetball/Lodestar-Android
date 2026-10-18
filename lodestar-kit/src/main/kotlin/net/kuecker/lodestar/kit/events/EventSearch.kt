package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Numerical helpers shared by the event finders. Times are Julian Dates in UT ("ut", days); the Julian Date of an
 * instant carries the ΔT estimate of [JulianDate], so every finder sees the same ephemeris time.
 */
internal object EventSearch {
    /** The Sun's mean rate of ecliptic longitude in degrees per day. */
    const val meanSolarRate: Double = 0.985_647_3

    /** Days in the tropical year. */
    const val tropicalYearDays: Double = 365.242_2

    /** Longest interval the calculator accepts, in days (a little over ten years). */
    const val maximumSpanDays: Double = 3_660.0

    private val obliquityJ2000Radians: Double = AstroMath.radians(Obliquity.j2000)

    /** The Julian Date (UT) of [date]. */
    fun ut(date: Instant): Double = JulianDate(date).ut

    /** The instant of a UT Julian Date. */
    fun instant(ut: Double): Instant = JulianDate(ut).date

    /**
     * `true` when [from] and [to] are in order and inside the years 1 to 4001 ([distantPast], [distantFuture]), where the
     * ephemerides and the calendar arithmetic are well defined.
     */
    fun isUsable(from: Instant, to: Instant): Boolean = from >= distantPast && to <= distantFuture && to >= from

    /** [to] limited to [maximumSpanDays] after [from]. */
    fun limitedEnd(from: Instant, to: Instant): Instant {
        val limit = from.addingTimeInterval(maximumSpanDays * JulianDate.secondsPerDay)
        return if (to > limit) limit else to
    }

    /** The Sun's apparent geocentric ecliptic longitude in degrees, referred to the equinox of J2000.0. */
    fun sunLongitudeJ2000(jd: JulianDate): Double {
        val sun = Ephemeris.geocentricJ2000(SolarSystemBody.SUN, jd)
        val y = sun.y * cos(obliquityJ2000Radians) + sun.z * sin(obliquityJ2000Radians)
        return AstroMath.normalizeDegrees(AstroMath.atan2Deg(y, sun.x))
    }

    /** The Sun's apparent geocentric ecliptic longitude of date in degrees (true equinox of date). */
    fun sunLongitudeOfDate(jd: JulianDate): Double = Ephemeris.state(SolarSystemBody.SUN, jd).eclipticLongitudeOfDate

    /**
     * The UT Julian Date near [guess] at which [longitude] equals [target] degrees, by Newton steps with the Sun's
     * mean rate (the true rate stays within 4 % of it, so the error shrinks by a factor of 25 each step).
     */
    fun solveSolarLongitude(target: Double, guess: Double, longitude: (JulianDate) -> Double): Double {
        var ut = guess
        for (iteration in 0 until 12) {
            val error = AstroMath.normalizeSignedDegrees(longitude(JulianDate(ut)) - target)
            val step = error / meanSolarRate
            ut -= step
            if (abs(step) < 1e-9) break
        }
        return ut
    }

    /**
     * Brent's method for a root of [function] between [lower] and [upper], or `null` when the function has the
     * same sign at both ends. Stops when the bracket is shorter than [tolerance].
     */
    fun root(function: (Double) -> Double, lower: Double, upper: Double, tolerance: Double): Double? {
        val valueAtLower = function(lower)
        val valueAtUpper = function(upper)
        if (!(valueAtLower.isFinite() && valueAtUpper.isFinite())) return null
        if ((valueAtLower > 0) == (valueAtUpper > 0) && valueAtLower != 0.0 && valueAtUpper != 0.0) return null
        return RiseSetProfile.root(
            RiseSetProfile.RealFunction { function(it) },
            lower, upper, valueAtLower, valueAtUpper, tolerance,
        )
    }

    /** The argument that minimises the unimodal [function] on [lower]…[upper] (golden-section search) and its value. */
    fun minimum(function: (Double) -> Double, lower: Double, upper: Double, tolerance: Double): Pair<Double, Double> {
        val peak = RiseSetProfile.maximum(RiseSetProfile.RealFunction { -function(it) }, lower, upper, tolerance)
        return Pair(peak.time, -peak.value)
    }

    /** The argument that maximises the unimodal [function] on [lower]…[upper] and its value. */
    fun maximum(function: (Double) -> Double, lower: Double, upper: Double, tolerance: Double): Pair<Double, Double> {
        val peak = RiseSetProfile.maximum(RiseSetProfile.RealFunction { function(it) }, lower, upper, tolerance)
        return Pair(peak.time, peak.value)
    }

    /**
     * Sample indices of the strict local maxima of a series (Swift and Kotlin agree on `>` before and `>=` after, so
     * a plateau counts once, at its first sample).
     */
    fun localMaxima(values: DoubleArray): List<Int> {
        val found = ArrayList<Int>()
        for (index in 1 until values.size - 1) {
            if (values[index] > values[index - 1] && values[index] >= values[index + 1]) found.add(index)
        }
        return found
    }

    /** Sample indices of the local minima of a series (`<=` before, `<` after). */
    fun localMinima(values: DoubleArray): List<Int> {
        val found = ArrayList<Int>()
        for (index in 1 until values.size - 1) {
            if (values[index] <= values[index - 1] && values[index] < values[index + 1]) found.add(index)
        }
        return found
    }

    /** Number of grid intervals of at most [step] days over [span] days, at least 1. */
    fun intervalCount(span: Double, step: Double): Int = maxOf(1, ceil(span / step).toInt())
}
