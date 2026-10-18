package net.kuecker.lodestar.kit.ephemeris

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Moon's geocentric position from the truncated ELP-2000/82 lunar theory of
 * Jean Meeus, *Astronomical Algorithms* (2nd ed., 1998), chapter 47: 60 periodic terms for
 * longitude and distance (Table 47.A), 60 for latitude (Table 47.B) and the additive
 * terms for Venus, Jupiter and the flattening of the Earth. Measured against JPL's DE440s over
 * 1950–2049 the direction is good to 8″ and the distance to 45 km (1.2×10⁻⁴). The coefficients
 * were checked row by row against two independent MIT-licensed implementations of the same
 * tables, `moonposition` in github.com/soniakeys/meeus and `moonposition.js` in
 * github.com/commenthol/astronomia.
 *
 * In Swift this type is `Ephemeris.LunarTheory`; Kotlin cannot add nested types from another file, so
 * it is a top-level object of the same package.
 */
internal object LunarTheory {
    /**
     * Geometric geocentric position, referred to the mean equinox and ecliptic of date.
     *
     * @property longitude Ecliptic longitude in degrees, [0, 360).
     * @property latitude Ecliptic latitude in degrees.
     * @property distanceKilometers Distance between the centres of the Earth and the Moon in kilometres.
     */
    data class Position(val longitude: Double, val latitude: Double, val distanceKilometers: Double)

    /**
     * The Moon's geocentric position in AU in the mean equatorial frame of J2000.0 as seen one
     * light-time ago (the Moon moves about 0.7″ while its light travels to the Earth).
     */
    fun geocentricJ2000(jd: JulianDate): Vector3 {
        val t = jd.centuriesTT
        val distanceAU = geocentricEcliptic(t).distanceKilometers / SkyFrame.kilometersPerAU
        val lightTimeCenturies = distanceAU * Ephemeris.lightTimePerAU / JulianDate.daysPerJulianCentury
        val position = geocentricEcliptic(t - lightTimeCenturies)
        val ecliptic = EclipticCoordinate(position.longitude, position.latitude).unitVector *
            (position.distanceKilometers / SkyFrame.kilometersPerAU)
        val meanEquatorialOfDate = Matrix3.rotationX(AstroMath.radians(Obliquity.mean(jd))) * ecliptic
        return Precession.matrixFromJ2000(jd).transposed * meanEquatorialOfDate
    }

    /**
     * The Moon's geometric position at [centuries] Julian centuries of TT since J2000.0, without light-time.
     *
     * The Swift parameter is `centuries t`; the Kotlin name keeps the label.
     */
    fun geocentricEcliptic(centuries: Double): Position {
        val t = centuries
        val meanLongitude = AstroMath.polynomial(
            t, 218.3164477, 481_267.881_234_21, -0.0015786, 1.0 / 538_841, -1.0 / 65_194_000,
        )
        val d = AstroMath.radians(
            AstroMath.polynomial(t, 297.8501921, 445_267.1114034, -0.0018819, 1.0 / 545_868, -1.0 / 113_065_000),
        )
        val m = AstroMath.radians(
            AstroMath.polynomial(t, 357.5291092, 35_999.0502909, -0.0001536, 1.0 / 24_490_000),
        )
        val mPrime = AstroMath.radians(
            AstroMath.polynomial(t, 134.9633964, 477_198.8675055, 0.0087414, 1.0 / 69_699, -1.0 / 14_712_000),
        )
        val f = AstroMath.radians(
            AstroMath.polynomial(t, 93.2720950, 483_202.0175233, -0.0036539, -1.0 / 3_526_000, 1.0 / 863_310_000),
        )
        val lPrime = AstroMath.radians(meanLongitude)
        val a1 = AstroMath.radians(119.75 + 131.849 * t)
        val a2 = AstroMath.radians(53.09 + 479_264.290 * t)
        val a3 = AstroMath.radians(313.45 + 481_266.484 * t)

        // Eccentricity of the Earth's orbit damps terms in the solar anomaly M.
        val e = 1.0 - 0.002516 * t - 0.0000074 * t * t
        val eSquared = e * e

        // The argument of a term: its integer multiples of D, M, M′ and F.
        fun combination(d1: Int, m1: Int, mPrime1: Int, f1: Int): Double =
            d1.toDouble() * d + m1.toDouble() * m + mPrime1.toDouble() * mPrime + f1.toDouble() * f

        var sumLongitude = 3958.0 * sin(a1) + 1962.0 * sin(lPrime - f) + 318.0 * sin(a2)
        var sumDistance = 0.0
        for (term in longitudeDistanceTerms) {
            val argument = combination(term.d, term.m, term.mPrime, term.f)
            val scale = eccentricityFactor(term.m, e, eSquared)
            sumLongitude += term.longitude * scale * sin(argument)
            sumDistance += term.distance * scale * cos(argument)
        }

        var sumLatitude = -2235.0 * sin(lPrime) + 382.0 * sin(a3) + 175.0 * sin(a1 - f) +
            175.0 * sin(a1 + f) + 127.0 * sin(lPrime - mPrime) - 115.0 * sin(lPrime + mPrime)
        for (term in latitudeTerms) {
            val argument = combination(term.d, term.m, term.mPrime, term.f)
            val scale = eccentricityFactor(term.m, e, eSquared)
            sumLatitude += term.latitude * scale * sin(argument)
        }

        // Sums are in millionths of a degree (longitude, latitude) and thousandths of a kilometre.
        return Position(
            longitude = AstroMath.normalizeDegrees(meanLongitude + sumLongitude / 1e6),
            latitude = sumLatitude / 1e6,
            distanceKilometers = 385_000.56 + sumDistance / 1e3,
        )
    }

