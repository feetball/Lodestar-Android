package net.kuecker.lodestar.kit.foundation

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * Right ascension and declination, both in degrees.
 *
 * The frame (J2000 mean, or true equator and equinox of date) is implied by the API that
 * produces or consumes the value.
 *
 * Construction (`EquatorialCoordinate(rightAscension, declination)`) normalizes the right
 * ascension to [0, 360) and clamps the declination to [-90, 90]; deserialization, like Swift's
 * synthesized `Codable`, stores the decoded values as they are.
 *
 * @property rightAscension Right ascension in degrees, [0, 360).
 * @property declination Declination in degrees, [-90, 90].
 */
@Serializable
@ConsistentCopyVisibility
data class EquatorialCoordinate private constructor(
    val rightAscension: Double,
    val declination: Double,
) {
    /** Right ascension in hours, [0, 24). */
    val rightAscensionHours: Double get() = rightAscension / 15

    /** Unit vector: x towards the equinox, z towards the north celestial pole. */
    val unitVector: Vector3
        get() {
            val cd = AstroMath.cosDeg(declination)
            return Vector3(
                cd * AstroMath.cosDeg(rightAscension),
                cd * AstroMath.sinDeg(rightAscension),
                AstroMath.sinDeg(declination),
            )
        }

    /** Great-circle separation from [other] in degrees. */
    fun angularSeparation(other: EquatorialCoordinate): Double = unitVector.angle(other.unitVector)

    companion object {
        /** Creates a coordinate, normalizing [rightAscension] to [0, 360) and clamping [declination] to [-90, 90]. */
        operator fun invoke(rightAscension: Double, declination: Double): EquatorialCoordinate =
            EquatorialCoordinate(
                AstroMath.normalizeDegrees(rightAscension),
                AstroMath.clamp(declination, -90.0, 90.0),
            )

        /** Swift `init(rightAscensionHours:declination:)`: right ascension given in hours. */
        fun fromRightAscensionHours(rightAscensionHours: Double, declination: Double): EquatorialCoordinate =
            invoke(rightAscensionHours * 15, declination)

        /**
         * Creates the coordinate pointing along [vector] (any non-zero length).
         * A zero vector yields (0°, 0°).
         */
        operator fun invoke(vector: Vector3): EquatorialCoordinate {
            val r = vector.length
            if (!(r > 0)) return invoke(0.0, 0.0)
            return invoke(AstroMath.atan2Deg(vector.y, vector.x), AstroMath.asinDeg(vector.z / r))
        }
    }
}

/**
 * Azimuth and altitude in degrees for an observer on the Earth's surface.
 *
 * Azimuth is measured from true north towards east, [0, 360). The matching vector form is the
 * local East–North–Up (ENU) frame: x = East, y = North, z = Up.
 *
 * Construction (`HorizontalCoordinate(azimuth, altitude)`) normalizes the azimuth to [0, 360) and
 * clamps the altitude to [-90, 90]; deserialization stores the decoded values as they are.
 *
 * @property azimuth Azimuth in degrees from north through east, [0, 360).
 * @property altitude Altitude above the horizon in degrees, [-90, 90].
 */
@Serializable
@ConsistentCopyVisibility
data class HorizontalCoordinate private constructor(
    val azimuth: Double,
    val altitude: Double,
) {
    /** Unit vector in the ENU frame. */
    val unitVector: Vector3
        get() {
            val ca = AstroMath.cosDeg(altitude)
            return Vector3(
                ca * AstroMath.sinDeg(azimuth),
                ca * AstroMath.cosDeg(azimuth),
                AstroMath.sinDeg(altitude),
            )
        }

    /** Great-circle separation from [other] in degrees. */
    fun angularSeparation(other: HorizontalCoordinate): Double = unitVector.angle(other.unitVector)

    companion object {
        /** Creates a coordinate, normalizing [azimuth] to [0, 360) and clamping [altitude] to [-90, 90]. */
        operator fun invoke(azimuth: Double, altitude: Double): HorizontalCoordinate =
            HorizontalCoordinate(
                AstroMath.normalizeDegrees(azimuth),
                AstroMath.clamp(altitude, -90.0, 90.0),
            )

        /**
         * Creates the coordinate pointing along an ENU [vector] (any non-zero length).
         * A zero vector yields (0°, 0°). At the zenith/nadir the azimuth is 0°.
         */
        operator fun invoke(vector: Vector3): HorizontalCoordinate {
            val r = vector.length
            if (!(r > 0)) return invoke(0.0, 0.0)
            val horizontal = sqrt(vector.x * vector.x + vector.y * vector.y)
            val azimuth = if (horizontal > 0) AstroMath.atan2Deg(vector.x, vector.y) else 0.0
            return invoke(azimuth, AstroMath.asinDeg(vector.z / r))
        }
    }
}

