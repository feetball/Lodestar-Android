package net.kuecker.lodestar.kit.comets

import net.kuecker.lodestar.kit.foundation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parsers for the MPC comet file, the MPC one-line minor-planet format, the packed designations and the JPL
 * Small-Body Database JSON. Expected Julian Days were computed with Python's `datetime` (JD of 2000-01-01 0h = 2451544.5).
 */
class CometsParsingTests {
    private fun comet(line: String): SmallBody = CometElementsParser.parseLine(line)

    private fun cometError(line: String): SmallBodyParseError =
        assertFailsWith<SmallBodyParseError> { CometElementsParser.parseLine(line) }

    private fun asteroidError(line: String): SmallBodyParseError =
        assertFailsWith<SmallBodyParseError> { MinorPlanetElementsParser.parseLine(line) }

    private fun jsonError(text: String): SmallBodyParseError =
        assertFailsWith<SmallBodyParseError> { SmallBodyJSONParser.parse(text) }

    /** [line] with the characters of columns [first]...[last] (1-based) replaced by [text], padded with spaces. */
    private fun replacing(line: String, first: Int, last: Int, text: String): String {
        val width = last - first + 1
        return line.substring(0, first - 1) + text.padEnd(width).take(width) + line.substring(last)
    }

    private fun cometOrbit(body: SmallBody): SmallBodyElements = body.orbit

    private fun cometMagnitudes(body: SmallBody): CometMagnitudes =
        (assertNotNull(body.photometry) as SmallBodyPhotometry.Comet).magnitudes

    private fun asteroidMagnitudes(body: SmallBody): AsteroidMagnitudes =
        (assertNotNull(body.photometry) as SmallBodyPhotometry.Asteroid).magnitudes

    // MARK: MPC comets

    @Test
    fun parsesHaleBopp() {
        val body = comet(CometsTestData.cometHaleBopp)
        assertEquals("comet.C/1995 O1", body.id)
        assertEquals("C/1995 O1", body.designation)
        assertEquals("Hale-Bopp", body.name)
        assertEquals("C/1995 O1 (Hale-Bopp)", body.title)
        assertEquals(SmallBodyKind.COMET, body.kind)
        assertNull(body.number)
        val orbit = cometOrbit(body)
        assertEquals(0.925_995, orbit.perihelionDistanceAU)
        assertEquals(0.994_895, orbit.eccentricity)
        assertEquals(130.7313, orbit.argumentOfPerihelion)
        assertEquals(281.8097, orbit.longitudeOfAscendingNode)
        assertEquals(89.7335, orbit.inclination)
        assertEquals(2_450_536.5284, orbit.perihelionJulianDayTT, 1e-6)
        assertEquals(2_461_330.5, orbit.epochJulianDayTT)
        assertEquals(OrbitShape.ELLIPTIC, orbit.shape)
        assertEquals(-2.0, cometMagnitudes(body).totalMagnitude)
        assertEquals(10.0, cometMagnitudes(body).totalSlope)
        assertNull(cometMagnitudes(body).nuclearMagnitude)
    }

    @Test
    fun parsesNumberedPeriodicComets() {
        val body = comet(CometsTestData.cometPonsBrooks)
        assertEquals("comet.12P", body.id)
        assertEquals("12P", body.designation)
        assertEquals("Pons-Brooks", body.name)
        assertEquals("12P/Pons-Brooks", body.title)
        assertEquals(12, body.number)
        assertEquals(2_460_421.7066, body.orbit.perihelionJulianDayTT, 1e-6)
        assertEquals(5.0, cometMagnitudes(body).totalMagnitude)
        assertEquals(15.0, cometMagnitudes(body).totalSlope) // slope parameter k = 6.0, K1 = 2.5 k

        val halley = comet(CometsTestData.cometHalley)
        assertEquals("1P", halley.designation)
        assertEquals(1, halley.number)
        assertEquals(2_474_038.0218, halley.orbit.perihelionJulianDayTT, 1e-6) // 2061 Aug 1.5218, day padded with a blank
        assertEquals(8.0, cometMagnitudes(halley).totalSlope)
        assertEquals(0.968_030, halley.orbit.eccentricity)
        assertEquals(162.1917, halley.orbit.inclination)

        val encke = comet(CometsTestData.cometEncke)
        assertEquals("2P/Encke", encke.title)
        assertEquals(14.4, cometMagnitudes(encke).totalMagnitude)
    }

    @Test
    fun parsesFragmentsOfSplitComets() {
        val biela = comet(CometsTestData.cometBielaA)
        assertEquals("3D-A", biela.designation)
        assertEquals("Biela", biela.name)
        assertEquals(3, biela.number)
        assertEquals("3D-A/Biela", biela.title)
        assertEquals("comet.3D-A", biela.id)
        assertEquals(2_460_646.6425, biela.orbit.perihelionJulianDayTT, 1e-6)

        val wachmann = comet(CometsTestData.cometSchwassmannWachmann3Bt)
        assertEquals("73P-BT", wachmann.designation)
        assertEquals(73, wachmann.number)
        assertEquals("Schwassmann-Wachmann", wachmann.name)
    }

    @Test
    fun parsesAnUnnumberedCometWithoutAName() {
        val body = comet(CometsTestData.cometUnnamedPeriodic)
        assertEquals("P/2026 N2", body.designation)
        assertEquals("", body.name)
        assertEquals("P/2026 N2", body.title)
        assertEquals("P/2026 N2", body.displayName)
        assertNull(body.number)
        assertEquals(2_461_309.2701, body.orbit.perihelionJulianDayTT, 1e-6)
    }

    @Test
    fun parsesHyperbolicAndInterstellarComets() {
        val atlas = comet(CometsTestData.cometTsuchinshanAtlas)
        assertEquals("C/2023 A3", atlas.designation)
        assertEquals("Tsuchinshan-ATLAS", atlas.name)
        assertEquals(OrbitShape.HYPERBOLIC, atlas.orbit.shape)
        assertEquals(1.000_180, atlas.orbit.eccentricity)
        assertEquals(2_460_581.3337, atlas.orbit.perihelionJulianDayTT, 1e-6)
        assertEquals(6.5, cometMagnitudes(atlas).totalMagnitude)
        assertEquals(8.0, cometMagnitudes(atlas).totalSlope) // k = 3.2

        val oumuamua = comet(CometsTestData.cometOumuamua)
        assertEquals("1I", oumuamua.designation)
        assertEquals("`Oumuamua", oumuamua.name)
        assertEquals(1, oumuamua.number)
        assertEquals(1.199_252, oumuamua.orbit.eccentricity)
        assertEquals(2_458_005.9886, oumuamua.orbit.perihelionJulianDayTT, 1e-6)
        assertEquals(2_458_000.5, oumuamua.orbit.epochJulianDayTT)
        assertEquals(23.0, cometMagnitudes(oumuamua).totalMagnitude)
        assertEquals(5.0, cometMagnitudes(oumuamua).totalSlope)

        val borisov = comet(CometsTestData.cometBorisov)
        assertEquals("2I/Borisov", borisov.title)
        assertEquals(3.345_952, borisov.orbit.eccentricity)

        val threeI = comet(CometsTestData.cometAtlas3i)
        assertEquals("3I", threeI.designation)
        assertEquals(6.139_884, threeI.orbit.eccentricity)
        assertEquals(2_460_977.9825, threeI.orbit.perihelionJulianDayTT, 1e-6)
        assertEquals(2_461_000.5, threeI.orbit.epochJulianDayTT)

        assertEquals(OrbitShape.HYPERBOLIC, comet(CometsTestData.cometSidingSpring).orbit.shape)
    }

    @Test
    fun parsesLinesWithFewerDecimals() {
        // 18D/Perrine-Mrkos is printed with three decimals in the angles and five in q and e.
        val body = comet(CometsTestData.cometPerrineMrkos)
        assertEquals("18D", body.designation)
        assertEquals(1.59239, body.orbit.perihelionDistanceAU)
        assertEquals(0.58937, body.orbit.eccentricity)
        assertEquals(238.150, body.orbit.argumentOfPerihelion)
        assertEquals(158.452, body.orbit.longitudeOfAscendingNode)
        assertEquals(17.664, body.orbit.inclination)
        assertEquals(2_460_551.412, body.orbit.perihelionJulianDayTT, 1e-6)
    }

