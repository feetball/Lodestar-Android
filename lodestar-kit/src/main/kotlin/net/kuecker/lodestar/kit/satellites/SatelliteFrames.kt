package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.ephemeris.Ephemeris
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reference frames for satellites: SGP4's TEME frame, the Earth-fixed frame, the observer's horizon frame, and
 * the Sun's position in TEME.
 *
 * **TEME to Earth-fixed.** SGP4 works in TEME (true equator, mean equinox of date). The Earth-fixed frame used
 * here is the pseudo-Earth-fixed frame, reached by turning TEME about the polar axis by Greenwich *mean* sidereal
 * time (the IAU-82 expression of the reference code) with UT1 taken as UTC. Polar motion (below 0.3″, about
 * 10 m at low-orbit distances) and UT1 - UTC (below 0.9 s, up to 0.004° of rotation) are ignored; both are far
 * below the accuracy of the elements.
 *
 * **Observer.** Geodetic latitude, longitude and height on the WGS-84 ellipsoid become Earth-fixed coordinates;
 * azimuth is measured from north towards east and altitude from the local horizontal plane (the ellipsoid
 * normal, so geodetic latitude is the right one). No refraction, light time or aberration is applied: for a
 * satellite they are below 0.01°.
 *
 * **Sun.** The apparent geocentric position from the engine's ephemeris, rotated to TEME with precession,
 * nutation and the equation of the equinoxes.
 */
object SatelliteFrames {
    /** WGS-84 equatorial radius in kilometres. */
    const val earthEquatorialRadiusKilometers: Double = 6378.137

    /** WGS-84 flattening. */
    const val earthFlattening: Double = 1.0 / 298.257223563

    /** Earth's rotation rate in radians per second (the value the SGP4 reference code uses for TEME to ECEF). */
    const val earthRotationRate: Double = 7.29211514670698e-5

    /** Julian Date of the Foundation reference date, 2001-01-01T00:00:00Z. */
    internal const val referenceDateJulianDate: Double = 2_451_910.5

    /** Greenwich mean sidereal time at [date], radians in [0, 2π) (UT1 taken as UTC). */
    fun greenwichMeanSiderealTime(date: Instant): Double =
        Sgp4Time.greenwichSiderealTime(julianDateUt(date.timeIntervalSinceReferenceDate))

    /** Julian Date (UT) of a time given as seconds since the Foundation reference date. */
    internal fun julianDateUt(referenceSeconds: Double): Double =
        referenceDateJulianDate + referenceSeconds / JulianDate.secondsPerDay

    /**
     * Turns a TEME state into the Earth-fixed frame at [date]: the position is rotated about the pole by the
     * sidereal angle, the velocity is the velocity relative to the rotating Earth (the inertial velocity rotated,
     * minus ω × r).
     */
    fun earthFixedState(state: TemeState, date: Instant): TemeState {
        val angle = greenwichMeanSiderealTime(date)
        val c = cos(angle)
        val s = sin(angle)
        val r = state.position
        val v = state.velocity
        val x = c * r.x + s * r.y
        val y = -s * r.x + c * r.y
        return TemeState(
            Vector3(x, y, r.z),
            Vector3(c * v.x + s * v.y + earthRotationRate * y, -s * v.x + c * v.y - earthRotationRate * x, v.z),
        )
    }

    /** The Earth-fixed position of a place on the WGS-84 ellipsoid, in kilometres. */
    fun earthFixedPosition(location: GeographicLocation): Vector3 {
        val latitude = AstroMath.radians(location.latitude)
        val longitude = AstroMath.radians(location.longitude)
        val height = location.elevation / 1000.0
        val eccentricitySquared = earthFlattening * (2.0 - earthFlattening)
        val sinLatitude = sin(latitude)
        val primeVertical = earthEquatorialRadiusKilometers / sqrt(1.0 - eccentricitySquared * sinLatitude * sinLatitude)
        return Vector3(
            (primeVertical + height) * cos(latitude) * cos(longitude),
            (primeVertical + height) * cos(latitude) * sin(longitude),
            (primeVertical * (1.0 - eccentricitySquared) + height) * sinLatitude,
        )
    }

