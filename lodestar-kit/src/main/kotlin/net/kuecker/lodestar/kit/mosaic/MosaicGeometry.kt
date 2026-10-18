package net.kuecker.lodestar.kit.mosaic

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Vector3
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A latitude and longitude grid on the celestial sphere or the sky dome: a pole, the direction of longitude 0
 * on the grid's equator (`x`) and the direction of longitude 90 (`y = pole × x`). Longitude grows
 * counter-clockwise seen from outside the sphere above the pole, so it grows towards the left of a
 * camera frame whose top edge points to the pole.
 */
internal class MosaicBasis(val pole: Vector3, val x: Vector3, val y: Vector3) {
    /** The unit vector at [longitude] and [latitude], both in degrees. */
    fun vector(longitude: Double, latitude: Double): Vector3 {
        val cosLatitude = AstroMath.cosDeg(latitude)
        return x * (cosLatitude * AstroMath.cosDeg(longitude)) +
            y * (cosLatitude * AstroMath.sinDeg(longitude)) +
            pole * AstroMath.sinDeg(latitude)
    }
}

/**
 * The region to cover in grid terms: the longitude span about longitude 0 and the latitude range.
 *
 * @property longitudeSpan The extent in longitude, degrees in (0, 360]; 360 is a full circle.
 */
internal class MosaicExtent(
    val kind: MosaicFrameKind,
    val basis: MosaicBasis,
    val longitudeSpan: Double,
    val minimumLatitude: Double,
    val maximumLatitude: Double,
) {
    /** Whether the region wraps all the way round the pole. */
    val isRing: Boolean
        get() = longitudeSpan >= 360.0 - MosaicGeometry.tolerance

    /** The area of the region in square degrees. */
    val areaSquareDegrees: Double
        get() = AstroMath.radians(longitudeSpan) *
            (AstroMath.sinDeg(maximumLatitude) - AstroMath.sinDeg(minimumLatitude)) *
            AstroMath.degreesPerRadian * AstroMath.degreesPerRadian
}

/** The rows and columns of a layout, in grid coordinates. Rows run from the top (highest latitude). */
internal class MosaicLayout(
    val rowLatitudes: DoubleArray,
    val columnLongitudes: List<DoubleArray>,
    val columnSpacings: DoubleArray,
    val rowWidths: DoubleArray,
    val rowStep: Double,
) {
    val panelCount: Int
        get() = columnLongitudes.sumOf { it.size }
}

/** A frame placed on the sphere: its centre and the unit vectors along its right and up edges. */
internal class MosaicAxes(val center: Vector3, val right: Vector3, val up: Vector3)

/** The geometry behind [MosaicPlanner]: grids, row and column layouts, and the coverage check. */
internal object MosaicGeometry {
    /** Slack for comparisons that should hold exactly. */
    const val tolerance: Double = 1e-9

    /** Points per band, along the latitude, at which the narrowest frame width is measured. */
    const val bandSamples: Int = 5

    /** The size of the lattice that checks the coverage of the region. */
    const val latitudeSamples: Int = 41
    const val longitudeSamples: Int = 61

    /** How much overlap and padding each further attempt adds, as a fraction of the frame. */
    const val attemptStep: Double = 0.02

    /** The number of attempts: 0 to 0.4 of extra overlap and padding. */
    const val maximumAttempts: Int = 21

    /** The largest overlap an attempt may use. */
    const val maximumOverlap: Double = 0.9

    // MARK: Region

