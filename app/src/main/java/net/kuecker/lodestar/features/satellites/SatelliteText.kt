package net.kuecker.lodestar.features.satellites

import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.satellites.SatelliteCrossing
import net.kuecker.lodestar.kit.satellites.SatellitePassPoint
import net.kuecker.lodestar.services.SatellitePassItem
import net.kuecker.lodestar.shared.AppFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The words of the satellite features. The iOS app uses the same texts. */
internal object SatelliteText {
    const val CARD_TITLE = "Satellite passes"
    const val ALL_PASSES = "All passes"
    const val PASSES_TITLE = "Satellite Passes"
    const val LOADING = "Loading orbit data…"
    const val FINDING = "Finding passes…"
    const val NO_PASSES_TONIGHT = "No visible passes on this night."
    const val NO_PASSES = "No visible passes in the next three days."
    const val NIGHT_OVER = "This night is over."
    const val TOO_FAR = "Passes can be predicted only three days ahead."
    const val STALE = "Orbit data is too old for exact times. Update it in Settings ▸ Online Data."
    const val STALE_TAG = "orbit data too old"
    const val VISIBLE_ONLY = "Visible passes only: the satellite is lit by the Sun while the sky is dark, at least 10° up."
    const val ATTRIBUTION = "Orbital data: CelesTrak, T. S. Kelso"
    const val FOLLOW = "Follow in Aim"
    const val WAIT = "Wait at the peak"
    const val ACTIONS_NOTE =
        "Follow in Aim guides the camera to the satellite's live position. Wait at the peak guides it to the point " +
            "where the pass is highest, with a countdown."
    const val STOP = "Stop"
    const val RIDE_ALONG_BELOW = "Below the horizon"

    /** "21:34 – 21:39", or just "21:34" when both ends are written the same (a pass visible for less than a minute). */
    fun span(item: SatellitePassItem, zone: ZoneId): String {
        val start = AppFormat.time(item.start, zone)
        val end = AppFormat.time(item.end, zone)
        return if (start == end) start else "$start – $end"
    }

    /** "peak 67° NE": the culmination's altitude and compass point. */
    fun peak(point: SatellitePassPoint): String =
        "peak ${AngleFormatter.formatDegrees(point.altitude, 0)} ${AngleFormatter.compassPoint(point.azimuth).abbreviation}"

    /** "mag −3.2", or "—" when the brightness is unknown. */
    fun magnitude(item: SatellitePassItem): String = AppFormat.magnitude(item.pass.brightest?.magnitude)

    /** The length of a pass part: "5 min 18 s", "42 s". */
    fun duration(seconds: Double): String {
        val whole = seconds.coerceAtLeast(0.0).roundToInt()
        return if (whole < 60) "$whole s" else "${whole / 60} min ${whole % 60} s"
    }

    /** A time of day with seconds in the 12 or 24 hour style: "21:34:12" or "9:34:12 PM". */
    fun timeWithSeconds(date: Instant, zone: ZoneId, use24Hour: Boolean = AppFormat.use24HourClock, locale: Locale = Locale.getDefault()): String {
        val pattern = if (use24Hour) "HH:mm:ss" else "h:mm:ss a"
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(date)
    }

    /** "21:34:12 · 225° SW · 10°": time, azimuth with compass point, altitude. */
    fun point(point: SatellitePassPoint, zone: ZoneId): String =
        "${timeWithSeconds(point.date, zone)} · ${AppFormat.azimuth(point.azimuth)} · ${AngleFormatter.formatDegrees(point.altitude, 0)}"

    /** The compass point in words, lower case: "south-west". */
    private fun direction(azimuth: Double): String =
        AngleFormatter.compassPoint(azimuth).fullName.lowercase(Locale.ROOT)

    /** What a screen reader says for the sky path of a pass. */
    fun spokenPath(item: SatellitePassItem, zone: ZoneId): String {
        val pass = item.pass
        val visible = pass.visibleSpan?.let { " Visible from ${AppFormat.time(it.start, zone)} to ${AppFormat.time(it.end, zone)}." }.orEmpty()
        return "Sky path of the ${item.name} pass. It rises in the ${direction(pass.rise.azimuth)}, reaches " +
            "${pass.culmination.altitude.roundToInt()} degrees in the ${direction(pass.culmination.azimuth)} and sets in the " +
            "${direction(pass.set.azimuth)}.$visible"
    }

