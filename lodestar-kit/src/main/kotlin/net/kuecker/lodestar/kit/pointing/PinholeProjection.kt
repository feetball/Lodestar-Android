package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.atan
import kotlin.math.sqrt

/**
 * A position in the phone's viewport, in UIKit points (Android: pixels or dp, whatever unit the
 * viewport size was given in).
 *
 * Created by [PinholeProjection] only; the constructor is internal, like Swift's memberwise initializer.
 */
@ConsistentCopyVisibility
data class ProjectedPoint internal constructor(
    /** Distance from the left edge of the viewport; grows towards the right. */
    val x: Double,
    /** Distance from the top edge of the viewport; grows downwards. */
    val y: Double,
    /**
     * Cosine of the angle between the direction and the optical axis, in (0, 1]; 1 at the centre of
     * the viewport. Small values are far off-axis (and far outside the viewport).
     */
    val depth: Double,
)

/**
 * A pinhole model of the phone's own back camera, used to draw celestial objects over the live
 * preview. The preview is assumed to fill the viewport of a phone held in portrait: device +x is
 * to the right, device +y up the screen, and the camera looks along device −z.
 *
 * The overlay follows the phone, not the DSLR: the heading offset (compass correction) is applied,
 * the DSLR device correction is not. Pass [AlignmentModel.headingOffset] for the mount as the offset,
 * or 0 when there is no alignment.
 *
 * ## Hot path
 *
 * [project] and [unproject] allocate a few small objects per call, which is fine for a handful of
 * points. To draw thousands of objects per frame use the allocation-free overloads: compute the
 * rotation once per frame with [worldToDevice], then call [projectInto] for each direction (or
 * [projectAllInto] for packed arrays), which write the screen coordinates into a reusable array. They
 * compute exactly the same numbers as [project].
 *
 * The constructor is private; use [create] or [fromLongSideFieldOfView].
 */
