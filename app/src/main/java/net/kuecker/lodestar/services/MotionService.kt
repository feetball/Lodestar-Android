package net.kuecker.lodestar.services

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kuecker.lodestar.BuildConfig
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Matrix3
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.pointing.DeviceAttitude
import net.kuecker.lodestar.model.HeadingSource
import kotlin.math.sqrt

/**
 * The magnetic field at the observer from the World Magnetic Model: how to turn magnetic north into true north
 * and how strong the field should be.
 */
data class MagneticReference(
    /** Degrees, east positive. */
    val declination: Double,
    /** Microtesla. */
    val expectedFieldMicrotesla: Double,
)

/** How far the compass can be trusted, from the magnetometer's calibration accuracy. Ordered worst to best. */
enum class CompassQuality {
    /** No magnetic data (gyro-only frame or no magnetometer). */
    UNAVAILABLE,
    UNCALIBRATED,
    LOW,
    MEDIUM,
    HIGH,
}

sealed interface MotionStatus {
    data object Idle : MotionStatus
    data object Starting : MotionStatus
    data object Running : MotionStatus

    /** This device has no usable orientation sensors. */
    data object Unavailable : MotionStatus
    data class Failed(val message: String) : MotionStatus
}

/** One sensor sample in app terms (copied out of the sensor events on the sensor thread). */
data class MotionSample(
    /** Smoothed, with the reference azimuth applied (magnetic frame: WMM declination; gyro frame: transferred). */
    val attitude: DeviceAttitude,
    /** Unsmoothed; use it for capturing alignment points. */
    val rawAttitude: DeviceAttitude,
    /** Device coordinates, unit g. */
    val gravity: Vector3,
    /** Degrees per second. */
    val angularSpeed: Double,
    /** Magnitude of the calibrated field in microtesla, when the frame provides one. */
    val magneticFieldMicrotesla: Double?,
    val compassQuality: CompassQuality,
    /** Seconds since boot. */
    val timestamp: Double,
)

/**
 * The phone's attitude from the Android sensors at 60 Hz, as true-north-referenced [DeviceAttitude]s.
 *
 * Samples are produced on a private sensor thread and published through a lock ([latestSample]), so render loops
 * can poll them from any thread. The observable properties are low-rate state, refreshed on the main thread about
 * twice a second while running.
 *
 * Heading sources:
 * - Compass: the rotation vector (magnetic north) plus the WMM declination; on devices without a rotation vector,
 *   the accelerometer and magnetometer.
 * - Gyro lock: once the compass frame has settled and the phone stands still, the compass heading is transferred to
 *   the game rotation vector (gyroscope only), which magnetic disturbances cannot affect. Until then the compass
 *   frame keeps running. The transfer is refused ([isGyroHeadingReferenced] stays `false`, [frameEpoch] advances)
 *   if the phone turns more than a degree about the vertical or tilts by several degrees in the meantime, or if it
 *   takes longer than two seconds; the heading then has to be set by star alignment.
 */
class MotionService(context: Context, private val scope: CoroutineScope) {
    private companion object {
        const val SAMPLING_PERIOD_US = 16_667
        const val FIELD_PERIOD_US = 20_000
        const val POLL_INTERVAL_MS = 500L

        /** Polls without any new sample after which the sensor stream counts as stalled (about 3 s). */
        const val STALLED_POLL_LIMIT = 6

        /** Low-pass weight per accelerometer sample for the accelerometer + magnetometer fallback. */
        const val GRAVITY_FILTER = 0.15
        const val DEGREES_PER_RADIAN = 180.0 / Math.PI
    }

    var status: MotionStatus by mutableStateOf(MotionStatus.Idle)
        private set
    var compassQuality by mutableStateOf(CompassQuality.UNAVAILABLE)
        private set

    /** Fractional deviation |measured field − WMM expected| / expected, `null` when unknown. Above 0.25 the
     * compass is probably disturbed. */
    var magneticDisturbance by mutableStateOf<Double?>(null)
        private set

    /** The source the samples actually come from. */
    var headingSource by mutableStateOf(HeadingSource.COMPASS)
        private set

    /** In gyro lock: whether the heading was transferred from the compass (otherwise the user must align on a star). */
    var isGyroHeadingReferenced by mutableStateOf(false)
        private set

