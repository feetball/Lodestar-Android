package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.DateInterval
import java.time.Duration
import java.time.Instant
import kotlin.math.min

/**
 * One stretch of the night with its cloud cover in percent and the weather of its forecast time (see [CloudHour]): the
 * time from a forecast time to the next one, cut to the night.
 */
class CloudSlot(
    val start: Instant,
    val end: Instant,
    val total: Double,
    val low: Double,
    val medium: Double,
    val high: Double,
    val temperature: Double? = null,
    val dewPoint: Double? = null,
    val humidity: Double? = null,
    val windSpeed: Double? = null,
    val windGust: Double? = null,
    val windFromDirection: Double? = null,
    val fog: Double? = null,
) {
    /** The length in seconds. */
    val seconds: Double get() = Duration.between(start, end).toMillis() / 1000.0

    /** Air temperature minus dew point in °C, never below 0; `null` when either is missing. */
    val dewPointSpread: Double? get() = ForecastNight.dewPointSpread(temperature, dewPoint)
}

/**
 * Lens fogging: the dew-point spread (air temperature minus dew point) is below [ForecastNight.dewSpreadBelow] °C.
 *
 * @property from The start of the first slot of the night with such a spread.
 * @property minimumSpread The smallest spread of the night in °C.
 */
class DewWarning(val from: Instant, val minimumSpread: Double) {
    /** The spread gets below [ForecastNight.dewVeryLikelyBelow] °C. */
    val isVeryLikely: Boolean get() = minimumSpread < ForecastNight.dewVeryLikelyBelow
}

/** How much the wind matters for a long exposure, by the strongest sustained wind of the night. */
enum class WindLevel(val rawValue: String) {
    /** Under [ForecastNight.windBreezyFrom] m/s. */
    CALM("calm"),

    /** From [ForecastNight.windBreezyFrom] m/s: long exposures may shake. */
    BREEZY("breezy"),

    /** From [ForecastNight.windWindyFrom] m/s: weigh the tripod down. */
    WINDY("windy"),

    /** From [ForecastNight.windStrongFrom] m/s. */
    STRONG("strong"),
}

/**
 * The wind of the night.
 *
 * @property maximum The strongest sustained wind in m/s.
 * @property gust The strongest gust in m/s, when the forecast has gusts for the night.
 * @property fromDirection Where the strongest wind blows from, in degrees clockwise from north, when known.
 */
class WindSummary(val maximum: Double, val gust: Double?, val fromDirection: Double?) {
    /** The level of [maximum]. */
    val level: WindLevel
        get() = when {
            maximum >= ForecastNight.windStrongFrom -> WindLevel.STRONG
            maximum >= ForecastNight.windWindyFrom -> WindLevel.WINDY
            maximum >= ForecastNight.windBreezyFrom -> WindLevel.BREEZY
            else -> WindLevel.CALM
        }
}

/**
 * Fog likely during the night: the forecast times with a fog area fraction of at least [ForecastNight.fogFrom] percent.
 *
 * @property from The start of the first such slot.
 * @property seconds The time of the night they cover together.
 */
class FogSummary(val from: Instant, val seconds: Double)

/** The air temperature range of the night in °C. */
class TemperatureRange(val minimum: Double, val maximum: Double)

/** One row of the night's hourly table: the forecast of one slot ([time] is the start of the slot). */
class NightHour(
    val time: Instant,
    val cloud: Double,
    val temperature: Double?,
    val dewPointSpread: Double?,
    val windSpeed: Double?,
    val windGust: Double?,
    val humidity: Double?,
)

/** What the forecast says about the night besides the clouds. Every part is `null` (or empty) where the forecast has no data. */
class NightConditions(
    val dew: DewWarning?,
    val wind: WindSummary?,
    val fog: FogSummary?,
    val temperature: TemperatureRange?,
    val hourly: List<NightHour>,
)

/** What the night's cloud forecast says, in numbers and in words. Pure. */
object ForecastNight {
    /** A slot with less total cloud than this (percent) counts as clear. */
    const val clearBelow = 30.0

    /** A night with at least this much cloud on average (percent) counts as cloudy. */
    const val cloudyFrom = 70.0

    /** The shortest clear stretch worth naming, in seconds (shorter nights name half their length). */
    const val minimumClearSpellSeconds = 2 * 3600.0

    /** The dew-point spread (°C) below which dew forms on a lens. */
    const val dewSpreadBelow = 2.0

