package net.kuecker.lodestar.features.smallbodies

import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.comets.SmallBody
import net.kuecker.lodestar.kit.comets.SmallBodyKind
import net.kuecker.lodestar.kit.foundation.JulianDate
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The words of the comet and asteroid screens: rows, warnings, the tail card and the lists. The iOS app uses the same texts. */
internal object SmallBodyText {
    const val NO_BRIGHTNESS_DATA = "no brightness data"
    const val TWILIGHT_GLOW = "in the twilight glow"
    const val POSITION_APPROXIMATE = "position approximate"
    const val NO_CURRENT_ORBIT = "no current orbit"

    const val PHOTOGRAPHABLE = "Photographable"

    const val TAIL_TITLE = "Ion tail direction (approximate)"
    const val TAIL_NOTE =
        "The real tail is up to several degrees long and curves. Tilting the camera by this much lays it along the frame."
    const val TAIL_FORESHORTENED = "Seen almost end-on, so the tail is short and hard to make out."

    const val TARGETS_NOTE = "Comets and asteroids come first when they are bright and high enough tonight."

    private const val HALF_DAY_SECONDS = 43_200L

    const val FOOTNOTE = "Magnitudes are estimates, good to a magnitude or two. Only bodies at least 25° from the Sun are listed."

    // Names

    /** `true` for a comet or an asteroid. */
    fun isSmallBody(obj: CelestialObject): Boolean = obj.smallBody != null

    /**
     * The name for rows and buttons: the common name of a comet or an asteroid ("Tsuchinshan-ATLAS", "Vesta"), else its
     * designation ("2024 YR4"); the name of every other object.
     */
    fun displayName(obj: CelestialObject): String =
        if (obj.smallBody != null) obj.alternateNames.firstOrNull() ?: obj.name else obj.name

    /** The kind of body an identifier like "comet.12P" or "asteroid.4" stands for, or `null` for other identifiers. */
    fun kindOfID(id: String): SmallBodyKind? = when {
        id.startsWith("comet.") -> SmallBodyKind.COMET
        id.startsWith("asteroid.") -> SmallBodyKind.ASTEROID
        else -> null
    }

    /** The designation in a small-body identifier: "12P" for "comet.12P". */
    fun designationOfID(id: String): String = id.substringAfter('.')

    // Numbers

    /** The magnitude with one decimal and a true minus sign: "7.4", "−0.3". */
    fun magnitudeNumber(value: Double): String {
        val rounded = (value * 10).roundToInt() / 10.0
        val format = NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        return (if (rounded < 0) "−" else "") + format.format(abs(rounded))
    }

    /** The magnitude as an estimate: "mag ≈ 7.4"; [NO_BRIGHTNESS_DATA] (capitalised with [sentence]) for `null`. */
    fun magnitude(value: Double?, sentence: Boolean = false): String {
        if (value == null || !value.isFinite()) return if (sentence) capitalized(NO_BRIGHTNESS_DATA) else NO_BRIGHTNESS_DATA
        return "mag ≈ ${magnitudeNumber(value)}"
    }

    /** "62° from the Sun". */
    fun elongation(degrees: Double): String = "${AngleFormatter.formatDegrees(degrees, 0)} from the Sun"

    /** The warning as it reads inside a line of text. */
    fun warning(warning: SmallBodyWarning): String = when (warning) {
        SmallBodyWarning.TWILIGHT_GLOW -> TWILIGHT_GLOW
        SmallBodyWarning.POSITION_APPROXIMATE -> POSITION_APPROXIMATE
        SmallBodyWarning.NO_BRIGHTNESS_DATA -> NO_BRIGHTNESS_DATA
    }

    /** The warning as a sentence of its own ("In the twilight glow"), for chips and notes. */
    fun sentence(warning: SmallBodyWarning): String = capitalized(warning(warning))

    fun capitalized(text: String): String = text.replaceFirstChar { it.titlecase(Locale.getDefault()) }

    // Rows

    /**
     * Kind, magnitude estimate and elongation on one line, then the warnings: "Comet · mag ≈ 7.4 · 62° from the Sun",
     * "Asteroid · no brightness data · 18° from the Sun · in the twilight glow". [info] is `null` before the position is
     * known ([isComputed] false) or when the orbit cannot be evaluated ([NO_CURRENT_ORBIT]). With [spoken] the parts are
     * separated by commas and written out.
     */
    fun subtitle(
        obj: CelestialObject,
        info: SmallBodyInfo?,
        isComputed: Boolean,
        spoken: Boolean = false,
        withWarnings: Boolean = true,
    ): String {
        val parts = arrayListOf(obj.category.displayName)
        if (info != null) {
            parts.add(magnitude(info.magnitude))
            parts.add(elongation(info.elongation))
            if (withWarnings) {
                for (warning in info.warnings) {
                    // "no brightness data" has been said by the magnitude part.
                    if (warning != SmallBodyWarning.NO_BRIGHTNESS_DATA) parts.add(warning(warning))
                }
            }
        } else if (isComputed) {
            parts.add(NO_CURRENT_ORBIT)
        }
        if (spoken) {
            return parts.joinToString(", ") {
                it.replace("mag ≈ ", "magnitude about ").replace("°", " degrees")
            }
        }
        return parts.joinToString(" · ")
    }

    /** The one line under a name in the picker, Tonight and the lists: kind, estimate and elongation. */
    fun summary(obj: CelestialObject, info: SmallBodyInfo?): String = subtitle(obj, info, isComputed = true)

    /** [summary] for the info of [obj] at [date]; just the kind for any other object. */
    fun summary(obj: CelestialObject, date: Instant): String {
        val body = obj.smallBody ?: return obj.category.displayName
        return summary(obj, SmallBodyInfo.compute(body, date))
    }

    // Detail

    /** The date of the orbital elements, "Oct 17, 2026". */
    fun elementsDate(body: SmallBody): String {
        // Epochs are quoted in dynamical time (0h TT): label the calendar day of that clock, not of UTC.
        val epoch = JulianDate.fromTt(body.orbit.epochJulianDayTT)
        // Rounded to the nearest day: the elements of a file are given for 0h of a day.
        val date = epoch.date.plusMillis((epoch.deltaT * 1000).roundToLong()).plusSeconds(HALF_DAY_SECONDS)
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneOffset.UTC).format(date)
    }

    /** "Straight up", "35° to the right of up", "20° to the left of up", "Straight down". */
    fun tailDirection(angleFromVertical: Double): String {
        val degrees = abs(angleFromVertical).roundToInt()
        return when {
            degrees == 0 -> "Straight up"
            degrees == 180 -> "Straight down"
            angleFromVertical > 0 -> "$degrees° to the right of up"
            else -> "$degrees° to the left of up"
        }
    }

    /** "12° clockwise", "12° counter-clockwise" or "None needed". */
    fun cameraTilt(tilt: Double): String {
        val degrees = abs(tilt).roundToInt()
        return when {
            degrees == 0 -> "None needed"
            tilt > 0 -> "$degrees° clockwise"
            else -> "$degrees° counter-clockwise"
        }
    }

    // Moving targets

    /** Why a mosaic cannot cover [obj], a comet or an asteroid. */
    fun mosaicRefusal(obj: CelestialObject): String =
        "${displayName(obj)} moves among the stars, so a mosaic cannot follow it. Plan a custom size instead."
}
