package net.kuecker.lodestar.kit.catalog

import java.text.Normalizer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// MARK: - Text normalization

class CatalogSearchTextTests {
    private fun key(text: String): String = String(SearchText.key(text).bytes, Charsets.UTF_8)

    private fun wordStarts(text: String): List<Int> {
        val mask = SearchText.key(text).wordStarts
        return (0 until 64).filter { mask and (1L shl it) != 0L }
    }

    @Test
    fun testCaseAndPunctuationAreIgnored() {
        assertEquals("m31", key("M 31"))
        assertEquals("m31", key("m31"))
        assertEquals("m31", key("M-31"))
        assertEquals("m31", key("m.31"))
        assertEquals("m31", key("  M\t31\n"))
        assertEquals("messier31", key("Messier 31"))
        assertEquals("sgra", key("Sgr A*"))
        assertEquals("hchipersei", key("h & chi Persei"))
        assertEquals("wolflundmarkmelotte", key("Wolf-Lundmark-Melotte"))
        assertEquals("orionnebula", key("(Orion) [Nebula]!"))
    }

    @Test
    fun testApostrophesJoinTheirWord() {
        assertEquals("barnardsstar", key("Barnard's Star"))
        assertEquals("barnardsstar", key("Barnard’s Star"))
        assertEquals("barnardsstar", key("Barnard‘s Star"))
        assertEquals("barnardsstar", key("Barnard`s Star"))
        assertEquals(listOf(0, 8), wordStarts("Barnard's Star"))
        assertEquals("amasdelecudesobieski", key("Amas de l'Ecu de Sobieski"))
    }

    @Test
    fun testDiacriticsAndLigaturesAreIgnored() {
        val expectations = listOf(
            "Boötes" to "bootes", "Bélénos" to "belenos", "Añañuca" to "ananuca", "Mönch" to "monch",
            "Tupã" to "tupa", "Ångström" to "angstrom", "Łódź" to "lodz", "Æsir" to "aesir", "straße" to "strasse",
            "Øresund" to "oresund", "Œuvre" to "oeuvre", "ﬁsh" to "fish", "Đồng" to "dong", "Þór" to "thor",
            "Rosalíadecastro" to "rosaliadecastro", "Citalá" to "citala", "ế" to "e", "ǎ" to "a", "ı" to "i",
            "é" to "e", "ö" to "o", "Å" to "a", "ñ" to "n",
        )
        for ((text, expected) in expectations) {
            assertEquals(expected, key(text), text)
        }
    }

    @Test
    fun testFullWidthTextIsReadAsNormalText() {
        assertEquals("m31", key("Ｍ３１"))
        assertEquals("ngc7000", key("ＮＧＣ　７０００"))
        assertEquals("sirius", key("ｓｉｒｉｕｓ"))
        assertEquals("sirius", key("Ｓｉｒｉｕｓ！"))
    }

    @Test
    fun testGreekLettersAreReadAsTheirNames() {
        val lower = "αβγδεζηθικλμνξοπρστυφχψω".toList()
        val upper = "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩ".toList()
        assertEquals(24, lower.size)
        assertEquals(24, upper.size)
        assertEquals(24, GreekAlphabet.letters.size)
        for ((index, letter) in GreekAlphabet.letters.withIndex()) {
            assertEquals(letter.name, key(lower[index].toString()), "lower case ${lower[index]}")
            assertEquals(letter.name, key(upper[index].toString()), "upper case ${upper[index]}")
            assertEquals(index, GreekAlphabet.index(lower[index].code))
            assertEquals(letter.name, letter.capitalizedName.lowercase())
        }
        assertEquals("sigma", key("ς"), "final sigma")
        assertEquals("theta", key("ϑ"))
        assertEquals("phi", key("ϕ"))
        assertEquals("pi", key("ϖ"))
        assertEquals("epsilon", key("ϵ"))
        assertEquals("kappa", key("ϰ"))
        assertEquals("rho", key("ϱ"))
        assertEquals("alpha", key("ά"), "accented Greek reads like the plain letter")
        assertEquals("alphacma", key("α CMa"))
        assertEquals("alphacma", key("αCMa"))
        assertEquals(listOf(0), wordStarts("αCMa"))
        assertEquals("aalpha", key("aα"))
        assertEquals(listOf(0, 1), wordStarts("aα"), "a Greek letter opens a word")
        assertNull(GreekAlphabet.index('a'.code))
        assertNull(GreekAlphabet.index(0x03A2), "the unassigned code point is not a letter")
        assertEquals("x", key("΢x"))
        assertEquals(GreekAlphabet.letters.size, GreekAlphabet.letters.map { it.name }.toSet().size)
    }

    @Test
    fun testSuperscriptAndSubscriptDigitsAreDigits() {
        assertEquals("alpha1cen", key("α¹ Cen"))
        assertEquals("alpha2cen", key("α² Cen"))
        assertEquals("pi3ori", key("π³ Ori"))
        assertEquals("x123456789", key("x⁰¹²³⁴⁵⁶⁷⁸⁹"), "the leading zero is dropped like any other")
        assertEquals("h2o", key("H₂O"))
        assertEquals("a123456789", key("a₀₁₂₃₄₅₆₇₈₉"))
        assertEquals("a10", key("a₁₀"))
        assertEquals("104", key("10⁴"))
        assertEquals(key("α¹ Cen"), key("alpha1 cen"))
        assertEquals(key("α¹ Cen"), key("alpha 1 cen"))
        assertEquals(listOf(0, 6), wordStarts("α¹ Cen"))
    }

