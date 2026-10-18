package net.kuecker.lodestar.kit.foundation

import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.math.floor

/**
 * An instant expressed as a Julian Date in two time scales.
 *
 * `ut` is the Julian Date in Universal Time (UT1, taken as equal to UTC — the difference is
 * below 0.9 s and irrelevant for this app). `tt` is Terrestrial Time, `ut + ΔT`, the uniform
 * time scale used by the ephemeris theories.
 *
 * Ordering ([compareTo]) is by [ut] only, as in Swift; equality compares both fields.
 *
 * @property ut Julian Date in UT1 (≈ UTC).
 * @property deltaT ΔT = TT − UT in seconds.
 */
@Serializable
data class JulianDate(val ut: Double, val deltaT: Double) : Comparable<JulianDate> {
    /** Creates a Julian Date from UT, estimating ΔT for that epoch. */
    constructor(ut: Double) : this(ut, Companion.deltaT(Companion.decimalYear(ut)))

    /** Creates a Julian Date from a point in time (POSIX time is UTC without leap seconds). */
    constructor(date: Instant) : this(date.timeIntervalSince1970 / secondsPerDay + unixEpoch)

    /** Julian Date in Terrestrial Time. */
    val tt: Double get() = ut + deltaT / secondsPerDay

    /** The instant as a point in time. */
    val date: Instant get() = instantFromTimeIntervalSince1970((ut - unixEpoch) * secondsPerDay)

    /** Julian centuries of TT since J2000.0. */
    val centuriesTT: Double get() = (tt - j2000) / daysPerJulianCentury

    /** Julian centuries of UT since J2000.0. */
    val centuriesUT: Double get() = (ut - j2000) / daysPerJulianCentury

    /** The epoch as a Julian year in TT, e.g. 2026.79. */
    val julianYearTT: Double get() = 2000 + (tt - j2000) / 365.25

    /** A Julian Date [days] later (ΔT is re-estimated for the new epoch). */
    fun addingDays(days: Double): JulianDate = JulianDate(ut + days)

    /** Orders by [ut] alone (Swift's `<`); a NaN [ut] compares equal to everything. */
    override fun compareTo(other: JulianDate): Int =
        if (ut < other.ut) -1 else if (other.ut < ut) 1 else 0

