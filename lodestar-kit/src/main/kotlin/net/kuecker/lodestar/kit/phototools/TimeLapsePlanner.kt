package net.kuecker.lodestar.kit.phototools

import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import kotlin.math.floor

/** Why a time-lapse could not be planned. */
sealed class TimeLapseError(message: String) : Exception(message) {
    /** The interval is not a finite number of seconds in (0, [TimeLapsePlanner.maximumIntervalSeconds]]. */
    data class InvalidInterval(val seconds: Double) :
        TimeLapseError("The interval $seconds s is not in (0, 3600] seconds.")

    /** The exposure is not a finite number of seconds in (0, [TimeLapsePlanner.maximumIntervalSeconds]]. */
    data class InvalidExposure(val seconds: Double) :
        TimeLapseError("The exposure $seconds s is not in (0, 3600] seconds.")

    /** The frame rate is not a finite number in (0, [TimeLapsePlanner.maximumFrameRate]]. */
    data class InvalidFrameRate(val framesPerSecond: Double) :
        TimeLapseError("The frame rate $framesPerSecond is not in (0, 240] frames per second.")

    /** The clip length, shooting time or frame count is not positive or yields less than one frame. */
    data class InvalidTarget(val value: Double) : TimeLapseError("The target $value yields no frames.")

    /** The minimum gap is not a finite number of seconds in [0, [TimeLapsePlanner.maximumMinimumGapSeconds]]. */
    data class InvalidMinimumGap(val seconds: Double) :
        TimeLapseError("The minimum gap $seconds s is not in [0, 60] seconds.")

    /** The frame size is not a finite number of megabytes in (0, [TimeLapsePlanner.maximumFrameSizeMegabytes]]. */
    data class InvalidFrameSize(val megabytes: Double) :
        TimeLapseError("The frame size $megabytes MB is not in (0, 10000] megabytes.")

    /**
     * The gap between the end of an exposure and the next shot is shorter than the camera needs to write the
     * picture, so frames would be skipped. [minimumIntervalSeconds] is the shortest interval that works.
     */
    data class GapTooShort(val gapSeconds: Double, val minimumGapSeconds: Double, val minimumIntervalSeconds: Double) :
        TimeLapseError("The gap $gapSeconds s is shorter than the $minimumGapSeconds s the camera needs.")

    /** The time-lapse would have [count] frames, more than the [limit] the planner handles. */
    data class TooManyFrames(val count: Int, val limit: Int) :
        TimeLapseError("The time-lapse needs $count frames; the limit is $limit.")
}

/** What the time-lapse is planned for. */
sealed interface TimeLapseTarget {
    /** A clip of this many seconds when played back at the frame rate. */
    data class ClipLength(val seconds: Double) : TimeLapseTarget

    /** This many seconds of shooting. The plan takes as many whole intervals as fit. */
    data class ShootingTime(val seconds: Double) : TimeLapseTarget

    /** This many frames. */
    data class FrameCount(val frames: Int) : TimeLapseTarget
}