    @Test
    fun testLeadingZerosOfNumbersAreDropped() {
        assertEquals("ngc224", key("NGC 0224"))
        assertEquals("ngc224", key("NGC0224"))
        assertEquals("m31", key("M031"))
        assertEquals("hr2491", key("HR 02491"))
        assertEquals("ic434", key("IC 0434"))
        assertEquals("0", key("0"))
        assertEquals("0", key("00"))
        assertEquals("0a", key("000a"))
        assertEquals("7", key("007"))
        assertEquals("a7", key("a007"))
        assertEquals("a0", key("a0"))
        assertEquals("a0b", key("a00b"))
        assertEquals("hd0", key("HD 0"))
        assertEquals("100", key("100"))
        assertEquals("1000", key("1000"))
        assertEquals("ic2431ned2", key("IC 2431_NED02"))
        assertEquals(key("ic 2431 ned 02"), key("IC2431_NED02"))
        assertEquals(listOf(0, 1), wordStarts("M 031"))
    }

    @Test
    fun testWordStartsMarkTheFirstByteOfEachWord() {
        assertEquals(listOf(0, 5), wordStarts("Orion Nebula"))
        assertEquals(listOf(0, 1), wordStarts("M 31"))
        assertEquals(listOf(0), wordStarts("NGC7000"))
        assertEquals(listOf(0, 1, 2), wordStarts("a-b-c"))
        assertEquals(listOf(0, 7), wordStarts("  leading space"))
        assertEquals(listOf(0, 5, 10), wordStarts("Alpha Canis Majoris"))
        assertEquals(emptyList(), wordStarts(""))
    }

    @Test
    fun testTextWithoutLettersOrDigitsHasNoKey() {
        for (text in listOf(
            "", " ", "\n\t", "!!!", "—", "...", "🔭", "😀😀😀", "星空", "Орион", "\u0000", "​﻿",
            "́́", "★☆", "∞", "‮",
        )) {
            assertEquals("", key(text), text.codePoints().toArray().joinToString(" ") { Integer.toHexString(it) })
        }
        assertEquals("m31", key("🔭M31🔭"))
        assertEquals("m42", key("Орион M42"))
    }

    @Test
    fun testKeysAreBounded() {
        assertEquals(SearchText.maximumKeyLength, SearchText.key("a".repeat(1000)).bytes.size)
        assertEquals(SearchText.maximumKeyLength, SearchText.key("ω".repeat(1000)).bytes.size)
        assertEquals("abcde", String(SearchText.key("abcdefgh", maximumScalars = 5).bytes, Charsets.UTF_8))
        assertEquals(0, SearchText.key("abc", maximumScalars = 0).bytes.size)
        assertEquals(-1L, SearchText.key("1 ".repeat(100)).wordStarts, "every one of the 64 stored digits opens a word")
        val long = SearchText.key("ab ".repeat(100))
        assertEquals(64, long.bytes.size)
        assertEquals(1L, long.wordStarts and 1L)
        assertEquals(64, SearchText.maximumQueryScalars)
        assertEquals(64, Catalog.maximumQueryLength)
    }

    @Test
    fun testNaturalOrderComparesNumbersByValue() {
        val unsorted = listOf("m10", "m2", "m1", "m100", "m21", "m3a", "m3", "abc", "ab1", "ab", "", "m")

        fun precedes(a: String, b: String): Boolean =
            SearchText.naturalPrecedes(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

        val sorted = unsorted.sortedWith { a, b -> if (precedes(a, b)) -1 else if (precedes(b, a)) 1 else 0 }
        assertEquals(listOf("", "ab", "ab1", "abc", "m", "m1", "m2", "m3", "m3a", "m10", "m21", "m100"), sorted)
        assertFalse(precedes("m1", "m1"))
        assertTrue(precedes("ngc9", "ngc10"))
        assertFalse(precedes("ngc10", "ngc9"))
        assertTrue(precedes("hd2491", "hd10000"))
        assertTrue(precedes("a9b", "a10a"))
        assertFalse(precedes("a10a", "a9b"))
    }
}

// MARK: - Searching the catalog

class CatalogSearchTests {
    private fun catalog(): Catalog = CatalogFixture.catalog()

    private fun ids(results: List<CatalogSearchResult>): List<String> = results.map { it.obj.id }

    /**
     * The ranking rules: kinds never get worse down the list, and results of one kind are ordered by
     * listed objects first, then brighter ones, then name.
     */
    private fun assertRankingOrder(results: List<CatalogSearchResult>, query: String) {
        for ((first, second) in results.zip(results.drop(1))) {
            assertTrue(first.kind <= second.kind, "$query: ${first.obj.id} before ${second.obj.id}")
            if (first.kind != second.kind) continue
            if (first.obj.isListed != second.obj.isListed) {
                assertTrue(first.obj.isListed, "$query: unlisted ${first.obj.id} before listed ${second.obj.id}")
                continue
            }
            if (first.obj.rankingMagnitude != second.obj.rankingMagnitude) {
                assertTrue(
                    first.obj.rankingMagnitude < second.obj.rankingMagnitude,
                    "$query: ${first.obj.id} before ${second.obj.id}",
                )
                continue
            }
            val a = SearchText.key(first.obj.name).bytes
            val b = SearchText.key(second.obj.name).bytes
            assertFalse(SearchText.naturalPrecedes(b, a), "$query: ${first.obj.name} before ${second.obj.name}")
        }
    }

    // MARK: Famous targets

