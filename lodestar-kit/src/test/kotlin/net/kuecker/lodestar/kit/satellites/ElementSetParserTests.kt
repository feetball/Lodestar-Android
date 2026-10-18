package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.fixtureBytes
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.fixtureText
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.issEpochUnixSeconds
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.issLine1
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.issLine2
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.withChecksum
import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElementSetParserTests {
    // MARK: Two-line sets

    @Test
    fun testParsesTheIssTwoLineSet() {
        val elements = ElementSetParser.parseTwoLine(issLine1, issLine2, "ISS (ZARYA)")
        assertEquals("ISS (ZARYA)", elements.name)
        assertEquals("1998-067A", elements.objectId)
        assertEquals(25544, elements.catalogNumber)
        assertEquals('U', elements.classification)
        assertEquals(issEpochUnixSeconds, elements.epoch.timeIntervalSince1970, 1e-5)
        assertEquals(15.48918166, elements.meanMotion, 1e-12)
        assertEquals(0.0006780, elements.eccentricity, 1e-12)
        assertEquals(51.6328, elements.inclination, 1e-12)
        assertEquals(51.6259, elements.rightAscensionOfAscendingNode, 1e-12)
        assertEquals(277.7856, elements.argumentOfPericenter, 1e-12)
        assertEquals(82.2363, elements.meanAnomaly, 1e-12)
        assertEquals(0, elements.ephemerisType)
        assertEquals(999, elements.elementSetNumber)
        assertEquals(59081, elements.revolutionNumberAtEpoch)
        assertEquals(0.14456e-3, elements.bstar, 1e-15)
        assertEquals(7.503e-5, elements.meanMotionDot, 1e-15)
        assertEquals(0.0, elements.meanMotionDdot, 0.0)
        assertEquals(1440.0 / 15.48918166, elements.periodMinutes, 1e-9)
    }

    @Test
    fun testTheSgp4EpochIsComputedLikeTheReference() {
        // 00005 of the SGP4 verification file: epoch day 179.78495062 of 2000, i.e. 2000-06-27T18:50:19.7335680Z,
        // which is 18441.78495062 days after 1949-12-31T00:00Z.
        val elements = ElementSetParser.parseTwoLine(
            "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753",
            "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667",
        )
        assertEquals(5, elements.catalogNumber)
        assertEquals("1958-002B", elements.objectId)
        assertEquals(18441.78495062, elements.sgp4EpochDays, 1e-9)
        assertEquals(962131819.733568, elements.epoch.timeIntervalSince1970, 1e-6)
    }

    @Test
    fun testChecksumCountsDigitsAndMinusSigns() {
        assertEquals(4, ElementSetParser.checksum(issLine1))
        assertEquals(4, ElementSetParser.checksum(issLine2))
        assertEquals(7, ElementSetParser.checksum("0123-"))
        assertEquals(6, ElementSetParser.checksum("123"))
        assertEquals(1, ElementSetParser.checksum("a-b+c.d e"))
        // Only the first 68 characters count.
        assertEquals(ElementSetParser.checksum(issLine1), ElementSetParser.checksum(issLine1.substring(0, 68) + "9999"))
    }

    @Test
    fun testRejectsLinesOfTheWrongLength() {
        assertEquals(ElementSetError.InvalidLineLength(1, 68), assertFailsWith<ElementSetError> {
            ElementSetParser.parseTwoLine(issLine1.substring(0, 68), issLine2)
        })
        assertEquals(ElementSetError.InvalidLineLength(2, 70), assertFailsWith<ElementSetError> {
            ElementSetParser.parseTwoLine(issLine1, issLine2 + "1")
        })
        // Trailing white space and line ends are ignored.
        assertEquals(25544, ElementSetParser.parseTwoLine("$issLine1  \r\n", "$issLine2\t\n").catalogNumber)
    }

    @Test
    fun testRejectsSwappedAndMislabelledLines() {
        assertEquals(ElementSetError.UnexpectedLineNumber(1), assertFailsWith<ElementSetError> {
            ElementSetParser.parseTwoLine(issLine2, issLine1)
        })
        assertEquals(ElementSetError.UnexpectedLineNumber(2), assertFailsWith<ElementSetError> {
            ElementSetParser.parseTwoLine(issLine1, issLine1)
        })
    }

    @Test
    fun testRejectsAWrongChecksum() {
        val corrupted = issLine1.replace(".00007503", ".00007603")
        val error = assertFailsWith<ElementSetError.ChecksumMismatch> { ElementSetParser.parseTwoLine(corrupted, issLine2) }
        assertEquals(ElementSetError.ChecksumMismatch(1, 5, 4), error)
        val last = assertFailsWith<ElementSetError.ChecksumMismatch> {
            ElementSetParser.parseTwoLine(issLine1, issLine2.substring(0, 68) + "5")
        }
        assertEquals(ElementSetError.ChecksumMismatch(2, 4, 5), last)
        assertTrue(error.message.orEmpty().contains("checksum"))
        // A letter in place of the checksum digit.
        assertEquals("checksum", assertFailsWith<ElementSetError.InvalidField> {
            ElementSetParser.parseTwoLine(issLine1.substring(0, 68) + "X", issLine2)
        }.field)
    }

    @Test
    fun testRejectsDifferentObjectsOnTheTwoLines() {
        val other = withChecksum(issLine2.replaceRange(2, 7, "25545"))
        assertEquals(ElementSetError.CatalogNumberMismatch(25544, 25545), assertFailsWith<ElementSetError> {
            ElementSetParser.parseTwoLine(issLine1, other)
        })
    }

    @Test
    fun testRejectsFieldsThatAreNotNumbers() {
        val inclination = withChecksum(issLine2.replaceRange(8, 16, " 5x.6328"))
        assertEquals("INCLINATION", assertFailsWith<ElementSetError.InvalidField> {
            ElementSetParser.parseTwoLine(issLine1, inclination)
        }.field)
        val eccentricity = withChecksum(issLine2.replaceRange(26, 33, "00067a0"))
        assertEquals("ECCENTRICITY", assertFailsWith<ElementSetError.InvalidField> {
            ElementSetParser.parseTwoLine(issLine1, eccentricity)
        }.field)
        val drag = withChecksum(issLine1.replaceRange(53, 61, " 1x456-3"))
        assertEquals("BSTAR", assertFailsWith<ElementSetError.InvalidField> {
            ElementSetParser.parseTwoLine(drag, issLine2)
        }.field)
        val epoch = withChecksum(issLine1.replaceRange(18, 20, "2x"))
        assertEquals("EPOCH", assertFailsWith<ElementSetError.InvalidField> {
            ElementSetParser.parseTwoLine(epoch, issLine2)
        }.field)
        val separator = withChecksum(issLine1.replaceRange(8, 9, "x"))
        assertEquals("separator", assertFailsWith<ElementSetError.InvalidField> {
            ElementSetParser.parseTwoLine(separator, issLine2)
        }.field)
        val number = withChecksum(issLine1.replaceRange(2, 7, "2554 "))
        assertEquals("NORAD_CAT_ID", assertFailsWith<ElementSetError.InvalidField> {
            ElementSetParser.parseTwoLine(number, withChecksum(issLine2.replaceRange(2, 7, "2554 ")))
        }.field)
    }

    @Test
    fun testRejectsValuesOutOfRange() {
        fun range(line1: String, line2: String): ElementSetError.ValueOutOfRange =
            assertFailsWith<ElementSetError.ValueOutOfRange> {
                ElementSetParser.parseTwoLine(withChecksum(line1), withChecksum(line2))
            }
        assertEquals("INCLINATION", range(issLine1, issLine2.replaceRange(8, 16, "181.0000")).field)
        assertEquals("RA_OF_ASC_NODE", range(issLine1, issLine2.replaceRange(17, 25, "360.5000")).field)
        assertEquals("MEAN_ANOMALY", range(issLine1, issLine2.replaceRange(43, 51, "400.0000")).field)
        assertEquals("MEAN_MOTION", range(issLine1, issLine2.replaceRange(52, 63, " 0.00000000")).field)
        assertEquals("MEAN_MOTION", range(issLine1, issLine2.replaceRange(52, 63, "100.00000000")).field)
        // Day 400 does not exist; 366 does not exist in 2026, but day 366.5 of 2024 does.
        assertEquals("EPOCH", range(issLine1.replaceRange(20, 32, "400.14461894"), issLine2).field)
        assertEquals("EPOCH", range(issLine1.replaceRange(20, 32, "366.50000000"), issLine2).field)
        assertEquals("EPOCH", range(issLine1.replaceRange(20, 32, "  0.50000000"), issLine2).field)
        val leap = ElementSetParser.parseTwoLine(withChecksum(issLine1.replaceRange(18, 32, "24366.50000000")), issLine2)
        val leapDate = ElementCalendar.civilFromDays(Math.floorDiv(leap.epoch.epochSecond, 86_400L))
        assertEquals(listOf(2024, 12, 31), listOf(leapDate.year, leapDate.month, leapDate.day))
        assertEquals(43_200, Math.floorMod(leap.epoch.epochSecond, 86_400L))
    }

    @Test
    fun testAlpha5CatalogNumbers() {
        fun parsed(code: String): Int {
            val first = withChecksum(issLine1.replaceRange(2, 7, code))
            val second = withChecksum(issLine2.replaceRange(2, 7, code))
            return ElementSetParser.parseTwoLine(first, second).catalogNumber
        }
        assertEquals(100_000, parsed("A0000"))
        assertEquals(100_001, parsed("A0001"))
        assertEquals(180_000, parsed("J0000")) // I is skipped: H = 17, J = 18
        assertEquals(221_234, parsed("N1234")) // N = 22
        assertEquals(230_000, parsed("P0000")) // O is skipped: P = 23
        assertEquals(339_999, parsed("Z9999"))
        assertEquals(99_999, parsed("99999"))
        for (invalid in listOf("I0000", "O0000", "A00x0", "a0000", "AB000")) {
            assertEquals("NORAD_CAT_ID", assertFailsWith<ElementSetError.InvalidField> { parsed(invalid) }.field, invalid)
        }
    }

    @Test
    fun testBlankOptionalFieldsAreAccepted() {
        // SGP4-VER.TLE 88888 (Original STR#3 SGP4 test): no designator, no revolution number.
        val elements = ElementSetParser.parseTwoLine(
            "1 88888U          80275.98708465  .00073094  13844-3  66816-4 0    87",
            "2 88888  72.8435 115.9689 0086731  52.6988 110.5714 16.05824518  1058",
        )
        assertNull(elements.objectId)
        assertNull(elements.name)
        assertEquals(88888, elements.catalogNumber)
        assertEquals(8, elements.elementSetNumber)
        assertEquals(105, elements.revolutionNumberAtEpoch)
        assertEquals(0.13844e-3, elements.meanMotionDdot, 1e-15)
        assertEquals(0.66816e-4, elements.bstar, 1e-15)
        assertEquals(1980, ElementCalendar.civilFromDays(Math.floorDiv(elements.epoch.epochSecond, 86_400L)).year)
    }

    @Test
    fun testNamesAreCleaned() {
        assertEquals("ISS (ZARYA)", ElementSetParser.parseTwoLine(issLine1, issLine2, "0 ISS (ZARYA)   ").name)
        assertEquals("A B", ElementSetParser.parseTwoLine(issLine1, issLine2, " \u0001A\u0007 B\n").name)
        assertNull(ElementSetParser.parseTwoLine(issLine1, issLine2, "   ").name)
        assertEquals(80, ElementSetParser.parseTwoLine(issLine1, issLine2, "X".repeat(200)).name.orEmpty().length)
    }

    // MARK: Batches of two-line sets

    @Test
    fun testParsesTheStationsListInThreeLineFormat() {
        val batch = ElementSetParser.parseTwoLineText(fixtureText("satellite_stations.tle"))
        assertEquals(0, batch.skippedCount)
        assertEquals(17, batch.elements.size) // four of the 21 stations have catalog numbers above 99999
        assertEquals("ISS (ZARYA)", batch.elements[0].name)
        assertEquals(25544, batch.elements[0].catalogNumber)
        assertEquals("POISK", batch.elements[1].name)
        assertEquals("CSS (TIANHE)", batch.elements[2].name)
        assertTrue(batch.elements.all { it.name != null })
    }

    @Test
    fun testTwoLineFormatWithoutNamesAndWithWindowsLineEnds() {
        val text = "$issLine1\r\n$issLine2\r\n\r\n$issLine1\r\n$issLine2\r\n"
        val batch = ElementSetParser.parseTwoLineText(text)
        assertEquals(2, batch.elements.size)
        assertNull(batch.elements[0].name)
        assertEquals(0, batch.skippedCount)
        assertTrue(ElementSetParser.parseTwoLineText("").elements.isEmpty())
        assertTrue(ElementSetParser.parseTwoLineText("\n\n  \nnothing here\n").elements.isEmpty())
    }

    @Test
    fun testBadRecordsAreSkippedAndReported() {
        val corrupted = issLine1.replace(".00007503", ".00007603")
        val text = buildString {
            append("GOOD ONE\n$issLine1\n$issLine2\n")
            append("BAD CHECKSUM\n$corrupted\n$issLine2\n")
            append("ORPHAN SECOND LINE\n$issLine2\n")
            append("ORPHAN FIRST LINE\n$issLine1\n")
            append("0 SPACE-TRACK STYLE\n$issLine1\n$issLine2\n")
        }
        val batch = ElementSetParser.parseTwoLineText(text)
        assertEquals(listOf("GOOD ONE", "SPACE-TRACK STYLE"), batch.elements.map { it.name })
        assertEquals(3, batch.skippedCount)
        assertEquals(listOf(1, 2, 3), batch.skipped.map { it.index })
        assertEquals(ElementSetError.ChecksumMismatch(1, 5, 4), batch.skipped[0].error)
        assertEquals("BAD CHECKSUM", batch.skipped[0].name)
        assertEquals(ElementSetError.UnexpectedLineNumber(1), batch.skipped[1].error)
        assertEquals(ElementSetError.UnexpectedLineNumber(2), batch.skipped[2].error)
    }

    @Test
    fun testSkippedRecordsAreCappedInTheReport() {
        val bad = issLine1.replace(".00007503", ".00007603")
        val text = "$bad\n$issLine2\n".repeat(ElementSetParser.maximumReportedSkips + 20)
        val batch = ElementSetParser.parseTwoLineText(text)
        assertEquals(ElementSetParser.maximumReportedSkips + 20, batch.skippedCount)
        assertEquals(ElementSetParser.maximumReportedSkips, batch.skipped.size)
    }

    @Test
    fun testRefusesAnInputThatIsTooLarge() {
        val huge = " ".repeat(ElementSetParser.maximumInputCharacters + 1)
        assertEquals(
            ElementSetError.InputTooLarge(ElementSetParser.maximumInputCharacters),
            assertFailsWith<ElementSetError> { ElementSetParser.parseTwoLineText(huge) },
        )
        assertEquals(
            ElementSetError.InputTooLarge(ElementSetParser.maximumInputCharacters),
            assertFailsWith<ElementSetError> { ElementSetParser.parseJson(huge) },
        )
    }

    // MARK: OMM JSON

    @Test
    fun testParsesTheStationsListInJson() {
        val batch = ElementSetParser.parseJson(fixtureBytes("satellite_stations.json"))
        assertEquals(0, batch.skippedCount)
        assertEquals(21, batch.elements.size)
        val iss = batch.elements[0]
        assertEquals("ISS (ZARYA)", iss.name)
        assertEquals("1998-067A", iss.objectId)
        assertEquals(25544, iss.catalogNumber)
        assertEquals(issEpochUnixSeconds, iss.epoch.timeIntervalSince1970, 1e-5)
        assertEquals(15.48918166, iss.meanMotion, 1e-12)
        assertEquals(0.00067803, iss.eccentricity, 1e-12)
        assertEquals(0.00014456442, iss.bstar, 1e-15)
        assertEquals(7.503e-5, iss.meanMotionDot, 1e-15)
        assertEquals(59081, iss.revolutionNumberAtEpoch)
        assertEquals(listOf(100057, 100712, 100882, 101031), batch.elements.map { it.catalogNumber }.filter { it > 99_999 })
        assertEquals(batch.elements.size, batch.latestPerObject().elements.size)
    }

    @Test
    fun testJsonAndTwoLineSetsDescribeTheSameOrbits() {
        val json = ElementSetParser.parseJson(fixtureBytes("satellite_stations.json")).elements.associateBy { it.catalogNumber }
        val lines = ElementSetParser.parseTwoLineText(fixtureText("satellite_stations.tle")).elements
        assertEquals(17, lines.size)
        for (fromLines in lines) {
            val fromJson = assertNotNull(json[fromLines.catalogNumber])
            assertEquals(fromJson.epoch.timeIntervalSince1970, fromLines.epoch.timeIntervalSince1970, 1e-3, "epoch of ${fromLines.catalogNumber}")
            assertEquals(fromJson.meanMotion, fromLines.meanMotion, 1e-8)
            assertEquals(fromJson.eccentricity, fromLines.eccentricity, 1e-7)
            assertEquals(fromJson.inclination, fromLines.inclination, 1e-4)
            assertEquals(fromJson.rightAscensionOfAscendingNode, fromLines.rightAscensionOfAscendingNode, 1e-4)
            assertEquals(fromJson.argumentOfPericenter, fromLines.argumentOfPericenter, 1e-4)
            assertEquals(fromJson.meanAnomaly, fromLines.meanAnomaly, 1e-4)
            assertEquals(fromJson.bstar, fromLines.bstar, abs(fromJson.bstar) * 1e-4 + 1e-9) // five significant digits
            assertEquals(fromJson.objectId, fromLines.objectId)
            assertEquals(fromJson.name, fromLines.name)
        }
    }

    private val sampleRecord = """
        {"OBJECT_NAME":"TEST SAT","OBJECT_ID":"2020-001A","EPOCH":"2026-10-18T03:28:15.076416","MEAN_MOTION":15.5,
        "ECCENTRICITY":0.001,"INCLINATION":51.6,"RA_OF_ASC_NODE":10,"ARG_OF_PERICENTER":20,"MEAN_ANOMALY":30,
        "EPHEMERIS_TYPE":0,"CLASSIFICATION_TYPE":"U","NORAD_CAT_ID":12345,"ELEMENT_SET_NO":7,"REV_AT_EPOCH":99,
        "BSTAR":0.0001,"MEAN_MOTION_DOT":0.00001,"MEAN_MOTION_DDOT":0}
    """.trimIndent().replace("\n", "")

    private fun record(replacing: Map<String, String?> = emptyMap()): String {
        var text = sampleRecord
        for ((key, value) in replacing) {
            val pattern = Regex("\"$key\":(\"[^\"]*\"|[^,}]+)")
            text = if (value == null) {
                text.replace(pattern, "\"X_$key\":0")
            } else {
                text.replace(pattern, "\"$key\":$value")
            }
        }
        return text
    }

    @Test
    fun testJsonRecordWithEverythingAndOptionalFieldsOmitted() {
        val full = ElementSetParser.parseJson("[${record()}]").elements.single()
        assertEquals("TEST SAT", full.name)
        assertEquals(12345, full.catalogNumber)
        assertEquals(7, full.elementSetNumber)
        assertEquals(99, full.revolutionNumberAtEpoch)
        assertEquals(10.0, full.rightAscensionOfAscendingNode, 0.0)

        val minimal = """{"EPOCH":"2026-10-18T03:28:15","MEAN_MOTION":15.5,"ECCENTRICITY":0.001,"INCLINATION":51.6,
            "RA_OF_ASC_NODE":10,"ARG_OF_PERICENTER":20,"MEAN_ANOMALY":30,"NORAD_CAT_ID":12345,"BSTAR":0.0001}"""
        val element = ElementSetParser.parseJson(minimal).elements.single()
        assertNull(element.name)
        assertNull(element.objectId)
        assertEquals('U', element.classification)
        assertEquals(0, element.elementSetNumber)
        assertEquals(0.0, element.meanMotionDot, 0.0)
        assertEquals(1792294095.0, element.epoch.timeIntervalSince1970, 1e-6)
        assertEquals("TEST SAT", ElementSetParser.parseJson(record()).elements.single().name) // a single object, not an array
    }

    @Test
    fun testJsonNumbersMayBeStrings() {
        val text = record(
            mapOf(
                "MEAN_MOTION" to "\"15.5\"", "ECCENTRICITY" to "\"1e-3\"", "NORAD_CAT_ID" to "\"12345\"",
                "BSTAR" to "\"-0.5e-4\"",
            ),
        )
        val element = ElementSetParser.parseJson("[$text]").elements.single()
        assertEquals(15.5, element.meanMotion, 0.0)
        assertEquals(0.001, element.eccentricity, 0.0)
        assertEquals(12345, element.catalogNumber)
        assertEquals(-0.5e-4, element.bstar, 0.0)
        // Strings that are not JSON numbers are refused.
        for (bad in listOf("\"15,5\"", "\"0x10\"", "\"NaN\"", "\"\"", "\"+15\"", "\".5\"", "\"1.\"", "\"015\"")) {
            val batch = ElementSetParser.parseJson("[${record(mapOf("MEAN_MOTION" to bad))}]")
            assertEquals(1, batch.skippedCount, bad)
            assertEquals("MEAN_MOTION", (batch.skipped[0].error as ElementSetError.InvalidField).field, bad)
        }
    }

    @Test
    fun testJsonRecordsWithProblemsAreSkipped() {
        val records = listOf(
            record(), // 0 good
            record(mapOf("EPOCH" to null)), // 1 missing EPOCH
            record(mapOf("EPOCH" to "\"2026-13-18T03:28:15\"")), // 2 month 13
            record(mapOf("EPOCH" to "\"yesterday\"")), // 3
            record(mapOf("MEAN_MOTION" to "-1")), // 4 out of range
            record(mapOf("INCLINATION" to "true")), // 5 wrong type
            record(mapOf("NORAD_CAT_ID" to "12.5")), // 6 not an integer
            record(mapOf("ECCENTRICITY" to "1")), // 7 eccentricity 1
            record(mapOf("CLASSIFICATION_TYPE" to "\"UU\"")), // 8
            "42", // 9 not an object
            "null", // 10
            record(mapOf("BSTAR" to "[1]")), // 11 array
            record(mapOf("OBJECT_NAME" to "7")), // 12 name is not a string
            record(mapOf("MEAN_ANOMALY" to "1e999")), // 13 not finite
            record(mapOf("NORAD_CAT_ID" to "null")), // 14 null counts as missing
            record(), // 15 good
        )
        val batch = ElementSetParser.parseJson("[${records.joinToString(",")}]")
        assertEquals(2, batch.elements.size)
        assertEquals(14, batch.skippedCount)
        assertEquals((1..14).toList(), batch.skipped.map { it.index })
        assertEquals(ElementSetError.MissingField("EPOCH"), batch.skipped[0].error)
        assertEquals(ElementSetError.ValueOutOfRange("EPOCH", 13.0), batch.skipped[1].error)
        assertEquals(ElementSetError.InvalidField("EPOCH", "yesterday"), batch.skipped[2].error)
        assertEquals(ElementSetError.ValueOutOfRange("MEAN_MOTION", -1.0), batch.skipped[3].error)
        assertEquals(ElementSetError.InvalidField("INCLINATION", "true"), batch.skipped[4].error)
        assertEquals(ElementSetError.ValueOutOfRange("NORAD_CAT_ID", 12.5), batch.skipped[5].error)
        assertEquals(ElementSetError.ValueOutOfRange("ECCENTRICITY", 1.0), batch.skipped[6].error)
        assertEquals(ElementSetError.InvalidField("CLASSIFICATION_TYPE", "UU"), batch.skipped[7].error)
        assertTrue(batch.skipped[8].error is ElementSetError.InvalidRecord)
        assertTrue(batch.skipped[9].error is ElementSetError.InvalidRecord)
        assertEquals("BSTAR", (batch.skipped[10].error as ElementSetError.InvalidField).field)
        assertTrue(batch.skipped[11].error is ElementSetError.InvalidField)
        assertEquals("MEAN_ANOMALY", (batch.skipped[12].error as ElementSetError.InvalidField).field)
        assertEquals(ElementSetError.MissingField("NORAD_CAT_ID"), batch.skipped[13].error)
    }

    @Test
    fun testJsonEpochSpellings() {
        fun epoch(text: String): Double =
            ElementSetParser.parseJson("[${record(mapOf("EPOCH" to "\"$text\""))}]").elements.single().epoch.timeIntervalSince1970
        val base = 1792294095.0
        assertEquals(base, epoch("2026-10-18T03:28:15"), 1e-6)
        assertEquals(base, epoch("2026-10-18T03:28:15Z"), 1e-6)
        assertEquals(base + 0.5, epoch("2026-10-18T03:28:15.5"), 1e-6)
        assertEquals(base + 0.076416, epoch("2026-10-18 03:28:15.076416+00:00"), 1e-6)
        assertEquals(base + 0.123456789, epoch("2026-10-18T03:28:15.123456789"), 1e-6)
        for (bad in listOf("2026-10-18", "2026-10-18T03:28", "2026-10-18T03:28:15.", "2026-10-18T03:28:15+02:00", "2026-02-30T00:00:00", "2026-10-18T24:00:00", "2026-10-18T03:28:60")) {
            val batch = ElementSetParser.parseJson("[${record(mapOf("EPOCH" to "\"$bad\""))}]")
            assertEquals(1, batch.skippedCount, bad)
        }
    }

    @Test
    fun testMalformedJsonIsAnError() {
        for (text in listOf("", "   ", "[{", "[1,2", "\"text\"", "42", "null", "{\"a\":", "[{\"EPOCH\":}]")) {
            assertTrue(assertFailsWith<ElementSetError> { ElementSetParser.parseJson(text) } is ElementSetError.MalformedJson, text)
        }
        assertEquals(0, ElementSetParser.parseJson("[]").elements.size)
        assertEquals(0, ElementSetParser.parseJson("[]").skippedCount)
        assertEquals(1, ElementSetParser.parseJson("{}").skippedCount)
    }

    @Test
    fun testLatestPerObjectKeepsTheNewestEpoch() {
        val older = record(mapOf("EPOCH" to "\"2026-10-17T03:28:15\""))
        val newer = record(mapOf("EPOCH" to "\"2026-10-19T03:28:15\"", "MEAN_ANOMALY" to "99"))
        val other = record(mapOf("NORAD_CAT_ID" to "777"))
        val batch = ElementSetParser.parseJson("[$older,$other,$newer,$older]").latestPerObject()
        assertEquals(listOf(12345, 777), batch.elements.map { it.catalogNumber })
        assertEquals(99.0, batch.elements[0].meanAnomaly, 0.0)
    }

    @Test
    fun testGroupMetadata() {
        assertEquals("GROUP=stations&FORMAT=json", ElementSetGroup.STATIONS.query)
        assertEquals(ElementSetGroup.STARLINK, ElementSetGroup.fromRawValue("starlink"))
        assertNull(ElementSetGroup.fromRawValue("unknown"))
        val iss = SatelliteTestSupport.iss()
        assertTrue(ElementSetGroup.STATIONS.isFresh(iss, SatelliteTestSupport.date(issEpochUnixSeconds + 2 * 86_400.0)))
        assertTrue(!ElementSetGroup.STATIONS.isFresh(iss, SatelliteTestSupport.date(issEpochUnixSeconds + 4 * 86_400.0)))
        assertEquals(2.0, abs(iss.ageDays(SatelliteTestSupport.date(issEpochUnixSeconds + 2 * 86_400.0))), 1e-9)
        assertEquals(-1.0, iss.ageDays(SatelliteTestSupport.date(issEpochUnixSeconds - 86_400.0)), 1e-9)
    }
}
