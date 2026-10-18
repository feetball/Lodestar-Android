package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.kit.foundation.CoordinateTransforms
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GalacticCoordinate
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.shared.SkySnapshot
import kotlin.math.floor
import kotlin.math.max

/** A closed outline of the Milky Way, with the cap that contains it for quick rejection. */
class AimMilkyWayRing private constructor(
    /** The outline's vertices packed as x, y, z, for allocation-free clipping and projection. */
    val vertices: DoubleArray,
    val center: Vector3,
    /** Largest angle in degrees between [center] and a vertex. */
    val radius: Double,
) {
    /** The number of vertices. */
    val count: Int get() = vertices.size / 3

    companion object {
        /**
         * Rings wider than this (radius in degrees) are cut up first: their closing edges after clipping would
         * approach half a circle, which a rectilinear view cannot represent.
         */
        const val MAXIMUM_RADIUS = 60.0

        /** The ring of [outline], or `null` when it is degenerate or too wide. */
        fun create(outline: List<Vector3>): AimMilkyWayRing? {
            if (outline.size < 3) return null
            var sumX = 0.0
            var sumY = 0.0
            var sumZ = 0.0
            for (vertex in outline) {
                sumX += vertex.x
                sumY += vertex.y
                sumZ += vertex.z
            }
            val center = Vector3(sumX, sumY, sumZ).normalized
            if (center.x == 0.0 && center.y == 0.0 && center.z == 0.0) return null
            var radius = 0.0
            val packed = DoubleArray(outline.size * 3)
            for ((index, vertex) in outline.withIndex()) {
                radius = max(radius, center.angle(vertex))
                packed[3 * index] = vertex.x
                packed[3 * index + 1] = vertex.y
                packed[3 * index + 2] = vertex.z
            }
            if (!(radius <= MAXIMUM_RADIUS)) return null
            return AimMilkyWayRing(packed, center, radius)
        }
    }
}

/** The outlines of one Milky Way brightness level: filled areas and the holes cut out of them (even-odd rule). */
class AimMilkyWayLayer(val rings: List<AimMilkyWayRing>) {
    companion object {
        /**
         * The drawable layers of the snapshot's bands. The outer glow circles the whole sky in one outline; it is cut
         * into strips of galactic longitude, which together fill the same area. A band that still cannot be prepared
         * is left out as a whole, because its holes would otherwise be filled on their own.
         */
        fun layers(bands: List<SkySnapshot.MilkyWayBand>, frame: SkyFrame): List<AimMilkyWayLayer> =
            bands.mapNotNull { band ->
                val rings = ArrayList<AimMilkyWayRing>()
                for (outline in band.polygons + band.holes) {
                    val ring = AimMilkyWayRing.create(outline)
                    if (ring != null) {
                        rings.add(ring)
                        continue
                    }
                    val strips = AimGalacticStrips.pieces(outline, frame)
                    val stripRings = strips.mapNotNull { AimMilkyWayRing.create(it) }
                    if (stripRings.isEmpty() || stripRings.size != strips.size) return@mapNotNull null
                    rings.addAll(stripRings)
                }
                if (rings.isEmpty()) null else AimMilkyWayLayer(rings)
            }
    }
}

/**
 * Cuts an outline that runs all the way around the sky into pieces of limited width.
 *
 * In galactic coordinates, with the longitude unrolled so it grows continuously along the outline, the outline of the
 * Milky Way is a simple polygon, and cutting it at multiples of [WIDTH] degrees of longitude gives pieces that
 * are compact on the sphere. Their shared edges are meridians, which are great-circle arcs like the rest.
 */
object AimGalacticStrips {
    /** The width of a piece in degrees of galactic longitude. */
    const val WIDTH = 60.0

    private class Point(val longitude: Double, val latitude: Double)

    /** The pieces as rings of ENU directions; empty when the outline is degenerate. */
    fun pieces(ring: List<Vector3>, frame: SkyFrame): List<List<Vector3>> {
        if (ring.size < 3) return emptyList()
        val toJ2000 = frame.j2000ToHorizontal.transposed
        val outline = ArrayList<Point>(ring.size)
        var turns = 0.0
        var hasPrevious = false
        var previousLongitude = 0.0
        for (vertex in ring) {
            val galactic = CoordinateTransforms.equatorialJ2000ToGalactic(EquatorialCoordinate(toJ2000 * vertex))
            if (hasPrevious) {
                val step = galactic.longitude - previousLongitude
                if (step > 180) {
                    turns -= 360
                } else if (step < -180) {
                    turns += 360
                }
            }
            hasPrevious = true
            previousLongitude = galactic.longitude
            outline.add(Point(galactic.longitude + turns, galactic.latitude))
        }
        val first = outline.minOf { it.longitude }
        val last = outline.maxOf { it.longitude }
        if (!(last - first < 4 * 360)) return emptyList()

        val pieces = ArrayList<List<Vector3>>()
        var start = floor(first / WIDTH) * WIDTH
        while (start < last) {
            val piece = clip(clip(outline, start, keepingAbove = true), start + WIDTH, keepingAbove = false)
            if (piece.size >= 3) pieces.add(piece.map { direction(it, frame) })
            start += WIDTH
        }
        return pieces
    }

    /** Sutherland–Hodgman against the line of constant longitude [boundary]. */
    private fun clip(polygon: List<Point>, boundary: Double, keepingAbove: Boolean): List<Point> {
        val last = polygon.lastOrNull() ?: return emptyList()
        val sign = if (keepingAbove) 1.0 else -1.0
        val output = ArrayList<Point>()
        var previous = last
        var previousDistance = sign * (previous.longitude - boundary)
        for (current in polygon) {
            val distance = sign * (current.longitude - boundary)
            if (distance >= 0) {
                if (previousDistance < 0) output.add(crossing(previous, current, previousDistance, distance))
                output.add(current)
            } else if (previousDistance >= 0) {
                output.add(crossing(previous, current, previousDistance, distance))
            }
            previous = current
            previousDistance = distance
        }
        return output
    }

    private fun crossing(a: Point, b: Point, da: Double, db: Double): Point {
        val t = da / (da - db)
        return Point(a.longitude + (b.longitude - a.longitude) * t, a.latitude + (b.latitude - a.latitude) * t)
    }

    private fun direction(point: Point, frame: SkyFrame): Vector3 {
        val galactic = GalacticCoordinate(point.longitude, point.latitude)
        return frame.horizontalVector(CoordinateTransforms.galacticToEquatorialJ2000(galactic).unitVector)
    }
}
