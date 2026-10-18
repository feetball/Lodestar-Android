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
import kotlinx.serialization.json.JsonObject
import kotlin.math.PI

/**
 * A camera and lens combination: the sensor, focal length, aperture and, optionally, pixel pitch.
 *
 * Every value of this type is inside the supported ranges, whichever factory or decoder
 * produced it, so code that receives a `LensSetup` never has to re-validate it. The constructor is
 * private; create values with `LensSetup(sensor, focalLengthMillimeters, aperture, pixelPitchMicrometers)`
 * (a companion `invoke`, which limits every value to its range) or [validated].
 *
 * The JSON form has the keys `sensor`, `focalLengthMillimeters`, `aperture` and, when known,
 * `pixelPitchMicrometers`, as in the Swift original. Decoding ignores unknown keys, whatever the
 * `Json` configuration.
 */
@Serializable(with = LensSetupSerializer::class)
@ConsistentCopyVisibility
data class LensSetup private constructor(
    /** The camera's sensor. */
    val sensor: SensorFormat,
    /** The lens focal length in millimetres (the real focal length, not the full-frame equivalent). */
    val focalLengthMillimeters: Double,
    /** The working aperture as an f-number, for example 2.8. */
    val aperture: Double,
    /**
     * The sensor's pixel pitch in micrometres, or null if unknown.
     *
     * The NPF exposure rule needs it; [SensorFormat.pixelPitchMicrometers] derives it
     * from the camera's resolution.
     */
    val pixelPitchMicrometers: Double?,
) {
    // MARK: Derived values

    /**
     * The focal length multiplied by the sensor's crop factor: the focal length that frames the
     * same view on a full-frame camera.
     */
    val equivalentFocalLengthMillimeters: Double
        get() = focalLengthMillimeters * sensor.cropFactor

    /** The angular size of the frame, for a camera held in landscape orientation. */
    val fieldOfView: FrameSize
        get() = FrameSize(sensor, focalLengthMillimeters)

    /**
     * The angle on the sky covered by one pixel, in arcseconds, or null if the pixel pitch is unknown.
     *
     * Uses the small-angle relation `pitch / focal length`, which holds near the centre of the frame.
     */
    val imageScaleArcsecondsPerPixel: Double?
        get() = pixelPitchMicrometers?.let { imageScale(it) }

    /** The angle covered by one pixel of the given [pitch] behind this lens, in arcseconds. */
    internal fun imageScale(pitch: Double): Double =
        arcsecondsPerMicrometerPerMillimeter * pitch / focalLengthMillimeters

    companion object {
        // MARK: Ranges and defaults

        /** The supported focal lengths, in millimetres. */
        val focalLengthRange: ClosedFloatingPointRange<Double> = 4.0..2000.0

        /** The supported apertures, as f-numbers. */
        val apertureRange: ClosedFloatingPointRange<Double> = 0.95..32.0

        /** The focal length used in place of one that is not a number, in millimetres. */
        const val defaultFocalLengthMillimeters = 24.0

        /** The aperture used in place of one that is not a number. */
        const val defaultAperture = 2.8

        /**
         * Arcseconds subtended by a 1 µm pixel behind a 1 mm focal length: the arcseconds in a radian
         * divided by the 1000 micrometres in a millimetre.
         */
        private val arcsecondsPerMicrometerPerMillimeter = 180.0 * 3600.0 / PI / 1000.0

        /**
         * Creates a setup, limiting every value to its supported range (Swift `LensSetup(sensor:…)`).
         *
         * The focal length is limited to [focalLengthRange] and the aperture to [apertureRange]; a
         * value that is not finite becomes [defaultFocalLengthMillimeters] or [defaultAperture]. A
         * pixel pitch that is not finite or lies outside [SensorFormat.pixelPitchRange] is more likely a
         * unit mistake than a near miss, so it is dropped and treated as unknown. Use
         * [validated] to reject invalid input instead of correcting it.
         *
         * @param sensor The camera's sensor.
         * @param focalLengthMillimeters The lens focal length.
         * @param aperture The f-number.
         * @param pixelPitchMicrometers The pixel pitch, or null if unknown.
         */
        operator fun invoke(
            sensor: SensorFormat,
            focalLengthMillimeters: Double,
            aperture: Double,
            pixelPitchMicrometers: Double? = null,
        ): LensSetup = LensSetup(
            sensor,
            RangeLimiter.limit(focalLengthMillimeters, focalLengthRange, defaultFocalLengthMillimeters),
            RangeLimiter.limit(aperture, apertureRange, defaultAperture),
            pixelPitchMicrometers?.takeIf { SensorFormat.pixelPitchRange.contains(it) },
        )

        /**
         * Creates a setup only if every value is finite and within its supported range; otherwise
         * returns null. Use this for user input that should be rejected rather than corrected.
         */
        fun validated(
            sensor: SensorFormat,
            focalLengthMillimeters: Double,
            aperture: Double,
            pixelPitchMicrometers: Double? = null,
        ): LensSetup? {
            // `ClosedFloatingPointRange.contains` is false for NaN and infinities, so these checks also reject them.
            if (!focalLengthRange.contains(focalLengthMillimeters) ||
                !apertureRange.contains(aperture) ||
                (pixelPitchMicrometers != null && !SensorFormat.pixelPitchRange.contains(pixelPitchMicrometers))
            ) {
                return null
            }
            return invoke(sensor, focalLengthMillimeters, aperture, pixelPitchMicrometers)
        }
    }
}

/**
 * Serializes a [LensSetup] as a JSON object with the keys `sensor`, `focalLengthMillimeters`,
 * `aperture` and, when the pixel pitch is known, `pixelPitchMicrometers`. Decoding reads the
 * object itself, so unknown keys are ignored (like Swift's `JSONDecoder`); a missing key, a value of
 * the wrong type or a value outside the supported ranges throws a [SerializationException].
 * Stored data is rejected rather than corrected, so corruption is not hidden.
 */
internal object LensSetupSerializer : KSerializer<LensSetup> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("net.kuecker.lodestar.kit.phototools.LensSetup") {
            element("sensor", SensorFormatSerializer.descriptor)
            element<Double>("focalLengthMillimeters")
            element<Double>("aperture")
            element<Double>("pixelPitchMicrometers", isOptional = true)
        }

    override fun serialize(encoder: Encoder, value: LensSetup) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, SensorFormatSerializer, value.sensor)
            encodeDoubleElement(descriptor, 1, value.focalLengthMillimeters)
            encodeDoubleElement(descriptor, 2, value.aperture)
            // An unknown pixel pitch is left out rather than written as null.
            value.pixelPitchMicrometers?.let { encodeDoubleElement(descriptor, 3, it) }
        }
    }

    override fun deserialize(decoder: Decoder): LensSetup {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("A lens setup can only be decoded from JSON.")
        return decode(jsonDecoder.decodeJsonElement())
    }

    private fun decode(element: JsonElement): LensSetup {
        val fields = element as? JsonObject
            ?: throw SerializationException("Expected a JSON object for a lens setup.")
        val sensor = SensorFormatSerializer.decode(
            fields["sensor"] ?: throw SerializationException("Missing value for key 'sensor'."),
        )
        val focalLength = fields.requireDouble("focalLengthMillimeters")
        val aperture = fields.requireDouble("aperture")
        val pitch = fields.optionalDouble("pixelPitchMicrometers")
        return LensSetup.validated(sensor, focalLength, aperture, pitch)
            ?: throw SerializationException("The lens setup has a value outside the supported ranges.")
    }
}
