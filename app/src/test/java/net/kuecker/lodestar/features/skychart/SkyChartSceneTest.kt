package net.kuecker.lodestar.features.skychart

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.Prominence
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.mosaic.MosaicOrientation
import net.kuecker.lodestar.kit.mosaic.MosaicPlanner
import net.kuecker.lodestar.kit.mosaic.MosaicRegion
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.shared.SkySnapshotBuilder
import net.kuecker.lodestar.shared.SkySnapshotOptions
import java.time.Instant
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkyChartSceneTest {
    private val catalog: Catalog by lazy { Catalog.loadBundled() }
    private val observer = Observer(checkNotNull(GeographicLocation.validated(45.0, -100.0, 300.0)))
    private val date = Instant.parse("2026-10-18T04:00:00Z")

    private fun snapshot(prominence: Prominence, horizon: HorizonProfile? = null) = SkySnapshotBuilder.build(
        catalog, observer, date, SkySnapshotOptions(deepSkyProminence = prominence), target = null, horizon = horizon,
    )

    @Test
    fun theSceneHoldsTheDeepSkyObjectsAboveTheHorizonUpToTheLevel() {
        val showpieces = SkyChartSceneBuilder.build(snapshot(Prominence.SHOWPIECE)).deepSky
        val everything = SkyChartSceneBuilder.build(snapshot(Prominence.OBSCURE)).deepSky
        assertTrue(showpieces.isNotEmpty())
        assertTrue(everything.size > 3 * showpieces.size)
        assertTrue(showpieces.all { it.prominence == 1 })
        assertTrue(everything.map { it.prominence }.toSet().containsAll(listOf(1, 2, 3, 4)))
        // The most prominent come first.
        assertEquals(everything.map { it.prominence }, everything.map { it.prominence }.sorted())
    }

    @Test
    fun anOpenHorizonHasNoSkyline() {
        val scene = SkyChartSceneBuilder.build(snapshot(Prominence.PROMINENT))
        assertNull(scene.skyline)
        assertNull(scene.mosaic)
        assertTrue(scene.bodies.none { it.isBehindSkyline })
    }

    @Test
    fun theSkylineHidesStarsObjectsAndLabelsBehindIt() {
        val open = SkyChartSceneBuilder.build(snapshot(Prominence.NOTABLE))
        val profile = HorizonProfile.constant(25.0)
        val hidden = SkyChartSceneBuilder.build(snapshot(Prominence.NOTABLE, profile))
        val skyline = assertNotNull(hidden.skyline)
        assertEquals(profile, skyline.profile)
        val radius = AstroMath.tanDeg((90 - 25.0) / 2)
        assertEquals(360, skyline.radii.size)
        assertTrue(skyline.radii.all { kotlin.math.abs(it - radius) < 1e-9 })
        assertTrue(hidden.deepSky.size < open.deepSky.size)
        assertTrue(hidden.pickables.size < open.pickables.size)
        assertTrue(hidden.labels.size < open.labels.size)
        // Everything that is left lies inside the skyline's circle of the chart.
        for (item in hidden.deepSky) assertTrue(hypot(item.x, item.y) < radius + 1e-6)
        for (group in hidden.starGroups) {
            for (index in group.points.indices step 2) assertTrue(hypot(group.points[index], group.points[index + 1]) < radius + 1e-6)
        }
    }

    @Test
    fun aSkylineBelowTheHorizonDoesNotReachIntoTheChart() {
        val scene = SkyChartSceneBuilder.build(snapshot(Prominence.PROMINENT, HorizonProfile.constant(-5.0)))
        assertTrue(assertNotNull(scene.skyline).radii.all { it == 1.0 })
    }

    @Test
    fun theMosaicIsPlacedInTheSkyOfTheScene() {
        val plan = MosaicPlanner.plan(
            MosaicRegion.centered(180.0, 40.0, 60.0, 30.0),
            LensSetup(SensorFormat.fullFrame, 85.0, 2.8, 4.3),
            MosaicOrientation.LANDSCAPE, 0.25, 45.0,
        )
        val scene = SkyChartSceneBuilder.build(snapshot(Prominence.PROMINENT), plan)
        val mosaic = assertNotNull(scene.mosaic)
        assertTrue(mosaic.plan === plan)
        assertEquals(plan.panels.size, mosaic.panelCount)
        assertEquals(2 * plan.panels.size, mosaic.centers.size)
        assertTrue(mosaic.region.isNotEmpty())
        for (index in 0 until mosaic.panelCount) {
            // The panels are high in the south: all above the horizon, with an outline on the chart.
            assertFalse(mosaic.centers[2 * index].isNaN(), "panel $index")
            assertTrue(hypot(mosaic.centers[2 * index], mosaic.centers[2 * index + 1]) < 1f)
            assertTrue(mosaic.outlines[index].isNotEmpty())
            assertEquals((plan.panels[index].shootingPosition + 1).toString(), mosaic.numbers[index])
        }
    }

    @Test
    fun aPanelBelowTheHorizonHasNoCentreOnTheChart() {
        val plan = MosaicPlanner.plan(
            MosaicRegion.centered(180.0, -30.0, 20.0, 20.0),
            LensSetup(SensorFormat.fullFrame, 85.0, 2.8, 4.3),
            MosaicOrientation.LANDSCAPE, 0.25, 45.0,
        )
        val mosaic = assertNotNull(SkyChartSceneBuilder.build(snapshot(Prominence.SHOWPIECE), plan).mosaic)
        assertTrue(mosaic.centers.all { it.isNaN() })
        assertTrue(mosaic.outlines.all { it.isEmpty() })
    }
}
