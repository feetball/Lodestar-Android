package net.kuecker.lodestar.kit.pointing

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonElement
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One alignment measurement: the camera was centred on an object whose true position is known
 * while the phone reported [attitude].
 *
 * Create values with `AlignmentPoint(attitude, target, timestamp, name)`; the name is shortened to 120
 * characters. In JSON the timestamp is the number of seconds since 2001-01-01T00:00:00Z, which is how
 * Swift's `JSONEncoder` writes a `Date` by default; a missing name is left out.
 */
@Serializable(with = AlignmentPointSerializer::class)
@ConsistentCopyVisibility
data class AlignmentPoint private constructor(
    /**
     * The attitude fed to [PointingSolution.solve] when the camera was centred (smoothed, if the
     * arrows are driven by a smoothed attitude), without any heading offset or correction applied.
     */
    val attitude: DeviceAttitude,
    /** The true apparent (refracted) horizontal position of the object the camera was centred on. */
    val target: HorizontalCoordinate,
    /** When the measurement was taken. */
    val timestamp: Instant,
    /** Display name of the object, if any. */
    val name: String?,
) {
    companion object {
        private const val maximumNameLength = 120

        /** Records a measurement. [name] is shortened to 120 characters. */
        operator fun invoke(
            attitude: DeviceAttitude,
            target: HorizontalCoordinate,
            timestamp: Instant,
            name: String? = null,
        ): AlignmentPoint = AlignmentPoint(attitude, target, timestamp, name?.prefixCharacters(maximumNameLength))
    }
}

/** Why an alignment point was rejected. */
sealed class AlignmentError(message: String) : Exception(message) {
    /**
     * An attitude or target coordinate is not a finite number. A singleton, so the stack trace it carries
     * is that of its creation, not of the `throw`.
     */
    data object NonFiniteInput : AlignmentError("An attitude or target coordinate is not a finite number.")

    /**
     * The phone-reported boresight is too close to the zenith or nadir for its azimuth to be
     * meaningful.
     *
     * @property altitude The boresight altitude in degrees.
     */
    data class BoresightTooHigh(val altitude: Double) : AlignmentError(
        "The phone-reported boresight (altitude ${formatFixed(altitude, 1)}°) is too close to the zenith " +
            "or nadir for its azimuth to be meaningful.",
    )

    /**
     * The target is too close to the zenith or nadir for its azimuth to be meaningful.
     *
     * @property altitude The target altitude in degrees.
     */
    data class TargetTooHigh(val altitude: Double) : AlignmentError(
        "The target (altitude ${formatFixed(altitude, 1)}°) is too close to the zenith or nadir for its " +
            "azimuth to be meaningful.",
    )

    /**
     * The points would need a device-frame correction larger than a mount could plausibly cause —
     * usually a wrong object was centred, or the wrong mount profile is selected.
     *
     * @property degrees The size of the correction in degrees.
     */
    data class ImplausibleCorrection(val degrees: Double) : AlignmentError(
        "The points would need a device-frame correction of ${formatFixed(degrees, 1)}°, more than a mount " +
            "could plausibly cause. Usually a wrong object was centred, or the wrong mount profile is selected.",
    )
}

/**
 * The calibration that makes the phone's sensors agree with where the camera really points.
 *
 * Two independent effects are modelled:
 * * [headingOffset] — the compass error: the corrected azimuth is the measured azimuth plus the
 *   offset (a rotation about the vertical);
 * * [deviceCorrection] — the small rotation, in the phone's device frame, between the mount's
 *   nominal camera axis and the camera's true axis (clamp tilt and twist). It is a rotation
 *   vector: the direction is the axis, the length the angle in degrees.
 *
 * One point is solved exactly (an altitude error is blamed on the mount, an azimuth error on the
 * compass). Two or more points are fitted by damped least squares, which separates the two
 * effects when the points are spread in altitude and camera roll. An alignment is only valid for
 * the mount it was solved for ([calibratedMount]); change the mount and the app should start over.
 *
 * Values come from [solve], [adding], [removingPoint] and [identity]; the constructor is private and
 * the internal `AlignmentModel(headingOffset, …)` normalises the heading offset like Swift's internal
 * initializer. The JSON form has the keys `headingOffset`, `deviceCorrection`, `points`, `residuals`
 * and, when present, `pointingUncertainty` and `calibratedMount`; decoding rejects out-of-range and
 * inconsistent values.
 */