    /**
     * SHA-256 of Tables 47.A and 47.B as lower-case hex (Kotlin addition, for tests): the number of rows, then per
     * row the four multipliers and the coefficients as IEEE 754 bits (big endian). The test compares it with the
     * digest of the rows in the Swift source.
     */
    val tableDigest: String
        get() {
            val digest = TableDigest()
            digest.add(longitudeDistanceTerms.size)
            for (term in longitudeDistanceTerms) {
                digest.add(term.d)
                digest.add(term.m)
                digest.add(term.mPrime)
                digest.add(term.f)
                digest.add(term.longitude)
                digest.add(term.distance)
            }
            digest.add(latitudeTerms.size)
            for (term in latitudeTerms) {
                digest.add(term.d)
                digest.add(term.m)
                digest.add(term.mPrime)
                digest.add(term.f)
                digest.add(term.latitude)
            }
            return digest.hex()
        }

    private fun eccentricityFactor(multiplier: Int, e: Double, eSquared: Double): Double =
        when (multiplier) {
            0 -> 1.0
            1, -1 -> e
            else -> eSquared
        }

    /**
     * A row of Table 47.A: multiples of D, M, M′ and F with the sine coefficient of the
     * longitude (10⁻⁶ degree) and the cosine coefficient of the distance (10⁻³ km).
     */
    private class PeriodicTerm(
        val d: Int,
        val m: Int,
        val mPrime: Int,
        val f: Int,
        val longitude: Double,
        val distance: Double,
    )

    /**
     * A row of Table 47.B: multiples of D, M, M′ and F with the sine coefficient of the
     * latitude (10⁻⁶ degree).
     */
    private class LatitudeTerm(
        val d: Int,
        val m: Int,
        val mPrime: Int,
        val f: Int,
        val latitude: Double,
    )