/**
 * Ecliptic longitude and latitude in degrees.
 *
 * Construction (`EclipticCoordinate(longitude, latitude)`) normalizes the longitude to [0, 360) and
 * clamps the latitude to [-90, 90]; deserialization stores the decoded values as they are.
 *
 * @property longitude Ecliptic longitude in degrees, [0, 360).
 * @property latitude Ecliptic latitude in degrees, [-90, 90].
 */
@Serializable
@ConsistentCopyVisibility
data class EclipticCoordinate private constructor(
    val longitude: Double,
    val latitude: Double,
) {
    /** Unit vector: x towards the equinox, z towards the north ecliptic pole. */
    val unitVector: Vector3
        get() {
            val cb = AstroMath.cosDeg(latitude)
            return Vector3(
                cb * AstroMath.cosDeg(longitude),
                cb * AstroMath.sinDeg(longitude),
                AstroMath.sinDeg(latitude),
            )
        }

    companion object {
        /** Creates a coordinate, normalizing [longitude] to [0, 360) and clamping [latitude] to [-90, 90]. */
        operator fun invoke(longitude: Double, latitude: Double): EclipticCoordinate =
            EclipticCoordinate(
                AstroMath.normalizeDegrees(longitude),
                AstroMath.clamp(latitude, -90.0, 90.0),
            )

        /** Creates the coordinate pointing along [vector] (any non-zero length). A zero vector yields (0°, 0°). */
        operator fun invoke(vector: Vector3): EclipticCoordinate {
            val r = vector.length
            if (!(r > 0)) return invoke(0.0, 0.0)
            return invoke(AstroMath.atan2Deg(vector.y, vector.x), AstroMath.asinDeg(vector.z / r))
        }
    }
}

/**
 * Galactic longitude and latitude in degrees (IAU 1958 system, as realised by Hipparcos).
 *
 * Construction (`GalacticCoordinate(longitude, latitude)`) normalizes the longitude to [0, 360) and
 * clamps the latitude to [-90, 90]; deserialization stores the decoded values as they are.
 *
 * @property longitude Galactic longitude in degrees, [0, 360); 0° points to the galactic centre.
 * @property latitude Galactic latitude in degrees, [-90, 90].
 */
@Serializable
@ConsistentCopyVisibility
data class GalacticCoordinate private constructor(
    val longitude: Double,
    val latitude: Double,
) {
    /** Unit vector: x towards the galactic centre, z towards the north galactic pole. */
    val unitVector: Vector3
        get() = EclipticCoordinate(longitude, latitude).unitVector

    companion object {
        /** Creates a coordinate, normalizing [longitude] to [0, 360) and clamping [latitude] to [-90, 90]. */
        operator fun invoke(longitude: Double, latitude: Double): GalacticCoordinate =
            GalacticCoordinate(
                AstroMath.normalizeDegrees(longitude),
                AstroMath.clamp(latitude, -90.0, 90.0),
            )

        /** Creates the coordinate pointing along [vector] (any non-zero length). A zero vector yields (0°, 0°). */
        operator fun invoke(vector: Vector3): GalacticCoordinate {
            val ecliptic = EclipticCoordinate(vector)
            return invoke(ecliptic.longitude, ecliptic.latitude)
        }
    }
}

/**
 * A place on the Earth: geodetic latitude/longitude in degrees and elevation in metres.
 *
 * Construction (`GeographicLocation(latitude, longitude, elevation)`) clamps and wraps the
 * components into range (see [Companion.invoke]); use [Companion.validated] for user input.
 * Deserialization stores the decoded values as they are.
 *
 * @property latitude Geodetic latitude in degrees, north positive, [-90, 90].
 * @property longitude Longitude in degrees, east positive, (-180, 180].
 * @property elevation Height above sea level in metres.
 */
@Serializable
@ConsistentCopyVisibility
data class GeographicLocation private constructor(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double,
) {
    companion object {
        /** The range of accepted elevations in metres, -1000 to 12000. */
        val elevationRange: ClosedFloatingPointRange<Double> = -1_000.0..12_000.0

        /**
         * Creates a location, clamping the latitude, wrapping the longitude and clamping the
         * elevation into range. Non-finite components become 0. Use [validated] for user input.
         */
        operator fun invoke(latitude: Double, longitude: Double, elevation: Double = 0.0): GeographicLocation =
            GeographicLocation(
                if (latitude.isFinite()) AstroMath.clamp(latitude, -90.0, 90.0) else 0.0,
                if (longitude.isFinite()) AstroMath.normalizeSignedDegrees(longitude) else 0.0,
                if (elevation.isFinite()) {
                    AstroMath.clamp(elevation, elevationRange.start, elevationRange.endInclusive)
                } else {
                    0.0
                },
            )

        /**
         * Returns a location only if every component is finite and in range
         * (latitude [-90, 90], longitude [-180, 360], elevation within [elevationRange]).
         */
        fun validated(latitude: Double, longitude: Double, elevation: Double = 0.0): GeographicLocation? {
            if (!latitude.isFinite() || !longitude.isFinite() || !elevation.isFinite()) return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..360.0 || elevation !in elevationRange) return null
            return invoke(latitude, longitude, elevation)
        }
    }
}

