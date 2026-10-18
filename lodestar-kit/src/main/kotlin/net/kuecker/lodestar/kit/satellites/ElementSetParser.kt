package net.kuecker.lodestar.kit.satellites

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor
import kotlin.math.pow

/**
 * Reads orbital element sets from the two formats CelesTrak publishes: GP data in OMM JSON (preferred; it has no
 * limit on the catalog number) and classic two-line element sets (TLE).
 *
 * Every field is validated (see [OrbitalElements]). A single set is parsed with [parseTwoLine] and throws an
 * [ElementSetError]; the batch functions skip bad records and report them in [ElementSetBatch.skipped].
 *
 * Cost: about 10 microseconds per record; a 10 000-record Starlink download (5 MB of JSON) parses in well under a
 * second. The functions are pure and thread safe.
 */
object ElementSetParser {
    /** The largest input accepted, in characters. */
    const val maximumInputCharacters: Int = 64_000_000

    /** The most records a batch parse looks at; the rest of the input is ignored. */
    const val maximumRecordCount: Int = 200_000

    /** The most skipped records a batch lists in [ElementSetBatch.skipped] (the total is in `skippedCount`). */
    const val maximumReportedSkips: Int = 50

    private const val lineLength: Int = 69

    // MARK: Two-line sets

    /**
     * Parses one two-line element set.
     *
     * Each line must have 69 characters (trailing white space is ignored), the right line number, a correct
     * modulo-10 checksum, and well-formed fields. The catalog number may be a five-digit number or an Alpha-5
     * number (a letter and four digits, 100 000 ... 339 999). Blank optional fields (designator, element set
     * number, revolution number, ephemeris type) are accepted.
     *
     * @param line1 The first line.
     * @param line2 The second line.
     * @param name The object name from a preceding name line, if any ("0 " of the 3LE format is removed).
     * @throws ElementSetError describing the first problem found.
     */
    fun parseTwoLine(line1: String, line2: String, name: String? = null): OrbitalElements {
        val first = trimTrailing(line1)
        val second = trimTrailing(line2)
        if (first.length != lineLength) throw ElementSetError.InvalidLineLength(1, first.length)
        if (second.length != lineLength) throw ElementSetError.InvalidLineLength(2, second.length)
        if (first[0] != '1' || first[1] != ' ') throw ElementSetError.UnexpectedLineNumber(1)
        if (second[0] != '2' || second[1] != ' ') throw ElementSetError.UnexpectedLineNumber(2)
        checkChecksum(first, 1)
        checkChecksum(second, 2)
        checkSeparators(first, firstLineSeparators)
        checkSeparators(second, secondLineSeparators)

        val catalogNumber = catalogNumber(first.substring(2, 7))
        val secondCatalogNumber = catalogNumber(second.substring(2, 7))
        if (catalogNumber != secondCatalogNumber) {
            throw ElementSetError.CatalogNumberMismatch(catalogNumber, secondCatalogNumber)
        }

        val classification = if (first[7] == ' ') 'U' else first[7]
        val objectId = designator(first.substring(9, 17))

        val yearText = first.substring(18, 20)
        if (!isDigits(yearText)) throw ElementSetError.InvalidField("EPOCH", first.substring(18, 32))
        val shortYear = yearText.toInt()
        val year = if (shortYear < 57) shortYear + 2000 else shortYear + 1900
        val dayText = first.substring(20, 32)
        val days = decimal(dayText, "EPOCH")
        val daysInYear = if (year % 4 == 0) 366.0 else 365.0
        if (!(days >= 1.0 && days < daysInYear + 1.0)) throw ElementSetError.ValueOutOfRange("EPOCH", days)
        val calendar = ElementCalendar.monthDayTime(year, days)

        val meanMotionDot = decimal(first.substring(33, 43), "MEAN_MOTION_DOT")
        val meanMotionDdot = impliedExponent(first.substring(44, 52), "MEAN_MOTION_DDOT")
        val bstar = impliedExponent(first.substring(53, 61), "BSTAR")
        val ephemerisType = if (first[62] == ' ') 0 else digit(first[62], "EPHEMERIS_TYPE")
        val elementSetNumber = integer(first.substring(64, 68), "ELEMENT_SET_NO")

        val inclination = decimal(second.substring(8, 16), "INCLINATION")
        val node = decimal(second.substring(17, 25), "RA_OF_ASC_NODE")
        val eccentricityText = second.substring(26, 33)
        if (!isDigits(eccentricityText)) throw ElementSetError.InvalidField("ECCENTRICITY", eccentricityText)
        val eccentricity = eccentricityText.toInt().toDouble() / 1.0e7
        val argument = decimal(second.substring(34, 42), "ARG_OF_PERICENTER")
        val anomaly = decimal(second.substring(43, 51), "MEAN_ANOMALY")
        val meanMotion = decimal(second.substring(52, 63), "MEAN_MOTION")
        val revolutions = integer(second.substring(63, 68), "REV_AT_EPOCH")

        return OrbitalElements.make(
            name = name?.let { cleanName(it) },
            objectId = objectId,
            catalogNumber = catalogNumber,
            classification = classification,
            year = year,
            month = calendar.month,
            day = calendar.day,
            hour = calendar.hour,
            minute = calendar.minute,
            second = calendar.second,
            meanMotion = meanMotion,
            eccentricity = eccentricity,
            inclination = inclination,
            rightAscensionOfAscendingNode = node,
            argumentOfPericenter = argument,
            meanAnomaly = anomaly,
            ephemerisType = ephemerisType,
            elementSetNumber = elementSetNumber,
            revolutionNumberAtEpoch = revolutions,
            bstar = bstar,
            meanMotionDot = meanMotionDot,
            meanMotionDdot = meanMotionDdot,
        )
    }