    /**
     * Increases whenever the attitude reference changes discontinuously (frame change, failed gyro transfer,
     * large declination step); alignments made before are invalid.
     */
    var frameEpoch by mutableIntStateOf(0)
        private set
    var referenceDescription by mutableStateOf("Not running")
        private set

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val rotationVector: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameRotationVector: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** Whether the device can run gyro lock (it needs the gyroscope-only game rotation vector). */
    val isGyroLockAvailable: Boolean = gameRotationVector != null

    private val lock = Any()
    private val processor = MotionProcessor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sensorThread: HandlerThread? = null
    private var listener: StreamListener? = null
    private var pollJob: Job? = null
    private var isRunning = false
    private var lastSampleCount = 0
    private var stalledPolls = 0

    @Volatile
    private var screenshotSample: MotionSample? = null

    /** The newest sample, or `null` when none has arrived yet. Callable from any thread; cheap. */
    fun latestSample(): MotionSample? = screenshotSample ?: synchronized(lock) { processor.latest }

    /**
     * Applies the current settings. Call as often as needed: nothing restarts unless the heading source changes;
     * a new declination takes effect on the running stream.
     */
    fun configure(headingSource: HeadingSource, smoothingTimeConstant: Double, magneticReference: MagneticReference?) {
        val source = if (isGyroLockAvailable) headingSource else HeadingSource.COMPASS
        val restart = synchronized(lock) {
            processor.setSmoothing(smoothingTimeConstant)
            processor.setMagneticReference(magneticReference)
            processor.setSource(source, frameAvailability())
        }
        if (restart != null && isRunning) launch(restart)
        publish()
    }

    /** Starts the sensors. Idempotent. */
    fun start() {
        if (screenshotSample != null || isRunning) return
        if (rotationVector == null && gameRotationVector == null && (accelerometer == null || magnetometer == null)) {
            update(MotionStatus.Unavailable)
            return
        }
        isRunning = true
        status = MotionStatus.Starting
        lastSampleCount = 0
        sensorThread = HandlerThread("LodestarMotion", android.os.Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        val first = synchronized(lock) { processor.start(frameAvailability()) }
        launch(first)
        startPolling()
        publish()
    }

    /** Stops the sensors and clears the latest sample. Idempotent. */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
        sensorThread?.quitSafely()
        sensorThread = null
        pollJob?.cancel()
        pollJob = null
        synchronized(lock) { processor.stop() }
        status = MotionStatus.Idle
        publish()
    }

    /**
     * Replaces the sensors with a level, portrait back-camera pointing at [boresight] (debug screenshot mode). Later
     * calls move the pointing on; [timestamp] is the sensor clock of the sample, in seconds.
     */
    fun useScreenshotPointing(boresight: HorizontalCoordinate, timestamp: Double = 1.0) {
        if (!BuildConfig.DEBUG) return
        val forward = boresight.unitVector
        val up = (Vector3(0.0, 0.0, 1.0) - forward * forward.z).normalized
        val back = -forward
        val right = up.cross(back)
        // The reference frame is north-west-up; the matrix rows are the device axes.
        fun northWestUp(v: Vector3) = Vector3(v.y, -v.x, v.z)
        val matrix = Matrix3(northWestUp(right), northWestUp(up), northWestUp(back))
        val attitude = DeviceAttitude.create(matrix, 0.0, 1.0) ?: return
        screenshotSample = MotionSample(
            attitude = attitude, rawAttitude = attitude, gravity = matrix * Vector3(0.0, 0.0, -1.0),
            angularSpeed = 0.0, magneticFieldMicrotesla = null, compassQuality = CompassQuality.HIGH,
            timestamp = timestamp,
        )
        status = MotionStatus.Running
        compassQuality = CompassQuality.HIGH
        referenceDescription = "Screenshot mode"
    }

    // Sensors

    private fun frameAvailability() = MotionFrameAvailability(
        magneticNorth = rotationVector != null || (accelerometer != null && magnetometer != null),
        trueNorth = false,
    )

