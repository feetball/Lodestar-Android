package net.kuecker.lodestar.kit.ephemeris

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pluto's heliocentric position from the series of Jean Meeus, *Astronomical Algorithms* (2nd ed., 1998),
 * chapter 37, "Pluto": 43 periodic terms in the mean arguments of Jupiter, Saturn and Pluto (Table 37.A),
 * referred to the mean equinox and ecliptic of J2000.0 and valid from 1885 to 2099 (outside it the error grows
 * quickly). Pluto has no VSOP87 series; it is a dwarf planet that Lodestar lists with the planets.
 *
 * With `T` in Julian centuries of TT since J2000.0 the mean arguments, in degrees, are
 * `J = 34.35 + 3034.9057 T`, `S = 50.08 + 1222.1138 T` and `P = 238.96 + 144.96 T`, and term `n` has the
 * argument `α = iJ + jS + kP` for the integers `i`, `j`, `k` of its row of Table 37.A. Then, with its own pair
 * `A`, `B` per term for each of the three sums,
 * - `l = 238.958116 + 144.96 T + 10⁻⁶ Σ (A sin α + B cos α)` degrees,
 * - `b = −3.908239 + 10⁻⁶ Σ (A sin α + B cos α)` degrees,
 * - `r = 40.7241346 + 10⁻⁷ Σ (A sin α + B cos α)` AU,
 * with `A` and `B` in the book's units, 10⁻⁶ degree for `l` and `b` and 10⁻⁷ AU for `r`. Meeus's example 37.a
 * (1992 October 13.0 TT) gives l = 232.74071°, b = +14.58782° and r = 29.711111 AU.
 *
 * Measured against the Pluto-system barycentre of JPL's DE440s (the Sun to the barycentre, one sample every 10
 * days; DE440s has no Pluto body, but Pluto's centre is only 0.03–0.08″ from the barycentre at seven dates
 * compared in JPL Horizons): over 1885–2099 the heliocentric longitude (times cos b) is good to 3.1″ (rms
 * 1.2″), the latitude to 0.33″ and the radius to 6.5×10⁻⁶ (0.0003 AU). The longitude error is below 1″ from
 * 1885 to 2024 (below 0.6″ from 1900) and grows to 1.2″ by 2049, 2.2″ by 2074 and 3.1″ by 2099, where the
 * series drifts from DE440s. Outside the span the error grows quickly (up to 1120″ in 1850–1884 and 1400″ in
 * 2100–2149, the radius by 0.1 %), so the series means something only for 1885–2099. With light-time applied
 * the apparent geocentric direction differs from DE440s by at most 1.3″ (median 0.4″) and the distance by
 * 4.6×10⁻⁶ over 1950–2049. Being trigonometric, the series stays finite for any date and r stays between 29.6
 * and 51.8 AU.
 *
 * The coefficients were compared digit by digit (as integers, parsed by script, not by running the code) with
 * four open-source transcriptions of Table 37.A, which agree in all 43 rows and in the constants. The URLs
 * name the commit that last changed the file; the SHA-256 is that of the file fetched on 2026-11-18:
 * - soniakeys/meeus v3/pluto/pluto.go (Go, MIT), SHA-256
 *   36fe0360e04865fd886c94571b5c69fc03ec3c8022d66bc46abdd706c40bb2ee
 *   https://raw.githubusercontent.com/soniakeys/meeus/c7f0dba3129809c96df09320f64f068e0430ba9d/v3/pluto/pluto.go
 * - commenthol/astronomia src/pluto.js (JavaScript, MIT), SHA-256
 *   2dd8a85b07467f679175a41f3c98c074bb282e5e15a3ee22a1016c71676e3983
 *   https://raw.githubusercontent.com/commenthol/astronomia/62f5ff44090a5ddf31e960122c95e1a013b3cfaa/src/pluto.js
 * - architest/pymeeus pymeeus/Pluto.py (Python, LGPL-3), SHA-256
 *   9a407bf676816845319340110b24b5bd693f350e748b7e3ddb3766985917a3cd
 *   https://raw.githubusercontent.com/architest/pymeeus/f6fb72d1124ad107a3a406d8a126881ef6660be8/pymeeus/Pluto.py
 * - saurvs/astro-rust src/pluto.rs (Rust, MIT), SHA-256
 *   e559c40c64b3c6fc3e643d1847e5bae7870bc78e638da1283cf6f7ac39257e5c
 *   https://raw.githubusercontent.com/saurvs/astro-rust/78a35745f8423f891bc1b4e138e3b40215cd8640/src/pluto.rs
 * A fifth, libnova's `pluto.c`, differs from them in three digits (longitude B of rows 25 and 37, radius B of
 * row 43) and was not used. The table, the constants and the record of this comparison are in
 * Tools/ephemeris/pluto_tables.py; Tools/ephemeris/check_pluto_theory.py checks the series against the book's
 * example and against DE440s.
 *
 * In Swift this type is `Ephemeris.PlutoTheory`; Kotlin cannot add nested types from another file, so it is a
 * top-level object of the same package. The numbers are copied digit for digit from the Swift source.
 */
