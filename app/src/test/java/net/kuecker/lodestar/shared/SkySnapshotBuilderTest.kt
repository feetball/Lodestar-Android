package net.kuecker.lodestar.shared

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.Prominence
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkySnapshotBuilderTest {
    private val catalog: Catalog by lazy { Catalog.loadBundled() }
    private val observer = Observer(checkNotNull(GeographicLocation.validated(45.0, -100.0, 300.0)))
    private val date = Instant.parse("2026-10-18T04:00:00Z")

    private fun build(prominence: Prominence, horizon: HorizonProfile? = null): SkySnapshot =
        SkySnapshotBuilder.build(
            catalog, observer, date,
            SkySnapshotOptions(deepSkyProminence = prominence, includeConstellations = false, includeMilkyWay = false),
            target = null, horizon = horizon,
        )

    @Test
    fun deepSkyObjectsFollowTheProminenceLevel() {
        val counts = Prominence.entries.map { build(it).deepSky.size }
        // Every level adds objects.
        assertTrue(counts.zipWithNext().all { (fewer, more) -> more > fewer }, "$counts")
        // The showpieces include Messier objects; the Galactic Center is a showpiece too.
        val showpieces = build(Prominence.SHOWPIECE).deepSky
        assertTrue(showpieces.any { it.id == "dso.M31" })
        assertTrue(showpieces.any { it.id == "special.galacticCenter" })
        for (item in showpieces) assertEquals(Prominence.SHOWPIECE, item.prominence, item.id)
        // Nothing is cut by magnitude: dark nebulae without one are in, at the levels that rank them.
        val everything = build(Prominence.OBSCURE).deepSky
        assertTrue(everything.any { it.obj.magnitude == null })
        assertTrue(everything.size > 2000)
        assertEquals(everything.size, everything.map { it.id }.toSet().size)
    }

    @Test
    fun theDeepSkyOfALowerLevelIsContainedInTheHigherOne() {
        val prominent = build(Prominence.PROMINENT).deepSky.map { it.id }.toSet()
        val notable = build(Prominence.NOTABLE).deepSky.map { it.id }.toSet()
        assertTrue(notable.containsAll(prominent))
    }

    @Test
    fun withoutASkylineNothingIsHidden() {
        for (snapshot in listOf(build(Prominence.NOTABLE), build(Prominence.NOTABLE, HorizonProfile.flat))) {
            assertNull(snapshot.horizon?.takeUnless { it.isFlat })
            assertFalse(snapshot.stars.any { it.isBehindSkyline })
            assertFalse(snapshot.deepSky.any { it.isBehindSkyline })
            assertFalse(snapshot.bodies.any { it.isBehindSkyline })
        }
    }

    @Test
    fun theSkylineHidesWhatIsBelowIt() {
        val profile = HorizonProfile.constant(30.0)
        val snapshot = build(Prominence.NOTABLE, profile)
        assertEquals(profile, snapshot.horizon)
        var hidden = 0
        var shown = 0
        for (star in snapshot.stars) {
            val altitude = AstroMath.asinDeg(star.direction.z)
            if (abs(altitude - 30.0) < 0.01) continue
            assertEquals(altitude < 30.0, star.isBehindSkyline, "star ${star.id} at $altitude")
            if (star.isBehindSkyline) hidden += 1 else shown += 1
        }
        assertTrue(hidden > 100 && shown > 100, "hidden $hidden shown $shown")
        for (item in snapshot.deepSky) {
            val altitude = AstroMath.asinDeg(item.direction.z)
            if (abs(altitude - 30.0) < 0.01) continue
            assertEquals(altitude < 30.0, item.isBehindSkyline, "object ${item.id}")
        }
        for (body in snapshot.bodies) {
            val altitude = AstroMath.asinDeg(body.direction.z)
            if (abs(altitude - 30.0) < 0.01) continue
            assertEquals(altitude < 30.0, body.isBehindSkyline, "body ${body.id}")
        }
    }

    @Test
    fun theSkylineIsDescribedForAccessibility() {
        val profile = checkNotNull(
            HorizonProfile.create(List(360) { degree -> if (degree in 80..100) 32.0 else 3.0 }),
        )
        val text = SkylineSummary.describe(profile)
        assertTrue(text.contains("32 degrees"), text)
        assertTrue(text.contains("east"), text)
        assertNotNull(SkylineSummary.describe(HorizonProfile.constant(0.5)))
        assertTrue(SkylineSummary.describe(HorizonProfile.constant(0.5)).contains("below one degree"))
    }
}
