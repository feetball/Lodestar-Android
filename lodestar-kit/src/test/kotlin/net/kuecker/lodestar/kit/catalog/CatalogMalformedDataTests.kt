package net.kuecker.lodestar.kit.catalog

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Damaged, hostile or unexpected data files: whole files that cannot be used raise a `CatalogError`,
 * single bad records are skipped and counted.
 */
class CatalogMalformedDataTests {
    // MARK: Building records

    /** A JSON object from members in order. */
    private fun json(members: List<Pair<String, String>>): String =
        "{" + members.joinToString(",") { "\"${it.first}\":${it.second}" } + "}"

    private val baseStar: List<Pair<String, String>> = listOf(
        "hip" to "2", "n" to "\"Other Star\"", "c" to "\"Ori\"", "ra" to "10.0", "de" to "20.0", "m" to "3.0",
    )
    private val baseDeepSky: List<Pair<String, String>> = listOf(
        "id" to "\"NGC1\"", "t" to "\"G\"", "ra" to "10.0", "de" to "20.0", "c" to "\"And\"", "vm" to "9.0",
        "maj" to "8", "min" to "4", "pa" to "30",
    )

    private fun changing(members: List<Pair<String, String>>, key: String, to: String?): List<Pair<String, String>> {
        val result = members.filter { it.first != key }.toMutableList()
        if (to != null) result.add(key to to)
        return result
    }

    private fun star(key: String, value: String?): String = json(changing(baseStar, key, value))

    private fun deepSky(key: String, value: String?): String = json(changing(baseDeepSky, key, value))

    /** Loads the default valid star next to `record` and returns the catalog. */
    private fun catalogWithStar(record: String): Catalog =
        CatalogFixture.syntheticCatalog(stars = listOf(CatalogFixture.defaultStar, record))

    private fun catalogWithDeepSky(record: String): Catalog =
        CatalogFixture.syntheticCatalog(deepSky = listOf(CatalogFixture.defaultDeepSky, record))

    private fun assertStarRejected(record: String, note: String = "") {
        val catalog = catalogWithStar(record)
        assertEquals(1, catalog.loadReport.stars.accepted, record)
        assertEquals(1, catalog.loadReport.stars.rejected, record)
        assertEquals(listOf("star.hip1"), catalog.objects.filter { it.id.startsWith("star.") }.map { it.id }, record)
        assertEquals(1, catalog.loadReport.issues.size)
        assertTrue(catalog.loadReport.issues[0].startsWith("stars[1]: "), catalog.loadReport.issues[0])
        if (note.isNotEmpty()) {
            assertTrue(catalog.loadReport.issues[0].contains(note), "${catalog.loadReport.issues[0]} should mention $note")
        }
        assertFalse(catalog.loadReport.isClean)
    }

    private fun assertStarAccepted(record: String) {
        val catalog = catalogWithStar(record)
        assertEquals(2, catalog.loadReport.stars.accepted, "$record ${catalog.loadReport.issues}")
        assertEquals(0, catalog.loadReport.stars.rejected)
        assertTrue(catalog.loadReport.isClean)
    }

    private fun assertDeepSkyRejected(record: String, note: String = "") {
        val catalog = catalogWithDeepSky(record)
        assertEquals(1, catalog.loadReport.deepSkyObjects.accepted, record)
        assertEquals(1, catalog.loadReport.deepSkyObjects.rejected, record)
        assertEquals(listOf("dso.M1"), catalog.objects.filter { it.id.startsWith("dso.") }.map { it.id }, record)
        assertTrue(catalog.loadReport.issues.firstOrNull()?.startsWith("dsos[1]: ") ?: false, "${catalog.loadReport.issues}")
        if (note.isNotEmpty()) {
            assertTrue(
                catalog.loadReport.issues.firstOrNull()?.contains(note) ?: false,
                "${catalog.loadReport.issues} should mention $note",
            )
        }
    }

    private fun assertError(
        expected: CatalogError,
        stars: ByteArray? = null,
        deepSky: ByteArray? = null,
        constellations: ByteArray? = null,
        milkyWay: ByteArray? = null,
    ) {
        try {
            Catalog(
                starsData = stars ?: CatalogFixture.file("stars", listOf(CatalogFixture.defaultStar)),
                dsosData = deepSky ?: CatalogFixture.file("objects", listOf(CatalogFixture.defaultDeepSky)),
                constellationsData = constellations
                    ?: CatalogFixture.file("constellations", listOf(CatalogFixture.defaultConstellation)),
                milkyWayData = milkyWay ?: CatalogFixture.file("levels", listOf(CatalogFixture.defaultMilkyWayLevel)),
            )
            fail("expected $expected")
        } catch (error: CatalogError) {
            when {
                error is CatalogError.Malformed && expected is CatalogError.Malformed ->
                    assertEquals(expected.file, error.file, "$error")
                error is CatalogError.Unreadable && expected is CatalogError.Unreadable ->
                    assertEquals(expected.file, error.file, "$error")
                error is CatalogError.MissingResource && expected is CatalogError.MissingResource ->
                    assertEquals(expected.file, error.file, "$error")
                else -> assertEquals(expected, error)
            }
        }
    }

    // MARK: Whole files