    @Test
    fun aMissingEpochDefaultsToThePerihelionTime() {
        val body = comet(CometsTestData.cometIkeyaMurakamiB)
        assertEquals("332P-B", body.designation)
        assertEquals(2_457_464.665, body.orbit.perihelionJulianDayTT, 1e-6)
        assertEquals(body.orbit.perihelionJulianDayTT, body.orbit.epochJulianDayTT)
        assertEquals(19.0, cometMagnitudes(body).totalMagnitude)
    }

    @Test
    fun orbitTypeAMakesAnAsteroid() {
        val body = comet(CometsTestData.cometAsteroidLike)
        assertEquals(SmallBodyKind.ASTEROID, body.kind)
        assertEquals("asteroid.A/2018 W3", body.id)
        assertEquals("A/2018 W3", body.designation)
        assertEquals(2_459_317.679, body.orbit.perihelionJulianDayTT, 1e-6)
        assertEquals(5.0, cometMagnitudes(body).totalSlope) // k = 2.0
    }

    @Test
    fun cometNameFormsAreSplit() {
        assertEquals(Pair("12P", "Pons-Brooks"), SmallBodyNaming.cometParts("12P/Pons-Brooks"))
        assertEquals(Pair("73P-B", "Schwassmann-Wachmann 3"), SmallBodyNaming.cometParts("73P-B/Schwassmann-Wachmann 3"))
        assertEquals(Pair("C/2023 A3", "Tsuchinshan-ATLAS"), SmallBodyNaming.cometParts("C/2023 A3 (Tsuchinshan-ATLAS)"))
        assertEquals(Pair("D/1993 F2-A", "Shoemaker-Levy 9"), SmallBodyNaming.cometParts("D/1993 F2-A (Shoemaker-Levy 9)"))
        assertEquals(Pair("P/2026 N2", ""), SmallBodyNaming.cometParts("P/2026 N2"))
        assertEquals(Pair("C/2010 X1", "Elenin (LINEAR (A))"), SmallBodyNaming.cometParts("C/2010 X1 (Elenin (LINEAR (A)))"))
        assertEquals(Pair("1I", "`Oumuamua"), SmallBodyNaming.cometParts("1I/`Oumuamua"))
        assertEquals(Pair("Strange", ""), SmallBodyNaming.cometParts("Strange"))
        assertEquals(Pair("(only)", ""), SmallBodyNaming.cometParts("(only)"))
        assertEquals(Pair("X/1", "a"), SmallBodyNaming.cometParts("X/1 (a)"))
        assertEquals(12, SmallBodyNaming.cometNumber("12P"))
        assertEquals(73, SmallBodyNaming.cometNumber("73P-B"))
        assertEquals(1, SmallBodyNaming.cometNumber("1I"))
        assertNull(SmallBodyNaming.cometNumber("C/2023 A3"))
        assertNull(SmallBodyNaming.cometNumber("1995 O1"))
        assertNull(SmallBodyNaming.cometNumber("12"))
        assertNull(SmallBodyNaming.cometNumber("12Z"))
        assertNull(SmallBodyNaming.cometNumber("12Px"))
        assertNull(SmallBodyNaming.cometNumber("0P"))
    }

    @Test
    fun designationFallsBackToThePackedColumns() {
        // Without the name columns the designation is built from the number, type and packed designation.
        val numbered = replacing(CometsTestData.cometPonsBrooks, 103, 168, "")
        assertEquals("12P", comet(numbered).designation)
        val fragment = replacing(CometsTestData.cometBielaA, 103, 168, "")
        assertEquals("3D-A", comet(fragment).designation)
        val provisional = replacing(CometsTestData.cometHaleBopp, 103, 168, "")
        assertEquals("C/1995 O1", comet(provisional).designation)
        assertEquals("comet.C/1995 O1", comet(provisional).id)
        val periodic = replacing(CometsTestData.cometUnnamedPeriodic, 103, 168, "")
        assertEquals("P/2026 N2", comet(periodic).designation)
        assertEquals(
            SmallBodyParseError.InvalidDesignation("J95O0"),
            cometError(replacing(replacing(CometsTestData.cometHaleBopp, 103, 168, ""), 6, 12, "J95O0")),
        )
        assertEquals(
            SmallBodyParseError.InvalidDesignation(""),
            cometError(replacing(replacing(CometsTestData.cometHaleBopp, 103, 168, ""), 6, 12, "")),
        )
    }

