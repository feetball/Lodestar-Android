package net.kuecker.lodestar.kit.comets

/**
 * Reader for the Minor Planet Center's comet orbit file
 * (https://www.minorplanetcenter.net/iau/MPCORB/CometEls.txt, about 164 kB and 1000 lines, updated daily; the fixed-width
 * format is described at https://www.minorplanetcenter.net/iau/info/CometOrbitFormat.html, "Ephemerides and Orbital
 * Elements Format").
 *
 * Columns (1-based, inclusive): 1–4 periodic-comet number, 5 orbit type, 6–12 packed provisional designation,
 * 15–18 / 20–21 / 23–29 year, month and day (TT) of perihelion, 31–39 perihelion distance, 42–49 eccentricity,
 * 52–59 argument of perihelion, 62–69 ascending node, 72–79 inclination (all J2000.0), 82–89 epoch `YYYYMMDD`
 * (optional), 92–95 absolute magnitude, 97–100 slope parameter `k` (total magnitude `H + 5 log Δ + 2.5 k log r`),
 * 103–158 designation and name. Every field is validated: numbers must be plain decimals within their ranges, the
 * date must exist, and the orbit type must be one of `C P D X I A`. Orbit type `A` (an asteroid on a cometary
 * orbit) gives an [SmallBodyKind.ASTEROID], every other type a [SmallBodyKind.COMET].
 */
object CometElementsParser {
    /** The shortest line that holds all orbital elements (through the inclination in column 79). */
    const val minimumLineLength: Int = 79

    /** The longest line accepted; real lines have about 170 characters. */
    const val maximumLineLength: Int = 400

    /** The slope parameter assumed when a line gives an absolute magnitude but no slope, 4.0 (K1 = 10). */
    const val defaultSlopeParameter: Double = 4.0

