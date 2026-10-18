package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.planning.*
import java.time.Instant

/**
 * When targets are visible over a [HorizonProfile]: above the real skyline instead of the flat horizon.
 *
 * A target is **visible** while it is not hidden by the skyline at its azimuth. The test is on the
 * target's geometric (not refracted) altitude: it must reach [thresholdAltitude] of the skyline altitude
 * there, i.e. the altitude at which atmospheric refraction lifts the target to the skyline. For the flat
 * profile this is exactly the horizon of [RiseSetCalculator], −0.5667° for stars, planets and deep-sky
 * objects and the Moon's upper limb, −0.8333° for the Sun's centre (its upper limb); the refraction
 * allowance shrinks with the skyline altitude as the standard refraction does (0.09° at 10°, nil at 60°). The
 * Moon is visible once its upper limb clears the skyline, the Sun likewise. Refraction is the standard
 * atmosphere (1010 mbar, 10 °C), whatever the observer's weather.
 *
 * "Rising" below means **clearing the skyline** and "setting" means **sinking behind it**: the crossings are
 * [AltitudeCrossing]s and the first rise, transit and set a [RiseTransitSet] (whose `rise` is when the
 * target first clears the skyline in the interval and `set` when it first sinks behind it; `transit` is
 * the upper culmination, whether or not hidden then). `ALWAYS_UP` means visible throughout, `ALWAYS_DOWN`
 * hidden throughout.
 *
 * The searches work like [RiseSetCalculator]'s: the margin (altitude minus skyline threshold) is sampled
 * and its sign changes refined to a quarter of a second, with pairs of crossings between two samples (a target
 * that just grazes a ridge) found as well. A flat profile is sampled every 10 minutes, any other every
 * [fineSampleInterval] seconds (the target moves at most half a degree in that time), so features of the skyline
 * as small as its 1° resolution are seen; a change of visibility that lasts less than that and does not come
 * within 0.05° of a sample can be missed. A search over one night costs a few hundred margin evaluations, about
 * 0.1 ms for a fixed object and 0.2 ms for the Moon; the batch functions share the frames of the night between
 * objects.
 *
 * Immutable and thread safe: every call builds its own working state.
 *
 * @property profile The skyline.
 */
data class HorizonVisibility(val profile: HorizonProfile) {
    /** The geometric altitude thresholds at every whole degree of azimuth, and the first one again at 360°. */
    private val geometricThresholds: DoubleArray = DoubleArray(HorizonProfile.binCount + 1) {
        thresholdAltitude(profile.altitudes[it % HorizonProfile.binCount])
    }

    /** The apparent skyline altitudes, and the first one again at 360°. */
    private val apparentThresholds: DoubleArray = DoubleArray(HorizonProfile.binCount + 1) {
        profile.altitudes[it % HorizonProfile.binCount]
    }

    private val isFlat: Boolean = profile.isFlat

    // MARK: At one instant

    /**
     * How far above the skyline a target is, in degrees: its altitude minus the skyline threshold at
     * [azimuth]. Zero or more means visible. NaN when the numbers are not finite.
     *
     * @param altitude Altitude of the target's centre in degrees.
     * @param azimuth Azimuth in degrees (any angle; wrapped).
     * @param refracted `false` (default) when [altitude] is geometric, `true` when it is the apparent altitude
     * (as returned by `SkyFrame.horizontal(..., refraction = true)`).
     */
    fun margin(altitude: Double, azimuth: Double, refracted: Boolean = false): Double =
        altitude - lookupHorizonTable(
            if (refracted) apparentThresholds else geometricThresholds,
            AstroMath.normalizeDegrees(azimuth),
        )

    /** Whether a target at [altitude] and [azimuth] is above the skyline (`margin >= 0`); see [margin]. */
    fun isVisible(altitude: Double, azimuth: Double, refracted: Boolean = false): Boolean =
        margin(altitude, azimuth, refracted) >= 0