    /** Table 47.A. */
    private val longitudeDistanceTerms: Array<PeriodicTerm> = arrayOf(
        PeriodicTerm(0, 0, 1, 0, longitude = 6288774.0, distance = -20905355.0),
        PeriodicTerm(2, 0, -1, 0, longitude = 1274027.0, distance = -3699111.0),
        PeriodicTerm(2, 0, 0, 0, longitude = 658314.0, distance = -2955968.0),
        PeriodicTerm(0, 0, 2, 0, longitude = 213618.0, distance = -569925.0),
        PeriodicTerm(0, 1, 0, 0, longitude = -185116.0, distance = 48888.0),
        PeriodicTerm(0, 0, 0, 2, longitude = -114332.0, distance = -3149.0),
        PeriodicTerm(2, 0, -2, 0, longitude = 58793.0, distance = 246158.0),
        PeriodicTerm(2, -1, -1, 0, longitude = 57066.0, distance = -152138.0),
        PeriodicTerm(2, 0, 1, 0, longitude = 53322.0, distance = -170733.0),
        PeriodicTerm(2, -1, 0, 0, longitude = 45758.0, distance = -204586.0),
        PeriodicTerm(0, 1, -1, 0, longitude = -40923.0, distance = -129620.0),
        PeriodicTerm(1, 0, 0, 0, longitude = -34720.0, distance = 108743.0),
        PeriodicTerm(0, 1, 1, 0, longitude = -30383.0, distance = 104755.0),
        PeriodicTerm(2, 0, 0, -2, longitude = 15327.0, distance = 10321.0),
        PeriodicTerm(0, 0, 1, 2, longitude = -12528.0, distance = 0.0),
        PeriodicTerm(0, 0, 1, -2, longitude = 10980.0, distance = 79661.0),
        PeriodicTerm(4, 0, -1, 0, longitude = 10675.0, distance = -34782.0),
        PeriodicTerm(0, 0, 3, 0, longitude = 10034.0, distance = -23210.0),
        PeriodicTerm(4, 0, -2, 0, longitude = 8548.0, distance = -21636.0),
        PeriodicTerm(2, 1, -1, 0, longitude = -7888.0, distance = 24208.0),
        PeriodicTerm(2, 1, 0, 0, longitude = -6766.0, distance = 30824.0),
        PeriodicTerm(1, 0, -1, 0, longitude = -5163.0, distance = -8379.0),
        PeriodicTerm(1, 1, 0, 0, longitude = 4987.0, distance = -16675.0),
        PeriodicTerm(2, -1, 1, 0, longitude = 4036.0, distance = -12831.0),
        PeriodicTerm(2, 0, 2, 0, longitude = 3994.0, distance = -10445.0),
        PeriodicTerm(4, 0, 0, 0, longitude = 3861.0, distance = -11650.0),
        PeriodicTerm(2, 0, -3, 0, longitude = 3665.0, distance = 14403.0),
        PeriodicTerm(0, 1, -2, 0, longitude = -2689.0, distance = -7003.0),
        PeriodicTerm(2, 0, -1, 2, longitude = -2602.0, distance = 0.0),
        PeriodicTerm(2, -1, -2, 0, longitude = 2390.0, distance = 10056.0),
        PeriodicTerm(1, 0, 1, 0, longitude = -2348.0, distance = 6322.0),
        PeriodicTerm(2, -2, 0, 0, longitude = 2236.0, distance = -9884.0),
        PeriodicTerm(0, 1, 2, 0, longitude = -2120.0, distance = 5751.0),
        PeriodicTerm(0, 2, 0, 0, longitude = -2069.0, distance = 0.0),
        PeriodicTerm(2, -2, -1, 0, longitude = 2048.0, distance = -4950.0),
        PeriodicTerm(2, 0, 1, -2, longitude = -1773.0, distance = 4130.0),
        PeriodicTerm(2, 0, 0, 2, longitude = -1595.0, distance = 0.0),
        PeriodicTerm(4, -1, -1, 0, longitude = 1215.0, distance = -3958.0),
        PeriodicTerm(0, 0, 2, 2, longitude = -1110.0, distance = 0.0),
        PeriodicTerm(3, 0, -1, 0, longitude = -892.0, distance = 3258.0),
        PeriodicTerm(2, 1, 1, 0, longitude = -810.0, distance = 2616.0),
        PeriodicTerm(4, -1, -2, 0, longitude = 759.0, distance = -1897.0),
        PeriodicTerm(0, 2, -1, 0, longitude = -713.0, distance = -2117.0),
        PeriodicTerm(2, 2, -1, 0, longitude = -700.0, distance = 2354.0),
        PeriodicTerm(2, 1, -2, 0, longitude = 691.0, distance = 0.0),
        PeriodicTerm(2, -1, 0, -2, longitude = 596.0, distance = 0.0),
        PeriodicTerm(4, 0, 1, 0, longitude = 549.0, distance = -1423.0),
        PeriodicTerm(0, 0, 4, 0, longitude = 537.0, distance = -1117.0),
        PeriodicTerm(4, -1, 0, 0, longitude = 520.0, distance = -1571.0),
        PeriodicTerm(1, 0, -2, 0, longitude = -487.0, distance = -1739.0),
        PeriodicTerm(2, 1, 0, -2, longitude = -399.0, distance = 0.0),
        PeriodicTerm(0, 0, 2, -2, longitude = -381.0, distance = -4421.0),
        PeriodicTerm(1, 1, 1, 0, longitude = 351.0, distance = 0.0),
        PeriodicTerm(3, 0, -2, 0, longitude = -340.0, distance = 0.0),
        PeriodicTerm(4, 0, -3, 0, longitude = 330.0, distance = 0.0),
        PeriodicTerm(2, -1, 2, 0, longitude = 327.0, distance = 0.0),
        PeriodicTerm(0, 2, 1, 0, longitude = -323.0, distance = 1165.0),
        PeriodicTerm(1, 1, -1, 0, longitude = 299.0, distance = 0.0),
        PeriodicTerm(2, 0, 3, 0, longitude = 294.0, distance = 0.0),
        PeriodicTerm(2, 0, -1, -2, longitude = 0.0, distance = 8752.0),
    )

