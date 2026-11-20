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
import net.kuecker.lodestar.kit.ephemeris.GreatRedSpot
import net.kuecker.lodestar.kit.foundation.Atmosphere
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.kit.planning.ImagingFilter
import net.kuecker.lodestar.kit.pointing.MountProfile
import java.time.DateTimeException
import java.time.Instant
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
    /** The recorded skyline (trees, mountains), or null for an open horizon. */
    val horizon: HorizonProfile? = null,
    /**
     * The skyline of the terrain computed from elevation data (hills, mountains), or null when none was computed. Unlike
     * [horizon] a flat one is kept: it says that the terrain was looked at and is flat. It is used where no skyline was
     * recorded, and for the stretches a partial sweep left open.
     */
    val terrainHorizon: HorizonProfile? = null,
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
        horizon: HorizonProfile? = this.horizon,
        terrainHorizon: HorizonProfile? = this.terrainHorizon,
    ): SavedSite? = create(name, latitude, longitude, elevation, timeZoneIdentifier, id, horizon, terrainHorizon)

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
            horizon: HorizonProfile? = null,
            terrainHorizon: HorizonProfile? = null,
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
                horizon = horizon?.takeUnless { it.isFlat },
                terrainHorizon = terrainHorizon,
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
            site.horizon?.let { put("horizon", Json.encodeToJsonElement(HorizonProfile.serializer(), it)) }
            site.terrainHorizon?.let { put("terrainHorizon", Json.encodeToJsonElement(HorizonProfile.serializer(), it)) }
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
                // A damaged skyline is dropped rather than losing the site.
                horizon = o["horizon"]?.let { runCatching { Json.decodeFromJsonElement(HorizonProfile.serializer(), it) }.getOrNull() },
                terrainHorizon = o["terrainHorizon"]?.let {
                    runCatching { Json.decodeFromJsonElement(HorizonProfile.serializer(), it) }.getOrNull()
                },
            )
        }.getOrNull()
    }
}

/**
 * The Great Red Spot's System II longitude as the user read it ([longitude], degrees [0, 360)) on the day [asOf].
 * The kit moves it on from there with the spot's usual drift. Absent, the kit's dated default applies.
 */
data class GreatRedSpotSetting(val longitude: Double, val asOf: Instant) {
    /** The spot this describes, or `null` when the values are not usable. */
    val spot: GreatRedSpot? get() = GreatRedSpot.create(longitude, asOf)

    internal fun toJson(): JsonObject = buildJsonObject {
        put("longitude", JsonPrimitive(longitude))
        put("asOf", JsonPrimitive(asOf.toString()))
    }

