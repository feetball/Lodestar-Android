package net.kuecker.lodestar.kit.catalog

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero

/**
 * The apparent size of an extended object on the sky.
 *
 * Sizes are the major and minor axes of the object's outline in arcminutes. Round objects (and
 * objects whose catalog entry lacks a minor axis) have no [minorArcminutes].
 *
 * Create one with [create]; values are always normalized (a size cannot be built out of range).
 * Serialized as a JSON object with the keys `majorArcminutes`, `minorArcminutes` and `positionAngle`
 * (the last two only when present); decoding applies the rules of [create] and fails when the major
 * axis is missing or not a finite, positive size. [toString] is the display text, [description].
 *
 * @property majorArcminutes Length of the major axis in arcminutes, greater than 0.
 * @property minorArcminutes Length of the minor axis in arcminutes, greater than 0 and at most [majorArcminutes].
 * @property positionAngle Position angle of the major axis in degrees, [0, 180): measured from north through east.
 */
@Serializable(with = AngularSizeSerializer::class)
@ConsistentCopyVisibility
data class AngularSize private constructor(
    val majorArcminutes: Double,
    val minorArcminutes: Double?,
    val positionAngle: Double?,
) {
    /**
     * The size for display, for example "178′ × 70′", "8.4′" or "36″".
     *
     * Sizes are rounded to whole arcminutes from 10′ up and to a tenth below that. Sizes below one
     * arcminute (that do not round up to 60″) are given in arcseconds. Both axes use the unit of the
     * major axis, and a minor axis that prints like the major one is left out.
     */
    val description: String
        get() {
            val inArcseconds = (majorArcminutes * 60).roundedHalfAwayFromZero() < 60
            val major = format(majorArcminutes, inArcseconds)
            val minorArcminutes = minorArcminutes ?: return major
            val minor = format(minorArcminutes, inArcseconds)
            return if (minor == major) major else "$major × $minor"
        }

    /** The display text, see [description] (Swift `CustomStringConvertible`). */
    override fun toString(): String = description

    companion object {
        /** Largest size an object can have: the whole sky, in arcminutes. */
        const val maximumArcminutes: Double = 360.0 * 60

        /**
         * Creates a size, or returns `null` when [majorArcminutes] is not a finite value in
         * (0, [maximumArcminutes]] (Swift's failable `init?(majorArcminutes:minorArcminutes:positionAngle:)`).
         *
         * A minor axis that is not finite, not positive or longer than the major axis is dropped. The
         * position angle is reduced modulo 180° (an axis has no direction), and dropped if not finite.
         */
        fun create(
            majorArcminutes: Double,
            minorArcminutes: Double? = null,
            positionAngle: Double? = null,
        ): AngularSize? {
            if (!(majorArcminutes.isFinite() && majorArcminutes > 0 && majorArcminutes <= maximumArcminutes)) {
                return null
            }
            val minor = if (minorArcminutes != null && minorArcminutes.isFinite() && minorArcminutes > 0 &&
                minorArcminutes <= majorArcminutes
            ) {
                minorArcminutes
            } else {
                null
            }
            val angle = if (positionAngle != null && positionAngle.isFinite()) {
                var reduced = positionAngle % 180.0
                if (reduced < 0) reduced += 180.0
                if (reduced >= 180) 0.0 else reduced
            } else {
                null
            }
            return AngularSize(majorArcminutes, minor, angle)
        }

        private fun format(arcminutes: Double, inArcseconds: Boolean): String {
            if (inArcseconds) {
                val seconds = arcminutes * 60
                val text = if (seconds < 10) {
                    CatalogFormat.trimmedOneDecimal(seconds)
                } else {
                    seconds.roundedHalfAwayFromZero().toLong().toString()
                }
                return text + "″"
            }
            val text = if (arcminutes < 10) {
                CatalogFormat.trimmedOneDecimal(arcminutes)
            } else {
                arcminutes.roundedHalfAwayFromZero().toLong().toString()
            }
            return text + "′"
        }
    }
}

/**
 * Serializes an [AngularSize] like Swift's `Codable`: the major axis, and the minor axis and position
 * angle when present. Decoding reads the object itself, so unknown keys are ignored; a missing or
 * mistyped member (a quoted number, `null` for the major axis) or an unusable major axis throws a
 * [SerializationException].
 */
internal object AngularSizeSerializer : KSerializer<AngularSize> {
    private const val MAJOR = "majorArcminutes"
    private const val MINOR = "minorArcminutes"
    private const val ANGLE = "positionAngle"

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("net.kuecker.lodestar.kit.catalog.AngularSize") {
            element<Double>(MAJOR)
            element<Double>(MINOR, isOptional = true)
            element<Double>(ANGLE, isOptional = true)
        }

    override fun serialize(encoder: Encoder, value: AngularSize) {
        encoder.encodeStructure(descriptor) {
            encodeDoubleElement(descriptor, 0, value.majorArcminutes)
            value.minorArcminutes?.let { encodeDoubleElement(descriptor, 1, it) }
            value.positionAngle?.let { encodeDoubleElement(descriptor, 2, it) }
        }
    }

    override fun deserialize(decoder: Decoder): AngularSize {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("An angular size can only be decoded from JSON.")
        val fields = jsonDecoder.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("Expected a JSON object for an angular size.")
        val major = fields[MAJOR]?.number(MAJOR) ?: throw SerializationException("The key \"$MAJOR\" is missing.")
        val minor = fields[MINOR]?.takeUnless { it is JsonNull }?.number(MINOR)
        val angle = fields[ANGLE]?.takeUnless { it is JsonNull }?.number(ANGLE)
        return AngularSize.create(major, minor, angle)
            ?: throw SerializationException("The major axis must be a finite, positive size.")
    }

    /** A JSON number as a `Double`; quoted numbers, `null`, `true`/`false`, `NaN` and the like are rejected. */
    private fun JsonElement.number(key: String): Double {
        val primitive = this as? JsonPrimitive
        if (primitive == null || primitive is JsonNull || primitive.isString) {
            throw SerializationException("Expected a number for the key \"$key\".")
        }
        val content = primitive.content
        if (!isJsonNumber(content)) throw SerializationException("Expected a number for the key \"$key\".")
        val value = content.toDouble()
        if (!value.isFinite()) throw SerializationException("The number <$content> does not fit in Double.")
        return value
    }

    /** Whether [text] follows the JSON grammar for numbers. */
    private fun isJsonNumber(text: String): Boolean {
        var index = 0
        val end = text.length
        if (index < end && text[index] == '-') index += 1
        if (index >= end) return false
        if (text[index] == '0') {
            index += 1
        } else if (text[index] in '1'..'9') {
            while (index < end && text[index] in '0'..'9') index += 1
        } else {
            return false
        }
        if (index < end && text[index] == '.') {
            index += 1
            val digitsStart = index
            while (index < end && text[index] in '0'..'9') index += 1
            if (index == digitsStart) return false
        }
        if (index < end && (text[index] == 'e' || text[index] == 'E')) {
            index += 1
            if (index < end && (text[index] == '+' || text[index] == '-')) index += 1
            val digitsStart = index
            while (index < end && text[index] in '0'..'9') index += 1
            if (index == digitsStart) return false
        }
        return index == end
    }
}
