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
        val off = AppSettings(showHorizonGlow = false)
        assertEquals(off, AppSettings.decode(AppSettings.encode(off)))
        assertEquals(AppSettings(), AppSettings.decode(AppSettings.encode(AppSettings())))
        assertTrue(AppSettings.decode("""{"showHorizonGlow":"sometimes"}""").showHorizonGlow)
    }
}
