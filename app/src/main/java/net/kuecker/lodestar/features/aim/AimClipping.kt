package net.kuecker.lodestar.features.aim

import androidx.compose.ui.graphics.Path
import net.kuecker.lodestar.kit.foundation.Matrix3
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.pointing.PinholeProjection
import kotlin.math.max
import kotlin.math.min

/**
 * Sutherland–Hodgman clipping of rings of world directions against planes through the observer.
 *
 * A great-circle arc projects to a straight line in a rectilinear view, and so does the chord between its
 * end points: both lie in the plane through the observer. Clipping the chord polygon with planes through the
 * observer therefore clips the spherical polygon exactly, and the points where it is cut keep the right
 * direction (their length is irrelevant). A ring that spans more than about a hemisphere has closing edges that
 * approach half a circle, which no rectilinear view can represent: cut such rings up first.
 *
 * Rings are packed as x, y, z per vertex. The instance owns the buffers of the result, which is why it is made once
 * and reused for every ring of every frame (main thread only).
 */
internal class AimPolygonClip {
    private var first = DoubleArray(3 * INITIAL_CAPACITY)
    private var second = DoubleArray(3 * INITIAL_CAPACITY)

    /** The vertices of the latest [clip], packed; only the first `3 · count` values are meaningful. */
    var result: DoubleArray = first
        private set

    /**
     * Clips the first [count] vertices of [ring] to the part where `normal · v ≥ 0` for every one of the [planes]
     * (inward normals packed as x, y, z).
     *
     * @return The number of vertices left, in [result]; 0 when nothing remains.
     */
    fun clip(ring: DoubleArray, count: Int, planes: DoubleArray): Int {
        var input = ring
        var inputCount = count
        var writeFirst = true
        for (plane in 0 until planes.size / 3) {
            if (inputCount == 0) return 0
            val nx = planes[3 * plane]
            val ny = planes[3 * plane + 1]
            val nz = planes[3 * plane + 2]
            val needed = 3 * (2 * inputCount)
            if (writeFirst) {
                if (first.size < needed) first = DoubleArray(needed)
            } else {
                if (second.size < needed) second = DoubleArray(needed)
            }
            val output = if (writeFirst) first else second
            var outputCount = 0
            var previousX = input[3 * (inputCount - 1)]
            var previousY = input[3 * (inputCount - 1) + 1]
            var previousZ = input[3 * (inputCount - 1) + 2]
            var previousDistance = nx * previousX + ny * previousY + nz * previousZ
            for (index in 0 until inputCount) {
                val x = input[3 * index]
                val y = input[3 * index + 1]
                val z = input[3 * index + 2]
                val distance = nx * x + ny * y + nz * z
                if (distance >= 0) {
                    if (previousDistance < 0) {
                        outputCount = putCrossing(output, outputCount, previousX, previousY, previousZ, x, y, z, previousDistance, distance)
                    }
                    output[3 * outputCount] = x
                    output[3 * outputCount + 1] = y
                    output[3 * outputCount + 2] = z
                    outputCount += 1
                } else if (previousDistance >= 0) {
                    outputCount = putCrossing(output, outputCount, previousX, previousY, previousZ, x, y, z, previousDistance, distance)
                }
                previousX = x
                previousY = y
                previousZ = z
                previousDistance = distance
            }
            input = output
            inputCount = outputCount
            writeFirst = !writeFirst
        }
        result = input
        return inputCount
    }

    /** Appends the point of the chord from `a` to `b` where the signed distances `da` and `db` change sign. */
    private fun putCrossing(
        output: DoubleArray, at: Int,
        ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, da: Double, db: Double,
    ): Int {
        val t = da / (da - db)
        output[3 * at] = ax + (bx - ax) * t
        output[3 * at + 1] = ay + (by - ay) * t
        output[3 * at + 2] = az + (bz - az) * t
        return at + 1
    }

    private companion object {
        const val INITIAL_CAPACITY = 512
    }
}

/** A line segment cut to the viewport. [startClipped] and [endClipped] tell whether an end was moved. */
internal class AimClippedSegment {
    var startX = 0.0
    var startY = 0.0
    var startZ = 0.0
    var endX = 0.0
    var endY = 0.0
    var endZ = 0.0
    var startClipped = false
    var endClipped = false
}

/**
 * The viewport, grown by a margin, as a pyramid of four planes through the camera, in world coordinates.
 *
 * Everything inside the pyramid is in front of the camera and projects to finite coordinates, so lines and
 * polygons that reach behind the camera can be cut before they are projected.
 *
 * @param screenToWorld The rotation from screen coordinates (x right, y up, the camera looks along −z) to world
 *   directions.
 * @param margin Pixels by which the viewport is grown on every side, so strokes do not end visibly at the edge.
 */
internal class AimFrustum(projection: PinholeProjection, screenToWorld: Matrix3, margin: Double) {
    /** Inward normals (left, right, bottom, top) packed as x, y, z: a direction is inside when all four dot products are ≥ 0. */
    val planes = DoubleArray(12)

    /** The result of the latest successful [clip]. */
    val segment = AimClippedSegment()

    init {
        val halfWidth = (projection.viewportWidth / 2 + margin) / projection.focalLength
        val halfHeight = (projection.viewportHeight / 2 + margin) / projection.focalLength
        // In screen coordinates (x right, y up, the camera looks along −z) the left plane is
        // x + halfWidth · depth ≥ 0 with depth = −z, and so on.
        val normals = listOf(
            Vector3(1.0, 0.0, -halfWidth), Vector3(-1.0, 0.0, -halfWidth),
            Vector3(0.0, 1.0, -halfHeight), Vector3(0.0, -1.0, -halfHeight),
        )
        for ((index, normal) in normals.withIndex()) {
            val world = screenToWorld * normal
            planes[3 * index] = world.x
            planes[3 * index + 1] = world.y
            planes[3 * index + 2] = world.z
        }
    }