    @Test
    fun cometLineErrorsAreTyped() {
        val good = CometsTestData.cometPonsBrooks
        assertEquals(SmallBodyParseError.LineTooShort(10, 79), cometError("0012P     "))
        assertEquals(SmallBodyParseError.LineTooShort(0, 79), cometError(""))
        assertEquals(SmallBodyParseError.LineTooLong(401, 400), cometError(good.padEnd(401, 'x')))
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.ORBIT_TYPE), cometError(replacing(good, 5, 5, "")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.ORBIT_TYPE, "Z"), cometError(replacing(good, 5, 5, "Z")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.COMET_NUMBER, "00x2"), cometError(replacing(good, 1, 4, "00x2")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.COMET_NUMBER, "0000"), cometError(replacing(good, 1, 4, "0000")))
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.PERIHELION_YEAR), cometError(replacing(good, 15, 18, "")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.PERIHELION_YEAR, "20x4"), cometError(replacing(good, 15, 18, "20x4")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_YEAR, 1500.0), cometError(replacing(good, 15, 18, "1500")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_MONTH, 13.0), cometError(replacing(good, 20, 21, "13")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_MONTH, 0.0), cometError(replacing(good, 20, 21, " 0")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.PERIHELION_DAY, "2x.2066"), cometError(replacing(good, 23, 29, "2x.2066")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_DAY, 32.0), cometError(replacing(good, 23, 29, "32.0000")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_DAY, -1.0), cometError(replacing(good, 23, 29, "-1.0000")))
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.PERIHELION_DISTANCE), cometError(replacing(good, 31, 39, "")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_DISTANCE, -0.5), cometError(replacing(good, 31, 39, "-0.5")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.PERIHELION_DISTANCE, "NaN"), cometError(replacing(good, 31, 39, "NaN")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.ECCENTRICITY, "1e0"), cometError(replacing(good, 42, 49, "1e0")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.ECCENTRICITY, "0x1p-1"), cometError(replacing(good, 42, 49, "0x1p-1")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.ECCENTRICITY, -0.1), cometError(replacing(good, 42, 49, "-0.1")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.ARGUMENT_OF_PERIHELION, 400.0), cometError(replacing(good, 52, 59, "400.0")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.LONGITUDE_OF_ASCENDING_NODE, -1.0), cometError(replacing(good, 62, 69, "-1.0")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.INCLINATION, 181.0), cometError(replacing(good, 72, 79, "181.0")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.INCLINATION, "1 8"), cometError(replacing(good, 72, 79, "1 8")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.EPOCH, "2026101"), cometError(replacing(good, 82, 89, "2026101")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.EPOCH, "20261340"), cometError(replacing(good, 82, 89, "20261340")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.EPOCH, "20260230"), cometError(replacing(good, 82, 89, "20260230")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.EPOCH, "2026-1-7"), cometError(replacing(good, 82, 89, "2026-1-7")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.ABSOLUTE_MAGNITUDE, "abc"), cometError(replacing(good, 92, 95, "abc")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.ABSOLUTE_MAGNITUDE, 41.0), cometError(replacing(good, 92, 95, "41.0")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.SLOPE_PARAMETER, "x"), cometError(replacing(good, 97, 100, "x")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.SLOPE_PARAMETER, 30.0), cometError(replacing(good, 97, 100, "30.0")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.SLOPE_PARAMETER, -1.0), cometError(replacing(good, 97, 100, "-1.0")))
    }

    @Test
    fun cometPerihelionDayMustExistInItsMonth() {
        val good = CometsTestData.cometPonsBrooks
        fun line(year: String, month: String, day: String): String =
            replacing(replacing(replacing(good, 15, 18, year), 20, 21, month), 23, 29, day)
        // A day the month does not have used to roll over into the next month, and a day before the 1st into the previous one.
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_DAY, 31.5), cometError(line("2026", " 2", "31.5000")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_DAY, 29.0), cometError(line("2025", " 2", "29.0000")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_DAY, 30.0), cometError(line("2024", " 2", "30.0000")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_DAY, 31.0), cometError(line("2026", " 4", "31.0000")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_DAY, 0.5), cometError(line("2026", " 4", "0.5000")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.PERIHELION_DAY, 0.0), cometError(line("2026", " 4", "0.0000")))
        // The first instant of a month, the leap day and the last fraction of a 30-day month are fine.
        assertEquals(2_460_310.5, comet(line("2024", " 1", "1.0000")).orbit.perihelionJulianDayTT, 1e-9)
        assertEquals(2_460_369.5, comet(line("2024", " 2", "29.0000")).orbit.perihelionJulianDayTT, 1e-9)
        assertEquals(2_460_431.4999, comet(line("2024", " 4", "30.9999")).orbit.perihelionJulianDayTT, 1e-6)
    }

    @Test
    fun cometPhotometryIsOptionalAndDefaultsTheSlope() {
        val good = CometsTestData.cometPonsBrooks
        assertNull(comet(replacing(good, 92, 100, "")).photometry)
        val noSlope = comet(replacing(good, 97, 100, ""))
        assertEquals(5.0, cometMagnitudes(noSlope).totalMagnitude)
        assertEquals(10.0, cometMagnitudes(noSlope).totalSlope)
        assertEquals(4.0, CometElementsParser.defaultSlopeParameter)
        // A slope without an absolute magnitude is validated but gives no photometry.
        assertNull(comet(replacing(good, 92, 95, "")).photometry)
        // The line may stop after the inclination.
        val truncated = good.substring(0, 79)
        val body = comet(truncated)
        assertEquals("12P", body.designation)
        assertNull(body.photometry)
    }

    @Test
    fun cometFileIsReadLeniently() {
        val lines = listOf(
            CometsTestData.cometPonsBrooks,
            "",
            "   ",
            "this is not a comet line at all, but it is long enough to be examined by the column reader of the parser ok",
            CometsTestData.cometHaleBopp,
            replacing(CometsTestData.cometEncke, 42, 49, "abc"),
            CometsTestData.cometPonsBrooks,
            "short",
            CometsTestData.cometHalley,
        )
        val result = CometElementsParser.parse(lines.joinToString("\r\n") + "\r\n")
        assertEquals(listOf("comet.12P", "comet.C/1995 O1", "comet.1P"), result.bodies.map { it.id })
        assertEquals(4, result.rejectedLineCount)
        assertEquals(listOf(4, 6, 7, 8), result.rejections.map { it.lineNumber })
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.COMET_NUMBER, "this"), result.rejections[0].error)
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.ECCENTRICITY, "abc"), result.rejections[1].error)
        assertEquals(SmallBodyParseError.DuplicateObject("comet.12P"), result.rejections[2].error)
        assertEquals(SmallBodyParseError.LineTooShort(5, 79), result.rejections[3].error)
    }

    @Test
    fun fileWithoutTrailingNewlineAndWithLfOnly() {
        val text = CometsTestData.cometPonsBrooks + "\n" + CometsTestData.cometHalley
        val result = CometElementsParser.parse(text)
        assertEquals(2, result.bodies.size)
        assertEquals(0, result.rejectedLineCount)
        assertTrue(result.rejections.isEmpty())
        assertEquals(0, CometElementsParser.parse("").bodies.size)
        assertEquals(0, CometElementsParser.parse("\n\n  \n").rejectedLineCount)
    }

    @Test
    fun onlyTheFirstRejectionsAreReported() {
        val text = (1..80).joinToString("\n") { "x" }
        val result = CometElementsParser.parse(text)
        assertEquals(80, result.rejectedLineCount)
        assertEquals(SmallBodyParseResult.maximumReportedRejections, result.rejections.size)
        assertEquals(50, result.rejections.size)
        assertEquals(50, result.rejections.last().lineNumber)
    }

    @Test
    fun oversizedInputIsRefused() {
        val error = assertFailsWith<SmallBodyParseError.InputTooLarge> {
            CometElementsParser.parse("x".repeat(SmallBodyBatch.maximumInputLength + 1))
        }
        assertEquals(SmallBodyParseError.InputTooLarge(SmallBodyBatch.maximumInputLength + 1, SmallBodyBatch.maximumInputLength), error)
        assertFailsWith<SmallBodyParseError.InputTooLarge> {
            MinorPlanetElementsParser.parse("x".repeat(SmallBodyBatch.maximumInputLength + 1))
        }
    }

    // MARK: MPC minor planets

    @Test
    fun parsesCeresFromMPCORB() {
        val body = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidCeres)
        assertEquals("asteroid.1", body.id)
        assertEquals("1", body.designation)
        assertEquals("Ceres", body.name)
        assertEquals("(1) Ceres", body.title)
        assertEquals(1, body.number)
        assertEquals(SmallBodyKind.ASTEROID, body.kind)
        val orbit = body.orbit
        assertEquals(2.7655526 * (1 - 0.0796923), orbit.perihelionDistanceAU, 1e-12)
        assertEquals(0.0796923, orbit.eccentricity)
        assertEquals(73.29420, orbit.argumentOfPerihelion)
        assertEquals(80.24863, orbit.longitudeOfAscendingNode)
        assertEquals(10.58803, orbit.inclination)
        assertEquals(2_461_200.5, orbit.epochJulianDayTT) // K2669 = 2026 June 9
        assertEquals(274.41935, assertNotNull(orbit.meanAnomaly(2_461_200.5)), 1e-6)
        // JPL's time of perihelion for Ceres: JD 2461599.8415 (TDB).
        assertEquals(2_461_599.8415, orbit.perihelionJulianDayTT, 2e-3)
        assertEquals(3.34, asteroidMagnitudes(body).absoluteMagnitude)
        assertEquals(0.15, asteroidMagnitudes(body).slopeParameter)
    }

    @Test
    fun parsesVestaFromMPCORB() {
        val body = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidVesta)
        assertEquals("(4) Vesta", body.title)
        assertEquals(3.25, asteroidMagnitudes(body).absoluteMagnitude)
        assertEquals(2_460_901.5874, body.orbit.perihelionJulianDayTT, 2e-3) // JPL: 2460901.58738
        assertEquals(2.3613660, assertNotNull(body.orbit.semiMajorAxisAU), 1e-7)
        assertEquals(0.27161836, assertNotNull(body.orbit.meanMotion), 1e-7)
    }

    @Test
    fun parsesPackedNumbersAndNames() {
        val eros = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidEros)
        assertEquals(433, eros.number)
        assertEquals("Eros", eros.name)
        assertEquals(10.39, asteroidMagnitudes(eros).absoluteMagnitude)
        val apophis = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidApophis)
        assertEquals(99942, apophis.number)
        assertEquals("Apophis", apophis.name)
        assertEquals("(99942) Apophis", apophis.title)
        val bennu = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidBennu)
        assertEquals(101_955, bennu.number)
        assertEquals("Bennu", bennu.name)
        assertEquals("asteroid.101955", bennu.id)
        val unnamed = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidNumberedUnnamed)
        assertEquals(202_411, unnamed.number)
        assertEquals("2005 RC", unnamed.name)
        assertEquals("(202411) 2005 RC", unnamed.title)
    }

    @Test
    fun parsesProvisionalDesignations() {
        val yr4 = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidYr42024)
        assertEquals("2024 YR4", yr4.designation)
        assertEquals("asteroid.2024 YR4", yr4.id)
        assertEquals("2024 YR4", yr4.title)
        assertNull(yr4.number)
        assertEquals("", yr4.name)
        assertEquals(23.93, asteroidMagnitudes(yr4).absoluteMagnitude)
        assertEquals(0.6613330, yr4.orbit.eccentricity)
        assertEquals("2024 AA", MinorPlanetElementsParser.parseLine(CometsTestData.asteroidYearEndEpochAa).designation)
        val flagged = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidAssumedEccentricity)
        assertEquals("2006 WO130", flagged.designation) // the cycle count 130 is packed as D0
        assertEquals(26.1, asteroidMagnitudes(flagged).absoluteMagnitude)
    }

    @Test
    fun anUnknownAbsoluteMagnitudeGivesNoPhotometry() {
        val body = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidUnknownH)
        assertEquals("1991 TT", body.designation)
        assertNull(body.photometry) // the MPC writes 99.99
        assertNull(MinorPlanetElementsParser.parseLine(replacing(CometsTestData.asteroidCeres, 9, 13, "")).photometry)
        val noSlope = MinorPlanetElementsParser.parseLine(replacing(CometsTestData.asteroidCeres, 15, 19, ""))
        assertEquals(0.15, asteroidMagnitudes(noSlope).slopeParameter)
        assertEquals(99.0, MinorPlanetElementsParser.unknownMagnitude)
    }

    @Test
    fun asteroidLineErrorsAreTyped() {
        val good = CometsTestData.asteroidCeres
        assertEquals(SmallBodyParseError.LineTooShort(5, 103), asteroidError("00001"))
        assertEquals(SmallBodyParseError.LineTooLong(401, 400), asteroidError(good.padEnd(401, 'x')))
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.EPOCH), asteroidError(replacing(good, 21, 25, "")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.EPOCH, "K2D69"), asteroidError(replacing(good, 21, 25, "K2D69")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.EPOCH, "K26W9"), asteroidError(replacing(good, 21, 25, "K26W9")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.EPOCH, "K2B31"), asteroidError(replacing(good, 21, 25, "K2B31")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.EPOCH, "X2669"), asteroidError(replacing(good, 21, 25, "X2669")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.MEAN_ANOMALY, 400.0), asteroidError(replacing(good, 27, 35, "400.0")))
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.MEAN_ANOMALY), asteroidError(replacing(good, 27, 35, "")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.ARGUMENT_OF_PERIHELION, "7x.2"), asteroidError(replacing(good, 38, 46, "7x.2")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.LONGITUDE_OF_ASCENDING_NODE, -2.0), asteroidError(replacing(good, 49, 57, "-2.0")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.INCLINATION, 190.0), asteroidError(replacing(good, 60, 68, "190.0")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.ECCENTRICITY, 1.0), asteroidError(replacing(good, 71, 79, "1.0")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.ECCENTRICITY, -0.1), asteroidError(replacing(good, 71, 79, "-0.1")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.MEAN_MOTION, 0.0), asteroidError(replacing(good, 81, 91, "0.0")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.MEAN_MOTION, 41.0), asteroidError(replacing(good, 81, 91, "41.0")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.SEMI_MAJOR_AXIS, 0.0), asteroidError(replacing(good, 93, 103, "0.0")))
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.SEMI_MAJOR_AXIS), asteroidError(replacing(good, 93, 103, "")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.ABSOLUTE_MAGNITUDE, "3.x"), asteroidError(replacing(good, 9, 13, "3.x")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.ABSOLUTE_MAGNITUDE, -9.0), asteroidError(replacing(good, 9, 13, "-9.0")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.ABSOLUTE_MAGNITUDE, 45.0), asteroidError(replacing(good, 9, 13, "45.0")))
        assertEquals(SmallBodyParseError.MalformedField(SmallBodyField.SLOPE_PARAMETER, "0.1.5"), asteroidError(replacing(good, 15, 19, "0.1.5")))
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.SLOPE_PARAMETER, 1.5), asteroidError(replacing(good, 15, 19, "1.5")))
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.PACKED_DESIGNATION), asteroidError(replacing(good, 1, 7, "")))
        assertEquals(SmallBodyParseError.InvalidDesignation("0000"), asteroidError(replacing(good, 1, 7, "0000")))
        assertEquals(SmallBodyParseError.InvalidDesignation("00000"), asteroidError(replacing(good, 1, 7, "00000")))
        assertEquals(SmallBodyParseError.InvalidDesignation("Z24Y04R"), asteroidError(replacing(replacing(good, 1, 7, "Z24Y04R"), 167, 194, "")))
    }

    @Test
    fun meanMotionMustAgreeWithTheSemiMajorAxis() {
        val good = CometsTestData.asteroidCeres
        val error = asteroidError(replacing(good, 81, 91, "0.30000000"))
        assertTrue(error is SmallBodyParseError.InconsistentElements, "$error")
        // An error of less than one percent is accepted (the MPC rounds both numbers).
        MinorPlanetElementsParser.parseLine(replacing(good, 81, 91, "0.21500000"))
    }

    @Test
    fun readableDesignationIsOptional() {
        // Without the readable columns the number and provisional designation come from the packed columns alone.
        val noName = replacing(CometsTestData.asteroidVesta, 167, 202, "")
        val body = MinorPlanetElementsParser.parseLine(noName)
        assertEquals("4", body.designation)
        assertEquals("", body.name)
        assertEquals("(4)", body.title)
        val provisional = MinorPlanetElementsParser.parseLine(replacing(CometsTestData.asteroidYr42024, 167, 202, ""))
        assertEquals("2024 YR4", provisional.designation)
        // A readable designation that disagrees with the packed number gives no name.
        val mismatched = replacing(CometsTestData.asteroidVesta, 167, 194, "(5) Astraea")
        assertEquals("", MinorPlanetElementsParser.parseLine(mismatched).name)
        // The readable designation rescues a packed one that cannot be unpacked.
        val rescued = replacing(replacing(CometsTestData.asteroidYr42024, 1, 7, "K24Y0?R"), 167, 194, "2024 YR4")
        assertEquals("2024 YR4", MinorPlanetElementsParser.parseLine(rescued).designation)
    }

    @Test
    fun mpcorbFileIsReadWithItsHeader() {
        val header = listOf(
            "MINOR PLANET CENTER ORBIT DATABASE (MPCORB)",
            "",
            "Des'n     H     G   Epoch     M        Peri.      Node       Incl.       e            n           a        Reference #Obs #Opp    Arc    rms  Perts   Computer",
            "",
            "-".repeat(160),
        )
        val body = listOf(
            CometsTestData.asteroidCeres,
            CometsTestData.asteroidVesta,
            "",
            CometsTestData.asteroidEros,
            replacing(CometsTestData.asteroidApophis, 71, 79, "1.5"),
            CometsTestData.asteroidCeres,
        )
        val result = MinorPlanetElementsParser.parse((header + body).joinToString("\n"))
        assertEquals(listOf("asteroid.1", "asteroid.4", "asteroid.433"), result.bodies.map { it.id })
        assertEquals(2, result.rejectedLineCount)
        assertEquals(listOf(10, 11), result.rejections.map { it.lineNumber })
        assertEquals(SmallBodyParseError.FieldOutOfRange(SmallBodyField.ECCENTRICITY, 1.5), result.rejections[0].error)
        assertEquals(SmallBodyParseError.DuplicateObject("asteroid.1"), result.rejections[1].error)
    }

    @Test
    fun fileWithoutAHeaderIsReadFromTheFirstLine() {
        val result = MinorPlanetElementsParser.parse(CometsTestData.asteroidCeres + "\n" + CometsTestData.asteroidVesta)
        assertEquals(2, result.bodies.size)
        assertEquals(0, result.rejectedLineCount)
        // A short rule is not a header.
        val short = MinorPlanetElementsParser.parse("-----\n" + CometsTestData.asteroidCeres)
        assertEquals(1, short.bodies.size)
        assertEquals(1, short.rejectedLineCount)
    }

    // MARK: Packed designations

    @Test
    fun packedMinorPlanetNumbers() {
        // Examples of https://www.minorplanetcenter.net/iau/info/PackedDes.html
        assertEquals(3202, PackedDesignation.number("03202"))
        assertEquals(50_000, PackedDesignation.number("50000"))
        assertEquals(100_345, PackedDesignation.number("A0345"))
        assertEquals(360_017, PackedDesignation.number("a0017"))
        assertEquals(203_289, PackedDesignation.number("K3289"))
        assertEquals(620_000, PackedDesignation.number("~0000"))
        assertEquals(620_061, PackedDesignation.number("~000z"))
        assertEquals(3_140_113, PackedDesignation.number("~AZaz"))
        assertEquals(15_396_335, PackedDesignation.number("~zzzz"))
        assertNull(PackedDesignation.number("00000"))
        assertNull(PackedDesignation.number("0345"))
        assertNull(PackedDesignation.number("A03x5"))
        assertNull(PackedDesignation.number("~0 00"))
        assertNull(PackedDesignation.number("#0345"))
        assertNull(PackedDesignation.number(""))
    }

    @Test
    fun packedMinorPlanetProvisionalDesignations() {
        // Examples of the MPC document.
        assertEquals("1995 XA", PackedDesignation.minorPlanet("J95X00A"))
        assertEquals("1995 XL1", PackedDesignation.minorPlanet("J95X01L"))
        assertEquals("1995 FB13", PackedDesignation.minorPlanet("J95F13B"))
        assertEquals("1998 SQ108", PackedDesignation.minorPlanet("J98SA8Q"))
        assertEquals("1998 SV127", PackedDesignation.minorPlanet("J98SC7V"))
        assertEquals("1998 SS162", PackedDesignation.minorPlanet("J98SG2S"))
        assertEquals("2099 AZ193", PackedDesignation.minorPlanet("K99AJ3Z"))
        assertEquals("2008 AA360", PackedDesignation.minorPlanet("K08Aa0A"))
        assertEquals("2007 TA418", PackedDesignation.minorPlanet("K07Tf8A"))
        assertEquals("1845 AB", PackedDesignation.minorPlanet("I45A00B"))
        assertEquals("2040 P-L", PackedDesignation.minorPlanet("PLS2040"))
        assertEquals("3138 T-1", PackedDesignation.minorPlanet("T1S3138"))
        assertEquals("1010 T-2", PackedDesignation.minorPlanet("T2S1010"))
        assertEquals("4101 T-3", PackedDesignation.minorPlanet("T3S4101"))
        assertNull(PackedDesignation.minorPlanet("J95X00"))
        assertNull(PackedDesignation.minorPlanet("L95X00A"))
        assertNull(PackedDesignation.minorPlanet("J95I00A"))
        assertNull(PackedDesignation.minorPlanet("J95X00I"))
        assertNull(PackedDesignation.minorPlanet("J95X0xA"))
        assertNull(PackedDesignation.minorPlanet("J9XX00A"))
        assertNull(PackedDesignation.minorPlanet("PLS0000"))
        assertNull(PackedDesignation.minorPlanet("PLS20x0"))
    }

    @Test
    fun packedCometDesignations() {
        assertEquals("1995 A1", PackedDesignation.cometProvisional("J95A010"))
        assertEquals("1994 P1-B", PackedDesignation.cometProvisional("J94P01b"))
        assertEquals("1994 P1", PackedDesignation.cometProvisional("J94P010"))
        assertEquals("2048 X13", PackedDesignation.cometProvisional("K48X130"))
        assertEquals("2033 L89-C", PackedDesignation.cometProvisional("K33L89c"))
        assertEquals("2088 A103", PackedDesignation.cometProvisional("K88AA30"))
        assertNull(PackedDesignation.cometProvisional("J95A000"))
        assertNull(PackedDesignation.cometProvisional("J95A01B"))
        assertNull(PackedDesignation.cometProvisional("J95A01"))
        assertNull(PackedDesignation.cometProvisional("J95I010"))
    }

    @Test
    fun packedDates() {
        assertEquals(PackedDate(2026, 6, 9), PackedDesignation.date("K2669"))
        assertEquals(PackedDate(2024, 10, 24), PackedDesignation.date("K24AO"))
        assertEquals(PackedDate(2025, 11, 11), PackedDesignation.date("K25BB"))
        assertEquals(PackedDate(2025, 12, 31), PackedDesignation.date("K25CV"))
        assertEquals(PackedDate(1996, 1, 1), PackedDesignation.date("J9611"))
        assertEquals(PackedDate(1850, 2, 28), PackedDesignation.date("I502S"))
        assertEquals(PackedDate(2024, 2, 29), PackedDesignation.date("K242T"))
        assertNull(PackedDesignation.date("K252T")) // 2025 is not a leap year
        assertNull(PackedDesignation.date("K24DA"))
        assertNull(PackedDesignation.date("K2401"))
        assertNull(PackedDesignation.date("K24A0"))
        assertNull(PackedDesignation.date("K24AW"))
        assertNull(PackedDesignation.date("K24"))
        assertNull(PackedDesignation.date("L2669"))
        assertNull(PackedDesignation.date("K2x69"))
    }

    // MARK: Numbers

    @Test
    fun decimalGrammar() {
        assertEquals(12.0, RecordFields.decimal("12"))
        assertEquals(-0.5, RecordFields.decimal("-0.5"))
        assertEquals(0.5, RecordFields.decimal(".5"))
        assertEquals(5.0, RecordFields.decimal("5."))
        assertEquals(2.5, RecordFields.decimal("+2.5"))
        assertEquals(-0.25, RecordFields.decimal("-.25"))
        assertNull(RecordFields.decimal(""))
        assertNull(RecordFields.decimal("."))
        assertNull(RecordFields.decimal("-"))
        assertNull(RecordFields.decimal("+."))
        assertNull(RecordFields.decimal("1.2.3"))
        assertNull(RecordFields.decimal("1 2"))
        assertNull(RecordFields.decimal("1e5"))
        assertNull(RecordFields.decimal("NaN"))
        assertNull(RecordFields.decimal("Infinity"))
        assertNull(RecordFields.decimal("0x10"))
        assertNull(RecordFields.decimal("1.5f"))
        assertNull(RecordFields.decimal("1.5d"))
        assertNull(RecordFields.decimal("--1"))
        assertNull(RecordFields.decimal("1".repeat(41)))
        assertEquals(1e5, RecordFields.decimal("1e5", allowExponent = true))
        assertEquals(3.7e-6, RecordFields.decimal("3.7E-6", allowExponent = true))
        assertEquals(-1.5e3, RecordFields.decimal("-1.5e+3", allowExponent = true))
        assertNull(RecordFields.decimal("1e", allowExponent = true))
        assertNull(RecordFields.decimal("1e+", allowExponent = true))
        assertNull(RecordFields.decimal("e5", allowExponent = true))
        assertNull(RecordFields.decimal("1e999", allowExponent = true))
        assertEquals(12, RecordFields.integer("12"))
        assertEquals(-7, RecordFields.integer("-7"))
        assertEquals(5, RecordFields.integer("+5"))
        assertEquals(987_654_321, RecordFields.integer("987654321"))
        assertNull(RecordFields.integer("9876543210"))
        assertNull(RecordFields.integer("1.5"))
        assertNull(RecordFields.integer("-"))
        assertNull(RecordFields.integer(""))
        assertNull(RecordFields.integer("1 2"))
        assertEquals(28, RecordFields.daysInMonth(2025, 2))
        assertEquals(29, RecordFields.daysInMonth(2024, 2))
        assertEquals(28, RecordFields.daysInMonth(1900, 2))
        assertEquals(29, RecordFields.daysInMonth(2000, 2))
        assertEquals(30, RecordFields.daysInMonth(2025, 4))
        assertEquals(31, RecordFields.daysInMonth(2025, 12))
    }

    @Test
    fun fixedWidthColumnsAreCountedInCharactersNotBytes() {
        // A non-ASCII name must not shift the columns that follow it, and a short line has blank columns.
        val line = FixedWidthLine("ab\u00E9\uD83D\uDE00 x")
        assertEquals(6, line.length)
        assertEquals("\u00E9", line.text(3, 3))
        assertEquals("\uD83D\uDE00", line.text(4, 4))
        assertEquals("x", line.text(5, 7))
        assertEquals("", line.text(20, 30))
        assertEquals("ab\u00E9\uD83D\uDE00", line.text(1, 5))
        assertEquals("", FixedWidthLine("   ").text(1, 3))
        assertEquals("a b", FixedWidthLine(" a b\t").text(1, 5))
    }

    @Test
    fun nonAsciiNamesAreKept() {
        val body = comet(replacing(CometsTestData.cometPonsBrooks, 103, 158, "12P/Pons-Br\u00F6oks"))
        assertEquals("Pons-Br\u00F6oks", body.name)
    }

    // MARK: SmallBody

    @Test
    fun smallBodyCreationValidatesText() {
        val orbit = assertNotNull(SmallBodyElements.create(1.0, 0.5, 0.0, 0.0, 0.0, 2_460_000.0))
        val body = assertNotNull(SmallBody.create("  C/2030 A1  ", " Test ", SmallBodyKind.COMET, null, orbit))
        assertEquals("C/2030 A1", body.designation)
        assertEquals("Test", body.name)
        assertEquals("comet.C/2030 A1", body.id)
        assertNull(body.photometry)
        assertNull(SmallBody.create("", "x", SmallBodyKind.COMET, null, orbit))
        assertNull(SmallBody.create("   ", "x", SmallBodyKind.COMET, null, orbit))
        assertNull(SmallBody.create("C/2030\nA1", "", SmallBodyKind.COMET, null, orbit))
        assertNull(SmallBody.create("A".repeat(81), "", SmallBodyKind.COMET, null, orbit))
        assertNotNull(SmallBody.create("A".repeat(80), "", SmallBodyKind.COMET, null, orbit))
        assertNull(SmallBody.create("X", "n".repeat(81), SmallBodyKind.COMET, null, orbit))
        assertNull(SmallBody.create("X", "a\u0007b", SmallBodyKind.COMET, null, orbit))
        assertNull(SmallBody.create("X", "a\u0085b", SmallBodyKind.COMET, null, orbit))
        assertNull(SmallBody.create("X", "", SmallBodyKind.COMET, 0, orbit))
        assertNull(SmallBody.create("X", "", SmallBodyKind.COMET, 100_000_000, orbit))
        assertNotNull(SmallBody.create("X", "", SmallBodyKind.COMET, 99_999_999, orbit))
    }

    @Test
    fun titlesFollowAstronomicalConvention() {
        val orbit = assertNotNull(SmallBodyElements.create(1.0, 0.5, 0.0, 0.0, 0.0, 2_460_000.0))
        assertEquals("12P/Pons-Brooks", assertNotNull(SmallBody.create("12P", "Pons-Brooks", SmallBodyKind.COMET, 12, orbit)).title)
        assertEquals("12P", assertNotNull(SmallBody.create("12P", "", SmallBodyKind.COMET, 12, orbit)).title)
        assertEquals("C/2023 A3 (Tsuchinshan-ATLAS)", assertNotNull(SmallBody.create("C/2023 A3", "Tsuchinshan-ATLAS", SmallBodyKind.COMET, null, orbit)).title)
        assertEquals("(4) Vesta", assertNotNull(SmallBody.create("4", "Vesta", SmallBodyKind.ASTEROID, 4, orbit)).title)
        assertEquals("(44594)", assertNotNull(SmallBody.create("44594", "", SmallBodyKind.ASTEROID, 44_594, orbit)).title)
        assertEquals("2024 YR4", assertNotNull(SmallBody.create("2024 YR4", "", SmallBodyKind.ASTEROID, null, orbit)).title)
        assertEquals("Vesta", assertNotNull(SmallBody.create("4", "Vesta", SmallBodyKind.ASTEROID, 4, orbit)).displayName)
    }

    @Test
    fun smallBodiesAreValuesWithStableIdentifiers() {
        val a = comet(CometsTestData.cometPonsBrooks)
        val b = comet(CometsTestData.cometPonsBrooks)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        // An element update keeps the identifier.
        val updated = comet(replacing(CometsTestData.cometPonsBrooks, 31, 39, "0.781000"))
        assertEquals(a.id, updated.id)
        assertTrue(a != updated)
    }

    // MARK: Small-Body Database JSON

    @Test
    fun parsesVestaFromTheSmallBodyDatabase() {
        val body = SmallBodyJSONParser.parse(CometsTestData.jsonVesta)
        assertEquals("asteroid.4", body.id)
        assertEquals("4", body.designation)
        assertEquals("Vesta", body.name)
        assertEquals(4, body.number)
        assertEquals(SmallBodyKind.ASTEROID, body.kind)
        assertEquals(2.148_361_914_524_259, body.orbit.perihelionDistanceAU)
        assertEquals(0.090_203_743_828_343_95, body.orbit.eccentricity)
        assertEquals(7.143_925_545_058_711, body.orbit.inclination)
        assertEquals(103.701_293_265_032, body.orbit.longitudeOfAscendingNode)
        assertEquals(151.468_647_822_156_4, body.orbit.argumentOfPerihelion)
        assertEquals(2_460_901.587_379_843, body.orbit.perihelionJulianDayTT, 1e-9)
        assertEquals(2_461_200.5, body.orbit.epochJulianDayTT)
        assertEquals(3.25, asteroidMagnitudes(body).absoluteMagnitude)
        assertEquals(0.32, asteroidMagnitudes(body).slopeParameter)
    }

    @Test
    fun parsesCometsFromTheSmallBodyDatabase() {
        val pons = SmallBodyJSONParser.parse(CometsTestData.jsonPonsBrooks)
        assertEquals("comet.12P", pons.id)
        assertEquals("12P", pons.designation)
        assertEquals("Pons-Brooks", pons.name)
        assertEquals(12, pons.number)
        assertEquals(0.780_861_133_778_458_6, pons.orbit.perihelionDistanceAU)
        assertEquals(2_460_421.631_157_980_6, pons.orbit.perihelionJulianDayTT, 1e-9)
        assertEquals(2_460_211.5, pons.orbit.epochJulianDayTT)
        val magnitudes = cometMagnitudes(pons)
        assertEquals(5.0, magnitudes.totalMagnitude)
        assertEquals(15.0, magnitudes.totalSlope)
        assertEquals(11.0, magnitudes.nuclearMagnitude)
        assertEquals(10.0, magnitudes.nuclearSlope)
        assertEquals(0.0, magnitudes.nuclearPhaseCoefficient)

        val atlas = SmallBodyJSONParser.parse(CometsTestData.jsonTsuchinshanAtlas)
        assertEquals("C/2023 A3", atlas.designation)
        assertEquals("Tsuchinshan-ATLAS", atlas.name)
        assertNull(atlas.number)
        assertEquals(OrbitShape.HYPERBOLIC, atlas.orbit.shape)
        assertEquals(1.000_095_368_540_586, atlas.orbit.eccentricity)
        assertEquals(8.9, cometMagnitudes(atlas).totalMagnitude)
        assertEquals(5.5, cometMagnitudes(atlas).totalSlope)
        assertNull(cometMagnitudes(atlas).nuclearMagnitude)

        val halley = SmallBodyJSONParser.parse(CometsTestData.jsonHalley)
        assertEquals("1P/Halley", halley.title)
        val halleyMagnitudes = cometMagnitudes(halley)
        assertEquals(13.6, halleyMagnitudes.nuclearMagnitude)
        assertEquals(5.0, halleyMagnitudes.nuclearSlope)
        assertEquals(0.03, halleyMagnitudes.nuclearPhaseCoefficient)
        assertEquals(2_446_469.973_616_146_677, halley.orbit.perihelionJulianDayTT, 1e-9)

        val threeI = SmallBodyJSONParser.parse(CometsTestData.jsonAtlas3i)
        assertEquals("C/2025 N1", threeI.designation)
        assertEquals("ATLAS", threeI.name)
        assertEquals(6.141_351_449_317_625, threeI.orbit.eccentricity)
        assertEquals(12.5, cometMagnitudes(threeI).totalMagnitude)
    }

    @Test
    fun parsesUnnumberedAsteroidsFromTheSmallBodyDatabase() {
        val body = SmallBodyJSONParser.parse(CometsTestData.jsonYr42024)
        assertEquals("2024 YR4", body.designation)
        assertEquals("", body.name)
        assertNull(body.number)
        assertEquals("asteroid.2024 YR4", body.id)
        assertEquals(23.93, asteroidMagnitudes(body).absoluteMagnitude)
        assertEquals(0.15, asteroidMagnitudes(body).slopeParameter)
        val ceres = SmallBodyJSONParser.parse(CometsTestData.jsonCeres)
        assertEquals("(1) Ceres", ceres.title)
        assertEquals(0.12, asteroidMagnitudes(ceres).slopeParameter)
    }

    @Test
    fun smallBodyDatabaseAndMPCAgreeOnTheSameOrbit() {
        val json = SmallBodyJSONParser.parse(CometsTestData.jsonVesta)
        val mpc = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidVesta)
        assertEquals(json.orbit.perihelionDistanceAU, mpc.orbit.perihelionDistanceAU, 2e-6)
        assertEquals(json.orbit.eccentricity, mpc.orbit.eccentricity, 2e-7)
        assertEquals(json.orbit.inclination, mpc.orbit.inclination, 2e-5)
        assertEquals(json.orbit.perihelionJulianDayTT, mpc.orbit.perihelionJulianDayTT, 2e-3)
        assertEquals(json.id, mpc.id)
    }

    @Test
    fun jsonValuesMayBeNumbers() {
        val text = """
            {"object": {"fullname": "C/2030 A1 (Test)", "kind": "cu"},
             "orbit": {"epoch": 2460000.5, "equinox": "J2000", "elements": [
               {"name": "e", "value": 0.5}, {"name": "q", "value": 1}, {"name": "i", "value": 10},
               {"name": "om", "value": 20.5}, {"name": "w", "value": 3.0E+1}, {"name": "tp", "value": 2460100.25},
               {"name": "per", "value": null}]},
             "phys_par": [{"name": "M1", "value": 7.5}, {"name": "K1", "value": 12}]}
        """.trimIndent()
        val body = SmallBodyJSONParser.parse(text)
        assertEquals("C/2030 A1", body.designation)
        assertEquals("Test", body.name)
        assertEquals(1.0, body.orbit.perihelionDistanceAU)
        assertEquals(30.0, body.orbit.argumentOfPerihelion)
        assertEquals(2_460_100.25, body.orbit.perihelionJulianDayTT)
        assertEquals(2_460_000.5, body.orbit.epochJulianDayTT)
        assertEquals(7.5, cometMagnitudes(body).totalMagnitude)
        assertEquals(12.0, cometMagnitudes(body).totalSlope)
    }

    @Test
    fun jsonFallsBackToMeanAnomalyAndDefaults() {
        // No tp and no q: derived from the mean anomaly, the epoch and the semi-major axis; K1 defaults to 10.
        val text = """
            {"object": {"fullname": "433 Eros (A898 PA)", "kind": "an"},
             "orbit": {"epoch": "2461200.5", "elements": [
               {"name": "e", "value": "0.2228780"}, {"name": "a", "value": "1.4582437"}, {"name": "i", "value": "10.82855"},
               {"name": "om", "value": "304.26797"}, {"name": "w", "value": "178.91814"}, {"name": "ma", "value": "62.51145"}]},
             "phys_par": [{"name": "H", "value": "10.39"}]}
        """.trimIndent()
        val body = SmallBodyJSONParser.parse(text)
        assertEquals(433, body.number)
        assertEquals("Eros", body.name)
        assertEquals(1.4582437 * (1 - 0.222878), body.orbit.perihelionDistanceAU, 1e-12)
        assertEquals(62.51145, assertNotNull(body.orbit.meanAnomaly(2_461_200.5)), 1e-6)
        assertEquals(0.15, asteroidMagnitudes(body).slopeParameter)
        // A comet that has M1 but no K1 gets the classical slope of 10.
        val comet = text.replace("433 Eros (A898 PA)", "C/2030 A1").replace("\"an\"", "\"cu\"")
            .replace("{\"name\": \"H\", \"value\": \"10.39\"}", "{\"name\": \"M1\", \"value\": \"8.0\"}")
        assertEquals(10.0, cometMagnitudes(SmallBodyJSONParser.parse(comet)).totalSlope)
        // A comet that only has H uses the asteroid law, an asteroid that only has M1 the comet law.
        val cometWithH = text.replace("433 Eros (A898 PA)", "C/2030 A1").replace("\"an\"", "\"cu\"")
        assertEquals(10.39, asteroidMagnitudes(SmallBodyJSONParser.parse(cometWithH)).absoluteMagnitude)
        val asteroidWithM1 = text.replace("{\"name\": \"H\", \"value\": \"10.39\"}", "{\"name\": \"M1\", \"value\": \"8.0\"}")
        assertEquals(8.0, cometMagnitudes(SmallBodyJSONParser.parse(asteroidWithM1)).totalMagnitude)
        // Without any magnitude parameter there is no photometry.
        assertNull(SmallBodyJSONParser.parse(text.replace("[{\"name\": \"H\", \"value\": \"10.39\"}]", "[]")).photometry)
    }

    @Test
    fun asteroidFullNames() {
        assertEquals(AsteroidIdentity(4, "4", "Vesta"), SmallBodyNaming.asteroidFullName("4 Vesta (A807 FA)"))
        assertEquals(AsteroidIdentity(433, "433", "Eros"), SmallBodyNaming.asteroidFullName("433 Eros (A898 PA)"))
        assertEquals(AsteroidIdentity(44_594, "44594", "1999 OX3"), SmallBodyNaming.asteroidFullName("44594 (1999 OX3)"))
        assertEquals(AsteroidIdentity(7, "7", ""), SmallBodyNaming.asteroidFullName("7"))
        assertEquals(AsteroidIdentity(null, "2024 YR4", ""), SmallBodyNaming.asteroidFullName("(2024 YR4)"))
        assertEquals(AsteroidIdentity(null, "2024 YR4", ""), SmallBodyNaming.asteroidFullName("2024 YR4"))
        assertEquals(AsteroidIdentity(99_942, "99942", "Apophis"), SmallBodyNaming.asteroidFullName("99942 Apophis (2004 MN4)"))
        assertEquals(AsteroidIdentity(null, "(a) (b)", ""), SmallBodyNaming.asteroidFullName("(a) (b)"))
        assertEquals(AsteroidIdentity(null, "123456789 X", ""), SmallBodyNaming.asteroidFullName("123456789 X"))
        assertEquals(AsteroidIdentity(4, "4", "Vesta"), SmallBodyNaming.asteroidReadable("(4) Vesta"))
        assertEquals(AsteroidIdentity(202_411, "202411", "2005 RC"), SmallBodyNaming.asteroidReadable("(202411) 2005 RC"))
        assertEquals(AsteroidIdentity(null, "2024 YR4", ""), SmallBodyNaming.asteroidReadable("2024 YR4"))
        assertEquals(AsteroidIdentity(null, "(x) y", ""), SmallBodyNaming.asteroidReadable("(x) y"))
        assertEquals(AsteroidIdentity(null, "()", ""), SmallBodyNaming.asteroidReadable("()"))
    }

    @Test
    fun jsonErrorsAreTyped() {
        assertTrue(jsonError("") is SmallBodyParseError.InvalidJSON)
        assertTrue(jsonError("{") is SmallBodyParseError.InvalidJSON)
        assertTrue(jsonError("not json") is SmallBodyParseError.InvalidJSON)
        assertEquals(SmallBodyParseError.InvalidJSON("the top level is not an object"), jsonError("[]"))
        assertEquals(SmallBodyParseError.InvalidJSON("the top level is not an object"), jsonError("42"))
        assertEquals(
            SmallBodyParseError.UnsupportedObject("specified object was not found"),
            jsonError("""{"code": "200", "message": "specified object was not found"}"""),
        )
        assertEquals(SmallBodyParseError.UnsupportedObject("the answer contains no object"), jsonError("{}"))
        assertEquals(SmallBodyParseError.UnsupportedObject("the answer contains no orbit"), jsonError("""{"object": {"kind": "an"}}"""))
        val good = CometsTestData.jsonVesta
        assertEquals(SmallBodyParseError.UnsupportedObject("the object has no kind"), jsonError(good.replace("\"kind\": \"an\"", "\"kinds\": \"an\"")))
        assertEquals(
            SmallBodyParseError.UnsupportedObject("the object kind \"sn\" is not a comet or asteroid"),
            jsonError(good.replace("\"kind\": \"an\"", "\"kind\": \"sn\"")),
        )
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.DESIGNATION), jsonError(good.replace("\"fullname\": \"4 Vesta (A807 FA)\"", "\"fullname\": \"  \"")))
        assertEquals(
            SmallBodyParseError.UnsupportedObject("the elements refer to the equinox B1950"),
            jsonError(good.replace("\"equinox\": \"J2000\"", "\"equinox\": \"B1950\"")),
        )
        assertEquals(SmallBodyParseError.UnsupportedObject("the orbit has no elements"), jsonError(good.replace("\"elements\"", "\"elementz\"")))
        assertEquals(
            SmallBodyParseError.MalformedField(SmallBodyField.ECCENTRICITY, "abc"),
            jsonError(good.replace("\".09020374382834395\"", "\"abc\"")),
        )
        assertEquals(
            SmallBodyParseError.FieldOutOfRange(SmallBodyField.ECCENTRICITY, -0.5),
            jsonError(good.replace("\".09020374382834395\"", "\"-0.5\"")),
        )
        assertEquals(
            SmallBodyParseError.FieldOutOfRange(SmallBodyField.INCLINATION, 200.0),
            jsonError(good.replace("\"7.143925545058711\"", "\"200\"")),
        )
        assertEquals(
            SmallBodyParseError.MalformedField(SmallBodyField.ARGUMENT_OF_PERIHELION, "NaN"),
            jsonError(good.replace("\"151.4686478221564\"", "\"NaN\"")),
        )
        assertEquals(
            SmallBodyParseError.MalformedField(SmallBodyField.ABSOLUTE_MAGNITUDE, "bright"),
            jsonError(good.replace("\"value\": \"3.25\"", "\"value\": \"bright\"")),
        )
        assertEquals(
            SmallBodyParseError.FieldOutOfRange(SmallBodyField.SLOPE_PARAMETER, 3.0),
            jsonError(good.replace("\"value\": \"0.32\"", "\"value\": \"3\"")),
        )
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.ECCENTRICITY), jsonError(good.replace("\"name\": \"e\"", "\"name\": \"ee\"")))
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.INCLINATION), jsonError(good.replace("\"name\": \"i\"", "\"name\": \"ii\"")))
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.LONGITUDE_OF_ASCENDING_NODE), jsonError(good.replace("\"name\": \"om\"", "\"name\": \"oom\"")))
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.ARGUMENT_OF_PERIHELION), jsonError(good.replace("\"name\": \"w\"", "\"name\": \"ww\"")))
        // Neither tp nor a mean anomaly.
        val noTime = good.replace("\"name\": \"tp\"", "\"name\": \"tq\"").replace("\"name\": \"ma\"", "\"name\": \"mb\"")
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.PERIHELION_TIME), jsonError(noTime))
        // A mean anomaly but no epoch.
        val noEpoch = good.replace("\"name\": \"tp\"", "\"name\": \"tq\"").replace("\"epoch\": \"2461200.5\"", "\"epochs\": \"2461200.5\"")
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.EPOCH), jsonError(noEpoch))
        // No q and no a.
        val noShape = good.replace("\"name\": \"q\"", "\"name\": \"qq\"")
        assertEquals(2.148_361_914_524_259 * 0 + 2.361_365_965_127_599 * (1 - 0.090_203_743_828_343_95), SmallBodyJSONParser.parse(noShape).orbit.perihelionDistanceAU, 1e-12)
        assertEquals(
            SmallBodyParseError.MissingField(SmallBodyField.PERIHELION_DISTANCE),
            jsonError(noShape.replace("\"name\": \"a\"", "\"name\": \"aa\"")),
        )
        // The mean anomaly needs a semi-major axis or q and an elliptic orbit.
        val noAxis = noTime.replace("\"name\": \"mb\"", "\"name\": \"ma\"").replace("\"name\": \"a\"", "\"name\": \"aa\"").replace("\"name\": \"q\"", "\"name\": \"qq\"")
        assertEquals(SmallBodyParseError.MissingField(SmallBodyField.SEMI_MAJOR_AXIS), jsonError(noAxis))
    }

    @Test
    fun jsonLimitsNestingAndSize() {
        assertEquals(SmallBodyParseError.InvalidJSON("nested too deeply"), jsonError("[".repeat(33) + "]".repeat(33)))
        assertEquals(SmallBodyParseError.InvalidJSON("nested too deeply"), jsonError("{\"a\":".repeat(40) + "1" + "}".repeat(40)))
        // Brackets inside strings do not count.
        val inString = CometsTestData.jsonVesta.replace("\"signature\"", "\"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\": \"\\\"[[[[\", \"signature\"")
        assertEquals("Vesta", SmallBodyJSONParser.parse(inString).name)
        val error = jsonError("x".repeat(SmallBodyJSONParser.maximumInputLength + 1))
        assertEquals(SmallBodyParseError.InputTooLarge(SmallBodyJSONParser.maximumInputLength + 1, SmallBodyJSONParser.maximumInputLength), error)
    }

    @Test
    fun jsonNumericFieldsRejectOtherKinds() {
        val good = CometsTestData.jsonVesta
        assertEquals(
            SmallBodyParseError.MalformedField(SmallBodyField.ECCENTRICITY, "true"),
            jsonError(good.replace("\".09020374382834395\"", "true")),
        )
        assertEquals(
            SmallBodyParseError.MalformedField(SmallBodyField.ECCENTRICITY, "false"),
            jsonError(good.replace("\".09020374382834395\"", "false")),
        )
        assertEquals(
            SmallBodyParseError.MalformedField(SmallBodyField.ECCENTRICITY, "array"),
            jsonError(good.replace("\".09020374382834395\"", "[1]")),
        )
        assertEquals(
            SmallBodyParseError.MalformedField(SmallBodyField.ECCENTRICITY, "object"),
            jsonError(good.replace("\".09020374382834395\"", "{}")),
        )
        // A null value counts as absent.
        assertEquals(
            SmallBodyParseError.MissingField(SmallBodyField.ECCENTRICITY),
            jsonError(good.replace("\".09020374382834395\"", "null")),
        )
        // Surrounding blanks in a string value are ignored.
        val padded = SmallBodyJSONParser.parse(good.replace("\"2.148361914524259\"", "\"  2.148361914524259 \""))
        assertEquals(2.148_361_914_524_259, padded.orbit.perihelionDistanceAU)
        // Names may be escaped in the document.
        val escaped = SmallBodyJSONParser.parse(good.replace("\"fullname\": \"4 Vesta (A807 FA)\"", "\"fullname\": \"4 Ves\\u0074a (A807 FA)\""))
        assertEquals("Vesta", escaped.name)
        // A repeated element name takes the last entry.
        val repeated = good.replace("{\"label\": \"e\", \"value\": \".09020374382834395\"", "{\"name\": \"e\", \"value\": \"0.5\"}, {\"label\": \"e\", \"value\": \".09020374382834395\"")
        assertEquals(0.090_203_743_828_343_95, SmallBodyJSONParser.parse(repeated).orbit.eccentricity)
    }

    @Test
    fun errorsDescribeThemselves() {
        assertEquals("The inclination 181.0 is out of range.", SmallBodyParseError.FieldOutOfRange(SmallBodyField.INCLINATION, 181.0).description)
        assertEquals("The eccentricity \"abc\" is not valid.", SmallBodyParseError.MalformedField(SmallBodyField.ECCENTRICITY, "abc").description)
        assertEquals("The epoch is missing.", SmallBodyParseError.MissingField(SmallBodyField.EPOCH).description)
        assertEquals("The line has 5 characters; at least 79 are required.", SmallBodyParseError.LineTooShort(5, 79).description)
        assertEquals("The line has 500 characters; at most 400 are allowed.", SmallBodyParseError.LineTooLong(500, 400).description)
        assertEquals("The designation \"x\" is not valid.", SmallBodyParseError.InvalidDesignation("x").description)
        assertEquals("The object comet.1P occurs more than once.", SmallBodyParseError.DuplicateObject("comet.1P").description)
        assertEquals("The JSON is not valid: oops.", SmallBodyParseError.InvalidJSON("oops").description)
        assertEquals("The object cannot be used: why.", SmallBodyParseError.UnsupportedObject("why").description)
        assertEquals("The orbital elements are inconsistent: because.", SmallBodyParseError.InconsistentElements("because").description)
        assertEquals("The input has 9 characters; at most 5 are accepted.", SmallBodyParseError.InputTooLarge(9, 5).description)
        assertEquals("comet", SmallBodyKind.COMET.rawValue)
        assertEquals(SmallBodyKind.ASTEROID, SmallBodyKind.fromRawValue("asteroid"))
        assertNull(SmallBodyKind.fromRawValue("planet"))
        assertEquals("Comet", SmallBodyKind.COMET.displayName)
        assertEquals("Asteroid", SmallBodyKind.ASTEROID.displayName)
        assertEquals(OrbitShape.HYPERBOLIC, OrbitShape.fromRawValue("hyperbolic"))
        assertNull(OrbitShape.fromRawValue("circular"))
        for (field in SmallBodyField.entries) {
            assertEquals(field, SmallBodyField.fromRawValue(field.rawValue))
            assertTrue(field.displayName.isNotEmpty())
        }
        assertNull(SmallBodyField.fromRawValue("nothing"))
    }
}
