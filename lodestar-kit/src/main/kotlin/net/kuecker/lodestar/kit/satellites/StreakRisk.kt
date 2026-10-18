package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.PointingSolution
import java.time.Instant
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The camera's field of view on the sky, in the horizon (ENU) frame: a rectangle of a rectilinear lens.
 *
 * The frame has an optical axis ([boresight]), an upward and a rightward axis of the picture, and a width and
 * height in degrees (the angles across the sensor's width and height, as `FieldOfView` computes them). A
 * direction is in the frame when its gnomonic (tangent-plane) coordinates `x = (d·right)/(d·boresight)`,
 * `y = (d·up)/(d·boresight)` satisfy `|x| ≤ tan(width/2)` and `|y| ≤ tan(height/2)`.
 *
 * **Roll.** 0° means the picture's horizontal edges are level (the camera held in landscape orientation on a
 * level tripod head); a positive roll turns the camera clockwise as the photographer sees it, as in
 * `PointingSolution.roll`. A portrait frame is a landscape frame with swapped width and height or a roll of 90°.
 * Looking at the zenith or nadir, "level" has no meaning and the picture's up is north.
 *
 * @property boresight The optical axis as an ENU unit vector.
 * @property up The picture's upward direction, an ENU unit vector perpendicular to [boresight].
 * @property right The picture's rightward direction (`boresight × up`).
 * @property widthDegrees The angle across the picture's width.
 * @property heightDegrees The angle across the picture's height.
 */
@ConsistentCopyVisibility
data class CameraFrame private constructor(
    val boresight: Vector3,
    val up: Vector3,
    val right: Vector3,
    val widthDegrees: Double,
    val heightDegrees: Double,
) {
    /** `tan(width / 2)`: the half-width of the picture on the tangent plane. */
    internal val halfWidth: Double = tan(AstroMath.radians(widthDegrees) / 2.0)

    /** `tan(height / 2)`: the half-height of the picture on the tangent plane. */
    internal val halfHeight: Double = tan(AstroMath.radians(heightDegrees) / 2.0)

    /** The angle from the optical axis to a corner of the picture, in degrees. */
    val cornerAngle: Double get() = AstroMath.degrees(atan(sqrt(halfWidth * halfWidth + halfHeight * halfHeight)))

    /** The optical axis as azimuth and altitude. */
    val boresightCoordinate: HorizontalCoordinate get() = HorizontalCoordinate(boresight)

    /**
     * The position of a direction on the picture, `x` and `y` scaled so that the edges are at ±1 (`x` to the
     * right, `y` upward); `null` for a direction at or behind the plane of the camera.
     */
    fun position(direction: Vector3): FramePosition? {
        val depth = direction.dot(boresight)
        if (!(depth > 1.0e-9)) return null
        return FramePosition(direction.dot(right) / depth / halfWidth, direction.dot(up) / depth / halfHeight)
    }

    /** `true` when the direction (any non-zero ENU vector) is inside the picture. */
    fun contains(direction: Vector3): Boolean {
        val p = position(direction) ?: return false
        return p.x >= -1.0 && p.x <= 1.0 && p.y >= -1.0 && p.y <= 1.0
    }

    companion object {
        /** The largest accepted width or height, in degrees (a rectilinear lens cannot reach 180°). */
        const val maximumFieldOfView: Double = 170.0

        /**
         * Creates a frame looking along [boresight] with the given size and roll.
         *
         * @param widthDegrees Angle across the width, above 0 and at most [maximumFieldOfView].
         * @param heightDegrees Angle across the height, same range.
         * @param rollDegrees Clockwise roll of the camera in degrees (see the class description).
         * @return the frame, or `null` if a value is not finite or out of range.
         */
        fun create(
            boresight: HorizontalCoordinate,
            widthDegrees: Double,
            heightDegrees: Double,
            rollDegrees: Double = 0.0,
        ): CameraFrame? {
            if (!validSize(widthDegrees, heightDegrees) || !rollDegrees.isFinite()) return null
            val forward = boresight.unitVector
            var level = Vector3(0.0, 0.0, 1.0) - forward * forward.z
            if (level.length < 1.0e-6) level = Vector3(0.0, 1.0, 0.0) - forward * forward.y
            val upLevel = level.normalized
            val rightLevel = forward.cross(upLevel)
            val c = AstroMath.cosDeg(rollDegrees)
            val s = AstroMath.sinDeg(rollDegrees)
            val up = upLevel * c + rightLevel * s
            val right = rightLevel * c - upLevel * s
            return CameraFrame(forward, up, right, widthDegrees, heightDegrees)
        }

        /**
         * Creates a frame from the pointing the Aim screen measured (its optical axis, up and right vectors).
         *
         * @return the frame, or `null` if a size is not finite or out of range.
         */
        fun create(solution: PointingSolution, widthDegrees: Double, heightDegrees: Double): CameraFrame? {
            if (!validSize(widthDegrees, heightDegrees)) return null
            return CameraFrame(
                solution.boresightVector.normalized,
                solution.cameraUpVector.normalized,
                solution.cameraRightVector.normalized,
                widthDegrees,
                heightDegrees,
            )
        }

        private fun validSize(widthDegrees: Double, heightDegrees: Double): Boolean =
            widthDegrees.isFinite() && heightDegrees.isFinite() && widthDegrees > 0.0 && heightDegrees > 0.0 &&
                widthDegrees <= maximumFieldOfView && heightDegrees <= maximumFieldOfView
    }
}

