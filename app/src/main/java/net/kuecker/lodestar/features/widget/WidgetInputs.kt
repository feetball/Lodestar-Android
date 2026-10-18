package net.kuecker.lodestar.features.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.foundation.Atmosphere
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.model.AppSettings
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.NightModeSetting
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.model.SettingsStore
import java.time.DateTimeException
import java.time.ZoneId

/** Where the widgets compute the night: the observer of the app, reduced to plain values that can be stored. */
@Serializable
internal data class WidgetPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevation: Double,
    /** The saved site's id; `null` for the device location. */
    val siteID: String? = null,
    /** IANA time zone of the saved site; `null` for the phone's current zone (always so for the device location). */
    val timeZoneID: String? = null,
    val temperatureCelsius: Double = 10.0,
    val pressureMillibars: Double = 1010.0,
    /** The saved site's recorded skyline, or `null` for an open horizon. */
    val horizon: HorizonProfile? = null,
) {
    /** The observer for the kit and the Tonight calculator, or `null` when the stored values are not a valid place. */
    fun observerContext(): ObserverContext? {
        val location = GeographicLocation.validated(latitude, longitude, elevation) ?: return null
        val zone = timeZoneID?.let { identifier ->
            try {
                ZoneId.of(identifier)
            } catch (_: DateTimeException) {
                null
            }
        } ?: ZoneId.systemDefault()
        return ObserverContext(
            observer = Observer(location, Atmosphere(pressureMillibars, temperatureCelsius)),
            timeZone = zone,
            name = name,
            source = siteID?.let { LocationSource.Site(it) } ?: LocationSource.Device,
            horizon = horizon,
        )
    }
}

/** Everything of the app that the widgets show or follow: the place and the night-mode preference. */
@Serializable
internal data class WidgetInputs(
    /** The observer, or `null` when the app has no location yet (the widgets ask to open Lodestar). */
    val place: WidgetPlace? = null,
    val nightMode: NightModeSetting = NightModeSetting.AUTOMATIC,
    val nightIntensity: Double = 0.7,
)

/** Works out [WidgetInputs] from the app's settings. Pure. */
internal object WidgetInputsResolver {
    /** The name the app gives the device location. */
    const val DEVICE_NAME = "Current Location"

    /**
     * The inputs for [settings]. A saved site is used as it is; the device location is [liveDevice] while the app
     * has one and [lastDevice] (the last one it stored) otherwise.
     */
    fun resolve(settings: AppSettings, liveDevice: GeographicLocation?, lastDevice: GeographicLocation?): WidgetInputs {
        val place = when (val source = settings.locationSource) {
            LocationSource.Device -> (liveDevice ?: lastDevice)?.let { location ->
                WidgetPlace(
                    name = DEVICE_NAME,
                    latitude = location.latitude, longitude = location.longitude, elevation = location.elevation,
                    temperatureCelsius = settings.temperatureCelsius, pressureMillibars = settings.pressureMillibars,
                )
            }
            is LocationSource.Site -> settings.savedSites.firstOrNull { it.id == source.id }?.let { site ->
                WidgetPlace(
                    name = site.name,
                    latitude = site.latitude, longitude = site.longitude, elevation = site.elevation,
                    siteID = site.id, timeZoneID = site.timeZoneIdentifier,
                    temperatureCelsius = settings.temperatureCelsius, pressureMillibars = settings.pressureMillibars,
                    horizon = site.horizon,
                )
            }
        }
        return WidgetInputs(place, settings.nightMode, settings.nightIntensity)
    }
}

/**
 * The widgets' own small store (device-local, not backed up): the inputs the app last saw and the last device
 * location it knew. The app writes them whenever they change ([WidgetUpdater]); the widgets read them without
 * starting any of the app.
 */
internal class WidgetInputsStore(private val preferences: SharedPreferences) {
    private companion object {
        const val FILE = "lodestar.widget"
        const val INPUTS_KEY = "inputs.v1"
        const val DEVICE_KEY = "device.v1"
        const val PREVIEW_VERSION_KEY = "previewVersion"
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    /** The stored inputs, or `null` when the app has not stored any (or they are damaged). */
    fun read(): WidgetInputs? =
        preferences.getString(INPUTS_KEY, null)?.let { text ->
            runCatching { json.decodeFromString(WidgetInputs.serializer(), text) }.getOrNull()
        }

    /** Stores [inputs]; `false` when they are what is stored already. */
    fun write(inputs: WidgetInputs): Boolean {
        if (read() == inputs) return false
        preferences.edit { putString(INPUTS_KEY, json.encodeToString(WidgetInputs.serializer(), inputs)) }
        return true
    }

    /** The last device location the app stored, if any. */
    fun lastDeviceLocation(): GeographicLocation? =
        preferences.getString(DEVICE_KEY, null)?.let { text ->
            runCatching { json.decodeFromString(StoredLocation.serializer(), text) }.getOrNull()
        }?.let { GeographicLocation.validated(it.latitude, it.longitude, it.elevation) }

    /** Remembers [location] as the device location for the times the app is not running. */
    fun rememberDeviceLocation(location: GeographicLocation) {
        val stored = StoredLocation(location.latitude, location.longitude, location.elevation)
        if (lastDeviceLocation() == location) return
        preferences.edit { putString(DEVICE_KEY, json.encodeToString(StoredLocation.serializer(), stored)) }
    }

    /** The version of the app that generated the system's widget previews last, or 0. */
    var previewVersion: Int
        get() = preferences.getInt(PREVIEW_VERSION_KEY, 0)
        set(value) = preferences.edit { putInt(PREVIEW_VERSION_KEY, value) }

    /**
     * The stored inputs, or, when the app has stored none yet (the widget was added before it was opened since the
     * widgets came), the ones its saved settings give; a device location is unknown then.
     */
    fun readOrBootstrap(appPreferences: SharedPreferences): WidgetInputs =
        read() ?: WidgetInputsResolver.resolve(
            SettingsStore(appPreferences, CoroutineScope(SupervisorJob())).settings, null, lastDeviceLocation(),
        )

    @Serializable
    private data class StoredLocation(val latitude: Double, val longitude: Double, val elevation: Double)
}
