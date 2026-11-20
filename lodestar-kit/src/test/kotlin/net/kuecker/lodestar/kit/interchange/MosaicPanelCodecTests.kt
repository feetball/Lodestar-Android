package net.kuecker.lodestar.kit.interchange

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.parseDeclination
import net.kuecker.lodestar.kit.formatting.parseRightAscension
import net.kuecker.lodestar.kit.mosaic.MosaicPlan
import net.kuecker.lodestar.kit.mosaic.MosaicPlanner
import net.kuecker.lodestar.kit.mosaic.MosaicRegion
import net.kuecker.lodestar.kit.mosaic.MosaicTestSupport
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The mosaic panel writers. */
class MosaicPanelCodecTests {
    private fun plan(positionAngle: Double = 0.0): MosaicPlan = MosaicPlanner.plan(
        MosaicRegion.Equatorial(EquatorialCoordinate(10.6847, 41.2687), 8.0, 5.0, positionAngle),
        MosaicTestSupport.frame(400.0),
    )

    /** The difference of two angles in degrees, in [0, 180]. */
    private fun separation(a: Double, b: Double): Double = abs(AstroMath.normalizeSignedDegrees(a - b))

    @Test
    fun testRecordsFollowThePanelsOfThePlan() {
        val plan = plan()
        val records = MosaicPanelCodec.records(plan)
        assertEquals(plan.panelCount, records.size)
        assertTrue(records.size > 1)
        for ((record, panel) in records.zip(plan.panels)) {
            val center = assertNotNull(panel.center.equatorialCoordinate)
            assertEquals(panel.id, record.panel)
            assertEquals(panel.row + 1, record.row)
            assertEquals(panel.column + 1, record.column)
            assertEquals(center.rightAscension, record.rightAscension, 1e-12)
            assertEquals(center.declination, record.declination, 1e-12)
            assertEquals(panel.shootingPosition + 1, record.order)
        }
        assertEquals((1..records.size).toList(), records.map { it.order }.sorted())
    }

    @Test
    fun testPositionAngleIsTheDirectionOfTheTopEdge() {
        // Rows follow the declination circles, so the top edge points north up to the convergence of the meridians.
        for (record in MosaicPanelCodec.records(plan())) {
            assertTrue(separation(record.positionAngle, 0.0) < 4, record.panel)
            assertTrue(record.positionAngle >= 0)
            assertTrue(record.positionAngle < 360)
        }
        // A region turned by 90° has its top edges pointing east.
        for (record in MosaicPanelCodec.records(plan(positionAngle = 90.0))) {
            assertTrue(separation(record.positionAngle, 90.0) < 4, record.panel)
        }
    }

    @Test
    fun testAPlanInAzimuthAndAltitudeHasNoCoordinates() {
        val region = MosaicRegion.Horizontal(150.0, 110.0, 0.0, 60.0)
        val plan = MosaicPlanner.plan(region, MosaicTestSupport.frame(35.0))
        assertFailsWith<InterchangeError.HorizontalPlan> { MosaicPanelCodec.records(plan) }
    }

    private val records = listOf(
        MosaicPanelRecord("R1C2", 1, 2, 10.6847, 41.2687, 12.5, 3),
        MosaicPanelRecord("R1C1", 1, 1, 359.9999, -0.000001, 0.0, 1),
        MosaicPanelRecord("R2C1", 2, 1, 83.8221, -5.3911, 359.99999, 2),
    )

    @Test
    fun testCSV() {
        val expected = listOf(
            "Panel,Row,Column,RA J2000 (deg),Dec J2000 (deg),Position angle (deg),Order",
            "R1C2,1,2,10.68470,41.26870,12.50000,3",
            "R1C1,1,1,359.99990,0.00000,0.00000,1",
            "R2C1,2,1,83.82210,-5.39110,359.99999,2",
        )
        assertEquals(expected.joinToString("\r\n") + "\r\n", MosaicPanelCodec.csv(records))
    }

    @Test
    fun testTextListInShootingOrder() {
        val expected = listOf(
            "M 31 R1C1, 00 00 00.0, +00 00 00",
            "M 31 R2C1, 05 35 17.3, -05 23 28",
            "M 31 R1C2, 00 42 44.3, +41 16 07",
        )
        assertEquals(expected.joinToString("\n") + "\n", MosaicPanelCodec.text(records, "M 31"))
        assertTrue(MosaicPanelCodec.text(records, " \n ").startsWith("Mosaic R1C1, "))
        assertEquals("", MosaicPanelCodec.text(emptyList(), "x"))
    }

    @Test
    fun testTextOfARealPlanReadsBackWithTheAngleParsers() {
        val list = MosaicPanelCodec.records(plan())
        val lines = MosaicPanelCodec.text(list, "M 31").split("\n").filter { it.isNotEmpty() }
        for ((line, record) in lines.zip(list.sortedBy { it.order })) {
            val parts = line.split(",").map { it.trim() }
            assertEquals(3, parts.size)
            val hours = assertNotNull(AngleFormatter.parseRightAscension(parts[1]))
            val declination = assertNotNull(AngleFormatter.parseDeclination(parts[2]))
            assertEquals(record.rightAscension, hours * 15, 0.5 * 15 / 3600 + 1e-9)
            assertEquals(record.declination, declination, 0.5 / 3600 + 1e-9)
        }
    }
}
