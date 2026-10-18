package net.kuecker.lodestar.kit.catalog

import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.ephemeris.RiseSetCalculator
import net.kuecker.lodestar.kit.ephemeris.RiseTransitSet
import net.kuecker.lodestar.kit.foundation.*
import java.text.Normalizer
import java.time.Instant
import java.util.Random
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests that are not in the Swift suite: boundaries of rules the Swift tests leave open (found by mutating the
 * port), and the Kotlin-only helpers that stand in for Swift's `String` and `Unicode.Scalar` behaviour.
 */
class CatalogPortExtrasTests {
    // MARK: Boundaries of the Swift rules

    @Test
    fun testPositionAnglesNeverReachOneEighty() {
        // -1e-20 + 180 rounds to exactly 180, which is not a valid angle: it wraps to 0.
        assertEquals(0.0, AngularSize.create(5.0, positionAngle = -1e-20)?.positionAngle)
        // Swift's truncatingRemainder gives -0.0 for -180, and so does %: the same angle as 0.
        assertTrue(AngularSize.create(5.0, positionAngle = -180.0)?.positionAngle == 0.0)
        assertEquals(179.0, AngularSize.create(5.0, positionAngle = -1.0)?.positionAngle)
        for (angle in listOf(-1e-300, -5e-324, 180.0 - 1e-14, 360.0, -360.0, 540.0)) {
            val reduced = assertNotNull(AngularSize.create(5.0, positionAngle = angle)?.positionAngle)
            assertTrue(reduced >= 0.0 && reduced < 180.0, "$angle -> $reduced")
        }
    }

    @Test
    fun testStarRecordsOfTheDeepSkyDataMergeWithinOneArcminute() {
        // 0.0083° of right ascension at declination 20° is 0.0078° = 0.47′ on the sky; the separations below are in arcseconds.
        fun merged(separationArcseconds: Double): Boolean {
            val declination = 20.0 + separationArcseconds / 3600
            val catalog = CatalogFixture.syntheticCatalog(
                stars = listOf(CatalogFixture.star(hip = 50, name = "Neighbor")),
                deepSky = listOf(
                    """{"id":"M1","m":1,"t":"SNR","ra":83.6,"de":22.0,"c":"Tau"}""",
                    """{"id":"NGC10","t":"*","ra":10.0,"de":$declination,"c":"Ori","vm":3}""",
                ),
            )
            return catalog.objectWithID("dso.NGC10") == null
        }
        assertTrue(merged(0.0))
        assertTrue(merged(59.0))
        assertFalse(merged(61.0))
        assertFalse(merged(120.0))
    }