    @Test
    fun testFamousTargetsCanBeFoundByName() {
        val catalog = catalog()
        val expectations = listOf(
            "Sirius" to "star.hip32349", "Polaris" to "star.hip11767", "Betelgeuse" to "star.hip27989",
            "Vega" to "star.hip91262", "Rigel" to "star.hip24436", "Arcturus" to "star.hip69673",
            "Antares" to "star.hip80763", "Proxima Centauri" to "star.hip70890", "Barnard's Star" to "star.hip87937",
            "M31" to "dso.M31", "M42" to "dso.M42", "M45" to "dso.M45", "NGC 7000" to "dso.NGC7000",
            "LMC" to "dso.ESO56-115", "SMC" to "dso.NGC292", "Omega Centauri" to "dso.NGC5139",
            "Double Cluster" to "dso.C14", "galactic center" to "special.galacticCenter",
            "Galactic Centre" to "special.galacticCenter",
            "Milky Way core" to "special.galacticCenter", "Sgr A*" to "special.galacticCenter",
            "Jupiter" to "planet.jupiter", "Moon" to "moon", "Sun" to "sun", "Mars" to "planet.mars",
            "Venus" to "planet.venus", "Saturn" to "planet.saturn", "Mercury" to "planet.mercury",
            "Uranus" to "planet.uranus", "Neptune" to "planet.neptune", "NCP" to "special.ncp",
            "South Celestial Pole" to "special.scp", "Pleiades" to "dso.M45", "Orion Nebula" to "dso.M42",
            "Andromeda Galaxy" to "dso.M31", "Crab Nebula" to "dso.M1", "Ring Nebula" to "dso.M57",
            "Horsehead Nebula" to "dso.B33", "North America Nebula" to "dso.NGC7000", "Veil Nebula" to "dso.NGC6960",
            "Rosette Nebula" to "dso.NGC2238", "Large Magellanic Cloud" to "dso.ESO56-115",
            "Whirlpool Galaxy" to "dso.M51", "Sombrero Galaxy" to "dso.M104", "Lagoon Nebula" to "dso.M8",
            "Trifid Nebula" to "dso.M20", "Helix Nebula" to "dso.NGC7293", "Hyades" to "dso.C41",
            "North Star" to "star.hip11767", "Pole Star" to "star.hip11767", "Lodestar" to "star.hip11767",
            "Dog Star" to "star.hip32349", "Luna" to "moon", "Sol" to "sun",
        )
        for ((query, id) in expectations) {
            val results = catalog.search(query)
            assertEquals(id, results.firstOrNull()?.obj?.id, query)
            assertEquals(CatalogSearchResult.MatchKind.EXACT, results.firstOrNull()?.kind, query)
        }
        val milkyWay = catalog.search("Milky Way")
        assertEquals("special.galacticCenter", milkyWay.firstOrNull()?.obj?.id)
        assertEquals(CatalogSearchResult.MatchKind.PREFIX, milkyWay.firstOrNull()?.kind, "the name is \"Milky Way Core\"")
    }

    @Test
    fun testCommonNamesCanBeAbbreviated() {
        val catalog = catalog()
        class Case(val query: String, val id: String, val kind: CatalogSearchResult.MatchKind)

        val expectations = listOf(
            Case("andromeda", "dso.M31", CatalogSearchResult.MatchKind.PREFIX),
            Case("orion neb", "dso.M42", CatalogSearchResult.MatchKind.PREFIX),
            Case("omega cen", "dso.NGC5139", CatalogSearchResult.MatchKind.PREFIX),
            Case("magellanic", "dso.ESO56-115", CatalogSearchResult.MatchKind.WORD_PREFIX),
            Case("double", "dso.C14", CatalogSearchResult.MatchKind.PREFIX),
            Case("pleiad", "dso.M45", CatalogSearchResult.MatchKind.PREFIX),
            Case("whirlpool", "dso.M51", CatalogSearchResult.MatchKind.PREFIX),
            Case("north america", "dso.NGC7000", CatalogSearchResult.MatchKind.PREFIX),
            Case("great orion", "dso.M42", CatalogSearchResult.MatchKind.PREFIX),
            Case("swan", "dso.M17", CatalogSearchResult.MatchKind.PREFIX),
            Case("nubecula", "dso.ESO56-115", CatalogSearchResult.MatchKind.PREFIX),
            Case("seven sisters", "dso.M45", CatalogSearchResult.MatchKind.EXACT),
        )
        for (case in expectations) {
            val results = catalog.search(case.query)
            assertTrue(ids(results).contains(case.id), case.query)
            assertEquals(case.kind, results.firstOrNull { it.obj.id == case.id }?.kind, case.query)
        }
        assertEquals("dso.M31", catalog.search("andromeda").firstOrNull()?.obj?.id, "the exact name outranks the Andromedae stars")
    }

    // MARK: Catalog designations

    @Test
    fun testMessierDesignationsInAllSpellings() {
        val catalog = catalog()
        for (spelling in listOf(
            "m31", "M31", "M 31", "m 31", "Messier 31", "messier31", "MESSIER 31", "M031", "M 031", "m0031",
            "Ｍ３１", "M-31", "M.31", "m  31", " m31 ", "M 31", "messier 031",
        )) {
            val results = catalog.search(spelling)
            assertEquals("dso.M31", results.firstOrNull()?.obj?.id, spelling)
            assertEquals(CatalogSearchResult.MatchKind.EXACT, results.firstOrNull()?.kind, spelling)
            assertEquals("M 31", results.firstOrNull()?.matchedText, spelling)
        }
        for (number in 1..110) {
            assertEquals("dso.M$number", catalog.search("M$number").firstOrNull()?.obj?.id, "M$number")
            assertEquals("dso.M$number", catalog.search("messier $number").firstOrNull()?.obj?.id)
        }
        val m1 = catalog.search("m1", limit = 200)
        assertEquals("dso.M1", m1.firstOrNull()?.obj?.id)
        assertTrue(ids(m1).contains("dso.M10") && ids(m1).contains("dso.M110"), "prefix matches follow the exact one")
        assertEquals(CatalogSearchResult.MatchKind.PREFIX, m1.drop(1).firstOrNull()?.kind)
    }

    @Test
    fun testNGCAndICDesignationsInAllSpellings() {
        val catalog = catalog()
        for (spelling in listOf("ngc224", "NGC 224", "ngc0224", "NGC 0224", "Ngc-224", "N G C 224", "NGC00224")) {
            val first = catalog.search(spelling).firstOrNull()
            assertEquals("dso.M31", first?.obj?.id, spelling)
            assertEquals("NGC 224", first?.matchedText, spelling)
            assertEquals(CatalogSearchResult.MatchKind.EXACT, first?.kind, spelling)
        }
        assertEquals("dso.NGC7000", catalog.search("ngc7000").firstOrNull()?.obj?.id)
        assertEquals("dso.NGC2239", catalog.search("ngc 2244").firstOrNull()?.obj?.id)
        for (spelling in listOf("ic 434", "IC434", "ic0434", "I C 434", "IC-434")) {
            assertEquals("dso.IC434", catalog.search(spelling).firstOrNull()?.obj?.id, spelling)
        }
        assertEquals("dso.NGC281", catalog.search("ic 11").firstOrNull()?.obj?.id)
        assertEquals("dso.IC359A", catalog.search("ic 359a").firstOrNull()?.obj?.id)
        assertEquals("dso.IC2431_NED02", catalog.search("ic 2431 ned 02").firstOrNull()?.obj?.id)
    }