    /**
     * Parses a text with many two-line sets: the 2LE format (pairs of lines) or the 3LE format (a name line
     * before each pair). Blank lines are ignored; a record that fails validation is skipped and reported.
     *
     * @throws ElementSetError.InputTooLarge if the text is longer than [maximumInputCharacters].
     */
    fun parseTwoLineText(text: String): ElementSetBatch {
        if (text.length > maximumInputCharacters) throw ElementSetError.InputTooLarge(maximumInputCharacters)
        val lines = splitLines(text)
        val elements = ArrayList<OrbitalElements>()
        val skipped = ArrayList<SkippedElementSet>()
        var skippedCount = 0
        var recordIndex = 0
        var pendingName: String? = null
        var index = 0
        while (index < lines.size && recordIndex < maximumRecordCount) {
            val line = lines[index]
            if (line.isEmpty()) {
                pendingName = null
                index += 1
                continue
            }
            val isFirstLine = line.length >= 2 && line[0] == '1' && line[1] == ' '
            val nextIsSecondLine = index + 1 < lines.size && lines[index + 1].length >= 2 &&
                lines[index + 1][0] == '2' && lines[index + 1][1] == ' '
            if (isFirstLine && nextIsSecondLine) {
                try {
                    elements.add(parseTwoLine(line, lines[index + 1], pendingName))
                } catch (error: ElementSetError) {
                    skippedCount += 1
                    if (skipped.size < maximumReportedSkips) {
                        skipped.add(SkippedElementSet(recordIndex, pendingName?.let { cleanName(it) }, error))
                    }
                }
                recordIndex += 1
                pendingName = null
                index += 2
            } else if ((isFirstLine && line.length >= 60) || (line.length >= 60 && line[0] == '2' && line[1] == ' ')) {
                // A line 1 without its line 2, or a line 2 without its line 1.
                skippedCount += 1
                if (skipped.size < maximumReportedSkips) {
                    skipped.add(
                        SkippedElementSet(
                            recordIndex,
                            pendingName?.let { cleanName(it) },
                            ElementSetError.UnexpectedLineNumber(if (isFirstLine) 2 else 1),
                        ),
                    )
                }
                recordIndex += 1
                pendingName = null
                index += 1
            } else {
                pendingName = line
                index += 1
            }
        }
        return ElementSetBatch(elements, skipped, skippedCount)
    }

