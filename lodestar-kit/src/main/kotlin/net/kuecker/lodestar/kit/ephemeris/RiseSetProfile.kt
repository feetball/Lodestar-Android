package net.kuecker.lodestar.kit.ephemeris

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * One evaluation of an altitude curve.
 *
 * In Swift this is `RiseSetCalculator.Sample`; Kotlin cannot add nested types from another file, so it is a
 * top-level internal class of the same package.
 *
 * @property centreAltitude Geometric altitude of the target's centre in degrees.
 * @property limbAllowance Degrees added to the centre altitude before it is compared with a horizon threshold, for
 * targets whose limb (not centre) defines the event and whose size varies (the Moon).
 */
internal data class RiseSetSample(val centreAltitude: Double, val limbAllowance: Double = 0.0) {
    /** The altitude that is compared with a horizon threshold. */
    val limbAltitude: Double get() = centreAltitude + limbAllowance
}

/**
 * An altitude curve sampled at regular instants, with root and extremum refinement.
 *
 * Times are seconds since the Foundation reference date. Sampling is regular with a step no
 * larger than requested; sign changes are bracketed between samples and refined with Brent's
 * method, extremes with golden-section search. Pairs of crossings that fall between two samples
 * (a target that only just touches the horizon) are found by testing each local extreme of the
 * samples.
 *
 * In Swift this is `RiseSetCalculator.Profile`; Kotlin cannot add nested types from another file, so it is a
 * top-level internal class of the same package.
 *
 * Samples are kept as parallel arrays of centre and limb altitudes (Swift: an array of `Sample`), and the
 * refinement loops evaluate through [Evaluator.centreAltitude] and [Evaluator.limbAltitude], so the inner
 * loops allocate nothing.
 */
internal class RiseSetProfile(start: Double, end: Double, step: Double, evaluate: Evaluator) {
    /** A real function of one variable (the unboxed counterpart of `(Double) -> Double`, used in the search loops). */
    fun interface RealFunction {
        /** The value of the function at [x]. */
        fun valueAt(x: Double): Double
    }

    /** The highest point found by [maximum]: the argument and the function value there. */
    class Peak(val time: Double, val value: Double)

    /** The lowest point of a profile (see [lowestPoint]): the instant and the centre altitude there. */
    class LowestPoint(val time: Double, val altitude: Double)

    /** Evaluates an altitude curve at a time in seconds since the Foundation reference date (Swift `Evaluator`). */
    fun interface Evaluator {
        /** The sample at [time]. */
        fun sample(time: Double): RiseSetSample

        /** The centre altitude at [time]; implementations may avoid allocating a [RiseSetSample]. */
        fun centreAltitude(time: Double): Double = sample(time).centreAltitude

        /** The limb altitude at [time]; implementations may avoid allocating a [RiseSetSample]. */
        fun limbAltitude(time: Double): Double = sample(time).limbAltitude
    }

    /** The sample instants, in seconds since the reference date. */
    val times: DoubleArray

    /** The centre altitude at each of the [times], in degrees. */
    val centreAltitudes: DoubleArray

    /** The limb altitude (centre altitude plus the limb allowance) at each of the [times], in degrees. */
    val limbAltitudes: DoubleArray

    /**
     * Samples the curve from [start] to [end] (seconds since the reference date) with at most
     * [step] seconds between samples. An empty or invalid interval yields a single sample at [start].
     */
    init {
        val span = end - start
        var intervalCount = 0
        if (span.isFinite() && span > 0 && step.isFinite() && step > 0) {
            intervalCount = swiftMin(ceil(span / step), (maximumSampleCount - 1).toDouble()).toInt()
        }
        val times = DoubleArray(intervalCount + 1)
        val centreAltitudes = DoubleArray(intervalCount + 1)
        val limbAltitudes = DoubleArray(intervalCount + 1)
        for (index in 0..intervalCount) {
            val fraction = index.toDouble() / maxOf(intervalCount, 1).toDouble()
            val time = if (index == intervalCount && intervalCount > 0) end else start + span * fraction
            val sample = evaluate.sample(time)
            times[index] = time
            centreAltitudes[index] = sample.centreAltitude
            limbAltitudes[index] = sample.limbAltitude
        }
        this.times = times
        this.centreAltitudes = centreAltitudes
        this.limbAltitudes = limbAltitudes
    }

    /** Samples the curve over a [DateInterval]. */
    constructor(interval: DateInterval, step: Double, evaluate: Evaluator) : this(
        interval.start.timeIntervalSinceReferenceDate,
        interval.end.timeIntervalSinceReferenceDate,
        step,
        evaluate,
    )

