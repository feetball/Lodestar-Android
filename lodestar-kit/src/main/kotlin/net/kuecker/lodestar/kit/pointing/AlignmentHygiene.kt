package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant

/**
 * One timestamped camera boresight, the input of [SteadinessDetector].
 *
 * The direction is stored as a unit vector in the horizon frame (ENU). Create values with the `create`
 * functions, which return null for a timestamp or direction that is not finite and for a zero direction.
 */
@ConsistentCopyVisibility
data class SteadinessSample private constructor(
    /** Seconds on any clock that only moves forward (the sensor clock of the attitudes, for example). */
    val timestamp: Double,
    /** Unit vector of the camera boresight in the horizon frame (x east, y north, z up). */
    val direction: Vector3,
) {
    companion object {
        /**
         * A sample at [timestamp] looking along [direction] (any non-zero length, normalised here); null when the
         * timestamp or direction is not finite, or the direction has no length. Swift `init?(timestamp:direction:)`.
         */
        fun create(timestamp: Double, direction: Vector3): SteadinessSample? {
            val length = direction.length
            if (!(timestamp.isFinite() && direction.isFinite && length.isFinite() && length > 0)) return null
            return SteadinessSample(timestamp, direction / length)
        }

        /** A sample looking at [boresight]; null for a non-finite timestamp. Swift `init?(timestamp:boresight:)`. */
        fun create(timestamp: Double, boresight: HorizontalCoordinate): SteadinessSample? =
            create(timestamp, boresight.unitVector)

        /**
         * The sample for the phone [attitude] (the one an [AlignmentPoint] stores) carried on [mount]: it is
         * stamped with the attitude's timestamp and looks along the mount's camera axis. Swift
         * `init?(attitude:mount:)`.
         */
        fun create(attitude: DeviceAttitude, mount: MountProfile): SteadinessSample? =
            create(attitude.timestamp, attitude.deviceToWorld * mount.cameraAxis)
    }
}

/**
 * Tells whether the camera has been held still: it keeps a sliding window of the last [windowSeconds] of
 * boresight samples and reports [isSteady] once the window is full and every sample in it lies within
 * [toleranceDegrees] of the newest sample.
 *
 * It is an immutable value; [adding] returns the detector for the stream so far. Each call copies the samples of
 * one window (a few dozen at sensor rates) and nothing else is kept, so a stream of any length runs in constant
 * memory. Cost per sample is one angle computation per sample in the window.
 *
 * The window counts as full when the unbroken stream of samples reaches back [windowSeconds] before the newest
 * sample. The stream is unbroken until a gap of more than [maximumGapSeconds] between two samples or a sample
 * stamped earlier than its predecessor, which start it over (a detector that reports "steady" must have seen
 * the whole window). A sample with the same timestamp as the newest is ignored. Use a new detector for each
 * attempt.
 *
 * Non-finite or non-positive arguments fall back to the defaults (1.5 s, 0.35°, 0.5 s).
 */
@ConsistentCopyVisibility
data class SteadinessDetector private constructor(
    /** Length of the window in seconds. */
    val windowSeconds: Double,
    /** Largest angle in degrees between the newest sample and any other sample of the window. */
    val toleranceDegrees: Double,
    /** A pause between two samples longer than this (seconds) starts the window over. */
    val maximumGapSeconds: Double,
    private val samples: List<SteadinessSample>,
    private val runStart: Double,
    /** `true` when the window is full and the camera stayed within the tolerance throughout it. */
    val isSteady: Boolean,
    /**
     * How much of the window the camera has been still for, in [0, 1]: the time since the newest sample that is
     * further than the tolerance from the newest one (or since the start of the stream), over [windowSeconds].
     * It is 1 exactly when [isSteady].
     */
    val progress: Double,
) {
    /** Number of samples held, which is bounded by the window. */
    val sampleCount: Int
        get() = samples.size

    /**
     * The detector after [sample]. A sample not later than the newest one is ignored when its timestamp is equal and
     * starts the window over when it is earlier.
     */
    fun adding(sample: SteadinessSample): SteadinessDetector {
        val newest = samples.lastOrNull() ?: return restarted(sample)
        if (sample.timestamp == newest.timestamp) return this
        if (!(sample.timestamp > newest.timestamp) || sample.timestamp - newest.timestamp > maximumGapSeconds) {
            return restarted(sample)
        }
        val windowStart = sample.timestamp - windowSeconds
        var dropped = 0
        while (dropped < samples.size && samples[dropped].timestamp < windowStart) dropped += 1
        val kept = ArrayList<SteadinessSample>(samples.size - dropped + 1)
        for (index in dropped until samples.size) kept.add(samples[index])
        kept.add(sample)

        // The trailing run of samples that are within the tolerance of the newest one.
        var outside = kept.size - 1
        while (outside >= 0 && sample.direction.angle(kept[outside].direction) <= toleranceDegrees) outside -= 1
        val steady = outside < 0 && runStart <= windowStart
        val begin = if (outside < 0) runStart else kept[outside + 1].timestamp
        val fraction = if (steady) 1.0 else AstroMath.clamp((sample.timestamp - begin) / windowSeconds, 0.0, 1.0)
        return SteadinessDetector(windowSeconds, toleranceDegrees, maximumGapSeconds, kept, runStart, steady, fraction)
    }

    private fun restarted(sample: SteadinessSample): SteadinessDetector =
        SteadinessDetector(
            windowSeconds, toleranceDegrees, maximumGapSeconds, listOf(sample), sample.timestamp,
            isSteady = false, progress = 0.0,
        )

    companion object {
        /** Default [windowSeconds]. */
        const val defaultWindowSeconds: Double = 1.5

        /** Default [toleranceDegrees]: a little more than the noise of a phone resting on a tripod bracket. */
        const val defaultToleranceDegrees: Double = 0.35

        /** Default [maximumGapSeconds]. */
        const val defaultMaximumGapSeconds: Double = 0.5

        /**
         * Creates a detector with an empty window. Non-finite or non-positive arguments fall back to the
         * defaults. Swift `init(windowSeconds:toleranceDegrees:maximumGapSeconds:)`.
         */
        operator fun invoke(
            windowSeconds: Double = defaultWindowSeconds,
            toleranceDegrees: Double = defaultToleranceDegrees,
            maximumGapSeconds: Double = defaultMaximumGapSeconds,
        ): SteadinessDetector = SteadinessDetector(
            valid(windowSeconds, defaultWindowSeconds), valid(toleranceDegrees, defaultToleranceDegrees),
            valid(maximumGapSeconds, defaultMaximumGapSeconds), emptyList(), 0.0, false, 0.0,
        )

        private fun valid(value: Double, fallback: Double): Double = if (value.isFinite() && value > 0) value else fallback
    }
}