    /** Whether a fixed object (a J2000 [position] with optional [properMotion]) is above the skyline at [at]. */
    fun isVisible(
        position: EquatorialCoordinate,
        properMotion: ProperMotion? = null,
        observer: Observer,
        at: Instant,
    ): Boolean = visible(HorizonFixedTrack(HorizonSkyFrames(observer), position, properMotion), at, 0.0, false)

    /** Whether the Sun, the Moon or a planet is above the skyline at [at] (the Sun and the Moon by their upper limb). */
    fun isVisible(body: SolarSystemBody, observer: Observer, at: Instant): Boolean =
        visible(HorizonBodyTrack(HorizonSkyFrames(observer), body), at, offset(body), false)

    /**
     * Whether a target described by [position] is above the skyline at [at].
     *
     * @param position The target's horizontal coordinates at an instant: geometric, or apparent with [refracted].
     * @param refracted Whether [position] returns refracted (apparent) altitudes.
     */
    fun isVisible(position: (Instant) -> HorizontalCoordinate, at: Instant, refracted: Boolean = false): Boolean =
        visible(HorizonFunctionTrack(position), at, 0.0, refracted)

    // MARK: Clearing and sinking behind the skyline

    /**
     * Every instant in [interval] at which a fixed object clears the skyline (`RISING`) or sinks behind it
     * (`SETTING`), in time order.
     *
     * @param position The J2000 place of the object.
     * @param properMotion Its proper motion, if known.
     * @param minimumAltitude An altitude in degrees the object must also reach (the Tonight planner's
     * minimum); the crossings are those of "above the skyline and above [minimumAltitude]". The default,
     * [noMinimumAltitude], asks for the skyline only.
     */
    fun crossings(
        position: EquatorialCoordinate,
        properMotion: ProperMotion? = null,
        observer: Observer,
        interval: DateInterval,
        minimumAltitude: Double = noMinimumAltitude,
    ): List<AltitudeCrossing> =
        search(
            HorizonFixedTrack(HorizonSkyFrames(observer), position, properMotion),
            interval, 0.0, minimumAltitude, false, null, null,
        ).crossings

    /**
     * Every instant in [interval] at which the Sun, the Moon or a planet clears the skyline (`RISING`) or sinks
     * behind it (`SETTING`), in time order. The Sun and the Moon count by their upper limb. This is the call for
     * "the Moon rises behind the ridge at 23:40". See the fixed-object overload for [minimumAltitude].
     */
    fun crossings(
        body: SolarSystemBody,
        observer: Observer,
        interval: DateInterval,
        minimumAltitude: Double = noMinimumAltitude,
    ): List<AltitudeCrossing> =
        search(
            HorizonBodyTrack(HorizonSkyFrames(observer), body),
            interval, offset(body), minimumAltitude, false, null, null,
        ).crossings

    /**
     * Every instant in [interval] at which a target described by [position] clears the skyline or sinks behind
     * it. The function must be continuous in time. See the fixed-object overload for [minimumAltitude].
     *
     * @param position The target's horizontal coordinates at an instant: geometric, or apparent with [refracted].
     * @param refracted Whether [position] returns refracted (apparent) altitudes.
     * @param sampleInterval Seconds between samples, 1…600; `null` chooses 600 for a flat profile and
     * [fineSampleInterval] otherwise.
     * @param tolerance The resolution of the returned times in seconds (at least a millisecond).
     */
    fun crossings(
        position: (Instant) -> HorizontalCoordinate,
        interval: DateInterval,
        refracted: Boolean = false,
        minimumAltitude: Double = noMinimumAltitude,
        sampleInterval: Double? = null,
        tolerance: Double = RiseSetCalculator.defaultTolerance,
    ): List<AltitudeCrossing> =
        search(HorizonFunctionTrack(position), interval, 0.0, minimumAltitude, refracted, sampleInterval, tolerance)
            .crossings

    /**
     * The first time a fixed object clears the skyline, culminates and sinks behind it inside [interval]
     * (see the type documentation for the meaning of rise and set here).
     */
    fun riseTransitSet(
        position: EquatorialCoordinate,
        properMotion: ProperMotion? = null,
        observer: Observer,
        interval: DateInterval,
    ): RiseTransitSet =
        riseTransitSet(
            HorizonFixedTrack(HorizonSkyFrames(observer), position, properMotion), interval, 0.0, false, null, null,
        )

