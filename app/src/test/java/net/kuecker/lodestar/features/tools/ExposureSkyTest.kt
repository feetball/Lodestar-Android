package net.kuecker.lodestar.features.tools

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.catalog.horizontal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExposureSkyTest {
    private val catalog: Catalog by lazy { Catalog.loadBundled() }
    private val observer = Observer(checkNotNull(GeographicLocation.validated(45.0, -100.0, 300.0)))
    private val date = Instant.parse("2026-10-18T04:00:00Z")

    @Test
    fun theMoonIsWorkedOutWithAndWithoutALocation() {
        val sky = ExposureSkyCalculator.compute(observer, null, date)
        assertTrue(sky.moonIlluminatedFraction in 0.0..1.0)
        assertEquals(0.52, sky.moonDiameterDegrees, 0.03)
        assertNotNull(sky.moonAltitude)
        assertNull(sky.targetAzimuth)
        assertNull(sky.targetAltitude)

        val withoutLocation = ExposureSkyCalculator.compute(null, catalog.objectWithID("planet.jupiter"), date)
        assertNull(withoutLocation.moonAltitude)
        assertNull(withoutLocation.targetAzimuth)
        assertNull(withoutLocation.targetAltitude)
        // The phase and the size hardly depend on the place.
        assertEquals(sky.moonIlluminatedFraction, withoutLocation.moonIlluminatedFraction, 0.01)
        assertEquals(sky.moonDiameterDegrees, withoutLocation.moonDiameterDegrees, 0.02)
    }

    @Test
    fun theTargetIsPlacedLikeTheRestOfTheApp() {
        val target = checkNotNull(catalog.objectWithID("dso.M31"))
        val sky = ExposureSkyCalculator.compute(observer, target, date)
        val expected = SkyFrame(date, observer).horizontal(target, true)
        assertEquals(expected.azimuth, assertNotNull(sky.targetAzimuth), 1e-9)
        assertEquals(expected.altitude, assertNotNull(sky.targetAltitude), 1e-9)
    }
}