    /** (Re)registers the sensors for the stream. Events still queued for the old stream are dropped by their id. */
    private fun launch(start: MotionStreamStart) {
        val manager = sensorManager ?: return
        val handler = sensorThread?.looper?.let(::Handler) ?: return
        listener?.let { manager.unregisterListener(it) }
        val next = StreamListener(start.id, start.stream)
        listener = next
        when (start.stream) {
            MotionStream.MAGNETIC_NORTH, MotionStream.TRUE_NORTH -> {
                if (rotationVector != null) {
                    manager.registerListener(next, rotationVector, SAMPLING_PERIOD_US, handler)
                } else {
                    manager.registerListener(next, accelerometer, SAMPLING_PERIOD_US, handler)
                }
                magnetometer?.let { manager.registerListener(next, it, FIELD_PERIOD_US, handler) }
            }
            MotionStream.ARBITRARY -> {
                gameRotationVector?.let { manager.registerListener(next, it, SAMPLING_PERIOD_US, handler) }
            }
        }
        gyroscope?.let { manager.registerListener(next, it, SAMPLING_PERIOD_US, handler) }
        stalledPolls = 0
    }

    private fun onEvent(event: MotionEvent) {
        mainHandler.post { handle(event) }
    }

    private fun handle(event: MotionEvent) {
        if (!isRunning) return
        when (event) {
            is MotionEvent.CompassSteady -> synchronized(lock) { processor.switchToGyroFrame() }?.let(::launch)
            is MotionEvent.TrueNorthUnavailable ->
                synchronized(lock) { processor.recoverFromTrueNorthFailure(event.stream, frameAvailability()) }
                    ?.let(::launch)
            is MotionEvent.Failed -> {
                val (current, count) = synchronized(lock) { processor.isCurrent(event.stream) to processor.sampleCount }
                if (current) {
                    lastSampleCount = count
                    stalledPolls = 0
                    status = MotionStatus.Failed("The motion sensors reported an error (code ${event.code}).")
                }
            }
        }
        publish()
    }