/** Conversions between the classical spherical coordinate systems. */
object CoordinateTransforms {
    /**
     * A local hour angle and declination, both in degrees (the Swift tuple
     * `(hourAngle: Double, declination: Double)` returned by [horizontalToEquatorial]).
     *
     * @property hourAngle Local hour angle in degrees, (-180, 180].
     * @property declination Declination in degrees.
     */
    data class HourAngleDeclination(val hourAngle: Double, val declination: Double)

    /** Rotation from J2000 (ICRS) equatorial to galactic coordinates (Hipparcos, vol. 1, §1.5.3). */
    val equatorialJ2000ToGalacticMatrix: Matrix3 = Matrix3(
        Vector3(-0.054_875_560_416_215_4, -0.873_437_090_234_885_0, -0.483_835_015_548_713_2),
        Vector3(0.494_109_427_875_583_7, -0.444_829_629_960_011_2, 0.746_982_244_497_218_9),
        Vector3(-0.867_666_149_019_004_7, -0.198_076_373_431_201_5, 0.455_983_776_175_066_9),
    )

    /** Ecliptic → equatorial for the given obliquity (degrees). */
    fun eclipticToEquatorial(ecliptic: EclipticCoordinate, obliquity: Double): EquatorialCoordinate =
        EquatorialCoordinate(Matrix3.rotationX(AstroMath.radians(obliquity)) * ecliptic.unitVector)

    /** Equatorial → ecliptic for the given obliquity (degrees). */
    fun equatorialToEcliptic(equatorial: EquatorialCoordinate, obliquity: Double): EclipticCoordinate =
        EclipticCoordinate(Matrix3.rotationX(-AstroMath.radians(obliquity)) * equatorial.unitVector)

    /** Local hour angle and declination → horizontal coordinates (degrees). */
    fun equatorialToHorizontal(hourAngle: Double, declination: Double, latitude: Double): HorizontalCoordinate =
        HorizontalCoordinate(
            localEquatorialToHorizontal(latitude) * localEquatorialVector(hourAngle, declination),
        )

    /** Horizontal coordinates → local hour angle and declination (degrees). */
    fun horizontalToEquatorial(horizontal: HorizontalCoordinate, latitude: Double): HourAngleDeclination {
        val local = localEquatorialToHorizontal(latitude).transposed * horizontal.unitVector
        val hourAngle = AstroMath.normalizeSignedDegrees(AstroMath.atan2Deg(-local.y, local.x))
        return HourAngleDeclination(hourAngle, AstroMath.asinDeg(local.z))
    }

    /**
     * Unit vector in the local equatorial frame: x towards the meridian on the equator
     * (hour angle 0), y towards hour angle −6h (east), z towards the celestial pole.
     */
    internal fun localEquatorialVector(hourAngle: Double, declination: Double): Vector3 {
        val cd = AstroMath.cosDeg(declination)
        return Vector3(
            cd * AstroMath.cosDeg(hourAngle),
            -cd * AstroMath.sinDeg(hourAngle),
            AstroMath.sinDeg(declination),
        )
    }

    /** Rotation from the local equatorial frame (see [localEquatorialVector]) to ENU. */
    internal fun localEquatorialToHorizontal(latitude: Double): Matrix3 {
        val sp = AstroMath.sinDeg(latitude)
        val cp = AstroMath.cosDeg(latitude)
        return Matrix3(
            Vector3(0.0, 1.0, 0.0),
            Vector3(-sp, 0.0, cp),
            Vector3(cp, 0.0, sp),
        )
    }

    /** J2000 equatorial → galactic coordinates. */
    fun equatorialJ2000ToGalactic(equatorial: EquatorialCoordinate): GalacticCoordinate =
        GalacticCoordinate(equatorialJ2000ToGalacticMatrix * equatorial.unitVector)

    /** Galactic → J2000 equatorial coordinates. */
    fun galacticToEquatorialJ2000(galactic: GalacticCoordinate): EquatorialCoordinate =
        EquatorialCoordinate(equatorialJ2000ToGalacticMatrix.transposed * galactic.unitVector)
}