    /**
     * The Sun's geocentric apparent position in TEME at [date], in kilometres (about 1.5e8 long): the engine's
     * apparent place rotated with precession, nutation and the equation of the equinoxes.
     */
    fun sunPosition(date: Instant): Vector3 = SatelliteSunTrack.exact(date.timeIntervalSinceReferenceDate)

    /**
     * Where the satellite is in the sky: the TEME [state] at [date] seen from [observer].
     *
     * @return azimuth and geometric altitude in degrees, the range in kilometres and the range rate in
     * kilometres per second (positive when the satellite recedes).
     */
    fun look(state: TemeState, date: Instant, observer: Observer): SatelliteDirection {
        val geometry = SatelliteObserverGeometry(observer)
        val r = state.position
        val v = state.velocity
        val sample = geometry.look(
            r.x, r.y, r.z, v.x, v.y, v.z,
            Sgp4Time.greenwichSiderealTime(julianDateUt(date.timeIntervalSinceReferenceDate)),
        )
        return SatelliteDirection(
            azimuth = sample.azimuth,
            altitude = sample.altitude,
            rangeKilometers = sample.range,
            rangeRateKilometersPerSecond = sample.rangeRate,
        )
    }
}

/**
 * A satellite's direction and distance from an observer.
 *
 * @property azimuth Azimuth in degrees from north through east, [0, 360).
 * @property altitude Geometric altitude in degrees (no refraction), [-90, 90].
 * @property rangeKilometers Distance from the observer in kilometres.
 * @property rangeRateKilometersPerSecond Rate of change of the range in kilometres per second.
 */
data class SatelliteDirection(
    val azimuth: Double,
    val altitude: Double,
    val rangeKilometers: Double,
    val rangeRateKilometersPerSecond: Double,
) {
    /** The direction as a horizontal coordinate. */
    val horizontal: HorizontalCoordinate get() = HorizontalCoordinate(azimuth, altitude)

    /** The direction as an ENU unit vector (x east, y north, z up). */
    val unitVector: Vector3 get() = horizontal.unitVector
}

/** A satellite relative to an observer in the observer's East-North-Up frame. */
internal class SatelliteTopocentric(
    val east: Double,
    val north: Double,
    val up: Double,
    val range: Double,
    val rangeRate: Double,
    /** Speed relative to the rotating Earth in kilometres per second. */
    val relativeSpeed: Double,
) {
    /** Geometric altitude in degrees. */
    val altitude: Double get() = AstroMath.atan2Deg(up, sqrt(east * east + north * north))

    /** Azimuth in degrees, [0, 360). */
    val azimuth: Double
        get() = if (east == 0.0 && north == 0.0) 0.0 else AstroMath.normalizeDegrees(AstroMath.atan2Deg(east, north))
}

/** The observer's Earth-fixed position and local East-North-Up axes. Immutable. */
internal class SatelliteObserverGeometry(val observer: Observer) {
    val x: Double
    val y: Double
    val z: Double
    val eastX: Double
    val eastY: Double
    val northX: Double
    val northY: Double
    val northZ: Double
    val upX: Double
    val upY: Double
    val upZ: Double

    init {
        val position = SatelliteFrames.earthFixedPosition(observer.location)
        x = position.x
        y = position.y
        z = position.z
        val latitude = AstroMath.radians(observer.location.latitude)
        val longitude = AstroMath.radians(observer.location.longitude)
        val sinLatitude = sin(latitude)
        val cosLatitude = cos(latitude)
        val sinLongitude = sin(longitude)
        val cosLongitude = cos(longitude)
        eastX = -sinLongitude
        eastY = cosLongitude
        northX = -sinLatitude * cosLongitude
        northY = -sinLatitude * sinLongitude
        northZ = cosLatitude
        upX = cosLatitude * cosLongitude
        upY = cosLatitude * sinLongitude
        upZ = sinLatitude
    }