    /** The modulo-10 checksum of the first 68 characters of a TLE line: digits count, `-` counts 1, rest 0. */
    fun checksum(line: String): Int {
        var sum = 0
        var index = 0
        val end = minOf(line.length, lineLength - 1)
        while (index < end) {
            val character = line[index]
            if (character in '0'..'9') {
                sum += character - '0'
            } else if (character == '-') {
                sum += 1
            }
            index += 1
        }
        return sum % 10
    }

    // MARK: OMM JSON

    /**
     * Parses CelesTrak GP data in OMM JSON: an array of objects (`FORMAT=json`) or a single object. Numeric
     * values may be JSON numbers or numeric strings (Space-Track writes strings). A record that is not an
     * object, lacks a required field or fails validation is skipped and reported.
     *
     * Required members: `EPOCH`, `MEAN_MOTION`, `ECCENTRICITY`, `INCLINATION`, `RA_OF_ASC_NODE`,
     * `ARG_OF_PERICENTER`, `MEAN_ANOMALY`, `NORAD_CAT_ID`, `BSTAR`. Optional: `OBJECT_NAME`, `OBJECT_ID`,
     * `CLASSIFICATION_TYPE` (default `U`), `EPHEMERIS_TYPE`, `ELEMENT_SET_NO`, `REV_AT_EPOCH`, `MEAN_MOTION_DOT`,
     * `MEAN_MOTION_DDOT` (default 0). Other members are ignored. `EPOCH` is an ISO 8601 UTC time such as
     * `2026-10-18T03:28:15.076416` (a trailing `Z` is accepted).
     *
     * @throws ElementSetError.InputTooLarge if the text is longer than [maximumInputCharacters].
     * @throws ElementSetError.MalformedJson if the text is not JSON or the top level is neither array nor object.
     */
    fun parseJson(json: String): ElementSetBatch {
        if (json.length > maximumInputCharacters) throw ElementSetError.InputTooLarge(maximumInputCharacters)
        val root: JsonElement = try {
            Json.parseToJsonElement(json)
        } catch (error: IllegalArgumentException) {
            throw ElementSetError.MalformedJson(error.message?.take(120) ?: "syntax error")
        }
        val elements = ArrayList<OrbitalElements>()
        val skipped = ArrayList<SkippedElementSet>()
        var skippedCount = 0
        val records: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> listOf(root)
            else -> throw ElementSetError.MalformedJson("the top level is neither an array nor an object")
        }
        var recordIndex = 0
        for (record in records) {
            if (recordIndex >= maximumRecordCount) break
            var name: String? = null
            try {
                if (record !is JsonObject) throw ElementSetError.InvalidRecord("the element is not an object")
                name = (record["OBJECT_NAME"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                elements.add(ommRecord(record))
            } catch (error: ElementSetError) {
                skippedCount += 1
                if (skipped.size < maximumReportedSkips) {
                    skipped.add(SkippedElementSet(recordIndex, name?.let { cleanName(it) }, error))
                }
            }
            recordIndex += 1
        }
        return ElementSetBatch(elements, skipped, skippedCount)
    }

    /** [parseJson] for a download that has not been decoded to text yet (UTF-8 bytes). */
    fun parseJson(bytes: ByteArray): ElementSetBatch {
        if (bytes.size > maximumInputCharacters) throw ElementSetError.InputTooLarge(maximumInputCharacters)
        return parseJson(String(bytes, Charsets.UTF_8))
    }

    private fun ommRecord(record: JsonObject): OrbitalElements {
        val epoch = isoEpoch(requiredText(record, "EPOCH"))
        val classificationText = optionalText(record, "CLASSIFICATION_TYPE")
        val classification = if (classificationText == null) {
            'U'
        } else if (classificationText.length == 1) {
            classificationText[0]
        } else {
            throw ElementSetError.InvalidField("CLASSIFICATION_TYPE", classificationText.take(40))
        }
        return OrbitalElements.make(
            name = optionalText(record, "OBJECT_NAME"),
            objectId = optionalText(record, "OBJECT_ID"),
            catalogNumber = integerValue(record, "NORAD_CAT_ID", required = true, defaultValue = 0),
            classification = classification,
            year = epoch.year,
            month = epoch.month,
            day = epoch.day,
            hour = epoch.hour,
            minute = epoch.minute,
            second = epoch.second,
            meanMotion = numberValue(record, "MEAN_MOTION", required = true),
            eccentricity = numberValue(record, "ECCENTRICITY", required = true),
            inclination = numberValue(record, "INCLINATION", required = true),
            rightAscensionOfAscendingNode = numberValue(record, "RA_OF_ASC_NODE", required = true),
            argumentOfPericenter = numberValue(record, "ARG_OF_PERICENTER", required = true),
            meanAnomaly = numberValue(record, "MEAN_ANOMALY", required = true),
            ephemerisType = integerValue(record, "EPHEMERIS_TYPE", required = false, defaultValue = 0),
            elementSetNumber = integerValue(record, "ELEMENT_SET_NO", required = false, defaultValue = 0),
            revolutionNumberAtEpoch = integerValue(record, "REV_AT_EPOCH", required = false, defaultValue = 0),
            bstar = numberValue(record, "BSTAR", required = true),
            meanMotionDot = numberValue(record, "MEAN_MOTION_DOT", required = false),
            meanMotionDdot = numberValue(record, "MEAN_MOTION_DDOT", required = false),
        )
    }

    /** The member as a finite number: a JSON number or a numeric string; `null` when absent or `null`. */
    private fun numberOrNull(record: JsonObject, key: String): Double? {
        val member = record[key]
        if (member == null || member is JsonNull) return null
        if (member !is JsonPrimitive) throw ElementSetError.InvalidField(key, "<${if (member is JsonArray) "array" else "object"}>")
        val text = member.content
        if (!member.isString && (text == "true" || text == "false")) throw ElementSetError.InvalidField(key, text)
        val value = if (member.isString) strictNumber(text) else text.toDoubleOrNull()
        if (value == null || !value.isFinite()) throw ElementSetError.InvalidField(key, text.take(40))
        return value
    }

    private fun numberValue(record: JsonObject, key: String, required: Boolean): Double {
        val value = numberOrNull(record, key)
        if (value != null) return value
        if (required) throw ElementSetError.MissingField(key)
        return 0.0
    }

    private fun integerValue(record: JsonObject, key: String, required: Boolean, defaultValue: Int): Int {
        val value = numberOrNull(record, key)
        if (value == null) {
            if (required) throw ElementSetError.MissingField(key)
            return defaultValue
        }
        if (value != floor(value) || value < -2_147_483_648.0 || value > 2_147_483_647.0) {
            throw ElementSetError.ValueOutOfRange(key, value)
        }
        return value.toInt()
    }

    private fun requiredText(record: JsonObject, key: String): String =
        optionalText(record, key) ?: throw ElementSetError.MissingField(key)

    private fun optionalText(record: JsonObject, key: String): String? {
        val member = record[key]
        if (member == null || member is JsonNull) return null
        if (member !is JsonPrimitive) throw ElementSetError.InvalidField(key, "<${if (member is JsonArray) "array" else "object"}>")
        if (!member.isString) throw ElementSetError.InvalidField(key, member.content.take(40))
        return member.content
    }

    /** The components of an ISO 8601 UTC epoch. */
    private class EpochComponents(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Double,
    )

    /** Reads `YYYY-MM-DDTHH:MM:SS[.f...]` with an optional `Z` or `+00:00` suffix (a space may replace the `T`). */
    private fun isoEpoch(text: String): EpochComponents {
        val invalid = ElementSetError.InvalidField("EPOCH", text.take(40))
        var end = text.length
        if (end > 0 && text[end - 1] == 'Z') {
            end -= 1
        } else if (end >= 6 && (text.endsWith("+00:00") || text.endsWith("-00:00"))) {
            end -= 6
        }
        if (end < 19) throw invalid
        if (text[4] != '-' || text[7] != '-' || (text[10] != 'T' && text[10] != ' ') ||
            text[13] != ':' || text[16] != ':'
        ) {
            throw invalid
        }
        val year = fixedDigits(text, 0, 4) ?: throw invalid
        val month = fixedDigits(text, 5, 2) ?: throw invalid
        val day = fixedDigits(text, 8, 2) ?: throw invalid
        val hour = fixedDigits(text, 11, 2) ?: throw invalid
        val minute = fixedDigits(text, 14, 2) ?: throw invalid
        val wholeSecond = fixedDigits(text, 17, 2) ?: throw invalid
        var second = wholeSecond.toDouble()
        if (end > 19) {
            if (text[19] != '.' || end == 20 || end - 20 > 15) throw invalid
            val fractionText = text.substring(20, end)
            if (!isDigits(fractionText)) throw invalid
            second += ("0.$fractionText").toDouble()
        }
        return EpochComponents(year, month, day, hour, minute, second)
    }

    private fun fixedDigits(text: String, start: Int, count: Int): Int? {
        var value = 0
        for (offset in 0 until count) {
            val character = text[start + offset]
            if (character !in '0'..'9') return null
            value = value * 10 + (character - '0')
        }
        return value
    }

    /** A number in JSON grammar (`-12.5e3`), as a string member may hold it; `null` if the text is anything else. */
    private fun strictNumber(text: String): Double? {
        var index = 0
        val length = text.length
        if (index < length && text[index] == '-') index += 1
        val integerStart = index
        while (index < length && text[index] in '0'..'9') index += 1
        if (index == integerStart) return null
        if (index - integerStart > 1 && text[integerStart] == '0') return null
        if (index < length && text[index] == '.') {
            index += 1
            val fractionStart = index
            while (index < length && text[index] in '0'..'9') index += 1
            if (index == fractionStart) return null
        }
        if (index < length && (text[index] == 'e' || text[index] == 'E')) {
            index += 1
            if (index < length && (text[index] == '+' || text[index] == '-')) index += 1
            val exponentStart = index
            while (index < length && text[index] in '0'..'9') index += 1
            if (index == exponentStart) return null
        }
        if (index != length) return null
        return text.toDoubleOrNull()
    }

    // MARK: Two-line fields

    private val firstLineSeparators = intArrayOf(1, 8, 17, 32, 43, 52, 61, 63)
    private val secondLineSeparators = intArrayOf(1, 7, 16, 25, 33, 42, 51)

    private fun checkSeparators(line: String, columns: IntArray) {
        for (column in columns) {
            if (line[column] != ' ') {
                throw ElementSetError.InvalidField("separator", line.substring(maxOf(0, column - 3), column + 4))
            }
        }
    }

    private fun checkChecksum(line: String, number: Int) {
        val last = line[lineLength - 1]
        if (last !in '0'..'9') throw ElementSetError.InvalidField("checksum", last.toString())
        val expected = checksum(line)
        val found = last - '0'
        if (expected != found) throw ElementSetError.ChecksumMismatch(number, expected, found)
    }

    /** A decimal in TLE spelling: optional sign, digits with an optional point, e.g. `51.6328`, `-.00000084`. */
    private fun decimal(field: String, name: String): Double {
        val text = field.trim()
        var index = 0
        var negative = false
        if (index < text.length && (text[index] == '+' || text[index] == '-')) {
            negative = text[index] == '-'
            index += 1
        }
        val start = index
        var digits = 0
        var points = 0
        while (index < text.length) {
            val character = text[index]
            if (character in '0'..'9') {
                digits += 1
            } else if (character == '.') {
                points += 1
            } else {
                break
            }
            index += 1
        }
        if (index != text.length || digits == 0 || points > 1) throw ElementSetError.InvalidField(name, field)
        var body = text.substring(start)
        if (body[0] == '.') body = "0$body"
        if (body[body.length - 1] == '.') body += "0"
        val magnitude = body.toDouble()
        return if (negative) -magnitude else magnitude
    }

    /** `±ddddd±e`: five digits with an implied leading point and a power of ten, e.g. ` 14456-3`. Blank is 0. */
    private fun impliedExponent(field: String, name: String): Double {
        if (field.isBlank()) return 0.0
        val sign = field[0]
        if (sign != ' ' && sign != '+' && sign != '-') throw ElementSetError.InvalidField(name, field)
        val digits = field.substring(1, 6)
        if (!isDigits(digits)) throw ElementSetError.InvalidField(name, field)
        val exponentSign = field[6]
        if (exponentSign != '+' && exponentSign != '-' && exponentSign != ' ') throw ElementSetError.InvalidField(name, field)
        val exponentDigit = field[7]
        if (exponentDigit !in '0'..'9') throw ElementSetError.InvalidField(name, field)
        val exponent = (exponentDigit - '0') * (if (exponentSign == '-') -1 else 1)
        val mantissa = digits.toInt().toDouble() / 1.0e5
        val value = mantissa * 10.0.pow(exponent.toDouble())
        return if (sign == '-') -value else value
    }

    /** A non-negative integer field that may be blank (then 0). */
    private fun integer(field: String, name: String): Int {
        val text = field.trim()
        if (text.isEmpty()) return 0
        if (!isDigits(text)) throw ElementSetError.InvalidField(name, field)
        return text.toInt()
    }

    private fun digit(character: Char, name: String): Int {
        if (character !in '0'..'9') throw ElementSetError.InvalidField(name, character.toString())
        return character - '0'
    }

    /** The catalog number of five characters: digits, or Alpha-5 (a letter, without I and O, and four digits). */
    private fun catalogNumber(field: String): Int {
        val text = field.trimStart()
        if (text.isEmpty()) throw ElementSetError.InvalidField("NORAD_CAT_ID", field)
        val first = text[0]
        if (first in '0'..'9') {
            if (!isDigits(text)) throw ElementSetError.InvalidField("NORAD_CAT_ID", field)
            return text.toInt()
        }
        if (text.length == 5 && first in 'A'..'Z' && first != 'I' && first != 'O' && isDigits(text.substring(1))) {
            var value = first - 'A' + 10
            if (first > 'I') value -= 1
            if (first > 'O') value -= 1
            return value * 10_000 + text.substring(1).toInt()
        }
        throw ElementSetError.InvalidField("NORAD_CAT_ID", field)
    }

    /** `98067A  ` becomes `1998-067A`; a blank field is `null`; anything else is kept as written. */
    private fun designator(field: String): String? {
        val text = field.trim()
        if (text.isEmpty()) return null
        if (text.length >= 6 && text.length <= 8 && isDigits(text.substring(0, 5)) && text.substring(5).all { it in 'A'..'Z' }) {
            val shortYear = text.substring(0, 2).toInt()
            val year = if (shortYear < 57) shortYear + 2000 else shortYear + 1900
            return "$year-${text.substring(2, 5)}${text.substring(5)}"
        }
        return text
    }

    private fun isDigits(text: String): Boolean {
        if (text.isEmpty()) return false
        for (character in text) {
            if (character !in '0'..'9') return false
        }
        return true
    }

    private fun trimTrailing(line: String): String {
        var end = line.length
        while (end > 0 && (line[end - 1] == ' ' || line[end - 1] == '\r' || line[end - 1] == '\n' || line[end - 1] == '\t')) {
            end -= 1
        }
        return if (end == line.length) line else line.substring(0, end)
    }

    /** A name line of the 3LE format; the Space-Track "0 " prefix is removed. */
    private fun cleanName(line: String): String? {
        val trimmed = line.trim()
        val text = if (trimmed.startsWith("0 ")) trimmed.substring(2) else trimmed
        return OrbitalElements.cleanText(text, OrbitalElements.maximumNameLength)
    }

    private fun splitLines(text: String): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        for (position in 0..text.length) {
            if (position == text.length || text[position] == '\n') {
                lines.add(trimTrailing(text.substring(start, position)))
                start = position + 1
            }
        }
        return lines
    }
}
