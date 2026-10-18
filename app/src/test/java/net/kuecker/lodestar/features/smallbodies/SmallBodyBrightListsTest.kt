package net.kuecker.lodestar.features.smallbodies

import net.kuecker.lodestar.features.smallbodies.SmallBodyFixtures as Fixtures
import net.kuecker.lodestar.kit.comets.SmallBodyKind
import net.kuecker.lodestar.kit.comets.SmallBodySelection
import net.kuecker.lodestar.kit.foundation.DateInterval
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmallBodyBrightListsTest {
    private val bright = Fixtures.oppositionComet("C/2026 X1", "Opposition")
    private val catalog = Fixtures.catalog(extra = listOf(bright))
    private val bodies = SmallBodyBrightLists.bodies(catalog)
    private val date = Fixtures.date

    private fun ids(period: SmallBodyListPeriod, limit: Double, kind: SmallBodyListKind) =
        SmallBodyBrightLists.select(bodies, date, period, limit, kind).map { it.body.id }

    @Test
    fun theListsChooseFromTheCatalogsComets() {
        val expected = (Fixtures.comets + Fixtures.asteroids + bright).map { it.id }.toSet()
        assertEquals(expected, bodies.map { it.id }.toSet())
        assertTrue(SmallBodyBrightLists.bodies(Fixtures.bundled).isEmpty())
    }

    @Test
    fun theNowListIsTheEnginesSelection() {
        for (limit in SmallBodyMagnitudeLimit.entries.map { it.value }) {
            val expected = SmallBodySelection.brightNow(bodies, date, limit).map { it.body.id }
            assertEquals(expected, ids(SmallBodyListPeriod.NOW, limit, SmallBodyListKind.BOTH), "limit $limit")
        }
    }

    @Test
    fun theMonthListIsTheEnginesSelectionOverThirtyOneDays() {
        val month = DateInterval(date, 31 * 86_400.0)
        for (limit in SmallBodyMagnitudeLimit.entries.map { it.value }) {
            val expected = SmallBodySelection.brightest(bodies, month, limit).map { it.body.id }
            assertEquals(expected, ids(SmallBodyListPeriod.THIS_MONTH, limit, SmallBodyListKind.BOTH), "limit $limit")
        }
    }

    @Test
    fun aBrighterLimitNarrowsTheList() {
        for (period in SmallBodyListPeriod.entries) {
            val six = ids(period, 6.0, SmallBodyListKind.BOTH)
            val eight = ids(period, 8.0, SmallBodyListKind.BOTH)
            val ten = ids(period, 10.0, SmallBodyListKind.BOTH)
            assertTrue(eight.containsAll(six) && ten.containsAll(eight), "$period $six $eight $ten")
            assertTrue(six.contains(bright.id), "the opposition comet is about magnitude 5.7: $six")
        }
    }

    @Test
    fun theListIsBrightestFirst() {
        for (period in SmallBodyListPeriod.entries) {
            val list = SmallBodyBrightLists.select(bodies, date, period, 10.0, SmallBodyListKind.BOTH)
            val magnitudes = list.map { it.peakMagnitude }
            assertEquals(magnitudes.sorted(), magnitudes, "$period")
        }
    }

    @Test
    fun aKindNarrowsTheList() {
        val comets = SmallBodyBrightLists.select(bodies, date, SmallBodyListPeriod.THIS_MONTH, 10.0, SmallBodyListKind.COMETS)
        val asteroids = SmallBodyBrightLists.select(bodies, date, SmallBodyListPeriod.THIS_MONTH, 10.0, SmallBodyListKind.ASTEROIDS)
        val both = SmallBodyBrightLists.select(bodies, date, SmallBodyListPeriod.THIS_MONTH, 10.0, SmallBodyListKind.BOTH)
        assertTrue(comets.isNotEmpty() && comets.all { it.body.kind == SmallBodyKind.COMET })
        assertTrue(asteroids.all { it.body.kind == SmallBodyKind.ASTEROID })
        assertEquals(both.map { it.body.id }.toSet(), (comets + asteroids).map { it.body.id }.toSet())
    }

    @Test
    fun theMonthListHoldsEverythingBrightNowAndSaysWhenItPeaks() {
        val now = ids(SmallBodyListPeriod.NOW, 10.0, SmallBodyListKind.BOTH)
        val month = SmallBodyBrightLists.select(bodies, date, SmallBodyListPeriod.THIS_MONTH, 10.0, SmallBodyListKind.BOTH)
        assertTrue(month.map { it.body.id }.containsAll(now))
        val end = date.plus(Duration.ofDays(31))
        for (entry in month) {
            assertTrue(entry.peakDate >= date.minusSeconds(1) && entry.peakDate <= end.plusSeconds(1), entry.body.id)
            assertTrue(entry.peakMagnitude <= 10.0, entry.body.id)
            assertTrue(entry.peak.elongation >= 25.0, entry.body.id)
        }
    }

    @Test
    fun theEntriesCarryTheCatalogObjectsAndTheirNumbers() {
        val entries = SmallBodyBrightLists.entries(catalog, date, SmallBodyListPeriod.NOW, 10.0, SmallBodyListKind.COMETS)
        val first = entries.first()
        assertEquals(bright.id, first.id, "the brightest comet comes first")
        assertEquals(assertNotNull(catalog.objectWithID(bright.id)), first.obj)
        assertEquals(first.bright.peak.apparentMagnitude, first.info.magnitude)
        assertEquals(first.bright.peak.elongation, first.info.elongation)
        assertTrue(entries.size <= SmallBodyBrightLists.MAXIMUM_COUNT)
        assertEquals(2, SmallBodyBrightLists.entries(catalog, date, SmallBodyListPeriod.THIS_MONTH, 10.0, SmallBodyListKind.BOTH, 2).size)
    }

    @Test
    fun nothingBrightIsAnEmptyListNotAnError() {
        assertTrue(SmallBodyBrightLists.select(bodies, date, SmallBodyListPeriod.NOW, -10.0, SmallBodyListKind.BOTH).isEmpty())
        assertTrue(SmallBodyBrightLists.select(emptyList(), date, SmallBodyListPeriod.THIS_MONTH, 10.0, SmallBodyListKind.BOTH).isEmpty())
        assertEquals(
            "No comet is brighter than magnitude 10 right now. That is normal; bright comets are rare.",
            SmallBodyBrightLists.emptyMessage(SmallBodyListPeriod.NOW, 10.0, SmallBodyListKind.COMETS),
        )
        assertEquals(
            "No comet or asteroid is brighter than magnitude 6 in the next 31 days. That is normal; bright comets are rare.",
            SmallBodyBrightLists.emptyMessage(SmallBodyListPeriod.THIS_MONTH, 6.0, SmallBodyListKind.BOTH),
        )
        assertEquals(
            "No asteroid is brighter than magnitude 8 right now.",
            SmallBodyBrightLists.emptyMessage(SmallBodyListPeriod.NOW, 8.0, SmallBodyListKind.ASTEROIDS),
        )
    }

    @Test
    fun theLimitsAreSixEightAndTenWithTenAsTheDefault() {
        assertEquals(listOf(6.0, 8.0, 10.0), SmallBodyMagnitudeLimit.entries.map { it.value })
        assertEquals(10.0, SmallBodyMagnitudeLimit.default.value)
        assertEquals(listOf("Now", "This month"), SmallBodyListPeriod.entries.map { it.title })
        assertEquals(listOf("Comets", "Asteroids", "Both"), SmallBodyListKind.entries.map { it.title })
    }
}
