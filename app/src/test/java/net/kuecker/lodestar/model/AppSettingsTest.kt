package net.kuecker.lodestar.model

import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.planning.ImagingFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsTest {
    @Test
    fun roundTripKeepsEverySetting() {
        val site = assertNotNull(SavedSite.create("Dark Site", 36.05, -112.14, 2130.0, "America/Phoenix"))
        val settings = AppSettings(
            hasCompletedOnboarding = true, locationSource = LocationSource.Site(site.id), savedSites = listOf(site),
            headingSource = HeadingSource.GYRO_LOCK, focalLength = 14.0, pixelPitch = 3.76,
            imagingFilter = ImagingFilter.DUAL_NARROWBAND,
            polarErrorDegrees = 2.5, exposureISO = 1600,
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
    fun theImagingFilterDefaultsToNoneAndAnUnknownOneFallsBack() {
        assertEquals(ImagingFilter.NONE, AppSettings().imagingFilter)
        // Settings stored before the filter existed have no key.
        assertEquals(ImagingFilter.NONE, AppSettings.decode("""{"focalLength":50}""").imagingFilter)
        assertEquals(ImagingFilter.NONE, AppSettings.decode("""{"imagingFilter":"polarising"}""").imagingFilter)
        assertEquals(ImagingFilter.NONE, AppSettings.decode("""{"imagingFilter":3}""").imagingFilter)
        for (filter in ImagingFilter.entries) {
            val stored = AppSettings.encode(AppSettings(imagingFilter = filter))
            assertTrue(stored.contains("\"imagingFilter\":\"${filter.rawValue}\""), stored)
            assertEquals(filter, AppSettings.decode(stored).imagingFilter)
        }
        // A bad filter does not cost the other settings.
        val decoded = AppSettings.decode("""{"imagingFilter":"x","aperture":4.0}""")
        assertEquals(4.0, decoded.aperture)
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
    fun exposureHelperSettingsHaveDefaultsAndRanges() {
        val defaults = AppSettings.decode("{}")
        assertEquals(1.0, defaults.polarErrorDegrees)
        assertEquals(100, defaults.exposureISO)
        val low = AppSettings.decode("""{"polarErrorDegrees":0.0,"exposureISO":25}""")
        assertEquals(0.1, low.polarErrorDegrees)
        assertEquals(100, low.exposureISO)
        val high = AppSettings.decode("""{"polarErrorDegrees":45,"exposureISO":409600}""")
        assertEquals(5.0, high.polarErrorDegrees)
        assertEquals(12800, high.exposureISO)
        val damaged = AppSettings.decode("""{"polarErrorDegrees":"one","exposureISO":1.5}""")
        assertEquals(1.0, damaged.polarErrorDegrees)
        assertEquals(100, damaged.exposureISO)
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

    @Test
    fun aSitesSkylineIsStoredAndADamagedOneIsDroppedWithoutLosingTheSite() {
        val skyline = assertNotNull(HorizonProfile.create(List(360) { if (it in 80..120) 12.0 else 0.0 }))
        val site = assertNotNull(SavedSite.create("Ridge", 40.0, -105.0, horizon = skyline))
        val decoded = AppSettings.decode(AppSettings.encode(AppSettings(savedSites = listOf(site))))
        assertEquals(skyline, decoded.savedSites.single().horizon)
        val damaged = AppSettings.decode(
            """{"savedSites":[{"id":"${site.id}","name":"Ridge","latitude":40,"longitude":-105,"horizon":{"version":1,"altitudeTenths":[1,2]}}]}""",
        )
        assertEquals("Ridge", damaged.savedSites.single().name)
        assertNull(damaged.savedSites.single().horizon)
        assertNull(assertNotNull(SavedSite.create("Open", 0.0, 0.0, horizon = HorizonProfile.flat)).horizon)
    }
}
