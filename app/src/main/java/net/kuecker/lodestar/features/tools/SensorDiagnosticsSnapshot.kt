package net.kuecker.lodestar.features.tools

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Matrix3
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.geomagnetism.CompassReliability
import net.kuecker.lodestar.kit.geomagnetism.WorldMagneticModel
import net.kuecker.lodestar.kit.pointing.MountProfile
import net.kuecker.lodestar.kit.pointing.PointingSolution
import net.kuecker.lodestar.services.MotionSample
import java.time.Instant
import kotlin.math.abs

/**
 * What the sensor diagnostics screen shows for one motion sample.
 *
 * The formulas are the self-checks of the attitude convention: the matrix `M` satisfies `v_device = M · v_reference`
 * with reference axes x = north, y = west, z = up, and device axes x = right edge, y = top edge, z = out of the
 * screen; the back camera looks along −z. Indices below are one-based, like the `m11 … m33` of the matrix.
 */
internal class SensorDiagnosticsSnapshot(sample: MotionSample) {
    companion object {
        /** A hypothesis about the matrix is accepted when its gravity prediction is this close (in g) to gravity. */
        const val GRAVITY_TOLERANCE = 0.05

        /** Poses whose two gravity predictions differ by less than this (in g) cannot tell the hypotheses apart. */
        const val POSE_DISCRIMINATION = 0.1

        /** The phone counts as flat when gravity is within this many degrees of straight through the screen. */
        const val FLAT_TOLERANCE = 10.0

        /** The boresight from the matrix and from LodestarKit agree when they differ by less than this many degrees. */
        const val BORESIGHT_TOLERANCE = 0.1
    }

    enum class GravityVerdict {
        /** The matrix maps reference vectors to device vectors, as assumed. */
        PASS,

        /** Gravity does not match the assumed mapping. */
        FAIL,

        /** Both mappings predict the same gravity in this pose; tilt the phone and look again. */
        INCONCLUSIVE,
    }

    /** The raw attitude matrix `M`. */
    val matrix: Matrix3 = sample.rawAttitude.matrix

    /** True azimuth of the reference frame's x axis: the declination for magnetic north, 0 for true north. */
    val referenceAzimuth: Double = sample.rawAttitude.referenceAzimuth

    /** Gravity in device coordinates, unit g. */
    val gravity: Vector3 = sample.gravity

    /** Rotation speed in degrees per second. */
    val angularSpeed: Double = sample.angularSpeed

    /** Magnitude of the calibrated magnetic field in microtesla, when the frame provides one. */
    val magneticFieldMicrotesla: Double? = sample.magneticFieldMicrotesla

    /** The back camera's direction by the formula, with the azimuth measured from the reference frame's x axis. */
    val boresightInFrame: HorizontalCoordinate = HorizontalCoordinate(
        // east = m32, north = −m31, up = −m33.
        azimuth = AstroMath.atan2Deg(matrix[2, 1], -matrix[2, 0]),
        altitude = AstroMath.asinDeg(-matrix[2, 2]),
    )

    /** The same direction with the reference azimuth added: azimuth from true north. */
    val boresight: HorizontalCoordinate = HorizontalCoordinate(
        azimuth = boresightInFrame.azimuth + referenceAzimuth,
        altitude = boresightInFrame.altitude,
    )

    /** Angle in degrees between [boresight] and what `PointingSolution` reports for the back-camera mount. */
    val boresightDisagreement: Double = boresight.angularSeparation(
        PointingSolution.solve(sample.rawAttitude, MountProfile.backCameraPortrait).boresight,
    )

    // Gravity: the third column of M if M maps reference to device, the third row if it maps device to reference.
    private val ifReferenceToDevice = Vector3(-matrix[0, 2], -matrix[1, 2], -matrix[2, 2])
    private val ifDeviceToReference = Vector3(-matrix[2, 0], -matrix[2, 1], -matrix[2, 2])

    /** Distance in g between gravity and `(−m13, −m23, −m33)`, the prediction if `M` maps reference to device. */
    val referenceToDeviceResidual: Double = (ifReferenceToDevice - gravity).length

    /** Distance in g between gravity and `(−m31, −m32, −m33)`, the prediction if `M` maps device to reference. */
    val deviceToReferenceResidual: Double = (ifDeviceToReference - gravity).length

    val gravityVerdict: GravityVerdict = when {
        (ifReferenceToDevice - ifDeviceToReference).length < POSE_DISCRIMINATION -> GravityVerdict.INCONCLUSIVE
        referenceToDeviceResidual < GRAVITY_TOLERANCE -> GravityVerdict.PASS
        else -> GravityVerdict.FAIL
    }

    /**
     * Compass heading of the phone's top edge, from the reference frame's x axis; meaningful with the phone flat.
     * Row 2 of `M` is the device y axis in the reference frame: east = −m22, north = m21.
     */
    val topEdgeHeadingInFrame: Double = AstroMath.normalizeDegrees(AstroMath.atan2Deg(-matrix[1, 1], matrix[1, 0]))

    /** The same heading from true north. */
    val topEdgeHeading: Double = AstroMath.normalizeDegrees(topEdgeHeadingInFrame + referenceAzimuth)

    /** Angle in degrees between the phone lying flat, screen up, and its present pose. */
    val tiltFromFlat: Double = AstroMath.acosDeg(-gravity.z)

    /** Whether the top-edge heading can be compared with a compass: the phone lies roughly flat, screen up. */
    val isFlat: Boolean get() = tiltFromFlat <= FLAT_TOLERANCE

    /** Whether the matrix-based and the LodestarKit boresights agree. */
    val boresightsAgree: Boolean get() = boresightDisagreement < BORESIGHT_TOLERANCE
}

/** The World Magnetic Model's values at the observer, to compare with the magnetometer. */
internal class SensorDiagnosticsMagneticModel private constructor(
    /** Degrees, east positive. */
    val declination: Double,
    /** Degrees, positive downwards. */
    val inclination: Double,
    /** Microtesla. */
    val expectedFieldMicrotesla: Double,
    /** Microtesla. */
    val horizontalFieldMicrotesla: Double,
    val reliability: CompassReliability,
    /** Whether the date lies within the years the model is valid for. */
    val isCurrent: Boolean,
) {
    /** How far a measured field is from the expected one, as a fraction of the expected one. */
    fun deviation(ofMeasured: Double): Double = abs(ofMeasured - expectedFieldMicrotesla) / expectedFieldMicrotesla

    companion object {
        /** The model's values at [location] on [date]; `null` when the model gives no valid field for the position. */
        fun create(location: GeographicLocation, date: Instant): SensorDiagnosticsMagneticModel? {
            val year = WorldMagneticModel.decimalYear(date)
            val model = WorldMagneticModel.wmm2025
            val field = model.field(location.latitude, location.longitude, location.elevation, year)
            if (!field.isValid) return null
            return SensorDiagnosticsMagneticModel(
                declination = field.declination,
                inclination = field.inclination,
                expectedFieldMicrotesla = field.totalIntensity / 1000,
                horizontalFieldMicrotesla = field.horizontalIntensity / 1000,
                reliability = field.compassReliability,
                isCurrent = model.isWithinValidity(year),
            )
        }
    }
}
