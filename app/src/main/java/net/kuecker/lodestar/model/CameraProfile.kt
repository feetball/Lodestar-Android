package net.kuecker.lodestar.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import net.kuecker.lodestar.kit.phototools.CameraPhotoSuggestion
import net.kuecker.lodestar.kit.phototools.CameraPreset
import net.kuecker.lodestar.kit.phototools.LensProjection
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import java.util.UUID

/**
 * A camera and lens the photographer uses, kept in the settings: the sensor (a preset or a custom size), focal length,
 * aperture, pixel pitch, the lens projection and, for a circular fisheye, the image circle.
 *
 * Every value is inside the ranges the settings keep, so a profile can be used as it is. The constructor is private;
 * [create] limits the values and [fromJson] reads a stored profile.
 */
@ConsistentCopyVisibility
data class CameraProfile private constructor(
    /** A UUID that stays with the profile. */
    val id: String,
    /** The name in the list, at most [MAXIMUM_NAME_LENGTH] characters. */
    val name: String,
    /** The id of a [SensorFormat] preset, or [CUSTOM_SENSOR_ID] when the sensor has the size below. */
    val sensorID: String,
    /** The width in millimetres of a custom sensor (kept while a preset is chosen). */
    val customSensorWidth: Double,
    /** The height in millimetres of a custom sensor. */
    val customSensorHeight: Double,
    /** The real focal length in millimetres. */
    val focalLength: Double,
    /** The f-number. */
    val aperture: Double,
    /** The pixel pitch in micrometres, or null if unknown. */
    val pixelPitch: Double?,
    /** How the lens maps the sky onto the sensor. */
    val projection: LensProjection,
    /** The diameter in millimetres of the image circle of a circular fisheye, or null if it covers the sensor. */
    val imageCircleDiameter: Double?,
) {
    /** The sensor of the profile. */
    val sensor: SensorFormat
        get() = SensorFormat.presets.firstOrNull { it.id == sensorID }
            ?: SensorFormat.custom(customSensorWidth, customSensorHeight)

    /** Whether the sensor has a custom size rather than a preset's. */
    val isCustomSensor: Boolean
        get() = SensorFormat.presets.none { it.id == sensorID }

    /** The profile as the camera and lens the engine works with. */
    val lensSetup: LensSetup
        get() = LensSetup(sensor, focalLength, aperture, pixelPitch, projection, imageCircleDiameter)

    /** A copy with the values given, limited like [create]; the identifier stays. */
    fun edited(
        name: String = this.name,
        sensorID: String = this.sensorID,
        customSensorWidth: Double = this.customSensorWidth,
        customSensorHeight: Double = this.customSensorHeight,
        focalLength: Double = this.focalLength,
        aperture: Double = this.aperture,
        pixelPitch: Double? = this.pixelPitch,
        projection: LensProjection = this.projection,
        imageCircleDiameter: Double? = this.imageCircleDiameter,
    ): CameraProfile = create(
        name, sensorID, customSensorWidth, customSensorHeight, focalLength, aperture, pixelPitch, projection,
        imageCircleDiameter, id,
    )

    companion object {
        /** The longest name of a profile, in characters. */
        const val MAXIMUM_NAME_LENGTH = 40

        /** The sensor identifier of a profile with a custom sensor size. */
        const val CUSTOM_SENSOR_ID = "custom"

        /** The name of the profile made from the camera of earlier versions. */
        const val DEFAULT_NAME = "My camera"

        /** The name of a profile made from a photo that does not name its camera. */
        const val PHOTO_NAME = "Camera from photo"

        /** The identifier of the profile made from the camera of earlier versions, the same on every device. */
        const val MIGRATED_ID = "00000000-0000-4000-8000-000000000001"

        /** The supported custom sensor sizes, in millimetres. */
        val customSensorRange: ClosedFloatingPointRange<Double> = 1.0..100.0

        /** The supported pixel pitches, in micrometres. */
        val pixelPitchRange: ClosedFloatingPointRange<Double> = 0.5..20.0

        /**
         * Creates a profile, limiting every value to its range: a number that is not finite takes the default of a
         * 24 mm f/2.8 full-frame lens, a pixel pitch or image circle out of range is dropped, a sensor that is not
         * a preset is custom, and a name is trimmed, cut at [MAXIMUM_NAME_LENGTH] and replaced by "Camera" when empty.
         * A [id] that is not a UUID is replaced by a new one.
         */
        fun create(
            name: String,
            sensorID: String = SensorFormat.fullFrame.id,
            customSensorWidth: Double = 36.0,
            customSensorHeight: Double = 24.0,
            focalLength: Double = LensSetup.defaultFocalLengthMillimeters,
            aperture: Double = LensSetup.defaultAperture,
            pixelPitch: Double? = null,
            projection: LensProjection = LensProjection.RECTILINEAR,
            imageCircleDiameter: Double? = null,
            id: String = UUID.randomUUID().toString(),
        ): CameraProfile {
            fun limited(value: Double, range: ClosedFloatingPointRange<Double>, fallback: Double) =
                if (value.isFinite()) value.coerceIn(range) else fallback
            return CameraProfile(
                id = if (runCatching { UUID.fromString(id) }.isSuccess) id else UUID.randomUUID().toString(),
                name = limitedName(name),
                sensorID = if (SensorFormat.presets.any { it.id == sensorID }) sensorID else CUSTOM_SENSOR_ID,
                customSensorWidth = limited(customSensorWidth, customSensorRange, 36.0),
                customSensorHeight = limited(customSensorHeight, customSensorRange, 24.0),
                focalLength = limited(focalLength, LensSetup.focalLengthRange, LensSetup.defaultFocalLengthMillimeters),
                aperture = limited(aperture, LensSetup.apertureRange, LensSetup.defaultAperture),
                pixelPitch = pixelPitch?.takeIf { it.isFinite() && it in pixelPitchRange },
                projection = projection,
                imageCircleDiameter = imageCircleDiameter?.takeIf { it.isFinite() && it in LensSetup.imageCircleRange },
            )
        }

        /** The profile of a preset: its sensor, lens, pixel pitch and projection, named after it. */
        fun fromPreset(preset: CameraPreset, id: String = UUID.randomUUID().toString()): CameraProfile {
            val isPresetSensor = SensorFormat.presets.any { it.id == preset.sensor.id }
            return create(
                name = preset.name,
                sensorID = if (isPresetSensor) preset.sensor.id else CUSTOM_SENSOR_ID,
                customSensorWidth = preset.sensor.widthMillimeters,
                customSensorHeight = preset.sensor.heightMillimeters,
                focalLength = preset.focalLengthMillimeters,
                aperture = preset.aperture,
                pixelPitch = preset.pixelPitchMicrometers,
                projection = preset.projection,
                id = id,
            )
        }

        /**
         * The profile a photo suggests (see `CameraPhotoMetadata`), named after the camera that took it. What the
         * photo does not say stays at the defaults of a 24 mm f/2.8 full-frame lens, so the photographer sees it in the
         * editor and corrects it.
         */
        fun fromPhoto(suggestion: CameraPhotoSuggestion, id: String = UUID.randomUUID().toString()): CameraProfile {
            val sensor = suggestion.sensor
            val presetID = sensor?.id?.takeIf { sensorID -> SensorFormat.presets.any { it.id == sensorID } }
            return create(
                name = suggestion.name ?: PHOTO_NAME,
                sensorID = presetID ?: if (sensor != null) CUSTOM_SENSOR_ID else SensorFormat.fullFrame.id,
                customSensorWidth = sensor?.widthMillimeters ?: 36.0,
                customSensorHeight = sensor?.heightMillimeters ?: 24.0,
                focalLength = suggestion.focalLengthMillimeters,
                aperture = suggestion.aperture ?: LensSetup.defaultAperture,
                pixelPitch = suggestion.pixelPitchMicrometers,
                id = id,
            )
        }

        /** [name] trimmed and cut to [MAXIMUM_NAME_LENGTH] characters without splitting a surrogate pair. */
        fun limitedName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return "Camera"
            var end = minOf(trimmed.length, MAXIMUM_NAME_LENGTH)
            if (end < trimmed.length && Character.isHighSurrogate(trimmed[end - 1])) end -= 1
            return trimmed.substring(0, end).trim().ifEmpty { "Camera" }
        }

        internal fun toJson(profile: CameraProfile): JsonElement = buildJsonObject {
            put("id", JsonPrimitive(profile.id))
            put("name", JsonPrimitive(profile.name))
            put("sensorID", JsonPrimitive(profile.sensorID))
            put("customSensorWidth", JsonPrimitive(profile.customSensorWidth))
            put("customSensorHeight", JsonPrimitive(profile.customSensorHeight))
            put("focalLength", JsonPrimitive(profile.focalLength))
            put("aperture", JsonPrimitive(profile.aperture))
            profile.pixelPitch?.let { put("pixelPitch", JsonPrimitive(it)) }
            put("projection", JsonPrimitive(profile.projection.rawValue))
            profile.imageCircleDiameter?.let { put("imageCircleDiameter", JsonPrimitive(it)) }
        }

        /**
         * Decodes a stored profile; null for an entry that is not an object or has no valid UUID (the caller drops
         * it). Other missing or invalid values take their defaults, so one bad number does not lose the profile.
         */
        internal fun fromJson(element: JsonElement): CameraProfile? {
            val o = element as? JsonObject ?: return null
            fun text(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun number(key: String): Double? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull
            val id = text("id")?.takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: return null
            return create(
                name = text("name") ?: "Camera",
                sensorID = text("sensorID") ?: SensorFormat.fullFrame.id,
                customSensorWidth = number("customSensorWidth") ?: 36.0,
                customSensorHeight = number("customSensorHeight") ?: 24.0,
                focalLength = number("focalLength") ?: LensSetup.defaultFocalLengthMillimeters,
                aperture = number("aperture") ?: LensSetup.defaultAperture,
                pixelPitch = number("pixelPitch"),
                projection = text("projection")?.let(LensProjection::fromRawValue) ?: LensProjection.RECTILINEAR,
                imageCircleDiameter = number("imageCircleDiameter"),
                id = id,
            )
        }
    }
}
