package net.kuecker.lodestar.kit.phototools

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kuecker.lodestar.kit.formatting.NumberText
import kotlin.math.sqrt

/**
 * The imaging area of a camera sensor.
 *
 * Sensor formats feed the field-of-view and exposure calculations. Width and height are the
 * dimensions of the imaging area in millimetres, with the camera held in landscape orientation.
 * Use a preset such as [fullFrame], pick one from [presets], or build one with
 * [custom]. Every value of this type has a size within [dimensionRange], so the
 * arithmetic built on it cannot meet a zero, negative or non-finite dimension; decoding rejects
 * stored data that would break this.
 *
 * The JSON form has the keys `id`, `name`, `widthMillimeters` and `heightMillimeters`, as in the Swift
 * original. Decoding ignores unknown keys, whatever the `Json` configuration.
 */
@Serializable(with = SensorFormatSerializer::class)
@ConsistentCopyVisibility
data class SensorFormat private constructor(
    /** A stable identifier: the preset's name, or `custom-<width>x<height>` for custom sizes. */
    val id: String,
    /** A short name for pickers, for example "Full Frame". */
    val name: String,
    /** The sensor width in millimetres. */
    val widthMillimeters: Double,
    /** The sensor height in millimetres. */
    val heightMillimeters: Double,
) {
    // MARK: Derived geometry

    /** The sensor diagonal in millimetres. */
    val diagonalMillimeters: Double
        get() = sqrt(widthMillimeters * widthMillimeters + heightMillimeters * heightMillimeters)

    /**
     * The crop factor: the diagonal of a 36 × 24 mm frame divided by this sensor's diagonal.
     *
     * A 50 mm lens on a sensor with crop factor 1.5 frames like a 75 mm lens on full frame.
     */
    val cropFactor: Double
        get() = fullFrameDiagonalMillimeters / diagonalMillimeters

    /**
     * Estimates the pixel pitch, the centre-to-centre spacing of photosites, from the resolution.
     *
     * Pixels are assumed square and to cover the whole sensor, so the pitch is
     * `sqrt(width × height / pixelCount)`. Nominal sensor sizes are rounded, so this is an estimate;
     * prefer the manufacturer's published pitch when it is known.
     *
     * @param megapixels The image resolution in millions of pixels.
     * @return The pitch in micrometres, or null if [megapixels] is not a positive finite number or
     *   implies a pitch outside [pixelPitchRange].
     */
    fun pixelPitchMicrometers(megapixels: Double): Double? {
        if (!megapixels.isFinite() || !(megapixels > 0.0)) return null
        val pixelCount = megapixels * 1_000_000.0
        val pitch = 1000.0 * sqrt(widthMillimeters * heightMillimeters / pixelCount)
        return if (pixelPitchRange.contains(pitch)) pitch else null
    }

    companion object {
        // MARK: Validation ranges

        /** The accepted range, in millimetres, for either dimension of a sensor. */
        val dimensionRange: ClosedFloatingPointRange<Double> = 1.0..200.0

        /**
         * The accepted range, in micrometres, for a pixel pitch.
         *
         * Wide enough for every consumer camera, from smartphones to low-light bodies with very large
         * pixels, while still rejecting values that are obviously a unit mistake.
         */
        val pixelPitchRange: ClosedFloatingPointRange<Double> = 0.3..100.0

        // MARK: Presets

        /** 35 mm full frame, 36 × 24 mm. */
        val fullFrame = SensorFormat(id = "fullFrame", name = "Full Frame", widthMillimeters = 36.0, heightMillimeters = 24.0)

        /** APS-C as used by Nikon, Sony, Fujifilm and Pentax, 23.5 × 15.6 mm (crop factor about 1.53). */
        val apsC = SensorFormat(id = "apsC", name = "APS-C", widthMillimeters = 23.5, heightMillimeters = 15.6)

        /** APS-C as used by Canon, 22.3 × 14.9 mm (crop factor about 1.61). */
        val apsCCanon = SensorFormat(
            id = "apsCCanon", name = "APS-C (Canon)", widthMillimeters = 22.3, heightMillimeters = 14.9,
        )

        /** Micro Four Thirds, 17.3 × 13 mm (crop factor about 2.0). */
        val microFourThirds = SensorFormat(
            id = "microFourThirds", name = "Micro Four Thirds", widthMillimeters = 17.3, heightMillimeters = 13.0,
        )

        /** One-inch type, 13.2 × 8.8 mm (crop factor about 2.7). */
        val oneInch = SensorFormat(id = "oneInch", name = "1-inch", widthMillimeters = 13.2, heightMillimeters = 8.8)

        /** Medium format 44 × 33, 43.8 × 32.9 mm (crop factor about 0.79). */
        val mediumFormat44x33 = SensorFormat(
            id = "mediumFormat44x33", name = "Medium Format 44\u00D733", widthMillimeters = 43.8, heightMillimeters = 32.9,
        )

        /** All presets, in the order a picker should list them. */
        val presets: List<SensorFormat> = listOf(
            fullFrame, apsC, apsCCanon, microFourThirds, oneInch, mediumFormat44x33,
        )

        /**
         * Creates a sensor format of the given size, limiting each dimension to [dimensionRange].
         *
         * A dimension that is not finite becomes the corresponding full-frame dimension (36 or
         * 24 mm). Use [validated] to reject invalid input instead of adjusting it.
         *
         * @param width The sensor width in millimetres.
         * @param height The sensor height in millimetres.
         */
        fun custom(width: Double, height: Double): SensorFormat {
            val limitedWidth = RangeLimiter.limit(width, dimensionRange, fullFrame.widthMillimeters)
            val limitedHeight = RangeLimiter.limit(height, dimensionRange, fullFrame.heightMillimeters)
            val widthLabel = NumberText.trimmedDescription(limitedWidth, 2)
            val heightLabel = NumberText.trimmedDescription(limitedHeight, 2)
            return SensorFormat(
                id = "custom-${NumberText.swiftDescription(limitedWidth)}x${NumberText.swiftDescription(limitedHeight)}",
                name = "Custom $widthLabel \u00D7 $heightLabel mm",
                widthMillimeters = limitedWidth,
                heightMillimeters = limitedHeight,
            )
        }

        /**
         * Creates a custom sensor format only if both dimensions are finite and within
         * [dimensionRange]; otherwise returns null. Use this for user input that should be rejected
         * rather than corrected.
         */
        fun validated(width: Double, height: Double): SensorFormat? {
            if (!dimensionRange.contains(width) || !dimensionRange.contains(height)) return null
            return custom(width, height)
        }

        private val fullFrameDiagonalMillimeters = sqrt(36.0 * 36.0 + 24.0 * 24.0)

        /**
         * The checks of Swift's `init(from:)`: returns null if the identifier or name is empty or
         * oversized, or a dimension is outside [dimensionRange].
         */
        internal fun decoded(id: String, name: String, width: Double, height: Double): SensorFormat? {
            if (!isValidLabel(id) || !isValidLabel(name) ||
                !dimensionRange.contains(width) || !dimensionRange.contains(height)
            ) {
                return null
            }
            return SensorFormat(id = id, name = name, widthMillimeters = width, heightMillimeters = height)
        }

        /** A label must be present and short enough to be display text rather than payload. */
        private fun isValidLabel(label: String): Boolean {
            // Every UTF-16 unit takes at least one UTF-8 byte, so a long label is too long without encoding it.
            if (label.isEmpty() || label.length > MAXIMUM_LABEL_BYTES) return false
            return label.toByteArray(Charsets.UTF_8).size <= MAXIMUM_LABEL_BYTES
        }

        private const val MAXIMUM_LABEL_BYTES = 128
    }
}