internal object PlutoTheory {
    /**
     * Pluto's heliocentric position referred to the mean ecliptic and equinox of J2000.0.
     *
     * @property longitude Longitude in degrees, [0, 360).
     * @property latitude Latitude in degrees.
     * @property radius Distance from the Sun in AU.
     */
    data class Position(val longitude: Double, val latitude: Double, val radius: Double)

    /** The number of periodic terms (43). Kotlin addition, for tests. */
    val termCount: Int
        get() = multipliers.size / 3

    /**
     * SHA-256 of the series tables as lower-case hex (Kotlin addition, for tests): for the multipliers, then the
     * longitude, latitude and radius coefficients (in this order), the number of values followed by every value, the
     * multipliers as four-byte big-endian integers and the coefficients as the IEEE 754 bits (big endian) of the
     * doubles, so a transcription error in any digit changes it. The test compares it with the digest of the numbers
     * in the Swift source.
     */
    val tableDigest: String
        get() {
            val digest = TableDigest()
            digest.add(multipliers.size)
            for (value in multipliers) digest.add(value)
            for (values in listOf(longitudeCoefficients, latitudeCoefficients, radiusCoefficients)) {
                digest.add(values.size)
                for (value in values) digest.add(value)
            }
            return digest.hex()
        }

    /** Heliocentric position in AU in the mean equatorial frame of J2000.0 at [julianDayTT], without light-time. */
    fun heliocentricJ2000(julianDayTT: Double): Vector3 {
        val t = (julianDayTT - JulianDate.j2000) / JulianDate.daysPerJulianCentury
        val position = heliocentricEcliptic(t)
        val longitude = AstroMath.radians(position.longitude)
        val latitude = AstroMath.radians(position.latitude)
        val cosLatitude = cos(latitude)
        val ecliptic = Vector3(
            position.radius * cosLatitude * cos(longitude),
            position.radius * cosLatitude * sin(longitude),
            position.radius * sin(latitude),
        )
        return eclipticToEquatorialJ2000 * ecliptic
    }

    /**
     * Pluto's heliocentric longitude (degrees, [0, 360)), latitude (degrees) and radius (AU) at [centuries] Julian
     * centuries of TT since J2000.0, referred to the mean ecliptic and equinox of J2000.0.
     *
     * The Swift parameter is `centuries t`; the Kotlin name keeps the label.
     */
    fun heliocentricEcliptic(centuries: Double): Position {
        val t = centuries
        val j = 34.35 + 3034.9057 * t
        val s = 50.08 + 1222.1138 * t
        val p = 238.96 + 144.96 * t

        var sumLongitude = 0.0
        var sumLatitude = 0.0
        var sumRadius = 0.0
        for (term in 0 until termCount) {
            val multiplierJ = multipliers[3 * term].toDouble()
            val multiplierS = multipliers[3 * term + 1].toDouble()
            val multiplierP = multipliers[3 * term + 2].toDouble()
            val argument = multiplierJ * j + multiplierS * s + multiplierP * p
            val sine = AstroMath.sinDeg(argument)
            val cosine = AstroMath.cosDeg(argument)
            val pair = 2 * term
            sumLongitude += longitudeCoefficients[pair] * sine + longitudeCoefficients[pair + 1] * cosine
            sumLatitude += latitudeCoefficients[pair] * sine + latitudeCoefficients[pair + 1] * cosine
            sumRadius += radiusCoefficients[pair] * sine + radiusCoefficients[pair + 1] * cosine
        }

        // The sums are in millionths of a degree (longitude, latitude) and ten-millionths of an AU (radius).
        return Position(
            longitude = AstroMath.normalizeDegrees(238.958116 + 144.96 * t + sumLongitude / 1e6),
            latitude = -3.908239 + sumLatitude / 1e6,
            radius = 40.7241346 + sumRadius / 1e7,
        )
    }