@ConsistentCopyVisibility
data class PinholeProjection private constructor(
    /** Viewport width in points. */
    val viewportWidth: Double,
    /** Viewport height in points. */
    val viewportHeight: Double,
    /** Focal length in points. */
    val focalLength: Double,
) {
    /** Field of view along the longer side of the viewport, degrees. */
    val longSideFieldOfView: Double
        get() = fieldOfView(swiftMax(viewportWidth, viewportHeight))

    /** Field of view across the viewport width, degrees. */
    val horizontalFieldOfView: Double
        get() = fieldOfView(viewportWidth)

    /** Field of view across the viewport height, degrees. */
    val verticalFieldOfView: Double
        get() = fieldOfView(viewportHeight)

    /**
     * Where a world direction (any non-zero ENU vector) appears in the viewport. Null when the
     * direction is behind the camera (or not finite). The point may lie outside the viewport; see
     * [contains].
     */
    fun project(worldDirection: Vector3, attitude: DeviceAttitude, headingOffset: Double): ProjectedPoint? =
        project(worldDirection, worldToDevice(attitude, headingOffset))

    /**
     * [project] for many directions at once (the attitude is converted only once). The result has one
     * entry per input direction.
     */
    fun project(worldDirections: List<Vector3>, attitude: DeviceAttitude, headingOffset: Double): List<ProjectedPoint?> {
        val rotation = worldToDevice(attitude, headingOffset)
        val projected = ArrayList<ProjectedPoint?>(worldDirections.size)
        for (direction in worldDirections) projected.add(project(direction, rotation))
        return projected
    }

    /**
     * The world direction (ENU unit vector) seen at a viewport position; the inverse of [project].
     * Null when a coordinate is not finite.
     */
    fun unproject(x: Double, y: Double, attitude: DeviceAttitude, headingOffset: Double): Vector3? {
        if (!(x.isFinite() && y.isFinite())) return null
        val ray = Vector3((x - viewportWidth / 2) / focalLength, -(y - viewportHeight / 2) / focalLength, -1.0)
        return deviceToWorld(attitude, headingOffset) * ray.normalized
    }

    /** `true` when the point lies inside the viewport, grown by [margin] points on every side. */
    fun contains(point: ProjectedPoint, margin: Double = 0.0): Boolean = contains(point.x, point.y, margin)

    /**
     * [contains] for a viewport position given as numbers, e.g. what [projectInto] wrote, without a
     * [ProjectedPoint]. (Kotlin addition.)
     */
    fun contains(x: Double, y: Double, margin: Double = 0.0): Boolean =
        x >= -margin && x <= viewportWidth + margin && y >= -margin && y <= viewportHeight + margin

    // MARK: Allocation-free overloads (Kotlin addition)

    /**
     * Projects the world direction `(x, y, z)` (any non-zero ENU vector) without allocating, and writes
     * the viewport position to `out[outIndex]` (x, grows right) and `out[outIndex + 1]` (y, grows down).
     * [worldToDevice] is the rotation from [worldToDevice] (heading offset included), computed once per
     * frame. Gives the numbers of [project] exactly.
     *
     * @return `true` when the direction is in front of the camera and was written; `false` (and nothing
     *   written) when it is behind the camera, grazes the image plane, is zero or not finite.
     */
    fun projectInto(
        x: Double,
        y: Double,
        z: Double,
        worldToDevice: Matrix3,
        out: DoubleArray,
        outIndex: Int = 0,
    ): Boolean = projectCore(x, y, z, worldToDevice) { screenX, screenY ->
        out[outIndex] = screenX
        out[outIndex + 1] = screenY
    }

    /** [projectInto] for a `FloatArray`, as Android's canvas wants its coordinates. */
    fun projectInto(
        x: Double,
        y: Double,
        z: Double,
        worldToDevice: Matrix3,
        out: FloatArray,
        outIndex: Int = 0,
    ): Boolean = projectCore(x, y, z, worldToDevice) { screenX, screenY ->
        out[outIndex] = screenX.toFloat()
        out[outIndex + 1] = screenY.toFloat()
    }

    /**
     * Projects [count] directions without allocating. [directions] holds them as consecutive `x, y, z`
     * triples (`directions[3 * i]`, …); the viewport position of direction `i` goes to `out[2 * i]` and
     * `out[2 * i + 1]`. Directions that are not in front of the camera get `NaN` coordinates, and
     * `visible[i]` (when [visible] is given) tells which were projected.
     *
     * @return The number of directions that were projected.
     */
    fun projectAllInto(
        directions: DoubleArray,
        count: Int,
        worldToDevice: Matrix3,
        out: DoubleArray,
        visible: BooleanArray? = null,
    ): Int {
        requirePackedArrays(directions.size, count, out.size, visible?.size ?: Int.MAX_VALUE)
        var projected = 0
        for (i in 0 until count) {
            val isVisible = projectCore(
                directions[3 * i], directions[3 * i + 1], directions[3 * i + 2], worldToDevice,
            ) { screenX, screenY ->
                out[2 * i] = screenX
                out[2 * i + 1] = screenY
            }
            if (isVisible) {
                projected += 1
            } else {
                out[2 * i] = Double.NaN
                out[2 * i + 1] = Double.NaN
            }
            if (visible != null) visible[i] = isVisible
        }
        return projected
    }

    /** [projectAllInto] for a `FloatArray`, as Android's canvas wants its coordinates. */
    fun projectAllInto(
        directions: DoubleArray,
        count: Int,
        worldToDevice: Matrix3,
        out: FloatArray,
        visible: BooleanArray? = null,
    ): Int {
        requirePackedArrays(directions.size, count, out.size, visible?.size ?: Int.MAX_VALUE)
        var projected = 0
        for (i in 0 until count) {
            val isVisible = projectCore(
                directions[3 * i], directions[3 * i + 1], directions[3 * i + 2], worldToDevice,
            ) { screenX, screenY ->
                out[2 * i] = screenX.toFloat()
                out[2 * i + 1] = screenY.toFloat()
            }
            if (isVisible) {
                projected += 1
            } else {
                out[2 * i] = Float.NaN
                out[2 * i + 1] = Float.NaN
            }
            if (visible != null) visible[i] = isVisible
        }
        return projected
    }

    /**
     * [unproject] without allocating a result: writes the ENU unit vector seen at the viewport position
     * to `out[outIndex]`, `out[outIndex + 1]`, `out[outIndex + 2]` (x East, y North, z Up).
     * [deviceToWorld] is the rotation from [deviceToWorld] (heading offset included), computed once
     * per frame. Gives the numbers of [unproject] exactly.
     *
     * @return `true` when the vector was written; `false` when a coordinate is not finite.
     */
    fun unprojectInto(x: Double, y: Double, deviceToWorld: Matrix3, out: DoubleArray, outIndex: Int = 0): Boolean {
        if (!(x.isFinite() && y.isFinite())) return false
        val rayX = (x - viewportWidth / 2) / focalLength
        val rayY = -(y - viewportHeight / 2) / focalLength
        val length = sqrt(rayX * rayX + rayY * rayY + 1.0)
        val ux = rayX / length
        val uy = rayY / length
        val uz = -1.0 / length
        val m = deviceToWorld
        out[outIndex] = m.r0.x * ux + m.r0.y * uy + m.r0.z * uz
        out[outIndex + 1] = m.r1.x * ux + m.r1.y * uy + m.r1.z * uz
        out[outIndex + 2] = m.r2.x * ux + m.r2.y * uy + m.r2.z * uz
        return true
    }

    // MARK: Private

    private fun project(direction: Vector3, worldToDevice: Matrix3): ProjectedPoint? {
        if (!direction.isFinite) return null
        val unit = direction.normalized
        if (PointingMath.isZero(unit)) return null
        val device = worldToDevice * unit
        val depth = -device.z
        if (!(depth > minimumDepth)) return null
        return ProjectedPoint(
            x = viewportWidth / 2 + focalLength * device.x / depth,
            y = viewportHeight / 2 - focalLength * device.y / depth,
            depth = depth,
        )
    }

    /**
     * The arithmetic of [project] on plain numbers: calls [write] with the viewport position and returns
     * `true` when the direction is in front of the camera. Inlined into each caller, so the callback costs nothing.
     */
    private inline fun projectCore(
        x: Double,
        y: Double,
        z: Double,
        worldToDevice: Matrix3,
        write: (screenX: Double, screenY: Double) -> Unit,
    ): Boolean {
        if (!(x.isFinite() && y.isFinite() && z.isFinite())) return false
        val length = sqrt(x * x + y * y + z * z)
        if (!(length > 0)) return false
        val ux = x / length
        val uy = y / length
        val uz = z / length
        val m = worldToDevice
        val depth = -(m.r2.x * ux + m.r2.y * uy + m.r2.z * uz)
        if (!(depth > minimumDepth)) return false
        val deviceX = m.r0.x * ux + m.r0.y * uy + m.r0.z * uz
        val deviceY = m.r1.x * ux + m.r1.y * uy + m.r1.z * uz
        write(viewportWidth / 2 + focalLength * deviceX / depth, viewportHeight / 2 - focalLength * deviceY / depth)
        return true
    }

    private fun fieldOfView(length: Double): Double = 2 * AstroMath.degrees(atan(length / 2 / focalLength))

    private fun requirePackedArrays(directionsSize: Int, count: Int, outSize: Int, visibleSize: Int) {
        require(count >= 0 && count <= directionsSize / 3) {
            "count $count does not fit the directions array of size $directionsSize"
        }
        require(outSize >= 2 * count) { "out needs ${2 * count} elements, has $outSize" }
        require(visibleSize >= count) { "visible needs $count elements, has $visibleSize" }
    }

    companion object {
        private const val minimumDepth = 1e-6
        private const val maximumSamplesPerEdge = 1000

        /**
         * A projection from an explicit focal length in points; null unless all values are finite and
         * positive (Swift `init?(viewportWidth:viewportHeight:focalLength:)`).
         */
        fun create(viewportWidth: Double, viewportHeight: Double, focalLength: Double): PinholeProjection? {
            if (!(viewportWidth.isFinite() && viewportWidth > 0 &&
                    viewportHeight.isFinite() && viewportHeight > 0 &&
                    focalLength.isFinite() && focalLength > 0)
            ) {
                return null
            }
            return PinholeProjection(viewportWidth, viewportHeight, focalLength)
        }

        /**
         * A projection whose viewport shows [longSideFieldOfView] degrees along its longer side;
         * null unless the sizes are finite and positive and the field of view is in (0°, 180°). For an
         * aspect-fill camera preview in a viewport that is not wider than the camera image (any tall
         * phone screen) this is the camera's own field of view along its long side (Swift
         * `init?(viewportWidth:viewportHeight:longSideFieldOfView:)`).
         */
        fun fromLongSideFieldOfView(
            viewportWidth: Double,
            viewportHeight: Double,
            longSideFieldOfView: Double,
        ): PinholeProjection? {
            if (!(longSideFieldOfView.isFinite() && longSideFieldOfView > 0 && longSideFieldOfView < 180 &&
                    viewportWidth.isFinite() && viewportHeight.isFinite())
            ) {
                return null
            }
            val focalLength = swiftMax(viewportWidth, viewportHeight) / 2 / AstroMath.tanDeg(longSideFieldOfView / 2)
            return create(viewportWidth, viewportHeight, focalLength)
        }

        /**
         * The rotation from ENU world coordinates to device coordinates for the overlay: the attitude with
         * the compass correction [headingOffset] applied. Compute it once per frame and pass it to
         * [projectInto] and [projectAllInto]. (Kotlin addition.)
         */
        fun worldToDevice(attitude: DeviceAttitude, headingOffset: Double): Matrix3 =
            attitude.withHeadingOffset(headingOffset).worldToDevice

        /**
         * The rotation from device coordinates to ENU world coordinates for the overlay, the inverse of
         * [worldToDevice]. Pass it to [unprojectInto]. (Kotlin addition.)
         */
        fun deviceToWorld(attitude: DeviceAttitude, headingOffset: Double): Matrix3 =
            attitude.withHeadingOffset(headingOffset).deviceToWorld

        /**
         * World directions (ENU unit vectors) along the border of the DSLR's picture, for drawing the
         * framing rectangle on the phone view or a sky chart. The loop runs clockwise as seen by the
         * photographer — top-left, top-right, bottom-right, bottom-left — with [samplesPerEdge] points
         * per edge (each edge starts at its corner and excludes the next one). The picture's width
         * follows `cameraRightVector`, its height `cameraUpVector`; swap the field-of-view values for a
         * camera turned to portrait. Straight picture edges are great-circle arcs, which a rectilinear
         * view such as [PinholeProjection] draws as straight lines.
         *
         * Returns an empty list unless both fields of view are in (0°, 180°) and [samplesPerEdge] ≥ 1.
         */
        fun dslrFrameOutline(
            solution: PointingSolution,
            horizontalFOV: Double,
            verticalFOV: Double,
            samplesPerEdge: Int,
        ): List<Vector3> {
            if (!(horizontalFOV.isFinite() && verticalFOV.isFinite() && samplesPerEdge >= 1 &&
                    horizontalFOV >= 0.0 && horizontalFOV < 180.0 && verticalFOV >= 0.0 && verticalFOV < 180.0 &&
                    horizontalFOV > 0 && verticalFOV > 0)
            ) {
                return emptyList()
            }
            val halfWidth = AstroMath.tanDeg(horizontalFOV / 2)
            val halfHeight = AstroMath.tanDeg(verticalFOV / 2)
            val samples = minOf(samplesPerEdge, maximumSamplesPerEdge)
            val cornerRight = doubleArrayOf(-1.0, 1.0, 1.0, -1.0)
            val cornerUp = doubleArrayOf(1.0, 1.0, -1.0, -1.0)
            val outline = ArrayList<Vector3>(4 * samples)
            for (edge in 0 until 4) {
                val next = (edge + 1) % 4
                for (step in 0 until samples) {
                    val t = step.toDouble() / samples.toDouble()
                    val right = (cornerRight[edge] + (cornerRight[next] - cornerRight[edge]) * t) * halfWidth
                    val up = (cornerUp[edge] + (cornerUp[next] - cornerUp[edge]) * t) * halfHeight
                    val direction = solution.boresightVector + solution.cameraRightVector * right + solution.cameraUpVector * up
                    outline.add(direction.normalized)
                }
            }
            return outline
        }
    }
}
