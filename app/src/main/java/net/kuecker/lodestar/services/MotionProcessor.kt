package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.Matrix3
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.pointing.AttitudeSmoother
import net.kuecker.lodestar.kit.pointing.DeviceAttitude
import net.kuecker.lodestar.kit.pointing.ReferenceFrames
import net.kuecker.lodestar.model.HeadingSource
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** The sensor reference frame a stream of samples is delivered in. */
enum class MotionStream {
    /** Android's rotation vector: its "north" axis points to magnetic north, whose true azimuth is the declination. */
    MAGNETIC_NORTH,

    /** A frame whose north axis points to true north (not offered by Android sensors; kept for parity). */
    TRUE_NORTH,

    /** Android's game rotation vector: gyro only. Its heading is known only after it was transferred from a compass frame. */
    ARBITRARY,
}

/** Which compass-referenced frames the device offers. */
data class MotionFrameAvailability(val magneticNorth: Boolean, val trueNorth: Boolean)

/**
 * A request to (re)start the sensors in [stream]. Samples of that stream must be fed back with [id], so samples
 * still queued from a previous stream are recognised and dropped.
 */
data class MotionStreamStart(val stream: MotionStream, val id: Int)

/** What the sensor thread asks of the main thread. Each event names the stream it came from. */
sealed interface MotionEvent {
    /** Gyro lock: a steady compass sample is stored, switch to the gyro frame now. */
    data class CompassSteady(val stream: Int) : MotionEvent

    /** The true-north frame cannot deliver. */
    data class TrueNorthUnavailable(val stream: Int) : MotionEvent

    /** The sensors reported another failure. */
    data class Failed(val stream: Int, val code: Int) : MotionEvent
}

/** One sensor sample reduced to plain values. */
data class MotionInput(
    /** Attitude as rows: `v_device = matrix · v_reference`, reference frame north-west-up. */
    val matrix: Matrix3,
    /** Gravity in device coordinates, unit g. */
    val gravity: Vector3,
    /** Rotation speed in degrees per second. */
    val angularSpeed: Double,
    /**
     * Rotation rate about the vertical axis in degrees per second (counter-clockwise seen from above is positive):
     * the part of the rotation that turns the heading.
     */
    val yawRate: Double,
    /** Magnitude of the calibrated magnetic field in microtesla; 0 when the frame provides none. */
    val fieldMicrotesla: Double,
    /** Calibration accuracy reported with the field. */
    val fieldAccuracy: CompassQuality,
    /** Seconds since boot. */
    val timestamp: Double,
) {
    /** Whether the numbers that every step depends on are finite. */
    val isUsable: Boolean
        get() = matrix.isFinite && gravity.isFinite && angularSpeed.isFinite() && yawRate.isFinite() &&
            timestamp.isFinite()
}

/** Where the transfer of the compass heading to the gyro-only frame stands. */
enum class GyroLockPhase {
    /** Compass heading source (or not running). */
    INACTIVE,

    /** Gyro lock wanted; the compass frame runs until the phone stands still. */
    AWAITING_COMPASS,

    /** A steady compass sample is stored; the frame is being switched. */
    COMPASS_CAPTURED,

    /** The gyro frame runs; waiting for a steady sample to compute its heading. */
    AWAITING_GYRO,

    /** The gyro frame carries the heading of the compass. */
    REFERENCED,

    /** The gyro frame has no known heading: the transfer failed or there is no compass. */
    UNREFERENCED,
}

/** The low-rate state the main thread publishes. */
data class MotionSnapshot(
    val stream: MotionStream?,
    val phase: GyroLockPhase,
    val source: HeadingSource,
    val compassQuality: CompassQuality,
    val magneticDisturbance: Double?,
    val frameEpoch: Int,
    val sampleCount: Int,
    val isDeclinationKnown: Boolean,
)

/**
 * The sensor-fusion state machine behind [MotionService], free of Android APIs so it can be reasoned about (and
 * tested) with plain numbers.
 *
 * It turns raw samples into [MotionSample]s: attitudes referenced to true north, smoothing, compass quality and
 * magnetic disturbance. It also runs the gyro-lock transfer: the compass frame is watched until the phone stands
 * still, a steady attitude is stored, and the first steady attitude of the gyro-only frame gives that frame's
 * azimuth, so the gyro attitude continues exactly where the compass attitude was.
 *
 * Not thread-safe: the service shares one instance with the sensor thread behind a lock; every method is short.
 */