    /** Rotation from the J2000 mean ecliptic to the J2000 mean equator. */
    private val eclipticToEquatorialJ2000: Matrix3 = Matrix3.rotationX(AstroMath.radians(Obliquity.j2000))

    /**
     * Table 37.A: the integer multipliers `i`, `j`, `k` of `J`, `S` and `P` in the argument of each term (three
     * values per term).
     */
    private val multipliers: IntArray = intArrayOf(
        0, 0, 1, // 1
        0, 0, 2, // 2
        0, 0, 3, // 3
        0, 0, 4, // 4
        0, 0, 5, // 5
        0, 0, 6, // 6
        0, 1, -1, // 7
        0, 1, 0, // 8
        0, 1, 1, // 9
        0, 1, 2, // 10
        0, 1, 3, // 11
        0, 2, -2, // 12
        0, 2, -1, // 13
        0, 2, 0, // 14
        1, -1, 0, // 15
        1, -1, 1, // 16
        1, 0, -3, // 17
        1, 0, -2, // 18
        1, 0, -1, // 19
        1, 0, 0, // 20
        1, 0, 1, // 21
        1, 0, 2, // 22
        1, 0, 3, // 23
        1, 0, 4, // 24
        1, 1, -3, // 25
        1, 1, -2, // 26
        1, 1, -1, // 27
        1, 1, 0, // 28
        1, 1, 1, // 29
        1, 1, 3, // 30
        2, 0, -6, // 31
        2, 0, -5, // 32
        2, 0, -4, // 33
        2, 0, -3, // 34
        2, 0, -2, // 35
        2, 0, -1, // 36
        2, 0, 0, // 37
        2, 0, 1, // 38
        2, 0, 2, // 39
        2, 0, 3, // 40
        3, 0, -2, // 41
        3, 0, -1, // 42
        3, 0, 0, // 43
    )

    /**
     * Table 37.A: the coefficients `A` of the sine and `B` of the cosine for the longitude, in 10⁻⁶ degree (two
     * values per term).
     */
    private val longitudeCoefficients: DoubleArray = doubleArrayOf(
        -19799805.0, 19850055.0, // 1
        897144.0, -4954829.0, // 2
        611149.0, 1211027.0, // 3
        -341243.0, -189585.0, // 4
        129287.0, -34992.0, // 5
        -38164.0, 30893.0, // 6
        20442.0, -9987.0, // 7
        -4063.0, -5071.0, // 8
        -6016.0, -3336.0, // 9
        -3956.0, 3039.0, // 10
        -667.0, 3572.0, // 11
        1276.0, 501.0, // 12
        1152.0, -917.0, // 13
        630.0, -1277.0, // 14
        2571.0, -459.0, // 15
        899.0, -1449.0, // 16
        -1016.0, 1043.0, // 17
        -2343.0, -1012.0, // 18
        7042.0, 788.0, // 19
        1199.0, -338.0, // 20
        418.0, -67.0, // 21
        120.0, -274.0, // 22
        -60.0, -159.0, // 23
        -82.0, -29.0, // 24
        -36.0, -29.0, // 25
        -40.0, 7.0, // 26
        -14.0, 22.0, // 27
        4.0, 13.0, // 28
        5.0, 2.0, // 29
        -1.0, 0.0, // 30
        2.0, 0.0, // 31
        -4.0, 5.0, // 32
        4.0, -7.0, // 33
        14.0, 24.0, // 34
        -49.0, -34.0, // 35
        163.0, -48.0, // 36
        9.0, -24.0, // 37
        -4.0, 1.0, // 38
        -3.0, 1.0, // 39
        1.0, 3.0, // 40
        -3.0, -1.0, // 41
        5.0, -3.0, // 42
        0.0, 0.0, // 43
    )

