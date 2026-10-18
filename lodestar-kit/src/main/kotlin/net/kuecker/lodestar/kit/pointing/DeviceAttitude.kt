package net.kuecker.lodestar.kit.pointing

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonElement
import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs

/**
 * The orientation of the phone as reported by the motion sensors, together with the reference frame
 * the numbers are expressed in.
 *
 * ## Frames
 *
 * * **Device frame**: x towards the right edge of the phone held in portrait, y towards its top edge, z
 *   out of the screen towards the user. The back camera looks along −z.
 * * **Reference frame**: the rotation matrix `M` of a CoreMotion `CMAttitude` satisfies
 *   `v_device = M · v_reference`, so the rows of `M` are the device axes written in the reference
 *   frame. For `.xTrueNorthZVertical` the reference frame is x = north, y = west, z = up ("NWU");
 *   `.xMagneticNorthZVertical` is the same with x = magnetic north; `.xArbitraryZVertical` has
 *   z = up and a horizontal x axis of unknown heading. Android sensor data is converted to the same
 *   convention before it gets here.
 * * **World frame**: local East–North–Up (ENU), as everywhere else in LodestarKit.
 *
 * The reference frame is described by [referenceAzimuth], the true azimuth of its x axis: 0° for
 * true north, the magnetic declination for magnetic north, and the heading found by
 * [ReferenceFrames] for an arbitrary frame. Together with the matrix this gives
 * `worldToDevice = M · T(ψ)` where `T(ψ)` rotates ENU coordinates into the reference frame.
 *
 * A sensor sample is converted row by row:
 *
 *     val attitude = DeviceAttitude.create(
 *         matrix = Matrix3(Vector3(m11, m12, m13), Vector3(m21, m22, m23), Vector3(m31, m32, m33)),
 *         referenceAzimuth = 0.0,   // .xTrueNorthZVertical
 *         timestamp = seconds)
 *
 * The constructor is private; create values with [create]. The JSON form has the keys `matrix`,
 * `referenceAzimuth` and `timestamp`, as in the Swift original, and decoding applies the checks of [create].
 */