/**
 * A position on the picture: `x` to the right and `y` upward, each −1 ... +1 from edge to edge inside the
 * frame.
 */
data class FramePosition(val x: Double, val y: Double)

/**
 * Where a satellite enters or leaves the picture.
 *
 * @property date The instant.
 * @property azimuth The satellite's azimuth in degrees.
 * @property altitude The satellite's geometric altitude in degrees.
 * @property position Its position on the picture (on the edge, ±1 in at least one coordinate; `null` if it could
 * not be computed).
 */
data class CrossingPoint(
    val date: Instant,
    val azimuth: Double,
    val altitude: Double,
    val position: FramePosition?,
)

/**
 * A sunlit satellite crossing the picture during an exposure.
 *
 * @property satellite The satellite.
 * @property entry Where the streak starts: the satellite enters the picture, rises above the horizon or leaves
 * the Earth's shadow, whichever happens last.
 * @property exit Where the streak ends.
 * @property startsBeforeExposure `true` when the streak is already under way when the exposure starts
 * ([entry] is then the exposure's start).
 * @property endsAfterExposure `true` when it continues after the exposure ends ([exit] is the exposure's end).
 * @property magnitude The apparent magnitude in the middle of the crossing, if the intrinsic magnitude is known.
 */
data class SatelliteCrossing(
    val satellite: Satellite,
    val entry: CrossingPoint,
    val exit: CrossingPoint,
    val startsBeforeExposure: Boolean,
    val endsAfterExposure: Boolean,
    val magnitude: Double?,
) {
    /** Seconds the satellite spends in the picture during the exposure. */
    val duration: Double get() = exit.date.timeIntervalSince(entry.date)
}

/**
 * Settings of [StreakRisk.assess].
 *
 * @property minimumAltitude Satellites below this geometric altitude (degrees) do not count as streaks; 0 is the
 * horizon.
 * @property summaryAltitude The altitude above which satellites are counted for
 * [StreakAssessment.sunlitAboveSummaryAltitudeCount] ("sunlit satellites above 20° right now").
 * @property rateWindow The time span in seconds over which [StreakAssessment.crossingsPerMinute] is estimated;
 * the exposure is used if it is longer.
 * @property limitingMagnitude When set, a crossing whose magnitude is known and fainter than this is dropped
 * (satellites with an unknown magnitude always stay).
 */