    /**
     * The satellite with TEME position (rx, ry, rz) km and velocity (vx, vy, vz) km/s, seen from the observer,
     * for Greenwich mean sidereal time [gmst] (radians).
     */
    fun look(rx: Double, ry: Double, rz: Double, vx: Double, vy: Double, vz: Double, gmst: Double): SatelliteTopocentric {
        val c = cos(gmst)
        val s = sin(gmst)
        val ex = c * rx + s * ry
        val ey = -s * rx + c * ry
        val dx = ex - x
        val dy = ey - y
        val dz = rz - z
        val east = eastX * dx + eastY * dy
        val north = northX * dx + northY * dy + northZ * dz
        val up = upX * dx + upY * dy + upZ * dz
        val range = sqrt(dx * dx + dy * dy + dz * dz)
        // Velocity relative to the rotating Earth.
        val wx = c * vx + s * vy + SatelliteFrames.earthRotationRate * ey
        val wy = -s * vx + c * vy - SatelliteFrames.earthRotationRate * ex
        val rangeRate = (dx * wx + dy * wy + dz * vz) / range
        return SatelliteTopocentric(east, north, up, range, rangeRate, sqrt(wx * wx + wy * wy + vz * vz))
    }

    /** The ENU unit vector towards a TEME direction (x, y, z not necessarily normalized) at sidereal angle [gmst]. */
    fun directionOf(tx: Double, ty: Double, tz: Double, gmst: Double): Vector3 {
        val c = cos(gmst)
        val s = sin(gmst)
        val ex = c * tx + s * ty
        val ey = -s * tx + c * ty
        val length = sqrt(ex * ex + ey * ey + tz * tz)
        return Vector3(
            (eastX * ex + eastY * ey) / length,
            (northX * ex + northY * ey + northZ * tz) / length,
            (upX * ex + upY * ey + upZ * tz) / length,
        )
    }
}

/**
 * The Sun's TEME position, exactly for one instant or interpolated over an interval: the engine's ephemeris is
 * evaluated at hourly nodes and a cubic Lagrange polynomial gives the values in between (the Sun moves 0.04° an
 * hour; the interpolation error is far below a milliarcsecond). Times are seconds since the Foundation
 * reference date. Immutable.
 */
internal class SatelliteSunTrack private constructor(private val origin: Double, private val nodes: Array<Vector3>) {
    /** The Sun's TEME position at [time] in kilometres (the time should lie in the interval given to [create]). */
    fun position(time: Double): Vector3 {
        val raw = floor((time - origin) / spacing).toInt()
        val base = raw.coerceIn(1, nodes.size - 3)
        val u = (time - origin) / spacing - base.toDouble()
        val w0 = -u * (u - 1) * (u - 2) / 6
        val w1 = (u + 1) * (u - 1) * (u - 2) / 2
        val w2 = -(u + 1) * u * (u - 2) / 2
        val w3 = (u + 1) * u * (u - 1) / 6
        val n0 = nodes[base - 1]
        val n1 = nodes[base]
        val n2 = nodes[base + 1]
        val n3 = nodes[base + 2]
        return Vector3(
            n0.x * w0 + n1.x * w1 + n2.x * w2 + n3.x * w3,
            n0.y * w0 + n1.y * w1 + n2.y * w2 + n3.y * w3,
            n0.z * w0 + n1.z * w1 + n2.z * w2 + n3.z * w3,
        )
    }

    companion object {
        /** Seconds between nodes. */
        const val spacing: Double = 3_600.0

        /** The most nodes a track holds (about 200 days). */
        const val maximumNodeCount: Int = 5_000

        /** The track for `[start, end]` (seconds since the reference date). */
        fun create(start: Double, end: Double): SatelliteSunTrack {
            val from = if (start.isFinite()) start else 0.0
            val to = if (end.isFinite()) max(end, from) else from
            val first = floor(from / spacing) * spacing - spacing
            val last = ceil(to / spacing) * spacing + 2 * spacing
            val count = min(max(5, ((last - first) / spacing).toInt() + 1), maximumNodeCount)
            val nodes = Array(count) { index -> exact(first + index * spacing) }
            return SatelliteSunTrack(first, nodes)
        }

        /** The Sun's TEME position at [time] in kilometres, from the ephemeris. */
        fun exact(time: Double): Vector3 {
            val jd = JulianDate(instantFromTimeIntervalSinceReferenceDate(time))
            val sunJ2000 = Ephemeris.geocentricJ2000(SolarSystemBody.SUN, jd)
            val nutation = Nutation(jd)
            val meanObliquity = Obliquity.mean(jd)
            val trueObliquity = meanObliquity + nutation.obliquity
            val equationOfTheEquinoxes = nutation.longitude * AstroMath.cosDeg(trueObliquity)
            val toTrueOfDate = nutation.matrix(meanObliquity) * Precession.matrixFromJ2000(jd)
            val toTeme = Matrix3.frameRotationZ(AstroMath.radians(equationOfTheEquinoxes)) * toTrueOfDate
            return (toTeme * sunJ2000) * SkyFrame.kilometersPerAU
        }
    }
}