    @Test
    fun testEveryNGCAndICNumberIsFindable() {
        val catalog = catalog()
        var checked = 0
        for (record in CatalogFixture.rawDeepSky()) {
            for ((prefix, numbers) in listOf("NGC" to (record.ngc ?: emptyList()), "IC" to (record.ic ?: emptyList()))) {
                for (number in numbers) {
                    if (catalog.objectWithID("dso.${record.id}") == null) continue
                    val exact = catalog.search("$prefix $number", limit = 10)
                        .filter { it.kind == CatalogSearchResult.MatchKind.EXACT }.map { it.obj.id }
                    assertTrue(exact.contains("dso.${record.id}"), "$prefix $number -> ${record.id}, found $exact")
                    checked += 1
                }
            }
        }
        assertTrue(checked > 600)
    }

    @Test
    fun testOtherCatalogDesignationsInAllSpellings() {
        val catalog = catalog()
        for (spelling in listOf("c14", "C 14", "caldwell 14", "Caldwell14", "c014")) {
            assertEquals("dso.C14", catalog.search(spelling).firstOrNull()?.obj?.id, spelling)
            assertEquals(CatalogSearchResult.MatchKind.EXACT, catalog.search(spelling).firstOrNull()?.kind, spelling)
        }
        assertEquals("dso.Mel111", catalog.search("mel 111").firstOrNull()?.obj?.id)
        assertEquals("dso.Mel111", catalog.search("melotte 111").firstOrNull()?.obj?.id)
        assertEquals("dso.B33", catalog.search("b33").firstOrNull()?.obj?.id)
        assertEquals("dso.B33", catalog.search("barnard 33").firstOrNull()?.obj?.id)
        assertEquals("dso.Cl399", catalog.search("collinder 399").firstOrNull()?.obj?.id)
        assertEquals("dso.Cl399", catalog.search("cr 399").firstOrNull()?.obj?.id)
        assertEquals("dso.ESO56-115", catalog.search("eso 56-115").firstOrNull()?.obj?.id)
        assertEquals("dso.ESO56-115", catalog.search("eso56115").firstOrNull()?.obj?.id)
        assertEquals("dso.HCG92", catalog.search("hcg 92").firstOrNull()?.obj?.id)
        assertEquals("dso.HCG92", catalog.search("hickson 92").firstOrNull()?.obj?.id)
        assertEquals("Caldwell 14", catalog.search("caldwell 14").firstOrNull()?.matchedText)
        assertEquals("Caldwell 14", catalog.search("c 14").firstOrNull()?.matchedText)
    }

    @Test
    fun testStarCatalogNumbers() {
        val catalog = catalog()
        for (spelling in listOf("hr 2491", "HR2491", "hr02491", "HR  2491", "H R 2491")) {
            assertEquals("star.hip32349", catalog.search(spelling).firstOrNull()?.obj?.id, spelling)
            assertEquals("HR 2491", catalog.search(spelling).firstOrNull()?.matchedText, spelling)
        }
        for (spelling in listOf("hd 48915", "HD48915", "hd048915")) {
            assertEquals("star.hip32349", catalog.search(spelling).firstOrNull()?.obj?.id, spelling)
        }
        for (spelling in listOf("hip 32349", "HIP32349", "hip032349")) {
            assertEquals("star.hip32349", catalog.search(spelling).firstOrNull()?.obj?.id, spelling)
        }
        // Every 25th star with each kind of number is found by it.
        for ((offset, star) in CatalogFixture.rawStars().withIndex()) {
            if (offset % 25 != 0) continue
            val id = star.hip?.let { "star.hip$it" } ?: star.hr?.let { "star.hr$it" } ?: "star.hyg${star.hyg ?: 0}"
            for (query in listOfNotNull(star.hr?.let { "HR $it" }, star.hd?.let { "hd$it" }, star.hip?.let { "HIP $it" })) {
                val first = catalog.search(query, limit = 3).firstOrNull()
                assertEquals(id, first?.obj?.id, query)
                assertEquals(CatalogSearchResult.MatchKind.EXACT, first?.kind, query)
            }
        }
    }

    @Test
    fun testBayerAndFlamsteedDesignationsInAllSpellings() {
        val catalog = catalog()
        for (spelling in listOf(
            "α CMa", "alpha cma", "Alpha CMa", "ALPHA CMA", "alphacma", "alpha canis majoris", "Alpha Canis Majoris",
            "α canis majoris", "α Canis Majoris", "alp cma", "alpcma", "alp canis majoris", "ALPHA-CMA",
            "α  CMa", "Α CMa",
        )) {
            val first = catalog.search(spelling).firstOrNull()
            assertEquals("star.hip32349", first?.obj?.id, spelling)
            assertEquals(CatalogSearchResult.MatchKind.EXACT, first?.kind, spelling)
        }
        for (spelling in listOf("9 CMa", "9 canis majoris", "9cma", "09 cma")) {
            assertEquals("star.hip32349", catalog.search(spelling).firstOrNull()?.obj?.id, spelling)
            assertEquals(CatalogSearchResult.MatchKind.EXACT, catalog.search(spelling).firstOrNull()?.kind, spelling)
        }
        assertEquals("star.hip32349", catalog.search("alf cma").firstOrNull()?.obj?.id, "SIMBAD writes alf")
        assertEquals(CatalogSearchResult.MatchKind.EXACT, catalog.search("alf canis majoris").firstOrNull()?.kind)
        assertEquals("α CMa", catalog.search("alp cma").firstOrNull()?.matchedText)
        assertEquals("Alpha Canis Majoris", catalog.search("alpha canis majoris").firstOrNull()?.matchedText)
        assertEquals("9 CMa", catalog.search("9 canis majoris").firstOrNull()?.matchedText)
        assertEquals("star.hip27989", catalog.search("alpha orionis").firstOrNull()?.obj?.id)
        assertEquals("star.hip27989", catalog.search("alpha ori").firstOrNull()?.obj?.id)
        assertEquals("star.hip27989", catalog.search("58 ori").firstOrNull()?.obj?.id)
        assertEquals("star.hip24436", catalog.search("beta orionis").firstOrNull()?.obj?.id, "Rigel")
        assertEquals("star.hip61084", catalog.search("gamma crucis").firstOrNull()?.obj?.id, "Gacrux")
    }