@Serializable(with = DeviceAttitudeSerializer::class)
@ConsistentCopyVisibility
data class DeviceAttitude private constructor(
    /** The CoreMotion rotation matrix, `v_device = matrix · v_reference`. */
    val matrix: Matrix3,
    /** True azimuth of the reference frame's x axis in degrees, (−180, 180]. */
    val referenceAzimuth: Double,
    /**
     * Sample time in seconds (a monotonic clock); used by [AttitudeSmoother], which needs it to
     * increase from sample to sample.
     */
    val timestamp: Double,
) {
    /** Rotation from ENU world coordinates to device coordinates. */
    val worldToDevice: Matrix3
        get() = matrix * referenceTransform(referenceAzimuth)

    /** Rotation from device coordinates to ENU world coordinates. */
    val deviceToWorld: Matrix3
        get() = worldToDevice.transposed

    /**
     * The same physical orientation interpreted with the reference frame's x axis [degrees]
     * further clockwise (east) — the compass correction. A world azimuth computed from the result
     * equals the uncorrected azimuth plus [degrees]. A non-finite offset is ignored.
     */
    fun withHeadingOffset(degrees: Double): DeviceAttitude {
        if (!degrees.isFinite() || degrees == 0.0) return this
        return fromOrthonormal(matrix, referenceAzimuth + degrees, timestamp)
    }

    companion object {
        /** The direction the phone's own back camera looks along, in device coordinates. */
        val backCameraAxis: Vector3 = Vector3(0.0, 0.0, -1.0)

        /** Largest deviation from orthonormality that is accepted (and cleaned up) in a sensor matrix. */
        private const val orthonormalityTolerance = 0.05

        /** Matrices this close to a rotation are used as they are, so a stored attitude reads back unchanged. */
        private const val exactnessTolerance = 1e-12

        /**
         * Creates an attitude from a sensor matrix (Swift `init?(matrix:referenceAzimuth:timestamp:)`).
         * The matrix is orthonormalised; null is returned when any input is not finite or the matrix is
         * not close to a proper rotation. [referenceAzimuth] is normalised to (−180, 180].
         */
        fun create(matrix: Matrix3, referenceAzimuth: Double = 0.0, timestamp: Double = 0.0): DeviceAttitude? {
            if (!(matrix.isFinite && referenceAzimuth.isFinite() && timestamp.isFinite() &&
                    isRotation(matrix, orthonormalityTolerance))
            ) {
                return null
            }
            val cleaned = if (isRotation(matrix, exactnessTolerance)) matrix else matrix.orthonormalized()
            return fromOrthonormal(cleaned, referenceAzimuth, timestamp)
        }

        /** Creates an attitude from a matrix that is already a proper rotation (Swift `init(orthonormal:…)`). */
        internal fun fromOrthonormal(matrix: Matrix3, referenceAzimuth: Double, timestamp: Double): DeviceAttitude =
            DeviceAttitude(matrix, AstroMath.normalizeSignedDegrees(referenceAzimuth), timestamp)

        /**
         * `T(ψ)`: converts ENU coordinates to coordinates in a reference frame that is vertical and
         * whose x axis has true azimuth [azimuth]. At 0° this is the NWU frame of
         * `.xTrueNorthZVertical`; the y axis is 90° counter-clockwise (seen from above) of x.
         */
        fun referenceTransform(azimuth: Double): Matrix3 {
            val s = AstroMath.sinDeg(azimuth)
            val c = AstroMath.cosDeg(azimuth)
            return Matrix3(Vector3(s, c, 0.0), Vector3(-c, s, 0.0), Vector3(0.0, 0.0, 1.0))
        }

        private fun isRotation(m: Matrix3, tolerance: Double): Boolean =
            abs(m.r0.lengthSquared - 1) <= tolerance &&
                abs(m.r1.lengthSquared - 1) <= tolerance &&
                abs(m.r2.lengthSquared - 1) <= tolerance &&
                abs(m.r0.dot(m.r1)) <= tolerance &&
                abs(m.r0.dot(m.r2)) <= tolerance &&
                abs(m.r1.dot(m.r2)) <= tolerance &&
                m.determinant > 0
    }
}

/**
 * Serializes a [DeviceAttitude] as a JSON object with the keys `matrix`, `referenceAzimuth` and
 * `timestamp`. Decoding reads the object itself, so unknown keys are ignored (like Swift's
 * `JSONDecoder`); a missing key, a value of the wrong type or a matrix that is not a rotation throws a
 * [SerializationException].
 */
internal object DeviceAttitudeSerializer : KSerializer<DeviceAttitude> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("net.kuecker.lodestar.kit.pointing.DeviceAttitude") {
            element("matrix", Matrix3.serializer().descriptor)
            element<Double>("referenceAzimuth")
            element<Double>("timestamp")
        }

    override fun serialize(encoder: Encoder, value: DeviceAttitude) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, Matrix3.serializer(), value.matrix)
            encodeDoubleElement(descriptor, 1, value.referenceAzimuth)
            encodeDoubleElement(descriptor, 2, value.timestamp)
        }
    }

    override fun deserialize(decoder: Decoder): DeviceAttitude = decode(decoder.decodeJsonTree("device attitude"))

    /** Decodes and validates an attitude from its JSON representation. */
    fun decode(element: JsonElement): DeviceAttitude {
        val fields = element.toJsonObject("a device attitude")
        val matrix = fields.requiredField("matrix").toMatrix3("matrix")
        val azimuth = fields.requiredField("referenceAzimuth").toStrictDouble("referenceAzimuth")
        val timestamp = fields.requiredField("timestamp").toStrictDouble("timestamp")
        return DeviceAttitude.create(matrix, azimuth, timestamp)
            ?: throw SerializationException("The attitude is not finite or its matrix is not a rotation.")
    }
}
