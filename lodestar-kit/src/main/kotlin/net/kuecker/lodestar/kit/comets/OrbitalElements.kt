package net.kuecker.lodestar.kit.comets

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.PI
import kotlin.math.sqrt

/** The kind of a small solar-system body. */
enum class SmallBodyKind(val rawValue: String) {
    /** A comet (including the interstellar ones). */
    COMET("comet"),

    /** An asteroid (minor planet). */
    ASTEROID("asteroid"),
    ;

    /** The English name, e.g. "Comet". */
    val displayName: String
        get() = when (this) {
            COMET -> "Comet"
            ASTEROID -> "Asteroid"
        }

    companion object {
        /** The kind whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): SmallBodyKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** The shape of a heliocentric two-body orbit, from its eccentricity. */
enum class OrbitShape(val rawValue: String) {
    /** `e < 1`: a closed orbit that repeats. */
    ELLIPTIC("elliptic"),

    /** `e == 1` exactly. */
    PARABOLIC("parabolic"),

    /** `e > 1`: the body passes the Sun once and leaves the solar system. */
    HYPERBOLIC("hyperbolic"),
    ;

    companion object {
        /** The shape whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): OrbitShape? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Osculating heliocentric orbital elements referred to the mean ecliptic and equinox of J2000.0, the frame of the
 * Minor Planet Center and of the JPL Small-Body Database.
 *
 * The orbit is described the way comets are: by the perihelion distance `q` and the time of perihelion passage, which
 * works for every eccentricity. Asteroid elements (semi-major axis, mean anomaly at an epoch) are converted by
 * [fromMeanAnomaly]. Times are Julian Days in Terrestrial Time (TT; the difference to the TDB of JPL is below 2 ms).
 *
 * Instances come from [create] or [fromMeanAnomaly], which return `null` for values out of range.
 *
 * @property perihelionDistanceAU Perihelion distance q in AU, 0.00001 to 1000.
 * @property eccentricity Eccentricity e, 0 to 100 (`< 1` elliptic, `== 1` parabolic, `> 1` hyperbolic).
 * @property inclination Inclination i to the ecliptic in degrees, [0, 180].
 * @property argumentOfPerihelion Argument of perihelion ω in degrees, [0, 360).
 * @property longitudeOfAscendingNode Longitude of the ascending node Ω in degrees, [0, 360).
 * @property perihelionJulianDayTT Julian Day (TT) of a perihelion passage.
 * @property epochJulianDayTT Julian Day (TT) at which the elements are osculating; informational (it tells how far the
 * two-body motion is extrapolated), the position never depends on it.
 */
@ConsistentCopyVisibility
data class OrbitalElements private constructor(
    val perihelionDistanceAU: Double,
    val eccentricity: Double,
    val inclination: Double,
    val argumentOfPerihelion: Double,
    val longitudeOfAscendingNode: Double,
    val perihelionJulianDayTT: Double,
    val epochJulianDayTT: Double,
) {
    /** Elliptic, parabolic or hyperbolic. */
    val shape: OrbitShape
        get() = if (eccentricity < 1) OrbitShape.ELLIPTIC else if (eccentricity > 1) OrbitShape.HYPERBOLIC else OrbitShape.PARABOLIC

    /** Semi-major axis in AU; `null` unless the orbit is elliptic. */
    val semiMajorAxisAU: Double?
        get() = if (eccentricity < 1) perihelionDistanceAU / (1 - eccentricity) else null

    /** Aphelion distance in AU; `null` unless the orbit is elliptic. */
    val aphelionDistanceAU: Double?
        get() = semiMajorAxisAU?.let { it * (1 + eccentricity) }

    /** Mean motion in degrees per day; `null` unless the orbit is elliptic. */
    val meanMotion: Double?
        get() = semiMajorAxisAU?.let { AstroMath.degrees(OrbitPropagator.gaussianGravitationalConstant / (it * sqrt(it))) }

    /** Orbital period in days; `null` unless the orbit is elliptic. */
    val periodDays: Double?
        get() = semiMajorAxisAU?.let { 2 * PI * it * sqrt(it) / OrbitPropagator.gaussianGravitationalConstant }

    /** Orbital period in Julian years; `null` unless the orbit is elliptic. */
    val periodYears: Double? get() = periodDays?.let { it / 365.25 }

    /**
     * The mean anomaly in degrees, [0, 360), at [julianDayTT]; `null` unless the orbit is elliptic.
     */
    fun meanAnomaly(julianDayTT: Double): Double? =
        meanMotion?.let { AstroMath.normalizeDegrees(it * (julianDayTT - perihelionJulianDayTT)) }

    companion object {
        /** The smallest accepted perihelion distance, 0.00001 AU. */
        const val minimumPerihelionDistanceAU: Double = 0.00001

        /** The largest accepted perihelion distance, 1000 AU. */
        const val maximumPerihelionDistanceAU: Double = 1000.0

        /** The largest accepted eccentricity, 100. */
        const val maximumEccentricity: Double = 100.0

        /** The earliest accepted Julian Day, 0 (4713 BC). */
        const val minimumJulianDay: Double = 0.0

        /** The latest accepted Julian Day, 6 000 000 (about the year 11700). */
        const val maximumJulianDay: Double = 6_000_000.0

        /**
         * Creates elements from the comet-style description, or returns `null` if a value is not finite or out of range
         * (see the property documentation). Angles beyond [0, 360) are reduced.
         *
         * @param epochJulianDayTT The osculating epoch; defaults to the perihelion time.
         */
        fun create(
            perihelionDistanceAU: Double,
            eccentricity: Double,
            inclination: Double,
            argumentOfPerihelion: Double,
            longitudeOfAscendingNode: Double,
            perihelionJulianDayTT: Double,
            epochJulianDayTT: Double? = null,
        ): OrbitalElements? {
            val epoch = epochJulianDayTT ?: perihelionJulianDayTT
            if (!(perihelionDistanceAU >= minimumPerihelionDistanceAU && perihelionDistanceAU <= maximumPerihelionDistanceAU)) return null
            if (!(eccentricity >= 0 && eccentricity <= maximumEccentricity)) return null
            if (!(inclination >= 0 && inclination <= 180)) return null
            if (!(argumentOfPerihelion.isFinite() && longitudeOfAscendingNode.isFinite())) return null
            if (!(perihelionJulianDayTT >= minimumJulianDay && perihelionJulianDayTT <= maximumJulianDay)) return null
            if (!(epoch >= minimumJulianDay && epoch <= maximumJulianDay)) return null
            return OrbitalElements(
                perihelionDistanceAU = perihelionDistanceAU,
                eccentricity = eccentricity,
                inclination = inclination,
                argumentOfPerihelion = AstroMath.normalizeDegrees(argumentOfPerihelion),
                longitudeOfAscendingNode = AstroMath.normalizeDegrees(longitudeOfAscendingNode),
                perihelionJulianDayTT = perihelionJulianDayTT,
                epochJulianDayTT = epoch,
            )
        }

        /**
         * Creates elliptic elements from the asteroid-style description: semi-major axis and the mean anomaly at an
         * epoch. The perihelion time is the epoch minus the mean anomaly (reduced to ±180°) divided by the mean motion
         * `k / a^1.5`. Returns `null` unless `0 <= e < 1` and the other values are in range.
         *
         * @param meanAnomaly Mean anomaly at [epochJulianDayTT] in degrees (any finite value).
         */
        fun fromMeanAnomaly(
            semiMajorAxisAU: Double,
            eccentricity: Double,
            inclination: Double,
            argumentOfPerihelion: Double,
            longitudeOfAscendingNode: Double,
            meanAnomaly: Double,
            epochJulianDayTT: Double,
        ): OrbitalElements? {
            if (!(eccentricity >= 0 && eccentricity < 1)) return null
            if (!(semiMajorAxisAU > 0 && semiMajorAxisAU.isFinite() && meanAnomaly.isFinite())) return null
            val motion = AstroMath.degrees(OrbitPropagator.gaussianGravitationalConstant / (semiMajorAxisAU * sqrt(semiMajorAxisAU)))
            val signedAnomaly = AstroMath.normalizeSignedDegrees(meanAnomaly)
            return create(
                perihelionDistanceAU = semiMajorAxisAU * (1 - eccentricity),
                eccentricity = eccentricity,
                inclination = inclination,
                argumentOfPerihelion = argumentOfPerihelion,
                longitudeOfAscendingNode = longitudeOfAscendingNode,
                perihelionJulianDayTT = epochJulianDayTT - signedAnomaly / motion,
                epochJulianDayTT = epochJulianDayTT,
            )
        }
    }
}