class MotionProcessor {
    companion object {
        /** Rotation speed below which the phone counts as standing still, in degrees per second. */
        const val STILL_SPEED = 2.0

        /** How long the phone must stand still before a sample is trusted for the heading transfer, in seconds. */
        const val STEADY_DURATION = 0.25

        /**
         * How long a compass frame must have been running before its heading is trusted for the transfer, in
         * seconds: the fused heading of a freshly started stream has not settled yet.
         */
        const val COMPASS_SETTLING_TIME = 1.0

        /**
         * Largest change of the gravity direction, in degrees, between the stored compass sample and the first
         * steady gyro sample; more means the phone was tilted in between.
         */
        const val MAXIMUM_TILT_CHANGE = 3.0

        /**
         * Most the phone may turn about the vertical axis, in degrees, between the compass sample and the heading
         * transfer. Gravity cannot show such a turn (a plain pan), yet it would put the transferred heading off by
         * exactly its size, so the rotation rate about the vertical is integrated and the transfer is refused
         * beyond this.
         */
        const val MAXIMUM_TRANSFER_TURN = 1.0

        /**
         * Longest interval between two samples that counts towards the integrated turn, in seconds. A longer gap
         * (such as the one while the sensors switch frames) is counted at the rate of the sample that ends it.
         */
        const val MAXIMUM_SAMPLE_GAP = 0.1

        /** Time allowed for the first steady gyro sample after the frame switch, in seconds. */
        const val TRANSFER_TIMEOUT = 2.0

        /** A change of the compass reference azimuth beyond this many degrees makes alignments invalid. */
        const val REFERENCE_STEP = 0.5

        /** Declination changes smaller than this many degrees are ignored. */
        const val DECLINATION_RESOLUTION = 0.01

        /** Time constant of the field-strength average, in seconds. */
        const val FIELD_AVERAGING_TIME = 1.0

        /** Smoothing time constants below this many seconds mean "no smoothing". */
        const val MINIMUM_SMOOTHING = 0.01
        const val DEFAULT_SMOOTHING = 0.12

        /** The compass frame to prefer, if the device has one. */
        fun compassStream(availability: MotionFrameAvailability): MotionStream? = when {
            availability.magneticNorth -> MotionStream.MAGNETIC_NORTH
            availability.trueNorth -> MotionStream.TRUE_NORTH
            else -> null
        }

        private fun referenceAzimuth(stream: MotionStream, declination: Double?): Double =
            if (stream == MotionStream.MAGNETIC_NORTH) declination ?: 0.0 else 0.0
    }

    /** A steady attitude taken in a compass frame, kept for the gyro-lock transfer. */
    private class CompassSample(
        val matrix: Matrix3,
        val azimuth: Double,
        val gravity: Vector3,
        /** The frame was magnetic north and the declination, hence [azimuth], was not known yet. */
        val lacksDeclination: Boolean,
    )

    // Configuration
    var source: HeadingSource = HeadingSource.COMPASS
        private set
    private var declination: Double? = null
    private var expectedField: Double? = null
    private var smoothingTimeConstant = DEFAULT_SMOOTHING

    // Stream
    var stream: MotionStream? = null
        private set
    private var streamID = 0
    private var phase = GyroLockPhase.INACTIVE
    private var gyroAzimuth: Double? = null

    /** The gyro frame's azimuth was derived from a magnetic frame whose declination was still unknown. */
    private var gyroAzimuthLacksDeclination = false
    private var storedCompassSample: CompassSample? = null
    private var transferStart: Double? = null
    private var transferLastTimestamp: Double? = null

    /** Degrees the phone has turned about the vertical axis since the compass sample was stored. */
    private var transferTurn = 0.0

    /** The compass reference azimuth the current alignments are valid for. */
    private var referenceAzimuth: Double? = null

    // Filters
    private var smoother = AttitudeSmoother(DEFAULT_SMOOTHING)
    private var streamStart: Double? = null
    private var stillSince: Double? = null
    private var fieldAverage: Double? = null
    private var fieldTimestamp: Double? = null
    private var isCalibrationRequested = false

    // Results
    var latest: MotionSample? = null
        private set
    var sampleCount = 0
        private set
    var frameEpoch = 0
        private set
    private var compassQuality = CompassQuality.UNAVAILABLE
    private var magneticDisturbance: Double? = null

    val snapshot: MotionSnapshot
        get() = MotionSnapshot(
            stream = stream, phase = phase, source = source, compassQuality = compassQuality,
            magneticDisturbance = magneticDisturbance, frameEpoch = frameEpoch, sampleCount = sampleCount,
            isDeclinationKnown = declination != null,
        )

    fun isCurrent(id: Int): Boolean = stream != null && id == streamID

    // Configuration