    /** The grid and extent of an equatorial region that has been validated. */
    fun extent(center: EquatorialCoordinate, width: Double, height: Double, positionAngle: Double): MosaicExtent {
        val c = center.unitVector
        val sinDeclination = AstroMath.sinDeg(center.declination)
        val cosDeclination = max(0.0, AstroMath.cosDeg(center.declination))
        val east = if (cosDeclination > 1e-12) {
            Vector3(-c.y, c.x, 0.0) / hypot(c.x, c.y)
        } else {
            Vector3(0.0, 1.0, 0.0)
        }
        val north = c.cross(east)
        val direction = north * AstroMath.cosDeg(positionAngle) +
            east * AstroMath.sinDeg(positionAngle)
        val pole = (c * sinDeclination + direction * cosDeclination).normalized
        val projected = c - pole * c.dot(pole)
        val x = if (projected.length > 1e-9) projected.normalized else (north - pole * north.dot(pole)).normalized
        val basis = MosaicBasis(pole, x, pole.cross(x))
        val span = if (cosDeclination > 1e-9) min(360.0, width / cosDeclination) else 360.0
        return MosaicExtent(
            kind = MosaicFrameKind.EQUATORIAL,
            basis = basis,
            longitudeSpan = span,
            minimumLatitude = max(-90.0, center.declination - height / 2),
            maximumLatitude = min(90.0, center.declination + height / 2),
        )
    }

    /** The grid and extent of a horizontal region that has been validated. */
    fun extent(azimuthStart: Double, azimuthSpan: Double, minimumAltitude: Double, maximumAltitude: Double): MosaicExtent {
        val centerAzimuth = AstroMath.normalizeDegrees(azimuthStart + azimuthSpan / 2)
        val x = Vector3(AstroMath.sinDeg(centerAzimuth), AstroMath.cosDeg(centerAzimuth), 0.0)
        val zenith = Vector3(0.0, 0.0, 1.0)
        return MosaicExtent(
            kind = MosaicFrameKind.HORIZONTAL,
            basis = MosaicBasis(zenith, x, zenith.cross(x)),
            longitudeSpan = azimuthSpan,
            minimumLatitude = minimumAltitude,
            maximumLatitude = maximumAltitude,
        )
    }

    // MARK: Frames

    /**
     * Half the longitude, in degrees, that a frame centred at latitude [centerLatitude] covers on the parallel
     * at [latitude]: the frame has half-sizes [tanHalfWidth] and [tanHalfHeight] in the tangent plane, its
     * top edge points to the pole and its centre is at longitude 0. 180 means the whole parallel is inside; 0
     * means the parallel does not meet the frame at longitude 0.
     */
    fun longitudeHalfExtent(
        centerLatitude: Double,
        latitude: Double,
        tanHalfWidth: Double,
        tanHalfHeight: Double,
    ): Double {
        val sinCenter = AstroMath.sinDeg(centerLatitude)
        val cosCenter = AstroMath.cosDeg(centerLatitude)
        val sinLatitude = AstroMath.sinDeg(latitude)
        val cosLatitude = AstroMath.cosDeg(latitude)
        fun inside(longitude: Double): Boolean {
            val cosLongitude = AstroMath.cosDeg(longitude)
            val cosDistance = sinCenter * sinLatitude + cosCenter * cosLatitude * cosLongitude
            if (!(cosDistance > 1e-12)) return false
            val x = cosLatitude * AstroMath.sinDeg(longitude) / cosDistance
            val y = (cosCenter * sinLatitude - sinCenter * cosLatitude * cosLongitude) / cosDistance
            return abs(x) <= tanHalfWidth * (1 + tolerance) && abs(y) <= tanHalfHeight * (1 + tolerance)
        }
        if (!inside(0.0)) return 0.0
        var low = 0.0
        var high = 1.0
        while (high <= 180.0) {
            if (!inside(high)) {
                repeat(40) {
                    val middle = (low + high) / 2
                    if (inside(middle)) low = middle else high = middle
                }
                return low
            }
            low = high
            high += 1.0
        }
        return 180.0
    }

    /** [vector] as a point of the plan's coordinate system: equatorial or horizontal coordinates. */
    fun point(vector: Vector3, kind: MosaicFrameKind): MosaicPoint = when (kind) {
        MosaicFrameKind.EQUATORIAL -> MosaicPoint.Equatorial(EquatorialCoordinate(vector))
        MosaicFrameKind.HORIZONTAL -> MosaicPoint.Horizontal(HorizontalCoordinate(vector))
    }

    /** The axes of the frame at [longitude] and [latitude] with its top edge pointing to the pole. */
    fun axes(basis: MosaicBasis, longitude: Double, latitude: Double): MosaicAxes {
        val center = basis.vector(longitude, latitude)
        val towardsPole = basis.pole - center * basis.pole.dot(center)
        val up = if (towardsPole.length > 1e-12) towardsPole.normalized else basis.x
        return MosaicAxes(center, center.cross(up), up)
    }