    /**
     * The first time the Sun, the Moon or a planet clears the skyline, culminates and sinks behind it inside
     * [interval]; the Sun and the Moon by their upper limb.
     */
    fun riseTransitSet(body: SolarSystemBody, observer: Observer, interval: DateInterval): RiseTransitSet =
        riseTransitSet(HorizonBodyTrack(HorizonSkyFrames(observer), body), interval, offset(body), false, null, null)

    /**
     * The first time a target described by [position] clears the skyline, culminates and sinks behind it inside
     * [interval]. See the generic [crossings] for the parameters.
     */
    fun riseTransitSet(
        position: (Instant) -> HorizontalCoordinate,
        interval: DateInterval,
        refracted: Boolean = false,
        sampleInterval: Double? = null,
        tolerance: Double = RiseSetCalculator.defaultTolerance,
    ): RiseTransitSet =
        riseTransitSet(HorizonFunctionTrack(position), interval, 0.0, refracted, sampleInterval, tolerance)

    // MARK: Visible windows

    /**
     * The stretches of [intervals] (for example tonight's darkness) in which a fixed object is above the
     * skyline and at least [minimumAltitude] high, sorted and without overlaps. Cost: about 0.07 ms for a
     * night.
     */
    fun visibleWindows(
        position: EquatorialCoordinate,
        properMotion: ProperMotion? = null,
        observer: Observer,
        intervals: List<DateInterval>,
        minimumAltitude: Double = noMinimumAltitude,
    ): List<DateInterval> =
        windows(
            HorizonFixedTrack(HorizonSkyFrames(observer), position, properMotion),
            intervals, 0.0, minimumAltitude, false, null, null,
        )

    /** The stretches of [interval] in which a fixed object is above the skyline; see the overload for several intervals. */
    fun visibleWindows(
        position: EquatorialCoordinate,
        properMotion: ProperMotion? = null,
        observer: Observer,
        interval: DateInterval,
        minimumAltitude: Double = noMinimumAltitude,
    ): List<DateInterval> = visibleWindows(position, properMotion, observer, listOf(interval), minimumAltitude)

    /**
     * The stretches of [intervals] in which the Sun, the Moon or a planet is above the skyline (see the
     * fixed-object overload).
     */
    fun visibleWindows(
        body: SolarSystemBody,
        observer: Observer,
        intervals: List<DateInterval>,
        minimumAltitude: Double = noMinimumAltitude,
    ): List<DateInterval> =
        windows(
            HorizonBodyTrack(HorizonSkyFrames(observer), body), intervals, offset(body), minimumAltitude, false, null, null,
        )

    /** The stretches of [interval] in which the Sun, the Moon or a planet is above the skyline. */
    fun visibleWindows(
        body: SolarSystemBody,
        observer: Observer,
        interval: DateInterval,
        minimumAltitude: Double = noMinimumAltitude,
    ): List<DateInterval> = visibleWindows(body, observer, listOf(interval), minimumAltitude)

    /**
     * The stretches of [intervals] in which a target described by [position] is above the skyline (see the
     * fixed-object overload and the generic [crossings] for the parameters).
     */
    fun visibleWindows(
        position: (Instant) -> HorizontalCoordinate,
        intervals: List<DateInterval>,
        refracted: Boolean = false,
        minimumAltitude: Double = noMinimumAltitude,
        sampleInterval: Double? = null,
        tolerance: Double = RiseSetCalculator.defaultTolerance,
    ): List<DateInterval> =
        windows(HorizonFunctionTrack(position), intervals, 0.0, minimumAltitude, refracted, sampleInterval, tolerance)

    /** The stretches of [interval] in which a target described by [position] is above the skyline. */
    fun visibleWindows(
        position: (Instant) -> HorizontalCoordinate,
        interval: DateInterval,
        refracted: Boolean = false,
        minimumAltitude: Double = noMinimumAltitude,
        sampleInterval: Double? = null,
        tolerance: Double = RiseSetCalculator.defaultTolerance,
    ): List<DateInterval> =
        visibleWindows(position, listOf(interval), refracted, minimumAltitude, sampleInterval, tolerance)

