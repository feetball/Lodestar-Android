package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EarthShadowTests {
    private val sun = Vector3(149_597_870.7, 0.0, 0.0)

    /** A satellite at radius [radius] km, [angle] radians from the Sun direction in the equatorial plane. */
    private fun satellite(radius: Double, angle: Double): Vector3 = Vector3(radius * cos(angle), radius * sin(angle), 0.0)

    @Test
    fun testFullSunlightAndUmbra() {
        assertEquals(1.0, EarthShadow.illuminatedFraction(satellite(6778.0, 0.0), sun), 0.0)
        assertEquals(1.0, EarthShadow.illuminatedFraction(satellite(6778.0, PI / 2), sun), 0.0)
        assertEquals(0.0, EarthShadow.illuminatedFraction(satellite(6778.0, PI), sun), 0.0)
        assertEquals(0.0, EarthShadow.illuminatedFraction(satellite(42164.0, PI), sun), 0.0)
        assertEquals(1.0, EarthShadow.illuminatedFraction(satellite(42164.0, PI / 2), sun), 0.0)
        assertTrue(EarthShadow.isSunlit(satellite(6778.0, 0.3), sun))
        assertTrue(!EarthShadow.isSunlit(satellite(6778.0, PI - 0.1), sun))
        // Inside the Earth, or with the Sun at the satellite, the fraction is 0 or 1.
        assertEquals(0.0, EarthShadow.illuminatedFraction(Vector3(1000.0, 0.0, 0.0), sun), 0.0)
        assertEquals(0.0, EarthShadow.illuminatedFraction(Vector3.zero, sun), 0.0)
    }

    @Test
    fun testHalfIlluminationIsTheEdgeOfThePointSourceShadow() {
        // The satellite is on the tangent line from the centre of the Sun's disc to the Earth when the Earth's
        // limb passes through the Sun's centre. Find that angle by bisection and check its neighbours.
        for (radius in listOf(6778.0, 7500.0, 12_000.0, 42_164.0)) {
            var low = PI / 2
            var high = PI
            while (high - low > 1e-13) {
                val middle = 0.5 * (low + high)
                if (EarthShadow.illuminatedFraction(satellite(radius, middle), sun) >= 0.5) low = middle else high = middle
            }
            val edge = 0.5 * (low + high)
            // Geometry: the tangent point of the shadow cone is where sin(angle from the antisolar axis) = Re/r (Sun at infinity).
            val limb = PI - kotlin.math.asin(SatelliteFrames.earthEquatorialRadiusKilometers / radius)
            assertEquals(limb, edge, 1e-4 * (1 + 3000.0 / radius), "radius $radius")
            assertTrue(EarthShadow.isSunlit(satellite(radius, edge - 1e-6), sun))
            assertTrue(!EarthShadow.isSunlit(satellite(radius, edge + 1e-6), sun))
            // Across the penumbra the Sun's disc is a straight edge's worth of area: symmetric about the half point.
            val spread = 0.2 * PI / 180 * 6778.0 / radius
            val before = EarthShadow.illuminatedFraction(satellite(radius, edge - spread), sun)
            val after = EarthShadow.illuminatedFraction(satellite(radius, edge + spread), sun)
            assertEquals(1.0, before + after, 2e-3, "radius $radius")
            assertTrue(before in 0.5..1.0 && after in 0.0..0.5)
        }
    }

    @Test
    fun testTheFractionFallsMonotonicallyAcrossThePenumbra() {
        val radius = 6778.0
        val limb = PI - kotlin.math.asin(SatelliteFrames.earthEquatorialRadiusKilometers / radius)
        var previous = 1.0
        var steps = 0
        var angle = limb - 0.01
        while (angle < limb + 0.01) {
            val fraction = EarthShadow.illuminatedFraction(satellite(radius, angle), sun)
            assertTrue(fraction <= previous + 1e-12, "not monotonic at $angle")
            assertTrue(fraction in 0.0..1.0)
            if (fraction in 0.001..0.999) steps += 1
            previous = fraction
            angle += 1e-5
        }
        // The Sun's disc is 0.53 degrees across; seen from the orbit that is 0.0093 radians of orbital angle
        // times the ratio of the distances; the penumbra takes a few hundred of the 1e-5 radian steps.
        assertTrue(steps in 100..1500, "penumbra spans $steps steps")
        assertEquals(0.0, previous, 1e-12)
    }

    @Test
    fun testTheSunLargerThanTheEarthLeavesARing() {
        // Far from the Earth (here: 20 million km behind it) the Earth covers only part of the Sun's disc.
        val fraction = EarthShadow.illuminatedFraction(Vector3(-2.0e7, 0.0, 0.0), sun)
        val earth = kotlin.math.asin(SatelliteFrames.earthEquatorialRadiusKilometers / 2.0e7)
        val sunRadius = kotlin.math.asin(EarthShadow.sunRadiusKilometers / (sun.x + 2.0e7))
        assertEquals(1.0 - earth * earth / (sunRadius * sunRadius), fraction, 1e-6)
        assertTrue(fraction in 0.9..1.0)
    }

    @Test
    fun testShadowDoesNotDependOnTheFrame() {
        val a = satellite(7000.0, 2.9)
        val b = Vector3(a.x, a.z, a.y) // the same geometry in another orientation of the axes
        val sunB = Vector3(sun.x, sun.z, sun.y)
        assertEquals(EarthShadow.illuminatedFraction(a, sun), EarthShadow.illuminatedFraction(b, sunB), 1e-12)
        assertEquals(0.0, abs(EarthShadow.illuminatedFraction(a, sun) - EarthShadow.illuminatedFraction(b, sunB)), 1e-12)
        assertEquals(EarthShadow.isSunlit(a, sun), EarthShadow.illuminatedFraction(a, sun) >= EarthShadow.sunlitThreshold)
        assertEquals(0.5, EarthShadow.sunlitThreshold, 0.0)
        assertTrue(sin(0.0) == 0.0)
    }
}
