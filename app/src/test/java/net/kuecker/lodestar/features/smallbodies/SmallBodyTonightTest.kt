package net.kuecker.lodestar.features.smallbodies

import net.kuecker.lodestar.features.smallbodies.SmallBodyFixtures as Fixtures
import net.kuecker.lodestar.features.tonight.TonightCalculator
import net.kuecker.lodestar.features.tonight.TonightInput
import net.kuecker.lodestar.features.tonight.TonightNightChoice
import net.kuecker.lodestar.features.tonight.TonightNightKey
import net.kuecker.lodestar.features.tonight.TonightReport
import net.kuecker.lodestar.features.tonight.TonightTargetsOutcome
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmallBodyTonightTest {
    private val zone = ZoneId.of("America/Denver")

    /** The evening of 2026-10-17 local time: the night in which the opposition comet stands high for hours. */
    private val now = Instant.parse("2026-10-18T03:00:00Z")
    private val opposition = Fixtures.oppositionComet("C/2026 X1", "Opposition")
    private val faint = Fixtures.oppositionComet("C/2026 F1", "Faint", totalMagnitude = 14.0)

    private fun context(horizon: HorizonProfile? = null) =
        ObserverContext(Fixtures.observer, zone, "Ridge", LocationSource.Device, horizon = horizon)

    private fun report(catalog: Catalog?, horizon: HorizonProfile? = null): TonightReport = TonightCalculator.report(
        TonightInput(TonightNightKey(context(horizon), TonightNightChoice.Tonight), now, catalog),
    )

    private fun targets(report: TonightReport) = assertIs<TonightTargetsOutcome.Ranked>(report.targets).targets

    @Test
    fun aBrightCometGetsACardAndATarget() {
        val report = report(Fixtures.bundled.withSmallBodies(listOf(opposition)))
        val card = assertNotNull(report.smallBodies)
        assertEquals(listOf(opposition.id), card.map { it.id })
        val target = targets(report).first()
        assertEquals(opposition.id, target.id)
        assertTrue(target.peakAltitude >= TonightCalculator.minimumTargetAltitude, "${target.peakAltitude}")
        assertTrue(target.hoursAboveMinimum >= SmallBodyTonightPlanner.MINIMUM_HOURS, "${target.hoursAboveMinimum}")
        assertTrue(target.peakTime >= report.summary.night.start && target.peakTime <= report.summary.night.end)
        val note = assertNotNull(target.note)
        assertTrue(Regex("^Comet · mag ≈ \\d\\.\\d · \\d+° from the Sun$").matches(note), note)
    }

    @Test
    fun theDeepSkyTargetsAreNotChanged() {
        val plain = report(Fixtures.bundled)
        val withComet = report(Fixtures.bundled.withSmallBodies(listOf(opposition)))
        val plainTargets = targets(plain)
        val rest = targets(withComet).filter { it.note == null }
        assertEquals(plainTargets, rest)
        assertTrue(plainTargets.all { it.note == null })
        assertEquals(plain.summary, withComet.summary)
    }

    @Test
    fun theSmallBodiesComeFirst() {
        val list = targets(report(Fixtures.bundled.withSmallBodies(listOf(opposition))))
        val moving = list.takeWhile { it.note != null }
        assertEquals(1, moving.size)
        assertTrue(list.drop(1).all { it.note == null })
        assertTrue(list.size > 1)
    }

    @Test
    fun aFaintCometIsNeitherListedNorRanked() {
        val report = report(Fixtures.bundled.withSmallBodies(listOf(faint)))
        assertEquals(emptyList(), assertNotNull(report.smallBodies))
        assertTrue(targets(report).none { it.note != null })
    }

    @Test
    fun withoutSmallBodiesTheCardIsEmptyAndWithoutACatalogItIsLeftOut() {
        assertEquals(emptyList(), assertNotNull(report(Fixtures.bundled).smallBodies))
        assertNull(report(null).smallBodies)
    }

    @Test
    fun aSkylineThatHidesTheCometTakesItOutOfTheTargetsNotOffTheCard() {
        val catalog = Fixtures.bundled.withSmallBodies(listOf(opposition))
        val walled = report(catalog, HorizonProfile.constant(85.0))
        assertEquals(listOf(opposition.id), assertNotNull(walled.smallBodies).map { it.id })
        assertTrue(targets(walled).none { it.note != null })
        // A low skyline changes nothing.
        val open = targets(report(catalog)).first { it.note != null }
        val low = targets(report(catalog, HorizonProfile.constant(5.0))).first { it.note != null }
        assertEquals(open.id, low.id)
        assertTrue(low.hoursAboveMinimum <= open.hoursAboveMinimum + 1e-9)
    }

    @Test
    fun atMostThreeMovingTargetsAndThreeOnTheCard() {
        val crowd = (1..8).map { Fixtures.oppositionComet("C/2026 Y$it", "Crowd $it") }
        val report = report(Fixtures.bundled.withSmallBodies(crowd))
        assertEquals(SmallBodyTonightPlanner.CARD_COUNT, assertNotNull(report.smallBodies).size)
        assertEquals(SmallBodyTonightPlanner.TARGET_COUNT, targets(report).count { it.note != null })
    }

    @Test
    fun theCardListsCometsOnlyAndTheTargetsMayBeAsteroids() {
        val catalog = Fixtures.catalog(extra = listOf(opposition))
        val report = report(catalog)
        assertTrue(assertNotNull(report.smallBodies).all { it.obj.smallBody?.kind == net.kuecker.lodestar.kit.comets.SmallBodyKind.COMET })
        for (target in targets(report).filter { it.note != null }) {
            val magnitude = assertNotNull(SmallBodyInfo.compute(assertNotNull(target.obj.smallBody), target.peakTime)).magnitude
            assertTrue(assertNotNull(magnitude) <= SmallBodyTonightPlanner.TARGET_MAGNITUDE_LIMIT, target.id)
        }
    }

    @Test
    fun noDarknessMeansNoMovingTargetsButTheCardStays() {
        val summer = Instant.parse("2026-06-21T06:00:00Z")
        val polar = net.kuecker.lodestar.kit.foundation.Observer(
            checkNotNull(net.kuecker.lodestar.kit.foundation.GeographicLocation.validated(78.0, 15.0, 10.0)),
        )
        val context = ObserverContext(polar, ZoneId.of("Arctic/Longyearbyen"), "Svalbard", LocationSource.Device)
        val report = TonightCalculator.report(
            TonightInput(
                TonightNightKey(context, TonightNightChoice.Tonight), summer,
                Fixtures.bundled.withSmallBodies(listOf(opposition)),
            ),
        )
        assertIs<TonightTargetsOutcome.NoDarkness>(report.targets)
        assertNotNull(report.smallBodies)
    }
}
