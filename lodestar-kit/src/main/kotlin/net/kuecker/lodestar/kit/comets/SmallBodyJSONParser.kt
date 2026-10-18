package net.kuecker.lodestar.kit.comets

/**
 * Reader for the answer of the JPL Small-Body Database API for one object
 * (https://ssd-api.jpl.nasa.gov/doc/sbdb.html), for example
 * `https://ssd-api.jpl.nasa.gov/sbdb.api?des=12P&phys-par=1&full-prec=1`.
 *
 * Request `phys-par=1` to get the magnitude parameters (`M1`, `K1`, `M2`, `K2`, `PC` for comets, `H`, `G` for asteroids).
 * The reader uses `object.fullname` and `object.kind`, `orbit.epoch`, `orbit.equinox` (must be `J2000` if present),
 * `orbit.elements` (`e`, `q`, `i`, `om`, `w`, `tp`, with `a`, `ma` as fallbacks) and `phys_par`. Values may be
 * strings or numbers (the service writes strings); unknown members are ignored. The time of perihelion `tp` is a Julian
 * Day in TDB, which is taken as TT (they differ by less than 2 ms).
 *
 * The answer is a small document (3–6 kB); texts above [maximumInputLength] characters or nested deeper than
 * [maximumNesting] are refused. The JSON grammar is strict (RFC 8259) and the same in Kotlin and Swift.
 */
object SmallBodyJSONParser {
    /** The longest accepted input, 1 Mi characters. */
    const val maximumInputLength: Int = 1_048_576

    /** The deepest accepted nesting of objects and arrays, 32. */
    const val maximumNesting: Int = JSONReader.maximumNesting

    /** The slope K1 assumed for a comet that has M1 but no K1, 10. */
    const val defaultTotalSlope: Double = CometMagnitudes.defaultTotalSlope

