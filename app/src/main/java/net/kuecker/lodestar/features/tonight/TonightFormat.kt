package net.kuecker.lodestar.features.tonight

import android.text.format.DateFormat
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.shared.AppFormat
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Text for the Tonight cards: time windows, durations, directions and clock positions. */
internal object TonightFormat {
    private val hourFormatters = HashMap<String, DateTimeFormatter>()
    private val combiningMarks = Regex("\\p{Mn}+")

    // Time

    /** A window of time in the observer's time zone, such as "21:40 – 04:12". */
    fun window(interval: DateInterval, zone: ZoneId): String =
        "${AppFormat.time(interval.start, zone)} – ${AppFormat.time(interval.end, zone)}"

    /** Every window of a list, separated by commas; the placeholder for an empty list. */
    fun windows(intervals: List<DateInterval>, zone: ZoneId): String {
        if (intervals.isEmpty()) return AppFormat.MISSING
        return intervals.joinToString(", ") { window(it, zone) }
    }

    /** The windows as a spoken phrase, such as "9:40 PM to 4:12 AM". */
    fun spokenWindows(intervals: List<DateInterval>, zone: ZoneId): String =
        intervals.joinToString(" and ") { "${AppFormat.time(it.start, zone)} to ${AppFormat.time(it.end, zone)}" }

    /** A length of time in hours and minutes, such as "6h 32m" or "45m". */
    fun duration(hours: Double): String {
        if (!hours.isFinite() || hours <= 0) return "0m"
        val minutes = (hours * 60).roundedHalfAwayFromZero().toInt()
        if (minutes < 60) return "${minutes}m"
        val remainder = minutes % 60
        return "${minutes / 60}h ${if (remainder < 10) "0" else ""}${remainder}m"
    }

    /** How far a night is from tonight: "Tonight", "Tomorrow night", "3 nights ago". */
    fun nightLabel(nightsFromTonight: Int): String = when {
        nightsFromTonight == 0 -> "Tonight"
        nightsFromTonight == 1 -> "Tomorrow night"
        nightsFromTonight == -1 -> "Last night"
        nightsFromTonight >= 2 -> "In $nightsFromTonight nights"
        else -> "${-nightsFromTonight} nights ago"
    }

    /** The zone's usual abbreviation at [date], such as "MDT". */
    fun zoneName(zone: ZoneId, date: Instant): String {
        val name = DateTimeFormatter.ofPattern("zzz", Locale.getDefault()).withZone(zone).format(date)
        return name.ifEmpty { zone.id }
    }

    /**
     * A whole hour as the time axis labels it: "21" or "9 PM", following the user's 12/24-hour preference like
     * [AppFormat.time].
     */
    fun hourLabel(date: Instant, zone: ZoneId): String {
        val locale = Locale.getDefault()
        val skeleton = if (AppFormat.use24HourClock) "H" else "h"
        return synchronized(hourFormatters) {
            hourFormatters.getOrPut("${locale.toLanguageTag()}|$skeleton") {
                DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            }
        }.withZone(zone).format(date)
    }

    // Directions

    /** From this altitude up, a target is so close to the zenith that its compass direction means little. */
    const val overheadAltitude: Double = 85.0

    /** A compass point and an azimuth, such as "SSE · 158°", or "Overhead" near the zenith. */
    fun direction(azimuth: Double, altitude: Double): String {
        if (!(altitude < overheadAltitude)) return "Overhead"
        val point = AngleFormatter.compassPoint(azimuth).abbreviation
        return "$point · ${AngleFormatter.formatAzimuth(azimuth, 0)}"
    }

    /** Where to look, as a short phrase: "facing SSE", or "overhead" near the zenith. */
    fun facing(azimuth: Double, altitude: Double): String {
        if (!(altitude < overheadAltitude)) return "overhead"
        return "facing " + AngleFormatter.compassPoint(azimuth).abbreviation
    }

    /** The same phrase for a screen reader: "facing south-southeast". */
    fun spokenFacing(azimuth: Double, altitude: Double): String {
        if (!(altitude < overheadAltitude)) return "overhead"
        return "facing " + AngleFormatter.compassPoint(azimuth).fullName.lowercase(Locale.ROOT)
    }

    /** The clock time for a direction measured clockwise from 12 o'clock: 30° per hour, so 315° is "10:30". */
    fun clockPosition(angle: Double): String {
        if (!angle.isFinite()) return AppFormat.MISSING
        val totalMinutes = (AstroMath.normalizeDegrees(angle) * 2).roundedHalfAwayFromZero().toInt() % 720
        val hour = if (totalMinutes / 60 == 0) 12 else totalMinutes / 60
        val minutes = totalMinutes % 60
        return "$hour:${if (minutes < 10) "0" else ""}$minutes"
    }

    // Objects

    private fun folded(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase(Locale.ROOT)

    /** The object's first designation when it differs from its name, such as "M 31" for the Andromeda Galaxy. */
    fun designation(obj: CelestialObject): String? {
        val first = obj.designations.firstOrNull() ?: return null
        return if (folded(first) == folded(obj.name)) null else first
    }

    /** Type, constellation, magnitude and size on one line: "Galaxy · Andromeda · mag 3.4 · 178′ × 70′". */
    fun description(obj: CelestialObject): String {
        val parts = arrayListOf(obj.category.displayName)
        obj.constellationName?.let(parts::add)
        obj.magnitude?.let { parts.add(AppFormat.magnitude(it)) }
        obj.angularSize?.let { parts.add(it.description) }
        return parts.joinToString(" · ")
    }
}