    /** The corner of a frame: [horizontalSign] +1 is towards its right edge, [verticalSign] +1 towards its top edge. */
    fun corner(axes: MosaicAxes, horizontalSign: Double, verticalSign: Double, tanHalfWidth: Double, tanHalfHeight: Double): Vector3 =
        (axes.center + axes.right * (horizontalSign * tanHalfWidth) + axes.up * (verticalSign * tanHalfHeight)).normalized

    /** The solid angle in square degrees of a frame with the given tangent-plane half-sizes. */
    fun frameAreaSquareDegrees(tanHalfWidth: Double, tanHalfHeight: Double): Double =
        4 * atan(tanHalfWidth * tanHalfHeight / sqrt(1 + tanHalfWidth * tanHalfWidth + tanHalfHeight * tanHalfHeight)) *
            AstroMath.degreesPerRadian * AstroMath.degreesPerRadian

    // MARK: Layout

    /**
     * Lays out rows and columns for [extent].
     *
     * Rows are circles of latitude, spaced so that neighbouring frames overlap by [overlap] of [frameHeight]
     * on the central meridian, centred on the region, with as many rows as it takes for the top edge of the
     * first row and the bottom edge of the last to reach the region's edges plus [padding] of the frame.
     * Each row gets as many columns as it takes for neighbouring frames, which overlap by [overlap] of the
     * narrowest longitude width the frame has within the row's band of latitudes, to reach the region's
     * side edges plus [padding]; the columns are centred on the region.
     *
     * @throws MosaicError.TooManyPanels if the layout has more than [MosaicPlanner.maximumPanelCount] panels.
     */
    fun layout(
        extent: MosaicExtent,
        tanHalfWidth: Double,
        tanHalfHeight: Double,
        frameHeight: Double,
        overlap: Double,
        padding: Double,
    ): MosaicLayout {
        val limit = MosaicPlanner.maximumPanelCount
        val low = extent.minimumLatitude
        val high = extent.maximumLatitude
        val paddedHeight = (high - low) + 2 * padding * frameHeight

        // Rows.
        val rowCount: Int
        val centers: DoubleArray
        var step = 0.0
        if (paddedHeight <= frameHeight + tolerance) {
            rowCount = 1
            centers = doubleArrayOf((low + high) / 2)
        } else {
            step = frameHeight * (1 - overlap)
            val intervals = ceil((paddedHeight - frameHeight) / step - tolerance)
            if (intervals + 1 > limit) throw MosaicError.TooManyPanels(clampedCount(intervals + 1), limit)
            rowCount = intervals.toInt() + 1
            val middle = (low + high) / 2
            centers = DoubleArray(rowCount) { middle + ((rowCount - 1).toDouble() / 2 - it.toDouble()) * step }
        }
        val bandHalfHeight = if (rowCount == 1) min(paddedHeight / 2, 0.49 * frameHeight) else step / 2

        // Columns.
        val longitudes = ArrayList<DoubleArray>(rowCount)
        val spacings = DoubleArray(rowCount)
        val widths = DoubleArray(rowCount)
        var total = 0.0
        for (row in 0 until rowCount) {
            var narrowest = 180.0
            for (sample in 0 until bandSamples) {
                val latitude = centers[row] - bandHalfHeight +
                    2 * bandHalfHeight * sample.toDouble() / (bandSamples - 1).toDouble()
                narrowest = min(narrowest, longitudeHalfExtent(centers[row], latitude, tanHalfWidth, tanHalfHeight))
            }
            val width = max(2 * narrowest, 1e-6)
            val span = extent.longitudeSpan + 2 * padding * width
            val maximumStep = (1 - overlap) * width
            val columns: DoubleArray
            if (extent.isRing || span >= 360.0 - tolerance) {
                val count = if (width >= 360.0 - tolerance) 1.0 else max(1.0, ceil(360.0 / maximumStep - tolerance))
                total += count
                if (total > limit) throw MosaicError.TooManyPanels(clampedCount(total), limit)
                val spacing = 360.0 / count
                columns = DoubleArray(count.toInt()) { ((count - 1) / 2 - it.toDouble()) * spacing }
                spacings[row] = if (count > 1) spacing else 0.0
            } else if (width >= span) {
                total += 1.0
                columns = doubleArrayOf(0.0)
                spacings[row] = 0.0
            } else {
                val count = ceil((span - width) / maximumStep - tolerance) + 1
                total += count
                if (total > limit) throw MosaicError.TooManyPanels(clampedCount(total), limit)
                columns = DoubleArray(count.toInt()) { ((count - 1) / 2 - it.toDouble()) * maximumStep }
                spacings[row] = maximumStep
            }
            widths[row] = width
            longitudes.add(columns)
        }
        if (total > limit) throw MosaicError.TooManyPanels(clampedCount(total), limit)
        return MosaicLayout(centers, longitudes, spacings, widths, step)
    }