    @Test
    fun testAPoleExactlyAtTheStandardHorizonIsAlwaysUp() {
        val interval = DateInterval(Instant.parse("2026-10-17T03:00:00Z"), 3_600.0)
        val pole = assertNotNull(CatalogFixture.catalog().objectWithID("special.ncp"))
        val place = Observer(GeographicLocation(latitude = RiseSetCalculator.standardHorizonAltitude, longitude = 0.0, elevation = 0.0))
        val result = RiseSetCalculator.riseTransitSet(pole, place, interval)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_UP, result.circumstance, "the pole is at the threshold: not below it")
        assertEquals(RiseSetCalculator.standardHorizonAltitude, result.transitAltitude)
    }

    // MARK: Determinism

    @Test
    fun testTwoLoadsGiveTheSameCatalog() {
        val first = Catalog.loadBundled()
        val second = Catalog.loadBundled()
        assertEquals(first.objects, second.objects)
        assertEquals(first.listedObjects, second.listedObjects)
        assertEquals(first.constellations, second.constellations)
        assertEquals(first.milkyWay, second.milkyWay)
        assertEquals(first.renderStars, second.renderStars)
        assertEquals(first.loadReport, second.loadReport)
        assertEquals(first.loadReport.hashCode(), second.loadReport.hashCode())
        for (group in ObjectGroup.entries) assertEquals(first.objects(group), second.objects(group))
        assertContentEquals(first.searchIndex.text, second.searchIndex.text)
        assertContentEquals(first.searchIndex.objectsByRank, second.searchIndex.objectsByRank)
        assertTrue(first.searchIndex.keyCount in 35_000..45_000, "about 40 000 search keys: ${first.searchIndex.keyCount}")
    }

    @Test
    fun testEveryDisplayedTextOfEveryObjectHasItsKeyInTheIndex() {
        val catalog = CatalogFixture.catalog()
        val index = catalog.searchIndex
        // The keys of an object are grouped, in the order of the objects.
        val keysOfObject = HashMap<Int, MutableList<SearchText.Key>>()
        for (keyIndex in 0 until index.keyCount) {
            keysOfObject.getOrPut(index.source(keyIndex).obj) { ArrayList() }.add(index.key(keyIndex))
        }
        for ((position, obj) in catalog.objects.withIndex()) {
            val keys = keysOfObject[position] ?: emptyList()
            for (text in listOf(obj.name) + obj.designations + obj.alternateNames) {
                val key = SearchText.key(text)
                if (key.bytes.isEmpty()) continue
                assertTrue(keys.contains(key), "${obj.id}: \"$text\" is not among the ${keys.size} keys")
            }
        }
        for (keyIndex in 0 until index.keyCount) {
            val key = index.key(keyIndex)
            assertEquals(SearchIndex.symbolMask(key.bytes), index.symbolMasks[keyIndex])
            assertTrue(key.bytes.isNotEmpty() && key.bytes.size <= SearchText.maximumKeyLength)
            assertEquals(1L, key.wordStarts and 1L, "a key opens with a word")
        }
    }

    @Test
    fun testTheSymbolBitTableFollowsTheDefinition() {
        for (value in 0..255) {
            val expected = when (value) {
                in 0x61..0x7A -> 1L shl (value - 0x61)
                in 0x30..0x39 -> 1L shl (26 + value - 0x30)
                else -> 1L shl 63
            }
            assertEquals(expected, SearchIndex.symbolBit(value.toByte()), "byte $value")
        }
    }

    @Test
    fun testAReusedKeyBuilderMakesTheSameKeysAsAFreshOne() {
        val builder = KeyBuilder()
        val texts = listOf(
            "M 31", "NGC 0224", "α¹ Cen", "Boötes", "", "000", "a0", "ＮＧＣ７０００", "🔭 M42", "x".repeat(200), "1 ".repeat(100),
            "Barnard's Star", "ǅ", "ﬃ", "①②③", "Ꜳꜳ", "𝐒𝐢𝐫𝐢𝐮𝐬", "́́", "straße", "Æsir",
        )
        for (round in 0 until 3) {
            for (text in texts) {
                builder.reset()
                builder.readText(text, Int.MAX_VALUE)
                val fresh = SearchText.key(text)
                assertEquals(fresh, builder.toKey(), text)
                assertEquals(SearchIndex.symbolMask(fresh.bytes), builder.symbolMask, text)
            }
        }
    }

    // MARK: Swift string behaviour

    @Test
    fun testWhiteSpaceIsTheUnicodeProperty() {
        val pattern = Pattern.compile("\\p{IsWhite_Space}")
        for (scalar in 0..0x10FFFF) {
            if (scalar in 0xD800..0xDFFF) continue
            val expected = pattern.matcher(String(Character.toChars(scalar))).matches()
            assertEquals(expected, CatalogText.isWhiteSpace(scalar), "U+%04X".format(scalar))
        }
    }

    @Test
    fun testControlCharactersAreGeneralCategoryCc() {
        for (scalar in listOf(0, 9, 10, 0x1F, 0x7F, 0x80, 0x9F)) assertTrue(CatalogText.isControl(scalar), "$scalar")
        for (scalar in listOf(0x20, 0x41, 0xA0, 0xAD, 0x200B, 0x1F52D)) assertFalse(CatalogText.isControl(scalar), "$scalar")
    }

    @Test
    fun testStringsCompareLikeSwiftStrings() {
        assertEquals(0, CatalogText.compare("é", "é"), "canonically equivalent strings are equal")
        assertTrue(CatalogText.compare("a", "b") < 0 && CatalogText.compare("b", "a") > 0)
        assertTrue(CatalogText.compare("a", "ab") < 0 && CatalogText.compare("ab", "a") > 0)
        assertEquals(0, CatalogText.compare("", ""))
        // U+FF5E sorts below U+10000 by scalar value, above it by UTF-16 unit.
        assertTrue(CatalogText.compare("～", "𐀀") < 0)
        assertTrue("～".compareTo("𐀀") > 0)
        assertTrue(CatalogText.compare("Z", "a") < 0, "upper case sorts before lower case")
        assertTrue(CatalogText.compare("star.hip10", "star.hip100") < 0)
    }

    @Test
    fun testCharactersArePrefixedByGraphemeClusters() {
        assertEquals("abc", CatalogText.prefixCharacters("abcdef", 3))
        assertEquals("abcdef", CatalogText.prefixCharacters("abcdef", 6))
        assertEquals("abcdef", CatalogText.prefixCharacters("abcdef", 60))
        assertEquals("", CatalogText.prefixCharacters("abc", 0))
        assertEquals("éé", CatalogText.prefixCharacters("ééé", 2))
        assertEquals("🔭🔭", CatalogText.prefixCharacters("🔭🔭🔭", 2))
        assertEquals("👨‍👩‍👧‍👦a", CatalogText.prefixCharacters("👨‍👩‍👧‍👦ab", 2), "a family is one character")
        assertEquals("🇺🇸", CatalogText.prefixCharacters("🇺🇸🇯🇵", 1), "a flag is one character")
    }

    @Test
    fun testUtf8LengthIsTheLengthOfTheEncoding() {
        val random = Random(11)
        val scalars = intArrayOf(0x41, 0x7F, 0x80, 0x7FF, 0x800, 0xFFFF, 0x10000, 0x1F52D, 0x10FFFF, 0xE000, 0xD7FF)
        for (iteration in 0 until 500) {
            val text = StringBuilder()
            repeat(random.nextInt(30)) { text.appendCodePoint(scalars[random.nextInt(scalars.size)]) }
            assertEquals(text.toString().toByteArray(Charsets.UTF_8).size, CatalogText.utf8Length(text.toString()))
        }
    }

    @Test
    fun testSpacesAndTabsAreTrimmed() {
        assertEquals("a b", CatalogText.trimmingSpacesAndTabs(" \t a b\t  　"))
        assertEquals("a\nb", CatalogText.trimmingSpacesAndTabs("a\nb"))
        assertEquals("\nx\n", CatalogText.trimmingSpacesAndTabs("\nx\n"), "line breaks are not in CharacterSet.whitespaces")
        assertEquals("", CatalogText.trimmingSpacesAndTabs("  \t "))
        assertEquals("é", CatalogText.trimmingSpacesAndTabs("é"))
    }

    // MARK: Small value types

    @Test
    fun testRawValuesFindTheirCases() {
        for (file in CatalogFile.entries) assertEquals(file, CatalogFile.fromRawValue(file.rawValue))
        for (order in CatalogSortOrder.entries) assertEquals(order, CatalogSortOrder.fromRawValue(order.rawValue))
        for (kind in CatalogSearchResult.MatchKind.entries) {
            assertEquals(kind, CatalogSearchResult.MatchKind.fromRawValue(kind.rawValue))
        }
        for (group in ObjectGroup.entries) assertEquals(group, ObjectGroup.fromRawValue(group.rawValue))
        assertEquals(listOf(0, 1, 2, 3, 4), CatalogSearchResult.MatchKind.entries.map { it.rawValue })
        assertNull(CatalogFile.fromRawValue("dsos"))
        assertNull(CatalogSortOrder.fromRawValue("Name"))
        assertNull(CatalogSearchResult.MatchKind.fromRawValue(5))
        assertNull(ObjectGroup.fromRawValue("Stars"))
        assertEquals(listOf("stars", "dsos", "constellations", "milkyway"), CatalogFile.entries.map { it.resourceName })
    }

    @Test
    fun testErrorsAreValues() {
        assertEquals(CatalogError.Malformed(CatalogFile.STARS, "x"), CatalogError.Malformed(CatalogFile.STARS, "x"))
        assertNotEquals<CatalogError>(CatalogError.Malformed(CatalogFile.STARS, "x"), CatalogError.Malformed(CatalogFile.STARS, "y"))
        assertNotEquals<CatalogError>(CatalogError.Malformed(CatalogFile.STARS, "x"), CatalogError.Unreadable(CatalogFile.STARS, "x"))
        assertEquals(
            CatalogError.UnsupportedSchema(CatalogFile.MILKY_WAY, 3).hashCode(),
            CatalogError.UnsupportedSchema(CatalogFile.MILKY_WAY, 3).hashCode(),
        )
        assertEquals(
            "The catalog data file stars.json has an unsupported schema version (none).",
            CatalogError.UnsupportedSchema(CatalogFile.STARS, null).message,
        )
        assertEquals(
            "The catalog data file dsos.json has no valid records (3 rejected).",
            CatalogError.NoValidRecords(CatalogFile.DEEP_SKY_OBJECTS, 3).message,
        )
        assertEquals(
            "The catalog data file constellations.json is missing.",
            CatalogError.MissingResource(CatalogFile.CONSTELLATIONS).message,
        )
        assertEquals(
            "The catalog data file milkyway.json could not be read: disk",
            CatalogError.Unreadable(CatalogFile.MILKY_WAY, "disk").message,
        )
    }

    @Test
    fun testTheLoadReportIsAValue() {
        val a = CatalogLoadReport()
        val b = CatalogLoadReport()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        a.reject(CatalogLoadReport::stars) { "one" }
        assertNotEquals(a, b)
        b.reject(CatalogLoadReport::stars) { "one" }
        assertEquals(a, b)
        b.reject(CatalogLoadReport::deepSkyObjects) { "two" }
        assertNotEquals(a, b)
        assertEquals(listOf("one", "two"), b.issues)
        assertTrue(b.toString().contains("rejected=1"))
    }

    @Test
    fun testObjectsOfAConstellationLessCatalogStillSort() {
        val catalog = CatalogFixture.syntheticCatalog(constellations = emptyList())
        assertEquals(listOf("Test Star"), catalog.objects(ObjectGroup.STARS).map { it.name })
        assertEquals(catalog.listedObjects.size, catalog.sorted(catalog.listedObjects, CatalogSortOrder.NAME).size)
    }

    @Test
    fun testCategoryAndGroupJsonUseTheRawValues() {
        for (category in ObjectCategory.entries) {
            assertEquals("\"${category.rawValue}\"", Json.encodeToString(ObjectCategory.serializer(), category))
        }
        for (group in ObjectGroup.entries) {
            assertEquals("\"${group.rawValue}\"", Json.encodeToString(ObjectGroup.serializer(), group))
        }
        assertEquals(
            ObjectGroup.entries.flatMap { it.categories }.toSet(),
            ObjectCategory.entries.toSet(),
        )
    }

    @Test
    fun testAngularSizeJsonKeysAndShapes() {
        assertEquals(
            """{"majorArcminutes":5.0}""",
            Json.encodeToString(AngularSize.serializer(), assertNotNull(AngularSize.create(5.0))),
        )
        assertEquals(
            """{"majorArcminutes":178.0,"minorArcminutes":70.0,"positionAngle":35.0}""",
            Json.encodeToString(AngularSize.serializer(), assertNotNull(AngularSize.create(178.0, 70.0, 35.0))),
        )
        // Unknown keys and nulls for the optional members are fine; the values are cleaned like those of create.
        val decoded = Json.decodeFromString(
            AngularSize.serializer(),
            """{"extra":[1],"majorArcminutes":10,"minorArcminutes":null,"positionAngle":-10}""",
        )
        assertEquals(assertNotNull(AngularSize.create(10.0, null, 170.0)), decoded)
    }

    @Test
    fun testTheNormalizerAgreesWithTheSearchKeyTable() {
        // Every Latin-1 and Latin Extended-A letter reads as the ASCII letters its decomposition starts with, or as
        // the special cases the key builder lists; nothing in that range is a word boundary except punctuation.
        for (scalar in 0xC0..0x17F) {
            val key = SearchText.key(String(Character.toChars(scalar))).bytes
            val decomposed = Normalizer.normalize(String(Character.toChars(scalar)), Normalizer.Form.NFKD)
            val letters = decomposed.filter { it in 'A'..'Z' || it in 'a'..'z' }.lowercase()
            val special = setOf(0xD7, 0xF7, 0xDE, 0xFE, 0xD0, 0xF0, 0xD8, 0xF8, 0xDF, 0x110, 0x111, 0x126, 0x127, 0x131, 0x141, 0x142, 0x152, 0x153, 0x166, 0x167, 0xC6, 0xE6)
            if (scalar !in special && letters.isNotEmpty()) {
                assertEquals(letters, String(key, Charsets.UTF_8), "U+%04X".format(scalar))
            }
        }
    }
}
