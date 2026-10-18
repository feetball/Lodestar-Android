package net.kuecker.lodestar.kit.comets

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Two-body heliocentric motion of a small body from [SmallBodyElements], for every eccentricity.
 *
 * Kepler's equation is solved in universal variables (Stumpff functions; Bate, Mueller & White, *Fundamentals of
 * Astrodynamics*, §4.4; Vallado 2013, §2.3), starting at perihelion: with `χ` the universal anomaly, `α = (1 − e)/q`
 * and `z = αχ²`, the time since perihelion `Δt` satisfies `k·Δt = e·χ³·S(z) + q·χ`, the distance is
 * `r = q + e·χ²·C(z)` and the perifocal position is `x = q − χ²·C(z)`, `y = √(q(1 + e))·χ·(1 − z·S(z))`. One formula
 * covers ellipses, parabolas and hyperbolas without a special case near `e = 1`. The left-hand side increases
 * monotonically with `χ` (its derivative is `r ≥ q`) and `|χ| ≤ k|Δt|/q`, so a safeguarded Newton iteration
 * (bisection whenever a step leaves the bracket) always converges, typically in four to six steps.
 *
 * Elliptic orbits are reduced to |Δt| ≤ half a period first, so any date works; for parabolic and hyperbolic orbits
 * the date must be within [maximumUnboundSpanDays] of perihelion (the body is then far beyond Neptune anyway).
 * Planetary perturbations are not modelled: see [SmallBodyEphemeris] for the resulting error.
 */
object OrbitPropagator {
    /** The Gaussian gravitational constant k in AU^1.5 per day (IAU 1976; the Sun's GM is k² in AU³/day²). */
    const val gaussianGravitationalConstant: Double = 0.01720209895

    /** The largest |t − perihelion| in days for which a parabolic or hyperbolic orbit is evaluated (about 137 years). */
    const val maximumUnboundSpanDays: Double = 50_000.0

    private const val maximumIterations = 100
    private const val relativeTolerance = 1e-14

    /** Rotation from the J2000 mean ecliptic to the J2000 mean equator (the one the planetary theory uses). */
    private val eclipticToEquatorialJ2000: Matrix3 = Matrix3.rotationX(AstroMath.radians(Obliquity.j2000))

    /**
     * A position in the orbital plane: [x] towards perihelion, [y] 90° ahead in the direction of motion, and the
     * distance [r] from the Sun, all in AU.
     */
    internal class Perifocal(val x: Double, val y: Double, val r: Double)

    /**
     * The heliocentric position in AU at [julianDayTT] in the mean ecliptic and equinox of J2000.0, or `null`
     * if the date is not finite or too far from perihelion for an unbound orbit.
     */
    fun heliocentricEcliptic(elements: SmallBodyElements, julianDayTT: Double): Vector3? {
        val perifocal = perifocal(
            elements.perihelionDistanceAU,
            elements.eccentricity,
            julianDayTT - elements.perihelionJulianDayTT,
        ) ?: return null
        val cosI = AstroMath.cosDeg(elements.inclination)
        val sinI = AstroMath.sinDeg(elements.inclination)
        val cosNode = AstroMath.cosDeg(elements.longitudeOfAscendingNode)
        val sinNode = AstroMath.sinDeg(elements.longitudeOfAscendingNode)
        val cosPeri = AstroMath.cosDeg(elements.argumentOfPerihelion)
        val sinPeri = AstroMath.sinDeg(elements.argumentOfPerihelion)
        val px = cosNode * cosPeri - sinNode * sinPeri * cosI
        val py = sinNode * cosPeri + cosNode * sinPeri * cosI
        val pz = sinPeri * sinI
        val qx = -cosNode * sinPeri - sinNode * cosPeri * cosI
        val qy = -sinNode * sinPeri + cosNode * cosPeri * cosI
        val qz = cosPeri * sinI
        val position = Vector3(
            perifocal.x * px + perifocal.y * qx,
            perifocal.x * py + perifocal.y * qy,
            perifocal.x * pz + perifocal.y * qz,
        )
        return if (position.isFinite) position else null
    }

    /**
     * The heliocentric position in AU at [julianDayTT] in the mean equatorial frame of J2000.0 (the frame of
     * `BodyState.geocentricJ2000`), or `null` as for [heliocentricEcliptic].
     */
    fun heliocentricJ2000(elements: SmallBodyElements, julianDayTT: Double): Vector3? =
        heliocentricEcliptic(elements, julianDayTT)?.let { eclipticToEquatorialJ2000 * it }