    /**
     * Cuts the chord from `a` to `b` to the pyramid.
     *
     * @return `false` when none of it is inside; otherwise the part inside is in [segment].
     */
    fun clip(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Boolean {
        var enter = 0.0
        var leave = 1.0
        for (plane in 0 until 4) {
            val nx = planes[3 * plane]
            val ny = planes[3 * plane + 1]
            val nz = planes[3 * plane + 2]
            val da = nx * ax + ny * ay + nz * az
            val db = nx * bx + ny * by + nz * bz
            if (da < 0 && db < 0) return false
            if (da < 0) {
                enter = max(enter, da / (da - db))
            } else if (db < 0) {
                leave = min(leave, da / (da - db))
            }
        }
        if (!(enter < leave)) return false
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        val result = segment
        result.startClipped = enter > 0
        result.endClipped = leave < 1
        result.startX = if (enter > 0) ax + dx * enter else ax
        result.startY = if (enter > 0) ay + dy * enter else ay
        result.startZ = if (enter > 0) az + dz * enter else az
        result.endX = if (leave < 1) ax + dx * leave else bx
        result.endY = if (leave < 1) ay + dy * leave else by
        result.endZ = if (leave < 1) az + dz * leave else bz
        return true
    }
}

/**
 * Projects world directions with the phone's own back camera: the heading offset is applied, the DSLR's device
 * correction is not (see [PinholeProjection]). [worldToScreen] turns world directions into the screen frame (x right,
 * y up, the camera looks along −z). The latest successful single projection is in [x] and [y] (pixels, y down).
 */
internal class AimProjector(val projection: PinholeProjection, private val worldToScreen: Matrix3) {
    /** Where the latest successful [project] put its direction in the viewport. */
    var x = 0f
        private set
    var y = 0f
        private set

    private val point = FloatArray(2)

    /** Projects [direction]; `false` when it is behind the camera, otherwise the position is in [x] and [y]. */
    fun project(direction: Vector3): Boolean = project(direction.x, direction.y, direction.z)

    fun project(dx: Double, dy: Double, dz: Double): Boolean {
        if (!projection.projectInto(dx, dy, dz, worldToScreen, point)) return false
        x = point[0]
        y = point[1]
        return true
    }

    /**
     * [project] for [count] packed directions at once: the viewport positions go to [out] as x, y pairs, `NaN` for
     * directions behind the camera. [out] needs `2 · count` values.
     */
    fun projectAll(directions: DoubleArray, count: Int, out: FloatArray) {
        projection.projectAllInto(directions, count, worldToScreen, out)
    }
}

/**
 * A set of polylines described by world directions: they are cut to the viewport as they are added and projected
 * in one batch when the path is made. Reusable: [clear] it to start over.
 */
internal class AimWorldPath {
    private var vertices = DoubleArray(3 * INITIAL_CAPACITY)
    private var startsSubpath = BooleanArray(INITIAL_CAPACITY)
    private var projected = FloatArray(2 * INITIAL_CAPACITY)
    private var count = 0

    val isEmpty: Boolean get() = count == 0

    fun clear() {
        count = 0
    }

    /** Adds the visible parts of the polyline through [points]; neighbours are joined by great-circle arcs. */
    fun addPolyline(points: List<Vector3>, closed: Boolean, frustum: AimFrustum) {
        val size = points.size
        if (size < 2) return
        var isContinuing = false
        val segmentCount = if (closed) size else size - 1
        for (index in 0 until segmentCount) {
            val a = points[index]
            val b = points[(index + 1) % size]
            isContinuing = addSegment(a.x, a.y, a.z, b.x, b.y, b.z, frustum, isContinuing)
        }
    }

    /** Adds the visible part of the single segment from `a` to `b` as its own subpath. */
    fun addSegment(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, frustum: AimFrustum) {
        addSegment(ax, ay, az, bx, by, bz, frustum, false)
    }

    /** Adds one segment; [isContinuing] tells whether it continues the previous one. Returns whether the next may. */
    private fun addSegment(
        ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double,
        frustum: AimFrustum, isContinuing: Boolean,
    ): Boolean {
        if (!frustum.clip(ax, ay, az, bx, by, bz)) return false
        val segment = frustum.segment
        if (!(isContinuing && !segment.startClipped)) {
            append(segment.startX, segment.startY, segment.startZ, true)
        }
        append(segment.endX, segment.endY, segment.endZ, false)
        return !segment.endClipped
    }

    private fun append(x: Double, y: Double, z: Double, starts: Boolean) {
        if (count == startsSubpath.size) {
            vertices = vertices.copyOf(vertices.size * 2)
            startsSubpath = startsSubpath.copyOf(startsSubpath.size * 2)
        }
        vertices[3 * count] = x
        vertices[3 * count + 1] = y
        vertices[3 * count + 2] = z
        startsSubpath[count] = starts
        count += 1
    }

    /** Rewinds [path] and fills it with the lines in viewport coordinates. */
    fun toPath(path: Path, projector: AimProjector) {
        path.rewind()
        if (count == 0) return
        if (projected.size < 2 * count) projected = FloatArray(2 * vertices.size / 3)
        projector.projectAll(vertices, count, projected)
        for (index in 0 until count) {
            val x = projected[2 * index]
            if (x.isNaN()) continue
            if (startsSubpath[index]) {
                path.moveTo(x, projected[2 * index + 1])
            } else {
                path.lineTo(x, projected[2 * index + 1])
            }
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024
    }
}
