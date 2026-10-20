package net.kuecker.lodestar.features.tools

import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.formatDuration
import net.kuecker.lodestar.kit.phototools.BatteryDemand
import net.kuecker.lodestar.kit.phototools.StarTrailError
import net.kuecker.lodestar.kit.phototools.StarTrailPlanner
import net.kuecker.lodestar.kit.phototools.TimeLapseError
import java.text.NumberFormat

/** The outcome of asking a planner: its plan, or why it refused ([F]: an explanation for the screen). */
internal sealed interface Planned<out T, out F> {
    data class Ok<T>(val value: T) : Planned<T, Nothing>

    data class Failed<F>(val failure: F) : Planned<Nothing, F>
}

/** What a time-lapse error leaves the user to do: use another interval. */
internal data class TimeLapseFailure(val message: String, val suggestedInterval: Double?)

/** The words of the star trail and time-lapse results. Pure functions, so they can be tested. */
internal object TrailsText {
    /** A gap between two stacked frames shows as a break in the trail above about this many pixels. */
    const val VISIBLE_GAP_PIXELS = 2.0

    /** [count] with the user's digit grouping: "100,000". */
    fun count(count: Int): String = NumberFormat.getIntegerInstance().format(count)

    /** A duration in at most two units: "10 s" below a minute (as [seconds] writes it), "4m 30s", "1h 05m". */
    fun duration(seconds: Double): String {
        val text = AngleFormatter.formatDuration(seconds)
        // The kit writes a bare seconds value as "10s"; here it has the space of the other seconds in the results.
        return if (text.endsWith("s") && !text.contains(' ')) text.dropLast(1) + " s" else text
    }

    /** Pixels: whole numbers, one decimal below ten ("0.8 px", "1,240 px"). */
    fun pixels(pixels: Double): String =
        "${if (pixels < 10) decimal(pixels, 1, 1) else decimal(pixels, 0, 0)} px"

    /** Seconds with up to [maximumDigits] decimals: "1 s", "2.5 s". */
    fun seconds(seconds: Double, maximumDigits: Int = 1): String = "${decimal(seconds, 0, maximumDigits)} s"

    /** A storage size: megabytes below a gigabyte, otherwise gigabytes ("600 MB", "9 GB"). */
    fun storage(megabytes: Double): String =
        if (megabytes < 1000) "${decimal(megabytes, 0, 0)} MB" else "${decimal(megabytes / 1000, 0, 1)} GB"

    /** How much the number of exposures asks of the camera's battery. */
    fun battery(demand: BatteryDemand): String = when (demand) {
        BatteryDemand.LIGHT -> "Light"
        BatteryDemand.MODERATE -> "Moderate"
        BatteryDemand.HEAVY -> "Heavy"
        BatteryDemand.VERY_HEAVY -> "Very heavy"
    }

    /** Runs a star trail calculation; a refusal becomes a [Planned.Failed] with an explanation. */
    inline fun <T> starTrail(block: () -> T): Planned<T, String> =
        try {
            Planned.Ok(block())
        } catch (error: StarTrailError) {
            Planned.Failed(starTrailError(error))
        }

    /** Runs a time-lapse calculation; a refusal becomes a [TimeLapseFailure] with an explanation. */
    inline fun <T> timeLapse(block: () -> T): Planned<T, TimeLapseFailure> =
        try {
            Planned.Ok(block())
        } catch (error: TimeLapseError) {
            Planned.Failed(timeLapseError(error))
        }

    fun starTrailError(error: StarTrailError): String = when (error) {
        is StarTrailError.InvalidDuration -> "The time must be longer than zero and at most 24 hours."
        is StarTrailError.InvalidDeclination -> "The declination must be between −90° and +90°."
        is StarTrailError.InvalidArc -> "The trail length must be more than zero (an arc of up to 360°)."
        StarTrailError.NoMotionAtThePole ->
            "A star exactly at a celestial pole does not move, so it never draws a trail. Choose a declination away " +
                "from ±90°."
        is StarTrailError.DurationTooLong ->
            "That trail takes ${duration(error.seconds)} at this declination, more than 24 hours. Choose a shorter " +
                "trail or a declination farther from the pole."
        is StarTrailError.InvalidExposure -> "The frame length must be between 0.1 seconds and one hour."
        is StarTrailError.InvalidGap -> "The gap between frames must be between 0 and 60 seconds."
        is StarTrailError.TooManyFrames ->
            "This stack needs ${count(error.count)} frames, more than the ${count(error.limit)} Lodestar plans. " +
                "Use a longer frame length, a shorter total time or a longer gap."
        is StarTrailError.InvalidFrame -> "The picture size is not usable."
    }

    /**
     * An explanation of a time-lapse error. For a gap that is too short it names the interval that works, which
     * the screen offers as a one-tap fix.
     */
    fun timeLapseError(error: TimeLapseError): TimeLapseFailure = when (error) {
        is TimeLapseError.GapTooShort -> {
            val needed = seconds(error.minimumGapSeconds, 2)
            val minimum = seconds(error.minimumIntervalSeconds, 2)
            val message = if (error.gapSeconds < 0) {
                "The exposure is longer than the interval, so the next picture would start before this one ends. " +
                    "Use an interval of at least $minimum, or shorten the exposure."
            } else {
                "Only ${seconds(error.gapSeconds, 2)} is left between the end of one exposure and the start of the " +
                    "next (interval minus exposure). The camera needs $needed to save each picture, or it skips " +
                    "frames. Use an interval of at least $minimum, or shorten the exposure."
            }
            TimeLapseFailure(message, error.minimumIntervalSeconds)
        }
        is TimeLapseError.InvalidInterval ->
            TimeLapseFailure("The interval must be longer than zero and at most one hour.", null)
        is TimeLapseError.InvalidExposure ->
            TimeLapseFailure("The exposure must be longer than zero and at most one hour.", null)
        is TimeLapseError.InvalidFrameRate ->
            TimeLapseFailure("The playback frame rate must be more than zero and at most 240 frames per second.", null)
        is TimeLapseError.InvalidTarget ->
            TimeLapseFailure("That gives less than one picture. Make the clip or the shooting time longer.", null)
        is TimeLapseError.InvalidMinimumGap ->
            TimeLapseFailure("The time the camera needs to save a picture must be between 0 and 60 seconds.", null)
        is TimeLapseError.InvalidFrameSize ->
            TimeLapseFailure("The size of a picture must be more than zero and at most 10,000 MB.", null)
        is TimeLapseError.TooManyFrames -> TimeLapseFailure(
            "This time-lapse needs ${count(error.count)} pictures, more than the ${count(error.limit)} Lodestar " +
                "plans. Make the clip or the shooting time shorter, or the interval longer.",
            null,
        )
    }

    /** What a gap of [pixels] between two stacked frames does to the trail. */
    fun gapVerdict(pixels: Double): String =
        if (pixels > VISIBLE_GAP_PIXELS) {
            "The stars move ${pixels(pixels)} between frames, so the stacked trails show breaks. Shorten the gap if " +
                "you can; stacking software can also bridge small gaps."
        } else {
            "The stars move only ${pixels(pixels)} between frames, so the stacked trails look unbroken."
        }

    /** How the sky turns, for the footer of the star trail result. */
    const val RATE_NOTE = "Stars turn about the celestial pole at ${StarTrailPlanner.rotationRateDegreesPerHour}° " +
        "an hour, the same for every star. A star at declination δ draws an arc of that angle × cos δ along its " +
        "circle, so trails shorten towards the pole."
}
