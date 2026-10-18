package net.kuecker.lodestar.kit.mosaic

import net.kuecker.lodestar.kit.catalog.AngularSize
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.phototools.FrameSize
import kotlin.math.sin

/**
 * How the camera is held while shooting a mosaic.
 *
 * The planner works with the frame as the photographer sees it: for [LANDSCAPE] the sensor's width runs
 * along the rows of the mosaic, for [PORTRAIT] (the camera turned a quarter turn) its height does.
 */
enum class MosaicOrientation(
    /** The stable string used for storage. */
    val rawValue: String,
) {
    /** The long side of the sensor runs along the rows. */
    LANDSCAPE("landscape"),

    /** The short side of the sensor runs along the rows; the camera is turned a quarter turn. */
    PORTRAIT("portrait");

    /** [size], a camera frame in landscape, as it appears in this orientation: swapped for [PORTRAIT]. */
    fun oriented(size: FrameSize): FrameSize = if (this == PORTRAIT) size.rotated else size

    companion object {
        /** The orientation with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): MosaicOrientation? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The coordinate system a mosaic is planned in.
 *
 * Equatorial plans follow the stars (panel centres are J2000 places), horizontal plans follow the
 * observer's horizon (panel centres are fixed azimuth and altitude).
 */
enum class MosaicFrameKind(
    /** The stable string used for storage. */
    val rawValue: String,
) {
    /** Panels are placed in J2000 right ascension and declination. */
    EQUATORIAL("equatorial"),

    /** Panels are placed in azimuth and altitude. */
    HORIZONTAL("horizontal");

    companion object {
        /** The kind with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): MosaicFrameKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The part of the sky a mosaic has to cover.
 *
 * Values are not checked when they are created; [MosaicPlanner.plan] throws a [MosaicError] for an
 * unusable region.
 */
sealed interface MosaicRegion {
    /**
     * A rectangle on the sky, planned in J2000 right ascension and declination.
     *
     * The rows of the mosaic follow the rectangle's width axis. At position angle 0 they follow circles of
     * constant declination, and the region is the band between two declinations and two hour circles: its
     * [height] is measured along a meridian and its [width] along the declination circle through the
     * [center] (an arc of that circle, so near a pole the right-ascension span is wider than [width]).
     * A position angle turns the whole rectangle about its centre.
     *
     * @property center The centre of the rectangle, a J2000 place.
     * @property width The extent along the rows in degrees, in (0, 360].
     * @property height The extent across the rows in degrees, in (0, 180].
     * @property positionAngle The direction of the height axis in degrees, measured from north through east:
     * 0 keeps the height axis north-south and the width axis along the declination circle, 90 turns the
     * rectangle so that its height runs east-west and its width north-south.
     */
    data class Equatorial(
        val center: EquatorialCoordinate,
        val width: Double,
        val height: Double,
        val positionAngle: Double = 0.0,
    ) : MosaicRegion

    /**
     * An azimuth and altitude box, for a panorama shot from a fixed tripod.
     *
     * The mosaic follows circles of constant altitude, with the camera held level.
     *
     * @property azimuthStart The azimuth of the left edge of the box in degrees, from north through east. The
     * box extends from there towards increasing azimuth.
     * @property azimuthSpan The azimuth extent in degrees, in (0, 360]; 360 is a full-circle panorama.
     * @property minimumAltitude The altitude of the lower edge in degrees, in [−90, 90).
     * @property maximumAltitude The altitude of the upper edge in degrees, in (−90, 90], above [minimumAltitude].
     */
    data class Horizontal(
        val azimuthStart: Double,
        val azimuthSpan: Double,
        val minimumAltitude: Double,
        val maximumAltitude: Double,
    ) : MosaicRegion

    companion object {
        /** The margin, as a fraction of the object's size, that [fitting] adds by default. */
        const val defaultMargin: Double = 0.2

        /** The largest margin [fitting] accepts. */
        const val maximumMargin: Double = 2.0

        /**
         * The region that fits an object of [size] centred on [center], with a margin all round.
         *
         * The width is the object's major axis and the height its minor axis (a round object gets a square
         * region), each enlarged by [margin]; the region is turned so that its width runs along the object's
         * major axis ([AngularSize.positionAngle], north-south when the catalog gives none).
         *
         * @param center The object's J2000 place.
         * @param size The object's angular size.
         * @param margin The margin as a fraction of each axis, in [0, [maximumMargin]]: 0.2 makes the region
         * 20 % larger than the object.
         * @throws MosaicError.InvalidMargin if the margin is not in range.
         * @throws MosaicError.InvalidWidth if the enlarged major axis is more than a full circle.
         */
        @Throws(MosaicError::class)
        fun fitting(
            center: EquatorialCoordinate,
            size: AngularSize,
            margin: Double = defaultMargin,
        ): MosaicRegion {
            if (!(margin >= 0.0 && margin <= maximumMargin)) throw MosaicError.InvalidMargin(margin)
            val scale = 1.0 + margin
            val width = size.majorArcminutes / 60.0 * scale
            val height = (size.minorArcminutes ?: size.majorArcminutes) / 60.0 * scale
            if (!(width > 0.0 && width <= 360.0)) throw MosaicError.InvalidWidth(width)
            if (!(height > 0.0 && height <= 180.0)) throw MosaicError.InvalidHeight(height)
            return Equatorial(
                center = center,
                width = width,
                height = height,
                positionAngle = AstroMath.normalizeDegrees((size.positionAngle ?: 0.0) - 90.0),
            )
        }

        /**
         * The region that fits a catalog object, see [fitting] with a size.
         *
         * @param obj A fixed object with a catalog size.
         * @param margin The margin as a fraction of each axis.
         * @throws MosaicError.ObjectNotFixed if the object moves (solar-system bodies) or is a celestial pole.
         * @throws MosaicError.ObjectHasNoSize if the catalog has no size for the object.
         */
        @Throws(MosaicError::class)
        fun fitting(obj: CelestialObject, margin: Double = defaultMargin): MosaicRegion {
            val center = obj.j2000 ?: throw MosaicError.ObjectNotFixed(obj.id)
            val size = obj.angularSize ?: throw MosaicError.ObjectHasNoSize(obj.id)
            return fitting(center, size, margin)
        }

        /**
         * A horizontal box given by its centre and extent, with the altitudes limited to [−90, 90].
         *
         * @param centerAzimuth The azimuth of the middle of the box in degrees.
         * @param centerAltitude The altitude of the middle of the box in degrees.
         * @param azimuthSpan The azimuth extent in degrees.
         * @param altitudeSpan The altitude extent in degrees.
         */
        fun centered(
            centerAzimuth: Double,
            centerAltitude: Double,
            azimuthSpan: Double,
            altitudeSpan: Double,
        ): MosaicRegion = Horizontal(
            azimuthStart = AstroMath.normalizeDegrees(centerAzimuth - azimuthSpan / 2),
            azimuthSpan = azimuthSpan,
            minimumAltitude = AstroMath.clamp(centerAltitude - altitudeSpan / 2, -90.0, 90.0),
            maximumAltitude = AstroMath.clamp(centerAltitude + altitudeSpan / 2, -90.0, 90.0),
        )
    }
}

/** Why a mosaic could not be planned. */
sealed class MosaicError(message: String) : Exception(message) {
    /** A value of the region, the frame or the observer is not a finite number. */
    data object NonFiniteInput : MosaicError("A value is not a finite number.")

    /** The region's width is not in (0, 360] degrees. */
    data class InvalidWidth(val value: Double) : MosaicError("The width $value is not in (0, 360] degrees.")

    /** The region's height is not in (0, 180] degrees. */
    data class InvalidHeight(val value: Double) : MosaicError("The height $value is not in (0, 180] degrees.")

    /** The horizontal region's azimuth span is not in (0, 360] degrees. */
    data class InvalidAzimuthSpan(val value: Double) :
        MosaicError("The azimuth span $value is not in (0, 360] degrees.")

    /** The horizontal region's altitudes are not ordered within [−90, 90] degrees. */
    data class InvalidAltitudeRange(val minimum: Double, val maximum: Double) :
        MosaicError("The altitude range $minimum to $maximum is not within [-90, 90] degrees.")

    /** The overlap is not in [MosaicPlanner.overlapRange]. */
    data class InvalidOverlap(val value: Double) : MosaicError("The overlap $value is not in 0.10 to 0.50.")

    /** The margin of a fitted region is not in [0, [MosaicRegion.maximumMargin]]. */
    data class InvalidMargin(val value: Double) : MosaicError("The margin $value is not in 0 to 2.")

    /** The camera frame is not a pair of angles in (0, 180) degrees. */
    data class InvalidFrame(val horizontal: Double, val vertical: Double) :
        MosaicError("The frame $horizontal by $vertical degrees is not usable.")

    /** The observer's latitude is not in [−90, 90] degrees. */
    data class InvalidLatitude(val value: Double) : MosaicError("The latitude $value is not in [-90, 90] degrees.")

    /** The mosaic would need [count] panels, more than the [limit] the planner will handle. */
    data class TooManyPanels(val count: Int, val limit: Int) :
        MosaicError("The mosaic needs $count panels; the limit is $limit.")

    /** The object is a solar-system body or a pole, so it has no fixed place to plan a mosaic on. */
    data class ObjectNotFixed(val objectID: String) : MosaicError("The object $objectID has no fixed place.")

    /** The catalog has no angular size for the object. */
    data class ObjectHasNoSize(val objectID: String) : MosaicError("The object $objectID has no angular size.")
}

/** A direction on the sky, in the coordinate system of the plan that produced it. */
sealed interface MosaicPoint {
    /** A J2000 place of an equatorial plan. */
    data class Equatorial(val coordinate: EquatorialCoordinate) : MosaicPoint

    /** An azimuth and altitude of a horizontal plan. */
    data class Horizontal(val coordinate: HorizontalCoordinate) : MosaicPoint

    /** The J2000 place, or null for a horizontal point. */
    val equatorialCoordinate: EquatorialCoordinate?
        get() = (this as? Equatorial)?.coordinate

    /** The azimuth and altitude, or null for an equatorial point. */
    val horizontalCoordinate: HorizontalCoordinate?
        get() = (this as? Horizontal)?.coordinate

    /** The unit vector of the direction: J2000 equatorial for an equatorial point, ENU for a horizontal point. */
    val unitVector: Vector3
        get() = when (this) {
            is Equatorial -> coordinate.unitVector
            is Horizontal -> coordinate.unitVector
        }
}

/**
 * One frame of a mosaic.
 *
 * Panels are numbered in row-major order: [index] runs along the top row from the left, then along the next
 * row, and so on. Rows count downwards from the pole side of the mosaic (north for equatorial plans, the
 * zenith for horizontal plans) and columns count from the left edge of the image, which is east for an
 * equatorial plan and the start of the azimuth range for a horizontal plan. The picture of the grid is
 * therefore what the stitched image looks like.
 *
 * @property index The panel's position in row-major order, from 0.
 * @property row The row, from 0 at the top.
 * @property column The column within its row, from 0 at the left. Rows may have different numbers of columns.
 * @property center The direction of the optical axis.
 * @property corners The four corners of the frame for drawing: top left, top right, bottom right, bottom left.
 * The edges between them are great-circle arcs, which is how a rectilinear lens sees straight lines.
 * @property shootingPosition The place of the panel in the shooting order, from 0.
 */
@ConsistentCopyVisibility
data class MosaicPanel internal constructor(
    val index: Int,
    val row: Int,
    val column: Int,
    val center: MosaicPoint,
    val corners: List<MosaicPoint>,
    val shootingPosition: Int,
) {
    /** A short name for lists: "R2C3" is the third panel of the second row. */
    val id: String
        get() = "R${row + 1}C${column + 1}"

    /**
     * The outline of the frame with each edge divided into [segmentsPerEdge] great-circle steps, for charts
     * that do not draw great circles as straight lines.
     *
     * @param segmentsPerEdge Steps per edge, limited to 1...64.
     * @return The points of the outline in the order of [corners], starting at the top left corner and not
     * repeating it at the end: `4 × segmentsPerEdge` points.
     */
    fun outline(segmentsPerEdge: Int = 8): List<MosaicPoint> {
        val steps = AstroMath.clamp(segmentsPerEdge, 1, 64)
        val result = ArrayList<MosaicPoint>(steps * 4)
        for (edge in 0 until 4) {
            val from = corners[edge]
            val to = corners[(edge + 1) % 4]
            val a = from.unitVector
            val b = to.unitVector
            val angle = a.angleRadians(b)
            for (step in 0 until steps) {
                val fraction = step.toDouble() / steps.toDouble()
                val vector = if (angle > 1e-12) {
                    val sinAngle = sin(angle)
                    a * (sin((1 - fraction) * angle) / sinAngle) + b * (sin(fraction * angle) / sinAngle)
                } else {
                    a
                }
                result.add(
                    when (from) {
                        is MosaicPoint.Equatorial -> MosaicPoint.Equatorial(EquatorialCoordinate(vector))
                        is MosaicPoint.Horizontal -> MosaicPoint.Horizontal(HorizontalCoordinate(vector))
                    },
                )
            }
        }
        return result
    }
}

/**
 * How much of the region a plan covers.
 *
 * @property widthDegrees The extent of the mosaic along its rows, in degrees of arc measured along the
 * parallel of the row nearest the middle of the plan.
 * @property heightDegrees The extent across the rows along the central meridian, in degrees.
 * @property longitudeSpanDegrees The extent along the rows in degrees of longitude about the plan's pole:
 * the azimuth span of a horizontal plan, the right-ascension span of an equatorial plan with position angle 0.
 * @property regionAreaSquareDegrees The area of the requested region.
 * @property panelAreaSquareDegrees The sum of the areas of all frames; larger than the region by the overlap
 * and by what the frames add at the edges.
 * @property coveredFraction The fraction of the region that lies inside at least one frame, [0, 1],
 * measured on a 41 by 61 lattice of points that includes the region's edges and corners.
 */
@ConsistentCopyVisibility
data class MosaicCoverage internal constructor(
    val widthDegrees: Double,
    val heightDegrees: Double,
    val longitudeSpanDegrees: Double,
    val regionAreaSquareDegrees: Double,
    val panelAreaSquareDegrees: Double,
    val coveredFraction: Double,
)

/** A caution about a plan. The plan is still usable. */
sealed interface MosaicWarning {
    /** The plan has more than [MosaicPlanner.recommendedPanelLimit] panels: shooting it takes long and the sky moves meanwhile. */
    data class TooManyPanels(val count: Int) : MosaicWarning

    /**
     * The planner could not place the frames so that the whole region is covered with the requested overlap:
     * only [coveredFraction] of the sampled region is inside a frame.
     */
    data class OverlapNotMet(val coveredFraction: Double) : MosaicWarning
}

/**
 * The panels needed to cover a region, and the order to shoot them in.
 *
 * Created by [MosaicPlanner.plan] only.
 *
 * @property region The region that was planned.
 * @property kind Whether the panels are placed in equatorial or horizontal coordinates.
 * @property orientation How the camera is held.
 * @property frame The size of one frame on the sky as held: `horizontal` runs along the rows.
 * @property overlap The requested overlap, a fraction of the frame size.
 * @property achievedOverlap The smallest overlap the layout gives between neighbouring panels, or null for a
 * single panel. Along a row it is measured at the latitude where the frame is narrowest on the sphere,
 * between rows on the central meridian. It is the requested overlap, or more when the planner had to add
 * overlap to close gaps that the curved frame edges leave.
 * @property rows The number of rows.
 * @property columnsPerRow The number of panels in each row, from the top. Rows nearer a pole of the grid
 * need fewer panels for the same extent because they are shorter on the sphere.
 * @property panels All panels in row-major order.
 * @property shootingOrder The panel indices in the order to shoot them.
 * @property pole The point the rows circle: the north celestial pole for an equatorial plan with position
 * angle 0 (turned with the position angle otherwise), the zenith for a horizontal plan. The top edge of each
 * frame points towards it.
 * @property coverage What the plan covers.
 * @property warnings Cautions, empty for a plan that fits the region comfortably.
 */
@ConsistentCopyVisibility
data class MosaicPlan internal constructor(
    val region: MosaicRegion,
    val kind: MosaicFrameKind,
    val orientation: MosaicOrientation,
    val frame: FrameSize,
    val overlap: Double,
    val achievedOverlap: Double?,
    val rows: Int,
    val columnsPerRow: List<Int>,
    val panels: List<MosaicPanel>,
    val shootingOrder: List<Int>,
    val pole: MosaicPoint,
    val coverage: MosaicCoverage,
    val warnings: List<MosaicWarning>,
) {
    /** The number of panels. */
    val panelCount: Int
        get() = panels.size

    /** The largest number of panels in a row. */
    val columns: Int
        get() = columnsPerRow.maxOrNull() ?: 0

    /** `true` when every row has the same number of panels, so the plan is a plain rows × columns grid. */
    val isRectangular: Boolean
        get() = columnsPerRow.all { it == columnsPerRow.first() }

    /** The grid as text for a title, for example "3 × 1" (columns × rows). */
    val gridDescription: String
        get() = if (isRectangular) "$columns × $rows" else "$panelCount panels in $rows rows"

    /** The panels in shooting order. */
    val orderedPanels: List<MosaicPanel>
        get() = shootingOrder.map { panels[it] }

    /**
     * The boundary of the requested region as closed polylines, for drawing it on a chart behind the panels.
     *
     * The top and bottom edges of the region are circles of latitude of the plan's grid, so they are sampled; the
     * sides are great-circle arcs. The loop runs clockwise as seen in the picture of the stitched image: along the top edge
     * from the left, down the right side, back along the bottom edge and up the left side.
     *
     * @param pointsPerEdge Points per side, limited to 2...256.
     * @return One loop of `4 × pointsPerEdge` points, or, for a region that wraps all the way round the pole,
     * two circles of `4 × pointsPerEdge` points, the upper and then the lower edge. A loop does not repeat its
     * first point at the end.
     */
    fun regionOutline(pointsPerEdge: Int = 16): List<List<MosaicPoint>> {
        val extent = when (val area = region) {
            is MosaicRegion.Equatorial -> MosaicGeometry.extent(area.center, area.width, area.height, area.positionAngle)
            is MosaicRegion.Horizontal ->
                MosaicGeometry.extent(area.azimuthStart, area.azimuthSpan, area.minimumAltitude, area.maximumAltitude)
        }
        val steps = AstroMath.clamp(pointsPerEdge, 2, 256)
        val half = extent.longitudeSpan / 2
        val high = extent.maximumLatitude
        val low = extent.minimumLatitude
        fun point(longitude: Double, latitude: Double): MosaicPoint =
            MosaicGeometry.point(extent.basis.vector(longitude, latitude), kind)
        if (extent.isRing) {
            val count = steps * 4
            fun circle(latitude: Double): List<MosaicPoint> =
                List(count) { point(180.0 - 360.0 * it.toDouble() / count.toDouble(), latitude) }
            return listOf(circle(high), circle(low))
        }
        val loop = ArrayList<MosaicPoint>(steps * 4)
        for (step in 0 until steps) {
            loop.add(point(half - extent.longitudeSpan * step.toDouble() / steps.toDouble(), high))
        }
        for (step in 0 until steps) {
            loop.add(point(-half, high - (high - low) * step.toDouble() / steps.toDouble()))
        }
        for (step in 0 until steps) {
            loop.add(point(-half + extent.longitudeSpan * step.toDouble() / steps.toDouble(), low))
        }
        for (step in 0 until steps) {
            loop.add(point(half, low + (high - low) * step.toDouble() / steps.toDouble()))
        }
        return listOf(loop)
    }

    /** The panel at [row] and [column], or null if there is none. */
    fun panel(row: Int, column: Int): MosaicPanel? {
        if (row < 0 || row >= rows || column < 0 || column >= columnsPerRow[row]) return null
        return panels[columnsPerRow.take(row).sum() + column]
    }
}
