package net.kuecker.lodestar.features.objects

import net.kuecker.lodestar.kit.catalog.AngularSize
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObjectDetailBodySizeTest {
    private val catalog: Catalog by lazy { Catalog.loadBundled() }
    private fun lens(pitch: Double?) = LensSetup(SensorFormat.fullFrame, 200.0, 2.8, pitch)
    private fun angularSize(major: Double, minor: Double): AngularSize = checkNotNull(AngularSize.create(major, minor))

    @Test
    fun aPlanetsDiscIsCountedInPixels() {
        val jupiter = checkNotNull(catalog.objectWithID("planet.jupiter"))
        // 46 arcseconds across: 0.7667 arcminutes.
        val size = angularSize(46.0 / 60, 46.0 / 60)
        val photography = ObjectDetailCalculator.photography(jupiter, size, 0.0, lens(4.0))
        val body = assertNotNull(photography.bodySize)
        assertEquals(46.0 / 3600, body.diameterDegrees, 1e-9)
        assertEquals(11.15, body.pixels, 0.01)
        assertFalse(body.assumedPixelPitch)
    }

    @Test
    fun theMoonAndTheSunHaveASizeToo() {
        val moon = checkNotNull(catalog.objectWithID("moon"))
        val size = angularSize(31.0, 31.0)
        val body = assertNotNull(ObjectDetailCalculator.photography(moon, size, 0.0, lens(4.0)).bodySize)
        assertEquals(31.0 * 60 / 4.1253, body.pixels, 0.5)
        val sun = checkNotNull(catalog.objectWithID("sun"))
        assertNotNull(ObjectDetailCalculator.photography(sun, size, 0.0, lens(4.0)).bodySize)
    }

    @Test
    fun theDefaultPixelPitchIsFlaggedWhenTheLensHasNone() {
        val jupiter = checkNotNull(catalog.objectWithID("planet.jupiter"))
        val size = angularSize(46.0 / 60, 46.0 / 60)
        val known = assertNotNull(ObjectDetailCalculator.photography(jupiter, size, 0.0, lens(4.0)).bodySize)
        val assumed = assertNotNull(ObjectDetailCalculator.photography(jupiter, size, 0.0, lens(null)).bodySize)
        assertTrue(assumed.assumedPixelPitch)
        assertEquals(known.pixels, assumed.pixels, 1e-9)
    }

    @Test
    fun deepSkyObjectsAndObjectsWithoutASizeHaveNone() {
        val andromeda = checkNotNull(catalog.objectWithID("dso.M31"))
        val size = angularSize(190.0, 60.0)
        assertNull(ObjectDetailCalculator.photography(andromeda, size, 41.0, lens(4.0)).bodySize)
        val jupiter = checkNotNull(catalog.objectWithID("planet.jupiter"))
        assertNull(ObjectDetailCalculator.photography(jupiter, null, 0.0, lens(4.0)).bodySize)
    }
}
