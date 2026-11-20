package net.kuecker.lodestar.features.skyquality

import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.skyquality.LightPollutionMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HorizonGlowRingTest {
    private val map = LightPollutionMap.load()

    @Test
    fun aCityGivesARingOfSeventyTwoValuesBetweenZeroAndOne() {
        val ring = assertNotNull(HorizonGlowRing.from(map.darkestDirection(GeographicLocation(40.0150, -105.2705))))
        assertEquals(HorizonGlowRing.SEGMENTS, ring.glow.size)
        assertTrue(ring.glow.all { it in 0f..1f })
        // Boulder: the foothills to the west are dark, Denver to the south-east is bright.
        assertTrue(ring.glow[63] < ring.glow[9])
        assertEquals(
            "A thin glow ring at the horizon marks the artificial light; it is brightest toward the east-southeast.",
            ring.spokenSummary,
        )
    }

    @Test
    fun darkSkyGivesNoRing() {
        assertNull(HorizonGlowRing.from(map.darkestDirection(GeographicLocation(0.0, -150.0))))
        assertNull(HorizonGlowRing.from(emptyList()))
    }
}