    /** Sets the attitude smoothing time constant in seconds; values under 0.01 turn smoothing off. */
    fun setSmoothing(timeConstant: Double) {
        val value = if (timeConstant.isFinite()) min(max(timeConstant, 0.0), 2.0) else DEFAULT_SMOOTHING
        if (value == smoothingTimeConstant) return
        smoothingTimeConstant = value
        smoother = AttitudeSmoother(value)
    }

    /**
     * Sets the magnetic declination (applied to the magnetic frame without a restart) and the expected field
     * strength. A declination step beyond [REFERENCE_STEP] invalidates alignments.
     */
    fun setMagneticReference(reference: MagneticReference?) {
        expectedField = reference?.expectedFieldMicrotesla?.takeIf { it.isFinite() && it > 0 }
        val newDeclination = reference?.declination?.takeIf { it.isFinite() }
        if (newDeclination == declination) return
        val old = declination
        if (old != null && newDeclination != null && abs(newDeclination - old) < DECLINATION_RESOLUTION) return
        declination = newDeclination
        val azimuth = gyroAzimuth
        if (stream == MotionStream.MAGNETIC_NORTH) {
            noteCompassReference(newDeclination ?: 0.0)
        } else if (stream == MotionStream.ARBITRARY && phase == GyroLockPhase.REFERENCED &&
            gyroAzimuthLacksDeclination && newDeclination != null && azimuth != null
        ) {
            // The gyro heading was transferred from a magnetic frame before the declination was known; add it now.
            gyroAzimuth = AstroMath.normalizeSignedDegrees(azimuth + newDeclination)
            gyroAzimuthLacksDeclination = false
            smoother.reset()
            frameEpoch += 1
        }
    }

    /** Changes the heading source. Returns the stream to restart in when the running frame must change. */
    fun setSource(newSource: HeadingSource, availability: MotionFrameAvailability): MotionStreamStart? {
        if (newSource == source) return null
        source = newSource
        val running = stream ?: return null
        val compass = compassStream(availability)
        return when (newSource) {
            HeadingSource.GYRO_LOCK -> {
                // The running compass frame is what the transfer starts from: wait there for a steady phone.
                if (compass == null || running == MotionStream.ARBITRARY) return null
                phase = GyroLockPhase.AWAITING_COMPASS
                storedCompassSample = null
                null
            }
            HeadingSource.COMPASS -> {
                if (running != MotionStream.ARBITRARY || compass == null) {
                    if (running != MotionStream.ARBITRARY) {
                        phase = GyroLockPhase.INACTIVE
                        storedCompassSample = null
                    }
                    return null
                }
                // Leaving the gyro frame: its heading and the compass heading differ by the accumulated drift.
                frameEpoch += 1
                phase = GyroLockPhase.INACTIVE
                begin(compass)
            }
        }
    }

    // Lifecycle

    /**
     * Starts (or restarts) in the first frame the source needs: the compass frame, which for gyro lock is only the
     * first step. Restarting while the gyro frame carries a transferred heading loses that heading, which starts a
     * new alignment epoch.
     */
    fun start(availability: MotionFrameAvailability): MotionStreamStart {
        if (stream == MotionStream.ARBITRARY &&
            (phase == GyroLockPhase.REFERENCED || phase == GyroLockPhase.UNREFERENCED)
        ) {
            frameEpoch += 1
        }
        storedCompassSample = null
        gyroAzimuth = null
        gyroAzimuthLacksDeclination = false
        resetTransfer()
        val compass = compassStream(availability)
        if (compass == null) {
            // No compass at all: gyro only, heading unknown until the user aligns on a star.
            phase = GyroLockPhase.UNREFERENCED
            frameEpoch += 1
            return begin(MotionStream.ARBITRARY)
        }
        phase = if (source == HeadingSource.GYRO_LOCK) GyroLockPhase.AWAITING_COMPASS else GyroLockPhase.INACTIVE
        return begin(compass)
    }

    /** Gyro lock: switches to the gyro-only frame after [MotionEvent.CompassSteady]. `null` if the event is stale. */
    fun switchToGyroFrame(): MotionStreamStart? {
        if (phase != GyroLockPhase.COMPASS_CAPTURED || storedCompassSample == null) return null
        phase = GyroLockPhase.AWAITING_GYRO
        transferStart = null // the turn since the capture is kept: it counts against the transfer
        return begin(MotionStream.ARBITRARY)
    }

