package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.timeIntervalSince1970
import java.time.Instant
import kotlin.math.floor

/** A UTC calendar date and time of day. */
data class MountDateTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val millisecond: Int,
)

/** Calendar arithmetic in UTC, identical in both languages (civil-from-days of H. Hinnant, no time zone database). */
internal object MountCalendar {
    /** The UTC fields of [date], or null if the date is outside the years 1970 to 9999. */
    fun fields(date: Instant): MountDateTime? {
        val total = date.timeIntervalSince1970
        if (!total.isFinite() || total < 0 || total > 253_402_300_799.0) return null
        val whole = floor(total)
        val seconds = whole.toLong()
        val millisecond = ((total - whole) * 1000).toInt().coerceIn(0, 999)
        val days = seconds / 86_400
        val secondOfDay = (seconds % 86_400).toInt()
        val z = days + 719_468
        val era = z / 146_097
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val shiftedMonth = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * shiftedMonth + 2) / 5 + 1).toInt()
        val month = (if (shiftedMonth < 10) shiftedMonth + 3 else shiftedMonth - 9).toInt()
        val year = (yearOfEra + era * 400 + (if (month <= 2) 1 else 0)).toInt()
        return MountDateTime(
            year, month, day, secondOfDay / 3600, secondOfDay % 3600 / 60, secondOfDay % 60, millisecond,
        )
    }

    fun twoDigits(value: Int): String = if (value in 0..9) "0$value" else value.toString()

    fun threeDigits(value: Int): String = when {
        value in 0..9 -> "00$value"
        value in 10..99 -> "0$value"
        else -> value.toString()
    }
}
