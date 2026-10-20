package net.kuecker.lodestar.shared

import android.text.format.DateFormat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.kit.formatting.*
import net.kuecker.lodestar.model.CoordinateStyle
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * App-level formatting: times in the observer's time zone and coordinates in the user's preferred style. Angle
 * text comes from the kit's [AngleFormatter].
 */
object AppFormat {
    /** Placeholder for unknown values. */
    const val MISSING = "—"

    /** The user's 12/24-hour preference; refreshed by the activity when it starts. Compose state, so times redraw. */
    var use24HourClock by mutableStateOf(true)

    // Time

    /** Short local time, e.g. "21:34" or "9:34 PM" (follows the user's 12/24-hour preference). */
    fun time(date: Instant?, zone: ZoneId): String {
        date ?: return MISSING
        return formatter(if (use24HourClock) "Hm" else "hm").withZone(zone).format(date)
    }

    /** Abbreviated date with the year and short time, e.g. "Oct 17, 2026, 21:34". */
    fun dateTime(date: Instant, zone: ZoneId): String =
        formatter(if (use24HourClock) "yMMMdHm" else "yMMMdhm").withZone(zone).format(date)

    /** Weekday and date, e.g. "Fri, Oct 17". */
    fun day(date: Instant, zone: ZoneId): String = formatter("EEEMMMd").withZone(zone).format(date)

    /** Relative description, e.g. "in 2h 05m" or "12m ago". */
    fun relative(date: Instant, now: Instant): String {
        val interval = (date.toEpochMilli() - now.toEpochMilli()) / 1000.0
        val text = AngleFormatter.formatDuration(abs(interval))
        return if (interval >= 0) "in $text" else "$text ago"
    }

    // Angles

    fun rightAscension(coordinate: EquatorialCoordinate, style: CoordinateStyle): String = when (style) {
        CoordinateStyle.SEXAGESIMAL -> AngleFormatter.formatRightAscension(coordinate.rightAscensionHours)
        CoordinateStyle.DECIMAL -> AngleFormatter.formatDegrees(coordinate.rightAscension, 3)
    }

    fun declination(coordinate: EquatorialCoordinate, style: CoordinateStyle): String = when (style) {
        CoordinateStyle.SEXAGESIMAL -> AngleFormatter.formatDeclination(coordinate.declination)
        CoordinateStyle.DECIMAL -> AngleFormatter.formatSignedDegrees(coordinate.declination, 3)
    }

    /** Altitude with sign, e.g. "+34.2°". */
    fun altitude(degrees: Double): String = AngleFormatter.formatSignedDegrees(degrees, 1)

    /** Azimuth with compass point, e.g. "123.4° SE". */
    fun azimuth(degrees: Double): String =
        "${AngleFormatter.formatAzimuth(degrees, 1)} ${AngleFormatter.compassPoint(degrees).abbreviation}"

    /** An angular distance in the most readable unit: "12.3°", "45′" or "30″". */
    fun separation(degrees: Double): String {
        if (!degrees.isFinite()) return MISSING
        val magnitude = abs(degrees)
        if (magnitude >= 1) return AngleFormatter.formatDegrees(magnitude, if (magnitude >= 10) 0 else 1)
        if (magnitude * 60 >= 1) return "${(magnitude * 60).roundedHalfAwayFromZero().toInt()}′"
        return "${(magnitude * 3600).roundedHalfAwayFromZero().toInt()}″"
    }

    /** Apparent magnitude, e.g. "mag 3.4" or "mag −1.5". */
    fun magnitude(value: Double?): String {
        if (value == null || !value.isFinite()) return MISSING
        val format = NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        return "mag ${if (value < 0) "−" else ""}${format.format(abs(value))}"
    }

    /** A percentage with no decimals, e.g. "64%". */
    fun percent(fraction: Double?): String {
        if (fraction == null || !fraction.isFinite()) return MISSING
        return "${(fraction * 100).roundedHalfAwayFromZero().toInt()}%"
    }

    // Line breaks

    /** The no-break space: a value stays with its unit, and a catalogue prefix with its number, across a line wrap. */
    const val NO_BREAK_SPACE = ' '

    /** [text] with every space a no-break space, so it moves to the next line whole ("mag 3.4", "NGC 7000", "6h 10m"). */
    fun unbreakable(text: String): String = text.replace(' ', NO_BREAK_SPACE)

    /**
     * A " · "-separated [line] in which each part stays whole and the dot stays with the part before it: a narrow line
     * breaks only after a dot, so no value is split from its unit and no line starts with a dot.
     */
    fun unbreakableParts(line: String): String =
        line.split(" · ").joinToString("${NO_BREAK_SPACE}· ") { unbreakable(it) }

    private val formatters = HashMap<String, DateTimeFormatter>()

    private fun formatter(skeleton: String): DateTimeFormatter = synchronized(formatters) {
        val locale = Locale.getDefault()
        formatters.getOrPut("${locale.toLanguageTag()}|$skeleton") {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        }
    }
}
