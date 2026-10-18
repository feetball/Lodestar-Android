package net.kuecker.lodestar.kit.catalog

import java.util.Base64
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests of the JSON reader that replaces Swift's `JSONDecoder` (Kotlin only; the Swift suite reaches the decoder only
 * through whole catalogs). The reader has to accept what `JSONDecoder` accepts, reject what it rejects, and read
 * numbers exactly.
 */
class CatalogJsonReaderTests {
    private fun reader(text: String): CatalogJsonReader = CatalogJsonReader(text.toByteArray(Charsets.UTF_8))

    private fun stars(text: String): DecodedFile<StarRecord> = reader(text).readFile("stars", StarRecord::read)

    private fun star(members: String): StarRecord? = stars("""{"schema":1,"stars":[{$members}]}""").records.single()

    private fun isValidDocument(document: ByteArray): Boolean {
        val wrapped = "{\"schema\":1,\"stars\":[],\"x\":".toByteArray(Charsets.UTF_8) + document + "}".toByteArray(Charsets.UTF_8)
        return try {
            CatalogJsonReader(wrapped).readFile("stars", StarRecord::read)
            true
        } catch (error: CatalogJsonException) {
            false
        }
    }

    // MARK: Grammar

    @Test
    fun testTheConformanceCorpusAgreesWithAStrictParser() {
        val stream = assertNotNull(CatalogJsonReaderTests::class.java.getResourceAsStream("/fixtures/catalog_json_cases.txt"))
        val lines = stream.use { String(it.readBytes(), Charsets.UTF_8) }.lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(lines.size > 600)
        var valid = 0
        for ((index, line) in lines.withIndex()) {
            val expected = line[0] == '1'
            val document = Base64.getDecoder().decode(line.substring(2))
            assertEquals(expected, isValidDocument(document), "case $index: ${String(document, Charsets.ISO_8859_1).take(80)}")
            if (expected) valid += 1
        }
        assertTrue(valid > 200 && valid < lines.size - 200, "the corpus has valid and invalid cases")
    }

    @Test
    fun testOnlyStandardJsonIsAccepted() {
        for (valid in listOf(
            "{}", "[]", "0", "-0", "1.5e+3", "\"\"", "true", "false", "null", " \t\r\n[ 1 , 2 ]\n", "{\"a\" : { \"b\" : [ ] } }",
            "\"\\u00e9\\ud83d\\ude00\\n\\/\"", "[1E5,1e-5,0.0,-1.25]",
        )) {
            assertTrue(isValidDocument(valid.toByteArray(Charsets.UTF_8)), valid)
        }
        for (invalid in listOf(
            "", "[", "]", "{", "}", "[1,]", "{\"a\":1,}", "[,1]", "{,}", "{\"a\"}", "{\"a\":}", "{a:1}", "{'a':1}", "['a']",
            "NaN", "Infinity", "-Infinity", "+1", "01", "-01", "00", ".5", "1.", "1e", "1e+", "-", "--1", "0x10", "1_000",
            "tru", "truee", "nul", "True", "NULL", "\"abc", "\"\\x\"", "\"\\u12\"", "\"\\u12G4\"", "\"\n\"", "\"\t\"",
            "\"\u0001\"", "1 2", "[1 2]", "{\"a\":1 \"b\":2}", "// comment\n1", "/* c */1", "1;", "[1]]", "{}}", "\u00a01",
        )) {
            assertTrue(!isValidDocument(invalid.toByteArray(Charsets.UTF_8)), invalid)
        }
    }

