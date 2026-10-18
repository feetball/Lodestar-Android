package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.mosaic.MosaicOrientation
import net.kuecker.lodestar.kit.mosaic.MosaicPlanner
import net.kuecker.lodestar.kit.mosaic.MosaicRegion
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AimMosaicTest {
    private val north = Vector3(0.0, 1.0, 0.0)

    private fun degrees(value: Double) = Math.toRadians(value)

    /** A camera looking north whose picture top leans [roll] degrees clockwise as seen through the camera. */
    private fun top(roll: Double) = Vector3(sin(degrees(roll)), 0.0, cos(degrees(roll)))

    private fun aim(roll: Double, isPortrait: Boolean = false) =
        AimPanelAim(1, HorizontalCoordinate(0.0, 0.0), roll, isPortrait, "Panel 1 of 1")

    @Test
    fun theRollDifferenceIsTheTurnBackToThePanelsRoll() {
        // A level camera asked for a level panel needs nothing; one rolled 20° clockwise must turn back.
        assertEquals(0.0, aim(0.0).rollDifference(north, top(0.0)), 1e-9)
        assertEquals(-20.0, aim(0.0).rollDifference(north, top(20.0)), 1e-6)
        // A panel that asks for 15° clockwise, from a level camera.
        assertEquals(15.0, aim(15.0).rollDifference(north, top(0.0)), 1e-6)
    }

    @Test
    fun aCameraHeldUpsideDownIsRightToo() {
        assertEquals(0.0, aim(0.0).rollDifference(north, top(180.0)), 1e-6)
        assertEquals(-10.0, aim(0.0).rollDifference(north, top(-170.0)), 1e-6)
    }

    @Test
    fun portraitTakesTheTopOfThePictureFromTheCameraSide() {
        // Looking north the camera's right is east. Turned 80° counter-clockwise it leans 10° from straight up.
        val right = Vector3(cos(degrees(80.0)), 0.0, sin(degrees(80.0)))
        assertEquals(-10.0, aim(0.0, isPortrait = true).rollDifference(north, right), 1e-6)
    }

    @Test
    fun theRollIsOnlyNamedBeyondADegree() {
        assertNull(AimMosaicPointings.rollText(0.9))
        assertNull(AimMosaicPointings.rollText(-1.0))
        assertEquals("Rotate the camera 12° clockwise", AimMosaicPointings.rollText(12.4))
        assertEquals("Rotate the camera 30° counterclockwise", AimMosaicPointings.rollText(-30.0))
        assertEquals("Rotate the camera 2.5° clockwise", AimMosaicPointings.rollText(2.5))
        assertEquals("Rotate the camera 12 degrees clockwise", AimMosaicPointings.spokenRollText(12.4))
        assertNull(AimMosaicPointings.spokenRollText(0.5))
    }

    @Test
    fun widerViewsDrawAndNameFewerDeepSkyObjects() {
        assertEquals(3, deepSkyProminenceLimit(100.0))
        assertEquals(3, deepSkyProminenceLimit(70.0))
        assertEquals(4, deepSkyProminenceLimit(60.0))
        assertEquals(0, deepSkyNameProminence(80.0))
        assertEquals(1, deepSkyNameProminence(60.0))
        assertEquals(2, deepSkyNameProminence(35.0))
        assertEquals(3, deepSkyNameProminence(25.0))
        assertEquals(4, deepSkyNameProminence(20.0))
    }

    @Test
    fun thePanelsOfAPlanStandInTheSkyInShootingOrder() {
        val observer = Observer(checkNotNull(GeographicLocation.validated(45.0, -100.0, 300.0)))
        val plan = MosaicPlanner.plan(
            MosaicRegion.centered(180.0, 40.0, 60.0, 30.0),
            LensSetup(SensorFormat.fullFrame, 85.0, 2.8, 4.3),
            MosaicOrientation.PORTRAIT, 0.25, 45.0,
        )
        val pointings = AimMosaicPointings.make(plan, SkyFrame(Instant.parse("2026-07-15T04:00:00Z"), observer))
        assertEquals(plan.panels.size, pointings.panelCount)
        assertTrue(pointings.isPortrait)
        for (step in 0 until pointings.panelCount) {
            val aim = assertNotNull(pointings.aim(step))
            assertEquals("Panel ${step + 1} of ${pointings.panelCount}", aim.label)
            assertEquals(pointings.order[step], plan.orderedPanels[step].index)
            assertEquals(step, pointings.positions[pointings.order[step]])
        }
        assertNull(pointings.aim(pointings.panelCount))
        assertNull(pointings.aim(-1))
        // The panels of a horizontal plan are level and stay where they are planned.
        assertEquals(0.0, pointings.aim(0)?.roll)
        for (index in 0 until pointings.panelCount) {
            assertEquals(4, pointings.outlines[index].size)
            assertEquals(12, pointings.corners[index].size)
            assertTrue(pointings.radii[index] > 1.0 && pointings.radii[index] < 30.0, "panel $index")
            assertEquals((pointings.positions[index] + 1).toString(), pointings.numbers[index])
        }
        // Shot panels, the current one, the next and the rest.
        val step = 2
        val kinds = (0 until pointings.panelCount).map { pointings.kind(it, step) }
        assertEquals(step, kinds.count { it == AimMosaicPointings.DONE })
        assertEquals(1, kinds.count { it == AimMosaicPointings.CURRENT })
        assertEquals(1, kinds.count { it == AimMosaicPointings.NEXT })
        assertEquals(pointings.panelCount - step - 2, kinds.count { it == AimMosaicPointings.PENDING })
        assertEquals(AimMosaicPointings.CURRENT, pointings.kind(pointings.order[step], step))
    }
}