@Serializable(with = AlignmentModelSerializer::class)
@ConsistentCopyVisibility
data class AlignmentModel private constructor(
    /** Compass correction in degrees, (−180, 180]; corrected azimuth = measured azimuth + offset. */
    val headingOffset: Double,
    /** Device-frame rotation vector in degrees applied to the mount's camera axis and up vector. */
    val deviceCorrection: Vector3,
    /** The measurements the model was fitted to, oldest first. */
    val points: List<AlignmentPoint>,
    /** Angular distance in degrees between the corrected boresight and the target of each point. */
    val residuals: List<Double>,
    /**
     * Expected pointing error in degrees (root-mean-square over the sky and camera rolls) of the
     * corrected camera away from the aligned points; null for [identity]. It follows from the
     * geometry of the points and an assumed centring accuracy of about 0.25° each.
     *
     * The residuals only say how well the points agree with each other, and a single point always
     * agrees perfectly. This number also says how well the points separate compass error from
     * mount error. It is about 13° (the scale of the solver's prior: "unknown") for one point and
     * for any number of points at the same altitude and camera roll, and falls to a few tenths of
     * a degree for three or more points spread in altitude and roll.
     */
    val pointingUncertainty: Double?,
    /** The mount the model was solved for; null for [identity]. */
    val calibratedMount: MountProfile?,
) {
    /** `true` once at least one point has been recorded. */
    val isAligned: Boolean
        get() = points.isNotEmpty()

    /** Root-mean-square of [residuals] in degrees; 0 without points. */
    val rmsResidual: Double
        get() {
            if (residuals.isEmpty()) return 0.0
            return sqrt(residuals.fold(0.0) { total, residual -> total + residual * residual } / residuals.size)
        }

    /** The device-frame correction as a rotation matrix. */
    internal val deviceCorrectionMatrix: Matrix3
        get() = PointingMath.rotationMatrix(deviceCorrection * AstroMath.radiansPerDegree)

    /**
     * `true` when the model may be used with [mount]: it was solved for the same camera axis and up
     * vector, or it is the identity.
     */
    fun isApplicable(mount: MountProfile): Boolean {
        val calibrated = calibratedMount ?: return true
        return calibrated.cameraAxis.dot(mount.cameraAxis) > 1 - 1e-9 &&
            calibrated.cameraUp.dot(mount.cameraUp) > 1 - 1e-9
    }

    /**
     * The compass correction to apply for [mount]: [headingOffset] when the model is applicable,
     * otherwise 0. Use it for anything drawn from the phone's own camera (see [PinholeProjection]);
     * [PointingSolution.solve] applies the same rule.
     */
    fun headingOffset(mount: MountProfile): Double = if (isApplicable(mount)) headingOffset else 0.0

    /**
     * The model refitted with one more point. Points recorded for a different mount are discarded.
     *
     * @throws AlignmentError See [solve].
     */
    @Throws(AlignmentError::class)
    fun adding(point: AlignmentPoint, mount: MountProfile): AlignmentModel {
        val earlier = if (isApplicable(mount)) points else emptyList()
        return solve(earlier + point, mount)
    }

    /**
     * The model refitted without the point at [index]; unchanged when the index is out of range.
     *
     * @throws AlignmentError See [solve].
     */
    @Throws(AlignmentError::class)
    fun removingPoint(index: Int, mount: MountProfile): AlignmentModel {
        if (index !in points.indices) return this
        val remaining = points.toMutableList()
        remaining.removeAt(index)
        return solve(remaining, mount)
    }

    companion object {
        /** Most points kept; adding more drops the oldest. */
        const val maximumPointCount: Int = 12

        /**
         * Points whose boresight or target is higher than this (or lower than its negative), in degrees,
         * are rejected because their azimuth is ill-conditioned.
         */
        const val maximumAltitude: Double = 80.0

        /** Largest accepted device-frame correction in degrees. */
        const val maximumDeviceCorrection: Double = 45.0

        /** No correction at all. */
        val identity: AlignmentModel = AlignmentModel(
            headingOffset = 0.0, deviceCorrection = Vector3.zero, points = emptyList(), residuals = emptyList(),
            pointingUncertainty = null, calibratedMount = null,
        )

        /**
         * Creates a model from its parts; [headingOffset] is normalised to (−180, 180]. This is Swift's
         * internal initializer, used by the solver, the decoder and tests.
         */
        internal operator fun invoke(
            headingOffset: Double,
            deviceCorrection: Vector3,
            points: List<AlignmentPoint>,
            residuals: List<Double>,
            pointingUncertainty: Double?,
            calibratedMount: MountProfile?,
        ): AlignmentModel = AlignmentModel(
            AstroMath.normalizeSignedDegrees(headingOffset), deviceCorrection, points.toList(), residuals.toList(),
            pointingUncertainty, calibratedMount,
        )

        /**
         * Fits a model to [points] (the newest [maximumPointCount] are used).
         *
         * @throws AlignmentError When a point has non-finite values, a boresight or target too
         *   close to the zenith or nadir, or the fit needs an implausibly large mount correction.
         */
        @Throws(AlignmentError::class)
        fun solve(points: List<AlignmentPoint>, mount: MountProfile): AlignmentModel {
            val kept = points.takeLast(maximumPointCount)
            if (kept.isEmpty()) return identity
            for (point in kept) validate(point, mount)
            val fit = AlignmentSolver.fit(kept, mount)
            val correction = fit.deviceCorrection.length
            if (!(correction <= maximumDeviceCorrection)) throw AlignmentError.ImplausibleCorrection(correction)
            return AlignmentModel(
                headingOffset = fit.headingOffset, deviceCorrection = fit.deviceCorrection, points = kept,
                residuals = fit.residuals, pointingUncertainty = fit.uncertainty, calibratedMount = mount,
            )
        }

        private fun validate(point: AlignmentPoint, mount: MountProfile) {
            val target = point.target
            if (!(target.azimuth.isFinite() && target.altitude.isFinite() && point.attitude.matrix.isFinite)) {
                throw AlignmentError.NonFiniteInput
            }
            if (!(abs(target.altitude) <= maximumAltitude)) throw AlignmentError.TargetTooHigh(target.altitude)
            val boresight = HorizontalCoordinate(point.attitude.deviceToWorld * mount.cameraAxis)
            if (!(abs(boresight.altitude) <= maximumAltitude)) throw AlignmentError.BoresightTooHigh(boresight.altitude)
        }
    }
}

