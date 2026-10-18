package net.kuecker.lodestar.kit.mosaic

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.CoordinateTransforms
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GalacticCoordinate
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.foundation.addingTimeInterval
import net.kuecker.lodestar.kit.planning.DarknessKind
import net.kuecker.lodestar.kit.planning.MilkyWayCoreVisibility
import net.kuecker.lodestar.kit.planning.NightSummary
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A point of the galactic plane.
 *
 * @property galacticLongitude The galactic longitude in degrees, [0, 360); 0° is the galactic centre.
 * @property horizontal Where the point stands in the sky.
 */
@ConsistentCopyVisibility
data class MilkyWayPoint internal constructor(
    val galacticLongitude: Double,
    val horizontal: HorizontalCoordinate,
)

/**
 * The Milky Way above the horizon at one moment.
 *
 * Created by [MilkyWayPanorama.arch] only.
 *
 * @property path The part of the galactic plane (galactic latitude 0) above [minimumAltitude] as a polyline in
 * azimuth and altitude, in order of increasing galactic longitude. Always one connected stretch: the plane is
 * a great circle, so half of it is up. Empty when none of it is.
 * @property highestPoint The highest point of [path], or null if the path is empty.
 * @property inclination The angle between the galactic plane and the horizon in degrees, [0, 90]: the altitude
 * of the highest point of a full arch. 0 means the plane lies along the horizon, 90 that it passes through the
 * zenith.
 * @property core Where the galactic centre (galactic longitude 0, latitude 0) stands, possibly below the horizon.
 * @property coreTilt The angle in degrees, [0, 90], between the galactic plane and the horizontal direction at
 * the galactic centre: 0 where the plane runs along the horizon there, 90 where it stands upright. Null when the
 * centre is within about 0.06 degrees of the zenith or the nadir, where "horizontal" has no direction.
 * @property minimumAltitude The altitude above which [path] was built, in degrees.
 */
@ConsistentCopyVisibility
data class MilkyWayArch internal constructor(
    val path: List<MilkyWayPoint>,
    val highestPoint: MilkyWayPoint?,
    val inclination: Double,
    val core: HorizontalCoordinate,
    val coreTilt: Double?,
    val minimumAltitude: Double,
) {
    /**
     * A horizontal panorama box that covers the part of the arch above [minimumAltitude], for
     * [MosaicPlanner.plan].
     *
     * The azimuth range runs from the arch's smallest to its largest azimuth, followed along the path so
     * that an arch passing near the zenith is measured correctly, and both are widened by [margin]. The
     * altitude range runs from the lowest to the highest point, widened by [margin] and limited to the sky.
     * The box is at least one degree wide and one degree high.
     *
     * @param minimumAltitude Points lower than this are left out, in degrees (not finite: 5°). Raising it
     * excludes the murky horizon and narrows the azimuth span.
     * @param margin The margin in degrees on every side, [0, 45] (not finite: 5°).
     * @return The region, or null if no point of the path is above [minimumAltitude].
     */
    fun panoramaRegion(
        minimumAltitude: Double = MilkyWayPanorama.defaultPanoramaMinimumAltitude,
        margin: Double = MilkyWayPanorama.defaultPanoramaMargin,
    ): MosaicRegion? {
        val floorAltitude = if (minimumAltitude.isFinite()) {
            AstroMath.clamp(minimumAltitude, -90.0, 90.0)
        } else {
            MilkyWayPanorama.defaultPanoramaMinimumAltitude
        }
        val border = if (margin.isFinite()) {
            AstroMath.clamp(margin, 0.0, 45.0)
        } else {
            MilkyWayPanorama.defaultPanoramaMargin
        }
        val points = path.filter { it.horizontal.altitude >= floorAltitude }
        if (points.isEmpty()) return null
        var unwrapped = points[0].horizontal.azimuth
        var lowestAzimuth = unwrapped
        var highestAzimuth = unwrapped
        var lowestAltitude = points[0].horizontal.altitude
        var highestAltitude = lowestAltitude
        for (index in 1 until points.size) {
            unwrapped += AstroMath.normalizeSignedDegrees(points[index].horizontal.azimuth - points[index - 1].horizontal.azimuth)
            lowestAzimuth = min(lowestAzimuth, unwrapped)
            highestAzimuth = max(highestAzimuth, unwrapped)
            lowestAltitude = min(lowestAltitude, points[index].horizontal.altitude)
            highestAltitude = max(highestAltitude, points[index].horizontal.altitude)
        }
        var lower = AstroMath.clamp(lowestAltitude - border, -90.0, 90.0)
        var upper = AstroMath.clamp(highestAltitude + border, -90.0, 90.0)
        if (upper - lower < 1.0) {
            upper = min(90.0, lower + 1.0)
            lower = upper - 1.0
        }
        return MosaicRegion.Horizontal(
            azimuthStart = AstroMath.normalizeDegrees(lowestAzimuth - border),
            azimuthSpan = min(360.0, max(1.0, (highestAzimuth - lowestAzimuth) + 2 * border)),
            minimumAltitude = lower,
            maximumAltitude = upper,
        )
    }
}

