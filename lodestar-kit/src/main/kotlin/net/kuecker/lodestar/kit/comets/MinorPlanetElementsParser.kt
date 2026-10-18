package net.kuecker.lodestar.kit.comets

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reader for the Minor Planet Center's one-line minor-planet orbit format, as used in `MPCORB.DAT` (about 320 MB, one
 * line per asteroid) and its extracts `NEA.txt` (8.7 MB), `PHA.txt` (0.5 MB), `Distant.txt` and `Unusual.txt`; the
 * format is described at https://www.minorplanetcenter.net/iau/info/MPOrbitFormat.html.
 *
 * Columns (1-based, inclusive): 1–7 packed number or provisional designation, 9–13 absolute magnitude H, 15–19 slope
 * parameter G, 21–25 packed epoch (TT), 27–35 mean anomaly, 38–46 argument of perihelion, 49–57 ascending node,
 * 60–68 inclination (J2000.0), 71–79 eccentricity, 81–91 mean daily motion (degrees), 93–103 semi-major axis (AU),
 * 167–194 readable designation (optional). H and G may be blank; the other elements are required and checked against
 * each other (mean motion and semi-major axis must agree to 1 %). Only elliptic orbits (`e < 1`) exist in this format.
 */
object MinorPlanetElementsParser {
    /** The shortest line that holds all elements (through the semi-major axis in column 103). */
    const val minimumLineLength: Int = 103

    /** The longest line accepted; real lines have about 200 characters. */
    const val maximumLineLength: Int = 400

    /** An absolute magnitude of this value or more (the MPC writes 99.99) means "unknown": the body has no photometry. */
    const val unknownMagnitude: Double = 99.0