data class StreakOptions(
    val minimumAltitude: Double = 0.0,
    val summaryAltitude: Double = 20.0,
    val rateWindow: Double = 120.0,
    val limitingMagnitude: Double? = null,
)

/**
 * The streak risk of an exposure.
 *
 * @property crossings The sunlit satellites that cross the picture during the exposure, by entry time.
 * @property evaluatedCount How many satellites SGP4 could propagate to the start of the exposure.
 * @property aboveHorizonCount How many of them are above the horizon at the start.
 * @property sunlitAboveSummaryAltitudeCount How many are sunlit and higher than [StreakOptions.summaryAltitude]
 * at the start: the number to show as "N sunlit satellites above 20° right now".
 * @property crossingsPerMinute How many sunlit satellites cross the picture per minute, estimated from the
 * crossings that begin within [StreakOptions.rateWindow] (or the exposure) after the start.
 * @property rateWindow The span in seconds that estimate covers.
 */
data class StreakAssessment(
    val crossings: List<SatelliteCrossing>,
    val evaluatedCount: Int,
    val aboveHorizonCount: Int,
    val sunlitAboveSummaryAltitudeCount: Int,
    val crossingsPerMinute: Double,
    val rateWindow: Double,
) {
    /**
     * The chance of at least one streak in an exposure of [exposureSeconds], if crossings come at random at the
     * estimated rate: `1 - exp(-rate · t)`.
     */
    fun probabilityOfStreak(exposureSeconds: Double): Double {
        if (!exposureSeconds.isFinite() || exposureSeconds <= 0.0) return 0.0
        return 1.0 - exp(-crossingsPerMinute * exposureSeconds / 60.0)
    }
}

/**
 * Predicts which satellites will streak through a camera frame during an exposure, and how many to expect.
 *
 * **Method.** At the start of the exposure every satellite is propagated once and counted (above the horizon,
 * sunlit above 20°, ...). A *prefilter* then drops every satellite that cannot reach the frame, the sunlight and
 * the sky above the minimum altitude within the window: a satellite at range `ρ` moves at most `v·T` kilometres
 * in `T` seconds (`v` is an upper bound of its speed), so its direction changes by at most `asin(v·T/ρ)`. The
 * survivors are sampled on a step that moves them by at most half the frame's smaller side (never more than 3°),
 * and consecutive samples are joined by a straight line on the gnomonic picture plane (great circles are straight
 * there), clipped against the frame, the horizon and the shadow's edge, which gives entry and exit times to a
 * fraction of the step. The Sun comes from hourly nodes of the ephemeris.
 *
 * **Cost.** About 3 microseconds per satellite for the prefilter: 10 000 Starlink element sets take 30 to
 * 90 ms on a desktop and well under a second on a phone. The fine check adds about 1 microsecond per sample
 * and survivor; a very wide frame (a 14 mm lens) keeps a few hundred survivors for tens of milliseconds more.
 *
 * Set up the satellites once (`Satellite(...)` costs 3 microseconds each) and reuse the list.
 */
object StreakRisk {
    /** The most satellites one call evaluates. */
    const val maximumSatelliteCount: Int = 100_000

    /** The longest exposure window evaluated, in seconds. */
    const val maximumExposureSeconds: Double = 3_600.0