    @Test
    fun testTruncatedFilesAreRejected() {
        val files = CatalogFile.entries.map { it to Catalog.bundledData(it) }
        for ((file, data) in files) {
            for (length in listOf(0, 1, 20, data.size / 10, data.size / 2, data.size - 5, data.size - 3)) {
                val truncated = data.copyOf(length)
                when (file) {
                    CatalogFile.STARS -> assertError(CatalogError.Malformed(CatalogFile.STARS, ""), stars = truncated)
                    CatalogFile.DEEP_SKY_OBJECTS ->
                        assertError(CatalogError.Malformed(CatalogFile.DEEP_SKY_OBJECTS, ""), deepSky = truncated)
                    CatalogFile.CONSTELLATIONS ->
                        assertError(CatalogError.Malformed(CatalogFile.CONSTELLATIONS, ""), constellations = truncated)
                    CatalogFile.MILKY_WAY ->
                        assertError(CatalogError.Malformed(CatalogFile.MILKY_WAY, ""), milkyWay = truncated)
                }
            }
        }
        // The complete files load, and so do the files without their final line break.
        Catalog.loadBundled()
        val stars = Catalog.bundledData(CatalogFile.STARS)
        Catalog(
            starsData = stars.copyOf(stars.size - 1),
            dsosData = Catalog.bundledData(CatalogFile.DEEP_SKY_OBJECTS),
            constellationsData = Catalog.bundledData(CatalogFile.CONSTELLATIONS),
            milkyWayData = Catalog.bundledData(CatalogFile.MILKY_WAY),
        )
    }

