package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import java.time.Instant

/** What a chip under the cloud strip is about. */
enum class ForecastChipKind(val rawValue: String) {
    DEW("dew"),
    WIND("wind"),
    FOG("fog"),
    TEMPERATURE("temperature"),
}

/**
 * One chip of the forecast card: [text] is what it shows, [spoken] what a screen reader says, [isWarning] whether it
 * is drawn in the warning tone.
 */
class ForecastChip(val kind: ForecastChipKind, val text: String, val spoken: String, val isWarning: Boolean)

/**
 * The words of the night's dew, wind, fog and temperature on the forecast card. The iOS app has the same texts.
 * [timeText] formats a time of day in the observer's time zone.
 */
object ForecastWords {
    /** Shown for a value the forecast does not have. */
    const val MISSING = "—"

    const val BREEZY = "Breezy: long exposures may shake"
    const val WINDY = "Windy: weigh the tripod down"
    const val STRONG = "Strong wind: expect shaky frames"

    /** "Lens fogging likely from 23:40", "very likely" when the spread gets under [ForecastNight.dewVeryLikelyBelow] °C. */
    fun dewHint(warning: DewWarning, timeText: (Instant) -> String): String =
        "Lens fogging ${if (warning.isVeryLikely) "very likely" else "likely"} from ${timeText(warning.from)}"

    /** The advice for [level]; `null` for a calm night. */
    fun windHint(level: WindLevel): String? = when (level) {
        WindLevel.CALM -> null
        WindLevel.BREEZY -> BREEZY
        WindLevel.WINDY -> WINDY
        WindLevel.STRONG -> STRONG
    }

    /** The one line for the most important warning of the night: the dew first, then the wind. `null` when there is none. */
    fun hint(conditions: NightConditions, timeText: (Instant) -> String): String? {
        conditions.dew?.let { return dewHint(it, timeText) }
        return conditions.wind?.let { windHint(it.level) }
    }

    /** The chips for [conditions] in the order dew, wind, fog, temperature; only those the forecast has something for. */
    fun chips(conditions: NightConditions, system: ForecastUnitSystem, timeText: (Instant) -> String): List<ForecastChip> {
        val chips = ArrayList<ForecastChip>(4)
        conditions.dew?.let { dew ->
            val spoken = "${dewHint(dew, timeText)}. The air gets within ${ForecastUnits.spokenDifference(dew.minimumSpread, system)} " +
                "of the dew point."
            chips.add(ForecastChip(ForecastChipKind.DEW, "Dew from ${timeText(dew.from)}", spoken, isWarning = true))
        }
        conditions.wind?.let { wind -> chips.add(windChip(wind, system)) }
        conditions.fog?.let { fog ->
            val hours = maxOf(1, (fog.seconds / 3600.0).roundedHalfAwayFromZero().toInt())
            val spoken = "Fog expected for about $hours ${if (hours == 1) "hour" else "hours"} from ${timeText(fog.from)}."
            chips.add(ForecastChip(ForecastChipKind.FOG, "Fog from ${timeText(fog.from)}", spoken, isWarning = true))
        }
        conditions.temperature?.let { range ->
            chips.add(
                ForecastChip(
                    ForecastChipKind.TEMPERATURE,
                    ForecastUnits.temperatureRange(range.minimum, range.maximum, system),
                    "Air temperature ${ForecastUnits.spokenTemperatureRange(range.minimum, range.maximum, system)}.",
                    isWarning = false,
                ),
            )
        }
        return chips
    }

    private fun windChip(wind: WindSummary, system: ForecastUnitSystem): ForecastChip {
        val speed = ForecastUnits.speedValue(wind.maximum, system)
        val gust = wind.gust?.let { ForecastUnits.speedValue(it, system) }?.takeIf { it > speed }
        val text = "Wind ${ForecastUnits.speed(wind.maximum, system)}" + (gust?.let { ", gusts $it" } ?: "")
        val direction = wind.fromDirection?.let { " from ${AngleFormatter.compassPoint(it).abbreviation}" } ?: ""
        val spoken = "Wind up to ${ForecastUnits.spokenSpeed(wind.maximum, system)}$direction" +
            (gust?.let { ", gusts up to $it" } ?: "") + "." +
            (windHint(wind.level)?.let { " $it." } ?: "")
        return ForecastChip(ForecastChipKind.WIND, text, spoken, isWarning = wind.level != WindLevel.CALM)
    }

    // The hourly table

    /** The column headings of the hourly table: time, cloud, temperature, dew gap, wind. */
    fun hourlyHeaders(system: ForecastUnitSystem): List<String> = listOf(
        "Time", "Cloud", "Temp (${ForecastUnits.temperatureUnit(system)})", "Dew gap (${ForecastUnits.temperatureUnit(system)})",
        "Wind (${ForecastUnits.speedUnit(system)})",
    )

    /** The cells of [hour] under [hourlyHeaders]: the time, the cloud in percent and the numbers without their units. */
    fun hourlyCells(hour: NightHour, system: ForecastUnitSystem, timeText: (Instant) -> String): List<String> = listOf(
        timeText(hour.time),
        "${hour.cloud.roundedHalfAwayFromZero().toInt()}%",
        hour.temperature?.let { ForecastUnits.signed(ForecastUnits.temperatureValue(it, system)) } ?: MISSING,
        hour.dewPointSpread?.let { ForecastUnits.differenceValue(it, system) } ?: MISSING,
        hour.windSpeed?.let { ForecastUnits.speedValue(it, system).toString() } ?: MISSING,
    )

    /** What a screen reader says for [hour]: its time and the values the forecast has for it. */
    fun hourlySpoken(hour: NightHour, system: ForecastUnitSystem, timeText: (Instant) -> String): String {
        val parts = ArrayList<String>(6)
        parts.add("${timeText(hour.time)}.")
        parts.add("Cloud ${hour.cloud.roundedHalfAwayFromZero().toInt()} percent.")
        hour.temperature?.let { parts.add("Temperature ${ForecastUnits.spokenTemperature(it, system)}.") }
        hour.dewPointSpread?.let { parts.add("Dew gap ${ForecastUnits.spokenDifference(it, system)}.") }
        hour.windSpeed?.let { parts.add("Wind ${ForecastUnits.spokenSpeed(it, system)}.") }
        hour.humidity?.let { parts.add("Humidity ${it.roundedHalfAwayFromZero().toInt()} percent.") }
        return parts.joinToString(" ")
    }
}
