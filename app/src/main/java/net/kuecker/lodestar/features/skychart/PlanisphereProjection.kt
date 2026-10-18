package net.kuecker.lodestar.features.skychart

import androidx.compose.ui.geometry.Offset
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A point of the planisphere's chart space: x to the right, y downwards, the horizon is the unit circle. */
internal data class ChartPoint(val x: Double, val y: Double)

/**
 * A growable list of chart points stored as x, y pairs, so that polylines and rings are built without boxing.
 */
internal class PointBuffer(initialCapacity: Int = 16) {
    private var values = DoubleArray(initialCapacity.coerceAtLeast(1) * 2)

    var count = 0
        private set

    val lastX: Double get() = values[count * 2 - 2]
    val lastY: Double get() = values[count * 2 - 1]

    fun add(x: Double, y: Double) {
        if (count * 2 + 2 > values.size) values = values.copyOf(values.size * 2)
        values[count * 2] = x
        values[count * 2 + 1] = y
        count += 1
    }

    fun removeLast() {
        if (count > 0) count -= 1
    }

    fun toFloatArray(): FloatArray = FloatArray(count * 2) { values[it].toFloat() }

    fun toDoubleArray(): DoubleArray = values.copyOf(count * 2)
}

/**
 * The planisphere's chart space and the geometry shared by the scene builder and the views.
 *
 * The chart shows the whole sky as seen lying on one's back and looking up: the zenith is the centre, the horizon is
 * the unit circle, **north is at the top and east is to the left** (azimuth grows counter-clockwise, like a star
 * wheel held overhead). The projection is the azimuthal stereographic one, `r = tan(zenithDistance / 2)`: conformal,
 * so small shapes keep their shape, with the horizon at radius 1 and the 30° and 60° altitude circles at 0.577 and
 * 0.268. Chart points use x to the right and y downwards, so [PlanisphereViewport] can scale them straight onto the
 * screen.
 */
internal object PlanisphereProjection {
    /**
     * Directions farther from the zenith than this chart radius (altitude below about −71°) are pulled back onto the
     * circle of this radius. The nadir lies at infinity; the pulled-back part of a polygon is never visible, it only
     * has to keep the polygon's topology intact.
     */
    const val RETRACTION_RADIUS = 6.0

    /** The chart point of an East–North–Up direction. The direction must be above the nadir. */
    fun chartPoint(direction: Vector3): ChartPoint {
        val denominator = 1 + direction.z
        return ChartPoint(-direction.x / denominator, -direction.y / denominator)
    }

    /**
     * Like [chartPoint], but a direction farther than [RETRACTION_RADIUS] from the zenith is moved along its ray onto that
     * circle, so the result is always finite.
     */
    fun retractedChartPoint(direction: Vector3): ChartPoint = retractedChartPoint(direction.x, direction.y, direction.z)

    /** [retractedChartPoint] for a direction given by its components. */
    fun retractedChartPoint(x: Double, y: Double, z: Double): ChartPoint {
        val denominator = 1 + z
        val horizontalSquared = x * x + y * y
        if (denominator > 0 && horizontalSquared <= RETRACTION_RADIUS * RETRACTION_RADIUS * denominator * denominator) {
            return ChartPoint(-x / denominator, -y / denominator)
        }
        val horizontal = sqrt(horizontalSquared)
        if (!(horizontal > 1e-12)) return ChartPoint(0.0, RETRACTION_RADIUS)
        return ChartPoint(-x / horizontal * RETRACTION_RADIUS, -y / horizontal * RETRACTION_RADIUS)
    }

    /** The chart radius of a circle of constant altitude (degrees); 1 for the horizon. */
    fun radiusOfAltitude(altitude: Double): Double = AstroMath.tanDeg((90 - altitude) / 2)

    /** The unit chart vector pointing towards an azimuth (degrees from north through east). */
    fun unitVectorTowardsAzimuth(azimuth: Double): ChartPoint =
        ChartPoint(-AstroMath.sinDeg(azimuth), -AstroMath.cosDeg(azimuth))

    // Polylines above the horizon

    /**
     * The parts of the polyline through [vertices] that lie above the horizon, as chart points (x, y pairs).
     *
     * Consecutive vertices are joined by great-circle arcs, subdivided so that no step exceeds [maximumStep] degrees;
     * an arc that crosses the horizon is cut exactly there.
     */
    fun visiblePolylines(vertices: List<Vector3>, maximumStep: Double = 4.0): List<FloatArray> {
        val first = vertices.firstOrNull() ?: return emptyList()
        val clipper = HorizonClipper()
        clipper.append(first.x, first.y, first.z)
        var previous = first
        for (index in 1 until vertices.size) {
            val vertex = vertices[index]
            forEachArcSample(previous, vertex, maximumStep) { x, y, z -> clipper.append(x, y, z) }
            previous = vertex
        }
        return clipper.finish()
    }

