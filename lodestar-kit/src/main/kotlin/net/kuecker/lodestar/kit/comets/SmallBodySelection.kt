package net.kuecker.lodestar.kit.comets

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * A comet or asteroid that is bright enough to photograph, with the moment it is brightest.
 *
 * @property body The body.
 * @property peak Its state at the brightest sampled moment (among those far enough from the Sun): the magnitude,
 * the elongation, the distances and the position.
 */
data class BrightSmallBody(val body: SmallBody, val peak: SmallBodyState) {
    /** The magnitude at the peak (the state has one, otherwise the body would not have been selected). */
    val peakMagnitude: Double get() = peak.apparentMagnitude ?: Double.POSITIVE_INFINITY

    /** The moment of the peak. */
    val peakDate: Instant get() = peak.julianDate.date
}

/**
 * Picks the bright comets (and asteroids) out of thousands of orbit records, for "bright comets now" and "this
 * month" lists.
 *
 * A cheap prefilter discards almost every record without computing a position: from the distance from the Sun over the
 * period (a Kepler solution at both ends, plus the perihelion or aphelion when the period contains it) and the fact
 * that the Earth stays between 0.983 and 1.017 AU from the Sun, it bounds the brightest the object can possibly get
 * (`M1 + 5 log Δmin + K1 log rmin`; for an asteroid `H + 5 log(r Δ)min`), and drops it if that is fainter than the limit.
 * The survivors are sampled (daily, at most [maximumSampleCount] samples; the Earth is computed once per sample for all
 * of them), the best sample is refined by a golden-section search, and the brightest state with enough elongation is kept.
 *
 * **Cost** (desktop CPU, warm): the 963 comets of `CometEls.txt` take about 1 ms for [brightNow] and 2 ms for a month;
 * 43 000 near-Earth asteroids (`NEA.txt`) take 60 ms and 0.5 s (their orbits come close to the Earth's, so the prefilter
 * keeps many of them). Records that survive the prefilter cost about 5 microseconds per sample plus 24 states for the
 * refinement. Sorting is by peak magnitude, brightest first, ties by identifier.
 */
object SmallBodySelection {
    /** The most samples taken over an interval, 64 (spans over 63 days are sampled more coarsely than daily). */
    const val maximumSampleCount: Int = 64

    /** The Earth's smallest distance from the Sun in AU, rounded down. */
    private const val earthMinimumDistance = 0.9831

    /** The Earth's largest distance from the Sun in AU, rounded up. */
    private const val earthMaximumDistance = 1.0169

    /** The smallest Earth distance used in the prefilter, 0.001 AU (150 000 km). */
    private const val minimumEarthDistance = 0.001

    /** Safety margin of the prefilter in magnitudes. */
    private const val prefilterMargin = 0.35

    private const val refinementIterations = 24

    /**
     * The bodies that reach [magnitudeLimit] while at least [minimumElongation] from the Sun at some moment in
     * [interval], brightest first.
     *
     * @param bodies The candidates (for example the result of [CometElementsParser.parse]).
     * @param interval The period; the sampling is daily up to 63 days and coarser beyond.
     * @param magnitudeLimit The faintest magnitude accepted (non-finite: nothing is selected).
     * @param minimumElongation Smallest angle from the Sun in degrees, clamped to 0…180.
     * @param kind Only comets or only asteroids; `null` for both.
     * @param maximumCount The most results to return (0 or less: none).
     */
    fun brightest(
        bodies: List<SmallBody>,
        interval: DateInterval,
        magnitudeLimit: Double = SmallBodyEphemeris.defaultMagnitudeLimit,
        minimumElongation: Double = SmallBodyEphemeris.defaultMinimumElongation,
        kind: SmallBodyKind? = null,
        maximumCount: Int = 100,
    ): List<BrightSmallBody> {
        val days = interval.duration / JulianDate.secondsPerDay
        val count = if (days > 0) min(maximumSampleCount, max(2, ceil(days).toInt() + 1)) else 1
        return select(bodies, interval.start, if (count > 1) days / (count - 1) else 0.0, count, magnitudeLimit, minimumElongation, kind, maximumCount)
    }

    /** The bodies that are at most [magnitudeLimit] and at least [minimumElongation] from the Sun at [date], brightest first. */
    fun brightNow(
        bodies: List<SmallBody>,
        date: Instant,
        magnitudeLimit: Double = SmallBodyEphemeris.defaultMagnitudeLimit,
        minimumElongation: Double = SmallBodyEphemeris.defaultMinimumElongation,
        kind: SmallBodyKind? = null,
        maximumCount: Int = 100,
    ): List<BrightSmallBody> = select(bodies, date, 0.0, 1, magnitudeLimit, minimumElongation, kind, maximumCount)