    /**
     * The visible windows of many fixed objects in the same [intervals], one list per object in the order given
     * (700 objects through a night take about a tenth of a second). The frames of the night are built once and
     * shared; a position that is not finite is never visible.
     *
     * @param positions The J2000 places of the objects.
     * @param properMotions The proper motion of each object in the order of [positions]; a missing or `null` entry
     * means none.
     */
    fun visibleWindows(
        positions: List<EquatorialCoordinate>,
        properMotions: List<ProperMotion?> = emptyList(),
        observer: Observer,
        intervals: List<DateInterval>,
        minimumAltitude: Double = noMinimumAltitude,
    ): List<List<DateInterval>> {
        val frames = HorizonSkyFrames(observer)
        val dark = IntervalSet.normalized(intervals)
        return positions.indices.map { index ->
            val position = positions[index]
            if (!position.unitVector.isFinite) return@map emptyList<DateInterval>()
            val motion = if (index < properMotions.size) properMotions[index] else null
            windows(HorizonFixedTrack(frames, position, motion), dark, 0.0, minimumAltitude, false, null, null)
        }
    }

    // MARK: Planner records

    /**
     * Rise, transit and set over the skyline during [night] and the usable windows in [darkness], as the
     * Tonight planner's [TargetVisibility] (see `NightPlanner.visibility` with a `horizon`).
     */
    internal fun targetVisibility(
        track: HorizonSkyTrack,
        night: DateInterval,
        darkness: List<DateInterval>,
        offset: Double,
        minimumAltitude: Double,
    ): TargetVisibility {
        val minimum = clampedMinimum(minimumAltitude)
        val skyline = search(track, night, offset, noMinimumAltitude, false, null, null)
        val culminations = culminations(track, night, RiseSetCalculator.defaultTolerance)
        val events = events(skyline, culminations.firstOrNull())
        val dark = IntervalSet.normalized(darkness)
        val windows = windows(track, dark, offset, minimum, false, null, null)
        if (dark.isEmpty()) return TargetVisibility(events, minimum, null, null, emptyList())

        // The best moment is the highest point while the target is usable; a target that never is is judged
        // by its highest point in the dark, hidden or not.
        val spans = if (windows.isEmpty()) dark else windows
        var bestTime: Instant? = null
        var bestAltitude = Double.NEGATIVE_INFINITY
        fun consider(time: Instant, altitude: Double) {
            if (altitude > bestAltitude) {
                bestAltitude = altitude
                bestTime = time
            }
        }
        for (span in spans) {
            for (time in listOf(span.start, span.end)) {
                track.evaluate(time.timeIntervalSinceReferenceDate, false)
                consider(time, track.altitude)
            }
            for (culmination in culminations) {
                if (culmination.time >= span.start && culmination.time <= span.end) {
                    consider(culmination.time, culmination.altitude)
                }
            }
        }
        val best = bestTime
        return TargetVisibility(
            riseTransitSet = events,
            minimumAltitude = minimum,
            maxAltitudeDuringDarkness = if (best != null) bestAltitude else null,
            bestTime = best,
            intervalsAboveMinimum = windows,
        )
    }

    /** The record of a target that has no usable position: it never rises. */
    internal fun neverVisible(minimumAltitude: Double): TargetVisibility =
        TargetVisibility(
            riseTransitSet = RiseTransitSet(
                rise = null,
                transit = null,
                set = null,
                transitAltitude = null,
                circumstance = RiseTransitSet.Circumstance.ALWAYS_DOWN,
            ),
            minimumAltitude = clampedMinimum(minimumAltitude),
            maxAltitudeDuringDarkness = null,
            bestTime = null,
            intervalsAboveMinimum = emptyList(),
        )

    private fun clampedMinimum(minimumAltitude: Double): Double =
        if (minimumAltitude.isFinite()) {
            AstroMath.clamp(minimumAltitude, -90.0, 90.0)
        } else {
            NightPlanner.defaultMinimumAltitude
        }