/**
 * Serializes a [SensorFormat] as a JSON object with the keys `id`, `name`, `widthMillimeters`
 * and `heightMillimeters`. Decoding reads the object itself, so unknown keys are ignored (like
 * Swift's `JSONDecoder`) and a value of the wrong type, a missing key or a value outside the
 * supported ranges throws a [SerializationException] ("dataCorrupted" in Swift terms).
 */
internal object SensorFormatSerializer : KSerializer<SensorFormat> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("net.kuecker.lodestar.kit.phototools.SensorFormat") {
            element<String>("id")
            element<String>("name")
            element<Double>("widthMillimeters")
            element<Double>("heightMillimeters")
        }

    override fun serialize(encoder: Encoder, value: SensorFormat) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.id)
            encodeStringElement(descriptor, 1, value.name)
            encodeDoubleElement(descriptor, 2, value.widthMillimeters)
            encodeDoubleElement(descriptor, 3, value.heightMillimeters)
        }
    }

    override fun deserialize(decoder: Decoder): SensorFormat {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("A sensor format can only be decoded from JSON.")
        return decode(jsonDecoder.decodeJsonElement())
    }

    /** Decodes and validates a sensor format from its JSON representation. */
    fun decode(element: JsonElement): SensorFormat {
        val fields = element as? JsonObject
            ?: throw SerializationException("Expected a JSON object for a sensor format.")
        val id = fields.requireString("id")
        val name = fields.requireString("name")
        val width = fields.requireDouble("widthMillimeters")
        val height = fields.requireDouble("heightMillimeters")
        return SensorFormat.decoded(id, name, width, height)
            ?: throw SerializationException("The sensor format has an invalid identifier, name or size.")
    }
}

// MARK: Strict JSON field access, shared with the lens setup decoder

/** The string stored under [key]; a missing key or any other JSON type throws. */
internal fun JsonObject.requireString(key: String): String {
    val primitive = this[key] as? JsonPrimitive
        ?: throw SerializationException("Expected a string for key '$key'.")
    if (!primitive.isString) throw SerializationException("Expected a string for key '$key'.")
    return primitive.content
}

/** The number stored under [key]; a missing key, a string, null or any other JSON type throws. */
internal fun JsonObject.requireDouble(key: String): Double {
    val element = this[key] ?: throw SerializationException("Missing value for key '$key'.")
    return element.strictDouble(key)
}

/** The number stored under [key], or null if the key is absent or null; any other JSON type throws. */
internal fun JsonObject.optionalDouble(key: String): Double? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return element.strictDouble(key)
}

/** A JSON number as a `Double`; quoted numbers, `true`/`false`, `NaN` and hexadecimal text are rejected. */
private fun JsonElement.strictDouble(key: String): Double {
    val primitive = this as? JsonPrimitive
    if (primitive == null || primitive is JsonNull || primitive.isString) {
        throw SerializationException("Expected a number for key '$key'.")
    }
    val content = primitive.content
    val isNumeric = content.isNotEmpty() && content.all { it in '0'..'9' || it == '-' || it == '+' || it == '.' || it == 'e' || it == 'E' }
    return (if (isNumeric) content.toDoubleOrNull() else null)
        ?: throw SerializationException("Expected a number for key '$key'.")
}