    /** The true-north frame cannot deliver: fall back to the magnetic frame, or to gyro only. */
    fun recoverFromTrueNorthFailure(id: Int, availability: MotionFrameAvailability): MotionStreamStart? {
        if (stream != MotionStream.TRUE_NORTH || id != streamID) return null
        if (availability.magneticNorth) return begin(MotionStream.MAGNETIC_NORTH)
        phase = GyroLockPhase.UNREFERENCED
        frameEpoch += 1
        return begin(MotionStream.ARBITRARY)
    }

    /**
     * Stops processing and forgets everything but the alignment epoch and the configuration. Stopping the
     * gyro-only frame loses its heading, so an alignment made in it becomes invalid.
     */
    fun stop() {
        if (stream == MotionStream.ARBITRARY &&
            (phase == GyroLockPhase.REFERENCED || phase == GyroLockPhase.UNREFERENCED)
        ) {
            frameEpoch += 1
        }
        stream = null
        streamID += 1
        phase = GyroLockPhase.INACTIVE
        gyroAzimuth = null
        gyroAzimuthLacksDeclination = false
        storedCompassSample = null
        resetTransfer()
        resetFilters()
        latest = null
        sampleCount = 0
        compassQuality = CompassQuality.UNAVAILABLE
        magneticDisturbance = null
        isCalibrationRequested = false
    }

    /** The magnetometer needs calibrating (the user should move the phone in a figure eight). */
    fun noteCalibrationRequested(id: Int) {
        if (id == streamID) isCalibrationRequested = true
    }

    // Samples

    /** Processes one sample of stream [id]. Returns an event for the main thread when one is due. */
    fun process(sample: MotionInput, id: Int): MotionEvent? {
        val stream = this.stream
        if (id != streamID || stream == null || !sample.isUsable) return null
        val input = if (sample.fieldMicrotesla.isFinite()) sample else sample.copy(fieldMicrotesla = 0.0)
        sampleCount += 1
        if (streamStart == null) streamStart = input.timestamp
        updateStillness(input)
        updateCompass(input, stream)

        when (stream) {
            MotionStream.MAGNETIC_NORTH, MotionStream.TRUE_NORTH -> {
                val azimuth = referenceAzimuth(stream, declination)
                publish(input, azimuth)
                if (phase == GyroLockPhase.COMPASS_CAPTURED) {
                    // The frame switch is still on its way: a turn since the capture makes the stored sample stale.
                    accumulateTurn(input)
                    if (abs(transferTurn) > MAXIMUM_TRANSFER_TURN) {
                        phase = GyroLockPhase.AWAITING_COMPASS
                        storedCompassSample = null
                        resetTransfer()
                    }
                } else if (phase == GyroLockPhase.AWAITING_COMPASS && isSteady(input.timestamp) &&
                    isSettled(input.timestamp) && compassQuality != CompassQuality.UNCALIBRATED
                ) {
                    storedCompassSample = CompassSample(
                        matrix = input.matrix, azimuth = azimuth, gravity = input.gravity,
                        lacksDeclination = stream == MotionStream.MAGNETIC_NORTH && declination == null,
                    )
                    phase = GyroLockPhase.COMPASS_CAPTURED
                    resetTransfer()
                    transferLastTimestamp = input.timestamp
                    return MotionEvent.CompassSteady(streamID)
                }
            }
            MotionStream.ARBITRARY -> {
                if (phase == GyroLockPhase.AWAITING_GYRO) resolveTransfer(input)
                // While the transfer is pending the last compass sample stays published: the phone stood still, so it
                // is still right, whereas an arbitrary-frame sample has no heading yet.
                if (phase != GyroLockPhase.AWAITING_GYRO) publish(input, gyroAzimuth ?: 0.0)
            }
        }
        return null
    }

    // Private

    private fun begin(next: MotionStream): MotionStreamStart {
        stream = next
        streamID += 1
        resetFilters()
        if (next != MotionStream.ARBITRARY) noteCompassReference(referenceAzimuth(next, declination))
        return MotionStreamStart(next, streamID)
    }

    private fun resetTransfer() {
        transferStart = null
        transferLastTimestamp = null
        transferTurn = 0.0
    }

    private fun resetFilters() {
        smoother.reset()
        streamStart = null
        stillSince = null
        fieldAverage = null
        fieldTimestamp = null
    }

    /** Records the compass reference azimuth; a step beyond [REFERENCE_STEP] starts a new alignment epoch. */
    private fun noteCompassReference(azimuth: Double) {
        val known = referenceAzimuth
        if (known == null) {
            referenceAzimuth = azimuth
            return
        }
        if (abs(AstroMath.normalizeSignedDegrees(azimuth - known)) > REFERENCE_STEP) {
            frameEpoch += 1
            referenceAzimuth = azimuth
        }
    }