    /**
     * Evaluates [satellites] for an exposure.
     *
     * @param satellites The satellites to consider, e.g. a CelesTrak group.
     * @param observer The observer.
     * @param frame The camera frame.
     * @param exposure The exposure: its start and duration (clipped to [maximumExposureSeconds]).
     * @param options Thresholds and the window for the rate.
     */
    fun assess(
        satellites: List<Satellite>,
        observer: Observer,
        frame: CameraFrame,
        exposure: DateInterval,
        options: StreakOptions = StreakOptions(),
    ): StreakAssessment {
        val start = exposure.start.timeIntervalSinceReferenceDate
        val duration = if (exposure.duration.isFinite()) min(max(exposure.duration, 0.0), maximumExposureSeconds) else 0.0
        val rateWindow = if (options.rateWindow.isFinite()) {
            AstroMath.clamp(options.rateWindow, 1.0, maximumExposureSeconds)
        } else {
            120.0
        }
        val window = max(duration, rateWindow)
        val evaluator = StreakEvaluator(observer, frame, start, duration, window, options)
        var evaluated = 0
        var above = 0
        var sunlitAbove = 0
        val raw = ArrayList<RawCrossing>()
        val limit = min(satellites.size, maximumSatelliteCount)
        for (index in 0 until limit) {
            val outcome = evaluator.evaluate(satellites[index], raw)
            if (outcome.evaluated) evaluated += 1
            if (outcome.aboveHorizon) above += 1
            if (outcome.sunlitAboveSummaryAltitude) sunlitAbove += 1
        }

        val crossings = ArrayList<SatelliteCrossing>()
        var startedInWindow = 0
        val exposureEnd = start + duration
        for (item in raw) {
            if (item.start < start + rateWindow) startedInWindow += 1
            if (item.end < start || item.start > exposureEnd) continue
            val crossing = evaluator.crossing(item, exposureEnd) ?: continue
            crossings.add(crossing)
        }
        crossings.sortBy { it.entry.date }
        return StreakAssessment(
            crossings = crossings,
            evaluatedCount = evaluated,
            aboveHorizonCount = above,
            sunlitAboveSummaryAltitudeCount = sunlitAbove,
            crossingsPerMinute = startedInWindow * 60.0 / rateWindow,
            rateWindow = rateWindow,
        )
    }
}

/** A run of continuous visibility in the picture, in seconds since the reference date. */
internal class RawCrossing(val satellite: Satellite, val start: Double, val end: Double)

/** What the first look at a satellite found. */
internal class StreakOutcome(val evaluated: Boolean, val aboveHorizon: Boolean, val sunlitAboveSummaryAltitude: Boolean)