/**
 * A moment at which the Milky Way's core is well placed, with the state of the sky then.
 *
 * @property date The instant.
 * @property coreAltitude The geometric altitude of the galactic centre in degrees.
 * @property coreAzimuth The azimuth of the galactic centre in degrees from north through east.
 * @property coreTilt The tilt of the galactic plane at the centre, see [MilkyWayArch.coreTilt].
 * @property inclination The inclination of the galactic plane to the horizon, see [MilkyWayArch.inclination].
 */
@ConsistentCopyVisibility
data class MilkyWayMoment internal constructor(
    val date: Instant,
    val coreAltitude: Double,
    val coreAzimuth: Double,
    val coreTilt: Double?,
    val inclination: Double,
)

/**
 * The best moments of a night for the Milky Way's core and arch. Any of them is null when the core never
 * rises high enough in the time searched.
 *
 * @property coreHighest When the galactic centre is highest.
 * @property mostHorizontal When the galactic plane lies flattest along the horizon at the centre (smallest tilt).
 * @property mostVertical When the galactic plane stands steepest at the centre (largest tilt).
 */
@ConsistentCopyVisibility
data class MilkyWayBestTimes internal constructor(
    val coreHighest: MilkyWayMoment?,
    val mostHorizontal: MilkyWayMoment?,
    val mostVertical: MilkyWayMoment?,
)

/**
 * Helpers for photographing the Milky Way as a panorama: the arch above the horizon, a panorama box that
 * covers it, and the best times of a night.
 *
 * "Tilt" is measured at the galactic centre: the angle between the galactic plane's direction there and the
 * horizontal direction (the one along the horizon). Where the centre is up, a small tilt means the plane
 * runs along the horizon past it, as in the spring pre-dawn Milky Way that lies flat above the south-eastern
 * horizon, and a large tilt means the plane rises steeply from the horizon through it, as in the upright core of
 * an autumn evening.
 */
object MilkyWayPanorama {
    /** The default spacing of the sampled galactic longitudes in degrees. */
    const val defaultLongitudeStep: Double = 1.0

    /** The default lowest altitude of a panorama box in degrees: below it the haze hides the Milky Way. */
    const val defaultPanoramaMinimumAltitude: Double = 5.0

    /** The default margin around a panorama box in degrees. */
    const val defaultPanoramaMargin: Double = 5.0

    /** The sampling step of the best-time search in seconds. */
    internal const val searchStep: Double = 600.0

    /** The most samples the best-time search takes within one interval. */
    internal const val maximumSamplesPerInterval: Int = 5_000

    private val coreDirection: Vector3 = CoordinateTransforms.equatorialJ2000ToGalacticMatrix.r0

    private val planeTangent: Vector3 = CoordinateTransforms.equatorialJ2000ToGalacticMatrix.r1

    private val poleDirection: Vector3 = CoordinateTransforms.equatorialJ2000ToGalacticMatrix.r2

