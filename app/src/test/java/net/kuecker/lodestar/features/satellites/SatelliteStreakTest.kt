package net.kuecker.lodestar.features.satellites

import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SatelliteStreakTest {
    private var previousLocale: Locale? = null

    @BeforeTest
    fun useEnglish() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun restoreLocale() {
        previousLocale?.let(Locale::setDefault)
    }

    @Test
    fun theChipNamesTheCrossingsTheExposureAndTheRisk() {
        assertEquals("Starlink: 3 crossings in 30 s · 12% streak risk", SatelliteText.streakChip(3, 30, 0.12))
        assertEquals("Starlink: 1 crossing in 9 s · 8% streak risk", SatelliteText.streakChip(1, 9, 0.084))
        assertEquals("Starlink: no crossings in 15 s · 0% streak risk", SatelliteText.streakChip(0, 15, 0.0))
        assertEquals("Starlink: 2 crossings in 120 s · 100% streak risk", SatelliteText.streakChip(2, 120, 1.7))
    }

    @Test
    fun theSheetSummaryGivesTheNumbersBehindTheChip() {
        assertEquals(
            "47 sunlit satellites above 20° right now. About 0.8 crossings a minute in this frame; 12% chance of a streak in a 30 s exposure.",
            SatelliteText.streakSummary(30, 47, 0.8, 0.12, Locale.US),
        )
        assertTrue(SatelliteText.streakSummary(9, 1, 0.0, 0.0, Locale.US).startsWith("1 sunlit satellite above 20°"))
    }

    @Test
    fun theTextsDoNotMentionAnotherPlatform() {
        val texts = listOf(
            SatelliteText.STALE, SatelliteText.ACTIONS_NOTE, SatelliteText.STREAK_NOTE, SatelliteText.NO_CROSSINGS,
            SatelliteText.VISIBLE_ONLY, SatelliteText.STREAK_STALE_CHIP,
        )
        for (text in texts) assertFalse(text.contains("iPhone") || text.contains("iOS"), text)
    }

    @Test
    fun theExposureIsTheNpfLimitOfTheLens() {
        // (35 · 2.8 + 30 · 4.0) / 24 = 9.08 s for a 24 mm f/2.8 lens on 4 µm pixels.
        val lens = LensSetup(SensorFormat.fullFrame, 24.0, 2.8, 4.0)
        assertEquals(9, StreakExposure.seconds(lens, null))
        // A target at 60° declination lengthens it by 1 / cos 60° = 2.
        assertEquals(18, StreakExposure.seconds(lens, 60.0))
    }

    @Test
    fun theExposureStaysWithinTheLimits() {
        val telephoto = LensSetup(SensorFormat.fullFrame, 600.0, 4.0, 4.0)
        assertEquals(StreakExposure.MINIMUM_SECONDS, StreakExposure.seconds(telephoto, null))
        val fisheye = LensSetup(SensorFormat.fullFrame, 8.0, 2.8, 8.0)
        // (98 + 240) / 8 = 42 s, times the declination factor of 5 near the pole: capped.
        assertEquals(StreakExposure.MAXIMUM_SECONDS, StreakExposure.seconds(fisheye, 85.0))
    }
}
