package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.floor

/**
 * A validated set of mean orbital elements for the SGP4/SDP4 propagator: one record of CelesTrak's GP data
 * (OMM keywords) or one classic two-line element set.
 *
 * Units are those of the published formats: angles in degrees, the mean motion in revolutions per day, the
 * drag term in inverse Earth radii. Create values with [create] or with [ElementSetParser]; both validate every
 * field, so an instance always holds finite values in the ranges listed below.
 *
 * | Field | Accepted values |
 * |---|---|
 * | [catalogNumber] | 0 ... 999 999 999 |
 * | [classification] | `A` ... `Z` (`U`, `C`, `S` in practice) |
 * | [epoch] | years 1957 ... 2099 |
 * | [meanMotion] | greater than 0 and below 100 revolutions per day |
 * | [eccentricity] | 0 ... below 1 |
 * | [inclination] | 0 ... 180 |
 * | [rightAscensionOfAscendingNode], [argumentOfPericenter], [meanAnomaly] | 0 ... 360 |
 * | [bstar] | magnitude at most 10 |
 * | [meanMotionDot], [meanMotionDdot] | magnitude at most 100 (SGP4 does not use them) |
 * | [ephemerisType] | 0 ... 9 |
 * | [elementSetNumber] | 0 ... 99 999 |
 * | [revolutionNumberAtEpoch] | 0 ... 9 999 999 |
 *
 * Names and designators are informational: control characters are removed and the text is capped.
 *
 * @property name The object name, e.g. "ISS (ZARYA)", if known (at most 80 characters).
 * @property objectId The international designator in CelesTrak's spelling ("1998-067A"), if known.
 * @property catalogNumber The NORAD catalog number.
 * @property classification The security classification letter, normally `U`.
 * @property epoch The epoch of the elements, UTC.
 * @property meanMotion Mean motion (Kozai mean, as published) in revolutions per day.
 * @property eccentricity Mean eccentricity.
 * @property inclination Mean inclination in degrees.
 * @property rightAscensionOfAscendingNode Mean right ascension of the ascending node in degrees.
 * @property argumentOfPericenter Mean argument of perigee in degrees.
 * @property meanAnomaly Mean anomaly in degrees.
 * @property ephemerisType The ephemeris type digit (0 for SGP4/SDP4 elements).
 * @property elementSetNumber The element set number.
 * @property revolutionNumberAtEpoch The revolution count at epoch.
 * @property bstar The SGP4 drag term B* in inverse Earth radii.
 * @property meanMotionDot First derivative of the mean motion divided by two, in revolutions per day squared.
 * @property meanMotionDdot Second derivative of the mean motion divided by six, in revolutions per day cubed.
 */
