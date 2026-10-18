package net.kuecker.lodestar.features.settings

import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.shared.AppFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Text for locations, accuracies and time zones in Settings and Onboarding. */
internal object SettingsFormat {
    /** "40.0150° N, 105.2705° W". */
    fun coordinates(location: GeographicLocation): String =
        "${AngleFormatter.formatLatitude(location.latitude)}, ${AngleFormatter.formatLongitude(location.longitude)}"

    /** Whole metres above sea level, e.g. "1650 m". */
    fun elevation(metres: Double): String {
        val format = NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }
        return "${format.format(metres)} m"
    }

    /** A position error radius, e.g. "±35 m" or "±2.4 km". */
    fun accuracy(metres: Double): String {
        if (!metres.isFinite() || metres < 0) return AppFormat.MISSING
        if (metres < 1000) return "±${metres.roundedHalfAwayFromZero().toInt()} m"
        return "±${decimal(metres / 1000, 1)} km"
    }

    /** [value] with exactly [fractionDigits] decimals in the user's number format. */
    fun decimal(value: Double, fractionDigits: Int): String {
        val format = NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = fractionDigits
            maximumFractionDigits = fractionDigits
        }
        return format.format(value)
    }

    /** The offset from UTC, e.g. "UTC−6" or "UTC+5:30". */
    fun utcOffset(zone: ZoneId, at: Instant): String {
        val totalMinutes = zone.rules.getOffset(at).totalSeconds / 60
        val sign = if (totalMinutes < 0) "−" else "+"
        val hours = abs(totalMinutes) / 60
        val minutes = abs(totalMinutes) % 60
        if (minutes == 0) return "UTC$sign$hours"
        return "UTC$sign$hours:${if (minutes < 10) "0" else ""}$minutes"
    }

    /** Whether [zone] is the fixed-offset fallback derived from a longitude rather than a real regional zone. */
    fun isNominal(zone: ZoneId): Boolean = zone.id.startsWith("GMT")

    /** The zone's name and offset, e.g. "America/New York · UTC−5", or the offset alone for an estimated zone. */
    fun describe(zone: ZoneId, at: Instant): String {
        val offset = utcOffset(zone, at)
        if (isNominal(zone)) return "$offset (estimated from longitude)"
        return "${zone.id.replace('_', ' ')} · $offset"
    }
}