    @Test
    fun testSuperscriptBayerDesignations() {
        val catalog = catalog()
        for (spelling in listOf(
            "α¹ Cen", "alpha1 cen", "alpha 1 cen", "alpha1 centauri", "alpha 1 centauri", "alp1 cen", "α1 Cen",
            "alpha1centauri", "Α¹ Cen", "alpha¹ cen",
        )) {
            val first = catalog.search(spelling).firstOrNull()
            assertEquals("star.hip71683", first?.obj?.id, spelling)
            assertEquals(CatalogSearchResult.MatchKind.EXACT, first?.kind, spelling)
        }
        assertEquals("star.hip71681", catalog.search("α² Cen").firstOrNull()?.obj?.id, "Toliman")
        assertEquals("star.hip71681", catalog.search("alpha2 centauri").firstOrNull()?.obj?.id)
        // Without the digit both components of the pair match exactly.
        for (spelling in listOf("alpha cen", "alpha centauri", "α Cen", "alp cen")) {
            val exact = catalog.search(spelling).filter { it.kind == CatalogSearchResult.MatchKind.EXACT }.map { it.obj.id }
            assertEquals(setOf("star.hip71683", "star.hip71681"), exact.toSet(), spelling)
        }
        assertEquals("star.hip71683", catalog.search("alpha centauri").firstOrNull()?.obj?.id, "the brighter component first")
        assertEquals("Alpha¹ Centauri", catalog.search("alpha1 centauri").firstOrNull()?.matchedText)
    }

    @Test
    fun testEveryBayerDesignationIsFindableInEverySpelling() {
        val catalog = catalog()
        val letters = GreekAlphabet.letters
        var checked = 0
        for ((offset, star) in CatalogFixture.rawStars().withIndex()) {
            if (offset % 3 != 0) continue
            val bayer = star.b ?: continue
            if (bayer.isEmpty()) continue
            val first = bayer.codePointAt(0)
            val letter = GreekAlphabet.letter(first) ?: continue
            val constellation = catalog.constellation(star.c) ?: continue
            val id = star.hip?.let { "star.hip$it" } ?: star.hr?.let { "star.hr$it" } ?: "star.hyg${star.hyg ?: 0}"
            val suffix = bayer.substring(Character.charCount(first))
            val queries = ArrayList<String>()
            queries += listOf(
                "$bayer ${star.c}", "${letter.name}$suffix ${star.c}", "${letter.name} ${star.c}",
                "${letter.name}$suffix ${constellation.genitive}", "$bayer ${constellation.genitive}",
            )
            queries += letter.abbreviations.map { "$it$suffix ${star.c}" }
            for (query in queries) {
                val exact = catalog.search(query, limit = 20)
                    .filter { it.kind == CatalogSearchResult.MatchKind.EXACT }.map { it.obj.id }
                assertTrue(exact.contains(id), "$query should find $id, found $exact")
            }
            checked += 1
        }
        assertTrue(checked > 400)
        val abbreviations = letters.flatMap { it.abbreviations }
        assertTrue(
            listOf("alp", "bet", "gam", "del", "eps", "zet", "iot", "kap", "lam", "omi", "sig", "ups", "ome", "tet", "the", "ksi", "khi")
                .all { abbreviations.contains(it) },
        )
    }

    @Test
    fun testTheSpellingsOfThetaXiAndChi() {
        val catalog = catalog()
        val sargas = assertNotNull(catalog.search("Sargas").firstOrNull()?.obj?.id)
        for (spelling in listOf("θ Sco", "theta sco", "the sco", "tet sco", "Theta Scorpii", "tet scorpii", "ϑ Sco")) {
            assertTrue(
                catalog.search(spelling, limit = 5).filter { it.kind == CatalogSearchResult.MatchKind.EXACT }
                    .map { it.obj.id }.contains(sargas),
                spelling,
            )
        }
        val taiyangshou = assertNotNull(catalog.search("Taiyangshou").firstOrNull()?.obj?.id)
        for (spelling in listOf("χ UMa", "chi uma", "khi uma", "Chi Ursae Majoris", "khi ursae majoris")) {
            assertEquals(taiyangshou, catalog.search(spelling).firstOrNull()?.obj?.id, spelling)
        }
        for (spelling in listOf("ξ UMa", "xi uma", "ksi uma", "Xi Ursae Majoris")) {
            val names = catalog.search(spelling, limit = 5).filter { it.kind == CatalogSearchResult.MatchKind.EXACT }
                .map { it.obj.name }
            assertEquals(setOf("Alula Australis", "Alula Australis B"), names.toSet(), spelling)
        }
    }

    // MARK: Normalization