@ConsistentCopyVisibility
data class OrbitalElements private constructor(
    val name: String?,
    val objectId: String?,
    val catalogNumber: Int,
    val classification: Char,
    val epoch: Instant,
    val meanMotion: Double,
    val eccentricity: Double,
    val inclination: Double,
    val rightAscensionOfAscendingNode: Double,
    val argumentOfPericenter: Double,
    val meanAnomaly: Double,
    val ephemerisType: Int,
    val elementSetNumber: Int,
    val revolutionNumberAtEpoch: Int,
    val bstar: Double,
    val meanMotionDot: Double,
    val meanMotionDdot: Double,
    /** Julian Day (at 0h, so a half-integer) of the epoch's date, as the SGP4 reference computes it. */
    internal val epochJulianDay: Double,
    /** Fraction of the day of the epoch, as the SGP4 reference computes it. */
    internal val epochJulianDayFraction: Double,
) {
    /** The orbital period in minutes, `1440 / meanMotion`. */
    val periodMinutes: Double get() = 1_440.0 / meanMotion

    /** The epoch in days since 1949 December 31 00:00 UT, the time scale of the SGP4 reference code. */
    internal val sgp4EpochDays: Double get() = (epochJulianDay + epochJulianDayFraction) - sgp4EpochOffset

    /**
     * The age of the element set at [date] in days: positive when [date] is after the epoch. SGP4's error grows
     * by roughly 1 to 3 km per day for low orbits, so refresh stale sets (see the notes in the API document).
     */
    fun ageDays(date: Instant): Double = date.timeIntervalSince(epoch) / JulianDate.secondsPerDay

    companion object {
        /** The longest accepted name, in characters. */
        const val maximumNameLength: Int = 80

        /** The longest accepted international designator, in characters. */
        const val maximumObjectIdLength: Int = 20

        /** Julian Day of 1949 December 31 00:00 UT, the zero of the SGP4 reference time scale. */
        internal const val sgp4EpochOffset: Double = 2_433_281.5

        /**
         * Validates the values and creates an element set.
         *
         * @param epoch The epoch (UTC), years 1957 ... 2099.
         * @throws ElementSetError.ValueOutOfRange if a number is not finite or outside its range.
         * @throws ElementSetError.InvalidField if the classification is not an upper-case letter.
         */
        fun create(
            name: String? = null,
            objectId: String? = null,
            catalogNumber: Int,
            classification: Char = 'U',
            epoch: Instant,
            meanMotion: Double,
            eccentricity: Double,
            inclination: Double,
            rightAscensionOfAscendingNode: Double,
            argumentOfPericenter: Double,
            meanAnomaly: Double,
            ephemerisType: Int = 0,
            elementSetNumber: Int = 0,
            revolutionNumberAtEpoch: Int = 0,
            bstar: Double = 0.0,
            meanMotionDot: Double = 0.0,
            meanMotionDdot: Double = 0.0,
        ): OrbitalElements {
            val seconds = epoch.timeIntervalSince1970
            if (!seconds.isFinite()) throw ElementSetError.ValueOutOfRange("EPOCH", seconds)
            var day = floor(seconds / JulianDate.secondsPerDay)
            if (!(day > -1.0e7 && day < 1.0e7)) throw ElementSetError.ValueOutOfRange("EPOCH", seconds)
            var secondOfDay = seconds - day * JulianDate.secondsPerDay
            if (secondOfDay < 0.0) {
                day -= 1.0
                secondOfDay += JulianDate.secondsPerDay
            } else if (secondOfDay >= JulianDate.secondsPerDay) {
                day += 1.0
                secondOfDay -= JulianDate.secondsPerDay
            }
            val civil = ElementCalendar.civilFromDays(day.toLong())
            val hour = floor(secondOfDay / 3_600.0)
            val minute = floor((secondOfDay - hour * 3_600.0) / 60.0)
            val second = secondOfDay - hour * 3_600.0 - minute * 60.0
            return make(
                name, objectId, catalogNumber, classification,
                civil.year, civil.month, civil.day, hour.toInt(), minute.toInt(), second,
                meanMotion, eccentricity, inclination, rightAscensionOfAscendingNode, argumentOfPericenter,
                meanAnomaly, ephemerisType, elementSetNumber, revolutionNumberAtEpoch, bstar, meanMotionDot,
                meanMotionDdot,
            )
        }

        /**
         * Validates the values and creates an element set from the calendar components of the epoch. The
         * parsers use this so the SGP4 epoch is computed exactly as the reference code does.
         */
        internal fun make(
            name: String?,
            objectId: String?,
            catalogNumber: Int,
            classification: Char,
            year: Int,
            month: Int,
            day: Int,
            hour: Int,
            minute: Int,
            second: Double,
            meanMotion: Double,
            eccentricity: Double,
            inclination: Double,
            rightAscensionOfAscendingNode: Double,
            argumentOfPericenter: Double,
            meanAnomaly: Double,
            ephemerisType: Int,
            elementSetNumber: Int,
            revolutionNumberAtEpoch: Int,
            bstar: Double,
            meanMotionDot: Double,
            meanMotionDdot: Double,
        ): OrbitalElements {
            if (year < 1957 || year > 2099) throw ElementSetError.ValueOutOfRange("EPOCH", year.toDouble())
            if (month < 1 || month > 12) throw ElementSetError.ValueOutOfRange("EPOCH", month.toDouble())
            if (day < 1 || day > ElementCalendar.daysInMonth(year, month)) {
                throw ElementSetError.ValueOutOfRange("EPOCH", day.toDouble())
            }
            if (hour < 0 || hour > 23) throw ElementSetError.ValueOutOfRange("EPOCH", hour.toDouble())
            if (minute < 0 || minute > 59) throw ElementSetError.ValueOutOfRange("EPOCH", minute.toDouble())
            if (!(second >= 0.0 && second < 60.0)) throw ElementSetError.ValueOutOfRange("EPOCH", second)
            if (catalogNumber < 0 || catalogNumber > 999_999_999) {
                throw ElementSetError.ValueOutOfRange("NORAD_CAT_ID", catalogNumber.toDouble())
            }
            if (classification !in 'A'..'Z') throw ElementSetError.InvalidField("CLASSIFICATION_TYPE", classification.toString())
            checkRange("MEAN_MOTION", meanMotion, 0.0, 100.0, lowerOpen = true, upperOpen = true)
            checkRange("ECCENTRICITY", eccentricity, 0.0, 1.0, lowerOpen = false, upperOpen = true)
            checkRange("INCLINATION", inclination, 0.0, 180.0)
            checkRange("RA_OF_ASC_NODE", rightAscensionOfAscendingNode, 0.0, 360.0)
            checkRange("ARG_OF_PERICENTER", argumentOfPericenter, 0.0, 360.0)
            checkRange("MEAN_ANOMALY", meanAnomaly, 0.0, 360.0)
            checkRange("BSTAR", bstar, -10.0, 10.0)
            checkRange("MEAN_MOTION_DOT", meanMotionDot, -100.0, 100.0)
            checkRange("MEAN_MOTION_DDOT", meanMotionDdot, -100.0, 100.0)
            checkRange("EPHEMERIS_TYPE", ephemerisType.toDouble(), 0.0, 9.0)
            checkRange("ELEMENT_SET_NO", elementSetNumber.toDouble(), 0.0, 99_999.0)
            checkRange("REV_AT_EPOCH", revolutionNumberAtEpoch.toDouble(), 0.0, 9_999_999.0)

            val julian = ElementCalendar.julianDay(year, month, day, hour, minute, second)
            val unixSeconds = ElementCalendar.daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L
            val epoch = instantFromTimeIntervalSinceReferenceDate(
                (unixSeconds - referenceDateUnixSeconds).toDouble() + second,
            )
            return OrbitalElements(
                name = cleanText(name, maximumNameLength),
                objectId = cleanText(objectId, maximumObjectIdLength),
                catalogNumber = catalogNumber,
                classification = classification,
                epoch = epoch,
                meanMotion = meanMotion,
                eccentricity = eccentricity,
                inclination = inclination,
                rightAscensionOfAscendingNode = rightAscensionOfAscendingNode,
                argumentOfPericenter = argumentOfPericenter,
                meanAnomaly = meanAnomaly,
                ephemerisType = ephemerisType,
                elementSetNumber = elementSetNumber,
                revolutionNumberAtEpoch = revolutionNumberAtEpoch,
                bstar = bstar,
                meanMotionDot = meanMotionDot,
                meanMotionDdot = meanMotionDdot,
                epochJulianDay = julian.day,
                epochJulianDayFraction = julian.fraction,
            )
        }

        /** Seconds from 1970-01-01T00:00:00Z to the Foundation reference date 2001-01-01T00:00:00Z. */
        private const val referenceDateUnixSeconds: Long = 978_307_200L

        private fun checkRange(
            field: String,
            value: Double,
            lower: Double,
            upper: Double,
            lowerOpen: Boolean = false,
            upperOpen: Boolean = false,
        ) {
            val inside = value.isFinite() &&
                (if (lowerOpen) value > lower else value >= lower) &&
                (if (upperOpen) value < upper else value <= upper)
            if (!inside) throw ElementSetError.ValueOutOfRange(field, value)
        }

        /** Removes control characters, trims, caps the length; `null` when nothing is left. */
        internal fun cleanText(text: String?, maximumLength: Int): String? {
            if (text == null) return null
            val kept = StringBuilder(minOf(text.length, maximumLength))
            for (character in text) {
                if (character.code >= 32 && character.code != 127) kept.append(character)
            }
            val trimmed = kept.toString().trim()
            if (trimmed.isEmpty()) return null
            return if (trimmed.length > maximumLength) trimmed.substring(0, maximumLength).trim() else trimmed
        }
    }
}

