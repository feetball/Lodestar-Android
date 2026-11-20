package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.formatFixed
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import java.util.Locale

/** The units the forecast values are shown in: metric (°C, m/s) or US customary (°F, mph). */
enum class ForecastUnitSystem(val rawValue: String) {
    /** Degrees Celsius and metres per second. */
    METRIC("metric"),

    /** Degrees Fahrenheit and miles per hour, for the regions that use US customary units. */
    US("us"),
}

/**
 * Temperature and wind speed as text in a [ForecastUnitSystem]. The forecast is always in °C and m/s; whole degrees and
 * whole speeds are rounded half away from zero, spreads show one decimal. The iOS app has the same texts.
 */
object ForecastUnits {
    /** Miles per hour in one metre per second. */
    const val milesPerHourPerMetrePerSecond = 3600.0 / 1609.344

    /** The minus sign of a negative number. */
    const val MINUS = "\u2212"

    private const val SPACE = "\u00A0"

    /** The regions whose people use US customary units (CLDR's measurement data). */
    private val usRegions = setOf("US", "LR", "MM")

    /**
     * The system of [locale]: the one its `-u-ms-` extension names (`ussystem`, `metric`), else US customary for the
     * regions that use it ([usRegions]) and metric everywhere else. The United Kingdom's mixed system counts as metric.
     */
    fun systemOf(locale: Locale): ForecastUnitSystem {
        val chosen: String? = locale.getUnicodeLocaleType("ms")
        val isUs = if (chosen != null) chosen == "ussystem" else locale.country in usRegions
        return if (isUs) ForecastUnitSystem.US else ForecastUnitSystem.METRIC
    }

    /** "°C" or "°F". */
    fun temperatureUnit(system: ForecastUnitSystem): String = if (system == ForecastUnitSystem.US) "°F" else "°C"

    /** "m/s" or "mph". */
    fun speedUnit(system: ForecastUnitSystem): String = if (system == ForecastUnitSystem.US) "mph" else "m/s"

    /** [celsius] in whole degrees of [system]. */
    fun temperatureValue(celsius: Double, system: ForecastUnitSystem): Int {
        val degrees = if (system == ForecastUnitSystem.US) celsius * 9.0 / 5.0 + 32.0 else celsius
        return degrees.roundedHalfAwayFromZero().toInt()
    }

    /** [metresPerSecond] in whole units of [system]. */
    fun speedValue(metresPerSecond: Double, system: ForecastUnitSystem): Int {
        val speed = if (system == ForecastUnitSystem.US) metresPerSecond * milesPerHourPerMetrePerSecond else metresPerSecond
        return speed.roundedHalfAwayFromZero().toInt()
    }

    /** A whole number with a real minus sign: "−3", "0", "4". */
    fun signed(value: Int): String = if (value < 0) "$MINUS${-value}" else "$value"

    /** A temperature such as "−3 °C" (a no-break space before the unit). */
    fun temperature(celsius: Double, system: ForecastUnitSystem): String =
        "${signed(temperatureValue(celsius, system))}$SPACE${temperatureUnit(system)}"

    /** A temperature range such as "−3…4 °C"; one temperature, "3 °C", when both ends round to the same. */
    fun temperatureRange(minimum: Double, maximum: Double, system: ForecastUnitSystem): String {
        val low = temperatureValue(minimum, system)
        val high = temperatureValue(maximum, system)
        if (low == high) return temperature(minimum, system)
        return "${signed(low)}…${signed(high)}$SPACE${temperatureUnit(system)}"
    }

    /** A wind speed such as "6 m/s" or "13 mph". */
    fun speed(metresPerSecond: Double, system: ForecastUnitSystem): String =
        "${speedValue(metresPerSecond, system)}$SPACE${speedUnit(system)}"

    /** A temperature difference of [celsius] with one decimal: "1.4" (the number only; a difference has no offset). */
    fun differenceValue(celsius: Double, system: ForecastUnitSystem): String =
        formatFixed(if (system == ForecastUnitSystem.US) celsius * 9.0 / 5.0 else celsius, 1)

    /** A temperature difference with its unit: "1.4 °C". */
    fun difference(celsius: Double, system: ForecastUnitSystem): String =
        "${differenceValue(celsius, system)}$SPACE${temperatureUnit(system)}"

    // Spoken

    /** "minus 3 degrees Celsius". */
    fun spokenTemperature(celsius: Double, system: ForecastUnitSystem): String {
        val value = temperatureValue(celsius, system)
        return "${spokenNumber(value)} ${spokenTemperatureUnit(system, singular = value == 1 || value == -1)}"
    }

    /** "from minus 3 to 4 degrees Celsius"; one temperature, "3 degrees Celsius", when both ends round to the same. */
    fun spokenTemperatureRange(minimum: Double, maximum: Double, system: ForecastUnitSystem): String {
        val low = temperatureValue(minimum, system)
        val high = temperatureValue(maximum, system)
        if (low == high) return spokenTemperature(minimum, system)
        return "from ${spokenNumber(low)} to ${spokenNumber(high)} ${spokenTemperatureUnit(system)}"
    }

    /** "6 metres per second" or "13 miles per hour" ("1 metre per second", "1 mile per hour"). */
    fun spokenSpeed(metresPerSecond: Double, system: ForecastUnitSystem): String {
        val value = speedValue(metresPerSecond, system)
        val unit = if (system == ForecastUnitSystem.US) "mile" else "metre"
        return "$value ${if (value == 1) unit else "${unit}s"} per ${if (system == ForecastUnitSystem.US) "hour" else "second"}"
    }

    /** "1.4 degrees Celsius". */
    fun spokenDifference(celsius: Double, system: ForecastUnitSystem): String =
        "${differenceValue(celsius, system)} ${spokenTemperatureUnit(system)}"

    private fun spokenTemperatureUnit(system: ForecastUnitSystem, singular: Boolean = false): String {
        val degrees = if (singular) "degree" else "degrees"
        return if (system == ForecastUnitSystem.US) "$degrees Fahrenheit" else "$degrees Celsius"
    }

    private fun spokenNumber(value: Int): String = if (value < 0) "minus ${-value}" else "$value"
}