    /**
     * Reads one orbit line.
     *
     * @throws SmallBodyParseError if the line is too short or too long, a field is malformed or out of range, the
     * mean motion and the semi-major axis disagree, or no designation can be made.
     */
    fun parseLine(line: String): SmallBody {
        val columns = FixedWidthLine(line)
        if (columns.length > maximumLineLength) throw SmallBodyParseError.LineTooLong(columns.length, maximumLineLength)
        if (columns.length < minimumLineLength) throw SmallBodyParseError.LineTooShort(columns.length, minimumLineLength)

        // Epoch and elements.
        val epochText = columns.text(21, 25)
        if (epochText.isEmpty()) throw SmallBodyParseError.MissingField(SmallBodyField.EPOCH)
        val epochDate = PackedDesignation.date(epochText)
            ?: throw SmallBodyParseError.MalformedField(SmallBodyField.EPOCH, RecordFields.excerpt(epochText))
        val epoch = RecordFields.julianDay(epochDate.year, epochDate.month, epochDate.day)
        val meanAnomaly = decimalField(columns, 27, 35, SmallBodyField.MEAN_ANOMALY, 0.0, 360.0)
        val argumentOfPerihelion = decimalField(columns, 38, 46, SmallBodyField.ARGUMENT_OF_PERIHELION, 0.0, 360.0)
        val ascendingNode = decimalField(columns, 49, 57, SmallBodyField.LONGITUDE_OF_ASCENDING_NODE, 0.0, 360.0)
        val inclination = decimalField(columns, 60, 68, SmallBodyField.INCLINATION, 0.0, 180.0)
        val eccentricity = decimalField(columns, 71, 79, SmallBodyField.ECCENTRICITY, 0.0, 1.0)
        if (eccentricity >= 1.0) throw SmallBodyParseError.FieldOutOfRange(SmallBodyField.ECCENTRICITY, eccentricity)
        val meanMotion = decimalField(columns, 81, 91, SmallBodyField.MEAN_MOTION, 0.0, 40.0)
        if (meanMotion <= 0.0) throw SmallBodyParseError.FieldOutOfRange(SmallBodyField.MEAN_MOTION, meanMotion)
        val semiMajorAxis = decimalField(columns, 93, 103, SmallBodyField.SEMI_MAJOR_AXIS, 0.0, 10_000.0)
        if (semiMajorAxis <= 0.0) throw SmallBodyParseError.FieldOutOfRange(SmallBodyField.SEMI_MAJOR_AXIS, semiMajorAxis)
        val expectedMotion = AstroMath.degrees(
            OrbitPropagator.gaussianGravitationalConstant / (semiMajorAxis * sqrt(semiMajorAxis)),
        )
        if (abs(expectedMotion - meanMotion) > 0.01 * meanMotion) {
            throw SmallBodyParseError.InconsistentElements("the mean motion $meanMotion and the semi-major axis $semiMajorAxis disagree")
        }
        val orbit = OrbitalElements.fromMeanAnomaly(
            semiMajorAxisAU = semiMajorAxis,
            eccentricity = eccentricity,
            inclination = inclination,
            argumentOfPerihelion = argumentOfPerihelion,
            longitudeOfAscendingNode = ascendingNode,
            meanAnomaly = meanAnomaly,
            epochJulianDayTT = epoch,
        ) ?: throw SmallBodyParseError.InconsistentElements("the elements do not describe an orbit")

        // Brightness.
        var photometry: SmallBodyPhotometry? = null
        val magnitudeText = columns.text(9, 13)
        val slopeText = columns.text(15, 19)
        var slope: Double? = null
        if (slopeText.isNotEmpty()) {
            slope = RecordFields.decimal(slopeText)
                ?: throw SmallBodyParseError.MalformedField(SmallBodyField.SLOPE_PARAMETER, RecordFields.excerpt(slopeText))
            if (slope < -0.25 || slope > 1.0) throw SmallBodyParseError.FieldOutOfRange(SmallBodyField.SLOPE_PARAMETER, slope)
        }
        if (magnitudeText.isNotEmpty()) {
            val magnitude = RecordFields.decimal(magnitudeText)
                ?: throw SmallBodyParseError.MalformedField(SmallBodyField.ABSOLUTE_MAGNITUDE, RecordFields.excerpt(magnitudeText))
            if (magnitude < unknownMagnitude && (magnitude < -5 || magnitude > 40)) {
                throw SmallBodyParseError.FieldOutOfRange(SmallBodyField.ABSOLUTE_MAGNITUDE, magnitude)
            }
            if (magnitude < unknownMagnitude) {
                val magnitudes = AsteroidMagnitudes.create(magnitude, slope ?: AsteroidMagnitudes.defaultSlopeParameter)
                    ?: throw SmallBodyParseError.FieldOutOfRange(SmallBodyField.ABSOLUTE_MAGNITUDE, magnitude)
                photometry = SmallBodyPhotometry.Asteroid(magnitudes)
            }
        }

        // Designation and name.
        val packed = columns.text(1, 7)
        if (packed.isEmpty()) throw SmallBodyParseError.MissingField(SmallBodyField.PACKED_DESIGNATION)
        val readableText = columns.text(167, 194)
        val readable = if (readableText.isEmpty()) null else SmallBodyNaming.asteroidReadable(readableText)
        val number: Int? = if (packed.length == 5) PackedDesignation.number(packed) else null
        val designation: String
        var name = ""
        if (number != null) {
            designation = number.toString()
            if (readable != null && readable.number == number) name = readable.name
        } else {
            val provisional = if (packed.length == 7) PackedDesignation.minorPlanet(packed) else null
            designation = provisional
                ?: (if (readable != null && readable.number == null) readable.designation else null)
                ?: throw SmallBodyParseError.InvalidDesignation(RecordFields.excerpt(packed))
        }
        return SmallBody.create(
            designation = designation,
            name = name,
            kind = SmallBodyKind.ASTEROID,
            number = number,
            orbit = orbit,
            photometry = photometry,
        ) ?: throw SmallBodyParseError.InvalidDesignation(RecordFields.excerpt(packed))
    }

    /**
     * Reads a whole file in this format, skipping lines that are not valid orbit records (and counting them in the
     * result). The header of `MPCORB.DAT` (everything up to the line of dashes) and blank lines are ignored without
     * being counted; the blank lines that separate the sections of `MPCORB.DAT` likewise. A second occurrence of an
     * object is skipped as a duplicate.
     *
     * @throws SmallBodyParseError.InputTooLarge if [text] has more than 64 Mi characters.
     */
    fun parse(text: String): SmallBodyParseResult = SmallBodyBatch.parse(text, skipHeader = true, parseLine = ::parseLine)

    private fun decimalField(
        columns: FixedWidthLine,
        first: Int,
        last: Int,
        field: SmallBodyField,
        minimum: Double,
        maximum: Double,
    ): Double {
        val text = columns.text(first, last)
        if (text.isEmpty()) throw SmallBodyParseError.MissingField(field)
        val value = RecordFields.decimal(text) ?: throw SmallBodyParseError.MalformedField(field, RecordFields.excerpt(text))
        if (value < minimum || value > maximum) throw SmallBodyParseError.FieldOutOfRange(field, value)
        return value
    }
}
