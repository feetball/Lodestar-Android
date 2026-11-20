package net.kuecker.lodestar.kit.phototools

import kotlin.math.min

/**
 * Field-of-view calculations for a lens on a given sensor.
 *
 * The angle across a sensor dimension `d` is `2 · θ(d / 2)`, where `θ(r)` is the inverse of the lens's
 * [LensProjection]: for a rectilinear lens of focal length `f` that is `2 · atan(d / 2f)`, and a fisheye
 * mapping is inverted the same way. No angle exceeds 180°. The horizontal and vertical angles refer to the
 * sensor's width and height, that is, a camera held in landscape orientation; use [FrameSize.rotated] for
 * portrait.
 *
 * A circular fisheye whose image circle is smaller than the sensor only fills that circle: every extent is
 * limited to the circle's diameter (`imageCircleDiameterMillimeters`), so a circle that fits inside the
 * frame gives the same angle in all three directions.
 *
 * The functions accept any positive focal length. A focal length that is not a positive finite
 * number is replaced by [LensSetup.defaultFocalLengthMillimeters], so the result is always a
 * valid angle. An image circle that is not a positive finite number is ignored.
 */
object FieldOfView {

    /** The horizontal field of view, in degrees, across the sensor width. */
    fun horizontal(
        sensor: SensorFormat,
        focalLength: Double,
        projection: LensProjection = LensProjection.RECTILINEAR,
        imageCircleDiameterMillimeters: Double? = null,
    ): Double = angleDegrees(
        framedExtent(sensor.widthMillimeters, imageCircleDiameterMillimeters), usable(focalLength), projection,
    )

    /** The vertical field of view, in degrees, across the sensor height. */
    fun vertical(
        sensor: SensorFormat,
        focalLength: Double,
        projection: LensProjection = LensProjection.RECTILINEAR,
        imageCircleDiameterMillimeters: Double? = null,
    ): Double = angleDegrees(
        framedExtent(sensor.heightMillimeters, imageCircleDiameterMillimeters), usable(focalLength), projection,
    )

    /** The diagonal field of view, in degrees, corner to corner. */
    fun diagonal(
        sensor: SensorFormat,
        focalLength: Double,
        projection: LensProjection = LensProjection.RECTILINEAR,
        imageCircleDiameterMillimeters: Double? = null,
    ): Double = angleDegrees(
        framedExtent(sensor.diagonalMillimeters, imageCircleDiameterMillimeters), usable(focalLength), projection,
    )

    /** The horizontal, vertical and diagonal fields of view at once. */
    fun of(
        sensor: SensorFormat,
        focalLength: Double,
        projection: LensProjection = LensProjection.RECTILINEAR,
        imageCircleDiameterMillimeters: Double? = null,
    ): FrameSize = FrameSize(sensor, usable(focalLength), projection, imageCircleDiameterMillimeters)

    private fun usable(focalLength: Double): Double =
        if (focalLength.isFinite() && focalLength > 0.0) focalLength else LensSetup.defaultFocalLengthMillimeters
}

/**
 * The part of a sensor extent that the lens images: [extent] limited to the image circle's diameter, when there is
 * a usable one (a positive finite number).
 */
internal fun framedExtent(extent: Double, imageCircleDiameterMillimeters: Double?): Double {
    if (imageCircleDiameterMillimeters == null || !imageCircleDiameterMillimeters.isFinite() ||
        !(imageCircleDiameterMillimeters > 0.0)
    ) {
        return extent
    }
    return min(extent, imageCircleDiameterMillimeters)
}

/**
 * The angle across [extent] in degrees, at most 180: `2 · θ(extent / 2)` for the projection's inverse mapping `θ`.
 * The caller guarantees a positive, finite focal length.
 */
private fun angleDegrees(extent: Double, focalLength: Double, projection: LensProjection): Double =
    min(180.0, 2.0 * projection.angleDegrees(extent / 2.0, focalLength))

// Swift's `min` and `max` pick the first argument when a comparison with the second is false, so
// they differ from `kotlin.math.min` and `max` for NaN and for signed zeros. `FrameSize` can hold
// any value its public initializer was given, so it uses Swift's rules.

