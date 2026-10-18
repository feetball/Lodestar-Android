package net.kuecker.lodestar.features.widget

import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.model.AppSettings
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.NightModeSetting
import net.kuecker.lodestar.model.SavedSite
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetInputsTest {
    private val ridge = checkNotNull(
        SavedSite.create("Ridge", 40.0, -105.3, 1650.0, "America/Denver", horizon = HorizonProfile.constant(12.0)),
    )
    private val device = GeographicLocation(32.78, -96.80, 150.0)

    private fun settings(source: LocationSource, nightMode: NightModeSetting = NightModeSetting.AUTOMATIC) =
        AppSettings(
            locationSource = source, savedSites = listOf(ridge), nightMode = nightMode, nightIntensity = 0.5,
            temperatureCelsius = 3.0, pressureMillibars = 900.0,
        )

    @Test
    fun aSavedSiteIsUsedWithItsZoneAndSkyline() {
        val inputs = WidgetInputsResolver.resolve(settings(LocationSource.Site(ridge.id)), null, null)
        val place = assertNotNull(inputs.place)
        assertEquals("Ridge", place.name)
        assertEquals(ridge.id, place.siteID)
        assertEquals("America/Denver", place.timeZoneID)
        assertEquals(ridge.horizon, place.horizon)
        val context = assertNotNull(place.observerContext())
        assertEquals(ZoneId.of("America/Denver"), context.timeZone)
        assertEquals(40.0, context.location.latitude)
        assertEquals(900.0, context.observer.atmosphere.pressureMillibars)
        assertEquals(3.0, context.observer.atmosphere.temperatureCelsius)
        assertEquals(LocationSource.Site(ridge.id), context.source)
        assertEquals(ridge.horizon, context.horizon)
    }

    @Test
    fun theDeviceLocationIsTheLiveOneThenTheLastStoredOne() {
        val live = GeographicLocation(10.0, 20.0, 5.0)
        assertEquals(10.0, WidgetInputsResolver.resolve(settings(LocationSource.Device), live, device).place?.latitude)
        val stored = WidgetInputsResolver.resolve(settings(LocationSource.Device), null, device).place
        assertEquals(32.78, stored?.latitude)
        assertEquals(WidgetInputsResolver.DEVICE_NAME, stored?.name)
        assertNull(stored?.siteID)
        assertNull(stored?.horizon)
        // The device location has the phone's zone, not a stored one.
        assertEquals(ZoneId.systemDefault(), stored?.observerContext()?.timeZone)
    }

    @Test
    fun withoutAnyLocationThereIsNoPlace() {
        assertNull(WidgetInputsResolver.resolve(settings(LocationSource.Device), null, null).place)
        // A site that was deleted: the settings fall back to the device, and so does the widget.
        val gone = AppSettings(locationSource = LocationSource.Site("00000000-0000-0000-0000-000000000000"))
        assertNull(WidgetInputsResolver.resolve(gone, null, null).place)
    }

    @Test
    fun theNightModePreferenceIsCarried() {
        val inputs = WidgetInputsResolver.resolve(settings(LocationSource.Device, NightModeSetting.ON), device, null)
        assertEquals(NightModeSetting.ON, inputs.nightMode)
        assertEquals(0.5, inputs.nightIntensity)
    }

    @Test
    fun aPlaceOutsideTheEarthIsNotAnObserver() {
        assertNull(WidgetPlace("Nowhere", 123.0, 0.0, 0.0).observerContext())
        // An unknown zone falls back to the phone's.
        val place = WidgetPlace("Somewhere", 10.0, 10.0, 0.0, timeZoneID = "Mars/Olympus")
        assertEquals(ZoneId.systemDefault(), place.observerContext()?.timeZone)
    }

    @Test
    fun theStoreKeepsInputsWithTheirSkylineAndReportsChanges() {
        val store = WidgetInputsStore(WidgetTestPreferences())
        assertNull(store.read())
        val inputs = WidgetInputsResolver.resolve(settings(LocationSource.Site(ridge.id)), null, null)
        assertTrue(store.write(inputs))
        assertEquals(inputs, store.read())
        assertFalse(store.write(inputs))
        val changed = inputs.copy(nightMode = NightModeSetting.OFF)
        assertTrue(store.write(changed))
        assertEquals(changed, store.read())
    }

    @Test
    fun theStoreRemembersTheDeviceLocation() {
        val store = WidgetInputsStore(WidgetTestPreferences())
        assertNull(store.lastDeviceLocation())
        store.rememberDeviceLocation(device)
        assertEquals(device, store.lastDeviceLocation())
        val moved = GeographicLocation(48.85, 2.35, 35.0)
        store.rememberDeviceLocation(moved)
        assertEquals(moved, store.lastDeviceLocation())
    }

    @Test
    fun damagedStoredInputsAreIgnored() {
        val preferences = WidgetTestPreferences()
        preferences.edit().putString("inputs.v1", "{not json").apply()
        preferences.edit().putString("device.v1", "[]").apply()
        val store = WidgetInputsStore(preferences)
        assertNull(store.read())
        assertNull(store.lastDeviceLocation())
    }

    @Test
    fun withoutStoredInputsTheSavedSettingsGiveTheSite() {
        val app = WidgetTestPreferences()
        // The key is the one of the app's settings store; a change of it must be noticed here.
        app.edit().putString("lodestar.settings.v1", AppSettings.encode(settings(LocationSource.Site(ridge.id)))).apply()
        val store = WidgetInputsStore(WidgetTestPreferences())
        val inputs = store.readOrBootstrap(app)
        assertEquals("Ridge", inputs.place?.name)
        assertEquals(0.5, inputs.nightIntensity)
    }

    @Test
    fun withoutStoredInputsAndSettingsThereIsNoPlaceUntilTheAppKnowsOne() {
        val store = WidgetInputsStore(WidgetTestPreferences())
        assertNull(store.readOrBootstrap(WidgetTestPreferences()).place)
        // A device location the app stored earlier is used for the device source.
        store.rememberDeviceLocation(device)
        val app = WidgetTestPreferences()
        app.edit().putString("lodestar.settings.v1", AppSettings.encode(AppSettings(locationSource = LocationSource.Device))).apply()
        assertEquals(32.78, store.readOrBootstrap(app).place?.latitude)
    }

    @Test
    fun storedInputsWinOverTheSettings() {
        val store = WidgetInputsStore(WidgetTestPreferences())
        val stored = WidgetInputs(WidgetPlace("Stored", 1.0, 2.0, 3.0))
        store.write(stored)
        val app = WidgetTestPreferences()
        app.edit().putString("lodestar.settings.v1", AppSettings.encode(settings(LocationSource.Site(ridge.id)))).apply()
        assertEquals(stored, store.readOrBootstrap(app))
    }

    @Test
    fun thePreviewVersionIsKept() {
        val store = WidgetInputsStore(WidgetTestPreferences())
        assertEquals(0, store.previewVersion)
        store.previewVersion = 7
        assertEquals(7, store.previewVersion)
    }
}