    /** Every instant at which the limb altitude passes through [threshold], in time order. */
    fun crossings(threshold: Double, tolerance: Double, evaluate: Evaluator): List<AltitudeCrossing> {
        val margin = DoubleArray(limbAltitudes.size) { limbAltitudes[it] - threshold }
        val function = RealFunction { time -> evaluate.limbAltitude(time) - threshold }
        val found = ArrayList<AltitudeCrossing>()
        for (index in 0 until times.size - 1) {
            val current = margin[index]
            val next = margin[index + 1]
            if (!(current.isFinite() && next.isFinite())) continue
            if ((current < 0) != (next < 0)) {
                val time = root(function, times[index], times[index + 1], current, next, tolerance)
                found.add(
                    AltitudeCrossing(
                        instantFromTimeIntervalSinceReferenceDate(time),
                        if (next >= 0) AltitudeCrossing.Kind.RISING else AltitudeCrossing.Kind.SETTING,
                    ),
                )
            } else if (index > 0 && margin[index - 1].isFinite() && (margin[index - 1] < 0) == (current < 0)) {
                found.addAll(hiddenCrossings(index, margin, function, tolerance))
            }
        }
        return found
    }

    /**
     * The pair of crossings, if any, hidden between samples `index - 1 ... index + 1`, which all lie
     * on the same side of the threshold: the curve dips to the other side around an extreme at [index].
     */
    private fun hiddenCrossings(
        index: Int,
        margin: DoubleArray,
        function: RealFunction,
        tolerance: Double,
    ): List<AltitudeCrossing> {
        // `orientation` makes the curve `g` negative on the sampled side, so the dip is a maximum of `g`.
        val orientation = if (margin[index] < 0) 1.0 else -1.0
        val before = orientation * margin[index - 1]
        val here = orientation * margin[index]
        val after = orientation * margin[index + 1]
        if (!(here > before && here >= after && here > -grazingMargin)) return emptyList()

        val peak = maximum(
            RealFunction { orientation * function.valueAt(it) },
            times[index - 1],
            times[index + 1],
            tolerance,
        )
        if (!(peak.value > 0)) return emptyList()
        val peakMargin = orientation * peak.value
        val first = root(function, times[index - 1], peak.time, margin[index - 1], peakMargin, tolerance)
        val second = root(function, peak.time, times[index + 1], peakMargin, margin[index + 1], tolerance)
        val sampledBelow = orientation > 0
        return listOf(
            AltitudeCrossing(
                instantFromTimeIntervalSinceReferenceDate(first),
                if (sampledBelow) AltitudeCrossing.Kind.RISING else AltitudeCrossing.Kind.SETTING,
            ),
            AltitudeCrossing(
                instantFromTimeIntervalSinceReferenceDate(second),
                if (sampledBelow) AltitudeCrossing.Kind.SETTING else AltitudeCrossing.Kind.RISING,
            ),
        )
    }

    /** Every local maximum of the centre altitude strictly inside the interval (upper culminations). */
    fun upperCulminations(tolerance: Double, evaluate: Evaluator): List<AltitudeCulmination> {
        if (times.size < 2) return emptyList()
        val altitude = RealFunction { evaluate.centreAltitude(it) }
        val last = times.size - 1
        val found = ArrayList<AltitudeCulmination>()

        fun append(peak: Peak) {
            found.add(AltitudeCulmination(instantFromTimeIntervalSinceReferenceDate(peak.time), peak.value))
        }
        // A curve that starts by falling away from its first sample, or ends by rising towards
        // its last, may peak inside the first or last interval; a search that runs into the end of
        // the interval means there is no peak.
        val boundaryGuard = swiftMax(1.0, 4 * tolerance)
        if (centreAltitudes[0] > centreAltitudes[1]) {
            val peak = maximum(altitude, times[0], times[1], tolerance)
            if (peak.time - times[0] > boundaryGuard) append(peak)
        }
        for (index in 1 until maxOf(last, 1)) {
            val before = centreAltitudes[index - 1]
            val here = centreAltitudes[index]
            val after = centreAltitudes[index + 1]
            if (!(before.isFinite() && here.isFinite() && after.isFinite() && here > before && here >= after)) continue
            append(maximum(altitude, times[index - 1], times[index + 1], tolerance))
        }
        if (centreAltitudes[last] > centreAltitudes[last - 1]) {
            val peak = maximum(altitude, times[last - 1], times[last], tolerance)
            if (times[last] - peak.time > boundaryGuard) append(peak)
        }
        return found
    }