    // MARK: Search

    /** The crossings found in a search and whether the target was visible at the start. */
    private class Search(val crossings: List<AltitudeCrossing>, val visibleAtStart: Boolean) {
        val circumstance: RiseTransitSet.Circumstance
            get() = if (crossings.isNotEmpty()) {
                RiseTransitSet.Circumstance.NORMAL
            } else if (visibleAtStart) {
                RiseTransitSet.Circumstance.ALWAYS_UP
            } else {
                RiseTransitSet.Circumstance.ALWAYS_DOWN
            }
    }

    private fun visible(track: HorizonSkyTrack, at: Instant, offset: Double, refracted: Boolean): Boolean {
        val evaluate = HorizonMargin(
            track, if (refracted) apparentThresholds else geometricThresholds, offset, noMinimumAltitude, !isFlat,
        )
        return evaluate.margin(at.timeIntervalSinceReferenceDate) >= 0
    }

    private fun search(
        track: HorizonSkyTrack,
        interval: DateInterval,
        offset: Double,
        minimumAltitude: Double,
        refracted: Boolean,
        sampleInterval: Double?,
        tolerance: Double?,
    ): Search {
        val evaluate = HorizonMargin(
            track = track,
            table = if (refracted) apparentThresholds else geometricThresholds,
            offset = offset,
            minimumAltitude = minimumAltitude,
            wantsAzimuth = !isFlat,
        )
        val profile = RiseSetProfile(interval, step(sampleInterval), evaluate)
        val crossings = profile.crossings(0.0, resolution(tolerance), evaluate)
        return Search(crossings, profile.limbAltitudes[0] >= 0.0)
    }

    private fun windows(
        track: HorizonSkyTrack,
        intervals: List<DateInterval>,
        offset: Double,
        minimumAltitude: Double,
        refracted: Boolean,
        sampleInterval: Double?,
        tolerance: Double?,
    ): List<DateInterval> {
        val result = ArrayList<DateInterval>()
        for (interval in IntervalSet.normalized(intervals)) {
            val found = search(track, interval, offset, minimumAltitude, refracted, sampleInterval, tolerance)
            result.addAll(NightPlanner.upIntervals(found.crossings, found.circumstance, interval))
        }
        return IntervalSet.normalized(result)
    }

    private fun riseTransitSet(
        track: HorizonSkyTrack,
        interval: DateInterval,
        offset: Double,
        refracted: Boolean,
        sampleInterval: Double?,
        tolerance: Double?,
    ): RiseTransitSet {
        val found = search(track, interval, offset, noMinimumAltitude, refracted, sampleInterval, tolerance)
        val culmination = culminations(track, interval, resolution(tolerance)).firstOrNull()
        return events(found, culmination)
    }

    private fun events(found: Search, culmination: AltitudeCulmination?): RiseTransitSet =
        RiseTransitSet(
            rise = found.crossings.firstOrNull { it.kind == AltitudeCrossing.Kind.RISING }?.time,
            transit = culmination?.time,
            set = found.crossings.firstOrNull { it.kind == AltitudeCrossing.Kind.SETTING }?.time,
            transitAltitude = culmination?.altitude,
            circumstance = found.circumstance,
        )

    private fun culminations(track: HorizonSkyTrack, interval: DateInterval, tolerance: Double): List<AltitudeCulmination> {
        val evaluate = HorizonAltitude(track)
        val profile = RiseSetProfile(interval, RiseSetCalculator.maximumSampleInterval, evaluate)
        return profile.upperCulminations(tolerance, evaluate)
    }

    private fun step(sampleInterval: Double?): Double {
        val automatic = if (isFlat) RiseSetCalculator.maximumSampleInterval else fineSampleInterval
        if (sampleInterval == null || !sampleInterval.isFinite()) return automatic
        return AstroMath.clamp(sampleInterval, 1.0, RiseSetCalculator.maximumSampleInterval)
    }

    private fun resolution(tolerance: Double?): Double =
        if (tolerance != null && tolerance.isFinite()) swiftMax(tolerance, 0.001) else RiseSetCalculator.defaultTolerance