    /** Table 47.B. */
    private val latitudeTerms: Array<LatitudeTerm> = arrayOf(
        LatitudeTerm(0, 0, 0, 1, latitude = 5128122.0),
        LatitudeTerm(0, 0, 1, 1, latitude = 280602.0),
        LatitudeTerm(0, 0, 1, -1, latitude = 277693.0),
        LatitudeTerm(2, 0, 0, -1, latitude = 173237.0),
        LatitudeTerm(2, 0, -1, 1, latitude = 55413.0),
        LatitudeTerm(2, 0, -1, -1, latitude = 46271.0),
        LatitudeTerm(2, 0, 0, 1, latitude = 32573.0),
        LatitudeTerm(0, 0, 2, 1, latitude = 17198.0),
        LatitudeTerm(2, 0, 1, -1, latitude = 9266.0),
        LatitudeTerm(0, 0, 2, -1, latitude = 8822.0),
        LatitudeTerm(2, -1, 0, -1, latitude = 8216.0),
        LatitudeTerm(2, 0, -2, -1, latitude = 4324.0),
        LatitudeTerm(2, 0, 1, 1, latitude = 4200.0),
        LatitudeTerm(2, 1, 0, -1, latitude = -3359.0),
        LatitudeTerm(2, -1, -1, 1, latitude = 2463.0),
        LatitudeTerm(2, -1, 0, 1, latitude = 2211.0),
        LatitudeTerm(2, -1, -1, -1, latitude = 2065.0),
        LatitudeTerm(0, 1, -1, -1, latitude = -1870.0),
        LatitudeTerm(4, 0, -1, -1, latitude = 1828.0),
        LatitudeTerm(0, 1, 0, 1, latitude = -1794.0),
        LatitudeTerm(0, 0, 0, 3, latitude = -1749.0),
        LatitudeTerm(0, 1, -1, 1, latitude = -1565.0),
        LatitudeTerm(1, 0, 0, 1, latitude = -1491.0),
        LatitudeTerm(0, 1, 1, 1, latitude = -1475.0),
        LatitudeTerm(0, 1, 1, -1, latitude = -1410.0),
        LatitudeTerm(0, 1, 0, -1, latitude = -1344.0),
        LatitudeTerm(1, 0, 0, -1, latitude = -1335.0),
        LatitudeTerm(0, 0, 3, 1, latitude = 1107.0),
        LatitudeTerm(4, 0, 0, -1, latitude = 1021.0),
        LatitudeTerm(4, 0, -1, 1, latitude = 833.0),
        LatitudeTerm(0, 0, 1, -3, latitude = 777.0),
        LatitudeTerm(4, 0, -2, 1, latitude = 671.0),
        LatitudeTerm(2, 0, 0, -3, latitude = 607.0),
        LatitudeTerm(2, 0, 2, -1, latitude = 596.0),
        LatitudeTerm(2, -1, 1, -1, latitude = 491.0),
        LatitudeTerm(2, 0, -2, 1, latitude = -451.0),
        LatitudeTerm(0, 0, 3, -1, latitude = 439.0),
        LatitudeTerm(2, 0, 2, 1, latitude = 422.0),
        LatitudeTerm(2, 0, -3, -1, latitude = 421.0),
        LatitudeTerm(2, 1, -1, 1, latitude = -366.0),
        LatitudeTerm(2, 1, 0, 1, latitude = -351.0),
        LatitudeTerm(4, 0, 0, 1, latitude = 331.0),
        LatitudeTerm(2, -1, 1, 1, latitude = 315.0),
        LatitudeTerm(2, -2, 0, -1, latitude = 302.0),
        LatitudeTerm(0, 0, 1, 3, latitude = -283.0),
        LatitudeTerm(2, 1, 1, -1, latitude = -229.0),
        LatitudeTerm(1, 1, 0, -1, latitude = 223.0),
        LatitudeTerm(1, 1, 0, 1, latitude = 223.0),
        LatitudeTerm(0, 1, -2, -1, latitude = -220.0),
        LatitudeTerm(2, 1, -1, -1, latitude = -220.0),
        LatitudeTerm(1, 0, 1, 1, latitude = -185.0),
        LatitudeTerm(2, -1, -2, -1, latitude = 181.0),
        LatitudeTerm(0, 1, 2, 1, latitude = -177.0),
        LatitudeTerm(4, 0, -2, -1, latitude = 176.0),
        LatitudeTerm(4, -1, -1, -1, latitude = 166.0),
        LatitudeTerm(1, 0, 1, -1, latitude = -164.0),
        LatitudeTerm(4, 0, 1, -1, latitude = 132.0),
        LatitudeTerm(1, 0, -1, -1, latitude = -119.0),
        LatitudeTerm(4, -1, 0, -1, latitude = 115.0),
        LatitudeTerm(2, -2, 0, 1, latitude = 107.0),
    )
}
