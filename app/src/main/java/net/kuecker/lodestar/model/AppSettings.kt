package net.kuecker.lodestar.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.kuecker.lodestar.kit.foundation.Atmosphere
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.kit.pointing.MountProfile
import java.time.DateTimeException
import java.time.ZoneId
import java.util.UUID

/** Where the observer's position comes from. */
@Serializable
sealed interface LocationSource {
    /** The device's current position. */
    @Serializable
    @SerialName("device")
    data object Device : LocationSource

    /** A user-saved observing site. */
    @Serializable
    @SerialName("site")
    data class Site(val id: String) : LocationSource
}

/** A user-saved observing site (typically a dark-sky location). */
@ConsistentCopyVisibility
data class SavedSite private constructor(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevation: Double,
    /** IANA time zone of the site, when known. */
    val timeZoneIdentifier: String?,
) {
    val location: GeographicLocation get() = GeographicLocation(latitude, longitude, elevation)

    val timeZone: ZoneId? get() = timeZoneIdentifier?.let(::zoneOrNull)

    /** A copy with new values, validated like [create]; `null` for invalid input. */
    fun edited(
        name: String = this.name,
        latitude: Double = this.latitude,
        longitude: Double = this.longitude,
        elevation: Double = this.elevation,
        timeZoneIdentifier: String? = this.timeZoneIdentifier,
    ): SavedSite? = create(name, latitude, longitude, elevation, timeZoneIdentifier, id)

    companion object {
        const val MAXIMUM_NAME_LENGTH = 60

        /** Validates the coordinates and trims/limits the name; returns `null` for invalid input. */
        fun create(
            name: String,
            latitude: Double,
            longitude: Double,
            elevation: Double = 0.0,
            timeZoneIdentifier: String? = null,
            id: String = UUID.randomUUID().toString(),
        ): SavedSite? {
            val location = GeographicLocation.validated(latitude, longitude, elevation) ?: return null
            if (runCatching { UUID.fromString(id) }.isFailure) return null
            val trimmed = name.trim()
            return SavedSite(
                id = id,
                name = if (trimmed.isEmpty()) "Unnamed site" else trimmed.take(MAXIMUM_NAME_LENGTH),
                latitude = location.latitude,
                longitude = location.longitude,
                elevation = location.elevation,
                timeZoneIdentifier = timeZoneIdentifier?.takeIf { zoneOrNull(it) != null },
            )
        }

        internal fun zoneOrNull(identifier: String): ZoneId? =
            try {
                ZoneId.of(identifier)
            } catch (_: DateTimeException) {
                null
            }

        internal fun toJson(site: SavedSite): JsonElement = buildJsonObject {
            put("id", JsonPrimitive(site.id))
            put("name", JsonPrimitive(site.name))
            put("latitude", JsonPrimitive(site.latitude))
            put("longitude", JsonPrimitive(site.longitude))
            put("elevation", JsonPrimitive(site.elevation))
            site.timeZoneIdentifier?.let { put("timeZoneIdentifier", JsonPrimitive(it)) }
        }

        /** Decodes and re-validates a stored site; `null` for invalid entries (dropped by the caller). */
        internal fun fromJson(element: JsonElement): SavedSite? = runCatching {
            val o = element as JsonObject
            create(
                id = o.getValue("id").jsonPrimitive.content,
                name = o.getValue("name").jsonPrimitive.content,
                latitude = o.getValue("latitude").jsonPrimitive.doubleOrNull ?: return null,
                longitude = o.getValue("longitude").jsonPrimitive.doubleOrNull ?: return null,
                elevation = o["elevation"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                timeZoneIdentifier = o["timeZoneIdentifier"]?.jsonPrimitive?.content,
            )
        }.getOrNull()
    }
}

@Serializable
enum class NightModeSetting(val title: String) {
    @SerialName("off") OFF("Off"),
    @SerialName("on") ON("On"),
    @SerialName("automatic") AUTOMATIC("After Sunset"),
}

@Serializable
enum class HeadingSource(val title: String) {
    /** Magnetometer-referenced heading (WMM declination applied). */
    @SerialName("compass") COMPASS("Compass"),

    /** Gyroscope-only heading after star alignment — immune to magnetic interference, drifts slowly. */
    @SerialName("gyroLock") GYRO_LOCK("Gyro Lock"),
}

@Serializable
enum class CoordinateStyle(val title: String) {
    @SerialName("sexagesimal") SEXAGESIMAL("h m s / ° ′ ″"),
    @SerialName("decimal") DECIMAL("Decimal"),
}

/**
 * Every persisted user preference. Decoding is forward compatible: missing or invalid keys fall back to defaults,
 * so adding a setting never wipes the user's data.
 */
data class AppSettings(
    val hasCompletedOnboarding: Boolean = false,

    // Location
    val locationSource: LocationSource = LocationSource.Device,
    val savedSites: List<SavedSite> = emptyList(),

    // Mount and sensors
    val mountPresetID: String = MountProfile.backCameraPortrait.id,
    val headingSource: HeadingSource = HeadingSource.COMPASS,
    /** Attitude smoothing time constant in seconds. */
    val smoothingTimeConstant: Double = 0.12,

    // Camera and lens (for the framing rectangle and exposure tools)
    val sensorID: String = SensorFormat.fullFrame.id,
    val customSensorWidth: Double = 36.0,
    val customSensorHeight: Double = 24.0,
    val focalLength: Double = 24.0,
    val aperture: Double = 2.8,
    val pixelPitch: Double? = null,

    // Night mode
    val nightMode: NightModeSetting = NightModeSetting.AUTOMATIC,
    val nightIntensity: Double = 0.7,
    val dimScreenAtNight: Boolean = true,
    val nightBrightness: Double = 0.2,
    /** Experimental: multiply-blend red layer over the whole UI. */
    val redFilterOverlay: Boolean = false,
    val cameraPreviewEnabled: Boolean = true,
    val cameraPreviewAtNight: Boolean = false,

    // Feedback
    val hapticsEnabled: Boolean = true,
    val audioCuesEnabled: Boolean = false,
    val keepScreenAwake: Boolean = true,

    // Sky display
    val showConstellationLines: Boolean = true,
    val showConstellationNames: Boolean = true,
    val showDeepSkyObjects: Boolean = true,
    val showMilkyWay: Boolean = true,
    val starMagnitudeLimit: Double = 5.0,
    val coordinateStyle: CoordinateStyle = CoordinateStyle.SEXAGESIMAL,

    // Atmosphere (refraction)
    val temperatureCelsius: Double = 10.0,
    val pressureMillibars: Double = 1010.0,

    // Objects
    val favoriteObjectIDs: List<String> = emptyList(),
    val recentObjectIDs: List<String> = emptyList(),
) {
    val atmosphere: Atmosphere get() = Atmosphere(pressureMillibars, temperatureCelsius)

    /** Clamps numeric preferences into their supported ranges (protects against corrupted storage). */
    fun sanitized(): AppSettings {
        fun clamp(v: Double, range: ClosedFloatingPointRange<Double>, fallback: Double) =
            if (v.isFinite()) v.coerceIn(range) else fallback
        val source = locationSource
        return copy(
            smoothingTimeConstant = clamp(smoothingTimeConstant, 0.0..1.0, 0.12),
            customSensorWidth = clamp(customSensorWidth, 1.0..100.0, 36.0),
            customSensorHeight = clamp(customSensorHeight, 1.0..100.0, 24.0),
            focalLength = clamp(focalLength, 4.0..2000.0, 24.0),
            aperture = clamp(aperture, 0.95..32.0, 2.8),
            pixelPitch = pixelPitch?.takeIf { it.isFinite() && it in 0.5..20.0 },
            nightIntensity = clamp(nightIntensity, 0.25..1.0, 0.7),
            nightBrightness = clamp(nightBrightness, 0.01..1.0, 0.2),
            starMagnitudeLimit = clamp(starMagnitudeLimit, 1.0..6.5, 5.0),
            temperatureCelsius = clamp(temperatureCelsius, -60.0..60.0, 10.0),
            pressureMillibars = clamp(pressureMillibars, 300.0..1100.0, 1010.0),
            locationSource = if (source is LocationSource.Site && savedSites.none { it.id == source.id }) {
                LocationSource.Device
            } else {
                source
            },
            savedSites = savedSites.take(MAXIMUM_SAVED_SITES),
            favoriteObjectIDs = favoriteObjectIDs.take(MAXIMUM_FAVORITES),
            recentObjectIDs = recentObjectIDs.take(MAXIMUM_RECENTS),
        )
    }

    companion object {
        const val MAXIMUM_SAVED_SITES = 100
        const val MAXIMUM_RECENTS = 20
        const val MAXIMUM_FAVORITES = 200

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(settings: AppSettings): String {
            val s = settings
            val o = buildJsonObject {
                put("hasCompletedOnboarding", JsonPrimitive(s.hasCompletedOnboarding))
                put("locationSource", json.encodeToJsonElement(LocationSource.serializer(), s.locationSource))
                put("savedSites", JsonArray(s.savedSites.map(SavedSite::toJson)))
                put("mountPresetID", JsonPrimitive(s.mountPresetID))
                put("headingSource", json.encodeToJsonElement(HeadingSource.serializer(), s.headingSource))
                put("smoothingTimeConstant", JsonPrimitive(s.smoothingTimeConstant))
                put("sensorID", JsonPrimitive(s.sensorID))
                put("customSensorWidth", JsonPrimitive(s.customSensorWidth))
                put("customSensorHeight", JsonPrimitive(s.customSensorHeight))
                put("focalLength", JsonPrimitive(s.focalLength))
                put("aperture", JsonPrimitive(s.aperture))
                s.pixelPitch?.let { put("pixelPitch", JsonPrimitive(it)) }
                put("nightMode", json.encodeToJsonElement(NightModeSetting.serializer(), s.nightMode))
                put("nightIntensity", JsonPrimitive(s.nightIntensity))
                put("dimScreenAtNight", JsonPrimitive(s.dimScreenAtNight))
                put("nightBrightness", JsonPrimitive(s.nightBrightness))
                put("redFilterOverlay", JsonPrimitive(s.redFilterOverlay))
                put("cameraPreviewEnabled", JsonPrimitive(s.cameraPreviewEnabled))
                put("cameraPreviewAtNight", JsonPrimitive(s.cameraPreviewAtNight))
                put("hapticsEnabled", JsonPrimitive(s.hapticsEnabled))
                put("audioCuesEnabled", JsonPrimitive(s.audioCuesEnabled))
                put("keepScreenAwake", JsonPrimitive(s.keepScreenAwake))
                put("showConstellationLines", JsonPrimitive(s.showConstellationLines))
                put("showConstellationNames", JsonPrimitive(s.showConstellationNames))
                put("showDeepSkyObjects", JsonPrimitive(s.showDeepSkyObjects))
                put("showMilkyWay", JsonPrimitive(s.showMilkyWay))
                put("starMagnitudeLimit", JsonPrimitive(s.starMagnitudeLimit))
                put("coordinateStyle", json.encodeToJsonElement(CoordinateStyle.serializer(), s.coordinateStyle))
                put("temperatureCelsius", JsonPrimitive(s.temperatureCelsius))
                put("pressureMillibars", JsonPrimitive(s.pressureMillibars))
                put("favoriteObjectIDs", JsonArray(s.favoriteObjectIDs.map(::JsonPrimitive)))
                put("recentObjectIDs", JsonArray(s.recentObjectIDs.map(::JsonPrimitive)))
            }
            return o.toString()
        }

        /** Decodes stored settings key by key; anything missing or invalid takes its default. */
        fun decode(text: String): AppSettings {
            val o = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return AppSettings()
            val d = AppSettings()
            fun <T> value(key: String, serializer: KSerializer<T>, fallback: T): T =
                o[key]?.let { runCatching { json.decodeFromJsonElement(serializer, it) }.getOrNull() } ?: fallback
            val strings = ListSerializer(String.serializer())
            val sites = (o["savedSites"] as? JsonArray)?.mapNotNull(SavedSite::fromJson) ?: emptyList()
            return AppSettings(
                hasCompletedOnboarding = value("hasCompletedOnboarding", Boolean.serializer(), d.hasCompletedOnboarding),
                locationSource = value("locationSource", LocationSource.serializer(), d.locationSource),
                savedSites = sites,
                mountPresetID = value("mountPresetID", String.serializer(), d.mountPresetID),
                headingSource = value("headingSource", HeadingSource.serializer(), d.headingSource),
                smoothingTimeConstant = value("smoothingTimeConstant", Double.serializer(), d.smoothingTimeConstant),
                sensorID = value("sensorID", String.serializer(), d.sensorID),
                customSensorWidth = value("customSensorWidth", Double.serializer(), d.customSensorWidth),
                customSensorHeight = value("customSensorHeight", Double.serializer(), d.customSensorHeight),
                focalLength = value("focalLength", Double.serializer(), d.focalLength),
                aperture = value("aperture", Double.serializer(), d.aperture),
                pixelPitch = value("pixelPitch", Double.serializer().nullable, d.pixelPitch),
                nightMode = value("nightMode", NightModeSetting.serializer(), d.nightMode),
                nightIntensity = value("nightIntensity", Double.serializer(), d.nightIntensity),
                dimScreenAtNight = value("dimScreenAtNight", Boolean.serializer(), d.dimScreenAtNight),
                nightBrightness = value("nightBrightness", Double.serializer(), d.nightBrightness),
                redFilterOverlay = value("redFilterOverlay", Boolean.serializer(), d.redFilterOverlay),
                cameraPreviewEnabled = value("cameraPreviewEnabled", Boolean.serializer(), d.cameraPreviewEnabled),
                cameraPreviewAtNight = value("cameraPreviewAtNight", Boolean.serializer(), d.cameraPreviewAtNight),
                hapticsEnabled = value("hapticsEnabled", Boolean.serializer(), d.hapticsEnabled),
                audioCuesEnabled = value("audioCuesEnabled", Boolean.serializer(), d.audioCuesEnabled),
                keepScreenAwake = value("keepScreenAwake", Boolean.serializer(), d.keepScreenAwake),
                showConstellationLines = value("showConstellationLines", Boolean.serializer(), d.showConstellationLines),
                showConstellationNames = value("showConstellationNames", Boolean.serializer(), d.showConstellationNames),
                showDeepSkyObjects = value("showDeepSkyObjects", Boolean.serializer(), d.showDeepSkyObjects),
                showMilkyWay = value("showMilkyWay", Boolean.serializer(), d.showMilkyWay),
                starMagnitudeLimit = value("starMagnitudeLimit", Double.serializer(), d.starMagnitudeLimit),
                coordinateStyle = value("coordinateStyle", CoordinateStyle.serializer(), d.coordinateStyle),
                temperatureCelsius = value("temperatureCelsius", Double.serializer(), d.temperatureCelsius),
                pressureMillibars = value("pressureMillibars", Double.serializer(), d.pressureMillibars),
                favoriteObjectIDs = value("favoriteObjectIDs", strings, d.favoriteObjectIDs),
                recentObjectIDs = value("recentObjectIDs", strings, d.recentObjectIDs),
            ).sanitized()
        }
    }
}
