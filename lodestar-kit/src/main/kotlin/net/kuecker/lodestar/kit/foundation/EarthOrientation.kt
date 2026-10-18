package net.kuecker.lodestar.kit.foundation

import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sin

/** Obliquity of the ecliptic. */
object Obliquity {
    /** Mean obliquity at J2000.0 in degrees (23° 26′ 21.448″). */
    const val j2000: Double = 23.0 + 26.0 / 60 + 21.448 / 3600

    /** Mean obliquity of date in degrees (IAU 1980, Meeus 22.2). */
    fun mean(jd: JulianDate): Double {
        val t = jd.centuriesTT
        val arcseconds = AstroMath.polynomial(t, 21.448, -46.8150, -0.00059, 0.001813)
        return 23.0 + 26.0 / 60 + arcseconds / 3600
    }
}

/**
 * Nutation in longitude (Δψ) and obliquity (Δε), IAU 1980 theory (Meeus, Table 22.A).
 *
 * @property longitude Nutation in longitude Δψ, degrees.
 * @property obliquity Nutation in obliquity Δε, degrees.
 */
@Serializable
data class Nutation(val longitude: Double, val obliquity: Double) {
    /**
     * Rotation from the mean equator & equinox of date to the true equator & equinox of date,
     * `N = R1(−ε) · R3(−Δψ) · R1(ε₀)`.
     */
    fun matrix(meanObliquity: Double): Matrix3 {
        val meanEpsilon = AstroMath.radians(meanObliquity)
        val trueEpsilon = AstroMath.radians(meanObliquity + obliquity)
        val psi = AstroMath.radians(longitude)
        return Matrix3.frameRotationX(-trueEpsilon) * Matrix3.frameRotationZ(-psi) * Matrix3.frameRotationX(meanEpsilon)
    }

    private class Term(
        val d: Double,
        val m: Double,
        val mPrime: Double,
        val f: Double,
        val omega: Double,
        val psi: Double,
        val psiT: Double,
        val epsilon: Double,
        val epsilonT: Double,
    )

