package net.kuecker.lodestar.features.horizon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.kuecker.lodestar.kit.horizon.AzimuthRange
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.horizon.HorizonRecorder
import net.kuecker.lodestar.kit.pointing.MountProfile
import net.kuecker.lodestar.kit.pointing.PointingSolution
import net.kuecker.lodestar.model.HeadingSource
import net.kuecker.lodestar.services.CompassQuality
import net.kuecker.lodestar.services.MotionSample

/** What is wrong with the sweep right now, if anything. */
internal enum class HorizonHint(val text: String?) {
    NONE(null),
    SLOW_DOWN("Too fast. Turn more slowly."),
    CALIBRATE_COMPASS("The compass needs calibrating. Move the phone in a figure 8, away from metal and magnets."),
    SET_HEADING(
        "Gyro Lock has no heading yet. Choose Compass as the heading source in Settings, or align on a star first.",
    ),
    POINT_AT_SKYLINE("Point the camera at the skyline."),
    INTERFERENCE("Magnetic interference nearby. Move away from metal and electronics, or the directions may be off."),
}

/** What the recording screen draws; a new value is published about ten times a second. */
internal class HorizonSweepState(
    /** The share of the circle recorded, 0…1. */
    val coverage: Double,
    val recordedBins: Int,
    /** One flag per whole degree of azimuth: the bin has a sample. */
    val recordedMask: List<Boolean>,
    /** The skyline recorded so far over the stored one (a copy of the old skyline where nothing is recorded yet). */
    val profile: HorizonProfile,
    /** Where the phone's back camera points now, `null` before the first sample. */
    val azimuth: Double?,
    val altitude: Double?,
    val hint: HorizonHint,
    /** The stretches still to sweep, in azimuth order. */
    val gaps: List<AzimuthRange>,
) {
    val isComplete: Boolean get() = recordedBins >= HorizonProfile.binCount
}

/**
 * One recording of a skyline: feeds the phone's back-camera direction into a [HorizonRecorder] and publishes what the
 * screen needs. Use a new one for every recording. Main thread only (the recorder is not thread safe).
 */
internal class HorizonSweep(private val base: HorizonProfile?) {
    private val recorder = HorizonRecorder()
    private var lastTimestamp = Double.NEGATIVE_INFINITY
    private var lastRejection: HorizonRecorder.Rejection? = null
    private var lastRejectionTime = Double.NEGATIVE_INFINITY
    private var lastPublishNanos = Long.MIN_VALUE
    private var latestTimestamp = 0.0
    private var currentAzimuth: Double? = null
    private var currentAltitude: Double? = null
    private var isHeadingMissing = false
    private var hasInterference = false

    var state by mutableStateOf(snapshot(HorizonHint.NONE))
        private set

    /** The skyline stored with the site when the recording started, drawn faintly for comparison. */
    val storedSkyline: HorizonProfile? get() = base

    /** Whether anything has been recorded. */
    val hasRecording: Boolean get() = recorder.recordedBinCount > 0

    /**
     * Adds the direction of the phone's back camera in [sample] (once per sample). [headingOffset] is the compass
     * correction of the star alignment, [quality] the compass quality for the recorder (see [quality]).
     */
    fun feed(
        sample: MotionSample,
        headingOffset: Double,
        quality: Double?,
        isHeadingMissing: Boolean,
        hasInterference: Boolean,
    ) {
        this.isHeadingMissing = isHeadingMissing
        this.hasInterference = hasInterference
        if (sample.timestamp <= lastTimestamp) return
        lastTimestamp = sample.timestamp
        latestTimestamp = sample.timestamp
        // The user holds the phone: the direction is that of its own back camera, whatever mount is set. The raw
        // attitude is used: the smoothed one lags behind the turn by the smoothing time (2° or more at a normal
        // sweep), which would shift the whole skyline; the recorder's median takes care of the noise.
        val attitude = sample.rawAttitude.withHeadingOffset(headingOffset)
        val boresight = PointingSolution.solve(attitude, MountProfile.backCameraPortrait).boresight
        currentAzimuth = boresight.azimuth
        currentAltitude = boresight.altitude
        val rejection = recorder.add(boresight.azimuth, boresight.altitude, sample.timestamp, quality)
        if (rejection != null) {
            lastRejection = rejection
            lastRejectionTime = sample.timestamp
        }
    }

