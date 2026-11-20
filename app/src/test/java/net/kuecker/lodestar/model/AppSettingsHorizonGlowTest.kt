package net.kuecker.lodestar.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppSettingsHorizonGlowTest {
    @Test
    fun horizonGlowIsOnByDefault() {
        assertTrue(AppSettings().showHorizonGlow)
        assertTrue(AppSettings.decode("{}").showHorizonGlow)
        assertTrue(AppSettings.decode("""{"showMilkyWay":false}""").showHorizonGlow)
    }

    @Test
    fun horizonGlowIsKeptAcrossARoundTripAndADamagedValueTakesTheDefault() {
        // Decoding sanitises, which turns the single camera into the first camera profile; so do the expected values.
        val off = AppSettings(showHorizonGlow = false).sanitized()
        assertEquals(off, AppSettings.decode(AppSettings.encode(off)))
        assertEquals(AppSettings().sanitized(), AppSettings.decode(AppSettings.encode(AppSettings())))
        assertTrue(AppSettings.decode("""{"showHorizonGlow":"sometimes"}""").showHorizonGlow)
    }
}