    companion object {
        /** Evaluates the 63-term IAU 1980 series (accuracy ≈ 0.001″) at [jd] (Swift `init(julianDate:)`). */
        operator fun invoke(jd: JulianDate): Nutation {
            val t = jd.centuriesTT
            val d = AstroMath.radians(AstroMath.polynomial(t, 297.85036, 445_267.111_480, -0.001_914_2, 1.0 / 189_474))
            val m = AstroMath.radians(AstroMath.polynomial(t, 357.52772, 35_999.050_340, -0.000_160_3, -1.0 / 300_000))
            val mPrime = AstroMath.radians(
                AstroMath.polynomial(t, 134.96298, 477_198.867_398, 0.008_697_2, 1.0 / 56_250),
            )
            val f = AstroMath.radians(AstroMath.polynomial(t, 93.27191, 483_202.017_538, -0.003_682_5, 1.0 / 327_270))
            val omega = AstroMath.radians(
                AstroMath.polynomial(t, 125.04452, -1_934.136_261, 0.002_070_8, 1.0 / 450_000),
            )

            var deltaPsi = 0.0
            var deltaEpsilon = 0.0
            for (term in terms) {
                val argument = term.d * d + term.m * m + term.mPrime * mPrime + term.f * f + term.omega * omega
                deltaPsi += (term.psi + term.psiT * t) * sin(argument)
                deltaEpsilon += (term.epsilon + term.epsilonT * t) * cos(argument)
            }
            // Coefficients are in units of 0.0001″.
            return Nutation(deltaPsi / 10_000 / 3600, deltaEpsilon / 10_000 / 3600)
        }

        /**
         * Meeus, Astronomical Algorithms (2nd ed.), Table 22.A: multiples of D, M, M′, F, Ω and the
         * sine (Δψ) / cosine (Δε) coefficients in 0.0001″, with their T-rates.
         */
        private val terms: Array<Term> = arrayOf(
            Term(0.0, 0.0, 0.0, 0.0, 1.0, -171_996.0, -174.2, 92_025.0, 8.9),
            Term(-2.0, 0.0, 0.0, 2.0, 2.0, -13_187.0, -1.6, 5_736.0, -3.1),
            Term(0.0, 0.0, 0.0, 2.0, 2.0, -2_274.0, -0.2, 977.0, -0.5),
            Term(0.0, 0.0, 0.0, 0.0, 2.0, 2_062.0, 0.2, -895.0, 0.5),
            Term(0.0, 1.0, 0.0, 0.0, 0.0, 1_426.0, -3.4, 54.0, -0.1),
            Term(0.0, 0.0, 1.0, 0.0, 0.0, 712.0, 0.1, -7.0, 0.0),
            Term(-2.0, 1.0, 0.0, 2.0, 2.0, -517.0, 1.2, 224.0, -0.6),
            Term(0.0, 0.0, 0.0, 2.0, 1.0, -386.0, -0.4, 200.0, 0.0),
            Term(0.0, 0.0, 1.0, 2.0, 2.0, -301.0, 0.0, 129.0, -0.1),
            Term(-2.0, -1.0, 0.0, 2.0, 2.0, 217.0, -0.5, -95.0, 0.3),
            Term(-2.0, 0.0, 1.0, 0.0, 0.0, -158.0, 0.0, 0.0, 0.0),
            Term(-2.0, 0.0, 0.0, 2.0, 1.0, 129.0, 0.1, -70.0, 0.0),
            Term(0.0, 0.0, -1.0, 2.0, 2.0, 123.0, 0.0, -53.0, 0.0),
            Term(2.0, 0.0, 0.0, 0.0, 0.0, 63.0, 0.0, 0.0, 0.0),
            Term(0.0, 0.0, 1.0, 0.0, 1.0, 63.0, 0.1, -33.0, 0.0),
            Term(2.0, 0.0, -1.0, 2.0, 2.0, -59.0, 0.0, 26.0, 0.0),
            Term(0.0, 0.0, -1.0, 0.0, 1.0, -58.0, -0.1, 32.0, 0.0),
            Term(0.0, 0.0, 1.0, 2.0, 1.0, -51.0, 0.0, 27.0, 0.0),
            Term(-2.0, 0.0, 2.0, 0.0, 0.0, 48.0, 0.0, 0.0, 0.0),
            Term(0.0, 0.0, -2.0, 2.0, 1.0, 46.0, 0.0, -24.0, 0.0),
            Term(2.0, 0.0, 0.0, 2.0, 2.0, -38.0, 0.0, 16.0, 0.0),
            Term(0.0, 0.0, 2.0, 2.0, 2.0, -31.0, 0.0, 13.0, 0.0),
            Term(0.0, 0.0, 2.0, 0.0, 0.0, 29.0, 0.0, 0.0, 0.0),
            Term(-2.0, 0.0, 1.0, 2.0, 2.0, 29.0, 0.0, -12.0, 0.0),
            Term(0.0, 0.0, 0.0, 2.0, 0.0, 26.0, 0.0, 0.0, 0.0),
            Term(-2.0, 0.0, 0.0, 2.0, 0.0, -22.0, 0.0, 0.0, 0.0),
            Term(0.0, 0.0, -1.0, 2.0, 1.0, 21.0, 0.0, -10.0, 0.0),
            Term(0.0, 2.0, 0.0, 0.0, 0.0, 17.0, -0.1, 0.0, 0.0),
            Term(2.0, 0.0, -1.0, 0.0, 1.0, 16.0, 0.0, -8.0, 0.0),
            Term(-2.0, 2.0, 0.0, 2.0, 2.0, -16.0, 0.1, 7.0, 0.0),
            Term(0.0, 1.0, 0.0, 0.0, 1.0, -15.0, 0.0, 9.0, 0.0),
            Term(-2.0, 0.0, 1.0, 0.0, 1.0, -13.0, 0.0, 7.0, 0.0),
            Term(0.0, -1.0, 0.0, 0.0, 1.0, -12.0, 0.0, 6.0, 0.0),
            Term(0.0, 0.0, 2.0, -2.0, 0.0, 11.0, 0.0, 0.0, 0.0),
            Term(2.0, 0.0, -1.0, 2.0, 1.0, -10.0, 0.0, 5.0, 0.0),
            Term(2.0, 0.0, 1.0, 2.0, 2.0, -8.0, 0.0, 3.0, 0.0),
            Term(0.0, 1.0, 0.0, 2.0, 2.0, 7.0, 0.0, -3.0, 0.0),
            Term(-2.0, 1.0, 1.0, 0.0, 0.0, -7.0, 0.0, 0.0, 0.0),
            Term(0.0, -1.0, 0.0, 2.0, 2.0, -7.0, 0.0, 3.0, 0.0),
            Term(2.0, 0.0, 0.0, 2.0, 1.0, -7.0, 0.0, 3.0, 0.0),
            Term(2.0, 0.0, 1.0, 0.0, 0.0, 6.0, 0.0, 0.0, 0.0),
            Term(-2.0, 0.0, 2.0, 2.0, 2.0, 6.0, 0.0, -3.0, 0.0),
            Term(-2.0, 0.0, 1.0, 2.0, 1.0, 6.0, 0.0, -3.0, 0.0),
            Term(2.0, 0.0, -2.0, 0.0, 1.0, -6.0, 0.0, 3.0, 0.0),
            Term(2.0, 0.0, 0.0, 0.0, 1.0, -6.0, 0.0, 3.0, 0.0),
            Term(0.0, -1.0, 1.0, 0.0, 0.0, 5.0, 0.0, 0.0, 0.0),
            Term(-2.0, -1.0, 0.0, 2.0, 1.0, -5.0, 0.0, 3.0, 0.0),
            Term(-2.0, 0.0, 0.0, 0.0, 1.0, -5.0, 0.0, 3.0, 0.0),
            Term(0.0, 0.0, 2.0, 2.0, 1.0, -5.0, 0.0, 3.0, 0.0),
            Term(-2.0, 0.0, 2.0, 0.0, 1.0, 4.0, 0.0, 0.0, 0.0),
            Term(-2.0, 1.0, 0.0, 2.0, 1.0, 4.0, 0.0, 0.0, 0.0),
            Term(0.0, 0.0, 1.0, -2.0, 0.0, 4.0, 0.0, 0.0, 0.0),
            Term(-1.0, 0.0, 1.0, 0.0, 0.0, -4.0, 0.0, 0.0, 0.0),
            Term(-2.0, 1.0, 0.0, 0.0, 0.0, -4.0, 0.0, 0.0, 0.0),
            Term(1.0, 0.0, 0.0, 0.0, 0.0, -4.0, 0.0, 0.0, 0.0),
            Term(0.0, 0.0, 1.0, 2.0, 0.0, 3.0, 0.0, 0.0, 0.0),
            Term(0.0, 0.0, -2.0, 2.0, 2.0, -3.0, 0.0, 0.0, 0.0),
            Term(-1.0, -1.0, 1.0, 0.0, 0.0, -3.0, 0.0, 0.0, 0.0),
            Term(0.0, 1.0, 1.0, 0.0, 0.0, -3.0, 0.0, 0.0, 0.0),
            Term(0.0, -1.0, 1.0, 2.0, 2.0, -3.0, 0.0, 0.0, 0.0),
            Term(2.0, -1.0, -1.0, 2.0, 2.0, -3.0, 0.0, 0.0, 0.0),
            Term(0.0, 0.0, 3.0, 2.0, 2.0, -3.0, 0.0, 0.0, 0.0),
            Term(2.0, -1.0, 0.0, 2.0, 2.0, -3.0, 0.0, 0.0, 0.0),
        )
    }
}