    /**
     * Reads one line of the file.
     *
     * @throws SmallBodyParseError if the line is too short or too long, a field is malformed or out of range, or no
     * designation can be made.
     */
    fun parseLine(line: String): SmallBody {
        val columns = FixedWidthLine(line)
        if (columns.length > maximumLineLength) throw SmallBodyParseError.LineTooLong(columns.length, maximumLineLength)
        if (columns.length < minimumLineLength) throw SmallBodyParseError.LineTooShort(columns.length, minimumLineLength)

        // Identity.
        val numberText = columns.text(1, 4)
        var number: Int? = null
        if (numberText.isNotEmpty()) {
            val value = RecordFields.integer(numberText)
            if (value == null || value < 1) {
                throw SmallBodyParseError.MalformedField(SmallBodyField.COMET_NUMBER, RecordFields.excerpt(numberText))
            }
            number = value
        }
        val typeText = columns.text(5, 5)
        if (typeText.isEmpty()) throw SmallBodyParseError.MissingField(SmallBodyField.ORBIT_TYPE)
        if (typeText.length != 1 || typeText[0] !in "CPDXIA") {
            throw SmallBodyParseError.MalformedField(SmallBodyField.ORBIT_TYPE, RecordFields.excerpt(typeText))
        }

        // Time of perihelion.
        val year = integerField(columns, 15, 18, SmallBodyField.PERIHELION_YEAR)
        if (year < RecordFields.minimumYear || year > RecordFields.maximumYear) {
            throw SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_YEAR, year.toDouble())
        }
        val month = integerField(columns, 20, 21, SmallBodyField.PERIHELION_MONTH)
        if (month < 1 || month > 12) {
            throw SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_MONTH, month.toDouble())
        }
        // The day starts at 1.0 and stays below the first of the next month, so an impossible date cannot roll over.
        val day = decimalField(columns, 23, 29, SmallBodyField.PERIHELION_DAY, 1.0, 32.0)
        if (day >= (RecordFields.daysInMonth(year, month) + 1).toDouble()) {
            throw SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_DAY, day)
        }
        val perihelionTime = RecordFields.julianDay(year, month, 1) + (day - 1.0)

        // Orbit.
        val perihelionDistance = decimalField(columns, 31, 39, SmallBodyField.PERIHELION_DISTANCE, 0.0, SmallBodyElements.maximumPerihelionDistanceAU)
        val eccentricity = decimalField(columns, 42, 49, SmallBodyField.ECCENTRICITY, 0.0, SmallBodyElements.maximumEccentricity)
        val argumentOfPerihelion = decimalField(columns, 52, 59, SmallBodyField.ARGUMENT_OF_PERIHELION, 0.0, 360.0)
        val ascendingNode = decimalField(columns, 62, 69, SmallBodyField.LONGITUDE_OF_ASCENDING_NODE, 0.0, 360.0)
        val inclination = decimalField(columns, 72, 79, SmallBodyField.INCLINATION, 0.0, 180.0)

        // Epoch, if given.
        var epoch: Double? = null
        val epochText = columns.text(82, 89)
        if (epochText.isNotEmpty()) {
            val packed = RecordFields.integer(epochText)
            if (epochText.length != 8 || packed == null) {
                throw SmallBodyParseError.MalformedField(SmallBodyField.EPOCH, RecordFields.excerpt(epochText))
            }
            val epochYear = packed / 10_000
            val epochMonth = (packed / 100) % 100
            val epochDay = packed % 100
            if (epochYear < RecordFields.minimumYear || epochYear > RecordFields.maximumYear || epochMonth < 1 ||
                epochMonth > 12 || epochDay < 1 || epochDay > RecordFields.daysInMonth(epochYear, epochMonth)
            ) {
                throw SmallBodyParseError.MalformedField(SmallBodyField.EPOCH, epochText)
            }
            epoch = RecordFields.julianDay(epochYear, epochMonth, epochDay)
        }

        val orbit = SmallBodyElements.create(
            perihelionDistanceAU = perihelionDistance,
            eccentricity = eccentricity,
            inclination = inclination,
            argumentOfPerihelion = argumentOfPerihelion,
            longitudeOfAscendingNode = ascendingNode,
            perihelionJulianDayTT = perihelionTime,
            epochJulianDayTT = epoch,
        ) ?: throw SmallBodyParseError.InconsistentElements("the elements do not describe an orbit")

        // Brightness.
        var photometry: SmallBodyPhotometry? = null
        val magnitudeText = columns.text(92, 95)
        val slopeText = columns.text(97, 100)
        var slope: Double? = null
        if (slopeText.isNotEmpty()) {
            slope = RecordFields.decimal(slopeText)
                ?: throw SmallBodyParseError.MalformedField(SmallBodyField.SLOPE_PARAMETER, RecordFields.excerpt(slopeText))
            if (slope < 0 || slope > 24) throw SmallBodyParseError.FieldOutOfRange(SmallBodyField.SLOPE_PARAMETER, slope)
        }
        if (magnitudeText.isNotEmpty()) {
            val magnitude = RecordFields.decimal(magnitudeText)
                ?: throw SmallBodyParseError.MalformedField(SmallBodyField.ABSOLUTE_MAGNITUDE, RecordFields.excerpt(magnitudeText))
            if (magnitude < -20 || magnitude > 40) {
                throw SmallBodyParseError.FieldOutOfRange(SmallBodyField.ABSOLUTE_MAGNITUDE, magnitude)
            }
            val magnitudes = CometMagnitudes.fromMPC(magnitude, slope ?: defaultSlopeParameter)
                ?: throw SmallBodyParseError.FieldOutOfRange(SmallBodyField.ABSOLUTE_MAGNITUDE, magnitude)
            photometry = SmallBodyPhotometry.Comet(magnitudes)
        }

        // Designation and name.
        val orbitType = typeText[0]
        val title = columns.text(103, 158)
        val designation: String
        val name: String
        if (title.isNotEmpty()) {
            val parts = SmallBodyNaming.cometParts(title)
            designation = parts.first
            name = parts.second
            if (number == null) number = SmallBodyNaming.cometNumber(designation)
        } else {
            val packed = columns.text(6, 12)
            designation = fallbackDesignation(number, orbitType, packed)
            name = ""
        }
        val kind = if (orbitType == 'A') SmallBodyKind.ASTEROID else SmallBodyKind.COMET
        return SmallBody.create(
            designation = designation,
            name = name,
            kind = kind,
            number = number,
            orbit = orbit,
            photometry = photometry,
        ) ?: throw SmallBodyParseError.InvalidDesignation(RecordFields.excerpt(title.ifEmpty { designation }))
    }

    /**
     * Reads a whole `CometEls.txt`, skipping lines that are not valid orbit records (and counting them in the result).
     * Duplicated objects are skipped after their first occurrence.
     *
     * @throws SmallBodyParseError.InputTooLarge if [text] has more than 64 Mi characters.
     */
    fun parse(text: String): SmallBodyParseResult = SmallBodyBatch.parse(text, skipHeader = false, parseLine = ::parseLine)

    private fun integerField(columns: FixedWidthLine, first: Int, last: Int, field: SmallBodyField): Int {
        val text = columns.text(first, last)
        if (text.isEmpty()) throw SmallBodyParseError.MissingField(field)
        return RecordFields.integer(text) ?: throw SmallBodyParseError.MalformedField(field, RecordFields.excerpt(text))
    }

    /** A decimal field in `minimum...maximum`; the upper bound is inclusive unless the caller tests it itself. */
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

    /** `12P` (with a fragment letter `12P-B` when the packed field holds one) or `C/1995 O1`, from the packed columns. */
    private fun fallbackDesignation(number: Int?, orbitType: Char, packed: String): String {
        if (number != null) {
            val fragment = if (packed.length == 1 && packed[0] in 'a'..'z') "-${packed[0].uppercaseChar()}" else ""
            return "$number$orbitType$fragment"
        }
        val provisional = PackedDesignation.cometProvisional(packed)
            ?: throw SmallBodyParseError.InvalidDesignation(RecordFields.excerpt(packed))
        return "$orbitType/$provisional"
    }
}
