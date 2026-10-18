package net.kuecker.lodestar.kit.comets

/**
 * A field of an orbit record, named in [SmallBodyParseError]s.
 *
 * @property rawValue The Swift case name.
 */
enum class SmallBodyField(val rawValue: String) {
    /** The periodic-comet number (columns 1–4 of a comet line). */
    COMET_NUMBER("cometNumber"),

    /** The orbit type letter (`C`, `P`, `D`, `X`, `I` or `A`). */
    ORBIT_TYPE("orbitType"),

    /** The packed provisional designation. */
    PACKED_DESIGNATION("packedDesignation"),

    /** The year of perihelion passage. */
    PERIHELION_YEAR("perihelionYear"),

    /** The month of perihelion passage. */
    PERIHELION_MONTH("perihelionMonth"),

    /** The day (with fraction) of perihelion passage. */
    PERIHELION_DAY("perihelionDay"),

    /** The perihelion time (JPL Julian Day). */
    PERIHELION_TIME("perihelionTime"),

    /** The perihelion distance. */
    PERIHELION_DISTANCE("perihelionDistance"),

    /** The eccentricity. */
    ECCENTRICITY("eccentricity"),

    /** The argument of perihelion. */
    ARGUMENT_OF_PERIHELION("argumentOfPerihelion"),

    /** The longitude of the ascending node. */
    LONGITUDE_OF_ASCENDING_NODE("longitudeOfAscendingNode"),

    /** The inclination. */
    INCLINATION("inclination"),

    /** The epoch of the elements. */
    EPOCH("epoch"),

    /** The absolute magnitude (H, or M1 of a comet). */
    ABSOLUTE_MAGNITUDE("absoluteMagnitude"),

    /** The slope parameter (G of an asteroid, k or K1 of a comet). */
    SLOPE_PARAMETER("slopeParameter"),

    /** The nuclear magnitude M2 or its slope K2 or phase coefficient PC. */
    NUCLEAR_MAGNITUDE("nuclearMagnitude"),

    /** The mean anomaly at the epoch. */
    MEAN_ANOMALY("meanAnomaly"),

    /** The mean daily motion. */
    MEAN_MOTION("meanMotion"),

    /** The semi-major axis. */
    SEMI_MAJOR_AXIS("semiMajorAxis"),

    /** The designation or name. */
    DESIGNATION("designation"),
    ;

    /** The English name of the field. */
    val displayName: String
        get() = when (this) {
            COMET_NUMBER -> "comet number"
            ORBIT_TYPE -> "orbit type"
            PACKED_DESIGNATION -> "packed designation"
            PERIHELION_YEAR -> "year of perihelion"
            PERIHELION_MONTH -> "month of perihelion"
            PERIHELION_DAY -> "day of perihelion"
            PERIHELION_TIME -> "time of perihelion"
            PERIHELION_DISTANCE -> "perihelion distance"
            ECCENTRICITY -> "eccentricity"
            ARGUMENT_OF_PERIHELION -> "argument of perihelion"
            LONGITUDE_OF_ASCENDING_NODE -> "longitude of the ascending node"
            INCLINATION -> "inclination"
            EPOCH -> "epoch"
            ABSOLUTE_MAGNITUDE -> "absolute magnitude"
            SLOPE_PARAMETER -> "slope parameter"
            NUCLEAR_MAGNITUDE -> "nuclear magnitude"
            MEAN_ANOMALY -> "mean anomaly"
            MEAN_MOTION -> "mean motion"
            SEMI_MAJOR_AXIS -> "semi-major axis"
            DESIGNATION -> "designation"
        }

    companion object {
        /** The field whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): SmallBodyField? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Why an orbit record could not be read. Every case is a data class, so errors are equal when their payloads are;
 * [description] is a sentence for logs.
 *
 * @property description A sentence that says what is wrong.
 */
sealed class SmallBodyParseError(val description: String) : Exception(description) {
    /** The line ends before the last required column. */
    data class LineTooShort(val length: Int, val minimum: Int) :
        SmallBodyParseError("The line has $length characters; at least $minimum are required.")

    /** The line is longer than any valid record. */
    data class LineTooLong(val length: Int, val maximum: Int) :
        SmallBodyParseError("The line has $length characters; at most $maximum are allowed.")

    /** A required field is blank or absent. */
    data class MissingField(val field: SmallBodyField) :
        SmallBodyParseError("The ${field.displayName} is missing.")

    /** A field is not a number (or not a date or letter) of the expected form. [text] is the field, cut to 40 characters. */
    data class MalformedField(val field: SmallBodyField, val text: String) :
        SmallBodyParseError("The ${field.displayName} \"$text\" is not valid.")

    /** A field is a number outside its permitted range. */
    data class FieldOutOfRange(val field: SmallBodyField, val value: Double) :
        SmallBodyParseError("The ${field.displayName} $value is out of range.")

    /** No usable designation could be read or unpacked. */
    data class InvalidDesignation(val text: String) :
        SmallBodyParseError("The designation \"$text\" is not valid.")

    /** The fields of the record contradict each other. */
    data class InconsistentElements(val reason: String) :
        SmallBodyParseError("The orbital elements are inconsistent: $reason.")

    /** The same object occurs twice in a file; the first occurrence is kept. */
    data class DuplicateObject(val id: String) :
        SmallBodyParseError("The object $id occurs more than once.")

    /** The text is not usable JSON of the expected shape. */
    data class InvalidJSON(val reason: String) :
        SmallBodyParseError("The JSON is not valid: $reason.")

    /** The service answered that it has no (unique) such object, or the record is of an unsupported kind. */
    data class UnsupportedObject(val reason: String) :
        SmallBodyParseError("The object cannot be used: $reason.")

    /** The text is longer than the parser accepts. */
    data class InputTooLarge(val length: Int, val maximum: Int) :
        SmallBodyParseError("The input has $length characters; at most $maximum are accepted.")
}

/**
 * One line a batch parser skipped.
 *
 * @property lineNumber The 1-based line number in the input.
 * @property error Why the line was skipped.
 */
data class SmallBodyLineRejection(val lineNumber: Int, val error: SmallBodyParseError)

/**
 * The outcome of parsing a whole file leniently: every valid record, and a count of the lines skipped. Blank lines
 * and the header of an MPCORB file are not counted as rejected.
 *
 * @property bodies The bodies in file order, without duplicates.
 * @property rejectedLineCount How many non-blank lines were skipped.
 * @property rejections The first [maximumReportedRejections] skipped lines with their reasons.
 */
data class SmallBodyParseResult(
    val bodies: List<SmallBody>,
    val rejectedLineCount: Int,
    val rejections: List<SmallBodyLineRejection>,
) {
    companion object {
        /** The number of rejections that are kept in [rejections], 50. */
        const val maximumReportedRejections: Int = 50
    }
}
