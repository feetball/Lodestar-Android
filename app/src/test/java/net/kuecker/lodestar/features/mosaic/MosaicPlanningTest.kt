package net.kuecker.lodestar.features.mosaic

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.mosaic.MosaicOrientation
import net.kuecker.lodestar.kit.mosaic.MosaicWarning
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MosaicPlanningTest {
    private val observer = Observer(checkNotNull(GeographicLocation.validated(45.0, -100.0, 300.0)))
    private val date = Instant.parse("2026-07-15T04:00:00Z")
    private val catalog: Catalog by lazy { Catalog.loadBundled() }

    private fun lens(focalLength: Double) = LensSetup(SensorFormat.fullFrame, focalLength, 2.8, 4.3)

    private fun inputs(
        setup: MosaicSetup,
        target: CelestialObject? = null,
        camera: HorizontalCoordinate? = null,
        focalLength: Double = 50.0,
    ) = MosaicPlanningInputs(setup, target, lens(focalLength), observer, camera, date)

    @Test
    fun aTargetWithoutSizeOrPlaceCannotBeFitted() {
        assertEquals("Choose a target first.", MosaicPlanning.targetProblem(null))
        val custom = CelestialObject.custom("Spot", EquatorialCoordinate.fromRightAscensionHours(5.0, 10.0))
        assertTrue(assertNotNull(MosaicPlanning.targetProblem(custom)).contains("no known size"))
        val moon = assertNotNull(catalog.objectWithID("moon"))
        assertTrue(assertNotNull(MosaicPlanning.targetProblem(moon)).contains("moves across the sky"))
        val sirius = catalog.search("Sirius", 1).first().obj
        assertNotNull(MosaicPlanning.targetProblem(sirius))
    }

    @Test
    fun aLargeObjectIsFittedWithTheMargin() {
        val orion = assertNotNull(catalog.objectWithID("dso.M42"))
        assertNull(MosaicPlanning.targetProblem(orion))
        val narrow = MosaicPlanning.plan(inputs(MosaicSetup(MosaicSource.TARGET, margin = 0.0), orion, focalLength = 300.0))
        val wide = MosaicPlanning.plan(inputs(MosaicSetup(MosaicSource.TARGET, margin = 1.0), orion, focalLength = 300.0))
        assertIs<MosaicPlanOutcome.Ready>(narrow)
        assertIs<MosaicPlanOutcome.Ready>(wide)
        assertEquals(orion.name, narrow.title)
        assertTrue(wide.plan.panelCount >= narrow.plan.panelCount)
        // The region is the object's size plus the margin on each axis: four times the area at a margin of 100 %.
        val ratio = wide.plan.coverage.regionAreaSquareDegrees / narrow.plan.coverage.regionAreaSquareDegrees
        assertEquals(4.0, ratio, 0.05)
    }

    @Test
    fun theOrientationFollowsTheRecommendationUnlessChosen() {
        val setup = MosaicSetup(MosaicSource.CUSTOM, customWidth = 10.0, customHeight = 40.0)
        val target = CelestialObject.custom("Spot", EquatorialCoordinate.fromRightAscensionHours(5.0, 10.0))
        val automatic = MosaicPlanning.plan(inputs(setup, target, focalLength = 135.0))
        assertIs<MosaicPlanOutcome.Ready>(automatic)
        assertEquals(automatic.recommended, automatic.plan.orientation)
        val chosen = if (automatic.recommended == MosaicOrientation.PORTRAIT) MosaicOrientation.LANDSCAPE else MosaicOrientation.PORTRAIT
        val manual = MosaicPlanning.plan(inputs(setup.copy(orientation = chosen), target, focalLength = 135.0))
        assertIs<MosaicPlanOutcome.Ready>(manual)
        assertEquals(chosen, manual.plan.orientation)
        assertTrue(manual.plan.panelCount >= automatic.plan.panelCount)
    }

    @Test
    fun aCustomSizeCentresOnTheTargetOrWhereTheCameraPoints() {
        val setup = MosaicSetup(MosaicSource.CUSTOM, customWidth = 20.0, customHeight = 12.0)
        val target = CelestialObject.custom("Spot", EquatorialCoordinate.fromRightAscensionHours(5.0, 10.0))
        val around = MosaicPlanning.plan(inputs(setup, target, HorizontalCoordinate(90.0, 40.0)))
        assertIs<MosaicPlanOutcome.Ready>(around)
        assertTrue(around.title.endsWith("around Spot"))
        val pointed = MosaicPlanning.plan(inputs(setup, null, HorizontalCoordinate(90.0, 40.0)))
        assertIs<MosaicPlanOutcome.Ready>(pointed)
        assertTrue(pointed.title.endsWith("where the camera points"))
        assertIs<MosaicPlanOutcome.Problem>(MosaicPlanning.plan(inputs(setup)))
    }

    @Test
    fun impossibleSizesAreExplained() {
        val setup = MosaicSetup(MosaicSource.CUSTOM, customWidth = 400.0, customHeight = 12.0)
        val problem = MosaicPlanning.plan(inputs(setup, null, HorizontalCoordinate(90.0, 40.0)))
        assertIs<MosaicPlanOutcome.Problem>(problem)
        assertTrue(problem.message.contains("not possible"))
        val huge = MosaicSetup(MosaicSource.CUSTOM, customWidth = 360.0, customHeight = 180.0)
        val many = MosaicPlanning.plan(inputs(huge, null, HorizontalCoordinate(90.0, 40.0), focalLength = 300.0))
        assertIs<MosaicPlanOutcome.Problem>(many)
        assertTrue(many.message.contains("too large"))
    }

    @Test
    fun theMilkyWayArchIsPlannedAsAPanorama() {
        assertNull(MosaicPlanning.archProblem(observer, date))
        val outcome = MosaicPlanning.plan(inputs(MosaicSetup(MosaicSource.MILKY_WAY), focalLength = 24.0))
        assertIs<MosaicPlanOutcome.Ready>(outcome)
        assertEquals("Milky Way arch", outcome.title)
        assertTrue(outcome.plan.panelCount >= 3)
    }

    @Test
    fun warningsAreReadable() {
        assertTrue(MosaicPlanning.describe(MosaicWarning.TooManyPanels(120)).contains("120 panels"))
        assertTrue(MosaicPlanning.describe(MosaicWarning.OverlapNotMet(0.93)).contains("93 %"))
    }
}
