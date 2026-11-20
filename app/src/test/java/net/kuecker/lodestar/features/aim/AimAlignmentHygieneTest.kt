package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Matrix3
import net.kuecker.lodestar.kit.pointing.AlignmentHygiene
import net.kuecker.lodestar.kit.pointing.AlignmentPoint
import net.kuecker.lodestar.kit.pointing.AlignmentPointSeparation
import net.kuecker.lodestar.kit.pointing.DeviceAttitude
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AimAlignmentHygieneTest {
    /** An alignment point whose recorded target is the given place (the attitude does not matter here). */
    private fun point(azimuth: Double, altitude: Double, name: String?): AlignmentPoint {
        val attitude = assertNotNull(DeviceAttitude.create(Matrix3.identity, 0.0, 0.0))
        return AlignmentPoint(attitude, HorizontalCoordinate(azimuth, altitude), Instant.ofEpochSecond(1_800_000_000), name)
    }

    // Age

    @Test
    fun ageReadsInMinutesAndHours() {
        assertEquals("just now", AimAlignmentAge.text(0.0))
        assertEquals("just now", AimAlignmentAge.text(59.9))
        assertEquals("1 min", AimAlignmentAge.text(60.0))
        assertEquals("12 min", AimAlignmentAge.text(12 * 60 + 30.0))
        assertEquals("59 min", AimAlignmentAge.text(3_599.0))
        assertEquals("1 h", AimAlignmentAge.text(3_600.0))
        assertEquals("1 h 30 min", AimAlignmentAge.text(5_400.0))
        assertEquals("3 h", AimAlignmentAge.text(10_800.0))
        assertEquals("3 h 1 min", AimAlignmentAge.text(10_860.0))
        assertEquals("26 h 5 min", AimAlignmentAge.text(26 * 3_600 + 5 * 60.0))
    }

    @Test
    fun anAgeThatMakesNoSenseReadsAsJustNow() {
        assertEquals("just now", AimAlignmentAge.text(-3_600.0))
        assertEquals("just now", AimAlignmentAge.text(Double.NaN))
        assertEquals("just now", AimAlignmentAge.text(Double.POSITIVE_INFINITY))
    }

    @Test
    fun cardAndChipTexts() {
        assertEquals("Aligned just now", AimAlignmentAge.cardText(10.0))
        assertEquals("Aligned 12 min ago", AimAlignmentAge.cardText(12 * 60.0))
        assertEquals("Aligned 3 h 5 min ago", AimAlignmentAge.cardText(3 * 3_600 + 5 * 60.0))
        assertEquals("Alignment 3 h old", AimAlignmentAge.chipText(AlignmentHygiene.compassStaleAfterSeconds))
        assertEquals("Alignment 1 h 30 min old", AimAlignmentAge.chipText(AlignmentHygiene.gyroLockStaleAfterSeconds))
    }

    // Spacing

    @Test
    fun degreesAreRoundedDownSoTheWarningNeverContradictsTheLimit() {
        assertEquals("8°", AimAlignmentSpacing.degreesText(8.9))
        assertEquals("19°", AimAlignmentSpacing.degreesText(19.99))
        assertEquals("20°", AimAlignmentSpacing.degreesText(20.0))
        assertEquals("47°", AimAlignmentSpacing.degreesText(47.6))
        assertEquals("24′", AimAlignmentSpacing.degreesText(0.4))
    }

    @Test
    fun nearestTextNamesThePoint() {
        assertEquals("47° from Vega", AimAlignmentSpacing.nearestText(AlignmentPointSeparation(47.2, 0, "Vega")))
        assertEquals("12° from point 3", AimAlignmentSpacing.nearestText(AlignmentPointSeparation(12.0, 2, null)))
        assertTrue(AimAlignmentSpacing.isTooClose(AlignmentPointSeparation(19.99, 0, null)))
        assertFalse(AimAlignmentSpacing.isTooClose(AlignmentPointSeparation(20.0, 0, null)))
    }

    @Test
    fun theWarningNamesTheNearestStarAndTheMinimum() {
        val existing = listOf(point(100.0, 40.0, "Vega"), point(280.0, 30.0, null))
        assertEquals(
            "Only 8° from Vega: pick a star at least 20° away for a better fit",
            AimAlignmentSpacing.warningText(HorizontalCoordinate(100.0, 48.5), existing),
        )
        assertEquals(
            "Only 12° from point 2: pick a star at least 20° away for a better fit",
            AimAlignmentSpacing.warningText(HorizontalCoordinate(280.0, 42.5), existing),
        )
        // Far enough from both, and nothing to compare with.
        assertNull(AimAlignmentSpacing.warningText(HorizontalCoordinate(190.0, 60.0), existing))
        assertNull(AimAlignmentSpacing.warningText(HorizontalCoordinate(100.0, 48.5), emptyList()))
    }
}