    @Test
    fun testStringsMustBeWellFormedUtf8AndEscapes() {
        assertTrue(isValidDocument("\"é日本語🔭\"".toByteArray(Charsets.UTF_8)))
        for (bytes in listOf(
            byteArrayOf(0x22, 0xC3.toByte(), 0x28, 0x22), // C3 28: a lead byte followed by an ASCII byte
            byteArrayOf(0x22, 0xC0.toByte(), 0x80.toByte(), 0x22), // overlong NUL
            byteArrayOf(0x22, 0xE0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x22), // overlong
            byteArrayOf(0x22, 0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x22), // U+D800 as UTF-8
            byteArrayOf(0x22, 0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte(), 0x22), // above U+10FFFF
            byteArrayOf(0x22, 0xFF.toByte(), 0x22),
            byteArrayOf(0x22, 0xE2.toByte(), 0x82.toByte(), 0x22), // truncated
        )) {
            assertTrue(!isValidDocument(bytes), bytes.joinToString(" ") { "%02X".format(it) })
        }
        // A lone surrogate escape is refused, a pair is one scalar.
        for (invalid in listOf("\"\\ud800\"", "\"\\udc00\"", "\"\\ud800x\"", "\"\\ud800\\u0041\"", "\"\\udc00\\ud800\"")) {
            assertTrue(!isValidDocument(invalid.toByteArray(Charsets.UTF_8)), invalid)
        }
        val record = assertNotNull(star(""""n":"a\u00e9\ud83d\ude00\n\t\"\\\/\b\f\r\u0000z""""))
        assertEquals("aé😀\n\t\"\\/\b\u000C\r\u0000z", record.n)
    }

    @Test
    fun testContainersMayBeNested512LevelsDeep() {
        fun nested(levels: Int): ByteArray = ("[".repeat(levels) + "]".repeat(levels)).toByteArray(Charsets.UTF_8)
        // The document itself is wrapped in one object.
        assertTrue(isValidDocument(nested(511)))
        assertTrue(!isValidDocument(nested(512)))
        assertTrue(!isValidDocument(nested(100_000)))
        val objects = { levels: Int -> ("{\"a\":".repeat(levels) + "1" + "}".repeat(levels)).toByteArray(Charsets.UTF_8) }
        assertTrue(isValidDocument(objects(511)))
        assertTrue(!isValidDocument(objects(512)))
        assertFailsWith<CatalogJsonException> { stars("[".repeat(10_000)) }
    }

    @Test
    fun testTheDocumentIsAnObjectWithSchemaAndRecords() {
        assertEquals(1L, stars("""{"schema":1,"stars":[]}""").schema)
        assertNull(stars("""{"stars":[]}""").schema)
        assertNull(stars("""{"schema":null,"stars":[]}""").schema)
        assertEquals(1L, stars("""{"schema":1.0,"stars":[]}""").schema)
        assertEquals(99999999999L, stars("""{"schema":99999999999,"stars":[]}""").schema)
        for (invalid in listOf(
            "[]", "5", """{"schema":1}""", """{"schema":1,"stars":null}""", """{"schema":1,"stars":{}}""",
            """{"schema":"1","stars":[]}""", """{"schema":1.5,"stars":[]}""", """{"schema":[1],"stars":[]}""",
            """{"schema":1,"stars":[]} x""", """{"schema":1,"stars":5}""",
        )) {
            assertFailsWith<CatalogJsonException>(invalid) { stars(invalid) }
        }
        // The last of repeated members counts, as in a dictionary.
        assertEquals(1, stars("""{"stars":5,"schema":1,"stars":[{"hip":1}]}""").records.size)
        assertFailsWith<CatalogJsonException> { stars("""{"stars":[],"stars":5}""") }
        assertEquals(2L, stars("""{"schema":"x","schema":2,"stars":[]}""").schema)
        // A byte order mark is skipped, a second one is not.
        val withMark = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + """{"stars":[]}""".toByteArray(Charsets.UTF_8)
        assertEquals(0, CatalogJsonReader(withMark).readFile("stars", StarRecord::read).records.size)
        assertFailsWith<CatalogJsonException> {
            CatalogJsonReader(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + withMark).readFile("stars", StarRecord::read)
        }
    }

    // MARK: Numbers

    @Test
    fun testNumbersAreTheClosestDoubles() {
        val random = Random(20261017)

        fun digits(count: Int): String = buildString { repeat(count) { append(('0' + random.nextInt(10))) } }

        fun token(): String {
            val sign = if (random.nextInt(3) == 0) "-" else ""
            val integer = if (random.nextInt(4) == 0) "0" else ('1' + random.nextInt(9)).toString() + digits(random.nextInt(22))
            val fraction = if (random.nextInt(3) == 0) "" else "." + "0".repeat(random.nextInt(8)) + digits(1 + random.nextInt(20))
            val exponent = when (random.nextInt(4)) {
                0 -> "e" + (random.nextInt(60) - 30)
                1 -> "E+" + random.nextInt(330)
                2 -> "e-" + random.nextInt(340)
                else -> ""
            }
            return sign + integer + fraction + exponent
        }

        for (iteration in 0 until 20_000) {
            val text = token()
            val expected = text.toDouble()
            val record = star("\"ra\":$text")
            if (expected.isInfinite()) {
                assertNull(record, text)
            } else {
                assertEquals(expected.toRawBits(), assertNotNull(record, text).ra?.toRawBits(), text)
            }
        }
        for (text in listOf(
            "0", "-0", "0.0", "-0.0", "1e400", "-1e400", "1e-400", "4.9e-324", "2.4703282292062327e-324", "2.4703282292062328e-324",
            "1.7976931348623157e308", "1.7976931348623159e308", "9007199254740993", "9007199254740992.5",
            "0.1", "0.30000000000000004", "123456789012345678901234567890", "1e22", "1e23", "8.5e-5",
        )) {
            val expected = text.toDouble()
            val record = star("\"ra\":$text")
            if (expected.isInfinite()) {
                assertNull(record, text)
            } else {
                assertEquals(expected.toRawBits(), assertNotNull(record, text).ra?.toRawBits(), text)
            }
        }
    }

    @Test
    fun testIntegersAreExactlyIntegral() {
        for ((text, expected) in listOf(
            "0" to 0L, "-0" to 0L, "7" to 7L, "-7" to -7L, "1.0" to 1L, "1e2" to 100L, "1E+2" to 100L, "12.50e1" to 125L,
            "9223372036854775807" to Long.MAX_VALUE, "-9223372036854775808" to Long.MIN_VALUE,
            "999999999999999999" to 999_999_999_999_999_999L, "4611686018427387904" to 4_611_686_018_427_387_904L,
            "9.007199254740992e15" to 9_007_199_254_740_992L,
        )) {
            assertEquals(expected, star("\"hip\":$text")?.hip, text)
        }
        for (text in listOf(
            "1.5", "0.5", "-0.1", "1e-1", "9223372036854775808", "-9223372036854775809", "1e19", "-1e19", "1e400",
            "18446744073709551615", "\"1\"", "true", "[1]", "{}",
        )) {
            assertNull(star("\"hip\":$text"), text)
        }
        assertNull(star("\"hip\":null")?.hip)
        assertNull(star("\"ra\":null")?.ra)
    }

    // MARK: Records

    @Test
    fun testMembersOfTheWrongTypeSpoilTheRecordAndTheLastOccurrenceCounts() {
        assertNull(star("\"hip\":\"x\""))
        assertNull(star("\"n\":5"))
        assertNull(star("\"ra\":\"1\""))
        assertNull(star("\"hip\":1,\"hip\":\"x\""))
        assertEquals(1L, star("\"hip\":\"x\",\"hip\":1")?.hip, "a later good value replaces an earlier bad one")
        assertEquals("b", star("\"n\":\"a\",\"n\":\"b\"")?.n)
        assertNull(star("\"n\":\"a\",\"n\":null")?.n)
        // Unknown members are skipped, whatever they hold, but they have to be valid JSON.
        assertNotNull(star("\"zz\":[1,{\"a\":[null,true,\"x\"]}],\"hip\":3"))
        assertFailsWith<CatalogJsonException> { star("\"zz\":[1,},\"hip\":3") }
        // Member names may be escaped.
        assertEquals(5L, star("\"h\\u0069p\":5")?.hip)
        assertNull(star("\"hip\\u0000\":5")?.hip)
    }

    @Test
    fun testArraysAreReadElementByElement() {
        val file = reader("""{"schema":1,"objects":[{"id":"A","ngc":[1,2,3],"names":["x","y"],"t":"G"},{"ngc":[1,"x"]},{"ngc":[1,null]},{"names":[1]},{"ngc":null,"names":null}]}""")
            .readFile("objects", DeepSkyRecord::read)
        assertEquals(listOf(1L, 2L, 3L), file.records[0]?.ngc)
        assertEquals(listOf("x", "y"), file.records[0]?.names)
        assertNull(file.records[1])
        assertNull(file.records[2])
        assertNull(file.records[3])
        assertNull(file.records[4]?.ngc)
    }

    @Test
    fun testPolylinesAreLossyElementByElement() {
        val file = reader("""{"schema":1,"constellations":[{"abbr":"Ori","lines":[[[1,2],[3,4]],[[1,"x"]],5,[],[[1,2,3]],[[1],[2]],"s",null]}]}""")
            .readFile("constellations", ConstellationRecord::read)
        val lines = assertNotNull(file.records.single()?.lines)
        assertEquals(8, lines.size)
        assertEquals(2, lines[0]?.size)
        assertEquals(listOf(1.0, 2.0), lines[0]?.get(0)?.toList())
        assertNull(lines[1])
        assertNull(lines[2])
        assertEquals(0, lines[3]?.size)
        assertEquals(1, lines[4]?.size)
        assertEquals(2, lines[5]?.size)
        assertNull(lines[6])
        assertNull(lines[7])
        assertNull(
            reader("""{"schema":1,"constellations":[{"lines":"none"}]}""").readFile("constellations", ConstellationRecord::read)
                .records.single(),
        )
    }

    @Test
    fun testTheRealDataFilesAreReadCompletely() {
        val starFile = CatalogJsonReader(Catalog.bundledData(CatalogFile.STARS)).readFile("stars", StarRecord::read)
        assertEquals(1L, starFile.schema)
        assertEquals(9001, starFile.records.size)
        assertTrue(starFile.records.none { it == null })
        val sirius = assertNotNull(starFile.records[0])
        assertEquals(32349L, sirius.hip)
        assertEquals("Sirius", sirius.n)
        assertEquals("α", sirius.b)
        assertEquals(101.287156, sirius.ra)
        assertEquals(-16.716116, sirius.de)
        assertEquals(-1.44, sirius.m)
        val dsoFile = CatalogJsonReader(Catalog.bundledData(CatalogFile.DEEP_SKY_OBJECTS)).readFile("objects", DeepSkyRecord::read)
        assertEquals(693, dsoFile.records.size)
        assertEquals("M1", dsoFile.records[0]?.id)
        assertEquals(4.0, dsoFile.records[0]?.minorAxis)
    }
}