// MARK: JSON

/**
 * Serializes an [AlignmentPoint] as a JSON object with the keys `attitude`, `target`, `timestamp`
 * (seconds since 2001-01-01T00:00:00Z) and, when there is one, `name`. Decoding ignores unknown keys and
 * rejects a target outside azimuth [0°, 360°) and altitude [−90°, 90°].
 */
internal object AlignmentPointSerializer : KSerializer<AlignmentPoint> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("net.kuecker.lodestar.kit.pointing.AlignmentPoint") {
            element("attitude", DeviceAttitudeSerializer.descriptor)
            element("target", HorizontalCoordinate.serializer().descriptor)
            element<Double>("timestamp")
            element<String>("name", isOptional = true)
        }

    override fun serialize(encoder: Encoder, value: AlignmentPoint) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, DeviceAttitudeSerializer, value.attitude)
            encodeSerializableElement(descriptor, 1, HorizontalCoordinate.serializer(), value.target)
            // Seconds since 2001-01-01T00:00:00Z, which is how Swift's `JSONEncoder` writes a `Date`.
            encodeDoubleElement(descriptor, 2, value.timestamp.timeIntervalSinceReferenceDate)
            // A missing name is left out rather than written as null.
            value.name?.let { encodeStringElement(descriptor, 3, it) }
        }
    }

    override fun deserialize(decoder: Decoder): AlignmentPoint = decode(decoder.decodeJsonTree("alignment point"))

    /** Decodes and validates an alignment point from its JSON representation. */
    fun decode(element: JsonElement): AlignmentPoint {
        val fields = element.toJsonObject("an alignment point")
        val target = fields.requiredField("target").toJsonObject("target")
        val azimuth = target.requiredField("azimuth").toStrictDouble("target.azimuth")
        val altitude = target.requiredField("altitude").toStrictDouble("target.altitude")
        if (!(azimuth >= 0.0 && azimuth < 360.0 && altitude >= -90.0 && altitude <= 90.0)) {
            throw SerializationException("The target azimuth or altitude is out of range.")
        }
        val attitude = DeviceAttitudeSerializer.decode(fields.requiredField("attitude"))
        val seconds = fields.requiredField("timestamp").toStrictDouble("timestamp")
        val name = fields.optionalField("name")?.toStrictString("name")
        // In range, so the normalising factory leaves both numbers as they were decoded.
        val timestamp = instantFromTimeIntervalSinceReferenceDate(seconds)
        return AlignmentPoint(attitude, HorizontalCoordinate(azimuth, altitude), timestamp, name)
    }
}