    /**
     * Receives the events of one stream on the sensor thread and turns them into [MotionInput]s. The latest
     * gyroscope and magnetometer readings are attached to each attitude sample.
     */
    private inner class StreamListener(private val streamID: Int, private val stream: MotionStream) :
        SensorEventListener {
        private val rotation = FloatArray(9)
        private var rate: FloatArray? = null
        private var fieldMicrotesla = 0.0
        private var fieldAccuracy = CompassQuality.UNAVAILABLE
        private var filteredGravity: FloatArray? = null
        private var magneticVector: FloatArray? = null
        private var previous: FloatArray? = null
        private var previousTimestamp = 0.0

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> rate = event.values.copyOf(3)
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                    fieldMicrotesla = sqrt((x * x + y * y + z * z).toDouble())
                    fieldAccuracy = quality(event.accuracy)
                    magneticVector = event.values.copyOf(3)
                }
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotation, event.values)
                    deliver(event.timestamp / 1e9)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val gravity = filteredGravity?.also {
                        for (i in 0..2) it[i] += (GRAVITY_FILTER * (event.values[i] - it[i])).toFloat()
                    } ?: event.values.copyOf(3).also { filteredGravity = it }
                    val field = magneticVector ?: return
                    if (SensorManager.getRotationMatrix(rotation, null, gravity, field)) deliver(event.timestamp / 1e9)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) fieldAccuracy = quality(accuracy)
        }

        private fun deliver(timestamp: Double) {
            val r = rotation
            // Android: v_world(east, north, up) = R · v_device. The kit wants v_device = M · v_reference with a
            // north-west-up reference, so row i of M is device axis i (column i of R) in north-west-up coordinates.
            val matrix = Matrix3(
                Vector3(r[3].toDouble(), -r[0].toDouble(), r[6].toDouble()),
                Vector3(r[4].toDouble(), -r[1].toDouble(), r[7].toDouble()),
                Vector3(r[5].toDouble(), -r[2].toDouble(), r[8].toDouble()),
            )
            // Up in device coordinates is the third row of R; gravity points the other way.
            val up = Vector3(r[6].toDouble(), r[7].toDouble(), r[8].toDouble())
            val (speed, yaw) = rates(up, timestamp)
            val usesField = stream != MotionStream.ARBITRARY
            val input = MotionInput(
                matrix = matrix, gravity = -up, angularSpeed = speed, yawRate = yaw,
                fieldMicrotesla = if (usesField) fieldMicrotesla else 0.0,
                fieldAccuracy = if (usesField) fieldAccuracy else CompassQuality.UNAVAILABLE,
                timestamp = timestamp,
            )
            val event = synchronized(lock) { processor.process(input, streamID) }
            if (event != null) onEvent(event)
        }

        /**
         * Rotation speed and the rate about the vertical (degrees per second): from the gyroscope when present,
         * otherwise from the change of attitude since the previous sample.
         */
        private fun rates(up: Vector3, timestamp: Double): Pair<Double, Double> {
            rate?.let { w ->
                val omega = Vector3(w[0].toDouble(), w[1].toDouble(), w[2].toDouble())
                return omega.length * DEGREES_PER_RADIAN to omega.dot(up) * DEGREES_PER_RADIAN
            }
            val last = previous
            val dt = timestamp - previousTimestamp
            previous = rotation.copyOf()
            previousTimestamp = timestamp
            if (last == null || dt <= 0 || dt > 1) return 0.0 to 0.0
            // Q = R_now · R_lastᵀ is the world-frame rotation since the last sample; small-angle axis × angle.
            fun q(i: Int, j: Int): Double {
                var sum = 0.0
                for (k in 0..2) sum += rotation[i * 3 + k].toDouble() * last[j * 3 + k].toDouble()
                return sum
            }
            val omega = Vector3((q(2, 1) - q(1, 2)) / 2, (q(0, 2) - q(2, 0)) / 2, (q(1, 0) - q(0, 1)) / 2) / dt
            return omega.length * DEGREES_PER_RADIAN to omega.z * DEGREES_PER_RADIAN
        }

        private fun quality(accuracy: Int) = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CompassQuality.HIGH
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CompassQuality.MEDIUM
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CompassQuality.LOW
            SensorManager.SENSOR_STATUS_UNRELIABLE -> CompassQuality.UNCALIBRATED
            else -> CompassQuality.UNAVAILABLE
        }
    }

    // Polling

    private fun startPolling() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                poll()
            }
        }
    }

    private fun poll() {
        val snapshot = publish()
        if (!isRunning) return
        if (snapshot.sampleCount != lastSampleCount) {
            lastSampleCount = snapshot.sampleCount
            stalledPolls = 0
            update(MotionStatus.Running)
        } else {
            stalledPolls += 1
            if (stalledPolls >= STALLED_POLL_LIMIT) recoverFromStall()
        }
    }

    /** No samples for a while: start over (gyro lock begins again from the compass frame). */
    private fun recoverFromStall() {
        status = MotionStatus.Failed("Motion data stopped arriving.")
        val restart = synchronized(lock) { processor.start(frameAvailability()) }
        launch(restart)
    }

    private fun publish(): MotionSnapshot {
        val snapshot = synchronized(lock) { processor.snapshot }
        if (screenshotSample != null) return snapshot
        if (compassQuality != snapshot.compassQuality) compassQuality = snapshot.compassQuality
        if (magneticDisturbance != snapshot.magneticDisturbance) magneticDisturbance = snapshot.magneticDisturbance
        if (frameEpoch != snapshot.frameEpoch) frameEpoch = snapshot.frameEpoch
        val referenced = snapshot.phase == GyroLockPhase.REFERENCED
        if (isGyroHeadingReferenced != referenced) isGyroHeadingReferenced = referenced
        val source = effectiveSource(snapshot)
        if (headingSource != source) headingSource = source
        val description = description(snapshot)
        if (referenceDescription != description) referenceDescription = description
        return snapshot
    }

    private fun update(newStatus: MotionStatus) {
        if (status != newStatus) status = newStatus
    }

    private fun effectiveSource(snapshot: MotionSnapshot): HeadingSource {
        val stream = snapshot.stream
        if (!isRunning || stream == null) return snapshot.source
        return if (stream == MotionStream.ARBITRARY) HeadingSource.GYRO_LOCK else HeadingSource.COMPASS
    }

    private fun description(snapshot: MotionSnapshot): String {
        val stream = snapshot.stream
        if (!isRunning || stream == null) return "Not running"
        val isPending = snapshot.phase == GyroLockPhase.AWAITING_COMPASS ||
            snapshot.phase == GyroLockPhase.COMPASS_CAPTURED
        return when (stream) {
            MotionStream.MAGNETIC_NORTH -> {
                val base = if (snapshot.isDeclinationKnown) "Magnetic north + WMM" else "Magnetic north (declination unknown)"
                if (isPending) "$base, gyro lock pending" else base
            }
            MotionStream.TRUE_NORTH -> if (isPending) "True north, gyro lock pending" else "True north"
            MotionStream.ARBITRARY -> when (snapshot.phase) {
                GyroLockPhase.REFERENCED -> "Gyro lock"
                GyroLockPhase.AWAITING_GYRO -> "Gyro lock (setting heading)"
                else -> "Gyro lock (heading not set)"
            }
        }
    }
}