    /**
     * The lowest centre altitude reached inside the interval, refined around the lowest sample.
     * (Samples whose altitude is not finite are never the lowest.)
     */
    fun lowestPoint(tolerance: Double, evaluate: Evaluator): LowestPoint {
        var lowest = 0
        for (index in centreAltitudes.indices) {
            if (centreAltitudes[index] < centreAltitudes[lowest]) lowest = index
        }
        if (times.size < 2) return LowestPoint(times[0], centreAltitudes[0])
        val trough = maximum(
            RealFunction { -evaluate.centreAltitude(it) },
            times[maxOf(lowest - 1, 0)],
            times[minOf(lowest + 1, times.size - 1)],
            tolerance,
        )
        return LowestPoint(trough.time, -trough.value)
    }

    companion object {
        /** Upper bound on the number of samples; longer intervals are sampled more coarsely. */
        const val maximumSampleCount: Int = 100_000

        /**
         * How close (degrees) a local extreme of the samples must come to a threshold before the curve
         * is searched for a pair of crossings between samples. Between two samples at most 10 minutes
         * apart the curve cannot rise above the sampled maximum by more than a few hundredths of a degree.
         */
        private const val grazingMargin: Double = 0.05

        /** The spacing of Double values near 1 (Swift `Double.ulpOfOne`). */
        private const val ulpOfOne: Double = 2.220446049250313e-16

        // MARK: Numerical search

        /**
         * Brent's method for a root of [function] bracketed by [lower] and [upper], whose function
         * values must have opposite signs (or be zero). Stops when the bracket is shorter than [tolerance].
         */
        fun root(
            function: RealFunction,
            lower: Double,
            upper: Double,
            valueAtLower: Double,
            valueAtUpper: Double,
            tolerance: Double,
        ): Double {
            var a = lower
            var b = upper
            var fa = valueAtLower
            var fb = valueAtUpper
            if (fa == 0.0) return a
            if (fb == 0.0) return b
            var c = a
            var fc = fa
            var step = b - a
            var previousStep = step
            for (iteration in 0 until 100) {
                if ((fb > 0) == (fc > 0)) {
                    c = a
                    fc = fa
                    step = b - a
                    previousStep = step
                }
                if (abs(fc) < abs(fb)) {
                    a = b
                    b = c
                    c = a
                    fa = fb
                    fb = fc
                    fc = fa
                }
                val tolerance1 = 2 * ulpOfOne * abs(b) + 0.5 * tolerance
                val midpoint = 0.5 * (c - b)
                if (abs(midpoint) <= tolerance1 || fb == 0.0) return b
                if (abs(previousStep) >= tolerance1 && abs(fa) > abs(fb)) {
                    // Inverse quadratic interpolation, or the secant method when only two points are distinct.
                    val ratio = fb / fa
                    var p: Double
                    var q: Double
                    if (a == c) {
                        p = 2 * midpoint * ratio
                        q = 1 - ratio
                    } else {
                        val qa = fa / fc
                        val r = fb / fc
                        p = ratio * (2 * midpoint * qa * (qa - r) - (b - a) * (r - 1))
                        q = (qa - 1) * (r - 1) * (ratio - 1)
                    }
                    if (p > 0) q = -q
                    p = abs(p)
                    if (2 * p < swiftMin(3 * midpoint * q - abs(tolerance1 * q), abs(previousStep * q))) {
                        previousStep = step
                        step = p / q
                    } else {
                        step = midpoint
                        previousStep = step
                    }
                } else {
                    step = midpoint
                    previousStep = step
                }
                a = b
                fa = fb
                b += if (abs(step) > tolerance1) step else if (midpoint >= 0) tolerance1 else -tolerance1
                fb = function.valueAt(b)
            }
            return b
        }

        /** Golden-section search for the maximum of a unimodal [function] on `lower ... upper`. */
        fun maximum(function: RealFunction, lower: Double, upper: Double, tolerance: Double): Peak {
            val inverseGolden = (sqrt(5.0) - 1) / 2
            var a = lower
            var b = upper
            var c = b - inverseGolden * (b - a)
            var d = a + inverseGolden * (b - a)
            var fc = function.valueAt(c)
            var fd = function.valueAt(d)
            for (iteration in 0 until 200) {
                if (!(abs(b - a) > tolerance)) break
                if (fc > fd) {
                    b = d
                    d = c
                    fd = fc
                    c = b - inverseGolden * (b - a)
                    fc = function.valueAt(c)
                } else {
                    a = c
                    c = d
                    fc = fd
                    d = a + inverseGolden * (b - a)
                    fd = function.valueAt(d)
                }
            }
            return if (fc > fd) Peak(c, fc) else Peak(d, fd)
        }
    }
}