/** Swift's `max(x, y)`: `y` if `y >= x`, otherwise `x`. */
private fun swiftMax(x: Double, y: Double): Double = if (y >= x) y else x

/** Swift's `min(x, y)`: `y` if `y < x`, otherwise `x`. */
private fun swiftMin(x: Double, y: Double): Double = if (y < x) y else x

/**
 * The angular size of the camera frame on the sky, in degrees.
 *
 * @constructor Creates a frame size from angles in degrees.
 */
data class FrameSize(
    /** The angle across the frame's width, in degrees. */
    val horizontal: Double,
    /** The angle across the frame's height, in degrees. */
    val vertical: Double,
    /** The angle across the frame's diagonal, in degrees. */
    val diagonal: Double,
) {
    /**
     * Computes the frame size of a sensor behind a lens. The caller guarantees that
     * [focalLength] is a positive, finite number of millimetres.
     */
    internal constructor(
        sensor: SensorFormat,
        focalLength: Double,
        projection: LensProjection = LensProjection.RECTILINEAR,
        imageCircleDiameterMillimeters: Double? = null,
    ) : this(
        horizontal = angleDegrees(framedExtent(sensor.widthMillimeters, imageCircleDiameterMillimeters), focalLength, projection),
        vertical = angleDegrees(framedExtent(sensor.heightMillimeters, imageCircleDiameterMillimeters), focalLength, projection),
        diagonal = angleDegrees(framedExtent(sensor.diagonalMillimeters, imageCircleDiameterMillimeters), focalLength, projection),
    )

    /** The longer side of the frame, in degrees. */
    val longSide: Double
        get() = swiftMax(horizontal, vertical)

    /** The shorter side of the frame, in degrees. */
    val shortSide: Double
        get() = swiftMin(horizontal, vertical)

    /** The frame of the same camera turned a quarter turn, with width and height exchanged. */
    val rotated: FrameSize
        get() = FrameSize(horizontal = vertical, vertical = horizontal, diagonal = diagonal)

    /**
     * Measures how much of the frame an extended object covers, for a "fits in frame" display.
     *
     * The object is assumed to be turned so that its major axis lies along the frame's long side,
     * which the photographer can arrange by rotating the camera; the fractions therefore describe
     * the best case. Angular sizes are compared directly (a flat-sky approximation), which is
     * accurate for objects well inside the frame.
     *
     * @param objectMajorArcmin The object's larger angular dimension, in arcminutes.
     * @param minorArcmin The object's smaller angular dimension, in arcminutes. The two values may be
     *   given in either order.
     * @return The framing, or null if a dimension is negative or not finite, the larger
     *   dimension is zero, or the frame has no area.
     */
    fun framing(objectMajorArcmin: Double, minorArcmin: Double): ObjectFraming? {
        val isMeasurable = objectMajorArcmin.isFinite() && minorArcmin.isFinite() &&
            objectMajorArcmin >= 0.0 && minorArcmin >= 0.0 &&
            swiftMax(objectMajorArcmin, minorArcmin) > 0.0 &&
            longSide > 0.0 && shortSide > 0.0
        if (!isMeasurable) return null
        val major = swiftMax(objectMajorArcmin, minorArcmin)
        val minor = swiftMin(objectMajorArcmin, minorArcmin)
        return ObjectFraming(
            majorAxisFraction = major / (longSide * 60.0),
            minorAxisFraction = minor / (shortSide * 60.0),
        )
    }
}

/** How much of the camera frame an extended object covers. */
@ConsistentCopyVisibility
data class ObjectFraming internal constructor(
    /** The object's major axis divided by the frame's long side; 1.0 spans the whole long side. */
    val majorAxisFraction: Double,
    /** The object's minor axis divided by the frame's short side; 1.0 spans the whole short side. */
    val minorAxisFraction: Double,
) {
    /**
     * The larger of the two fractions: how full the frame is along the object's tightest
     * dimension. 0.25 means the object fills a quarter of the frame, and anything above 1.0
     * does not fit.
     */
    val fillFraction: Double
        get() = swiftMax(majorAxisFraction, minorAxisFraction)

    /** Whether the object fits inside the frame when oriented along it. */
    val fitsInFrame: Boolean
        get() = fillFraction <= 1.0
}
