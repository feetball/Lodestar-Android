package net.kuecker.lodestar.features.satellites

import kotlin.test.Test
import kotlin.test.assertEquals

class SatellitePathModelTest {
    private fun assertPoint(expectedX: Double, expectedY: Double, azimuth: Double, altitude: Double) {
        val (x, y) = SatellitePathModel.project(azimuth, altitude)
        assertEquals(expectedX, x, 1e-9, "x of $azimuth/$altitude")
        assertEquals(expectedY, y, 1e-9, "y of $azimuth/$altitude")
    }

    @Test
    fun theHorizonIsTheUnitCircleWithNorthAtTheTopAndEastOnTheLeft() {
        assertPoint(0.0, 1.0, 0.0, 0.0)
        assertPoint(-1.0, 0.0, 90.0, 0.0)
        assertPoint(0.0, -1.0, 180.0, 0.0)
        assertPoint(1.0, 0.0, 270.0, 0.0)
    }

    @Test
    fun theZenithIsTheCentreAndAltitudeScalesTheRadius() {
        assertPoint(0.0, 0.0, 123.0, 90.0)
        assertPoint(0.0, 2.0 / 3.0, 0.0, 30.0)
        assertPoint(0.0, 1.0 / 3.0, 0.0, 60.0)
        assertPoint(-0.5, 0.0, 90.0, 45.0)
    }

    @Test
    fun altitudesOutsideTheSkyAreClamped() {
        assertPoint(0.0, 1.0, 0.0, -5.0)
        assertPoint(0.0, 0.0, 0.0, 95.0)
    }
}