    /** The distance from the Sun in AU at [julianDayTT]; `null` as for [heliocentricEcliptic]. Cheaper than the position. */
    fun heliocentricDistance(elements: SmallBodyElements, julianDayTT: Double): Double? =
        perifocal(
            elements.perihelionDistanceAU,
            elements.eccentricity,
            julianDayTT - elements.perihelionJulianDayTT,
        )?.r

    /** The Stumpff function `C(z) = (1 − cos √z) / z`, continued analytically to `z < 0` with the hyperbolic cosine. */
    internal fun stumpffC(z: Double): Double {
        if (abs(z) < 1.0) {
            var term = 0.5
            var sum = 0.5
            for (k in 1..13) {
                term *= -z / ((2.0 * k + 1) * (2.0 * k + 2))
                sum += term
            }
            return sum
        }
        if (z > 0) return (1 - cos(sqrt(z))) / z
        return (cosh(sqrt(-z)) - 1) / -z
    }

    /** The Stumpff function `S(z) = (√z − sin √z) / z^1.5`, continued analytically to `z < 0` with the hyperbolic sine. */
    internal fun stumpffS(z: Double): Double {
        if (abs(z) < 1.0) {
            var term = 1.0 / 6.0
            var sum = 1.0 / 6.0
            for (k in 1..13) {
                term *= -z / ((2.0 * k + 2) * (2.0 * k + 3))
                sum += term
            }
            return sum
        }
        if (z > 0) {
            val root = sqrt(z)
            return (root - sin(root)) / (root * root * root)
        }
        val root = sqrt(-z)
        return (sinh(root) - root) / (root * root * root)
    }

    /**
     * The orbital-plane position [days] after perihelion for a conic with perihelion distance [q] and eccentricity [e],
     * or `null` if an input is not finite, `q <= 0`, `e < 0`, or [days] is beyond [maximumUnboundSpanDays] for an
     * unbound orbit.
     */
    internal fun perifocal(q: Double, e: Double, days: Double): Perifocal? {
        if (!(q > 0 && e >= 0 && days.isFinite())) return null
        val k = gaussianGravitationalConstant
        var elapsed = days
        if (e < 1) {
            val a = q / (1 - e)
            val period = 2 * PI * a * sqrt(a) / k
            if (!period.isFinite()) return null
            elapsed -= floor(elapsed / period + 0.5) * period
        } else if (abs(elapsed) > maximumUnboundSpanDays) {
            return null
        }
        if (elapsed == 0.0) return Perifocal(q, 0.0, q)

        val alpha = (1 - e) / q
        val tau = k * abs(elapsed)
        var low = 0.0
        var high = tau / q
        var chi = initialGuess(q, e, tau)
        if (!(chi > low && chi < high)) chi = 0.5 * high
        for (iteration in 0 until maximumIterations) {
            val z = alpha * chi * chi
            val residual = e * chi * chi * chi * stumpffS(z) + q * chi - tau
            if (residual == 0.0) break
            if (residual > 0) high = chi else low = chi
            val derivative = e * chi * chi * stumpffC(z) + q
            var next = chi - residual / derivative
            if (!(next > low && next < high)) next = 0.5 * (low + high)
            val step = abs(next - chi)
            chi = next
            if (step <= relativeTolerance * chi) break
        }
        val z = alpha * chi * chi
        val c = stumpffC(z)
        val x = q - chi * chi * c
        val y = sqrt(q * (1 + e)) * chi * (1 - z * stumpffS(z))
        val r = e * chi * chi * c + q
        val signedY = if (elapsed < 0) -y else y
        if (!(x.isFinite() && signedY.isFinite() && r.isFinite())) return null
        return Perifocal(x, signedY, r)
    }

    /**
     * The root of Barker's cubic `e·χ³/6 + q·χ = τ` (the parabolic solution) for `e >= 0.1`, else the upper bound
     * `τ/q`; the start of the Newton iteration.
     */
    private fun initialGuess(q: Double, e: Double, tau: Double): Double {
        if (e < 0.1) return tau / q
        val p = 6 * q / e
        val c = 6 * tau / e
        val u = cbrt(c / 2 + sqrt(c * c / 4 + p * p * p / 27))
        return u - p / (3 * u)
    }
}