    // Filled rings

    /**
     * A closed ring of directions as a chart polygon (x, y pairs; its closing edge is implicit), or an empty array
     * for fewer than three vertices.
     *
     * Edges are great-circle arcs subdivided to at most [maximumStep] degrees. Parts of the ring that dip towards the
     * nadir are pulled onto the retraction circle, and where the ring runs along that circle it follows it, so the
     * polygon keeps the topology of the projected ring and fills correctly inside the horizon circle.
     */
    fun ring(vertices: List<Vector3>, maximumStep: Double = 2.0): DoubleArray {
        if (vertices.size < 3) return DoubleArray(0)
        val points = PointBuffer(vertices.size * 2)
        val start = retractedChartPoint(vertices[0])
        points.add(start.x, start.y)
        for (index in vertices.indices) {
            val next = vertices[(index + 1) % vertices.size]
            forEachArcSample(vertices[index], next, maximumStep) { x, y, z ->
                val point = retractedChartPoint(x, y, z)
                if (points.count > 0 && isOnRetractionCircle(points.lastX, points.lastY) &&
                    isOnRetractionCircle(point.x, point.y)
                ) {
                    addRetractionArc(points, points.lastX, points.lastY, point.x, point.y)
                }
                points.add(point.x, point.y)
            }
        }
        points.removeLast() // the closing sample repeats the first vertex
        return points.toDoubleArray()
    }

    /** Whether the point lies inside [ring] (even–odd rule, ring closed implicitly). */
    fun contains(x: Double, y: Double, ring: DoubleArray): Boolean {
        var inside = false
        val count = ring.size / 2
        var previous = count - 1
        for (index in 0 until count) {
            val ax = ring[index * 2]
            val ay = ring[index * 2 + 1]
            val bx = ring[previous * 2]
            val by = ring[previous * 2 + 1]
            if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside
            previous = index
        }
        return inside
    }

    // Helpers

    /**
     * Calls [body] with the directions along the great-circle arc from [a] to [b], excluding [a] and including [b], at
     * most [maximumStep] degrees apart.
     */
    private inline fun forEachArcSample(
        a: Vector3,
        b: Vector3,
        maximumStep: Double,
        body: (x: Double, y: Double, z: Double) -> Unit,
    ) {
        val angle = a.angle(b)
        // Nearly opposite directions have no unique arc; step straight to the end.
        val steps = if (angle > 170) 1 else max(1, ceil(angle / maximumStep).toInt())
        for (step in 1 until steps) {
            val x = a.x * (steps - step) + b.x * step
            val y = a.y * (steps - step) + b.y * step
            val z = a.z * (steps - step) + b.z * step
            val length = sqrt(x * x + y * y + z * z)
            if (length > 0) body(x / length, y / length, z / length) else body(0.0, 0.0, 0.0)
        }
        body(b.x, b.y, b.z)
    }

    private fun isOnRetractionCircle(x: Double, y: Double): Boolean =
        x * x + y * y >= RETRACTION_RADIUS * RETRACTION_RADIUS * (1 - 1e-9)

    /** Adds the points strictly between (ax, ay) and (bx, by) along the retraction circle, the short way round. */
    private fun addRetractionArc(points: PointBuffer, ax: Double, ay: Double, bx: Double, by: Double) {
        val sweep = atan2(ax * by - ay * bx, ax * bx + ay * by)
        val steps = ceil(abs(sweep) / (PI / 12)).toInt()
        if (steps <= 1) return
        val start = atan2(ay, ax)
        for (step in 1 until steps) {
            val angle = start + sweep * step / steps
            points.add(RETRACTION_RADIUS * cos(angle), RETRACTION_RADIUS * sin(angle))
        }
    }
}

/** Collects the above-horizon pieces of a polyline whose samples arrive in order. */
private class HorizonClipper {
    private val polylines = ArrayList<FloatArray>()
    private var current = PointBuffer()
    private var hasPrevious = false
    private var previousX = 0.0
    private var previousY = 0.0
    private var previousZ = 0.0

    fun append(x: Double, y: Double, z: Double) {
        val isVisible = z >= 0
        if (!hasPrevious) {
            if (isVisible) addChartPoint(x, y, z)
        } else {
            val wasVisible = previousZ >= 0
            if (wasVisible && isVisible) {
                addChartPoint(x, y, z)
            } else if (wasVisible) {
                addCrossing(previousX, previousY, previousZ, x, y, z)
                flush()
            } else if (isVisible) {
                current = PointBuffer()
                addCrossing(previousX, previousY, previousZ, x, y, z)
                addChartPoint(x, y, z)
            }
        }
        hasPrevious = true
        previousX = x
        previousY = y
        previousZ = z
    }

    fun finish(): List<FloatArray> {
        flush()
        return polylines
    }

