package net.kuecker.lodestar.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsNumberTextTest {
    @Test
    fun parsesBothDecimalMarksAndTheMinusSign() {
        assertEquals(2.8, SettingsNumberText.parse(" 2,8 ", 0.5..32.0))
        assertEquals(-12.5, SettingsNumberText.parse("−12.5", -60.0..60.0))
        assertEquals(24.0, SettingsNumberText.parse("24.", 4.0..2000.0))
    }

    @Test
    fun rejectsOutOfRangeNonNumbersAndOverlongText() {
        assertNull(SettingsNumberText.parse("3000", 4.0..2000.0))
        assertNull(SettingsNumberText.parse("2.5d", 0.5..32.0))
        assertNull(SettingsNumberText.parse("NaN", 0.0..10.0))
        assertNull(SettingsNumberText.parse("1".repeat(17), 0.0..Double.MAX_VALUE))
    }

    @Test
    fun formatsWithoutTrailingZerosOrGrouping() {
        assertEquals("24", SettingsNumberText.text(24.0, 1))
        assertEquals("2.8", SettingsNumberText.text(2.8, 1))
        assertEquals("1500", SettingsNumberText.text(1500.0, 0))
        assertEquals("0", SettingsNumberText.text(-0.04, 1))
        assertEquals("", SettingsNumberText.text(null, 1))
    }
}