    // Aim

    /** "Range 612 km · 0.62°/s · Sunlit" or "… · In Earth's shadow". */
    fun followInfo(rangeKilometers: Double, angularSpeed: Double, isSunlit: Boolean, locale: Locale = Locale.getDefault()): String {
        val range = NumberFormat.getIntegerInstance(locale).format(rangeKilometers.roundToInt())
        val speed = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(angularSpeed)
        return "Range $range km · $speed°/s · ${if (isSunlit) "Sunlit" else "In Earth's shadow"}"
    }

    /** The countdown to the peak: "Peak in 2:41", "At the peak now", "Peak passed 0:42 ago". */
    fun peakCountdown(seconds: Double): String {
        if (!seconds.isFinite()) return AppFormat.MISSING
        if (abs(seconds) < 2) return "At the peak now"
        val text = clock(abs(seconds).roundToInt())
        return if (seconds > 0) "Peak in $text" else "Peak passed $text ago"
    }

    /** "2:41" or "1:02:03". */
    fun clock(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.ROOT, minutes, seconds)
        }
    }

    /**
     * A note about the pass of a followed satellite while it is not in it: "Rises 21:34 (in 12m)" before, "The pass is
     * over" after; `null` during the pass.
     */
    fun passNote(now: Instant, rise: Instant, set: Instant, timeText: (Instant) -> String): String? = when {
        now < rise -> "Rises ${timeText(rise)} (${AppFormat.relative(rise, now)})"
        now > set -> "The pass is over"
        else -> null
    }

    // Streak warning

    /**
     * The chip in Aim: "Starlink: 3 crossings in 30 s · 12% streak risk". With no crossing: "Starlink: no crossings in
     * 30 s · 8% streak risk".
     */
    fun streakChip(crossings: Int, exposureSeconds: Int, risk: Double): String {
        val count = when (crossings) {
            0 -> "no crossings"
            1 -> "1 crossing"
            else -> "$crossings crossings"
        }
        return "Starlink: $count in $exposureSeconds s · ${AppFormat.percent(risk.coerceIn(0.0, 1.0))} streak risk"
    }

    const val STREAK_STALE_CHIP = "Starlink data too old"
    const val NO_CROSSINGS = "No satellite is expected to cross the frame during this exposure."
    const val STREAK_NOTE =
        "A prediction from the Starlink orbit data for the exposure that keeps stars sharp with your lens (the NPF " +
            "rule). A satellite's brightness varies; the magnitude is unknown for most."

    /**
     * The numbers behind the chip: "47 sunlit satellites above 20° right now. About 0.8 crossings a minute in this
     * frame; 12% chance of a streak in a 30 s exposure."
     */
    fun streakSummary(
        exposureSeconds: Int,
        sunlitAbove20: Int,
        crossingsPerMinute: Double,
        risk: Double,
        locale: Locale = Locale.getDefault(),
    ): String {
        val rate = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(crossingsPerMinute)
        val satellites = if (sunlitAbove20 == 1) "1 sunlit satellite" else "$sunlitAbove20 sunlit satellites"
        return "$satellites above 20° right now. About $rate crossings a minute in this frame; " +
            "${AppFormat.percent(risk.coerceIn(0.0, 1.0))} chance of a streak in a $exposureSeconds s exposure."
    }

    /** One crossing: "21:34:12 – 21:34:19 · mag —", with "already in frame" when it began before the exposure. */
    fun crossing(crossing: SatelliteCrossing, zone: ZoneId): String {
        val early = if (crossing.startsBeforeExposure) " · already in frame" else ""
        return "${timeWithSeconds(crossing.entry.date, zone)} – ${timeWithSeconds(crossing.exit.date, zone)} · " +
            AppFormat.magnitude(crossing.magnitude) + early
    }
}
