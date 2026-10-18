package net.kuecker.lodestar.kit.pointing

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kuecker.lodestar.kit.foundation.*
import java.text.BreakIterator
import java.util.Locale

// Support code that has no Swift counterpart: Swift's `String.prefix(_:)` counts characters, and the
// `Codable` conformances of the pointing types validate while decoding, which here means reading the JSON
// tree with the same strictness as Swift's `JSONDecoder`.

/**
 * Swift's `String.prefix(maxLength)`: the first [maxLength] characters, where a character is an extended
 * grapheme cluster (an emoji with a skin tone or an accented letter written with a combining mark is one
 * character, however many UTF-16 units it takes).
 */
internal fun String.prefixCharacters(maxLength: Int): String {
    // Every character takes at least one UTF-16 unit, so a short string cannot have too many of them.
    if (length <= maxLength) return this
    val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT)
    boundaries.setText(this)
    var end = 0
    var count = 0
    while (count < maxLength) {
        val next = boundaries.next()
        if (next == BreakIterator.DONE) return this
        end = next
        count += 1
    }
    return substring(0, end)
}

// MARK: Strict JSON reading

/**
 * The JSON tree at the decoder's position. The pointing types are decoded from the tree, not field by
 * field, so that unknown keys are ignored (like Swift's `JSONDecoder`) whatever the `Json` configuration.
 */
internal fun Decoder.decodeJsonTree(what: String): JsonElement {
    val jsonDecoder = this as? JsonDecoder
        ?: throw SerializationException("A $what can only be decoded from JSON.")
    return jsonDecoder.decodeJsonElement()
}

/** [this] as a JSON object; anything else throws. */
internal fun JsonElement.toJsonObject(what: String): JsonObject =
    this as? JsonObject ?: throw SerializationException("Expected a JSON object for $what.")

/** [this] as a JSON array; anything else throws. */
internal fun JsonElement.toJsonArray(what: String): JsonArray =
    this as? JsonArray ?: throw SerializationException("Expected a JSON array for $what.")

/** The value stored under [key]; a missing key throws (a JSON `null` is returned as such). */
internal fun JsonObject.requiredField(key: String): JsonElement =
    this[key] ?: throw SerializationException("Missing value for key '$key'.")

/** The value stored under [key], or null when the key is absent or holds a JSON `null` (`decodeIfPresent`). */
internal fun JsonObject.optionalField(key: String): JsonElement? {
    val element = this[key] ?: return null
    return if (element is JsonNull) null else element
}

/** [this] as a string; a number, `true`, `false` or `null` throws. */
internal fun JsonElement.toStrictString(what: String): String {
    val primitive = this as? JsonPrimitive
    if (primitive == null || primitive is JsonNull || !primitive.isString) {
        throw SerializationException("Expected a string for $what.")
    }
    return primitive.content
}

/**
 * [this] as a finite `Double`. A quoted number, `true`/`false`, `null`, `NaN`, hexadecimal text and a
 * number too large for a `Double` are rejected, as Swift's `JSONDecoder` rejects them.
 */
internal fun JsonElement.toStrictDouble(what: String): Double {
    val primitive = this as? JsonPrimitive
    if (primitive == null || primitive is JsonNull || primitive.isString) {
        throw SerializationException("Expected a number for $what.")
    }
    val content = primitive.content
    val looksNumeric = content.isNotEmpty() &&
        content.all { it in '0'..'9' || it == '-' || it == '+' || it == '.' || it == 'e' || it == 'E' }
    val value = (if (looksNumeric) content.toDoubleOrNull() else null)
        ?: throw SerializationException("Expected a number for $what.")
    if (!value.isFinite()) throw SerializationException("The number for $what does not fit in a Double.")
    return value
}

/** [this] as a `Vector3` (`{"x":…,"y":…,"z":…}`); other keys are ignored. */
internal fun JsonElement.toVector3(what: String): Vector3 {
    val fields = toJsonObject(what)
    return Vector3(
        fields.requiredField("x").toStrictDouble("$what.x"),
        fields.requiredField("y").toStrictDouble("$what.y"),
        fields.requiredField("z").toStrictDouble("$what.z"),
    )
}

/** [this] as a `Matrix3` (`{"r0":{…},"r1":{…},"r2":{…}}`); other keys are ignored. */
internal fun JsonElement.toMatrix3(what: String): Matrix3 {
    val fields = toJsonObject(what)
    return Matrix3(
        fields.requiredField("r0").toVector3("$what.r0"),
        fields.requiredField("r1").toVector3("$what.r1"),
        fields.requiredField("r2").toVector3("$what.r2"),
    )
}
