package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.DateInterval
import java.time.Duration
import java.time.Instant
import kotlin.math.min

/** One stretch of the night with its cloud cover in percent: the time from a forecast time to the next one, cut to the night. */
class CloudSlot(
    val start: Instant,
    val end: Instant,
    val total: Double,
    val low: Double,
    val medium: Double,
    val high: Double,
) {
    /** The length in seconds. */
    val seconds: Double get() = Duration.between(start, end).toMillis() / 1000.0
}

/** What the night's cloud forecast says, in numbers and in words. Pure. */
object ForecastNight {
    /** A slot with less total cloud than this (percent) counts as clear. */
    const val clearBelow = 30.0

    /** A night with at least this much cloud on average (percent) counts as cloudy. */
    const val cloudyFrom = 70.0

    /** The shortest clear stretch worth naming, in seconds (shorter nights name half their length). */
    const val minimumClearSpellSeconds = 2 * 3600.0

    private val hour: Duration = Duration.ofHours(1)

    /**
     * The forecast's slots that overlap [night], cut to it, in time order. Hourly forecast times give one-hour slots;
     * the six-hourly times further ahead give six-hour slots. Empty when the forecast does not reach the night.
     */
    fun slots(forecast: CloudForecast, night: DateInterval): List<CloudSlot> {
        val nightStart = night.start
        val nightEnd = night.end
        if (!nightEnd.isAfter(nightStart)) return emptyList()
        val slots = ArrayList<CloudSlot>()
        val hours = forecast.hours
        for ((index, entry) in hours.withIndex()) {
            val next = hours.getOrNull(index + 1)?.time ?: entry.time.plus(hour)
            val start = maxOf(entry.time, nightStart)
            val end = minOf(next, nightEnd)
            if (!end.isAfter(start)) continue
            slots.add(CloudSlot(start, end, entry.total, entry.low, entry.medium, entry.high))
        }
        return slots
    }

    /** The time-weighted mean total cloud of [slots] in percent. */
    fun meanTotal(slots: List<CloudSlot>): Double {
        val seconds = slots.sumOf { it.seconds }
        if (seconds <= 0) return 0.0
        return slots.sumOf { it.total * it.seconds } / seconds
    }

    /** A stretch of clear sky, from [start] to [end]. */
    class ClearSpell(val start: Instant, val end: Instant) {
        val seconds: Double get() = Duration.between(start, end).toMillis() / 1000.0
    }

    /** The unbroken clear stretches of [slots] (total cloud under [clearBelow]), in time order. */
    fun clearSpells(slots: List<CloudSlot>): List<ClearSpell> {
        val spells = ArrayList<ClearSpell>()
        var start: Instant? = null
        var end: Instant? = null
        for (slot in slots) {
            val isClear = slot.total < clearBelow
            val open = end
            if (isClear && start != null && open != null && slot.start == open) {
                end = slot.end
                continue
            }
            if (start != null && end != null) spells.add(ClearSpell(start, end))
            start = if (isClear) slot.start else null
            end = if (isClear) slot.end else null
        }
        if (start != null && end != null) spells.add(ClearSpell(start, end))
        return spells
    }

    /**
     * The clear spells in a few words, for example "Mostly clear after 23:00", "Mostly clear until 01:00", "Clear from
     * 22:00 to 02:00", "Clear all night", "Cloudy all night" or "No long clear spell". [timeText] formats a time of day.
     * `null` for no slots.
     */
    fun summary(slots: List<CloudSlot>, timeText: (Instant) -> String): String? {
        if (slots.isEmpty()) return null
        val windowStart = slots.first().start
        val windowEnd = slots.last().end
        val total = slots.sumOf { it.seconds }
        val spells = clearSpells(slots)
        val clearSeconds = spells.sumOf { it.seconds }
        if (clearSeconds >= 0.9 * total) return "Clear all night"
        val longest = spells.maxByOrNull { it.seconds }
        if (longest == null || longest.seconds < min(minimumClearSpellSeconds, total / 2)) {
            return if (meanTotal(slots) >= cloudyFrom) "Cloudy all night" else "No long clear spell"
        }
        val touchesStart = longest.start == windowStart
        val touchesEnd = longest.end == windowEnd
        return when {
            touchesEnd -> "Mostly clear after ${timeText(longest.start)}"
            touchesStart -> "Mostly clear until ${timeText(longest.end)}"
            else -> "Clear from ${timeText(longest.start)} to ${timeText(longest.end)}"
        }
    }

    /**
     * The sentence a screen reader reads for the cloud strip: the span, the extremes of the total cloud and the
     * [summary]. `null` for no slots.
     */
    fun spokenSummary(slots: List<CloudSlot>, timeText: (Instant) -> String): String? {
        if (slots.isEmpty()) return null
        val clearest = slots.minBy { it.total }
        val cloudiest = slots.maxBy { it.total }
        val parts = ArrayList<String>()
        parts.add("Cloud cover from ${timeText(slots.first().start)} to ${timeText(slots.last().end)}.")
        parts.add(
            "Total cloud runs from ${clearest.total.toInt()} percent at ${timeText(clearest.start)} " +
                "to ${cloudiest.total.toInt()} percent at ${timeText(cloudiest.start)}.",
        )
        summary(slots, timeText)?.let { parts.add("$it.") }
        return parts.joinToString(" ")
    }
}