/** Evaluates satellites for one exposure; holds the Sun track and the observer's frame. */
internal class StreakEvaluator(
    private val observer: Observer,
    private val frame: CameraFrame,
    private val start: Double,
    private val duration: Double,
    private val window: Double,
    private val options: StreakOptions,
) {
    private val evaluator = SatelliteEvaluator(observer, SatelliteSunTrack.create(start, start + window))
    private val geometry = evaluator.geometry
    private val state = DoubleArray(6)
    private val minimumAltitude = if (options.minimumAltitude.isFinite()) options.minimumAltitude else 0.0
    private val summaryAltitude = if (options.summaryAltitude.isFinite()) options.summaryAltitude else 20.0
    private val gmstStart = evaluator.gmst(start)
    private val sunStart = evaluator.sunPosition(start)
    private val sunEnd = evaluator.sunPosition(start + window)
    private val gmstEnd = evaluator.gmst(start + window)
    private val cornerRadians = AstroMath.radians(frame.cornerAngle)
    private val smallestSide = min(frame.widthDegrees, frame.heightDegrees)
    private val sampleArc = AstroMath.clamp(0.5 * smallestSide, 0.2, 3.0)
    private val threshold = EarthShadow.sunlitThreshold

    fun evaluate(satellite: Satellite, output: MutableList<RawCrossing>): StreakOutcome {
        val propagator = satellite.propagator
        val minutes = (start - propagator.epochReferenceSeconds) / 60.0
        if (propagator.propagateRaw(minutes, state) != 0) return StreakOutcome(false, false, false)
        val topocentric = geometry.look(state[0], state[1], state[2], state[3], state[4], state[5], gmstStart)
        val altitude = topocentric.altitude
        val fraction = EarthShadow.illuminatedFraction(state[0], state[1], state[2], sunStart.x, sunStart.y, sunStart.z)
        val outcome = StreakOutcome(
            evaluated = true,
            aboveHorizon = altitude > 0.0,
            sunlitAboveSummaryAltitude = altitude >= summaryAltitude && fraction >= threshold,
        )

        // Prefilter: how far can the direction to the satellite turn within the window?
        val maximumSpeed = propagator.maximumSpeedKilometersPerSecond
        val reach = asin(min(1.0, maximumSpeed * window / topocentric.range))
        val direction = Vector3(topocentric.east, topocentric.north, topocentric.up) / topocentric.range
        val separation = acos(direction.dot(frame.boresight).coerceIn(-1.0, 1.0))
        if (separation - reach > cornerRadians) return outcome
        if (altitude + AstroMath.degrees(reach) < minimumAltitude) return outcome
        if (fraction < threshold && window <= shadowShortcutLimit) {
            // In shadow now: a low-orbit satellite is lit for an hour at a stretch, so one that is also in
            // shadow at the end of a short window cannot have been lit in between.
            val endMinutes = (start + window - propagator.epochReferenceSeconds) / 60.0
            if (propagator.propagateRaw(endMinutes, state) == 0) {
                val endFraction = EarthShadow.illuminatedFraction(state[0], state[1], state[2], sunEnd.x, sunEnd.y, sunEnd.z)
                if (endFraction < threshold) return outcome
            }
        }
        scan(satellite, topocentric, output)
        return outcome
    }

    /** Samples the satellite through the window and appends the runs inside the frame, above the horizon and lit. */
    private fun scan(satellite: Satellite, first: SatelliteTopocentric, output: MutableList<RawCrossing>) {
        val propagator = satellite.propagator
        val maximumSpeed = propagator.maximumSpeedKilometersPerSecond
        val lowestRange = max(first.range - maximumSpeed * window, minimumRangeKilometers)
        val maximumRate = AstroMath.degrees(maximumSpeed / lowestRange)
        val step = AstroMath.clamp(sampleArc / maximumRate, minimumStep, maximumStep)
        val count = max(1, ceil(window / step).toInt())
        val interval = window / count

        var previousX = 0.0
        var previousY = 0.0
        var previousValid = false
        var previousAltitude = 0.0
        var previousFraction = 0.0
        var runStart = Double.NaN
        var runEnd = Double.NaN
        val clipper = SegmentClipper()
        val restriction = LinearRestriction()
        for (index in 0..count) {
            val time = start + index * interval
            val minutes = (time - propagator.epochReferenceSeconds) / 60.0
            if (propagator.propagateRaw(minutes, state) != 0) {
                previousValid = false
                continue
            }
            val gmst = if (index == 0) gmstStart else if (index == count) gmstEnd else evaluator.gmst(time)
            val sun = if (index == 0) sunStart else evaluator.sunPosition(time)
            val topocentric = geometry.look(state[0], state[1], state[2], state[3], state[4], state[5], gmst)
            val altitude = topocentric.altitude
            val fraction = EarthShadow.illuminatedFraction(state[0], state[1], state[2], sun.x, sun.y, sun.z)
            val ex = topocentric.east / topocentric.range
            val ey = topocentric.north / topocentric.range
            val ez = topocentric.up / topocentric.range
            val depth = ex * frame.boresight.x + ey * frame.boresight.y + ez * frame.boresight.z
            val valid = depth > 1.0e-9
            val x = if (valid) (ex * frame.right.x + ey * frame.right.y + ez * frame.right.z) / depth / frame.halfWidth else 0.0
            val y = if (valid) (ex * frame.up.x + ey * frame.up.y + ez * frame.up.z) / depth / frame.halfHeight else 0.0

            if (index > 0 && valid && previousValid && clipper.clip(previousX, previousY, x, y)) {
                var low = clipper.low
                var high = clipper.high
                if (restriction.restrict(previousAltitude, altitude, minimumAltitude)) {
                    low = max(low, restriction.low)
                    high = min(high, restriction.high)
                    if (restriction.restrict(previousFraction, fraction, threshold)) {
                        low = max(low, restriction.low)
                        high = min(high, restriction.high)
                    } else {
                        high = -1.0
                    }
                } else {
                    high = -1.0
                }
                if (low <= high) {
                    val from = time - interval + low * interval
                    val to = time - interval + high * interval
                    if (!runStart.isNaN() && from <= runEnd + joinTolerance) {
                        runEnd = to
                    } else {
                        if (!runStart.isNaN()) output.add(RawCrossing(satellite, runStart, runEnd))
                        runStart = from
                        runEnd = to
                    }
                }
            }
            previousX = x
            previousY = y
            previousValid = valid
            previousAltitude = altitude
            previousFraction = fraction
        }
        if (!runStart.isNaN()) output.add(RawCrossing(satellite, runStart, runEnd))
    }

    /** Builds the reported crossing for the part of [raw] inside the exposure, or `null` if it is filtered out. */
    fun crossing(raw: RawCrossing, exposureEnd: Double): SatelliteCrossing? {
        val from = max(raw.start, start)
        val to = min(raw.end, exposureEnd)
        if (from > to) return null
        val middle = 0.5 * (from + to)
        val entry = point(raw.satellite, from) ?: return null
        val exit = point(raw.satellite, to) ?: return null
        val magnitude = if (raw.satellite.intrinsicMagnitude != null) {
            evaluator.look(raw.satellite, middle, state)?.magnitude
        } else {
            null
        }
        val limit = options.limitingMagnitude
        if (limit != null && magnitude != null && magnitude > limit) return null
        return SatelliteCrossing(
            satellite = raw.satellite,
            entry = entry,
            exit = exit,
            startsBeforeExposure = raw.start <= start + joinTolerance,
            endsAfterExposure = raw.end >= exposureEnd - joinTolerance,
            magnitude = magnitude,
        )
    }

    private fun point(satellite: Satellite, time: Double): CrossingPoint? {
        val look = evaluator.look(satellite, time, state) ?: return null
        return CrossingPoint(look.date, look.azimuth, look.altitude, frame.position(look.direction))
    }

    private companion object {
        const val minimumStep: Double = 0.05
        const val maximumStep: Double = 5.0
        const val minimumRangeKilometers: Double = 150.0
        const val joinTolerance: Double = 1.0e-6

        /** Windows up to this many seconds use the shadow shortcut of the prefilter. */
        const val shadowShortcutLimit: Double = 1_200.0
    }
}