    /** Publishes the state if [nowNanos] is at least [PUBLISH_INTERVAL_NANOS] after the last time. */
    fun publishIfDue(nowNanos: Long) {
        if (lastPublishNanos != Long.MIN_VALUE && nowNanos - lastPublishNanos < PUBLISH_INTERVAL_NANOS) return
        lastPublishNanos = nowNanos
        val recent = lastRejection.takeIf { latestTimestamp - lastRejectionTime < HINT_SECONDS }
        state = snapshot(hint(recent, recorder.sweepRate, isHeadingMissing, hasInterference))
    }

    /**
     * The result of finishing now: small gaps are interpolated, longer ones are filled as [fill] says (the stored
     * skyline stays in them for `KEEP_BASE`).
     */
    fun finish(fill: HorizonRecorder.UnrecordedFill): HorizonRecorder.Recording = recorder.finish(fill, base)

    /** Forgets everything recorded in this sweep. */
    fun restart() {
        recorder.reset()
        lastRejection = null
        lastPublishNanos = Long.MIN_VALUE
        publishIfDue(0L)
    }

    private fun snapshot(hint: HorizonHint) = HorizonSweepState(
        coverage = recorder.coverage,
        recordedBins = recorder.recordedBinCount,
        recordedMask = recorder.recordedMask,
        profile = recorder.profile(HorizonRecorder.UnrecordedFill.KEEP_BASE, base),
        azimuth = currentAzimuth,
        altitude = currentAltitude,
        hint = hint,
        gaps = recorder.gaps,
    )

    companion object {
        /** The screen refreshes ten times a second. */
        const val PUBLISH_INTERVAL_NANOS = 100_000_000L

        /** A rejection keeps its hint on screen for this long (sensor clock seconds). */
        const val HINT_SECONDS = 1.2

        /** Above this share of the recorder's speed limit the user is told to slow down before samples are dropped. */
        private const val WARNING_SPEED_SHARE = 0.7

        /** Fractional deviation from the expected field strength above which the compass is probably disturbed. */
        const val INTERFERENCE_THRESHOLD = 0.25

        /**
         * The compass quality the recorder wants (0…1, `null` unknown). In Gyro Lock the heading comes from the compass
         * transfer or the star alignment and magnetic interference cannot move it, so it counts as perfect once it is
         * set and as useless before. Otherwise Android's accuracy: unreliable, low, medium, high → 0, 0.2, 0.6, 1.
         */
        fun quality(
            source: HeadingSource,
            isHeadingSet: Boolean,
            compass: CompassQuality,
        ): Double? = when (source) {
            HeadingSource.GYRO_LOCK -> if (isHeadingSet) 1.0 else 0.0
            HeadingSource.COMPASS -> when (compass) {
                CompassQuality.UNAVAILABLE -> null
                CompassQuality.UNCALIBRATED -> 0.0
                CompassQuality.LOW -> 0.2
                CompassQuality.MEDIUM -> 0.6
                CompassQuality.HIGH -> 1.0
            }
        }

        /** What to tell the user, from the latest rejection (if recent), the speed and the compass conditions. */
        fun hint(
            recentRejection: HorizonRecorder.Rejection?,
            sweepRate: Double?,
            isHeadingMissing: Boolean,
            hasInterference: Boolean,
            maximumRate: Double = HorizonRecorder.Configuration.standard.maximumSweepRate,
        ): HorizonHint = when {
            recentRejection == HorizonRecorder.Rejection.LOW_QUALITY ->
                if (isHeadingMissing) HorizonHint.SET_HEADING else HorizonHint.CALIBRATE_COMPASS
            recentRejection == HorizonRecorder.Rejection.TOO_FAST ||
                (sweepRate ?: 0.0) > WARNING_SPEED_SHARE * maximumRate -> HorizonHint.SLOW_DOWN
            recentRejection == HorizonRecorder.Rejection.OUT_OF_RANGE -> HorizonHint.POINT_AT_SKYLINE
            hasInterference -> HorizonHint.INTERFERENCE
            else -> HorizonHint.NONE
        }
    }
}