    private fun clampedCount(count: Double): Int = if (count >= 1e9) 1_000_000_000 else count.toInt()

    /** The axes of every frame of [layout], row by row. */
    fun axes(layout: MosaicLayout, basis: MosaicBasis): List<MosaicAxes> {
        val result = ArrayList<MosaicAxes>(layout.panelCount)
        for (row in layout.rowLatitudes.indices) {
            for (longitude in layout.columnLongitudes[row]) {
                result.add(axes(basis, longitude, layout.rowLatitudes[row]))
            }
        }
        return result
    }

    // MARK: Coverage

    /** The lattice of points of the region, as `x, y, z` triples, that the coverage check uses. */
    fun samples(extent: MosaicExtent): DoubleArray {
        val result = DoubleArray(latitudeSamples * longitudeSamples * 3)
        var offset = 0
        for (row in 0 until latitudeSamples) {
            val latitude = extent.minimumLatitude +
                (extent.maximumLatitude - extent.minimumLatitude) * row.toDouble() / (latitudeSamples - 1).toDouble()
            for (column in 0 until longitudeSamples) {
                val longitude = if (extent.isRing) {
                    -180.0 + 360.0 * (column.toDouble() + 0.5) / longitudeSamples.toDouble()
                } else {
                    -extent.longitudeSpan / 2 + extent.longitudeSpan * column.toDouble() / (longitudeSamples - 1).toDouble()
                }
                val vector = extent.basis.vector(longitude, latitude)
                result[offset] = vector.x
                result[offset + 1] = vector.y
                result[offset + 2] = vector.z
                offset += 3
            }
        }
        return result
    }

    /**
     * The fraction of [samples] that lie inside at least one of [frames]. With [stopAtGap] the count ends at
     * the first point that is outside every frame and the result is below 1.
     */
    fun coveredFraction(
        samples: DoubleArray,
        frames: List<MosaicAxes>,
        tanHalfWidth: Double,
        tanHalfHeight: Double,
        stopAtGap: Boolean,
    ): Double {
        val total = samples.size / 3
        val reach = 1 / sqrt(1 + tanHalfWidth * tanHalfWidth + tanHalfHeight * tanHalfHeight) * (1 - tolerance)
        var covered = 0
        for (index in 0 until total) {
            val x = samples[index * 3]
            val y = samples[index * 3 + 1]
            val z = samples[index * 3 + 2]
            var inside = false
            for (frame in frames) {
                val depth = x * frame.center.x + y * frame.center.y + z * frame.center.z
                if (depth < reach) continue
                val right = x * frame.right.x + y * frame.right.y + z * frame.right.z
                val up = x * frame.up.x + y * frame.up.y + z * frame.up.z
                if (abs(right) <= tanHalfWidth * depth * (1 + tolerance) && abs(up) <= tanHalfHeight * depth * (1 + tolerance)) {
                    inside = true
                    break
                }
            }
            if (inside) {
                covered += 1
            } else if (stopAtGap) {
                return covered.toDouble() / total.toDouble()
            }
        }
        return covered.toDouble() / total.toDouble()
    }
}
