package net.kuecker.lodestar.kit.phototools

import net.kuecker.lodestar.kit.foundation.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The border of the picture a camera takes, as directions in the camera's own frame, so that an app can draw any
 * frame (a rectangle, a curved fisheye frame, a circle) as a polygon on the sky.
 *
 * The camera frame has x to the right of the picture, y towards its top and z along the optical axis, towards
 * the scene. A world direction follows from a pointing as `right · x + up · y + forward · z`, with the right, up and
 * boresight vectors of the pointing solution. The picture is for a camera held in landscape orientation; turn the
 * camera's right and up vectors for portrait.
 *
 * The border of the sensor is mapped through the inverse of the lens's [LensProjection], so a straight sensor edge
 * becomes a curve for a fisheye lens, and directions can lie behind the sensor's plane (negative z) when the lens
 * sees more than 90° from the axis. A lens with an image circle smaller than the sensor frames that circle, or what
 * is left of it inside the sensor.
 *
 * Cost: one pass over `4 · samplesPerEdge` points, each with a few trigonometric calls and one [Vector3]. That is
 * small, but not free; compute the outline when the lens setup changes, keep it, and turn it into world directions
 * each frame.
 */
object CameraFrameOutline {

    /** The most samples per edge that are used; larger requests are limited to this. */
    const val maximumSamplesPerEdge = 1000

    /**
     * Unit vectors along the border of the frame of [setup], clockwise as seen by the photographer, in the camera
     * frame.
     *
     * A rectangular frame starts at the top-left corner: top-left, top-right, bottom-right, bottom-left, with
     * [samplesPerEdge] points per edge (each edge starts at its corner and excludes the next one), so the result has
     * `4 · samplesPerEdge` points. A circular frame (an image circle that fits inside the sensor) has as many points,
     * evenly spaced, starting at the top-left, 45° anticlockwise from the top. When the image circle only cuts the
     * sensor's corners, the points of the rectangular outline are moved onto the circle.
     *
     * @param setup The camera and lens.
     * @param samplesPerEdge The points per edge, at least 1 and limited to [maximumSamplesPerEdge].
     * @return The directions, or an empty list if [samplesPerEdge] is less than 1.
     */
    fun directions(setup: LensSetup, samplesPerEdge: Int): List<Vector3> {
        if (samplesPerEdge < 1) return emptyList()
        val samples = min(samplesPerEdge, maximumSamplesPerEdge)
        val halfWidth = setup.sensor.widthMillimeters / 2.0
        val halfHeight = setup.sensor.heightMillimeters / 2.0
        val radius = setup.imageCircleDiameterMillimeters?.let { it / 2.0 }
        val focalLength = setup.focalLengthMillimeters
        val projection = setup.projection
        val outline = ArrayList<Vector3>(4 * samples)

        if (radius != null && radius <= min(halfWidth, halfHeight)) {
            val count = 4 * samples
            for (index in 0 until count) {
                val turn = -PI / 4.0 + 2.0 * PI * index.toDouble() / count.toDouble()
                outline.add(direction(radius * sin(turn), radius * cos(turn), focalLength, projection))
            }
            return outline
        }

        val cornerRight = doubleArrayOf(-1.0, 1.0, 1.0, -1.0)
        val cornerUp = doubleArrayOf(1.0, 1.0, -1.0, -1.0)
        // A circle that does not reach the corners of the sensor trims them.
        val trimRadius = radius?.takeIf { it < hypot(halfWidth, halfHeight) }
        for (edge in 0 until 4) {
            val next = (edge + 1) % 4
            for (step in 0 until samples) {
                val t = step.toDouble() / samples.toDouble()
                var x = (cornerRight[edge] + (cornerRight[next] - cornerRight[edge]) * t) * halfWidth
                var y = (cornerUp[edge] + (cornerUp[next] - cornerUp[edge]) * t) * halfHeight
                if (trimRadius != null) {
                    val distance = hypot(x, y)
                    if (distance > trimRadius) {
                        x *= trimRadius / distance
                        y *= trimRadius / distance
                    }
                }
                outline.add(direction(x, y, focalLength, projection))
            }
        }
        return outline
    }

    /**
     * The unit vector, in the camera frame, towards the middle of the top edge of the frame: where a mark that shows
     * which way is up belongs. For a circular frame this is the top of the circle.
     */
    fun topMiddle(setup: LensSetup): Vector3 {
        val halfHeight = framedExtent(setup.sensor.heightMillimeters, setup.imageCircleDiameterMillimeters) / 2.0
        return direction(0.0, halfHeight, setup.focalLengthMillimeters, setup.projection)
    }

    /**
     * The direction of the ray that lands at ([x], [y]) millimetres from the centre of the frame (x to the right, y up)
     * behind a lens of [focalLength] millimetres.
     */
    private fun direction(x: Double, y: Double, focalLength: Double, projection: LensProjection): Vector3 {
        if (projection == LensProjection.RECTILINEAR) {
            return Vector3(x / focalLength, y / focalLength, 1.0).normalized
        }
        val distance = hypot(x, y)
        if (!(distance > 0.0)) return Vector3(0.0, 0.0, 1.0)
        val angle = projection.angleRadians(distance, focalLength)
        val scale = sin(angle) / distance
        return Vector3(x * scale, y * scale, cos(angle))
    }
}
