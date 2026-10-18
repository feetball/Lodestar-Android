package net.kuecker.lodestar.kit.satellites

import kotlin.math.PI
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SatelliteBrightnessTests {
    @Test
    fun testTheIntrinsicMagnitudeIsTheMagnitudeAt1000KilometresAndNinetyDegrees() {
        assertEquals(-1.8, assertNotNull(SatelliteBrightness.apparentMagnitude(-1.8, 1000.0, 90.0)), 1e-12)
        assertEquals(5.3, assertNotNull(SatelliteBrightness.apparentMagnitude(5.3, 1000.0, 90.0)), 1e-12)
    }

    @Test
    fun testRangeScalesAsFiveLogRange() {
        val near = assertNotNull(SatelliteBrightness.apparentMagnitude(-1.8, 500.0, 90.0))
        val far = assertNotNull(SatelliteBrightness.apparentMagnitude(-1.8, 2000.0, 90.0))
        assertEquals(5.0 * log10(0.5), near + 1.8, 1e-12)
        assertEquals(5.0 * log10(2.0), far + 1.8, 1e-12)
        assertEquals(5.0, assertNotNull(SatelliteBrightness.apparentMagnitude(0.0, 10_000.0, 90.0)), 1e-12)
    }

    @Test
    fun testPhaseFollowsTheDiffuseSphereLaw() {
        // Full phase is 2.5 log10(pi) = 1.243 magnitudes brighter than 90 degrees.
        assertEquals(-1.8 - 2.5 * log10(PI), assertNotNull(SatelliteBrightness.apparentMagnitude(-1.8, 1000.0, 0.0)), 1e-12)
        // 60 degrees: sin 60 + (pi - pi/3) cos 60.
        val bracket = kotlin.math.sin(PI / 3) + (PI - PI / 3) * kotlin.math.cos(PI / 3)
        assertEquals(-1.8 - 2.5 * log10(bracket), assertNotNull(SatelliteBrightness.apparentMagnitude(-1.8, 1000.0, 60.0)), 1e-12)
        // Fainter towards new phase, monotonically.
        var previous = -100.0
        for (phase in 0..175 step 5) {
            val value = assertNotNull(SatelliteBrightness.apparentMagnitude(0.0, 1000.0, phase.toDouble()))
            assertTrue(value > previous)
            previous = value
        }
        assertNull(SatelliteBrightness.apparentMagnitude(0.0, 1000.0, 180.0))
    }

    @Test
    fun testPenumbraDimsTheSatellite() {
        val full = assertNotNull(SatelliteBrightness.apparentMagnitude(0.0, 1000.0, 90.0, 1.0))
        val half = assertNotNull(SatelliteBrightness.apparentMagnitude(0.0, 1000.0, 90.0, 0.5))
        assertEquals(2.5 * log10(2.0), half - full, 1e-12)
        assertNull(SatelliteBrightness.apparentMagnitude(0.0, 1000.0, 90.0, 0.0))
    }

    @Test
    fun testInvalidInputsGiveNoMagnitude() {
        assertNull(SatelliteBrightness.apparentMagnitude(Double.NaN, 1000.0, 90.0))
        assertNull(SatelliteBrightness.apparentMagnitude(0.0, Double.POSITIVE_INFINITY, 90.0))
        assertNull(SatelliteBrightness.apparentMagnitude(0.0, 0.5, 90.0))
        assertNull(SatelliteBrightness.apparentMagnitude(0.0, 1000.0, -1.0))
        assertNull(SatelliteBrightness.apparentMagnitude(0.0, 1000.0, 180.5))
        assertNull(SatelliteBrightness.apparentMagnitude(0.0, 1000.0, 90.0, 1.5))
        assertNull(SatelliteBrightness.apparentMagnitude(0.0, 1000.0, 90.0, Double.NaN))
    }

    @Test
    fun testKnownIntrinsicMagnitudes() {
        assertEquals(-1.8, SatelliteBrightness.issIntrinsicMagnitude, 0.0)
        assertEquals(-1.8, SatelliteBrightness.intrinsicMagnitude(25544))
        assertEquals(0.0, SatelliteBrightness.intrinsicMagnitude(48274))
        assertEquals(2.2, SatelliteBrightness.intrinsicMagnitude(20580))
        assertNull(SatelliteBrightness.intrinsicMagnitude(44714))
        assertNull(SatelliteBrightness.intrinsicMagnitude(-1))
        // A bright ISS pass: 450 km range, 60 degrees phase.
        val magnitude = assertNotNull(SatelliteBrightness.apparentMagnitude(-1.8, 450.0, 60.0))
        assertEquals(-4.24, magnitude, 0.01)
    }
}