    @Test
    fun testCaseDiacriticsAndWidthDoNotMatter() {
        val catalog = catalog()
        for (spelling in listOf(
            "sirius", "SIRIUS", "SiRiUs", "ｓｉｒｉｕｓ", "ＳＩＲＩＵＳ", "Sirius!", "(Sirius)", "'sirius'", "  sirius  ",
            "s i r i u s", "S-I-R-I-U-S", "sir­ius", "sirius​", "﻿sirius",
        )) {
            assertEquals("star.hip32349", catalog.search(spelling).firstOrNull()?.obj?.id, spelling)
            assertEquals(CatalogSearchResult.MatchKind.EXACT, catalog.search(spelling).firstOrNull()?.kind, spelling)
        }
        // Names with diacritics are found without them, and with decomposed accents.
        var checked = 0
        for (star in CatalogFixture.rawStars()) {
            val name = star.n ?: continue
            if (name.codePoints().noneMatch { it > 0x7F }) continue
            val id = star.hip?.let { "star.hip$it" } ?: star.hr?.let { "star.hr$it" } ?: "star.hyg${star.hyg ?: 0}"
            val plain = Normalizer.normalize(name, Normalizer.Form.NFD).filter {
                Character.getType(it) != Character.NON_SPACING_MARK.toInt()
            }.lowercase()
            assertTrue(
                catalog.search(plain, limit = 5).filter { it.kind == CatalogSearchResult.MatchKind.EXACT }
                    .map { it.obj.id }.contains(id),
                "$plain -> $name",
            )
            assertTrue(
                catalog.search(Normalizer.normalize(name, Normalizer.Form.NFD), limit = 5).map { it.obj.id }.contains(id),
                name,
            )
            assertTrue(catalog.search(name.uppercase(), limit = 5).map { it.obj.id }.contains(id), name)
            checked += 1
        }
        assertTrue(checked > 10)
        assertEquals("Bélénos", catalog.search("belenos").firstOrNull()?.obj?.name)
        assertEquals("Bélénos", catalog.search("Bélénos").firstOrNull()?.obj?.name)
        assertEquals("Añañuca", catalog.search("ananuca").firstOrNull()?.obj?.name)
        assertEquals("Mönch", catalog.search("monch").firstOrNull()?.obj?.name)
        assertEquals("Tupã", catalog.search("tupa").firstOrNull()?.obj?.name)
        assertEquals("star.hip87937", catalog.search("barnards star").firstOrNull()?.obj?.id)
        assertEquals("star.hip87937", catalog.search("barnard’s star").firstOrNull()?.obj?.id)
    }

    @Test
    fun testPunctuationAndWhitespaceDifferencesAreIgnored() {
        val catalog = catalog()
        for (spelling in listOf(
            "orion nebula", "Orion-Nebula", "orion_nebula", "orion   nebula", "orion,nebula", "orionnebula",
            "ORION NEBULA", "orion\tnebula", "orion\nnebula", "orion.nebula", "(orion) nebula",
        )) {
            val first = catalog.search(spelling).firstOrNull()
            assertEquals("dso.M42", first?.obj?.id, spelling)
            assertEquals(CatalogSearchResult.MatchKind.EXACT, first?.kind, spelling)
        }
    }

    // MARK: Ranking

    @Test
    fun testResultsAreRankedByKindThenListingBrightnessAndName() {
        val catalog = catalog()
        val queries = listOf(
            "a", "m", "ngc", "hr", "hd 4", "alpha", "the", "cen", "1", "7", "ori", "sag", "nebula", "cluster", "m1",
            "ic", "hip 3", "galaxy", "star", "gam", "eta car", "mu", "ngc 1", "orion", "andromeda", "alpha cen", "c1",
        )
        for (query in queries) {
            val results = catalog.search(query, limit = 300)
            assertFalse(results.isEmpty(), query)
            assertRankingOrder(results, query)
        }
    }

    @Test
    fun testEveryKindOfMatchIsPossible() {
        val catalog = catalog()
        assertEquals(CatalogSearchResult.MatchKind.EXACT, catalog.search("sirius").firstOrNull()?.kind)
        assertEquals(CatalogSearchResult.MatchKind.PREFIX, catalog.search("siri").firstOrNull()?.kind)
        assertEquals(
            CatalogSearchResult.MatchKind.WORD_PREFIX,
            catalog.search("centauri", limit = 200).firstOrNull { it.obj.id == "dso.NGC5139" }?.kind,
        )
        assertEquals(
            CatalogSearchResult.MatchKind.CONTAINS,
            catalog.search("ntauri", limit = 200).firstOrNull { it.obj.id == "dso.NGC5139" }?.kind,
        )
        assertEquals(CatalogSearchResult.MatchKind.FUZZY, catalog.search("omgcent").firstOrNull()?.kind)
        assertEquals(
            listOf(
                CatalogSearchResult.MatchKind.EXACT, CatalogSearchResult.MatchKind.PREFIX,
                CatalogSearchResult.MatchKind.WORD_PREFIX, CatalogSearchResult.MatchKind.CONTAINS,
                CatalogSearchResult.MatchKind.FUZZY,
            ),
            CatalogSearchResult.MatchKind.entries,
        )
        assertTrue(
            CatalogSearchResult.MatchKind.EXACT < CatalogSearchResult.MatchKind.PREFIX &&
                CatalogSearchResult.MatchKind.CONTAINS < CatalogSearchResult.MatchKind.FUZZY,
        )
    }

    @Test
    fun testExactBeatsPrefixBeatsWordPrefixBeatsContains() {
        val catalog = catalog()
        val results = catalog.search("rion", limit = 300)
        assertFalse(results.isEmpty())
        assertTrue(
            results.all {
                it.kind == CatalogSearchResult.MatchKind.CONTAINS || it.kind == CatalogSearchResult.MatchKind.WORD_PREFIX ||
                    it.kind == CatalogSearchResult.MatchKind.PREFIX
            },
        )
        val orionis = catalog.search("orion", limit = 300)
        assertEquals(CatalogSearchResult.MatchKind.PREFIX, orionis.firstOrNull()?.kind, "no object is called just Orion")
        assertTrue(
            orionis.any { it.kind == CatalogSearchResult.MatchKind.WORD_PREFIX },
            "the Orionis designations match at a word start",
        )
        assertRankingOrder(orionis, "orion")
    }