    companion object {
        /** Julian Date of the J2000.0 epoch (2000 January 1, 12:00 TT). */
        const val j2000: Double = 2_451_545.0

        /** Julian Date of the Unix epoch (1970 January 1, 00:00 UTC). */
        const val unixEpoch: Double = 2_440_587.5

        /** Days in a Julian century. */
        const val daysPerJulianCentury: Double = 36_525.0

        /** Seconds in a day. */
        const val secondsPerDay: Double = 86_400.0

        /**
         * Creates a Julian Date from TT (e.g. for published examples given in dynamical time),
         * estimating ΔT for that epoch (Swift `init(tt:)`).
         */
        fun fromTt(tt: Double): JulianDate {
            val estimate = deltaT(decimalYear(tt))
            return JulianDate(tt - estimate / secondsPerDay, estimate)
        }

        /** The decimal year corresponding to a Julian Day number (Julian-year approximation). */
        fun decimalYear(julianDay: Double): Double = 2000 + (julianDay - j2000) / 365.25

        /** Julian Day for a proleptic Gregorian calendar date and time (Meeus, ch. 7). */
        fun julianDay(
            year: Int,
            month: Int,
            day: Int,
            hour: Int = 0,
            minute: Int = 0,
            second: Double = 0.0,
        ): Double {
            var y = year.toDouble()
            var m = month.toDouble()
            if (month <= 2) {
                y -= 1
                m += 12
            }
            val a = floor(y / 100)
            val b = 2 - a + floor(a / 4)
            val dayFraction = (hour.toDouble() + minute.toDouble() / 60 + second / 3600) / 24
            return floor(365.25 * (y + 4716)) +
                floor(30.6001 * (m + 1)) +
                day.toDouble() + dayFraction + b - 1524.5
        }

        /**
         * Estimated ΔT = TT − UT in seconds for a decimal year.
         *
         * Uses the Espenak & Meeus (2006) polynomials up to 2005, measured annual values from
         * 2005 to 2026, a linear blend towards the Espenak & Meeus long-term expression between 2026
         * and 2050, and that expression afterwards. Errors are well below a few seconds for the
         * 20th and 21st centuries, which is negligible for pointing purposes.
         *
         * The Swift parameter is `decimalYear y`; the Kotlin name keeps the label.
         */
        fun deltaT(decimalYear: Double): Double {
            val y = decimalYear
            if (!y.isFinite()) return 69.2
            if (y >= measuredDeltaTStart && y <= measuredDeltaTEnd) {
                val position = y - measuredDeltaTStart
                val index = minOf(position.toInt(), measuredDeltaT.size - 2)
                val fraction = position - index.toDouble()
                return measuredDeltaT[index] + (measuredDeltaT[index + 1] - measuredDeltaT[index]) * fraction
            }
            if (y > measuredDeltaTEnd && y < 2050) {
                val start = measuredDeltaT[measuredDeltaT.size - 1]
                val end = longTermDeltaT(2050.0) - 0.5628 * (2150 - 2050)
                return start + (end - start) * (y - measuredDeltaTEnd) / (2050 - measuredDeltaTEnd)
            }
            if (y >= 2050 && y < 2150) {
                return longTermDeltaT(y) - 0.5628 * (2150 - y)
            }
            if (y >= 2150 || y < 1800) {
                return longTermDeltaT(y)
            }
            if (y < 1860) {
                val t = y - 1800
                return AstroMath.polynomial(
                    t, 13.72, -0.332447, 0.0068612, 0.0041116, -0.00037436,
                    0.0000121272, -0.0000001699, 0.000000000875,
                )
            }
            if (y < 1900) {
                val t = y - 1860
                return AstroMath.polynomial(t, 7.62, 0.5737, -0.251754, 0.01680668, -0.0004473624, 1.0 / 233_174)
            }
            if (y < 1920) {
                val t = y - 1900
                return AstroMath.polynomial(t, -2.79, 1.494119, -0.0598939, 0.0061966, -0.000197)
            }
            if (y < 1941) {
                val t = y - 1920
                return AstroMath.polynomial(t, 21.20, 0.84493, -0.076100, 0.0020936)
            }
            if (y < 1961) {
                val t = y - 1950
                return AstroMath.polynomial(t, 29.07, 0.407, -1 / 233.0, 1 / 2547.0)
            }
            if (y < 1986) {
                val t = y - 1975
                return AstroMath.polynomial(t, 45.45, 1.067, -1 / 260.0, -1 / 718.0)
            }
            val t = y - 2000
            return AstroMath.polynomial(t, 63.86, 0.3345, -0.060374, 0.0017275, 0.000651814, 0.00002373599)
        }

        private fun longTermDeltaT(y: Double): Double {
            val u = (y - 1820) / 100
            return -20 + 32 * u * u
        }

        private const val measuredDeltaTStart: Double = 2005.0
        private const val measuredDeltaTEnd: Double = 2026.0

        /** ΔT at the start of each year 2005…2026 (seconds), from IERS/USNO determinations. */
        private val measuredDeltaT: DoubleArray = doubleArrayOf(
            64.69, 64.85, 65.15, 65.46, 65.78, 66.07, 66.32, 66.60, 66.91, 67.28, 67.64,
            68.10, 68.59, 68.97, 69.22, 69.36, 69.36, 69.29, 69.20, 69.18, 69.14, 69.11,
        )
    }
}

/** Sidereal time (Earth rotation angle relative to the equinox). */
object SiderealTime {
    /** Greenwich mean sidereal time in degrees, [0, 360) (IAU 1982 expression, Meeus 12.4). */
    fun greenwichMean(jd: JulianDate): Double {
        val d = jd.ut - JulianDate.j2000
        val t = d / JulianDate.daysPerJulianCentury
        val theta = 280.460_618_37 + 360.985_647_366_29 * d + 0.000_387_933 * t * t - t * t * t / 38_710_000
        return AstroMath.normalizeDegrees(theta)
    }

    /**
     * Greenwich apparent sidereal time in degrees: mean sidereal time plus the equation of the
     * equinoxes (Δψ·cos ε).
     */
    fun greenwichApparent(jd: JulianDate, nutation: Nutation, trueObliquity: Double): Double =
        AstroMath.normalizeDegrees(greenwichMean(jd) + nutation.longitude * AstroMath.cosDeg(trueObliquity))
}