    private fun select(
        bodies: List<SmallBody>,
        start: Instant,
        stepDays: Double,
        count: Int,
        magnitudeLimit: Double,
        minimumElongation: Double,
        kind: SmallBodyKind?,
        maximumCount: Int,
    ): List<BrightSmallBody> {
        if (!magnitudeLimit.isFinite() || !minimumElongation.isFinite() || maximumCount <= 0) return emptyList()
        val elongationLimit = AstroMath.clamp(minimumElongation, 0.0, 180.0)
        val first = JulianDate(start)
        val last = first.addingDays(stepDays * (count - 1))
        var earths: List<SmallBodyEphemeris.EarthReference>? = null
        val found = ArrayList<BrightSmallBody>()
        for (body in bodies) {
            if (kind != null && body.kind != kind) continue
            if (body.photometry == null) continue
            if (brightestPossible(body, first.tt, last.tt) > magnitudeLimit) continue
            val samples = earths ?: List(count) { index ->
                SmallBodyEphemeris.EarthReference(if (index == 0) first else first.addingDays(stepDays * index))
            }.also { earths = it }
            var best: SmallBodyState? = null
            var bestIndex = 0
            for (index in samples.indices) {
                val state = SmallBodyEphemeris.state(body, samples[index]) ?: continue
                if (!qualifies(state, elongationLimit)) continue
                if (best == null || magnitude(state) < magnitude(best)) {
                    best = state
                    bestIndex = index
                }
            }
            if (best == null) continue
            if (count > 1) {
                val low = max(0, bestIndex - 1)
                val high = min(count - 1, bestIndex + 1)
                best = refined(body, best, samples[low].julianDate, samples[high].julianDate, elongationLimit)
            }
            if (magnitude(best) <= magnitudeLimit) found.add(BrightSmallBody(body, best))
        }
        found.sortWith { a, b ->
            if (a.peakMagnitude < b.peakMagnitude) {
                -1
            } else if (b.peakMagnitude < a.peakMagnitude) {
                1
            } else {
                compareIdentifiers(a.body.id, b.body.id)
            }
        }
        return if (found.size > maximumCount) found.subList(0, maximumCount).toList() else found
    }

    private fun magnitude(state: SmallBodyState): Double = state.apparentMagnitude ?: Double.POSITIVE_INFINITY

    private fun qualifies(state: SmallBodyState, elongationLimit: Double): Boolean =
        state.apparentMagnitude != null && state.elongation >= elongationLimit

    /** Orders identifiers by Unicode scalar values (the same order in Kotlin and Swift). */
    private fun compareIdentifiers(a: String, b: String): Int {
        val left = a.codePoints().toArray()
        val right = b.codePoints().toArray()
        val common = min(left.size, right.size)
        for (index in 0 until common) {
            if (left[index] != right[index]) return if (left[index] < right[index]) -1 else 1
        }
        return if (left.size < right.size) -1 else if (left.size > right.size) 1 else 0
    }

    /**
     * Golden-section search for the brightest qualifying state between [from] and [to], starting from [seed] (so the
     * result is never fainter than the seed).
     */
    private fun refined(
        body: SmallBody,
        seed: SmallBodyState,
        from: JulianDate,
        to: JulianDate,
        elongationLimit: Double,
    ): SmallBodyState {
        val ratio = 0.6180339887498949
        var best = seed
        var low = from.ut
        var high = to.ut
        if (!(high > low)) return best
        fun evaluate(ut: Double): Double {
            val state = SmallBodyEphemeris.state(body, JulianDate(ut)) ?: return Double.POSITIVE_INFINITY
            if (!qualifies(state, elongationLimit)) return Double.POSITIVE_INFINITY
            val value = magnitude(state)
            if (value < magnitude(best)) best = state
            return value
        }
        var c = high - ratio * (high - low)
        var d = low + ratio * (high - low)
        var fc = evaluate(c)
        var fd = evaluate(d)
        for (iteration in 0 until refinementIterations) {
            if (fc < fd) {
                high = d
                d = c
                fd = fc
                c = high - ratio * (high - low)
                fc = evaluate(c)
            } else {
                low = c
                c = d
                fc = fd
                d = low + ratio * (high - low)
                fd = evaluate(d)
            }
        }
        return best
    }

    /**
     * A lower bound of the magnitude [body] can have between [startTT] and [endTT] (Julian Days TT): the brightest
     * conceivable combination of the smallest distance from the Sun over the period and the smallest possible
     * distance from the Earth. `-∞` when no bound can be computed.
     */
    internal fun brightestPossible(body: SmallBody, startTT: Double, endTT: Double): Double {
        val photometry = body.photometry ?: return Double.POSITIVE_INFINITY
        val orbit = body.orbit
        val atStart = OrbitPropagator.heliocentricDistance(orbit, startTT) ?: return Double.NEGATIVE_INFINITY
        val atEnd = OrbitPropagator.heliocentricDistance(orbit, endTT) ?: return Double.NEGATIVE_INFINITY
        var nearest = min(atStart, atEnd)
        var farthest = max(atStart, atEnd)
        val period = orbit.periodDays
        val passage = orbit.perihelionJulianDayTT
        if (period == null) {
            if (passage >= startTT && passage <= endTT) nearest = orbit.perihelionDistanceAU
        } else {
            val perihelionCount = ceil((startTT - passage) / period)
            if (passage + perihelionCount * period <= endTT) nearest = orbit.perihelionDistanceAU
            val aphelionCount = ceil((startTT - passage) / period - 0.5)
            if (passage + (aphelionCount + 0.5) * period <= endTT) {
                farthest = orbit.aphelionDistanceAU ?: farthest
            }
        }
        val earthDistance = max(minimumEarthDistance, max(nearest - earthMaximumDistance, earthMinimumDistance - farthest))
        return when (photometry) {
            is SmallBodyPhotometry.Comet -> {
                val magnitudes = photometry.magnitudes
                val sunDistance = if (magnitudes.totalSlope >= 0) nearest else farthest
                magnitudes.totalMagnitude + 5 * log10(earthDistance) + magnitudes.totalSlope * log10(sunDistance) - prefilterMargin
            }
            is SmallBodyPhotometry.Asteroid ->
                photometry.magnitudes.absoluteMagnitude + 5 * log10(nearest * earthDistance) - prefilterMargin
        }
    }
}