/**
 * Serializes an [AlignmentModel] as a JSON object with the keys `headingOffset`, `deviceCorrection`,
 * `points`, `residuals` and, when present, `pointingUncertainty` and `calibratedMount`. Decoding ignores
 * unknown keys and throws a [SerializationException] for a missing key, a value of the wrong type, a
 * value out of range or an inconsistent combination of values (see [AlignmentModel]).
 */
internal object AlignmentModelSerializer : KSerializer<AlignmentModel> {
    private val pointsSerializer = ListSerializer(AlignmentPointSerializer)
    private val residualsSerializer = ListSerializer(Double.serializer())

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("net.kuecker.lodestar.kit.pointing.AlignmentModel") {
            element<Double>("headingOffset")
            element("deviceCorrection", Vector3.serializer().descriptor)
            element("points", pointsSerializer.descriptor)
            element("residuals", residualsSerializer.descriptor)
            element<Double>("pointingUncertainty", isOptional = true)
            element("calibratedMount", MountProfileSerializer.descriptor, isOptional = true)
        }

    override fun serialize(encoder: Encoder, value: AlignmentModel) {
        encoder.encodeStructure(descriptor) {
            encodeDoubleElement(descriptor, 0, value.headingOffset)
            encodeSerializableElement(descriptor, 1, Vector3.serializer(), value.deviceCorrection)
            encodeSerializableElement(descriptor, 2, pointsSerializer, value.points)
            encodeSerializableElement(descriptor, 3, residualsSerializer, value.residuals)
            // An unknown uncertainty and an unknown mount are left out rather than written as null.
            value.pointingUncertainty?.let { encodeDoubleElement(descriptor, 4, it) }
            value.calibratedMount?.let { encodeSerializableElement(descriptor, 5, MountProfileSerializer, it) }
        }
    }

    override fun deserialize(decoder: Decoder): AlignmentModel = decode(decoder.decodeJsonTree("alignment model"))

    /** Decodes and validates an alignment model from its JSON representation. */
    fun decode(element: JsonElement): AlignmentModel {
        val fields = element.toJsonObject("an alignment model")
        val heading = fields.requiredField("headingOffset").toStrictDouble("headingOffset")
        val correction = fields.requiredField("deviceCorrection").toVector3("deviceCorrection")
        val points = fields.requiredField("points").toJsonArray("points").map { AlignmentPointSerializer.decode(it) }
        val residuals = fields.requiredField("residuals").toJsonArray("residuals").map { it.toStrictDouble("residuals") }
        val uncertainty = fields.optionalField("pointingUncertainty")?.toStrictDouble("pointingUncertainty")
        val mount = fields.optionalField("calibratedMount")?.let { MountProfileSerializer.decode(it) }
        val isConsistent = heading.isFinite() && abs(heading) <= 180 &&
            correction.isFinite && correction.length <= AlignmentModel.maximumDeviceCorrection &&
            points.size <= AlignmentModel.maximumPointCount && residuals.size == points.size &&
            residuals.all { it.isFinite() && it >= 0 } &&
            (uncertainty == null || (uncertainty.isFinite() && uncertainty >= 0)) &&
            (uncertainty == null) == points.isEmpty()
        if (!isConsistent) throw SerializationException("The alignment contains out-of-range or inconsistent values.")
        return AlignmentModel(heading, correction, points, residuals, uncertainty, mount)
    }
}