    @Test
    fun testBrighterObjectsComeFirstWithinOneKind() {
        val catalog = catalog()
        val results = catalog.search("alpha", limit = 80)
        assertEquals(80, results.size)
        assertTrue(results.all { it.kind == CatalogSearchResult.MatchKind.PREFIX })
        assertEquals(listOf("Sirius", "Canopus", "Arcturus", "Rigil Kentaurus", "Vega"), results.take(5).map { it.obj.name })
        assertRankingOrder(results, "alpha")
    }

    @Test
    fun testSyntheticRankingCoversEveryKindAndTieBreak() {
        val stars = listOf(
            CatalogFixture.star(hip = 900001, name = "Zed"),
            CatalogFixture.star(hip = 900002, name = "Zedra"),
            CatalogFixture.star(hip = 900003, name = "Big Zed"),
            CatalogFixture.star(hip = 900004, name = "Azed"),
            CatalogFixture.star(hip = 900005, name = "Zeppelin Dumbo"),
            CatalogFixture.star(hip = 7000, magnitude = 2.0),
            CatalogFixture.star(hip = 7001, magnitude = 6.0),
            CatalogFixture.star(hip = 7002, name = "Seven Thousand", magnitude = 9.0),
            CatalogFixture.star(hip = 800001, name = "Tie Gamma", magnitude = 4.0),
            CatalogFixture.star(hip = 800002, name = "Tie Alpha", magnitude = 4.0),
            CatalogFixture.star(hip = 800003, name = "Tie Beta", magnitude = 2.0),
            CatalogFixture.star(hip = 800004, name = "Tie 10", magnitude = 5.0),
            CatalogFixture.star(hip = 800005, name = "Tie 2", magnitude = 5.0),
        )
        val catalog = CatalogFixture.syntheticCatalog(stars = stars)

        fun names(query: String): List<String> = catalog.search(query).map { it.obj.name }

        assertEquals(
            listOf("Zed", "Zedra", "Big Zed", "Azed"), names("zed"),
            "exact, prefix, word prefix, contains; no fuzzy suggestions",
        )
        assertEquals(
            listOf(
                CatalogSearchResult.MatchKind.EXACT, CatalogSearchResult.MatchKind.PREFIX,
                CatalogSearchResult.MatchKind.WORD_PREFIX, CatalogSearchResult.MatchKind.CONTAINS,
            ),
            catalog.search("zed").map { it.kind },
        )
        assertEquals(listOf("Zeppelin Dumbo"), names("zpd"), "the fuzzy suggestion appears when nothing else matches")
        assertEquals(listOf(CatalogSearchResult.MatchKind.FUZZY), catalog.search("zpd").map { it.kind })
        // Listed before unlisted, then brighter first.
        assertEquals(listOf("HIP 7000", "Seven Thousand", "HIP 7001"), names("hip 700"))
        assertEquals(listOf(true, true, false), catalog.search("hip 700").map { it.obj.isListed })
        // Brighter first, then alphabetical, with numbers in numeric order.
        assertEquals(listOf("Tie Beta", "Tie Alpha", "Tie Gamma", "Tie 2", "Tie 10"), names("tie"))
    }

    // MARK: Fuzzy matches

    @Test
    fun testFuzzyMatchesAreSuggestedOnlyWhenNothingElseMatches() {
        val catalog = catalog()
        val results = catalog.search("andgal")
        assertEquals("dso.M31", results.firstOrNull()?.obj?.id)
        assertTrue(results.all { it.kind == CatalogSearchResult.MatchKind.FUZZY })
        assertEquals("Andromeda Galaxy", results.firstOrNull()?.matchedText)
        for (exactQuery in listOf("sirius", "m31", "lmc", "moon", "alpha cma", "ngc 7000")) {
            assertFalse(catalog.search(exactQuery).any { it.kind == CatalogSearchResult.MatchKind.FUZZY }, exactQuery)
        }
        assertTrue(catalog.search("qqq").isEmpty())
        assertTrue(catalog.search("zzzzzzzz").isEmpty())
    }

    @Test
    fun testFuzzyMatchingNeedsThreeCharactersAndAWordStart() {
        val catalog = catalog()
        assertFalse(catalog.search("ag").any { it.kind == CatalogSearchResult.MatchKind.FUZZY })
        assertTrue(catalog.search("qz").isEmpty())
        assertTrue(catalog.search("zq").isEmpty())
        assertFalse(catalog.search("ndgal").any { it.obj.id == "dso.M31" }, "the first letter must open a word")
        assertFalse(catalog.search("ngal").any { it.obj.id == "dso.M31" })
        assertTrue(catalog.search("andgal").any { it.obj.id == "dso.M31" })
        assertTrue(catalog.search("andmeda").any { it.obj.id == "dso.M31" }, "a dropped letter is tolerated")
        assertTrue(catalog.search("magcloud").any { it.obj.id == "dso.ESO56-115" })
    }

    // MARK: Matched text

    @Test
    fun testMatchedTextIsTheDisplayedNameOrDesignation() {
        val catalog = catalog()
        val expectations = listOf(
            "sirius" to "Sirius", "alp cma" to "α CMa", "alpha canis majoris" to "Alpha Canis Majoris",
            "hr 2491" to "HR 2491", "hd 48915" to "HD 48915", "hip 32349" to "HIP 32349", "9 canis majoris" to "9 CMa",
            "m31" to "M 31", "ngc 224" to "NGC 224", "andromeda gal" to "Andromeda Galaxy", "caldwell 14" to "Caldwell 14",
            "c 14" to "Caldwell 14", "lmc" to "LMC", "nubecula" to "Nubecula Major", "orion neb" to "Orion Nebula",
            "great orion" to "Great Orion Nebula", "ncp" to "NCP", "celestial north" to "Celestial North Pole",
            "sagittarius a" to "Sagittarius A*", "jupiter" to "Jupiter",
        )
        for ((query, text) in expectations) {
            assertEquals(text, catalog.search(query).firstOrNull()?.matchedText, query)
        }
    }

    // MARK: Limits and unusual input