    /** Table 37.A: the coefficients `A` and `B` for the latitude, in 10⁻⁶ degree (two values per term). */
    private val latitudeCoefficients: DoubleArray = doubleArrayOf(
        -5452852.0, -14974862.0, // 1
        3527812.0, 1672790.0, // 2
        -1050748.0, 327647.0, // 3
        178690.0, -292153.0, // 4
        18650.0, 100340.0, // 5
        -30697.0, -25823.0, // 6
        4878.0, 11248.0, // 7
        226.0, -64.0, // 8
        2030.0, -836.0, // 9
        69.0, -604.0, // 10
        -247.0, -567.0, // 11
        -57.0, 1.0, // 12
        -122.0, 175.0, // 13
        -49.0, -164.0, // 14
        -197.0, 199.0, // 15
        -25.0, 217.0, // 16
        589.0, -248.0, // 17
        -269.0, 711.0, // 18
        185.0, 193.0, // 19
        315.0, 807.0, // 20
        -130.0, -43.0, // 21
        5.0, 3.0, // 22
        2.0, 17.0, // 23
        2.0, 5.0, // 24
        2.0, 3.0, // 25
        3.0, 1.0, // 26
        2.0, -1.0, // 27
        1.0, -1.0, // 28
        0.0, -1.0, // 29
        0.0, 0.0, // 30
        0.0, -2.0, // 31
        2.0, 2.0, // 32
        -7.0, 0.0, // 33
        10.0, -8.0, // 34
        -3.0, 20.0, // 35
        6.0, 5.0, // 36
        14.0, 17.0, // 37
        -2.0, 0.0, // 38
        0.0, 0.0, // 39
        0.0, 0.0, // 40
        0.0, 1.0, // 41
        0.0, 0.0, // 42
        1.0, 0.0, // 43
    )

    /** Table 37.A: the coefficients `A` and `B` for the radius, in 10⁻⁷ AU (two values per term). */
    private val radiusCoefficients: DoubleArray = doubleArrayOf(
        66865439.0, 68951812.0, // 1
        -11827535.0, -332538.0, // 2
        1593179.0, -1438890.0, // 3
        -18444.0, 483220.0, // 4
        -65977.0, -85431.0, // 5
        31174.0, -6032.0, // 6
        -5794.0, 22161.0, // 7
        4601.0, 4032.0, // 8
        -1729.0, 234.0, // 9
        -415.0, 702.0, // 10
        239.0, 723.0, // 11
        67.0, -67.0, // 12
        1034.0, -451.0, // 13
        -129.0, 504.0, // 14
        480.0, -231.0, // 15
        2.0, -441.0, // 16
        -3359.0, 265.0, // 17
        7856.0, -7832.0, // 18
        36.0, 45763.0, // 19
        8663.0, 8547.0, // 20
        -809.0, -769.0, // 21
        263.0, -144.0, // 22
        -126.0, 32.0, // 23
        -35.0, -16.0, // 24
        -19.0, -4.0, // 25
        -15.0, 8.0, // 26
        -4.0, 12.0, // 27
        5.0, 6.0, // 28
        3.0, 1.0, // 29
        6.0, -2.0, // 30
        2.0, 2.0, // 31
        -2.0, -2.0, // 32
        14.0, 13.0, // 33
        -63.0, 13.0, // 34
        136.0, -236.0, // 35
        273.0, 1065.0, // 36
        251.0, 149.0, // 37
        -25.0, -9.0, // 38
        9.0, -2.0, // 39
        -8.0, 7.0, // 40
        2.0, -10.0, // 41
        19.0, 35.0, // 42
        10.0, 3.0, // 43
    )
}
