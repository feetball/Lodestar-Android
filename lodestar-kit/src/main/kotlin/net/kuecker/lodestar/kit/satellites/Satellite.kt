package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where a satellite is, and how it looks, at one instant for one observer.
 *
 * Created by [Satellite.look]. All angles are in degrees.
 *
 * @property date The instant.
 * @property azimuth Azimuth from true north through east, [0, 360).
 * @property altitude Geometric altitude above the horizon (no refraction), [-90, 90].
 * @property apparentAltitude Altitude as seen through the atmosphere of the observer's `Atmosphere`.
 * @property rangeKilometers Distance from the observer.
 * @property rangeRateKilometersPerSecond Rate of change of the range (positive when receding).
 * @property angularSpeed How fast the satellite moves across the sky, in degrees per second.
 * @property illuminatedFraction The visible fraction of the Sun's disc as seen from the satellite, 0 ... 1
 * ([EarthShadow]).
 * @property phaseAngle The Sun-satellite-observer angle, 0 (full) ... 180.
 * @property sunAltitude The geometric altitude of the Sun's centre for the observer.
 * @property magnitude The apparent magnitude ([SatelliteBrightness]); `null` when the intrinsic magnitude is
 * unknown or the satellite is not lit.
 * @property temeState The TEME position (km) and velocity (km/s) the look was computed from.
 */
data class SatelliteLook(
    val date: Instant,
    val azimuth: Double,
    val altitude: Double,
    val apparentAltitude: Double,
    val rangeKilometers: Double,
    val rangeRateKilometersPerSecond: Double,
    val angularSpeed: Double,
    val illuminatedFraction: Double,
    val phaseAngle: Double,
    val sunAltitude: Double,
    val magnitude: Double?,
    val temeState: TemeState,
) {
    /** The geometric direction as azimuth and altitude. */
    val horizontal: HorizontalCoordinate get() = HorizontalCoordinate(azimuth, altitude)

    /** The direction as the observer sees it, with refraction. Point the camera here. */
    val apparentHorizontal: HorizontalCoordinate get() = HorizontalCoordinate(azimuth, apparentAltitude)

    /** The geometric direction as an ENU unit vector (x east, y north, z up). */
    val direction: Vector3 get() = horizontal.unitVector

    /** The refracted direction as an ENU unit vector: the one a camera needs to point along. */
    val apparentDirection: Vector3 get() = apparentHorizontal.unitVector

    /** `true` when the satellite is above the geometric horizon. */
    val isAboveHorizon: Boolean get() = altitude > 0.0

    /** `true` when at least half of the Sun's disc is visible from the satellite ([EarthShadow.sunlitThreshold]). */
    val isSunlit: Boolean get() = illuminatedFraction >= EarthShadow.sunlitThreshold

    /** `true` when the Sun is below [darknessSunAltitude] (default -6°, the end of civil twilight). */
    fun observerIsDark(darknessSunAltitude: Double = SatellitePassFinder.defaultDarknessSunAltitude): Boolean =
        sunAltitude < darknessSunAltitude

    /** `true` when the satellite can be seen: above the horizon, sunlit, and the observer in darkness. */
    fun isVisible(darknessSunAltitude: Double = SatellitePassFinder.defaultDarknessSunAltitude): Boolean =
        isAboveHorizon && isSunlit && observerIsDark(darknessSunAltitude)
}

/**
 * The sky for one observer at one instant: the sidereal angle, the Sun and the observer's frame, computed once and
 * shared by many satellites ([Satellite.look]). Building one costs about 40 microseconds (mostly the Sun's
 * ephemeris); evaluating a satellite against it about 2 microseconds.
 *
 * @property date The instant.
 * @property observer The observer.
 */
class SatelliteSkyContext(val date: Instant, val observer: Observer) {
    internal val geometry: SatelliteObserverGeometry = SatelliteObserverGeometry(observer)
    internal val time: Double = date.timeIntervalSinceReferenceDate
    internal val gmst: Double = Sgp4Time.greenwichSiderealTime(SatelliteFrames.julianDateUt(time))

    /** The Sun's TEME position in kilometres. */
    val sunPosition: Vector3 = SatelliteSunTrack.exact(time)

    /** The geometric altitude of the Sun's centre for the observer, in degrees. */
    val sunAltitude: Double = AstroMath.asinDeg(geometry.directionOf(sunPosition.x, sunPosition.y, sunPosition.z, gmst).z)

    /** `true` when the Sun is below [darknessSunAltitude] (default -6°, the end of civil twilight). */
    fun isDark(darknessSunAltitude: Double = SatellitePassFinder.defaultDarknessSunAltitude): Boolean =
        sunAltitude < darknessSunAltitude
}

/**
 * A satellite: its element set, an SGP4 propagator and, when known, its intrinsic magnitude.
 *
 * Immutable and thread safe. Construction costs the propagator's initialization (about 3 microseconds). Equality
 * is identity.
 *
 * @property elements The element set.
 * @property intrinsicMagnitude The magnitude at 1000 km and 90° phase ([SatelliteBrightness]); `null` if unknown.
 * Defaults to the built-in value for the catalog number, if there is one.
 */