/**
 * Clips segments against the square |x| ≤ 1, |y| ≤ 1 (Liang-Barsky) without allocating: [clip] leaves the
 * parameters of the part inside in [low] and [high].
 */
internal class SegmentClipper {
    var low: Double = 0.0
        private set
    var high: Double = 1.0
        private set

    /** Clips the segment (x0, y0) - (x1, y1); returns `false` if it lies outside the square. */
    fun clip(x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
        low = 0.0
        high = 1.0
        val dx = x1 - x0
        val dy = y1 - y0
        return edge(-dx, x0 + 1.0) && edge(dx, 1.0 - x0) && edge(-dy, y0 + 1.0) && edge(dy, 1.0 - y0)
    }

    private fun edge(p: Double, q: Double): Boolean {
        if (p == 0.0) return q >= 0.0
        val r = q / p
        if (p < 0.0) {
            if (r > high) return false
            if (r > low) low = r
        } else {
            if (r < low) return false
            if (r < high) high = r
        }
        return true
    }
}

/**
 * The part of a step on which a quantity that goes linearly from `from` to `to` is at least `limit`. [restrict]
 * leaves the parameters in [low] and [high] and returns `false` if there is none.
 */
internal class LinearRestriction {
    var low: Double = 0.0
        private set
    var high: Double = 1.0
        private set

    fun restrict(from: Double, to: Double, limit: Double): Boolean {
        if (from >= limit && to >= limit) {
            low = 0.0
            high = 1.0
            return true
        }
        if (from < limit && to < limit) return false
        val crossing = (limit - from) / (to - from)
        if (from < limit) {
            low = crossing
            high = 1.0
        } else {
            low = 0.0
            high = crossing
        }
        return true
    }
}