    @Test
    fun testLimits() {
        val catalog = catalog()
        assertTrue(catalog.search("a", limit = 0).isEmpty())
        assertTrue(catalog.search("a", limit = -1).isEmpty())
        assertTrue(catalog.search("a", limit = Int.MIN_VALUE).isEmpty())
        assertEquals(1, catalog.search("a", limit = 1).size)
        assertEquals(7, catalog.search("a", limit = 7).size)
        assertEquals(50, catalog.search("a").size, "the default limit is 50")
        val all = catalog.search("a", limit = Int.MAX_VALUE)
        assertTrue(all.size > 1000)
        assertTrue(all.size <= catalog.objects.size)
        assertEquals(all.size, ids(all).toSet().size, "an object is listed once")
        assertEquals(ids(all).take(10), ids(catalog.search("a", limit = 10)))
        assertEquals(ids(all).take(50), ids(catalog.search("a")))
        assertEquals(1, catalog.search("sirius", limit = 1).size)
    }

    @Test
    fun testQueriesWithoutLettersOrDigitsFindNothing() {
        val catalog = catalog()
        for (query in listOf(
            "", " ", "   ", "\n", "\t\r\n", "!!!", "---", "...", "—", "*", "🔭", "😀😀😀", "星空", "\u0000", "​",
            "́", "Орион", "﷽", "∞", "‮", "()[]{}", "'", "`", "’",
        )) {
            assertTrue(catalog.search(query).isEmpty(), "${query.codePoints().toArray().toList()}")
        }
    }

    @Test
    fun testOnlyTheFirstSixtyFourCharactersOfAQueryAreUsed() {
        val catalog = catalog()
        val padded = "sirius" + " ".repeat(100) + "garbage that is ignored"
        assertEquals("star.hip32349", catalog.search(padded).firstOrNull()?.obj?.id)
        assertEquals(CatalogSearchResult.MatchKind.EXACT, catalog.search(padded).firstOrNull()?.kind)
        val withinLimit = "sirius" + " ".repeat(40) + "garbage"
        assertTrue(catalog.search(withinLimit).isEmpty() || catalog.search(withinLimit).firstOrNull()?.kind != CatalogSearchResult.MatchKind.EXACT)
        assertEquals(64, Catalog.maximumQueryLength)
        val atLimit = "m".repeat(64)
        assertTrue(catalog.search(atLimit).isEmpty())
        assertEquals(0, catalog.search("x".repeat(100_000)).size)
    }

    @Test
    fun testGarbageInputNeverCrashes() {
        val catalog = catalog()
        val hostile = listOf(
            "a".repeat(1_000_000), "é".repeat(500_000),
            "🔭".repeat(100_000), "́".repeat(100_000),
            "a" + "́".repeat(100_000), "Ω".repeat(100_000),
            "9".repeat(100_000), "0".repeat(100_000),
            "m31 ".repeat(50_000), "\u0000\u0000\u0000", "%s%s%n%x", "\\", "'; DROP TABLE stars; --",
            "<script>alert(1)</script>", "../../etc/passwd", "‮‭RTL", "👨‍👩‍👧‍👦", "🇺🇸🇯🇵", "�￾￿",
            "􏿿", "", "퟿", "ǅ", "ŉ", "ﬃ", "①②③", "ⅷ", "㎏", "㌀", "Ǆ", "Ꜳꜳ", "ᴀʙᴄ", "𝐒𝐢𝐫𝐢𝐮𝐬", "𝕊𝕚𝕣𝕚𝕦𝕤",
            "ａｂｃ１２３", "ＡＢＣ", "～", "α" + "ͅ".repeat(1000),
        )
        for (text in hostile) {
            val results = catalog.search(text, limit = 5)
            assertTrue(results.size <= 5)
            assertRankingOrder(results, text.take(20))
        }
        // Mathematical alphanumerics decompose to plain letters.
        assertEquals("star.hip32349", catalog.search("𝐒𝐢𝐫𝐢𝐮𝐬").firstOrNull()?.obj?.id)
        assertEquals("star.hip32349", catalog.search("𝕊𝕚𝕣𝕚𝕦𝕤").firstOrNull()?.obj?.id)
    }

    @Test
    fun testRandomTextNeverCrashes() {
        val catalog = catalog()
        var state = 0x9E37_79B9_7F4A_7C15uL.toLong()
        fun next(): Long {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return state ushr 33
        }

        val ranges: List<IntRange> = listOf(
            0x20..0x7E, 0x0..0x1F, 0xA0..0x17F, 0x370..0x3FF, 0x2000..0x206F, 0x2070..0x209F,
            0x300..0x36F, 0xFF00..0xFFEF, 0x1F300..0x1F64F, 0x4E00..0x4E80, 0x30..0x39, 0x41..0x5A,
        )
        for (iteration in 0 until 300) {
            val text = StringBuilder()
            for (character in 0 until (next() % 40).toInt()) {
                val range = ranges[(next() % ranges.size).toInt()]
                val value = range.first + (next() % (range.last - range.first + 1).toLong()).toInt()
                // A scalar value is never a surrogate; Swift's Unicode.Scalar(_:) fails for those.
                if (value in 0xD800..0xDFFF) continue
                text.appendCodePoint(value)
            }
            val results = catalog.search(text.toString(), limit = 8)
            assertTrue(results.size <= 8)
            assertRankingOrder(results, text.toString())
        }
    }

    @Test
    fun testSearchesAreRepeatableAndSafeToRunConcurrently() {
        val catalog = catalog()
        val queries = listOf("sirius", "m31", "alpha cen", "ngc 7", "orion", "andgal", "hr 24", "a")
        val expected = queries.map { query -> catalog.search(query, limit = 20).map { it.obj.id } }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = List(64) { iteration ->
                Callable {
                    val index = iteration % queries.size
                    index to catalog.search(queries[index], limit = 20).map { it.obj.id }
                }
            }
            val results = pool.invokeAll(tasks).map { it.get() }
            assertEquals(64, results.size)
            for ((index, ids) in results) {
                assertEquals(expected[index], ids, queries[index])
            }
        } finally {
            pool.shutdown()
        }
    }
}
