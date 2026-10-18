// WorldMagneticModel.kt
//
// The NOAA/BGS World Magnetic Model 2025 (WMM2025): a degree-12 spherical-harmonic model of the
// Earth's main magnetic field, valid from 2025.0 to 2030.0. It gives the magnetic declination
// needed to turn a compass heading into a true heading, and the expected field strength against
// which a magnetometer reading can be checked for local interference.
//
// Algorithm: Chulliat et al., "The US/UK World Magnetic Model for 2025-2030: Technical Report",
// NOAA NCEI, 2025, https://doi.org/10.25923/prbc-s316, section 1.2 (equation numbers below refer
// to that report). Coefficients: NOAA NCEI, https://doi.org/10.25921/aqfd-sd83 (public domain).

package net.kuecker.lodestar.kit.geomagnetism

import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// MARK: - Field elements

/**
 * The geomagnetic field vector at one point, in the local geodetic north-east-down frame.
 *
 * All seven classical magnetic elements follow from the three components [north], [east] and
 * [down], so they are stored once and the remaining elements are derived from them.
 *
 * Instances are immutable and may be shared between threads.
 */
data class MagneticFieldElements(
    /** Northward component X, in nanotesla. */
    val north: Double,
    /** Eastward component Y, in nanotesla. */
    val east: Double,
    /** Downward component Z, in nanotesla (positive into the Earth). */
    val down: Double,
) {
    /** Horizontal intensity H, in nanotesla. */
    val horizontalIntensity: Double get() = hypot(north, east)

    /**
     * Total intensity F, in nanotesla. Compare it with a magnetometer's field magnitude
     * (1 microtesla = 1000 nT) to detect magnetic interference.
     */
    val totalIntensity: Double get() = hypot(horizontalIntensity, down)

    /**
     * Declination D in degrees, the angle from true north to magnetic north, in `[-180, 180]`.
     * East is positive: `trueHeading = magneticHeading + declination`.
     *
     * The declination is poorly conditioned where the horizontal intensity is small, near the
     * magnetic poles; see [compassReliability].
     */
    val declination: Double get() = atan2(east, north) * degreesPerRadian

    /** Inclination (dip) I in degrees, in `[-90, 90]`. Positive when the field points into the Earth. */
    val inclination: Double get() = atan2(down, horizontalIntensity) * degreesPerRadian

    /**
     * Whether all components are finite. The model returns non-finite elements only for
     * non-finite input.
     */
    val isValid: Boolean get() = north.isFinite() && east.isFinite() && down.isFinite()

    /**
     * How far a compass can be trusted here, from NOAA's horizontal-intensity thresholds for the
     * WMM Blackout Zones (technical report, section 1.8). Non-finite elements report
     * [CompassReliability.BLACKOUT].
     */
    val compassReliability: CompassReliability
        get() {
            val horizontal = horizontalIntensity
            if (horizontal >= cautionZoneThreshold) return CompassReliability.RELIABLE
            if (horizontal >= blackoutZoneThreshold) return CompassReliability.CAUTION
            return CompassReliability.BLACKOUT
        }

    private companion object {
        const val degreesPerRadian = 180 / PI
        const val blackoutZoneThreshold = 2000.0
        const val cautionZoneThreshold = 6000.0
    }
}

/** How far a magnetic compass can be trusted, judged by the horizontal field intensity. */
enum class CompassReliability {
    /** Horizontal intensity of 6000 nT or more, outside the zones around the magnetic poles. */
    RELIABLE,

    /**
     * Horizontal intensity from 2000 nT up to 6000 nT: the Caution Zone around a magnetic pole,
     * where compass accuracy degrades increasingly and declination errors can exceed 1 degree.
     */
    CAUTION,

    /**
     * Horizontal intensity below 2000 nT: the Blackout Zone, where a compass must not be trusted
     * and declination errors of up to 180 degrees occur.
     */
    BLACKOUT,
}

// MARK: - Model

/**
 * A degree-12 spherical-harmonic model of the Earth's main magnetic field.
 *
 * Use [wmm2025] for the World Magnetic Model 2025. The model is a pure value: evaluation has no
 * side effects and may be called from any thread.
 *
 * The WMM describes the main (core) field only. Its typical global RMS error at the Earth's surface
 * is about 0.4 degrees in declination and 130 nT in total intensity (technical report, table 16),
 * dominated by the crustal field it does not include. Local anomalies, and ferromagnetic material
 * or electronics near a magnetometer, can change a reading by much more than that.
 *
 * @constructor Creates a model from coefficient rows. Pairs of degree and order that no row names
 *   are zero.
 * @param epoch The reference epoch t0 of the main-field coefficients, in decimal years.
 * @param rows The coefficients. Each row must have a degree of at least 1 and an order from 0
 *   through its degree, and at least one row is required.
 * @throws IllegalArgumentException if a row is out of range or there is no row.
 */
