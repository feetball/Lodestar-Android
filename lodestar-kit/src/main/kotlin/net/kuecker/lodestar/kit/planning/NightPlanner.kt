package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.ceil

/**
 * Plans a night of astrophotography: when it is dark, when the Moon is up, when the Milky Way's core
 * and the planets are visible, and which targets rise highest in the dark.
 *
 * All altitudes are geometric (no refraction) unless a caller's own position function says otherwise,
 * and refer to the centre of the target. Times are `Instant`s; "the night starting on" a local date runs
 * from local noon to local noon (see [TwilightCalculator]).
 *
 * The rest of the planner is declared as extension functions in `NightPlannerSummary.kt`
 * (`summary`) and `NightPlannerVisibility.kt` (`visibility`); import
 * `net.kuecker.lodestar.kit.planning.*` to call them as `NightPlanner.summary(...)`.
 */
object NightPlanner {
    /** The minimum altitude, in degrees, that `visibility` counts as usable when none is given. */
    const val defaultMinimumAltitude: Double = 20.0

    /** The largest number of samples [altitudeCurve] returns. */
    const val maximumCurveSampleCount: Int = 100_000

    // MARK: Altitude curves

    /**
     * Samples where a target is in the sky across an interval.
     *
     * The samples are evenly spaced from the start to the end of the interval, with at most [step]
     * seconds between them (the spacing grows if that would take more than [maximumCurveSampleCount]
     * samples). A [SkyFrame] is built for every sample and handed to [position], which returns the
     * target's horizontal coordinates; return geometric coordinates (`refraction = false`) for a
     * geometric curve. An empty interval gives a single sample at its start.
     *
     * @param observer The place of observation.
     * @param interval The time span to sample.
     * @param step The largest gap between samples in seconds; a value that is not positive or not
     * finite is replaced by 10 minutes, and values below a second by one second.
     * @param position The target's horizontal coordinates in the given frame.
     */
    fun altitudeCurve(
        observer: Observer,
        interval: DateInterval,
        step: Double = 600.0,
        position: (SkyFrame) -> HorizontalCoordinate,
    ): List<AltitudeSample> {
        val duration = if (interval.duration.isFinite()) maxOf(interval.duration, 0.0) else 0.0
        val gapCount = curveGapCount(duration, step)
        val spacing = if (gapCount > 0) duration / gapCount.toDouble() else 0.0
        val curve = ArrayList<AltitudeSample>(gapCount + 1)
        for (index in 0..gapCount) {
            val date = if (index == gapCount && gapCount > 0) {
                interval.end
            } else {
                interval.start.addingTimeInterval(index.toDouble() * spacing)
            }
            val horizontal = position(SkyFrame(date, observer))
            curve.add(AltitudeSample(date, horizontal.altitude, horizontal.azimuth))
        }
        return curve
    }

    /**
     * The number of gaps between the samples of a curve over [duration] seconds: enough that none is
     * longer than [step], and not so many that the curve would exceed [maximumCurveSampleCount] samples.
     */
    internal fun curveGapCount(duration: Double, step: Double): Int {
        val requested = if (step.isFinite() && step > 0) swiftMax(step, 1.0) else 600.0
        return swiftMin(ceil(duration / requested), (maximumCurveSampleCount - 1).toDouble()).toInt()
    }

    /**
     * Samples the geometric altitude and azimuth of a fixed object, given by its J2000 catalog place
     * and optional proper motion, across an interval. See the position-function overload of
     * [altitudeCurve].
     */
    fun altitudeCurve(
        place: EquatorialCoordinate,
        properMotion: ProperMotion? = null,
        observer: Observer,
        interval: DateInterval,
        step: Double = 600.0,
    ): List<AltitudeSample> =
        altitudeCurve(observer, interval, step) { it.horizontal(place, properMotion, false) }

    /**
     * Samples the geometric altitude and azimuth of the Sun, the Moon or a planet across an interval.
     * See the position-function overload of [altitudeCurve].
     */
    fun altitudeCurve(
        body: SolarSystemBody,
        observer: Observer,
        interval: DateInterval,
        step: Double = 600.0,
    ): List<AltitudeSample> =
        altitudeCurve(observer, interval, step) { it.horizontal(body, false) }
}
