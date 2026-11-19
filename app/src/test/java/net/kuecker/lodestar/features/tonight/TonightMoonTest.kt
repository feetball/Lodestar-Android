package net.kuecker.lodestar.features.tonight

import net.kuecker.lodestar.features.objects.ObjectMoonGeometry
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.planning.ImagingFilter
import net.kuecker.lodestar.kit.planning.MoonImpact
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Moon in the Tonight ranking: each target is judged at its best time, through the user's filter. */
class TonightMoonTest {
    private val zone = ZoneId.of("America/Denver")
    private val observer = Observer(checkNotNull(GeographicLocation.validated(40.0, -105.3, 1650.0)))
    private val context = ObserverContext(observer, zone, "Ridge", LocationSource.Device)
    private val catalog: Catalog by lazy { Catalog.loadBundled() }

    /** The afternoon before a night with a nearly full Moon. */
    private val fullMoonNight = Instant.parse("2026-12-23T20:00:00Z")

    /** The afternoon before a night with a nearly new Moon. */
    private val newMoonNight = Instant.parse("2026-12-08T20:00:00Z")

    private fun report(now: Instant, filter: ImagingFilter) = TonightCalculator.report(
        TonightInput(TonightNightKey(context, TonightNightChoice.Tonight), now, catalog, filter),
    )

    private fun targets(report: TonightReport): List<TonightTarget> =
        (report.targets as TonightTargetsOutcome.Ranked).targets.filter { it.note == null }

    private fun isEmissionLine(target: TonightTarget) = MoonImpact.isEmissionLineTarget(target.obj)

    @Test
    fun theNightsUnderTestHaveTheMoonsPhaseThatTheyAreMeantFor() {
        assertTrue(report(fullMoonNight, ImagingFilter.NONE).summary.moon.illuminatedFraction > 0.9)
        assertTrue(report(newMoonNight, ImagingFilter.NONE).summary.moon.illuminatedFraction < 0.1)
    }

    @Test
    fun everyRankedTargetCarriesTheAssessmentOfItsBestTimeThroughTheFilter() {
        for (filter in listOf(ImagingFilter.NONE, ImagingFilter.NARROWBAND)) {
            val ranked = targets(report(fullMoonNight, filter))
            assertEquals(TonightCalculator.targetLimit, ranked.size)
            for (target in ranked) {
                val geometry = ObjectMoonGeometry.of(target.obj, observer, target.peakTime)
                assertEquals(geometry.assess(filter, MoonImpact.assessmentKind(target.obj)), assertNotNull(target.moon), target.id)
            }
        }
    }

    @Test
    fun cometsAndAsteroidsAreNotJudgedByTheMoon() {
        val plain = report(fullMoonNight, ImagingFilter.NONE)
        for (target in (plain.targets as TonightTargetsOutcome.Ranked).targets.filter { it.note != null }) {
            assertNull(target.moon, target.id)
        }
    }

    @Test
    fun aFilterOnlyChangesWhatTheMoonDoesToEmissionLineTargets() {
        val broadband = targets(report(fullMoonNight, ImagingFilter.NONE)).associateBy { it.id }
        val narrowband = targets(report(fullMoonNight, ImagingFilter.NARROWBAND))
        var compared = 0
        for (target in narrowband) {
            val before = broadband[target.id] ?: continue
            compared += 1
            val moon = assertNotNull(target.moon)
            assertEquals(assertNotNull(before.moon).broadbandLevel, moon.broadbandLevel, target.id)
            if (isEmissionLine(target)) {
                assertTrue(moon.level.ordinal <= moon.broadbandLevel.ordinal, target.id)
            } else {
                assertEquals(before.moon, target.moon, target.id)
            }
        }
        assertTrue(compared > 0)
    }

    @Test
    fun aFilterLiftsEmissionNebulaeInTheRankingUnderABrightMoon() {
        val broadband = targets(report(fullMoonNight, ImagingFilter.NONE))
        val narrowband = targets(report(fullMoonNight, ImagingFilter.NARROWBAND))
        assertTrue(narrowband.count(::isEmissionLine) > broadband.count(::isEmissionLine))
        // And the targets the filter relaxed say so.
        assertTrue(narrowband.any { it.moon?.isRelaxed == true })
        assertTrue(broadband.none { it.moon?.isRelaxed == true })
    }

    @Test
    fun withoutMoonlightAFilterChangesNothing() {
        val broadband = targets(report(newMoonNight, ImagingFilter.NONE))
        val narrowband = targets(report(newMoonNight, ImagingFilter.NARROWBAND))
        assertEquals(broadband.map { it.id }, narrowband.map { it.id })
        for (target in broadband + narrowband) {
            val level = assertNotNull(target.moon).level
            assertTrue(level == MoonImpact.Level.MOON_DOWN || level == MoonImpact.Level.MINOR, "${target.id} $level")
        }
    }
}