/** General precession (IAU 1976, Lieske et al. 1977). */
object Precession {
    /**
     * Rotation taking J2000 mean equatorial vectors to the mean equator & equinox of [jd],
     * `P = R3(−z) · R2(θ) · R3(−ζ)` (Meeus 21.2 with the starting epoch at J2000).
     */
    fun matrixFromJ2000(jd: JulianDate): Matrix3 {
        val t = jd.centuriesTT
        val arcsecondsToRadians = 1 / AstroMath.arcsecondsPerRadian
        val zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * arcsecondsToRadians
        val z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * arcsecondsToRadians
        val theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * arcsecondsToRadians
        return Matrix3.frameRotationZ(-z) * Matrix3.frameRotationY(theta) * Matrix3.frameRotationZ(-zeta)
    }
}

/** Low-precision solar quantities (Meeus ch. 25, ≈ 0.01°), used for annual aberration. */
object SolarCoordinates {
    /** Mean anomaly of the Sun in degrees, [0, 360). */
    fun meanAnomaly(jd: JulianDate): Double {
        val t = jd.centuriesTT
        return AstroMath.normalizeDegrees(AstroMath.polynomial(t, 357.52911, 35_999.050_29, -0.000_153_7))
    }

    /** Geometric (true) ecliptic longitude of the Sun referred to the mean equinox of date, degrees. */
    fun geometricLongitude(jd: JulianDate): Double {
        val t = jd.centuriesTT
        val meanLongitude = AstroMath.polynomial(t, 280.46646, 36_000.769_83, 0.000_303_2)
        val m = AstroMath.radians(meanAnomaly(jd))
        val center = (1.914_602 - 0.004_817 * t - 0.000_014 * t * t) * sin(m) +
            (0.019_993 - 0.000_101 * t) * sin(2 * m) +
            0.000_289 * sin(3 * m)
        return AstroMath.normalizeDegrees(meanLongitude + center)
    }
}

