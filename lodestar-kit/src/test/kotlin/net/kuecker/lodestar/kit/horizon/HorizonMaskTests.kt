package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.horizon.HorizonTestSupport as Support
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HorizonMaskTests {
    private val mask = HorizonMask(Support.truthProfile)

    @Test
    fun testMaskAgreesWithTheProfileForRandomDirections() {
        val generator = Support.SeededGenerator(5_000uL)
        var below = 0
        var above = 0
        var skipped = 0
        repeat(20_000) {
            val vector = Vector3(
                generator.nextSigned(), generator.nextSigned(), 0.35 * generator.nextSigned(),
            ) * (0.5 + 100 * generator.nextDouble())
            val direction = HorizontalCoordinate(vector)
            val skyline = Support.truthProfile.altitude(direction.azimuth)
            // The table is a quarter of a degree fine; directions on the skyline itself may go either way.
            if (kotlin.math.abs(direction.altitude - skyline) < 0.01) {
                skipped += 1
                return@repeat
            }
            val expected = direction.altitude < skyline
            assertEquals(expected, mask.isBelow(vector), "$direction against $skyline")
            assertEquals(expected, mask.isBelow(vector.x, vector.y, vector.z))
            assertEquals(expected, mask.isBelow(direction.azimuth, direction.altitude))
            if (expected) below += 1 else above += 1
        }
        assertTrue(skipped < 100)
        assertTrue(below > 1_000 && above > 1_000, "$below below and $above above")
    }

    @Test
    fun testTheSkylineItselfIsNotBelow() {
        // Nodes: azimuth 90° at 30°, azimuth 190° at 12°.
        assertFalse(mask.isBelow(90.0, 30.0))
        assertTrue(mask.isBelow(90.0, 29.99))
        assertFalse(mask.isBelow(90.0, 30.01))
        assertFalse(mask.isBelow(190.0, 12.0))
        assertTrue(mask.isBelow(190.0, 11.9))
        assertTrue(mask.isBelow(HorizontalCoordinate(90.0, 29.5).unitVector))
        assertFalse(mask.isBelow(HorizontalCoordinate(90.0, 30.5).unitVector))
        // Between nodes the skyline is the line: at 100° it is 30 · (1 − 10/40) = 22.5°.
        assertTrue(mask.isBelow(HorizontalCoordinate(100.0, 22.3).unitVector))
        assertFalse(mask.isBelow(HorizontalCoordinate(100.0, 22.7).unitVector))
    }

    @Test
    fun testMaskIsIndependentOfTheLengthOfTheVector() {
        for (scale in listOf(1e-6, 0.3, 1.0, 5_000.0, 1e9)) {
            val inside = HorizontalCoordinate(90.0, 25.0).unitVector * scale
            val outside = HorizontalCoordinate(90.0, 35.0).unitVector * scale
            assertTrue(mask.isBelow(inside), "$scale")
            assertFalse(mask.isBelow(outside), "$scale")
        }
    }

    @Test
    fun testMaskWrapsThroughNorth() {
        val skyline = HorizonProfile.flat.setting(20.0, AzimuthRange(359.0, 0.0)).setting(10.0, AzimuthRange(0.0, 0.0))
        val wrapped = HorizonMask(skyline)
        // Between node 359 (20°) and node 0 (10°) the skyline is 15° at 359.5°.
        assertTrue(wrapped.isBelow(HorizontalCoordinate(359.5, 14.8).unitVector))
        assertFalse(wrapped.isBelow(HorizontalCoordinate(359.5, 15.2).unitVector))
        assertTrue(wrapped.isBelow(HorizontalCoordinate(-0.25, 12.3).unitVector)) // 359.75°: skyline 12.5°
        assertFalse(wrapped.isBelow(HorizontalCoordinate(-0.25, 12.7).unitVector))
        assertTrue(wrapped.isBelow(HorizontalCoordinate(0.0, 9.9).unitVector))
        assertFalse(wrapped.isBelow(HorizontalCoordinate(0.0, 10.1).unitVector))
    }

    @Test
    fun testFlatMaskHidesTheDirectionsBelowTheHorizon() {
        val flat = HorizonMask(HorizonProfile.flat)
        assertTrue(flat.isBelow(1.0, 0.0, -0.001))
        assertFalse(flat.isBelow(1.0, 0.0, 0.0))
        assertFalse(flat.isBelow(1.0, 0.0, 0.001))
        assertTrue(flat.isBelow(HorizontalCoordinate(200.0, -3.0).unitVector))
        assertFalse(flat.isBelow(HorizontalCoordinate(200.0, 3.0).unitVector))
        assertEquals(HorizonProfile.flat, flat.profile)
    }

    @Test
    fun testZenithNadirAndInvalidDirections() {
        assertFalse(mask.isBelow(0.0, 0.0, 1.0)) // the zenith is above any skyline
        assertTrue(mask.isBelow(0.0, 0.0, -1.0)) // and the nadir below
        assertFalse(mask.isBelow(0.0, 0.0, 0.0))
        assertFalse(mask.isBelow(Double.NaN, 0.0, 0.0))
        assertFalse(mask.isBelow(1.0, Double.NaN, -1.0))
        assertFalse(mask.isBelow(Double.POSITIVE_INFINITY, 0.0, -1.0))
        assertFalse(mask.isBelow(Vector3.zero))
        assertFalse(mask.isBelow(Double.NaN, 10.0))
        assertFalse(mask.isBelow(10.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun testMaskOfAConstantSkylineIsAnAltitudeTest() {
        val ridge = HorizonMask(HorizonProfile.constant(15.0))
        for (azimuth in listOf(0.0, 37.3, 90.0, 271.5, 359.9)) {
            assertTrue(ridge.isBelow(HorizontalCoordinate(azimuth, 14.9).unitVector), "$azimuth")
            assertFalse(ridge.isBelow(HorizontalCoordinate(azimuth, 15.1).unitVector), "$azimuth")
        }
    }

    @Test
    fun testTestingAFrameOfStarsIsFast() {
        val generator = Support.SeededGenerator(9uL)
        val vectors = DoubleArray(3 * 20_000)
        for (index in 0 until 20_000) {
            val direction = HorizontalCoordinate(360 * generator.nextDouble(), AstroMath.asinDeg(generator.nextDouble())).unitVector
            vectors[3 * index] = direction.x
            vectors[3 * index + 1] = direction.y
            vectors[3 * index + 2] = direction.z
        }
        var hidden = 0
        repeat(3) {
            hidden = 0
            for (index in 0 until 20_000) {
                if (mask.isBelow(vectors[3 * index], vectors[3 * index + 1], vectors[3 * index + 2])) hidden += 1
            }
        }
        val start = System.nanoTime()
        repeat(50) {
            for (index in 0 until 20_000) {
                if (mask.isBelow(vectors[3 * index], vectors[3 * index + 1], vectors[3 * index + 2])) hidden += 1
            }
        }
        val elapsed = (System.nanoTime() - start) / 1e9
        // A million tests, about fifty frames of twenty thousand stars each.
        assertTrue(elapsed < 1.5, "took $elapsed s")
        assertTrue(hidden > 0)
    }
}