    private fun flush() {
        if (current.count >= 2) polylines.add(current.toFloatArray())
        current = PointBuffer()
    }

    private fun addChartPoint(x: Double, y: Double, z: Double) {
        val denominator = 1 + z
        current.add(-x / denominator, -y / denominator)
    }

    /** Adds the point where the short arc between the two directions (one above the horizon, one below) meets it. */
    private fun addCrossing(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double) {
        val fraction = az / (az - bz)
        val x = ax + (bx - ax) * fraction
        val y = ay + (by - ay) * fraction
        val z = az + (bz - az) * fraction
        val length = sqrt(x * x + y * y + z * z)
        if (length > 0) addChartPoint(x / length, y / length, z / length) else addChartPoint(0.0, 0.0, 0.0)
    }
}

/**
 * The size of the view the chart is drawn in, in pixels, and the screen density for the sizes the chart specifies in
 * density-independent units.
 */
internal class PlanisphereLayout(val width: Float, val height: Float, val density: Float) {
    /** The horizon circle's radius at zoom 1: the view's shorter half-side, less a margin for the cardinal letters. */
    val baseRadius: Float =
        max(MINIMUM_RADIUS * density, min(width, height) / 2 - RIM_MARGIN * density)

    private companion object {
        /** Space kept free around the horizon circle at zoom 1, for the cardinal letters, in dp. */
        const val RIM_MARGIN = 26f
        const val MINIMUM_RADIUS = 40f
    }
}

/**
 * How the chart is placed on the screen: size of the view, zoom, pan and rotation.
 *
 * At zoom 1 the horizon circle fills the shorter side of the view, less a margin for the cardinal letters. Panning
 * moves the circle's centre; [rotation] turns the chart clockwise so that the chosen azimuth points up (0 keeps north
 * at the top).
 */
internal class PlanisphereViewport(
    val layout: PlanisphereLayout,
    val zoom: Float = 1f,
    val pan: Offset = Offset.Zero,
    /** Clockwise rotation of the chart on the screen, in radians. */
    val rotation: Double = 0.0,
) {
    /** Screen pixels per chart unit: the radius of the horizon circle. */
    val radius: Float = layout.baseRadius * zoom

    /** Screen position of the zenith. */
    val centerX: Float = layout.width / 2 + pan.x
    val centerY: Float = layout.height / 2 + pan.y

    /** Cosine and sine of [rotation]. */
    val cosine: Float = cos(rotation).toFloat()
    val sine: Float = sin(rotation).toFloat()

    /** The screen x of a chart point. */
    fun screenX(x: Float, y: Float): Float = centerX + radius * (x * cosine - y * sine)

    /** The screen y of a chart point. */
    fun screenY(x: Float, y: Float): Float = centerY + radius * (x * sine + y * cosine)

    /** The screen position of a chart point. */
    fun screen(x: Float, y: Float): Offset = Offset(screenX(x, y), screenY(x, y))

    /** The screen position of a chart point given in double precision. */
    fun screen(point: ChartPoint): Offset = screen(point.x.toFloat(), point.y.toFloat())

    /** Screen pixels per radian of sky at a chart point (the stereographic scale grows towards the horizon). */
    fun pixelsPerRadian(x: Float, y: Float): Float = radius * (1 + x * x + y * y) / 2

    /** The screen position [outside] pixels outside the horizon circle, towards [azimuth] (degrees). */
    fun rimPoint(azimuth: Double, outside: Float): Offset {
        val scale = 1 + outside / radius
        return screen((-AstroMath.sinDeg(azimuth) * scale).toFloat(), (-AstroMath.cosDeg(azimuth) * scale).toFloat())
    }

    companion object {
        val zoomRange: ClosedFloatingPointRange<Float> = 1f..6f

        /**
         * The pan that keeps the chart point under [anchor] (a screen position) in place while the zoom changes from
         * [oldZoom] to [newZoom].
         */
        fun anchoredPan(pan: Offset, anchor: Offset, layout: PlanisphereLayout, oldZoom: Float, newZoom: Float): Offset {
            val ratio = newZoom / oldZoom
            val dx = anchor.x - layout.width / 2
            val dy = anchor.y - layout.height / 2
            return Offset(dx - ratio * (dx - pan.x), dy - ratio * (dy - pan.y))
        }

        /**
         * [pan] limited to the distance the zoomed chart overhangs the view, `baseRadius * (zoom - 1)`, so the middle
         * of the view always stays on the chart; at zoom 1 the chart cannot be moved.
         */
        fun clampedPan(pan: Offset, zoom: Float, layout: PlanisphereLayout): Offset {
            val limit = layout.baseRadius * (zoom - 1)
            val length = hypot(pan.x, pan.y)
            if (!(length > limit)) return pan
            if (!(limit > 0)) return Offset.Zero
            val scale = limit / length
            return Offset(pan.x * scale, pan.y * scale)
        }
    }
}