class Satellite(
    val elements: OrbitalElements,
    val intrinsicMagnitude: Double? = SatelliteBrightness.intrinsicMagnitude(elements.catalogNumber),
) {
    /** The SGP4 propagator for [elements]. */
    val propagator: Sgp4Propagator = Sgp4Propagator(elements)

    /** The object name, or "NORAD <number>" when the element set has none. */
    val name: String get() = elements.name ?: "NORAD ${elements.catalogNumber}"

    /** The NORAD catalog number. */
    val catalogNumber: Int get() = elements.catalogNumber

    /**
     * Where the satellite is in the sky and how it looks at [date], for [observer]. Builds a
     * [SatelliteSkyContext] (about 40 microseconds); use [look] with a context to evaluate many satellites at one
     * instant.
     *
     * @return the look, or `null` when SGP4 cannot propagate to [date] (a decayed satellite, a time too far
     * from the epoch).
     */
    fun look(date: Instant, observer: Observer): SatelliteLook? = look(SatelliteSkyContext(date, observer))

    /** [look] against a prepared sky. */
    fun look(context: SatelliteSkyContext): SatelliteLook? {
        val state = DoubleArray(6)
        return SatelliteEvaluator.look(this, context.geometry, context.time, context.gmst, context.sunPosition, context.sunAltitude, state)
    }

    /**
     * The looks at times from [start] to [end] every [step] seconds, for drawing a track or choosing where to
     * wait. Times where SGP4 fails are left out. At most 20 000 points and 30 days are produced; the Sun is
     * interpolated from hourly nodes.
     *
     * @param step Seconds between points, clamped to 0.1 ... 3600.
     */
    fun track(start: Instant, end: Instant, step: Double, observer: Observer): List<SatelliteLook> {
        val startTime = start.timeIntervalSinceReferenceDate
        val endTime = end.timeIntervalSinceReferenceDate
        if (!(endTime >= startTime) || !startTime.isFinite() || !endTime.isFinite()) return emptyList()
        val interval = if (step.isFinite()) AstroMath.clamp(step, 0.1, 3_600.0) else 1.0
        val points = minOf((endTime - startTime) / interval, maximumTrackPoints.toDouble(), maximumTrackDays * 86_400.0 / interval)
        val count = minOf(points.toInt() + 1, maximumTrackPoints)
        val evaluator = SatelliteEvaluator(observer, SatelliteSunTrack.create(startTime, startTime + (count - 1) * interval))
        val state = DoubleArray(6)
        val looks = ArrayList<SatelliteLook>(count)
        for (index in 0 until count) {
            val time = startTime + index * interval
            evaluator.look(this, time, state)?.let { looks.add(it) }
        }
        return looks
    }

    companion object {
        /** The most points [track] returns. */
        const val maximumTrackPoints: Int = 20_000

        /** The longest span [track] covers, in days. */
        const val maximumTrackDays: Double = 30.0
    }
}

/** Evaluates satellites for one observer; shares the observer geometry and the Sun between evaluations. */
internal class SatelliteEvaluator(val observer: Observer, private val sunTrack: SatelliteSunTrack?) {
    val geometry: SatelliteObserverGeometry = SatelliteObserverGeometry(observer)

    /** The Sun's TEME position at [time] (seconds since the reference date). */
    fun sunPosition(time: Double): Vector3 = sunTrack?.position(time) ?: SatelliteSunTrack.exact(time)

    /** The Sun's geometric altitude for the observer, in degrees. */
    fun sunAltitude(sun: Vector3, gmst: Double): Double =
        AstroMath.asinDeg(geometry.directionOf(sun.x, sun.y, sun.z, gmst).z)

    fun gmst(time: Double): Double = Sgp4Time.greenwichSiderealTime(SatelliteFrames.julianDateUt(time))

    /** The complete look of [satellite] at [time], or `null` if SGP4 fails. */
    fun look(satellite: Satellite, time: Double, state: DoubleArray): SatelliteLook? {
        val gmst = gmst(time)
        val sun = sunPosition(time)
        return look(satellite, geometry, time, gmst, sun, sunAltitude(sun, gmst), state)
    }

    companion object {
        fun look(
            satellite: Satellite,
            geometry: SatelliteObserverGeometry,
            time: Double,
            gmst: Double,
            sun: Vector3,
            sunAltitude: Double,
            state: DoubleArray,
        ): SatelliteLook? {
            val propagator = satellite.propagator
            val minutes = (time - propagator.epochReferenceSeconds) / 60.0
            if (propagator.propagateRaw(minutes, state) != 0) return null
            val topocentric = geometry.look(state[0], state[1], state[2], state[3], state[4], state[5], gmst)
            val fraction = EarthShadow.illuminatedFraction(state[0], state[1], state[2], sun.x, sun.y, sun.z)
            val sunDirection = geometry.directionOf(sun.x, sun.y, sun.z, gmst)
            val phase = SatelliteVectorMath.angleBetween(
                -topocentric.east, -topocentric.north, -topocentric.up,
                sunDirection.x, sunDirection.y, sunDirection.z,
            )
            val altitude = topocentric.altitude
            val across = sqrt(max(topocentric.relativeSpeed * topocentric.relativeSpeed - topocentric.rangeRate * topocentric.rangeRate, 0.0))
            val magnitude = satellite.intrinsicMagnitude?.let {
                SatelliteBrightness.apparentMagnitude(it, topocentric.range, phase, fraction)
            }
            return SatelliteLook(
                date = instantFromTimeIntervalSinceReferenceDate(time),
                azimuth = topocentric.azimuth,
                altitude = altitude,
                apparentAltitude = Refraction.apparentAltitude(altitude, geometry.observer.atmosphere),
                rangeKilometers = topocentric.range,
                rangeRateKilometersPerSecond = topocentric.rangeRate,
                angularSpeed = AstroMath.degrees(across / topocentric.range),
                illuminatedFraction = fraction,
                phaseAngle = phase,
                sunAltitude = sunAltitude,
                magnitude = magnitude,
                temeState = TemeState(Vector3(state[0], state[1], state[2]), Vector3(state[3], state[4], state[5])),
            )
        }
    }
}