    /**
     * Reads the answer for one object.
     *
     * @throws SmallBodyParseError if the text is not JSON of the expected shape, the service reports that the object
     * was not found (or not unique), the object is neither comet nor asteroid, or a required element is missing,
     * malformed or out of range.
     */
    fun parse(json: String): SmallBody {
        if (json.length > maximumInputLength) throw SmallBodyParseError.InputTooLarge(json.length, maximumInputLength)
        val root = try {
            JSONReader(json).readDocument()
        } catch (error: JSONReaderException) {
            throw SmallBodyParseError.InvalidJSON(
                if (error.failure == JSONFailure.TOO_DEEP) "nested too deeply" else "the text is not valid JSON",
            )
        }
        val document = root as? JSONValue.ObjectValue ?: throw SmallBodyParseError.InvalidJSON("the top level is not an object")
        val obj = member(document, "object")
            ?: throw SmallBodyParseError.UnsupportedObject(text(document.members["message"]) ?: "the answer contains no object")
        val orbitObject = member(document, "orbit") ?: throw SmallBodyParseError.UnsupportedObject("the answer contains no orbit")

        // Kind and names.
        val kindText = text(obj.members["kind"]) ?: throw SmallBodyParseError.UnsupportedObject("the object has no kind")
        val kind = when (kindText.firstOrNull()) {
            'c' -> SmallBodyKind.COMET
            'a' -> SmallBodyKind.ASTEROID
            else -> throw SmallBodyParseError.UnsupportedObject("the object kind \"${RecordFields.excerpt(kindText)}\" is not a comet or asteroid")
        }
        val fullName = text(obj.members["fullname"])?.trim(' ', '\t')
        if (fullName.isNullOrEmpty()) throw SmallBodyParseError.MissingField(SmallBodyField.DESIGNATION)
        val designation: String
        val name: String
        val number: Int?
        if (kind == SmallBodyKind.COMET) {
            val parts = SmallBodyNaming.cometParts(fullName)
            designation = parts.first
            name = parts.second
            number = SmallBodyNaming.cometNumber(designation)
        } else {
            val identity = SmallBodyNaming.asteroidFullName(fullName)
            designation = identity.designation
            name = identity.name
            number = identity.number
        }

        // Orbit.
        val equinox = text(orbitObject.members["equinox"])
        if (equinox != null && equinox != "J2000") {
            throw SmallBodyParseError.UnsupportedObject("the elements refer to the equinox ${RecordFields.excerpt(equinox)}")
        }
        val elementArray = orbitObject.members["elements"] as? JSONValue.ArrayValue
            ?: throw SmallBodyParseError.UnsupportedObject("the orbit has no elements")
        val elements = namedValues(elementArray)
        val eccentricity = number(elements["e"], SmallBodyField.ECCENTRICITY, 0.0, SmallBodyElements.maximumEccentricity)
            ?: throw SmallBodyParseError.MissingField(SmallBodyField.ECCENTRICITY)
        val inclination = number(elements["i"], SmallBodyField.INCLINATION, 0.0, 180.0)
            ?: throw SmallBodyParseError.MissingField(SmallBodyField.INCLINATION)
        val node = number(elements["om"], SmallBodyField.LONGITUDE_OF_ASCENDING_NODE, 0.0, 360.0)
            ?: throw SmallBodyParseError.MissingField(SmallBodyField.LONGITUDE_OF_ASCENDING_NODE)
        val argument = number(elements["w"], SmallBodyField.ARGUMENT_OF_PERIHELION, 0.0, 360.0)
            ?: throw SmallBodyParseError.MissingField(SmallBodyField.ARGUMENT_OF_PERIHELION)
        val epoch = number(orbitObject.members["epoch"], SmallBodyField.EPOCH, SmallBodyElements.minimumJulianDay, SmallBodyElements.maximumJulianDay)
        val semiMajorAxis = number(elements["a"], SmallBodyField.SEMI_MAJOR_AXIS, -1e12, 1e12)
        val perihelionDistance = number(elements["q"], SmallBodyField.PERIHELION_DISTANCE, 0.0, SmallBodyElements.maximumPerihelionDistanceAU)
        val perihelionTime = number(elements["tp"], SmallBodyField.PERIHELION_TIME, SmallBodyElements.minimumJulianDay, SmallBodyElements.maximumJulianDay)
        val orbit: SmallBodyElements
        if (perihelionTime != null) {
            val q = perihelionDistance
                ?: (if (semiMajorAxis != null && eccentricity < 1 && semiMajorAxis > 0) semiMajorAxis * (1 - eccentricity) else null)
                ?: throw SmallBodyParseError.MissingField(SmallBodyField.PERIHELION_DISTANCE)
            orbit = SmallBodyElements.create(q, eccentricity, inclination, argument, node, perihelionTime, epoch)
                ?: throw SmallBodyParseError.InconsistentElements("the elements do not describe an orbit")
        } else {
            val anomaly = number(elements["ma"], SmallBodyField.MEAN_ANOMALY, -1e9, 1e9)
                ?: throw SmallBodyParseError.MissingField(SmallBodyField.PERIHELION_TIME)
            val a = semiMajorAxis
                ?: (if (perihelionDistance != null && eccentricity < 1) perihelionDistance / (1 - eccentricity) else null)
                ?: throw SmallBodyParseError.MissingField(SmallBodyField.SEMI_MAJOR_AXIS)
            if (epoch == null) throw SmallBodyParseError.MissingField(SmallBodyField.EPOCH)
            orbit = SmallBodyElements.fromMeanAnomaly(a, eccentricity, inclination, argument, node, anomaly, epoch)
                ?: throw SmallBodyParseError.InconsistentElements("the elements do not describe an elliptic orbit")
        }

        // Brightness.
        val parameters = namedValues(document.members["phys_par"] as? JSONValue.ArrayValue)
        val totalMagnitude = number(parameters["M1"], SmallBodyField.ABSOLUTE_MAGNITUDE, -20.0, 40.0)
        val totalSlope = number(parameters["K1"], SmallBodyField.SLOPE_PARAMETER, -5.0, 60.0)
        val nuclearMagnitude = number(parameters["M2"], SmallBodyField.NUCLEAR_MAGNITUDE, -20.0, 40.0)
        val nuclearSlope = number(parameters["K2"], SmallBodyField.NUCLEAR_MAGNITUDE, -5.0, 60.0)
        val phaseCoefficient = number(parameters["PC"], SmallBodyField.NUCLEAR_MAGNITUDE, -0.1, 0.5)
        val absoluteMagnitude = number(parameters["H"], SmallBodyField.ABSOLUTE_MAGNITUDE, -5.0, 40.0)
        val slope = number(parameters["G"], SmallBodyField.SLOPE_PARAMETER, -0.25, 1.0)
        val cometModel: SmallBodyPhotometry? = totalMagnitude?.let { magnitude ->
            CometMagnitudes.create(magnitude, totalSlope ?: defaultTotalSlope, nuclearMagnitude, nuclearSlope, phaseCoefficient ?: 0.0)
                ?.let { SmallBodyPhotometry.Comet(it) }
        }
        val asteroidModel: SmallBodyPhotometry? = absoluteMagnitude?.let { magnitude ->
            AsteroidMagnitudes.create(magnitude, slope ?: AsteroidMagnitudes.defaultSlopeParameter)
                ?.let { SmallBodyPhotometry.Asteroid(it) }
        }
        val photometry = if (kind == SmallBodyKind.COMET) cometModel ?: asteroidModel else asteroidModel ?: cometModel

        return SmallBody.create(
            designation = designation,
            name = name,
            kind = kind,
            number = number,
            orbit = orbit,
            photometry = photometry,
        ) ?: throw SmallBodyParseError.InvalidDesignation(RecordFields.excerpt(fullName))
    }