/** A civil calendar date. */
internal class ElementCivilDate(val year: Int, val month: Int, val day: Int)

/** A Julian Day split as the SGP4 reference does: the day at 0h and the fraction of the day. */
internal class ElementJulianDay(val day: Double, val fraction: Double)

/** Month, day and time of day found from a day of the year (the reference's `days2mdhms`). */
internal class ElementMonthDayTime(val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Double)

/** Calendar arithmetic for element epochs, written to reproduce the SGP4 reference code exactly. */
internal object ElementCalendar {
    /** `true` for leap years of the Gregorian calendar. */
    fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    /** Days in [month] (1 ... 12) of [year]. */
    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (isLeapYear(year)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days from 1970-01-01 to the given proleptic Gregorian date (Howard Hinnant's algorithm). */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = Math.floorDiv(y, 400L)
        val yearOfEra = y - era * 400
        val shifted = (month + (if (month > 2) -3 else 9)).toLong()
        val dayOfYear = (153 * shifted + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    /** The civil date [days] days after 1970-01-01. */
    fun civilFromDays(days: Long): ElementCivilDate {
        val shifted = days + 719_468
        val era = Math.floorDiv(shifted, 146_097L)
        val dayOfEra = shifted - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthIndex = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * monthIndex + 2) / 5 + 1).toInt()
        val month = (if (monthIndex < 10) monthIndex + 3 else monthIndex - 9).toInt()
        val year = (yearOfEra + era * 400 + (if (month <= 2) 1 else 0)).toInt()
        return ElementCivilDate(year, month, day)
    }

    /** The reference's `jday_SGP4`: Julian Day at 0h and fraction of the day. Valid for 1901 ... 2099. */
    fun julianDay(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Double): ElementJulianDay {
        var julian = 367.0 * year -
            floor((7 * (year + floor((month + 9) / 12.0))) * 0.25) +
            floor(275 * month / 9.0) +
            day + 1_721_013.5
        var fraction = (second + minute * 60.0 + hour * 3_600.0) / 86_400.0
        if (abs(fraction) > 1.0) {
            val whole = floor(fraction)
            julian += whole
            fraction -= whole
        }
        return ElementJulianDay(julian, fraction)
    }

    /** The reference's `days2mdhms_SGP4`: month, day and time of day from the day of the year (1.0 = Jan 1 0h). */
    fun monthDayTime(year: Int, days: Double): ElementMonthDayTime {
        val monthLengths = intArrayOf(0, 31, if (year % 4 == 0) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        val dayOfYear = floor(days).toInt()
        var index = 1
        var elapsed = 0
        while (dayOfYear > elapsed + monthLengths[index] && index < 12) {
            elapsed += monthLengths[index]
            index++
        }
        val day = dayOfYear - elapsed
        var temp = (days - dayOfYear) * 24.0
        val hour = floor(temp).toInt()
        temp = (temp - hour) * 60.0
        val minute = floor(temp).toInt()
        val second = (temp - minute) * 60.0
        return ElementMonthDayTime(index, day, hour, minute, second)
    }
}