    /**
     * The Milky Way above the horizon for the instant and observer of [frame].
     *
     * Samples the galactic plane every [longitudeStep] degrees of galactic longitude (360 points by default),
     * so a call costs a few hundred horizontal conversions: well under a millisecond.
     *
     * @param frame The instant and observer.
     * @param minimumAltitude Points lower than this are left out of the path, in degrees, limited to [−90, 90]
     * (not finite: 0).
     * @param longitudeStep The spacing of the samples in degrees, limited to [0.25, 10] (not finite or not
     * positive: 1).
     * @param refraction Whether to raise the altitudes by atmospheric refraction.
     */
    fun arch(
        frame: SkyFrame,
        minimumAltitude: Double = 0.0,
        longitudeStep: Double = defaultLongitudeStep,
        refraction: Boolean = true,
    ): MilkyWayArch {
        val floorAltitude = if (minimumAltitude.isFinite()) AstroMath.clamp(minimumAltitude, -90.0, 90.0) else 0.0
        val step = if (longitudeStep.isFinite() && longitudeStep > 0.0) {
            AstroMath.clamp(longitudeStep, 0.25, 10.0)
        } else {
            defaultLongitudeStep
        }
        val count = ceil(360.0 / step - 1e-9).toInt()
        val samples = List(count) { index ->
            val longitude = index.toDouble() * step
            val place = CoordinateTransforms.galacticToEquatorialJ2000(GalacticCoordinate(longitude, 0.0))
            MilkyWayPoint(longitude, frame.horizontal(place, null, refraction))
        }
        val up = samples.map { it.horizontal.altitude >= floorAltitude }

        var first = -1
        for (index in 0 until count) {
            if (up[index] && !up[(index + count - 1) % count]) {
                first = index
                break
            }
        }
        val path = ArrayList<MilkyWayPoint>()
        if (first >= 0) {
            var index = first
            while (up[index % count] && path.size < count) {
                path.add(samples[index % count])
                index += 1
            }
        } else if (up.all { it }) {
            path.addAll(samples)
        }

        val normal = frame.horizontalVector(poleDirection).normalized
        val towardsZenith = Vector3(0.0, 0.0, 1.0) - normal * normal.z
        var highest: MilkyWayPoint? = null
        if (towardsZenith.length > 1e-9) {
            val geometric = HorizontalCoordinate(towardsZenith.normalized)
            val apparent = if (refraction) SkyFrame.refracted(geometric, frame.observer.atmosphere) else geometric
            if (apparent.altitude >= floorAltitude) {
                val galactic = CoordinateTransforms.equatorialJ2000ToGalactic(frame.j2000(geometric, false))
                highest = MilkyWayPoint(galactic.longitude, apparent)
            }
        } else {
            for (point in path) {
                val best = highest
                if (best == null || point.horizontal.altitude > best.horizontal.altitude) highest = point
            }
        }

        val core = frame.horizontal(EquatorialCoordinate(coreDirection), null, refraction)
        return MilkyWayArch(
            path = path,
            highestPoint = highest,
            inclination = AstroMath.acosDeg(abs(normal.z)),
            core = core,
            coreTilt = tilt(frame, frame.horizontalVector(coreDirection).normalized),
            minimumAltitude = floorAltitude,
        )
    }

    /**
     * The best moments for the Milky Way's core within a night, from its [NightSummary].
     *
     * @param summary The night.
     * @param darkness Which dark hours to search: astronomical darkness or the part with the Moon down.
     * @param minimumCoreAltitude The lowest altitude, in degrees, at which the core counts as usable.
     * @see bestTimes
     */
    fun bestTimes(
        summary: NightSummary,
        darkness: DarknessKind = DarknessKind.ASTRONOMICAL,
        minimumCoreAltitude: Double = MilkyWayCoreVisibility.minimumAltitude,
    ): MilkyWayBestTimes = bestTimes(summary.observer, summary.darkness(darkness), minimumCoreAltitude)