    /** A spread (°C) below this makes lens fogging very likely. */
    const val dewVeryLikelyBelow = 1.0

    /** The sustained wind (m/s) from which long exposures may shake. */
    const val windBreezyFrom = 5.0

    /** The sustained wind (m/s) from which the tripod needs weight. */
    const val windWindyFrom = 8.0

    /** The sustained wind (m/s) that counts as strong. */
    const val windStrongFrom = 12.0

    /** A fog area fraction (percent) from which a slot counts as foggy. */
    const val fogFrom = 50.0

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
            slots.add(
                CloudSlot(
                    start, end, entry.total, entry.low, entry.medium, entry.high,
                    entry.temperature, entry.dewPoint, entry.humidity, entry.windSpeed, entry.windGust,
                    entry.windFromDirection, entry.fog,
                ),
            )
        }
        return slots
    }

    /** The time-weighted mean total cloud of [slots] in percent. */
    fun meanTotal(slots: List<CloudSlot>): Double {
        val seconds = slots.sumOf { it.seconds }
        if (seconds <= 0) return 0.0
        return slots.sumOf { it.total * it.seconds } / seconds
    }

    /** Air temperature minus dew point in °C, never below 0 (a dew point above the air is rounding); `null` when either is `null`. */
    fun dewPointSpread(temperature: Double?, dewPoint: Double?): Double? =
        if (temperature != null && dewPoint != null) maxOf(temperature - dewPoint, 0.0) else null

    /**
     * Lens fogging in [slots]: from the first slot whose dew-point spread is below [dewSpreadBelow] °C, with the smallest
     * spread of the night. `null` when the spread stays at [dewSpreadBelow] °C or more, or the forecast has no dew point.
     */
    fun dew(slots: List<CloudSlot>): DewWarning? {
        var from: Instant? = null
        var minimum = Double.POSITIVE_INFINITY
        for (slot in slots) {
            val spread = slot.dewPointSpread ?: continue
            if (spread < dewSpreadBelow && from == null) from = slot.start
            if (spread < minimum) minimum = spread
        }
        val start = from ?: return null
        return DewWarning(start, minimum)
    }

    /**
     * The wind in [slots]: the strongest sustained wind (with where it blows from) and the strongest gust. `null` when
     * the forecast has no wind speed for the night; the gust is `null` when it has no gusts.
     */
    fun wind(slots: List<CloudSlot>): WindSummary? {
        var maximum: Double? = null
        var direction: Double? = null
        var gust: Double? = null
        for (slot in slots) {
            val speed = slot.windSpeed
            if (speed != null && (maximum == null || speed > maximum)) {
                maximum = speed
                direction = slot.windFromDirection
            }
            val slotGust = slot.windGust
            if (slotGust != null && (gust == null || slotGust > gust)) gust = slotGust
        }
        val strongest = maximum ?: return null
        return WindSummary(strongest, gust, direction)
    }

    /** The fog in [slots]: the slots with a fog area fraction of at least [fogFrom] percent. `null` when there are none. */
    fun fog(slots: List<CloudSlot>): FogSummary? {
        var from: Instant? = null
        var seconds = 0.0
        for (slot in slots) {
            val fog = slot.fog ?: continue
            if (fog < fogFrom) continue
            if (from == null) from = slot.start
            seconds += slot.seconds
        }
        val start = from ?: return null
        return FogSummary(start, seconds)
    }

    /** The lowest and highest air temperature in [slots]. `null` when the forecast has no temperature for the night. */
    fun temperature(slots: List<CloudSlot>): TemperatureRange? {
        var minimum: Double? = null
        var maximum: Double? = null
        for (slot in slots) {
            val value = slot.temperature ?: continue
            if (minimum == null || value < minimum) minimum = value
            if (maximum == null || value > maximum) maximum = value
        }
        if (minimum == null || maximum == null) return null
        return TemperatureRange(minimum, maximum)
    }

    /** One row per slot of [slots], for the hourly table. */
    fun hourly(slots: List<CloudSlot>): List<NightHour> = slots.map {
        NightHour(it.start, it.total, it.temperature, it.dewPointSpread, it.windSpeed, it.windGust, it.humidity)
    }

    /** Everything the forecast says about the night in [slots] besides the clouds. */
    fun conditions(slots: List<CloudSlot>): NightConditions =
        NightConditions(dew(slots), wind(slots), fog(slots), temperature(slots), hourly(slots))

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