    companion object {
        /** Reads the stored object; `null` when anything is missing or out of range. */
        internal fun fromJson(element: JsonElement?): GreatRedSpotSetting? {
            val o = element as? JsonObject ?: return null
            val longitude = o["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
            val asOf = runCatching { Instant.parse(o["asOf"]?.jsonPrimitive?.content ?: return null) }.getOrNull() ?: return null
            return GreatRedSpotSetting(longitude, asOf).takeIf { it.spot != null }
        }
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

/** How fast spoken guidance talks. */
@Serializable
enum class SpokenGuidanceRate(val title: String) {
    @SerialName("slow") SLOW("Slow"),
    @SerialName("normal") NORMAL("Normal"),
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

    // Camera and lens (for the framing rectangle and exposure tools). The camera profiles are the source; the single
    // camera keys below them follow the active profile (see `sanitized`), so code that reads them keeps working.
    val cameraProfiles: List<CameraProfile> = emptyList(),
    val activeCameraProfileID: String? = null,
    val sensorID: String = SensorFormat.fullFrame.id,
    val customSensorWidth: Double = 36.0,
    val customSensorHeight: Double = 24.0,
    val focalLength: Double = 24.0,
    val aperture: Double = 2.8,
    val pixelPitch: Double? = null,
    /** The filter in front of the sensor: it decides how far the Moon warnings are relaxed for emission nebulae. */
    val imagingFilter: ImagingFilter = ImagingFilter.NONE,

    // Exposure calculator
    /** The angle between a tracker's axis and the pole that the Tracked section works with, in degrees. */
    val polarErrorDegrees: Double = 1.0,
    /** The ISO speed of the Moon section. */
    val exposureISO: Int = 100,

    // Night mode
    val nightMode: NightModeSetting = NightModeSetting.AUTOMATIC,
    val nightIntensity: Double = 0.7,
    val dimScreenAtNight: Boolean = true,
    val nightBrightness: Double = 0.2,
    /** The screen brightness of the red torch. */
    val torchBrightness: Double = 0.3,
    /** Experimental: multiply-blend red layer over the whole UI. */
    val redFilterOverlay: Boolean = false,
    val cameraPreviewEnabled: Boolean = true,
    val cameraPreviewAtNight: Boolean = false,

    // Feedback
    val hapticsEnabled: Boolean = true,
    val audioCuesEnabled: Boolean = false,
    val keepScreenAwake: Boolean = true,
    /** Speaks the pan and tilt to make while aiming ("Pan right 12, tilt up 3"). */
    val spokenGuidance: Boolean = false,
    val spokenGuidanceRate: SpokenGuidanceRate = SpokenGuidanceRate.NORMAL,
    /** Shows the aiming on a paired Wear OS watch while Aim is open (nothing is sent without a watch). */
    val aimOnWatch: Boolean = true,

    // Sky display
    val showConstellationLines: Boolean = true,
    val showConstellationNames: Boolean = true,
    val showDeepSkyObjects: Boolean = true,
    val showMilkyWay: Boolean = true,
    /** A thin ring on the sky chart's horizon that is brighter toward the artificial light. */
    val showHorizonGlow: Boolean = true,
    /** Deep-sky objects drawn in the sky views, up to this `Prominence` level (1 showpieces … 4 everything). */
    val deepSkyProminence: Int = 2,
    val starMagnitudeLimit: Double = 5.0,
    val coordinateStyle: CoordinateStyle = CoordinateStyle.SEXAGESIMAL,

    // Atmosphere (refraction)
    val temperatureCelsius: Double = 10.0,
    val pressureMillibars: Double = 1010.0,

    // Objects
    val favoriteObjectIDs: List<String> = emptyList(),
    val recentObjectIDs: List<String> = emptyList(),

    // Jupiter
    /** The Great Red Spot's longitude the user entered, or `null` for the kit's dated default. */
    val greatRedSpot: GreatRedSpotSetting? = null,
) {
    val atmosphere: Atmosphere get() = Atmosphere(pressureMillibars, temperatureCelsius)

    /** The Great Red Spot to compute with: the user's longitude, else [GreatRedSpot.reference]. */
    val greatRedSpotOrDefault: GreatRedSpot get() = greatRedSpot?.spot ?: GreatRedSpot.reference

    /**
     * The camera and lens in use: the stored profile with [activeCameraProfileID], else the first one, else a profile
     * made from the single camera keys (before the profiles exist).
     */
    val activeCameraProfile: CameraProfile
        get() = cameraProfiles.firstOrNull { it.id == activeCameraProfileID }
            ?: cameraProfiles.firstOrNull()
            ?: singleCameraProfile()

    /** The camera of the single camera keys as a profile, with the identifier of the migrated profile. */
    private fun singleCameraProfile(): CameraProfile = CameraProfile.create(
        CameraProfile.DEFAULT_NAME, sensorID, customSensorWidth, customSensorHeight, focalLength, aperture, pixelPitch,
        id = CameraProfile.MIGRATED_ID,
    )

    /**
     * Takes a change that [previous] settings went through to its single camera keys alone (code that predates the
     * profiles writes them) into the active profile. A change to the profiles themselves wins, and so does any
     * change to the profiles made together with one to the keys; [sanitized] then lets the keys follow the profile.
     */
    fun reconcilingCamera(previous: AppSettings): AppSettings {
        val keysChanged = sensorID != previous.sensorID || customSensorWidth != previous.customSensorWidth ||
            customSensorHeight != previous.customSensorHeight || focalLength != previous.focalLength ||
            aperture != previous.aperture || pixelPitch != previous.pixelPitch
        val profilesChanged = cameraProfiles != previous.cameraProfiles ||
            activeCameraProfileID != previous.activeCameraProfileID
        if (!keysChanged || profilesChanged || cameraProfiles.isEmpty()) return this
        val active = activeCameraProfile
        val updated = active.edited(
            sensorID = sensorID, customSensorWidth = customSensorWidth, customSensorHeight = customSensorHeight,
            focalLength = focalLength, aperture = aperture, pixelPitch = pixelPitch,
        )
        return copy(cameraProfiles = cameraProfiles.map { if (it.id == active.id) updated else it })
    }

    /**
     * Clamps numeric preferences into their supported ranges (protects against corrupted storage), makes sure there
     * is a camera profile and an active one (the first start with profiles turns the single camera into "My camera"),
     * and sets the single camera keys to the active profile.
     */
    fun sanitized(): AppSettings {
        fun clamp(v: Double, range: ClosedFloatingPointRange<Double>, fallback: Double) =
            if (v.isFinite()) v.coerceIn(range) else fallback
        val source = locationSource
        val profiles = cameraProfiles.distinctBy { it.id }.take(MAXIMUM_CAMERA_PROFILES)
            .ifEmpty { listOf(singleCameraProfile()) }
        val active = profiles.firstOrNull { it.id == activeCameraProfileID } ?: profiles.first()
        return copy(
            cameraProfiles = profiles,
            activeCameraProfileID = active.id,
            sensorID = active.sensorID,
            customSensorWidth = active.customSensorWidth,
            customSensorHeight = active.customSensorHeight,
            focalLength = active.focalLength,
            aperture = active.aperture,
            pixelPitch = active.pixelPitch,
            smoothingTimeConstant = clamp(smoothingTimeConstant, 0.0..1.0, 0.12),
            polarErrorDegrees = clamp(polarErrorDegrees, POLAR_ERROR_RANGE, 1.0),
            exposureISO = exposureISO.coerceIn(EXPOSURE_ISO_RANGE),
            nightIntensity = clamp(nightIntensity, 0.25..1.0, 0.7),
            nightBrightness = clamp(nightBrightness, 0.01..1.0, 0.2),
            torchBrightness = clamp(torchBrightness, 0.01..1.0, 0.3),
            starMagnitudeLimit = clamp(starMagnitudeLimit, 1.0..6.5, 5.0),
            deepSkyProminence = deepSkyProminence.coerceIn(1, 4),
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
            greatRedSpot = greatRedSpot?.takeIf { it.spot != null },
        )
    }

    companion object {
        const val MAXIMUM_SAVED_SITES = 100
        const val MAXIMUM_CAMERA_PROFILES = 50
        const val MAXIMUM_RECENTS = 20
        const val MAXIMUM_FAVORITES = 200

        /** The polar-alignment errors the exposure calculator offers, in degrees. */
        val POLAR_ERROR_RANGE: ClosedFloatingPointRange<Double> = 0.1..5.0

        /** The ISO speeds the exposure calculator offers. */
        val EXPOSURE_ISO_RANGE: IntRange = 100..12800

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
                put("cameraProfiles", JsonArray(s.cameraProfiles.map(CameraProfile::toJson)))
                s.activeCameraProfileID?.let { put("activeCameraProfileID", JsonPrimitive(it)) }
                put("sensorID", JsonPrimitive(s.sensorID))
                put("customSensorWidth", JsonPrimitive(s.customSensorWidth))
                put("customSensorHeight", JsonPrimitive(s.customSensorHeight))
                put("focalLength", JsonPrimitive(s.focalLength))
                put("aperture", JsonPrimitive(s.aperture))
                s.pixelPitch?.let { put("pixelPitch", JsonPrimitive(it)) }
                put("imagingFilter", json.encodeToJsonElement(ImagingFilter.serializer(), s.imagingFilter))
                put("polarErrorDegrees", JsonPrimitive(s.polarErrorDegrees))
                put("exposureISO", JsonPrimitive(s.exposureISO))
                put("nightMode", json.encodeToJsonElement(NightModeSetting.serializer(), s.nightMode))
                put("nightIntensity", JsonPrimitive(s.nightIntensity))
                put("dimScreenAtNight", JsonPrimitive(s.dimScreenAtNight))
                put("nightBrightness", JsonPrimitive(s.nightBrightness))
                put("torchBrightness", JsonPrimitive(s.torchBrightness))
                put("redFilterOverlay", JsonPrimitive(s.redFilterOverlay))
                put("cameraPreviewEnabled", JsonPrimitive(s.cameraPreviewEnabled))
                put("cameraPreviewAtNight", JsonPrimitive(s.cameraPreviewAtNight))
                put("hapticsEnabled", JsonPrimitive(s.hapticsEnabled))
                put("audioCuesEnabled", JsonPrimitive(s.audioCuesEnabled))
                put("keepScreenAwake", JsonPrimitive(s.keepScreenAwake))
                put("spokenGuidance", JsonPrimitive(s.spokenGuidance))
                put("spokenGuidanceRate", json.encodeToJsonElement(SpokenGuidanceRate.serializer(), s.spokenGuidanceRate))
                put("aimOnWatch", JsonPrimitive(s.aimOnWatch))
                put("showConstellationLines", JsonPrimitive(s.showConstellationLines))
                put("showConstellationNames", JsonPrimitive(s.showConstellationNames))
                put("showDeepSkyObjects", JsonPrimitive(s.showDeepSkyObjects))
                put("showMilkyWay", JsonPrimitive(s.showMilkyWay))
                put("showHorizonGlow", JsonPrimitive(s.showHorizonGlow))
                put("deepSkyProminence", JsonPrimitive(s.deepSkyProminence))
                put("starMagnitudeLimit", JsonPrimitive(s.starMagnitudeLimit))
                put("coordinateStyle", json.encodeToJsonElement(CoordinateStyle.serializer(), s.coordinateStyle))
                put("temperatureCelsius", JsonPrimitive(s.temperatureCelsius))
                put("pressureMillibars", JsonPrimitive(s.pressureMillibars))
                put("favoriteObjectIDs", JsonArray(s.favoriteObjectIDs.map(::JsonPrimitive)))
                put("recentObjectIDs", JsonArray(s.recentObjectIDs.map(::JsonPrimitive)))
                s.greatRedSpot?.let { put("greatRedSpot", it.toJson()) }
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
            val profiles = (o["cameraProfiles"] as? JsonArray)?.mapNotNull(CameraProfile::fromJson) ?: emptyList()
            return AppSettings(
                hasCompletedOnboarding = value("hasCompletedOnboarding", Boolean.serializer(), d.hasCompletedOnboarding),
                locationSource = value("locationSource", LocationSource.serializer(), d.locationSource),
                savedSites = sites,
                mountPresetID = value("mountPresetID", String.serializer(), d.mountPresetID),
                headingSource = value("headingSource", HeadingSource.serializer(), d.headingSource),
                smoothingTimeConstant = value("smoothingTimeConstant", Double.serializer(), d.smoothingTimeConstant),
                cameraProfiles = profiles,
                activeCameraProfileID = value("activeCameraProfileID", String.serializer().nullable, d.activeCameraProfileID),
                sensorID = value("sensorID", String.serializer(), d.sensorID),
                customSensorWidth = value("customSensorWidth", Double.serializer(), d.customSensorWidth),
                customSensorHeight = value("customSensorHeight", Double.serializer(), d.customSensorHeight),
                focalLength = value("focalLength", Double.serializer(), d.focalLength),
                aperture = value("aperture", Double.serializer(), d.aperture),
                pixelPitch = value("pixelPitch", Double.serializer().nullable, d.pixelPitch),
                imagingFilter = value("imagingFilter", ImagingFilter.serializer(), d.imagingFilter),
                polarErrorDegrees = value("polarErrorDegrees", Double.serializer(), d.polarErrorDegrees),
                exposureISO = value("exposureISO", Int.serializer(), d.exposureISO),
                nightMode = value("nightMode", NightModeSetting.serializer(), d.nightMode),
                nightIntensity = value("nightIntensity", Double.serializer(), d.nightIntensity),
                dimScreenAtNight = value("dimScreenAtNight", Boolean.serializer(), d.dimScreenAtNight),
                nightBrightness = value("nightBrightness", Double.serializer(), d.nightBrightness),
                torchBrightness = value("torchBrightness", Double.serializer(), d.torchBrightness),
                redFilterOverlay = value("redFilterOverlay", Boolean.serializer(), d.redFilterOverlay),
                cameraPreviewEnabled = value("cameraPreviewEnabled", Boolean.serializer(), d.cameraPreviewEnabled),
                cameraPreviewAtNight = value("cameraPreviewAtNight", Boolean.serializer(), d.cameraPreviewAtNight),
                hapticsEnabled = value("hapticsEnabled", Boolean.serializer(), d.hapticsEnabled),
                audioCuesEnabled = value("audioCuesEnabled", Boolean.serializer(), d.audioCuesEnabled),
                keepScreenAwake = value("keepScreenAwake", Boolean.serializer(), d.keepScreenAwake),
                spokenGuidance = value("spokenGuidance", Boolean.serializer(), d.spokenGuidance),
                spokenGuidanceRate = value("spokenGuidanceRate", SpokenGuidanceRate.serializer(), d.spokenGuidanceRate),
                aimOnWatch = value("aimOnWatch", Boolean.serializer(), d.aimOnWatch),
                showConstellationLines = value("showConstellationLines", Boolean.serializer(), d.showConstellationLines),
                showConstellationNames = value("showConstellationNames", Boolean.serializer(), d.showConstellationNames),
                showDeepSkyObjects = value("showDeepSkyObjects", Boolean.serializer(), d.showDeepSkyObjects),
                showMilkyWay = value("showMilkyWay", Boolean.serializer(), d.showMilkyWay),
                showHorizonGlow = value("showHorizonGlow", Boolean.serializer(), d.showHorizonGlow),
                deepSkyProminence = value("deepSkyProminence", Int.serializer(), d.deepSkyProminence),
                starMagnitudeLimit = value("starMagnitudeLimit", Double.serializer(), d.starMagnitudeLimit),
                coordinateStyle = value("coordinateStyle", CoordinateStyle.serializer(), d.coordinateStyle),
                temperatureCelsius = value("temperatureCelsius", Double.serializer(), d.temperatureCelsius),
                pressureMillibars = value("pressureMillibars", Double.serializer(), d.pressureMillibars),
                favoriteObjectIDs = value("favoriteObjectIDs", strings, d.favoriteObjectIDs),
                recentObjectIDs = value("recentObjectIDs", strings, d.recentObjectIDs),
                greatRedSpot = GreatRedSpotSetting.fromJson(o["greatRedSpot"]) ?: d.greatRedSpot,
            ).sanitized()
        }
    }
}