    private fun updateStillness(input: MotionInput) {
        if (input.angularSpeed < STILL_SPEED) {
            if (stillSince == null) stillSince = input.timestamp
        } else {
            stillSince = null
        }
    }

    private fun isSteady(timestamp: Double): Boolean {
        val since = stillSince ?: return false
        return timestamp - since >= STEADY_DURATION
    }

    private fun isSettled(timestamp: Double): Boolean {
        val start = streamStart ?: return false
        return timestamp - start >= COMPASS_SETTLING_TIME
    }

    private fun updateCompass(input: MotionInput, stream: MotionStream) {
        if (stream == MotionStream.ARBITRARY || !input.fieldMicrotesla.isFinite() || input.fieldMicrotesla <= 0) {
            compassQuality = CompassQuality.UNAVAILABLE
            magneticDisturbance = null
            fieldAverage = null
            fieldTimestamp = null
            return
        }
        if (isCalibrationRequested && input.fieldAccuracy >= CompassQuality.MEDIUM) isCalibrationRequested = false
        compassQuality = if (isCalibrationRequested) CompassQuality.UNCALIBRATED else input.fieldAccuracy

        val average = fieldAverage
        val last = fieldTimestamp
        fieldAverage = if (average != null && last != null && input.timestamp > last) {
            val weight = 1 - exp(-(input.timestamp - last) / FIELD_AVERAGING_TIME)
            average + weight * (input.fieldMicrotesla - average)
        } else {
            input.fieldMicrotesla
        }
        fieldTimestamp = input.timestamp

        val expected = expectedField
        val smoothedField = fieldAverage
        magneticDisturbance = if (compassQuality >= CompassQuality.LOW && expected != null && smoothedField != null) {
            abs(smoothedField - expected) / expected
        } else {
            null
        }
    }

    /** Computes the gyro frame's azimuth from the first steady sample, or gives up after [TRANSFER_TIMEOUT]. */
    private fun resolveTransfer(input: MotionInput) {
        if (transferStart == null) transferStart = input.timestamp
        val stored = storedCompassSample
        if (stored == null) {
            failTransfer()
            return
        }
        accumulateTurn(input)
        if (abs(transferTurn) > MAXIMUM_TRANSFER_TURN) {
            failTransfer()
            return
        }
        if (isSteady(input.timestamp)) {
            // `stored.matrix · T(stored.azimuth)` and `input.matrix · T(ψ)` describe one orientation; solve for ψ.
            val solved = if (stored.gravity.angle(input.gravity) <= MAXIMUM_TILT_CHANGE) {
                ReferenceFrames.referenceAzimuth(input.matrix, stored.matrix, stored.azimuth)
            } else {
                null
            }
            if (solved == null) {
                failTransfer()
                return
            }
            var azimuth: Double = solved
            if (stored.lacksDeclination) {
                val known = declination
                if (known != null) {
                    azimuth = AstroMath.normalizeSignedDegrees(azimuth + known)
                } else {
                    gyroAzimuthLacksDeclination = true
                }
            }
            gyroAzimuth = azimuth
            phase = GyroLockPhase.REFERENCED
            smoother.reset()
        } else {
            val start = transferStart
            if (start != null && input.timestamp - start > TRANSFER_TIMEOUT) failTransfer()
        }
    }

    /** Adds the rotation about the vertical axis since the previous sample to the turn since the capture. */
    private fun accumulateTurn(input: MotionInput) {
        val last = transferLastTimestamp
        if (last != null && input.timestamp > last) {
            transferTurn += input.yawRate * min(input.timestamp - last, MAXIMUM_SAMPLE_GAP)
        }
        transferLastTimestamp = input.timestamp
    }

    private fun failTransfer() {
        gyroAzimuth = null
        gyroAzimuthLacksDeclination = false
        phase = GyroLockPhase.UNREFERENCED
        frameEpoch += 1
        smoother.reset()
    }

    private fun publish(input: MotionInput, azimuth: Double) {
        val raw = DeviceAttitude.create(input.matrix, azimuth, input.timestamp) ?: return
        val smoothed = if (smoothingTimeConstant >= MINIMUM_SMOOTHING) smoother.update(raw) else raw
        val usesField = stream != MotionStream.ARBITRARY && input.fieldMicrotesla.isFinite() && input.fieldMicrotesla > 0
        latest = MotionSample(
            attitude = smoothed, rawAttitude = raw, gravity = input.gravity, angularSpeed = input.angularSpeed,
            magneticFieldMicrotesla = if (usesField) input.fieldMicrotesla else null,
            compassQuality = compassQuality, timestamp = input.timestamp,
        )
    }
}