class WorldMagneticModel internal constructor(
    /** The reference epoch t0 of the main-field coefficients, in decimal years. */
    val epoch: Double,
    rows: List<CoefficientRow>,
) {
    /** The start of the validity interval, in decimal years. */
    val validFrom: Double = epoch

    /**
     * The end of the validity interval, in decimal years. The model is not valid at this instant
     * or later: WMM2025 is to be used from 1 January 2025 through 31 December 2029.
     */
    val validUntil: Double = epoch + validityYears

    private val maximumDegree: Int
    private val mainFieldG: DoubleArray
    private val mainFieldH: DoubleArray
    private val secularVariationG: DoubleArray
    private val secularVariationH: DoubleArray

    init {
        var degree = 0
        for (row in rows) degree = max(degree, row.degree)
        require(degree >= 1) { "A magnetic model needs at least one coefficient of degree 1" }
        val count = LegendreTable.entryCount(degree)
        val g = DoubleArray(count)
        val h = DoubleArray(count)
        val gDot = DoubleArray(count)
        val hDot = DoubleArray(count)
        for (row in rows) {
            require(row.degree >= 1 && row.order in 0..row.degree) { "Invalid degree or order" }
            val index = LegendreTable.index(row.degree, row.order)
            g[index] = row.g
            h[index] = row.h
            gDot[index] = row.gDot
            hDot[index] = row.hDot
        }
        maximumDegree = degree
        mainFieldG = g
        mainFieldH = h
        secularVariationG = gDot
        secularVariationH = hDot
    }

    /**
     * Reports whether [decimalYear] lies within the interval for which the model is valid.
     * A non-finite year is never valid.
     */
    fun isWithinValidity(decimalYear: Double): Boolean =
        decimalYear >= validFrom && decimalYear < validUntil

    /**
     * Evaluates the model at a point.
     *
     * Out-of-range input is brought into range rather than rejected: [latitude] is clamped to
     * `[-90, 90]`, [longitude] is wrapped into `[-180, 180]` and [altitudeMeters] is clamped to the
     * range of the model, 1 km below to 850 km above the ellipsoid. A non-finite argument yields
     * elements for which [MagneticFieldElements.isValid] is false.
     *
     * A [decimalYear] outside the validity interval is extrapolated linearly and is
     * increasingly unreliable; check [isWithinValidity].
     *
     * The geographic poles are supported. There the north direction is defined as the limit of
     * the north direction along the meridian of [longitude], so the declination at a pole
     * depends on the longitude supplied (technical report, section 1.4).
     *
     * @param latitude Geodetic latitude in degrees, north positive.
     * @param longitude Longitude in degrees, east positive.
     * @param altitudeMeters Height above the WGS 84 ellipsoid in meters. A height above mean sea
     *   level is adequate: the geoid lies within about 110 m of the ellipsoid, which changes the
     *   field by only a few nanotesla.
     * @param decimalYear The time as a decimal year, for example 2026.5; see
     *   [WorldMagneticModel.decimalYear].
     * @return The field in the local geodetic north-east-down frame.
     */
    fun field(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double,
        decimalYear: Double,
    ): MagneticFieldElements {
        if (!latitude.isFinite() || !longitude.isFinite() || !altitudeMeters.isFinite() || !decimalYear.isFinite()) {
            return MagneticFieldElements(north = Double.NaN, east = Double.NaN, down = Double.NaN)
        }
        val clampedLatitude = min(max(latitude, -90.0), 90.0)
        val wrappedLongitude = Math.IEEEremainder(longitude, 360.0)
        val clampedAltitude = min(max(altitudeMeters, minimumAltitudeMeters), maximumAltitudeMeters)

        val position = GeocentricPosition(
            geodeticLatitudeDegrees = clampedLatitude,
            heightKilometers = clampedAltitude / 1000,
        )
        val geocentric = synthesize(
            sinLatitude = position.sinLatitude,
            cosLatitude = position.cosLatitude,
            longitudeRadians = wrappedLongitude * PI / 180,
            radiusKilometers = position.radiusKilometers,
            decimalYear = decimalYear,
        )

        // Equation (17): rotate the geocentric components into the geodetic frame.
        return MagneticFieldElements(
            north = geocentric.north * position.rotationCosine - geocentric.down * position.rotationSine,
            east = geocentric.east,
            down = geocentric.north * position.rotationSine + geocentric.down * position.rotationCosine,
        )
    }

    // MARK: Spherical-harmonic synthesis

    /**
     * Evaluates the geocentric field components X', Y', Z' (equations 9 to 12), in nanotesla.
     *
     * The east component divides by cos(phi'). Instead of special-casing the poles, the
     * [LegendreTable] supplies P(sin phi') / cos(phi') from a recurrence that never divides,
     * which equals the limit the technical report prescribes at the poles (section 1.4).
     *
     * @param sinLatitude sin(phi') of the geocentric latitude.
     * @param cosLatitude cos(phi') of the geocentric latitude; zero at the poles.
     * @param longitudeRadians Longitude in radians.
     * @param radiusKilometers Distance from the Earth's center in kilometers.
     * @param decimalYear Time in decimal years.
     */
    internal fun synthesize(
        sinLatitude: Double,
        cosLatitude: Double,
        longitudeRadians: Double,
        radiusKilometers: Double,
        decimalYear: Double,
    ): GeocentricComponents {
        val table = LegendreTable(
            maximumDegree = maximumDegree,
            sinLatitude = sinLatitude,
            cosLatitude = cosLatitude,
        )
        val elapsedYears = decimalYear - epoch
        val radiusRatio = referenceRadiusKilometers / radiusKilometers
        val cosines = DoubleArray(maximumDegree + 1) { cos(it.toDouble() * longitudeRadians) }
        val sines = DoubleArray(maximumDegree + 1) { sin(it.toDouble() * longitudeRadians) }

        var north = 0.0
        var east = 0.0
        var down = 0.0
        for (degree in 1..maximumDegree) {
            val radialFactor = radiusRatio.pow((degree + 2).toDouble()) // (a/r)^(n+2)
            for (order in 0..degree) {
                val index = LegendreTable.index(degree, order)
                // Equation (9): time-adjusted Gauss coefficients.
                val g = mainFieldG[index] + elapsedYears * secularVariationG[index]
                val h = mainFieldH[index] + elapsedYears * secularVariationH[index]
                val cosine = cosines[order]
                val sine = sines[order]

                val cosineTerm = g * cosine + h * sine
                north -= radialFactor * cosineTerm * table.latitudeDerivative[index]
                east += radialFactor * order.toDouble() * (g * sine - h * cosine) * table.overCosine[index]
                down -= radialFactor * (degree + 1).toDouble() * cosineTerm * table.value[index]
            }
        }
        return GeocentricComponents(north = north, east = east, down = down)
    }

    companion object {
        /** The World Magnetic Model 2025, valid from 2025.0 to 2030.0. */
        val wmm2025: WorldMagneticModel = WorldMagneticModel(
            epoch = WMM2025Coefficients.epoch,
            rows = WMM2025Coefficients.rows,
        )

        /**
         * Converts a date to a decimal year: the UTC calendar year plus the elapsed fraction of that
         * year, so that midday UTC on 2 July 2025 is exactly 2025.5. Leap seconds are ignored, as in
         * POSIX time.
         *
         * Returns `NaN` for a date that lies more than about 300,000 years from 1970.
         */
        fun decimalYear(date: Instant): Double =
            decimalYear(secondsSince1970 = date.epochSecond.toDouble() + date.nano / 1e9)

        /**
         * Converts seconds since 1970-01-01T00:00:00Z (POSIX time) to a decimal year. This is the
         * implementation behind [decimalYear] for an [Instant]; unlike an [Instant] it also accepts
         * non-finite and arbitrarily large values.
         *
         * Returns `NaN` for a value that is not finite or lies more than about 300,000 years from 1970.
         */
        internal fun decimalYear(secondsSince1970: Double): Double {
            val seconds = secondsSince1970
            if (!seconds.isFinite() || abs(seconds) > maximumConvertibleSeconds) return Double.NaN

            // The mean Gregorian year estimate is at most one year off; the loops correct it.
            var year = 1970L + floor(seconds / (365.2425 * secondsPerDay)).toLong()
            var start = unixSeconds(year)
            while (seconds < start) {
                year -= 1
                start = unixSeconds(year)
            }
            var end = unixSeconds(year + 1)
            while (seconds >= end) {
                year += 1
                start = end
                end = unixSeconds(year + 1)
            }
            return year.toDouble() + (seconds - start) / (end - start)
        }

        // MARK: Constants

        /** The geomagnetic reference radius a, in kilometers. */
        private const val referenceRadiusKilometers = 6371.2

        /** Valid heights above the ellipsoid, in meters (MIL-PRF-89500B). */
        private const val minimumAltitudeMeters = -1_000.0
        private const val maximumAltitudeMeters = 850_000.0

        /** How long a WMM epoch is valid, in years. */
        private const val validityYears = 5.0

        private const val secondsPerDay = 86_400.0

        /** Dates farther than this from 1970, about 316,000 years, are not converted to a decimal year. */
        private const val maximumConvertibleSeconds = 1e13

        /** Days from 0001-01-01 to 1970-01-01 in the proleptic Gregorian calendar. */
        private const val daysFromYearOneToUnixEpoch = 719_162L

        // MARK: Calendar helpers

        /** Seconds since 1970-01-01T00:00:00Z of the first instant of [year] (proleptic Gregorian). */
        private fun unixSeconds(year: Long): Double {
            val elapsedYears = year - 1
            val days = 365 * elapsedYears +
                floorDivide(elapsedYears, 4) - floorDivide(elapsedYears, 100) + floorDivide(elapsedYears, 400) -
                daysFromYearOneToUnixEpoch
            return days.toDouble() * secondsPerDay
        }

        /** Integer division of [dividend] by a positive [divisor], rounding toward negative infinity. */
        private fun floorDivide(dividend: Long, divisor: Long): Long {
            val quotient = dividend / divisor
            return if (dividend % divisor < 0) quotient - 1 else quotient
        }
    }

    // MARK: - Supporting types

    /** One line of a WMM coefficient file: the Gauss coefficients of one degree and order. */
    internal data class CoefficientRow(
        /** Degree n, at least 1. */
        val degree: Int,
        /** Order m, from 0 through [degree]. */
        val order: Int,
        /** Cosine coefficient g at the model epoch, in nT. */
        val g: Double,
        /** Sine coefficient h at the model epoch, in nT. Zero for order 0. */
        val h: Double,
        /** Secular variation of [g], in nT per year. */
        val gDot: Double,
        /** Secular variation of [h], in nT per year. Zero for order 0. */
        val hDot: Double,
    )

    /** The geocentric field components X', Y', Z' returned by [synthesize], in nanotesla. */
    internal data class GeocentricComponents(
        /** Northward component X', in nanotesla. */
        val north: Double,
        /** Eastward component Y', in nanotesla. */
        val east: Double,
        /** Downward component Z', in nanotesla. */
        val down: Double,
    )

    /**
     * A point in geocentric spherical coordinates, derived from geodetic latitude and height.
     *
     * Converts a point on or above the WGS 84 ellipsoid (equations 7 and 8).
     *
     * @param geodeticLatitudeDegrees Geodetic latitude phi in degrees.
     * @param heightKilometers Height above the ellipsoid in kilometers.
     */
    internal class GeocentricPosition(geodeticLatitudeDegrees: Double, heightKilometers: Double) {
        /** sin(phi') of the geocentric latitude. */
        val sinLatitude: Double

        /** cos(phi') of the geocentric latitude. */
        val cosLatitude: Double

        /** Distance from the Earth's center, in kilometers. */
        val radiusKilometers: Double

        /** cos(phi' - phi), the rotation from the geocentric to the geodetic frame. */
        val rotationCosine: Double

        /** sin(phi' - phi), the rotation from the geocentric to the geodetic frame. */
        val rotationSine: Double

        init {
            val latitude = geodeticLatitudeDegrees * PI / 180
            val sinGeodetic = sin(latitude)
            val cosGeodetic = cos(latitude)
            val primeVerticalRadius = semiMajorAxisKilometers /
                sqrt(1 - eccentricitySquared * sinGeodetic * sinGeodetic)
            val distanceFromAxis = (primeVerticalRadius + heightKilometers) * cosGeodetic
            val heightAboveEquator = (primeVerticalRadius * (1 - eccentricitySquared) + heightKilometers) *
                sinGeodetic

            radiusKilometers = hypot(distanceFromAxis, heightAboveEquator)
            sinLatitude = heightAboveEquator / radiusKilometers
            cosLatitude = distanceFromAxis / radiusKilometers
            rotationCosine = cosLatitude * cosGeodetic + sinLatitude * sinGeodetic
            rotationSine = sinLatitude * cosGeodetic - cosLatitude * sinGeodetic
        }

        private companion object {
            /** WGS 84 semi-major axis A, in kilometers. */
            const val semiMajorAxisKilometers = 6378.137

            /** WGS 84 flattening f, from the defining reciprocal 1/f = 298.257223563. */
            const val flattening = 1 / 298.257223563

            /** WGS 84 first eccentricity squared, e^2 = f (2 - f). */
            const val eccentricitySquared = flattening * (2 - flattening)
        }
    }

    /**
     * Schmidt semi-normalized associated Legendre functions of sin(phi'), for every degree and order
     * up to a maximum, with the two companions the field equations need.
     *
     * The functions follow equations (5) and (6) of the technical report: without the Condon-Shortley
     * phase and with the factor sqrt(2 (n-m)! / (n+m)!) for orders above zero. All three tables come
     * from three-term recurrences that contain no division by cos(phi'), so they are exact at the
     * poles.
     *
     * The tables are flat arrays indexed by [index]; callers must not modify them.
     *
     * @constructor Computes the table for a geocentric latitude given by its sine and cosine.
     * @param maximumDegree The highest degree needed, at least 1.
     * @param sinLatitude sin(phi').
     * @param cosLatitude cos(phi'), non-negative.
     * @throws IllegalArgumentException if [maximumDegree] is less than 1.
     */
    internal class LegendreTable(maximumDegree: Int, sinLatitude: Double, cosLatitude: Double) {
        /** P(n, m)(sin phi'). */
        val value: DoubleArray

        /**
         * The derivative of P(n, m)(sin phi') with respect to the latitude phi', the quantity of
         * equation (16).
         */
        val latitudeDerivative: DoubleArray

        /**
         * P(n, m)(sin phi') / cos(phi') for orders m >= 1; zero for m = 0, which no formula needs.
         * It tends to a finite limit at the poles.
         */
        val overCosine: DoubleArray

        init {
            require(maximumDegree >= 1) { "maximumDegree must be at least 1" }
            val s = sinLatitude
            val c = cosLatitude
            val count = entryCount(maximumDegree)
            val p = DoubleArray(count)
            val dp = DoubleArray(count)
            val q = DoubleArray(count)
            p[0] = 1.0

            for (degree in 1..maximumDegree) {
                val n = degree.toDouble()

                // Sectoral functions (m = n) from the one of the previous degree.
                val sectoral = index(degree, degree)
                val previousSectoral = index(degree - 1, degree - 1)
                if (degree == 1) {
                    p[sectoral] = c
                    dp[sectoral] = -s
                    q[sectoral] = 1.0
                } else {
                    val factor = sqrt((2 * n - 1) / (2 * n))
                    p[sectoral] = factor * c * p[previousSectoral]
                    dp[sectoral] = factor * (c * dp[previousSectoral] - s * p[previousSectoral])
                    q[sectoral] = factor * p[previousSectoral]
                }

                // All other orders (m < n) from the two previous degrees of the same order.
                for (order in 0 until degree) {
                    val m = order.toDouble()
                    val inverseRoot = 1 / sqrt(n * n - m * m)
                    val a = (2 * n - 1) * inverseRoot
                    val one = index(degree - 1, order)
                    var pValue = a * s * p[one]
                    var dpValue = a * (s * dp[one] + c * p[one])
                    var qValue = a * s * q[one]
                    if (order <= degree - 2) {
                        val b = sqrt((n - 1) * (n - 1) - m * m) * inverseRoot
                        val two = index(degree - 2, order)
                        pValue -= b * p[two]
                        dpValue -= b * dp[two]
                        qValue -= b * q[two]
                    }
                    val index = index(degree, order)
                    p[index] = pValue
                    dp[index] = dpValue
                    q[index] = qValue
                }
            }
            value = p
            latitudeDerivative = dp
            overCosine = q
        }

        companion object {
            /** The number of (degree, order) pairs, including degree 0, up to [maximumDegree]. */
            fun entryCount(maximumDegree: Int): Int = (maximumDegree + 1) * (maximumDegree + 2) / 2

            /** The position of the pair ([degree], [order]) in the flat arrays. */
            fun index(degree: Int, order: Int): Int = degree * (degree + 1) / 2 + order
        }
    }
}