    /** The offset of a body's threshold from the standard one: the Sun's centre sits a semi-diameter lower. */
    private fun offset(body: SolarSystemBody): Double =
        if (body == SolarSystemBody.SUN) {
            RiseSetCalculator.sunHorizonAltitude - RiseSetCalculator.standardHorizonAltitude
        } else {
            0.0
        }

    companion object {
        /** The skyline-only value of the `minimumAltitude` parameters: no minimum altitude. */
        const val noMinimumAltitude: Double = -90.0

        /**
         * Seconds between samples when the profile is not flat: in that time a target moves at most half a
         * degree across the sky, so the 1° features of a skyline cannot slip between samples.
         */
        const val fineSampleInterval: Double = 120.0

        /** The standard refraction at the horizon (apparent altitude 0), in degrees, as `Refraction` computes it. */
        private val horizonRefraction: Double = Refraction.refractionForApparentAltitude(0.0)

        /**
         * The geometric altitude, in degrees, at which a target is seen at the apparent altitude
         * [skylineAltitude]: the horizon altitude of [RiseSetCalculator] (−0.5667°) for 0°, and the same
         * allowance scaled by the standard refraction at that altitude otherwise (0.09° at 10°), so that
         * `thresholdAltitude(0.0) == RiseSetCalculator.standardHorizonAltitude`. Below 0° the allowance stays
         * at −0.5667°. A skyline altitude that is not finite gives NaN.
         */
        fun thresholdAltitude(skylineAltitude: Double): Double {
            if (!skylineAltitude.isFinite()) return Double.NaN
            val ratio = Refraction.refractionForApparentAltitude(swiftMax(skylineAltitude, 0.0)) / horizonRefraction
            return skylineAltitude + RiseSetCalculator.standardHorizonAltitude * ratio
        }
    }
}

/**
 * Linear interpolation of a 361-entry table (one entry per whole degree of azimuth and the first repeated at
 * 360°) at [azimuth] in [0, 360); NaN for any other value.
 */
internal fun lookupHorizonTable(table: DoubleArray, azimuth: Double): Double {
    if (!(azimuth >= 0.0 && azimuth < 360.0)) return Double.NaN
    val lower = azimuth.toInt()
    val fraction = azimuth - lower.toDouble()
    val first = table[lower]
    return first + (table[lower + 1] - first) * fraction
}

/**
 * The margin of a track over the skyline as an altitude curve for [RiseSetProfile]: the altitude of the
 * centre plus the limb allowance minus the threshold at the track's azimuth (negative while hidden), and
 * with a minimum altitude the lower of that and the margin over the minimum.
 */
internal class HorizonMargin(
    private val track: HorizonSkyTrack,
    private val table: DoubleArray,
    private val offset: Double,
    private val minimumAltitude: Double,
    private val wantsAzimuth: Boolean,
) : RiseSetProfile.Evaluator {
    override fun sample(time: Double): RiseSetSample = RiseSetSample(margin(time))

    override fun centreAltitude(time: Double): Double = margin(time)

    override fun limbAltitude(time: Double): Double = margin(time)

    /** The margin at [time] (seconds since the Foundation reference date); NaN when the position is not finite. */
    fun margin(time: Double): Double {
        track.evaluate(time, wantsAzimuth)
        val altitude = track.altitude
        val overSkyline = altitude + track.limbAllowance - (offset + lookupHorizonTable(table, track.azimuth))
        if (minimumAltitude <= HorizonVisibility.noMinimumAltitude) return overSkyline
        return swiftMin(overSkyline, altitude - minimumAltitude)
    }
}

/** The altitude of a track's centre as an altitude curve for [RiseSetProfile] (culminations). */
internal class HorizonAltitude(private val track: HorizonSkyTrack) : RiseSetProfile.Evaluator {
    override fun sample(time: Double): RiseSetSample = RiseSetSample(centreAltitude(time))

    override fun centreAltitude(time: Double): Double {
        track.evaluate(time, false)
        return track.altitude
    }

    override fun limbAltitude(time: Double): Double = centreAltitude(time)
}
