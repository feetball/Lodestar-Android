package net.kuecker.lodestar.kit.skyquality

import net.kuecker.lodestar.kit.planning.PlanningTestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkyQualityScoreTests {
    /** An estimate of the given class (a glow index at the start of its band). */
    private fun bortle(bortleClass: Int): BortleEstimate {
        val estimate = BortleEstimate(if (bortleClass == 1) 0.0 else BortleEstimate.glowThresholds[bortleClass - 2], 0)
        assertEquals(bortleClass, estimate.bortleClass)
        return estimate
    }

    private fun score(
        dark: Double = 9.0,
        moonFree: Double = 9.0,
        lit: Double = 0.0,
        bortleClass: Int = 1,
        clouds: Double? = 0.0,
    ): ImagingScore = SkyQualityScore.imaging(
        astronomicalDarkHours = dark,
        moonFreeDarkHours = moonFree,
        moonIlluminatedFraction = lit,
        bortle = bortle(bortleClass),
        cloudFraction = clouds,
    )

    @Test
    fun testTheIdealNightScoresTenAndTheWorstZero() {
        val ideal = score()
        assertEquals(10.0, ideal.total, 1e-12)
        assertEquals(ImagingRating.EXCELLENT, ideal.rating)
        val worst = score(dark = 0.0, moonFree = 0.0, lit = 1.0, bortleClass = 9, clouds = 1.0)
        assertEquals(0.0, worst.total, 1e-12)
        assertEquals(ImagingRating.POOR, worst.rating)
        assertEquals(0.0, score(dark = 0.0, moonFree = 0.0, bortleClass = 9, clouds = 1.0).total, 1e-12)
    }

    @Test
    fun testComponentsSumToTheTotalAndTheirWeightsToTen() {
        for (clouds in listOf(null, 0.0, 0.4, 1.0)) {
            val result = score(dark = 4.5, moonFree = 3.0, lit = 0.6, bortleClass = 5, clouds = clouds)
            assertEquals(result.total, result.components.sumOf { it.points }, 1e-12)
            assertEquals(10.0, result.components.sumOf { it.weight }, 1e-12)
            for (part in result.components) {
                assertEquals(part.weight * part.quality, part.points, 1e-12)
                assertTrue(part.quality in 0.0..1.0 && part.points in 0.0..part.weight + 1e-12)
            }
            assertEquals(
                listOf(
                    ImagingScoreComponentKind.DARKNESS_HOURS, ImagingScoreComponentKind.MOON,
                    ImagingScoreComponentKind.SKY_BRIGHTNESS,
                ) + (if (clouds == null) emptyList() else listOf(ImagingScoreComponentKind.CLOUDS)),
                result.components.map { it.kind },
            )
        }
    }

    @Test
    fun testKnownNightWithAForecast() {
        // 4.5 h dark: 0.75; the Moon is up 1.5 of those hours at 60 % lit: 1 - (1/3) * 0.6 = 0.8; Bortle 5: 0.5; clouds 25 %: 0.75.
        val result = score(dark = 4.5, moonFree = 3.0, lit = 0.6, bortleClass = 5, clouds = 0.25)
        assertEquals(listOf(2.0, 3.0, 2.0, 3.0), result.components.map { it.weight })
        for ((part, expected) in result.components.zip(listOf(0.75, 0.8, 0.5, 0.75))) {
            assertEquals(expected, part.quality, 1e-12, part.kind.rawValue)
        }
        for ((part, expected) in result.components.zip(listOf(1.5, 2.4, 1.0, 2.25))) {
            assertEquals(expected, part.points, 1e-12, part.kind.rawValue)
        }
        assertEquals(7.15, result.total, 1e-12)
        assertTrue(result.includesClouds)
        assertEquals(ImagingRating.GOOD, result.rating)
        assertEquals(0.75, assertNotNull(result.component(ImagingScoreComponentKind.CLOUDS)).quality, 1e-12)
    }

    @Test
    fun testKnownNightWithoutAForecastScalesTheWeightsToTen() {
        val result = score(dark = 4.5, moonFree = 3.0, lit = 0.6, bortleClass = 5, clouds = null)
        assertFalse(result.includesClouds)
        assertNull(result.component(ImagingScoreComponentKind.CLOUDS))
        for ((part, expected) in result.components.zip(listOf(20.0 / 7, 30.0 / 7, 20.0 / 7))) {
            assertEquals(expected, part.weight, 1e-12, part.kind.rawValue)
        }
        assertEquals(7.0, result.total, 1e-12)
        // A forecast that is not a number is no forecast.
        assertEquals(result, score(dark = 4.5, moonFree = 3.0, lit = 0.6, bortleClass = 5, clouds = Double.NaN))
        // A forecast outside 0-1 is limited to it.
        assertEquals(score(clouds = 1.0), score(clouds = 7.0))
        assertEquals(score(clouds = 0.0), score(clouds = -3.0))
    }

    @Test
    fun testEachPartMovesTheScoreTheRightWay() {
        assertTrue(score(clouds = 0.2).total > score(clouds = 0.6).total)
        for (bortleClass in 1 until 9) assertTrue(score(bortleClass = bortleClass).total > score(bortleClass = bortleClass + 1).total)
        assertTrue(score(lit = 0.1, moonFree = 4.0).total > score(lit = 0.9, moonFree = 4.0).total)
        assertTrue(score(lit = 0.8, moonFree = 8.0).total > score(lit = 0.8, moonFree = 2.0).total)
        assertTrue(score(dark = 3.0, moonFree = 3.0).total < score(dark = 5.0, moonFree = 5.0).total)
        // Six hours is enough: more earns nothing.
        assertEquals(score(dark = 6.0, moonFree = 6.0).total, score(dark = 12.0, moonFree = 12.0).total, 1e-12)
        // A bright Moon that is up for an hour of a long night costs little; a new Moon costs nothing.
        assertTrue(score(dark = 9.0, moonFree = 8.0, lit = 1.0).total > 9.0)
        assertEquals(10.0, score(dark = 9.0, moonFree = 0.0, lit = 0.0).total, 1e-12)
    }

    @Test
    fun testNoDarknessMeansNoDarknessAndNoMoonCredit() {
        val result = score(dark = 0.0, moonFree = 0.0, lit = 0.0, bortleClass = 1, clouds = 0.0)
        assertEquals(0.0, assertNotNull(result.component(ImagingScoreComponentKind.DARKNESS_HOURS)).quality)
        assertEquals(0.0, assertNotNull(result.component(ImagingScoreComponentKind.MOON)).quality)
        assertEquals(5.0, result.total, 1e-12)
    }

    @Test
    fun testInputsThatAreOutOfRangeAreLimited() {
        assertEquals(score(dark = 0.0, moonFree = 0.0), score(dark = -4.0, moonFree = 2.0))
        assertEquals(score(dark = 0.0, moonFree = 0.0), score(dark = Double.NaN, moonFree = 2.0))
        assertEquals(score(dark = 5.0, moonFree = 5.0), score(dark = 5.0, moonFree = 9.0))
        assertEquals(score(dark = 5.0, moonFree = 0.0), score(dark = 5.0, moonFree = Double.NaN))
        assertEquals(score(lit = 1.0, moonFree = 3.0), score(lit = 4.0, moonFree = 3.0))
        assertEquals(score(lit = 0.0, moonFree = 3.0), score(lit = Double.NaN, moonFree = 3.0))
    }

    @Test
    fun testRatingsHaveTheirBoundaries() {
        assertEquals(ImagingRating.POOR, ImagingRating(2.99))
        assertEquals(ImagingRating.FAIR, ImagingRating(3.0))
        assertEquals(ImagingRating.FAIR, ImagingRating(5.49))
        assertEquals(ImagingRating.GOOD, ImagingRating(5.5))
        assertEquals(ImagingRating.GOOD, ImagingRating(7.99))
        assertEquals(ImagingRating.EXCELLENT, ImagingRating(8.0))
        assertEquals(ImagingRating.EXCELLENT, ImagingRating(10.0))
        assertEquals(ImagingRating.POOR, ImagingRating(Double.NaN))
        for (kind in ImagingScoreComponentKind.entries) {
            assertEquals(kind, ImagingScoreComponentKind.fromRawValue(kind.rawValue))
        }
        for (rating in ImagingRating.entries) assertEquals(rating, ImagingRating.fromRawValue(rating.rawValue))
        assertNull(ImagingScoreComponentKind.fromRawValue("sky"))
        assertNull(ImagingRating.fromRawValue("great"))
    }

    @Test
    fun testScoreOfARealNightReadsTheSummary() {
        // Boulder: new moon on 10 October 2026 and full moon on 26 October (see PlanningNightSummaryTests).
        val newMoon = PlanningTestSupport.summary(PlanningTestSupport.boulder, 2026, 10, 10)
        val fullMoon = PlanningTestSupport.summary(PlanningTestSupport.boulder, 2026, 10, 25)
        val sky = bortle(3)
        val dark = SkyQualityScore.imaging(newMoon, sky, 0.1)
        val bright = SkyQualityScore.imaging(fullMoon, sky, 0.1)
        assertEquals(
            SkyQualityScore.imaging(
                newMoon.astronomicalDarkHours, newMoon.moonFreeDarkHours, newMoon.moon.illuminatedFraction, sky, 0.1,
            ),
            dark,
        )
        assertTrue(dark.total > 8.0, "new moon: ${dark.total}")
        assertTrue(bright.total < 6.5, "full moon: ${bright.total}")
        assertTrue(dark.total - bright.total > 2.5)
        assertTrue(assertNotNull(bright.component(ImagingScoreComponentKind.MOON)).quality < 0.02)
        assertTrue(assertNotNull(dark.component(ImagingScoreComponentKind.MOON)).quality > 0.97)
        assertEquals(1.0, assertNotNull(dark.component(ImagingScoreComponentKind.DARKNESS_HOURS)).quality, 1e-12)
    }
}
