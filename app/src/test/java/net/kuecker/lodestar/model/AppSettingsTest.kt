package net.kuecker.lodestar.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppSettingsTest {
    @Test
    fun roundTripKeepsEverySetting() {
        val site = assertNotNull(SavedSite.create("Dark Site", 36.05, -112.14, 2130.0, "America/Phoenix"))
        val settings = AppSettings(
            hasCompletedOnboarding = true, locationSource = LocationSource.Site(site.id), savedSites = listOf(site),
            headingSource = HeadingSource.GYRO_LOCK, focalLength = 14.0, pixelPitch = 3.76,
            nightMode = NightModeSetting.ON, coordinateStyle = CoordinateStyle.DECIMAL,
            favoriteObjectIDs = listOf("dso.M31"), recentObjectIDs = listOf("planet.mars", "moon"),
        )
        assertEquals(settings, AppSettings.decode(AppSettings.encode(settings)))
    }

    @Test
    fun corruptOrUnknownStorageFallsBackPerKey() {
        assertEquals(AppSettings(), AppSettings.decode("not json"))
        val decoded = AppSettings.decode(
            """{"focalLength":"fifty","aperture":4.0,"nightMode":"sometimes","newSetting":true,"keepScreenAwake":false}""",
        )
        assertEquals(24.0, decoded.focalLength)
        assertEquals(4.0, decoded.aperture)
        assertEquals(NightModeSetting.AUTOMATIC, decoded.nightMode)
        assertEquals(false, decoded.keepScreenAwake)
    }

    @Test
    fun outOfRangeValuesAreClampedAndDanglingSitesReset() {
        val decoded = AppSettings.decode(
            """{"focalLength":1e9,"nightIntensity":-3,"pixelPitch":99,"locationSource":{"type":"site","id":"gone"}}""",
        )
        assertEquals(2000.0, decoded.focalLength)
        assertEquals(0.25, decoded.nightIntensity)
        assertNull(decoded.pixelPitch)
        assertEquals(LocationSource.Device, decoded.locationSource)
    }

    @Test
    fun sitesAreValidatedAndInvalidStoredSitesDropped() {
        assertNull(SavedSite.create("Nowhere", 91.0, 0.0))
        assertNull(SavedSite.create("Bad id", 0.0, 0.0, id = "not-a-uuid"))
        val site = assertNotNull(SavedSite.create("   ", 10.0, 190.0, timeZoneIdentifier = "Mars/Olympus"))
        assertEquals("Unnamed site", site.name)
        assertEquals(-170.0, site.longitude, 1e-9)
        assertNull(site.timeZoneIdentifier)
        val decoded = AppSettings.decode(
            """{"savedSites":[{"id":"${site.id}","name":"Ok","latitude":1,"longitude":2},{"id":"x","name":"Bad","latitude":200,"longitude":0}]}""",
        )
        assertEquals(listOf("Ok"), decoded.savedSites.map { it.name })
    }
}