    /**
     * The best moments for the Milky Way's core within [intervals].
     *
     * The core is sampled every ten minutes and the best samples are refined by a golden-section search. A
     * moment only counts while the core is at least [minimumCoreAltitude] high. The three results are
     * - [MilkyWayBestTimes.coreHighest]: the greatest core altitude;
     * - [MilkyWayBestTimes.mostHorizontal]: the smallest tilt of the galactic plane at the core;
     * - [MilkyWayBestTimes.mostVertical]: the largest tilt.
     *
     * An hour of search costs about six conversions; a whole night, around a hundred microseconds each.
     *
     * @param observer The place of observation.
     * @param intervals The times to search, for example the dark hours of a night.
     * @param minimumCoreAltitude The lowest usable core altitude in degrees (not finite: 10).
     */
    fun bestTimes(
        observer: Observer,
        intervals: List<DateInterval>,
        minimumCoreAltitude: Double = MilkyWayCoreVisibility.minimumAltitude,
    ): MilkyWayBestTimes {
        val floorAltitude = if (minimumCoreAltitude.isFinite()) {
            AstroMath.clamp(minimumCoreAltitude, -90.0, 90.0)
        } else {
            MilkyWayCoreVisibility.minimumAltitude
        }
        val objectives = listOf(
            { state: CoreState -> if (state.altitude >= floorAltitude) state.altitude else Double.NEGATIVE_INFINITY },
            { state: CoreState ->
                val tilt = state.tilt
                if (state.altitude >= floorAltitude && tilt != null) -tilt else Double.NEGATIVE_INFINITY
            },
            { state: CoreState ->
                val tilt = state.tilt
                if (state.altitude >= floorAltitude && tilt != null) tilt else Double.NEGATIVE_INFINITY
            },
        )
        val moments = objectives.map { best(observer, intervals, it) }
        return MilkyWayBestTimes(coreHighest = moments[0], mostHorizontal = moments[1], mostVertical = moments[2])
    }

    // MARK: Search

    /** The state of the core and the plane at one instant. */
    internal class CoreState(
        val date: Instant,
        val altitude: Double,
        val azimuth: Double,
        val tilt: Double?,
        val inclination: Double,
    )

    internal fun state(observer: Observer, date: Instant): CoreState {
        val frame = SkyFrame(date, observer)
        val core = frame.horizontalVector(coreDirection).normalized
        val normal = frame.horizontalVector(poleDirection).normalized
        val horizontal = HorizontalCoordinate(core)
        return CoreState(
            date = date,
            altitude = horizontal.altitude,
            azimuth = horizontal.azimuth,
            tilt = tilt(frame, core),
            inclination = AstroMath.acosDeg(abs(normal.z)),
        )
    }

    /** The angle between the plane's direction at the core and the horizontal direction there; null near the zenith. */
    private fun tilt(frame: SkyFrame, core: Vector3): Double? {
        val horizontalLength = hypot(core.x, core.y)
        if (!(horizontalLength > 0.001)) return null
        val rotated = frame.j2000ToHorizontal * planeTangent
        val tangent = (rotated - core * rotated.dot(core)).normalized
        val level = Vector3(-core.y, core.x, 0.0) / horizontalLength
        return AstroMath.acosDeg(min(1.0, abs(tangent.dot(level))))
    }

    private fun best(observer: Observer, intervals: List<DateInterval>, score: (CoreState) -> Double): MilkyWayMoment? {
        var bestScore = Double.NEGATIVE_INFINITY
        var bestInterval: DateInterval? = null
        var bestOffset = 0.0
        var bestStep = 0.0
        for (interval in intervals) {
            val duration = interval.duration
            val count = if (duration > 0.0) {
                min(maximumSamplesPerInterval.toDouble(), ceil(duration / searchStep)).toInt()
            } else {
                0
            }
            val step = if (count > 0) duration / count.toDouble() else 0.0
            for (index in 0..count) {
                val offset = index.toDouble() * step
                val value = score(state(observer, interval.start.addingTimeInterval(offset)))
                if (value > bestScore) {
                    bestScore = value
                    bestInterval = interval
                    bestOffset = offset
                    bestStep = step
                }
            }
        }
        val interval = bestInterval ?: return null

        // Golden-section search for the maximum around the best sample.
        val ratio = 0.618_033_988_749_894_9
        var low = max(0.0, bestOffset - bestStep)
        var high = min(interval.duration, bestOffset + bestStep)
        val evaluate = { offset: Double -> score(state(observer, interval.start.addingTimeInterval(offset))) }
        var c = high - ratio * (high - low)
        var d = low + ratio * (high - low)
        var fc = evaluate(c)
        var fd = evaluate(d)
        repeat(40) {
            if (fc > fd) {
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
        val refined = (low + high) / 2
        val offset = if (evaluate(refined) >= bestScore) refined else bestOffset
        val result = state(observer, interval.start.addingTimeInterval(offset))
        return MilkyWayMoment(
            date = result.date,
            coreAltitude = result.altitude,
            coreAzimuth = result.azimuth,
            coreTilt = result.tilt,
            inclination = result.inclination,
        )
    }
}