/**
 * Where a candidate star lies relative to the nearest alignment point.
 *
 * @property degrees Angular separation in degrees.
 * @property pointIndex Index of that point in the list given to [AlignmentHygiene.bestSeparation].
 * @property pointName The point's name, if it has one.
 */
data class AlignmentPointSeparation(val degrees: Double, val pointIndex: Int, val pointName: String?)

/**
 * Rules that keep a star alignment trustworthy: how far apart the alignment stars should be and when an alignment
 * is too old to rely on. The steadiness check for the moment of syncing is [SteadinessDetector].
 *
 * The numbers are rules of thumb chosen for the app, not measured values.
 */
object AlignmentHygiene {
    /**
     * Alignment stars closer together than this, in degrees, make the least-squares fit ill-conditioned: the
     * points then say little about compass error against bracket tilt.
     */
    const val minimumPointSeparationDegrees: Double = 20.0

    /**
     * Seconds after which an alignment made with the compass heading is stale: 3 hours. The compass heading does
     * not drift; what goes wrong is slow and caused by the setting: the phone slips in its bracket, the tripod is
     * moved near metal or cars, the magnetic surroundings change.
     */
    const val compassStaleAfterSeconds: Double = 3.0 * 3600.0

    /**
     * Seconds after which an alignment made in gyro lock is stale: 90 minutes. Gyro lock holds the heading by
     * integrating the gyroscope in a frame without an absolute reference, so its error keeps growing with time
     * (gyro drift) on top of the bracket slips that affect the compass too.
     */
    const val gyroLockStaleAfterSeconds: Double = 90.0 * 60.0

    /** The age in seconds from which an alignment is stale: [gyroLockStaleAfterSeconds] in gyro lock, else [compassStaleAfterSeconds]. */
    fun staleAfterSeconds(gyroLock: Boolean): Double = if (gyroLock) gyroLockStaleAfterSeconds else compassStaleAfterSeconds

    /**
     * Whether an alignment made at [alignedAt] is stale at [now]: its age is at least [staleAfterSeconds]. An
     * alignment from the future (the clock was set back) is not stale.
     */
    fun isStale(alignedAt: Instant, now: Instant, gyroLock: Boolean): Boolean =
        now.timeIntervalSince(alignedAt) >= staleAfterSeconds(gyroLock)

    /**
     * The existing point nearest to [candidate], with the separation in degrees; null without points. Positions are
     * the horizontal places recorded when each point was taken, which is the frame the fit is solved in, so
     * [candidate] should be the object's apparent horizontal position now. The first of equally near points wins.
     */
    fun bestSeparation(candidate: HorizontalCoordinate, existing: List<AlignmentPoint>): AlignmentPointSeparation? {
        var best: AlignmentPointSeparation? = null
        for ((index, point) in existing.withIndex()) {
            val separation = candidate.angularSeparation(point.target)
            // A separation that is NaN (a non-finite candidate) is never smaller, so it is never chosen.
            if (separation < (best?.degrees ?: Double.POSITIVE_INFINITY)) {
                best = AlignmentPointSeparation(separation, index, point.name)
            }
        }
        return best
    }

    /**
     * The smallest separation in degrees between [candidate] and the [existing] points when it is under
     * [minimumPointSeparationDegrees]; null when it is fine or there are no points.
     */
    fun separationWarning(candidate: HorizontalCoordinate, existing: List<AlignmentPoint>): Double? {
        val nearest = bestSeparation(candidate, existing) ?: return null
        return if (nearest.degrees < minimumPointSeparationDegrees) nearest.degrees else null
    }
}
