package net.kuecker.lodestar.features.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetMoonShapeTest {
    @Test
    fun aWaxingMoonInTheNorthIsLitOnTheRight() {
        assertFalse(WidgetMoonShape(0.3, isWaxing = true, isSouthernHemisphere = false).isMirrored)
        assertTrue(WidgetMoonShape(0.3, isWaxing = false, isSouthernHemisphere = false).isMirrored)
    }

    @Test
    fun inTheSouthTheSidesSwap() {
        assertTrue(WidgetMoonShape(0.3, isWaxing = true, isSouthernHemisphere = true).isMirrored)
        assertFalse(WidgetMoonShape(0.3, isWaxing = false, isSouthernHemisphere = true).isMirrored)
    }

    @Test
    fun theTerminatorIsAsWideAsThePhaseSays() {
        assertEquals(1.0, WidgetMoonShape(0.0, true, false).terminatorHalfWidth, 1e-12)
        assertEquals(1.0, WidgetMoonShape(1.0, true, false).terminatorHalfWidth, 1e-12)
        assertEquals(0.0, WidgetMoonShape(0.5, true, false).terminatorHalfWidth, 1e-12)
        assertEquals(0.5, WidgetMoonShape(0.25, true, false).terminatorHalfWidth, 1e-12)
        assertEquals(0.5, WidgetMoonShape(0.75, true, false).terminatorHalfWidth, 1e-12)
    }

    @Test
    fun belowHalfLitIsACrescent() {
        assertTrue(WidgetMoonShape(0.17, false, false).isCrescent)
        assertFalse(WidgetMoonShape(0.83, false, false).isCrescent)
    }

    @Test
    fun implausibleFractionsAreClamped() {
        assertEquals(0.0, WidgetMoonShape(Double.NaN, true, false).fraction)
        assertEquals(1.0, WidgetMoonShape(1.4, true, false).fraction)
        assertEquals(0.0, WidgetMoonShape(-0.2, true, false).fraction)
    }
}