    @Test
    fun testWrongTopLevelShapesAreRejected() {
        val shapes = listOf(
            "[]", "{}", "null", "true", "123", "\"stars\"", """{"schema":1}""", """{"schema":1,"stars":null}""",
            """{"schema":1,"stars":"text"}""", """{"schema":1,"stars":{}}""", """{"schema":1,"stars":5}""",
            """{"schema":"1","stars":[]}""", """{"schema":1.5,"stars":[]}""", """{"schema":[1],"stars":[]}""",
            """{"schema":1,"objects":[]}""", "{\"schema\":1,\"stars\":[]}garbage", "{\"schema\":1,\"stars\":[]}}", "",
        )
        for (shape in shapes) {
            assertError(CatalogError.Malformed(CatalogFile.STARS, ""), stars = shape.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun testEveryFileIsBlamedByName() {
        val bad = "[1,2,3]".toByteArray(Charsets.UTF_8)
        assertError(CatalogError.Malformed(CatalogFile.STARS, ""), stars = bad)
        assertError(CatalogError.Malformed(CatalogFile.DEEP_SKY_OBJECTS, ""), deepSky = bad)
        assertError(CatalogError.Malformed(CatalogFile.CONSTELLATIONS, ""), constellations = bad)
        assertError(CatalogError.Malformed(CatalogFile.MILKY_WAY, ""), milkyWay = bad)
        assertError(
            CatalogError.UnsupportedSchema(CatalogFile.STARS, 2),
            stars = CatalogFixture.file("stars", listOf(CatalogFixture.defaultStar), schema = "2"),
        )
        assertError(
            CatalogError.UnsupportedSchema(CatalogFile.DEEP_SKY_OBJECTS, 0),
            deepSky = CatalogFixture.file("objects", listOf(CatalogFixture.defaultDeepSky), schema = "0"),
        )
        assertError(
            CatalogError.UnsupportedSchema(CatalogFile.CONSTELLATIONS, -1),
            constellations = CatalogFixture.file("constellations", emptyList(), schema = "-1"),
        )
        assertError(
            CatalogError.UnsupportedSchema(CatalogFile.MILKY_WAY, 99),
            milkyWay = CatalogFixture.file("levels", emptyList(), schema = "99"),
        )
        assertError(
            CatalogError.UnsupportedSchema(CatalogFile.STARS, null),
            stars = """{"stars":[]}""".toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun testBinaryAndEncodingGarbageIsRejected() {
        val garbage: List<ByteArray> = listOf(
            ByteArray(0),
            byteArrayOf(0x20, 0x0A, 0x09),
            byteArrayOf(0x00, 0x00, 0x00, 0x00),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte()),
            """{"schema":1,"stars":[""".toByteArray(Charsets.UTF_8) + byteArrayOf(0xC3.toByte(), 0x28) +
                "]}".toByteArray(Charsets.UTF_8),
            ByteArray(256) { it.toByte() },
            ByteArray(10_000) { 0x7B },
            ByteArray(10_000) { 0x5B },
            "﻿".toByteArray(Charsets.UTF_8),
            """{'schema':1,'stars':[]}""".toByteArray(Charsets.UTF_8),
            "<xml/>".toByteArray(Charsets.UTF_8),
        )
        for (data in garbage) {
            assertError(CatalogError.Malformed(CatalogFile.STARS, ""), stars = data)
            assertError(CatalogError.Malformed(CatalogFile.MILKY_WAY, ""), milkyWay = data)
        }
    }

    @Test
    fun testNonFiniteAndMisspelledNumberLiteralsNeverLoadAsNumbers() {
        // Strict parsers reject the file; lenient ones read some of these as text or garbage, and the
        // record fails to decode. Either way no record with such a number is loaded.
        for (literal in listOf("NaN", "Infinity", "-Infinity", "nan", "inf", "+1", "01", ".5", "1.", "0x10", "1e", "--1", "1e+", "-", "Inf")) {
            val record = """{"hip":1,"c":"Ori","ra":$literal,"de":20.0,"m":2.5}"""
            try {
                val catalog = Catalog(
                    starsData = CatalogFixture.file("stars", listOf(CatalogFixture.defaultStar, record)),
                    dsosData = CatalogFixture.file("objects", listOf(CatalogFixture.defaultDeepSky)),
                    constellationsData = CatalogFixture.file("constellations", emptyList()),
                    milkyWayData = CatalogFixture.file("levels", emptyList()),
                )
                // A lenient parser read the literal as a valid number: it must be a finite one.
                assertTrue(catalog.objectWithID("star.hip1")?.j2000?.rightAscension?.isFinite() ?: true, literal)
            } catch (error: CatalogError.Malformed) {
                assertEquals(CatalogFile.STARS, error.file, literal)
            }
        }
        for (literal in listOf("NaN", "Infinity", "-Infinity")) {
            val record = """{"hip":1,"c":"Ori","ra":$literal,"de":20.0,"m":2.5}"""
            assertError(CatalogError.Malformed(CatalogFile.STARS, ""), stars = CatalogFixture.file("stars", listOf(record)))
        }
    }

    @Test
    fun testDeeplyNestedInputIsRejectedWithoutCrashing() {
        val depth = 200_000
        val nestedArrays = ("[".repeat(depth) + "]".repeat(depth)).toByteArray(Charsets.UTF_8)
        assertError(CatalogError.Malformed(CatalogFile.STARS, ""), stars = nestedArrays)
        val nestedInRecord = ("""{"schema":1,"stars":[{"hip":1,"x":""" + "[".repeat(depth)).toByteArray(Charsets.UTF_8)
        assertError(CatalogError.Malformed(CatalogFile.STARS, ""), stars = nestedInRecord)
        val nestedObjects = ("""{"schema":1,"stars":""" + """{"a":""".repeat(depth)).toByteArray(Charsets.UTF_8)
        assertError(CatalogError.Malformed(CatalogFile.STARS, ""), stars = nestedObjects)

        // Nesting inside an ignored member of a valid record is bounded too: shallow is fine, deep is refused.
        fun record(nesting: Int): ByteArray =
            ("""{"schema":1,"stars":[{"hip":1,"c":"Ori","ra":1,"de":1,"m":1,"junk":""" + "[".repeat(nesting) +
                "]".repeat(nesting) + "}]}").toByteArray(Charsets.UTF_8)
        Catalog(
            starsData = record(100),
            dsosData = CatalogFixture.file("objects", listOf(CatalogFixture.defaultDeepSky)),
            constellationsData = CatalogFixture.file("constellations", emptyList()),
            milkyWayData = CatalogFixture.file("levels", emptyList()),
        )
        assertError(CatalogError.Malformed(CatalogFile.STARS, ""), stars = record(1000))
    }

    @Test
    fun testOversizedFilesAreRejected() {
        val oversized = ByteArray(Catalog.maximumFileBytes + 1)
        try {
            Catalog(
                starsData = oversized,
                dsosData = CatalogFixture.file("objects", listOf(CatalogFixture.defaultDeepSky)),
                constellationsData = CatalogFixture.file("constellations", emptyList()),
                milkyWayData = CatalogFixture.file("levels", emptyList()),
            )
            fail("expected an error")
        } catch (error: CatalogError.Malformed) {
            assertEquals(CatalogFile.STARS, error.file)
            assertTrue(error.reason.contains("larger"), error.reason)
        }
    }

    @Test
    fun testFilesWithoutUsableObjectsAreRejected() {
        assertError(CatalogError.NoValidRecords(CatalogFile.STARS, 0), stars = CatalogFixture.file("stars", emptyList()))
        assertError(
            CatalogError.NoValidRecords(CatalogFile.DEEP_SKY_OBJECTS, 0),
            deepSky = CatalogFixture.file("objects", emptyList()),
        )
        assertError(
            CatalogError.NoValidRecords(CatalogFile.STARS, 3),
            stars = CatalogFixture.file("stars", listOf(star("ra", "\"x\""), star("de", "100"), "5")),
        )
        assertError(
            CatalogError.NoValidRecords(CatalogFile.DEEP_SKY_OBJECTS, 2),
            deepSky = CatalogFixture.file("objects", listOf(deepSky("id", null), deepSky("ra", "-3"))),
        )
        assertError(
            CatalogError.NoValidRecords(CatalogFile.STARS, 1),
            stars = CatalogFixture.file("stars", listOf("""{"hip":1}""")),
        )
    }

    @Test
    fun testConstellationsAndTheMilkyWayMayBeEmpty() {
        val catalog = CatalogFixture.syntheticCatalog(constellations = emptyList(), milkyWayLevels = emptyList())
        assertTrue(catalog.constellations.isEmpty())
        assertTrue(catalog.milkyWay.levels.isEmpty())
        assertNull(catalog.constellation("Ori"))
        val star = assertNotNull(catalog.objectWithID("star.hip1"))
        assertEquals("Ori", star.constellation, "the abbreviation is kept")
        assertNull(star.constellationName)
        assertEquals("Star · mag 2.5", star.subtitle)
        assertTrue(catalog.loadReport.isClean)
        assertEquals("star.hip1", catalog.search("test star").firstOrNull()?.obj?.id)
    }

    @Test
    fun testAMinimalCatalogWorks() {
        val catalog = CatalogFixture.syntheticCatalog()
        assertEquals(9 + 3 + 1 + 1, catalog.objects.size)
        assertEquals(1, catalog.renderStars.size)
        assertEquals(listOf("Ori"), catalog.constellations.map { it.abbreviation })
        assertEquals("Orion", catalog.objectWithID("star.hip1")?.constellationName)
        assertEquals(listOf("HIP 1"), catalog.objectWithID("star.hip1")?.designations)
        assertEquals("Tau", catalog.objectWithID("dso.M1")?.constellation)
        assertNull(catalog.objectWithID("dso.M1")?.constellationName, "Taurus is not in the constellation file")
        assertEquals(1, catalog.milkyWay.levels.size)
        assertEquals("star.hip1", catalog.search("hip 1").firstOrNull()?.obj?.id)
        assertEquals("dso.M1", catalog.search("m1").firstOrNull()?.obj?.id)
        assertEquals(listOf("star.hip1"), catalog.objects(ObjectGroup.STARS).map { it.id })
        assertTrue(catalog.loadReport.isClean)
    }

    // MARK: Star records

    @Test
    fun testStarRecordsWithTheWrongShapeAreSkipped() {
        for (record in listOf("5", "\"star\"", "null", "[]", "[1,2]", "true", "{}", "{\"hip\":5}")) {
            assertStarRejected(record)
        }
        val wrongTypes = listOf(
            "hip" to "\"x\"", "hip" to "1.5", "hip" to "[1]", "hip" to "{}", "hip" to "true", "hr" to "\"y\"",
            "n" to "5", "n" to "[\"a\"]", "b" to "1", "f" to "\"x\"", "c" to "5", "ra" to "\"12\"",
            "ra" to "[1]", "de" to "{}", "pa" to "\"fast\"", "pd" to "false", "m" to "\"bright\"",
            "ci" to "[]", "sp" to "7", "d" to "\"far\"",
        )
        for ((key, value) in wrongTypes) {
            assertStarRejected(star(key, value), "")
        }
    }

    @Test
    fun testStarRecordsWithNonFiniteLookingNumbersAreSkipped() {
        val values = listOf("\"NaN\"", "\"nan\"", "\"Infinity\"", "\"-Infinity\"", "\"inf\"", "1e999", "-1e999", "1e400", "1E+999")
        for (value in values) {
            for (key in listOf("ra", "de", "m", "pa", "pd", "ci", "d")) {
                assertStarRejected(star(key, value))
            }
        }
    }

    @Test
    fun testStarValuesOutOfRangeAreSkipped() {
        val rejected = listOf(
            "ra" to "-0.5", "ra" to "360.0001", "ra" to "720", "ra" to "1e10", "de" to "90.0001", "de" to "-90.5",
            "de" to "1e6", "m" to "40.5", "m" to "-30.5", "m" to "1e30", "pa" to "100000.5", "pd" to "-100001",
            "ci" to "10.5", "ci" to "-2.5", "d" to "0", "d" to "-1", "d" to "1000001", "hip" to "0", "hip" to "-5",
            "hip" to "100000000", "hr" to "0", "hd" to "-1", "f" to "0", "f" to "1000", "f" to "-3",
        )
        for ((key, value) in rejected) {
            assertStarRejected(star(key, value), "")
        }
        val accepted = listOf(
            "ra" to "0", "ra" to "360", "ra" to "359.999999", "ra" to "-0.0", "de" to "90", "de" to "-90",
            "de" to "0", "m" to "-30", "m" to "40", "pa" to "100000", "pd" to "-100000", "pa" to "0", "ci" to "10",
            "ci" to "-2", "d" to "1000000", "d" to "0.0001", "hip" to "99999999", "f" to "1", "f" to "999",
            "sp" to "\"\"", "n" to "\"\"", "n" to "null", "b" to "null", "sp" to "null", "d" to "null",
        )
        for ((key, value) in accepted) {
            assertStarAccepted(star(key, value))
        }
        val edge = catalogWithStar(star("ra", "360"))
        assertEquals(0.0, edge.objectWithID("star.hip2")?.j2000?.rightAscension, "right ascension 360° wraps to 0°")
    }

    @Test
    fun testStarRecordsMissingRequiredValuesAreSkipped() {
        assertStarRejected(star("ra", null), "position")
        assertStarRejected(star("de", null), "position")
        assertStarRejected(star("m", null), "magnitude")
        assertStarRejected(star("hip", null), "hip, hr or hyg")
        assertStarRejected(json(changing(changing(baseStar, "hip", null), "hyg", "0")), "out of range")
        assertStarAccepted(star("c", null))
        assertStarAccepted(json(changing(changing(baseStar, "hip", null), "hr", "77")))
        assertStarAccepted(json(changing(changing(baseStar, "hip", null), "hyg", "123")))
    }

    @Test
    fun testStarTextIsValidated() {
        val long = "x".repeat(81)
        assertStarRejected(star("n", "\"$long\""), "too long")
        assertStarRejected(star("n", "\"${"é".repeat(500)}\""), "too long")
        assertStarRejected(star("n", "\"line\\nbreak\""), "control")
        assertStarRejected(star("n", "\"nul\\u0000byte\""), "control")
        assertStarRejected(star("n", "\"bell\\u0007\""), "control")
        assertStarRejected(star("sp", "\"${"K".repeat(25)}\""), "too long")
        assertStarRejected(star("b", "\"αβγδε\""), "too long")
        assertStarRejected(star("b", "\"α β\""), "whitespace")
        assertStarRejected(star("c", "\"Orion\""), "too long")
        assertStarRejected(star("c", "\"O\""), "malformed")
        assertStarRejected(star("c", "\"O1i\""), "malformed")
        assertStarRejected(star("c", "\"Ωρι\""), "malformed")
        assertStarAccepted(star("n", "\"  padded name  \""))
        assertStarAccepted(star("n", "\"${"x".repeat(80)}\""))
        assertStarAccepted(star("n", "\"日本語 🔭 Åström\""))
        assertStarAccepted(star("c", "\"or\""))
        assertStarAccepted(star("c", "\"\""))
        val padded = catalogWithStar(star("n", "\"  padded name  \""))
        assertEquals("padded name", padded.objectWithID("star.hip2")?.name)
    }

    @Test
    fun testDuplicateStarsKeepTheFirst() {
        val catalog = CatalogFixture.syntheticCatalog(
            stars = listOf(
                star("n", "\"First\""), star("n", "\"Second\""), star("hip", "3"),
                json(changing(changing(baseStar, "hip", null), "hr", "5")),
                json(changing(changing(changing(baseStar, "hip", null), "hr", "5"), "n", "\"Again\"")),
            ),
        )
        assertEquals(3, catalog.loadReport.stars.accepted)
        assertEquals(2, catalog.loadReport.stars.rejected)
        assertEquals("First", catalog.objectWithID("star.hip2")?.name)
        assertNotNull(catalog.objectWithID("star.hip3"))
        assertEquals("Other Star", catalog.objectWithID("star.hr5")?.name)
        assertTrue(catalog.loadReport.issues.all { it.contains("duplicate") }, "${catalog.loadReport.issues}")
        // The same Hipparcos number with a different Harvard number is still the same star.
        val twice = CatalogFixture.syntheticCatalog(stars = listOf(star("hr", "10"), star("hr", "11")))
        assertEquals(1, twice.loadReport.stars.rejected)
    }

    @Test
    fun testGoodRecordsSurviveBadNeighbours() {
        val bad = listOf("5", star("ra", "\"x\""), star("de", "99"), star("m", "\"NaN\""))
        val records = ArrayList<String>()
        val expected = ArrayList<String>()
        for (index in 1..30) {
            if (index % 3 == 0) {
                records.add(bad[(index / 3) % bad.size])
            } else {
                records.add(json(changing(baseStar, "hip", "$index")))
                expected.add("star.hip$index")
            }
        }
        val catalog = CatalogFixture.syntheticCatalog(stars = records)
        assertEquals(20, catalog.loadReport.stars.accepted)
        assertEquals(10, catalog.loadReport.stars.rejected)
        assertEquals(expected.sorted(), catalog.objects.filter { it.id.startsWith("star.") }.map { it.id }.sorted())
        assertEquals(20, catalog.renderStars.size)
        assertEquals(10, catalog.loadReport.issues.size)
        assertEquals(10, catalog.loadReport.rejectedCount)
    }

    @Test
    fun testUnknownMembersAreIgnored() {
        val record = json(baseStar + listOf("extra" to "{\"nested\":[1,2,{\"x\":null}]}", "future" to "\"field\"", "n2" to "null"))
        assertStarAccepted(record)
        val file = """{"schema":1,"source":"test","license":"x","generated":"today","stars":[${CatalogFixture.defaultStar}],"extra":[1,2,3]}"""
            .toByteArray(Charsets.UTF_8)
        Catalog(
            starsData = file,
            dsosData = CatalogFixture.file("objects", listOf(CatalogFixture.defaultDeepSky)),
            constellationsData = CatalogFixture.file("constellations", emptyList()),
            milkyWayData = CatalogFixture.file("levels", emptyList()),
        )
    }

    @Test
    fun testTheIssueListIsBounded() {
        val bad = (0 until 200).map { star("ra", "${it + 400}") }
        val catalog = CatalogFixture.syntheticCatalog(stars = listOf(CatalogFixture.defaultStar) + bad)
        assertEquals(200, catalog.loadReport.stars.rejected)
        assertEquals(CatalogLoadReport.maximumIssueCount, catalog.loadReport.issues.size)
        assertEquals("stars[1]: position missing or out of range", catalog.loadReport.issues.firstOrNull())
    }

    @Test
    fun testAHugeNumberOfRecordsLoads() {
        val records = (1..5000).map { CatalogFixture.star(hip = it, magnitude = (it % 140).toDouble() / 10) }
        val catalog = CatalogFixture.syntheticCatalog(stars = records)
        assertEquals(5000, catalog.loadReport.stars.accepted)
        assertEquals("star.hip4999", catalog.search("hip 4999").firstOrNull()?.obj?.id)
    }

    // MARK: Deep-sky records

    @Test
    fun testDeepSkyRecordsWithTheWrongShapeAreSkipped() {
        for (record in listOf("5", "null", "[]", "\"M1\"", "{}", """{"id":"X"}""")) {
            assertDeepSkyRejected(record)
        }
        val wrongTypes = listOf(
            "id" to "5", "t" to "[]", "ra" to "\"1\"", "de" to "null", "c" to "7", "vm" to "\"9\"", "bm" to "[]",
            "maj" to "\"big\"", "min" to "{}", "pa" to "\"x\"", "names" to "\"Name\"", "names" to "[1,2]",
            "names" to "{}", "ngc" to "5", "ngc" to "[\"a\"]", "ngc" to "[1.5]", "ic" to "\"x\"", "m" to "\"1\"",
            "h" to "5", "sb" to "\"22\"",
        )
        for ((key, value) in wrongTypes) {
            assertDeepSkyRejected(deepSky(key, value))
        }
    }

    @Test
    fun testDeepSkyValuesAreValidated() {
        class Case(val key: String, val value: String?, val note: String)

        val rejected = listOf(
            Case("id", null, "id"), Case("id", "\"\"", "id"), Case("id", "\"   \"", "id"),
            Case("id", "\"M 1\"", "id"), Case("id", "\"M1/2\"", "id"), Case("id", "\"M1\\\"x\"", "id"),
            Case("id", "\"é\"", "id"), Case("id", "\"${"A".repeat(41)}\"", "too long"),
            Case("t", null, "type"), Case("t", "\"\"", "type"), Case("t", "\"WayTooLongType\"", "too long"),
            Case("ra", null, "position"), Case("ra", "-1", "position"), Case("ra", "361", "position"),
            Case("de", "91", "position"), Case("de", null, "position"),
            Case("vm", "41", "vm"), Case("vm", "-31", "vm"), Case("bm", "1e9", "bm"), Case("maj", "0", "maj"),
            Case("maj", "-5", "maj"), Case("maj", "21601", "maj"), Case("min", "0", "min"), Case("min", "-1", "min"),
            Case("min", "9", "minor axis longer"), Case("pa", "361", "pa"), Case("pa", "-1", "pa"),
            Case("m", "0", "Messier"), Case("m", "1000", "Messier"), Case("m", "-3", "Messier"),
            Case("ngc", "[0]", "ngc"), Case("ngc", "[100000]", "ngc"), Case("ngc", "[-5]", "ngc"),
            Case("ic", "[1, 0]", "ic"), Case("sb", "0", "sb"), Case("sb", "41", "sb"), Case("sb", "-3", "sb"),
            Case("h", "\"${"S".repeat(17)}\"", "too long"), Case("c", "\"Andromeda\"", "too long"),
            Case("names", "[\"${"n".repeat(81)}\"]", "too long"), Case("names", "[\"bad\\u0007name\"]", "control"),
        )
        for (case in rejected) {
            assertDeepSkyRejected(deepSky(case.key, case.value), case.note)
        }
        assertDeepSkyRejected(json(changing(changing(baseDeepSky, "maj", null), "min", "4")), "without major")
    }

    @Test
    fun testValidDeepSkyEdgeCasesAreAccepted() {
        val accepted = listOf(
            "vm" to "-30", "vm" to "40", "vm" to null, "bm" to "12.5", "maj" to "21600", "min" to null,
            "min" to "8", "pa" to "0", "pa" to "360", "pa" to null, "m" to "1", "m" to "999", "ngc" to "[1]",
            "ngc" to "[]", "ngc" to null, "ngc" to "[99999, 99999]", "ic" to "[5386]", "names" to "[]",
            "names" to null, "names" to "[\"\", \" \"]", "h" to null, "h" to "\"\"", "sb" to "40", "sb" to "0.1",
            "c" to null, "c" to "\"\"", "c" to "\"And\"", "t" to "\"Unknown\"", "t" to "\"Other\"", "ra" to "360",
            "de" to "-90", "id" to "\"IC359A\"", "id" to "\"A_b-c.d+e\"", "id" to "\"ESO56-115\"",
        )
        for ((key, value) in accepted) {
            val record = deepSky(key, value)
            val catalog = catalogWithDeepSky(record)
            assertEquals(2, catalog.loadReport.deepSkyObjects.accepted, "$record ${catalog.loadReport.issues}")
            assertEquals(0, catalog.loadReport.deepSkyObjects.rejected, record)
        }
        val tiny = catalogWithDeepSky(json(changing(changing(baseDeepSky, "maj", "0.001"), "min", "0.0005")))
        assertEquals(0.001, tiny.objectWithID("dso.NGC1")?.angularSize?.majorArcminutes)
        assertEquals(0.0005, tiny.objectWithID("dso.NGC1")?.angularSize?.minorArcminutes)
        assertEquals(2, tiny.loadReport.deepSkyObjects.accepted)
        val unknownType = catalogWithDeepSky(deepSky("t", "\"Unknown\""))
        assertEquals(ObjectCategory.OTHER, unknownType.objectWithID("dso.NGC1")?.category, "unknown type codes are 'other'")
        val blankNames = catalogWithDeepSky(deepSky("names", "[\"\", \" \", \"Real\", \"Real\", \" Real \"]"))
        assertEquals("Real", blankNames.objectWithID("dso.NGC1")?.name)
        assertEquals(emptyList(), blankNames.objectWithID("dso.NGC1")?.alternateNames)
        val dup = catalogWithDeepSky(deepSky("ngc", "[7, 7, 8, 7]"))
        assertEquals(listOf("NGC 7", "NGC 8", "NGC 1"), dup.objectWithID("dso.NGC1")?.designations)
    }

    @Test
    fun testDuplicateDeepSkyIdentifiersKeepTheFirst() {
        val catalog = CatalogFixture.syntheticCatalog(
            deepSky = listOf(deepSky("names", "[\"First\"]"), deepSky("names", "[\"Second\"]"), deepSky("id", "\"NGC2\"")),
        )
        assertEquals(2, catalog.loadReport.deepSkyObjects.accepted)
        assertEquals(1, catalog.loadReport.deepSkyObjects.rejected)
        assertEquals("First", catalog.objectWithID("dso.NGC1")?.name)
        assertTrue(catalog.loadReport.issues[0].contains("duplicate"), "${catalog.loadReport.issues}")
    }

    @Test
    fun testPlainStarRecordsAreOnlyMergedIntoStarsAtTheSamePlace() {
        val starAtOrigin = CatalogFixture.star(hip = 50, name = "Neighbor")
        fun plainStar(id: String, ra: String, name: String): String =
            """{"id":"$id","names":["$name"],"t":"*","ra":$ra,"de":20.0,"c":"Ori","vm":3}"""

        val catalog = CatalogFixture.syntheticCatalog(
            stars = listOf(starAtOrigin),
            deepSky = listOf(
                plainStar("NGC10", "10.0", "Alias"), // on the star: merged
                plainStar("NGC11", "10.0166", "Alias"), // 0.9 arcmin away (RA offset times cos δ): merged
                plainStar("NGC12", "10.05", "Survivor"), // about 2.8 arcmin away: stays
                """{"id":"M40","m":40,"t":"**","ra":10.0,"de":20.0,"c":"Ori"}""", // Messier: stays
                """{"id":"NGC13","t":"G","ra":10.0,"de":20.0,"c":"Ori"}""", // not a star: stays
            ),
        )
        assertEquals(2, catalog.loadReport.deepSkyObjects.merged)
        assertEquals(3, catalog.loadReport.deepSkyObjects.accepted)
        assertNull(catalog.objectWithID("dso.NGC10"))
        assertNull(catalog.objectWithID("dso.NGC11"))
        assertNotNull(catalog.objectWithID("dso.NGC12"))
        assertNotNull(catalog.objectWithID("dso.M40"))
        assertNotNull(catalog.objectWithID("dso.NGC13"))
        val neighbor = assertNotNull(catalog.objectWithID("star.hip50"))
        assertEquals(listOf("HIP 50", "NGC 10", "NGC 11"), neighbor.designations)
        assertEquals(listOf("Alias"), neighbor.alternateNames)
        assertEquals("star.hip50", catalog.search("ngc 11").firstOrNull()?.obj?.id)
        assertEquals("star.hip50", catalog.search("alias").firstOrNull()?.obj?.id)
        assertEquals("Alias", catalog.search("alias").firstOrNull()?.matchedText)
        assertTrue(catalog.loadReport.isClean, "merging is not a rejection")
    }

    // MARK: Constellations and the Milky Way

    @Test
    fun testConstellationRecordsAreValidated() {
        val valid = listOf(
            "abbr" to "\"Ori\"", "name" to "\"Orion\"", "genitive" to "\"Orionis\"",
            "label" to "[83.0,5.0]", "lines" to "[[[80.0,0.0],[85.0,10.0]]]",
        )

        fun variant(key: String, value: String?): String = json(changing(valid, key, value))

        val taurus = """{"abbr":"Tau","name":"Taurus","genitive":"Tauri","label":[65.0,15.0],"lines":[[[60.0,10.0],[70.0,20.0]]]}"""
        val bad = listOf(
            variant("abbr", null), variant("abbr", "\"Orion\""), variant("abbr", "5"), variant("name", null),
            variant("name", "\"\""), variant("genitive", null), variant("label", null), variant("label", "[1]"),
            variant("label", "[1,2,3]"), variant("label", "[400,0]"), variant("label", "[10,95]"),
            variant("label", "\"x\""), "5", "null", "[]", "{}",
        )
        for (record in bad) {
            val catalog = CatalogFixture.syntheticCatalog(constellations = listOf(taurus, record))
            assertEquals(1, catalog.loadReport.constellations.accepted, record)
            assertEquals(1, catalog.loadReport.constellations.rejected, record)
            assertEquals(1, catalog.constellations.size, record)
        }
        val good = CatalogFixture.syntheticCatalog(constellations = listOf(variant("lines", null), variant("abbr", "\"Cma\"")))
        assertEquals(2, good.loadReport.constellations.accepted)
        assertEquals(0, good.constellation("ori")?.lines?.size, "a constellation without a figure is fine")
        assertEquals("Cma", good.constellation("CMA")?.abbreviation)
    }

    @Test
    fun testDuplicateConstellationsKeepTheFirst() {
        val first = CatalogFixture.defaultConstellation
        val second = first.replace("Orion\"", "Second\"")
        val catalog = CatalogFixture.syntheticCatalog(
            constellations = listOf(first, second, first.replace("\"Ori\"", "\"ORI\"")),
        )
        assertEquals(1, catalog.constellations.size)
        assertEquals("Orion", catalog.constellations.firstOrNull()?.name)
        assertEquals(2, catalog.loadReport.constellations.rejected)
        assertTrue(catalog.loadReport.issues.all { it.contains("duplicate") })
    }

    @Test
    fun testBadPolylinesAreDroppedAndTheConstellationKept() {
        val lines = "[[[80,0],[85,10]],[[1,2]],[],[[1,2],[\"x\",3]],[[1,2],[3]],[[1,2],[3,4,5]],[[1,2],[400,3]],[[1,2],[3,95]],[[10,10],[20,20],[30,30]],5,\"x\"]"
        val record = """{"abbr":"Ori","name":"Orion","genitive":"Orionis","label":[83,5],"lines":$lines}"""
        val catalog = CatalogFixture.syntheticCatalog(constellations = listOf(record))
        assertEquals(1, catalog.constellations.size)
        assertEquals(listOf(2, 3), catalog.constellations[0].lines.map { it.size })
        assertEquals(2, catalog.loadReport.constellationLines.accepted)
        assertEquals(9, catalog.loadReport.constellationLines.rejected)
        assertEquals(0, catalog.loadReport.constellations.rejected)
        assertFalse(catalog.loadReport.isClean)
        assertTrue(catalog.loadReport.issues.any { it.contains("9 invalid polylines") }, "${catalog.loadReport.issues}")
        val notArray = """{"abbr":"Ori","name":"Orion","genitive":"Orionis","label":[83,5],"lines":"none"}"""
        val wrongType = CatalogFixture.syntheticCatalog(constellations = listOf(notArray))
        assertEquals(1, wrongType.loadReport.constellations.rejected, "a lines member of the wrong type spoils the record")
    }

    @Test
    fun testMilkyWayLevelsAreValidatedAndSorted() {
        val ring = "[[10,10],[20,10],[20,20]]"
        val levels = listOf(
            """{"level":3,"polygons":[$ring],"holes":[]}""",
            """{"level":1,"polygons":[$ring,[[1,1],[2,2]],[[1,1],[2,"x"],[3,3]],[[1,1],[2,95],[3,3]],5],"holes":[$ring,[]]}""",
            """{"level":2,"polygons":[$ring]}""",
            """{"level":0,"polygons":[$ring]}""", """{"level":101,"polygons":[$ring]}""", """{"polygons":[$ring]}""",
            """{"level":4,"polygons":[[[1,1],[2,2]]]}""", """{"level":5}""", """{"level":6,"polygons":"x"}""", "7", "null",
        )
        val catalog = CatalogFixture.syntheticCatalog(milkyWayLevels = levels)
        assertEquals(listOf(1, 2, 3), catalog.milkyWay.levels.map { it.level })
        assertEquals(1, catalog.milkyWay.levels[0].polygons.size)
        assertEquals(1, catalog.milkyWay.levels[0].holes.size)
        assertEquals(0, catalog.milkyWay.levels[1].holes.size)
        assertEquals(4, catalog.loadReport.milkyWayRings.accepted)
        assertEquals(4 + 1 + 8, catalog.loadReport.milkyWayRings.rejected, "bad rings, plus levels that are unusable as a whole")
        assertFalse(catalog.loadReport.issues.isEmpty())
    }

    // MARK: Robustness

    @Test
    fun testManyConcurrentLoadsAreIndependent() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = List(8) {
                Callable { runCatching { CatalogFixture.syntheticCatalog().objects.size }.getOrDefault(-1) }
            }
            val counts = pool.invokeAll(tasks).map { it.get() }
            assertEquals(List(8) { 14 }, counts)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun testRandomDamageToTheRealFilesNeverCrashes() {
        var state = 42L
        fun next(): Int {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return (state ushr 33).toInt()
        }

        fun damaged(data: ByteArray, round: Int): ByteArray {
            val position = next() % data.size
            return when (round % 3) {
                0 -> data.copyOf().also { it[position] = next().toByte() }
                1 -> {
                    val end = minOf(data.size, position + 1 + next() % 40)
                    data.copyOfRange(0, position) + data.copyOfRange(end, data.size)
                }
                else -> data.copyOfRange(0, position) + "\"\\u00".toByteArray(Charsets.UTF_8) +
                    data.copyOfRange(position, data.size)
            }
        }

        val dsos = Catalog.bundledData(CatalogFile.DEEP_SKY_OBJECTS)
        val constellations = Catalog.bundledData(CatalogFile.CONSTELLATIONS)
        val milkyWay = Catalog.bundledData(CatalogFile.MILKY_WAY)
        var loaded = 0
        var failed = 0
        for (round in 0 until 60) {
            try {
                val catalog = Catalog(
                    starsData = CatalogFixture.file("stars", listOf(CatalogFixture.defaultStar)),
                    dsosData = damaged(dsos, round),
                    constellationsData = damaged(constellations, round),
                    milkyWayData = damaged(milkyWay, round),
                )
                assertTrue(catalog.objects.size > 12)
                assertTrue(catalog.loadReport.issues.size <= CatalogLoadReport.maximumIssueCount)
                loaded += 1
            } catch (error: CatalogError) {
                failed += 1
            }
        }
        assertEquals(60, loaded + failed)
        assertTrue(loaded > 0, "most single-byte changes only spoil a record or a value")
    }
}