/** Annual aberration of light caused by the Earth's orbital velocity. */
object Aberration {
    /** Constant of aberration κ in arcseconds. */
    const val constantArcseconds: Double = 20.49552

    /**
     * The Earth's orbital velocity as a fraction of the speed of light (β = v/c), expressed in the
     * J2000 equatorial frame. Includes the orbital-eccentricity terms (Meeus 23.3).
     */
    fun earthVelocity(jd: JulianDate): Vector3 {
        val t = jd.centuriesTT
        val sunLongitude = AstroMath.radians(SolarCoordinates.geometricLongitude(jd))
        val eccentricity = AstroMath.polynomial(t, 0.016_708_634, -0.000_042_037, -0.000_000_126_7)
        val perihelion = AstroMath.radians(AstroMath.polynomial(t, 102.937_35, 1.719_46, 0.000_46))
        val kappa = constantArcseconds / AstroMath.arcsecondsPerRadian

        // Velocity in the mean ecliptic & equinox of date.
        val ecliptic = Vector3(
            kappa * (sin(sunLongitude) - eccentricity * sin(perihelion)),
            -kappa * (cos(sunLongitude) - eccentricity * cos(perihelion)),
            0.0,
        )
        val meanEquatorialOfDate = Matrix3.rotationX(AstroMath.radians(Obliquity.mean(jd))) * ecliptic
        return Precession.matrixFromJ2000(jd).transposed * meanEquatorialOfDate
    }

    /**
     * Applies first-order aberration to a unit [direction]: the apparent direction is displaced
     * towards the observer's velocity. Returns a unit vector.
     */
    fun apply(direction: Vector3, earthVelocity: Vector3): Vector3 = (direction + earthVelocity).normalized

    /** Removes first-order aberration from an apparent unit direction (inverse of [apply]). */
    fun remove(apparentDirection: Vector3, earthVelocity: Vector3): Vector3 =
        (apparentDirection - earthVelocity).normalized
}