    /** The member [key] of [document] if it is an object. */
    private fun member(document: JSONValue.ObjectValue, key: String): JSONValue.ObjectValue? =
        document.members[key] as? JSONValue.ObjectValue

    /** The `value` of every `{"name": ..., "value": ...}` entry of [array] by name (the last of a repeated name wins). */
    private fun namedValues(array: JSONValue.ArrayValue?): Map<String, JSONValue> {
        val values = HashMap<String, JSONValue>()
        if (array == null) return values
        for (item in array.items) {
            val entry = item as? JSONValue.ObjectValue ?: continue
            val key = text(entry.members["name"]) ?: continue
            values[key] = entry.members["value"] ?: JSONValue.NullValue
        }
        return values
    }

    /** The text of a JSON string; `null` for anything else. */
    private fun text(value: JSONValue?): String? = (value as? JSONValue.StringValue)?.value

    /**
     * The number in a JSON string or number within `minimum...maximum`; `null` when the value is absent or `null`.
     *
     * @throws SmallBodyParseError.MalformedField if the value is not a plain decimal (or is a boolean, array or object),
     * FieldOutOfRange if it is outside the range.
     */
    private fun number(value: JSONValue?, field: SmallBodyField, minimum: Double, maximum: Double): Double? {
        val result = when (value) {
            null, JSONValue.NullValue -> return null
            is JSONValue.NumberValue -> value.value
            is JSONValue.StringValue -> RecordFields.decimal(value.value.trim(' ', '\t'), allowExponent = true)
                ?: throw SmallBodyParseError.MalformedField(field, RecordFields.excerpt(value.value))
            is JSONValue.BooleanValue -> throw SmallBodyParseError.MalformedField(field, if (value.value) "true" else "false")
            is JSONValue.ArrayValue -> throw SmallBodyParseError.MalformedField(field, "array")
            is JSONValue.ObjectValue -> throw SmallBodyParseError.MalformedField(field, "object")
        }
        if (result < minimum || result > maximum) throw SmallBodyParseError.FieldOutOfRange(field, result)
        return result
    }
}
