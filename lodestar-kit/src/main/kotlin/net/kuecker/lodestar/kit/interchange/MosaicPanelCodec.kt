package net.kuecker.lodestar.kit.interchange

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.formatting.NumberText
import net.kuecker.lodestar.kit.mosaic.MosaicFrameKind
import net.kuecker.lodestar.kit.mosaic.MosaicPanel
import net.kuecker.lodestar.kit.mosaic.MosaicPlan

/**
 * One panel of a mosaic as it is written to a file.
 *
 * @property panel The panel's name, "R2C3" for the third panel of the second row.
 * @property row The row from 1, counted from the pole side of the mosaic.
 * @property column The column from 1 within its row, counted from the left of the stitched image.
 * @property rightAscension The J2000 right ascension of the panel's centre in degrees, [0, 360).
 * @property declination The J2000 declination of the panel's centre in degrees.
 * @property positionAngle The sky position angle of the frame's top edge in degrees, [0, 360), from north through east
 * (the angle a framing tool calls rotation).
 * @property order The place of the panel in the shooting order, from 1.
 */
data class MosaicPanelRecord(
    val panel: String,
    val row: Int,
    val column: Int,
    val rightAscension: Double,
    val declination: Double,
    val positionAngle: Double,
    val order: Int,
)

/** Writes the panels of an equatorial mosaic plan as CSV and as a plain text list for planetarium and ASIAIR import. */
object MosaicPanelCodec {
    /**
     * The records of a plan's panels in row-major order.
     *
     * The position angle is that of the direction from the panel's centre to the middle of its top edge (the edge that
     * points to the plan's pole), measured from the direction of the north celestial pole through east at the centre. At a
     * celestial pole it is 0.
     *
     * @throws InterchangeError.HorizontalPlan for a plan in azimuth and altitude.
     */
    @Throws(InterchangeError::class)
    fun records(plan: MosaicPlan): List<MosaicPanelRecord> {
        if (plan.kind != MosaicFrameKind.EQUATORIAL) throw InterchangeError.HorizontalPlan
        val result = ArrayList<MosaicPanelRecord>(plan.panels.size)
        for (panel in plan.panels) {
            val center = panel.center.equatorialCoordinate ?: throw InterchangeError.HorizontalPlan
            result.add(
                MosaicPanelRecord(
                    panel = panel.id,
                    row = panel.row + 1,
                    column = panel.column + 1,
                    rightAscension = center.rightAscension,
                    declination = center.declination,
                    positionAngle = positionAngle(panel, center),
                    order = panel.shootingPosition + 1,
                ),
            )
        }
        return result
    }

    private fun positionAngle(panel: MosaicPanel, center: EquatorialCoordinate): Double {
        if (panel.corners.size < 2) return 0.0
        val c = center.unitVector
        val pole = Vector3(0.0, 0.0, 1.0)
        val eastDirection = pole.cross(c)
        if (eastDirection.length <= 1e-12) return 0.0
        val east = eastDirection.normalized
        val north = c.cross(east)
        val topMiddle = (corner(panel, 0) + corner(panel, 1)).normalized
        val toTop = topMiddle - c * topMiddle.dot(c)
        if (toTop.length <= 1e-12) return 0.0
        return AstroMath.normalizeDegrees(AstroMath.atan2Deg(toTop.dot(east), toTop.dot(north)))
    }

    private fun corner(panel: MosaicPanel, index: Int): Vector3 =
        panel.corners[index].unitVector

    /**
     * The panels as CSV (RFC 4180, CRLF): `Panel,Row,Column,RA J2000 (deg),Dec J2000 (deg),Position angle (deg),Order`,
     * coordinates and angle in decimal degrees with five places.
     */
    fun csv(records: List<MosaicPanelRecord>): String {
        val rows = ArrayList<List<String>>()
        rows.add(listOf("Panel", "Row", "Column", "RA J2000 (deg)", "Dec J2000 (deg)", "Position angle (deg)", "Order"))
        for (record in records) {
            rows.add(
                listOf(
                    record.panel,
                    record.row.toString(),
                    record.column.toString(),
                    InterchangeText.fixed(record.rightAscension, 5),
                    InterchangeText.fixed(record.declination, 5),
                    InterchangeText.fixed(record.positionAngle, 5),
                    record.order.toString(),
                ),
            )
        }
        return InterchangeCSV.encode(rows)
    }

    /**
     * The panels as a text list in shooting order, one line per panel: `Name, RA hh mm ss.s, Dec ±dd mm ss`, for example
     * `M 31 R1C2, 00 42 44.3, +41 16 09`; the name is the target name and the panel's name. Lines end with LF.
     */
    fun text(records: List<MosaicPanelRecord>, targetName: String): String {
        val cleaned = InterchangeText.singleLine(targetName, SessionLogEntry.maximumNameLength)
        val name = cleaned.ifEmpty { "Mosaic" }
        val lines = records.sortedBy { it.order }.map {
            "$name ${it.panel}, ${rightAscensionText(it.rightAscension)}, ${declinationText(it.declination)}"
        }
        return lines.joinToString("\n") + (if (lines.isEmpty()) "" else "\n")
    }

    /** "hh mm ss.s" for a right ascension in degrees. */
    internal fun rightAscensionText(degrees: Double): String {
        val hours = AstroMath.normalizeDegrees(degrees) / 15
        val parts = NumberText.sexagesimal(hours, 1) ?: return "00 00 00.0"
        return "${NumberText.padded(parts.whole % 24, 2)} ${NumberText.padded(parts.minutes, 2)} ${parts.secondsText}"
    }

    /** "±dd mm ss" for a declination in degrees. */
    internal fun declinationText(degrees: Double): String {
        val clamped = AstroMath.clamp(if (degrees.isFinite()) degrees else 0.0, -90.0, 90.0)
        val parts = NumberText.sexagesimal(kotlin.math.abs(clamped), 0) ?: return "+00 00 00"
        val sign = if (clamped < 0 && !parts.isZero) "-" else "+"
        return "$sign${NumberText.padded(parts.whole, 2)} ${NumberText.padded(parts.minutes, 2)} ${parts.secondsText}"
    }
}
