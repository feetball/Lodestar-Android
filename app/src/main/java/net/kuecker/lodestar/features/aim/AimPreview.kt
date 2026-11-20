package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.kit.formatting.AngleFormatter
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The rules of the time preview: the sky, the target and the guidance of the Aim screen worked out for a moment other
 * than now ([AimViewModel.previewOffset]), while the phone's pointing stays live. Pure functions, so the date logic and
 * the wording can be tested without the app.
 */
internal object AimPreview {
    /** The farthest the preview can be from now, either way: 48 hours, in seconds. */
    const val LIMIT_SECONDS = 48 * 3600.0

    /** An offset closer to now than this many seconds is live: stepping forward and back ends the preview exactly. */
    const val LIVE_TOLERANCE_SECONDS = 30.0

    /**
     * The apparent altitude of the Sun's centre above which it is day, in degrees: its upper limb at the horizon (the
     * −0.833° of sunrise and sunset) seen through standard refraction, which lifts the centre by about 0.57°.
     */
    const val DAYLIGHT_ALTITUDE = -0.27

    /** A step of the time bar. */
    enum class Step(val seconds: Double, val title: String, val spokenTitle: String) {
        BACK_HOUR(-3600.0, "−1 h", "One hour earlier"),
        BACK_QUARTER(-900.0, "−15 min", "Fifteen minutes earlier"),
        FORWARD_QUARTER(900.0, "+15 min", "Fifteen minutes later"),
        FORWARD_HOUR(3600.0, "+1 h", "One hour later"),
    }

    /**
     * [seconds] as a preview offset: limited to ±48 hours, 0 (live) for anything within [LIVE_TOLERANCE_SECONDS] of now
     * and for a value that is not a number.
     */
    fun normalized(seconds: Double): Double {
        if (!seconds.isFinite() || abs(seconds) < LIVE_TOLERANCE_SECONDS) return 0.0
        return seconds.coerceIn(-LIMIT_SECONDS, LIMIT_SECONDS)
    }

    /** The instant the sky is worked out for: [now] moved by [offset] seconds (the same instant when live). */
    fun date(now: Instant, offset: Double): Instant =
        if (offset == 0.0) now else now.plusMillis((offset * 1000).roundToLong())

    /** The preview offset that shows the sky at [date] when it is [now]: [normalized], so far dates are held at the limit. */
    fun offset(date: Instant, now: Instant): Double = normalized((date.toEpochMilli() - now.toEpochMilli()) / 1000.0)

    /** Whether the Sun is up when its centre stands at the apparent [sunAltitude] degrees. */
    fun isDaylight(sunAltitude: Double): Boolean = sunAltitude > DAYLIGHT_ALTITUDE

    /** Whether [date] falls on the same calendar day as [now] in [zone]. */
    fun isSameDay(date: Instant, now: Instant, zone: ZoneId): Boolean =
        date.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()

    // Wording

    /**
     * The text of the chip: "Preview 02:15", with the weekday ([day]) when the time is not today, and in daylight
     * "Preview · Daylight at 14:20".
     */
    fun chipText(time: String, day: String?, isDaylight: Boolean): String {
        val moment = if (day == null) time else "$day $time"
        return if (isDaylight) "Preview · Daylight at $moment" else "Preview $moment"
    }

    /** What TalkBack says for the chip. */
    fun spokenChip(time: String, day: String?, isDaylight: Boolean): String {
        val moment = if (day == null) time else "$day $time"
        val sun = if (isDaylight) " The Sun is up." else ""
        return "Previewing the sky at $moment.$sun"
    }

    /** "Will be at altitude 34° at 02:15" or "Will be below the horizon at 02:15"; past tense ("Was") for an earlier time. */
    fun placeNote(altitude: Double, time: String, isFuture: Boolean): String {
        val verb = if (isFuture) "Will be" else "Was"
        if (altitude < 0) return "$verb below the horizon at $time"
        return "$verb at altitude ${AngleFormatter.formatDegrees(altitude, 0)} at $time"
    }

    /** "Will be behind the recorded skyline at 02:15". */
    fun skylineNote(time: String, isFuture: Boolean): String =
        "${if (isFuture) "Will be" else "Was"} behind the recorded skyline at $time"

    /** The note for a target below the horizon, with the time of its next rise when there is one ([riseTime]). */
    fun belowHorizonNote(time: String, riseTime: String?, doesNotRise: Boolean, isFuture: Boolean): String {
        val base = placeNote(-1.0, time, isFuture)
        return when {
            riseTime != null -> "$base · rises $riseTime"
            doesNotRise -> "$base · does not rise in the next 24 hours"
            else -> base
        }
    }

    /** What TalkBack says about the target: "Jupiter will be at altitude 34 degrees at 02:15." */
    fun spokenPlace(name: String, altitude: Double, time: String, isFuture: Boolean): String {
        val verb = if (isFuture) "will be" else "was"
        if (altitude < 0) return "$name $verb below the horizon at $time."
        return "$name $verb at altitude ${AimFormat.spoken(altitude)} at $time."
    }
}