/**
 * The Earth's shadow on a satellite.
 *
 * The model is conical: the Earth and the Sun are discs of apparent radius `b = asin(R⊕/r)` and
 * `a = asin(R☉/d)` as seen from the satellite, and the illuminated fraction of the Sun is the part of its disc
 * not covered by the Earth's disc (Montenbruck & Gill, *Satellite Orbits*, section 3.4.2). It is 1 in full
 * sunlight, 0 in the umbra, and in between in the penumbra, which at low-orbit altitudes lasts only a few seconds.
 * The Earth is a sphere of the WGS-84 equatorial radius; its atmosphere, which dims the sunlight a little
 * before the geometric shadow, is not modelled.
 *
 * A fraction of one half means the Earth's limb passes through the centre of the Sun's disc, which is the
 * criterion of a point-source (cylindrical) shadow; [isSunlit] uses it.
 */
object EarthShadow {
    /** Radius of the Sun in kilometres (IAU nominal). */
    const val sunRadiusKilometers: Double = 695_700.0

    /** The illuminated fraction at or above which [isSunlit] is `true`. */
    const val sunlitThreshold: Double = 0.5

    /**
     * The fraction of the Sun's disc visible from [satellite] (0 ... 1).
     *
     * @param satellite The satellite's geocentric position in kilometres (any inertial or Earth-fixed frame).
     * @param sun The Sun's geocentric position in kilometres, in the same frame.
     */
    fun illuminatedFraction(satellite: Vector3, sun: Vector3): Double =
        illuminatedFraction(satellite.x, satellite.y, satellite.z, sun.x, sun.y, sun.z)

    /** `true` when at least half of the Sun's disc is visible (see [illuminatedFraction]). */
    fun isSunlit(satellite: Vector3, sun: Vector3): Boolean =
        illuminatedFraction(satellite, sun) >= sunlitThreshold

    internal fun illuminatedFraction(
        sx: Double,
        sy: Double,
        sz: Double,
        px: Double,
        py: Double,
        pz: Double,
    ): Double {
        val distance = sqrt(sx * sx + sy * sy + sz * sz)
        if (!(distance > SatelliteFrames.earthEquatorialRadiusKilometers)) return 0.0
        val dx = px - sx
        val dy = py - sy
        val dz = pz - sz
        val sunDistance = sqrt(dx * dx + dy * dy + dz * dz)
        if (!(sunDistance > sunRadiusKilometers)) return 1.0
        val a = asin(sunRadiusKilometers / sunDistance)
        val b = asin(SatelliteFrames.earthEquatorialRadiusKilometers / distance)
        val cosine = -(sx * dx + sy * dy + sz * dz) / (distance * sunDistance)
        val c = acos(cosine.coerceIn(-1.0, 1.0))
        if (c >= a + b) return 1.0
        if (c <= b - a) return 0.0
        if (c <= a - b) return 1.0 - (b * b) / (a * a)
        val x = (c * c + a * a - b * b) / (2.0 * c)
        val y = sqrt(max(a * a - x * x, 0.0))
        val area = a * a * acos((x / a).coerceIn(-1.0, 1.0)) +
            b * b * acos(((c - x) / b).coerceIn(-1.0, 1.0)) - c * y
        return (1.0 - area / (PI * a * a)).coerceIn(0.0, 1.0)
    }
}

/** Vector helpers on plain components, so the sampling loops allocate nothing. */
internal object SatelliteVectorMath {
    /** The angle between two vectors in degrees. */
    fun angleBetween(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val cx = ay * bz - az * by
        val cy = az * bx - ax * bz
        val cz = ax * by - ay * bx
        return AstroMath.degrees(atan2(sqrt(cx * cx + cy * cy + cz * cz), ax * bx + ay * by + az * bz))
    }
}