/** How much a time-lapse asks of the camera's battery, judged by the number of exposures. */
enum class BatteryDemand(
    /** The stable string used for storage. */
    val rawValue: String,
) {
    /** Up to 300 exposures: one battery is plenty. */
    LIGHT("light"),

    /** Up to 1000 exposures: a full battery, a spare in the bag. */
    MODERATE("moderate"),

    /** Up to 2500 exposures: a spare battery or external power. */
    HEAVY("heavy"),

    /** More than 2500 exposures: external power. */
    VERY_HEAVY("veryHeavy");

    companion object {
        /** The demand with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): BatteryDemand? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The numbers of a time-lapse.
 *
 * @property interval The time between the starts of two exposures in seconds.
 * @property exposure The exposure time in seconds.
 * @property frameRate The playback frame rate in frames per second.
 * @property frameCount The number of frames (exposures).
 * @property shootingTime The time the shooting takes, `frameCount × interval`, in seconds.
 * @property clipLength The length of the clip, `frameCount / frameRate`, in seconds.
 * @property gap The time between the end of an exposure and the start of the next, `interval − exposure`, in
 * seconds: what the camera has to write the picture and refocus.
 * @property speedUp How much faster than real time the clip plays: `interval × frameRate`.
 * @property frameSizeMegabytes The size of one picture in megabytes (1 MB = 1 000 000 bytes), or null if unknown.
 * @property storageMegabytes The storage all frames need in megabytes, or null if the frame size is unknown.
 * @property storageGigabytes The storage in gigabytes (1 GB = 1000 MB), or null if the frame size is unknown.
 * @property exposureCount The number of exposures the battery has to supply: equal to [frameCount].
 * @property batteryDemand How demanding the number of exposures is.
 * @property batteryNote A sentence about batteries for the number of exposures, in English.
 */
@ConsistentCopyVisibility
data class TimeLapsePlan internal constructor(
    val interval: Double,
    val exposure: Double,
    val frameRate: Double,
    val frameCount: Int,
    val shootingTime: Double,
    val clipLength: Double,
    val gap: Double,
    val speedUp: Double,
    val frameSizeMegabytes: Double?,
    val storageMegabytes: Double?,
    val storageGigabytes: Double?,
    val exposureCount: Int,
    val batteryDemand: BatteryDemand,
    val batteryNote: String,
)

/**
 * Works out the numbers of a time-lapse: how many frames it takes, how long the shooting and the clip last,
 * how much storage it needs and whether the camera can keep up with the interval.
 *
 * All functions are pure and take microseconds.
 */
object TimeLapsePlanner {
    /** The longest interval or exposure the planner accepts: an hour. */
    const val maximumIntervalSeconds: Double = 3_600.0

    /** The highest playback frame rate the planner accepts. */
    const val maximumFrameRate: Double = 240.0

    /** The most frames a plan may have. */
    const val maximumFrameCount: Int = 100_000

    /** The largest frame size the planner accepts, in megabytes. */
    const val maximumFrameSizeMegabytes: Double = 10_000.0

    /** The shortest gap between exposures by default: one second for the camera to write the picture. */
    const val defaultMinimumGapSeconds: Double = 1.0

    /** The largest minimum gap the planner accepts. */
    const val maximumMinimumGapSeconds: Double = 60.0

    /** Up to this many exposures the battery demand is [BatteryDemand.LIGHT]. */
    internal const val lightExposureLimit: Int = 300

    /** Up to this many exposures the battery demand is [BatteryDemand.MODERATE]. */
    internal const val moderateExposureLimit: Int = 1_000

    /** Up to this many exposures the battery demand is [BatteryDemand.HEAVY]. */
    internal const val heavyExposureLimit: Int = 2_500

    /**
     * The shortest interval that leaves the camera [minimumGapSeconds] after an exposure of [exposure] seconds.
     *
     * @throws TimeLapseError.InvalidExposure for an exposure out of range.
     * @throws TimeLapseError.InvalidMinimumGap for a minimum gap out of range.
     */
    @Throws(TimeLapseError::class)
    fun minimumInterval(exposure: Double, minimumGapSeconds: Double = defaultMinimumGapSeconds): Double {
        if (!(exposure > 0.0 && exposure <= maximumIntervalSeconds)) throw TimeLapseError.InvalidExposure(exposure)
        if (!(minimumGapSeconds >= 0.0 && minimumGapSeconds <= maximumMinimumGapSeconds)) {
            throw TimeLapseError.InvalidMinimumGap(minimumGapSeconds)
        }
        return exposure + minimumGapSeconds
    }

    /**
     * Plans a time-lapse.
     *
     * The frame count follows from the target: a clip length gives `round(clipLength × frameRate)` frames, a shooting
     * time as many whole intervals as fit in it, and a frame count is taken as it is.
     *
     * @param interval The time between the starts of two exposures in seconds, in (0, [maximumIntervalSeconds]].
     * @param exposure The exposure time in seconds, in (0, [maximumIntervalSeconds]].
     * @param frameRate The playback frame rate in frames per second, in (0, [maximumFrameRate]].
     * @param target The clip length, shooting time or frame count to plan for.
     * @param frameSizeMegabytes The size of one picture in megabytes, for the storage estimate; null leaves the
     * storage unknown.
     * @param minimumGapSeconds The gap the camera needs between the end of an exposure and the next start (the
     * write time) in seconds, [0, [maximumMinimumGapSeconds]].
     * @throws TimeLapseError for a value out of range, a target that yields no frames or more than
     * [maximumFrameCount], or [TimeLapseError.GapTooShort] when `interval − exposure` is less than
     * [minimumGapSeconds].
     */
    @Throws(TimeLapseError::class)
    fun plan(
        interval: Double,
        exposure: Double,
        frameRate: Double,
        target: TimeLapseTarget,
        frameSizeMegabytes: Double? = null,
        minimumGapSeconds: Double = defaultMinimumGapSeconds,
    ): TimeLapsePlan {
        if (!(interval > 0.0 && interval <= maximumIntervalSeconds)) throw TimeLapseError.InvalidInterval(interval)
        if (!(exposure > 0.0 && exposure <= maximumIntervalSeconds)) throw TimeLapseError.InvalidExposure(exposure)
        if (!(frameRate > 0.0 && frameRate <= maximumFrameRate)) throw TimeLapseError.InvalidFrameRate(frameRate)
        if (!(minimumGapSeconds >= 0.0 && minimumGapSeconds <= maximumMinimumGapSeconds)) {
            throw TimeLapseError.InvalidMinimumGap(minimumGapSeconds)
        }
        if (frameSizeMegabytes != null && !(frameSizeMegabytes > 0.0 && frameSizeMegabytes <= maximumFrameSizeMegabytes)) {
            throw TimeLapseError.InvalidFrameSize(frameSizeMegabytes)
        }
        val gap = interval - exposure
        if (!(gap >= minimumGapSeconds - 1e-9)) {
            throw TimeLapseError.GapTooShort(gap, minimumGapSeconds, exposure + minimumGapSeconds)
        }

        val frames: Double = when (target) {
            is TimeLapseTarget.ClipLength -> {
                if (!(target.seconds > 0.0 && target.seconds.isFinite())) throw TimeLapseError.InvalidTarget(target.seconds)
                (target.seconds * frameRate).roundedHalfAwayFromZero()
            }

            is TimeLapseTarget.ShootingTime -> {
                if (!(target.seconds > 0.0 && target.seconds.isFinite())) throw TimeLapseError.InvalidTarget(target.seconds)
                floor(target.seconds / interval + 1e-9)
            }

            is TimeLapseTarget.FrameCount -> target.frames.toDouble()
        }
        val requested = when (target) {
            is TimeLapseTarget.ClipLength -> target.seconds
            is TimeLapseTarget.ShootingTime -> target.seconds
            is TimeLapseTarget.FrameCount -> target.frames.toDouble()
        }
        if (!(frames >= 1.0)) throw TimeLapseError.InvalidTarget(requested)
        if (frames > maximumFrameCount) {
            throw TimeLapseError.TooManyFrames(if (frames >= 1e9) 1_000_000_000 else frames.toInt(), maximumFrameCount)
        }
        val frameCount = frames.toInt()
        val storageMegabytes = frameSizeMegabytes?.let { it * frames }
        return TimeLapsePlan(
            interval = interval,
            exposure = exposure,
            frameRate = frameRate,
            frameCount = frameCount,
            shootingTime = frames * interval,
            clipLength = frames / frameRate,
            gap = gap,
            speedUp = interval * frameRate,
            frameSizeMegabytes = frameSizeMegabytes,
            storageMegabytes = storageMegabytes,
            storageGigabytes = storageMegabytes?.let { it / 1000.0 },
            exposureCount = frameCount,
            batteryDemand = batteryDemand(frameCount),
            batteryNote = batteryNote(frameCount),
        )
    }

    /** How demanding [exposureCount] exposures are for the battery. */
    fun batteryDemand(exposureCount: Int): BatteryDemand = when {
        exposureCount <= lightExposureLimit -> BatteryDemand.LIGHT
        exposureCount <= moderateExposureLimit -> BatteryDemand.MODERATE
        exposureCount <= heavyExposureLimit -> BatteryDemand.HEAVY
        else -> BatteryDemand.VERY_HEAVY
    }

    /**
     * A sentence about batteries for [exposureCount] exposures, in English. The limits are rules of thumb: a
     * typical camera battery lasts for several hundred to a couple of thousand pictures, fewer in the cold and
     * with long exposures.
     */
    fun batteryNote(exposureCount: Int): String = when (batteryDemand(exposureCount)) {
        BatteryDemand.LIGHT -> "About $exposureCount exposures: one charged battery is plenty."
        BatteryDemand.MODERATE ->
            "About $exposureCount exposures: start with a fully charged battery and keep a spare in the bag."
        BatteryDemand.HEAVY ->
            "About $exposureCount exposures: bring a spare battery or external power, as cold shortens battery life."
        BatteryDemand.VERY_HEAVY ->
            "About $exposureCount exposures: use external power, such as a power bank or a dummy battery."
    }
}
