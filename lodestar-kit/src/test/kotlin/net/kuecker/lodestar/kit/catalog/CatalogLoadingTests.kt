package net.kuecker.lodestar.kit.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.foundation.*
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// MARK: - Shared support for the catalog tests

/** The bundled catalog, loaded once, and the raw records of the data files for independent checks. */
internal object CatalogFixture {
    private val loaded: Result<Catalog> = runCatching { Catalog.loadBundled() }

    fun catalog(): Catalog = loaded.getOrThrow()

    /** A star record as it appears in `stars.json`. */
    @Serializable
    class RawStar(
        val hip: Int? = null,
        val hr: Int? = null,
        val hd: Int? = null,
        val hyg: Int? = null,
        val n: String? = null,
        val b: String? = null,
        val f: Int? = null,
        val c: String,
        val ra: Double,
        val de: Double,
        val pa: Double? = null,
        val pd: Double? = null,
        val m: Double,
        val ci: Double? = null,
        val sp: String? = null,
        val d: Double? = null,
    )

    /** A deep-sky record as it appears in `dsos.json`. */
    @Serializable
    class RawDeepSky(
        val id: String,
        val m: Int? = null,
        val ngc: List<Int>? = null,
        val ic: List<Int>? = null,
        val names: List<String>? = null,
        val t: String,
        val ra: Double,
        val de: Double,
        val c: String,
        val vm: Double? = null,
        val bm: Double? = null,
        val maj: Double? = null,
        val min: Double? = null,
        val pa: Double? = null,
        val h: String? = null,
        val sb: Double? = null,
        val cat: List<String>? = null,
        val p: Int? = null,
    )

    @Serializable
    private class StarFile(val stars: List<RawStar>)

    @Serializable
    private class DeepSkyFile(val objects: List<RawDeepSky>)

    private val json = Json { ignoreUnknownKeys = true }

    private val stars: List<RawStar> by lazy {
        json.decodeFromString(StarFile.serializer(), String(Catalog.bundledData(CatalogFile.STARS), Charsets.UTF_8)).stars
    }

    private val deepSky: List<RawDeepSky> by lazy {
        json.decodeFromString(
            DeepSkyFile.serializer(),
            String(Catalog.bundledData(CatalogFile.DEEP_SKY_OBJECTS), Charsets.UTF_8),
        ).objects
    }

    fun rawStars(): List<RawStar> = stars

    fun rawDeepSky(): List<RawDeepSky> = deepSky

    /** A catalog of the given JSON records (strings of one JSON object each) in the real file layout. */
    fun syntheticCatalog(
        stars: List<String> = listOf(defaultStar),
        deepSky: List<String> = listOf(defaultDeepSky),
        constellations: List<String> = listOf(defaultConstellation),
        milkyWayLevels: List<String> = listOf(defaultMilkyWayLevel),
    ): Catalog = Catalog(
        starsData = file("stars", stars),
        dsosData = file("objects", deepSky),
        constellationsData = file("constellations", constellations),
        milkyWayData = file("levels", milkyWayLevels),
    )

    fun file(key: String, records: List<String>, schema: String = "1"): ByteArray =
        """{"schema":$schema,"$key":[${records.joinToString(",")}]}""".toByteArray(Charsets.UTF_8)

    const val defaultStar = """{"hip":1,"n":"Test Star","c":"Ori","ra":10.0,"de":20.0,"m":2.5}"""
    const val defaultDeepSky = """{"id":"M1","m":1,"t":"SNR","ra":83.6,"de":22.0,"c":"Tau","vm":8.4,"maj":8,"min":4}"""
    const val defaultConstellation =
        """{"abbr":"Ori","name":"Orion","genitive":"Orionis","label":[83.0,5.0],"lines":[[[80.0,0.0],[85.0,10.0]]]}"""
    const val defaultMilkyWayLevel = """{"level":1,"polygons":[[[10,10],[20,10],[20,20]]],"holes":[]}"""

    /** A synthetic star record with the given extra JSON members. */
    fun star(hip: Int, name: String? = null, magnitude: Double = 5.0, extra: String = ""): String {
        val nameMember = name?.let { """"n":"$it",""" } ?: ""
        return """{"hip":$hip,$nameMember"c":"Ori","ra":10.0,"de":20.0,"m":$magnitude$extra}"""
    }
}

// MARK: - Loading the bundled catalog

class CatalogLoadingTests {
    @Test
    fun testLoadsTheBundledFilesWithoutRejectingAnyRecord() {
        val catalog = CatalogFixture.catalog()
        val report = catalog.loadReport
        assertTrue(report.isClean, "${report.issues}")
        assertEquals(0, report.rejectedCount)
        assertTrue(report.issues.isEmpty())
        assertEquals(CatalogFixture.rawStars().size, report.stars.accepted)
        assertEquals(CatalogFixture.rawDeepSky().size, report.deepSkyObjects.accepted + report.deepSkyObjects.merged)
        assertEquals(88, report.constellations.accepted)
        assertEquals(0, report.constellations.rejected)
        assertEquals(0, report.stars.merged)
    }

    @Test
    fun testBundledCountsMatchTheDataFiles() {
        val catalog = CatalogFixture.catalog()
        val stars = CatalogFixture.rawStars()
        val deepSky = CatalogFixture.rawDeepSky()
        assertEquals(9001, stars.size)
        assertEquals(2441, deepSky.size)
        assertEquals(stars.size, catalog.renderStars.size)
        assertEquals(88, catalog.constellations.size)
        assertEquals(5, catalog.milkyWay.levels.size)
        val merged = catalog.loadReport.deepSkyObjects.merged
        assertEquals(9 + 3 + (deepSky.size - merged) + stars.size, catalog.objects.size)
        assertEquals(catalog.objects.filter { it.isListed }.size, catalog.listedObjects.size)
        assertEquals(catalog.objects.filter { it.isListed }.map { it.id }, catalog.listedObjects.map { it.id })
    }

    @Test
    fun testObjectsAreOrderedSolarSystemSpecialDeepSkyThenStarsByMagnitude() {
        val catalog = CatalogFixture.catalog()
        val objects = catalog.objects
        assertEquals(
            listOf(
                "sun", "moon", "planet.mercury", "planet.venus", "planet.mars",
                "planet.jupiter", "planet.saturn", "planet.uranus", "planet.neptune",
            ),
            objects.take(9).map { it.id },
        )
        assertEquals(
            listOf("special.galacticCenter", "special.ncp", "special.scp"),
            objects.subList(9, 12).map { it.id },
        )
        val firstStar = objects.indexOfFirst { it.id.startsWith("star.") }
        assertTrue(firstStar >= 0)
        assertTrue(objects.subList(12, firstStar).all { it.id.startsWith("dso.") })
        assertTrue(objects.subList(firstStar, objects.size).all { it.id.startsWith("star.") })
        assertEquals("dso.M1", objects[12].id, "deep-sky objects keep the file order: Messier first")
        val magnitudes = objects.subList(firstStar, objects.size).mapNotNull { it.magnitude }
        assertEquals(objects.size - firstStar, magnitudes.size)
        assertEquals(magnitudes, magnitudes.sorted())
        assertEquals("star.hip32349", objects[firstStar].id, "Sirius is the brightest star")
    }

    @Test
    fun testIdentifiersAreUniqueWellFormedAndResolvable() {
        val catalog = CatalogFixture.catalog()
        val identifiers = catalog.objects.map { it.id }
        assertEquals(identifiers.size, identifiers.toSet().size, "duplicate identifiers")
        val prefixes = listOf("dso.", "star.hip", "star.hr", "star.hyg", "planet.", "special.")
        for (obj in catalog.objects) {
            assertFalse(obj.id.isEmpty())
            assertTrue(obj.id == "sun" || obj.id == "moon" || prefixes.any { obj.id.startsWith(it) }, obj.id)
            assertEquals(obj.id, catalog.objectWithID(obj.id)?.id)
        }
        assertNull(catalog.objectWithID(""))
        assertNull(catalog.objectWithID("dso.M999"))
        assertNull(catalog.objectWithID("custom.00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun testAllMessierObjectsArePresent() {
        val catalog = CatalogFixture.catalog()
        for (number in 1..110) {
            val obj = assertNotNull(catalog.objectWithID("dso.M$number"), "M$number is missing")
            assertEquals("M $number", obj.designations.firstOrNull())
            assertTrue(obj.isListed)
            assertNotNull(obj.constellation)
        }
        assertEquals("Andromeda Galaxy", catalog.objectWithID("dso.M31")?.name)
        assertEquals(listOf("M 31", "NGC 224"), catalog.objectWithID("dso.M31")?.designations)
        assertEquals("And", catalog.objectWithID("dso.M31")?.constellation)
        assertEquals("Andromeda", catalog.objectWithID("dso.M31")?.constellationName)
        assertEquals("M 2", catalog.objectWithID("dso.M2")?.name, "objects without a common name are named by catalog number")
        assertEquals(listOf("M 102", "NGC 5866"), catalog.objectWithID("dso.M102")?.designations)
        assertEquals("North America Nebula", catalog.objectWithID("dso.NGC7000")?.name)
        assertEquals(listOf("IC 434"), catalog.objectWithID("dso.IC434")?.designations)
    }

    @Test
    fun testEveryFixedPositionIsFiniteAndInRange() {
        val catalog = CatalogFixture.catalog()
        var fixed = 0
        for (obj in catalog.objects) {
            when (val position = obj.position) {
                is ObjectPosition.Fixed -> {
                    val coordinate = position.coordinate
                    val motion = position.properMotion
                    fixed += 1
                    assertTrue(
                        coordinate.rightAscension.isFinite() && coordinate.rightAscension >= 0.0 &&
                            coordinate.rightAscension < 360.0,
                        obj.id,
                    )
                    assertTrue(
                        coordinate.declination.isFinite() && coordinate.declination >= -90.0 &&
                            coordinate.declination <= 90.0,
                        obj.id,
                    )
                    assertEquals(coordinate, obj.j2000)
                    if (motion != null) {
                        assertTrue(
                            motion.raMilliarcsecondsPerYear.isFinite() && motion.decMilliarcsecondsPerYear.isFinite(),
                            obj.id,
                        )
                    }
                }
                is ObjectPosition.SolarSystem, is ObjectPosition.CelestialPole -> assertNull(obj.j2000)
            }
            obj.magnitude?.let { assertTrue(it.isFinite(), obj.id) }
            obj.colorIndex?.let { assertTrue(it.isFinite(), obj.id) }
            obj.distanceLightYears?.let { assertTrue(it.isFinite() && it > 0, obj.id) }
            obj.angularSize?.let { assertTrue(it.majorArcminutes > 0 && it.majorArcminutes.isFinite(), obj.id) }
            assertFalse(obj.name.isEmpty())
        }
        assertEquals(catalog.objects.size - 9 - 2, fixed)
    }

    @Test
    fun testStarsFollowTheNamingAndListingRules() {
        val catalog = CatalogFixture.catalog()
        for (star in CatalogFixture.rawStars()) {
            val id = star.hip?.let { "star.hip$it" } ?: star.hr?.let { "star.hr$it" } ?: "star.hyg${star.hyg ?: 0}"
            val obj = assertNotNull(catalog.objectWithID(id), id)
            var expectedName = star.n
            if (expectedName == null && star.b != null) expectedName = "${star.b} ${star.c}"
            if (expectedName == null && star.f != null) expectedName = "${star.f} ${star.c}"
            if (expectedName == null && star.hr != null) expectedName = "HR ${star.hr}"
            if (expectedName == null && star.hd != null) expectedName = "HD ${star.hd}"
            if (expectedName == null && star.hip != null) expectedName = "HIP ${star.hip}"
            assertEquals(expectedName, obj.name, id)
            assertEquals(
                if (star.hip == CatalogFactory.quasarHipparcosNumber) ObjectCategory.OTHER else ObjectCategory.STAR,
                obj.category,
                id,
            )
            assertEquals(star.n != null || star.m <= 3.5, obj.isListed, id)
            assertEquals(star.m, obj.magnitude)
            assertEquals(star.c, obj.constellation)
            assertNotNull(obj.constellationName, id)
            assertEquals(star.ci, obj.colorIndex)
            assertEquals(star.sp, obj.spectralType)
            assertEquals(EquatorialCoordinate(rightAscension = star.ra, declination = star.de), obj.j2000)
            assertEquals(star.d == null, obj.distanceLightYears == null)
            val parsecs = star.d
            val lightYears = obj.distanceLightYears
            if (parsecs != null && lightYears != null) {
                assertEquals(parsecs * 3.2615637771674, lightYears, 1e-6)
            }
            val position = obj.position as? ObjectPosition.Fixed ?: fail(id)
            val motion = position.properMotion
            assertEquals(star.pa, motion?.raMilliarcsecondsPerYear)
            assertEquals(star.pd, motion?.decMilliarcsecondsPerYear)
            // Designations come in a fixed order: Bayer, Flamsteed, then catalog numbers.
            val expectedDesignations = ArrayList<String>()
            if (star.b != null) expectedDesignations.add("${star.b} ${star.c}")
            if (star.f != null) expectedDesignations.add("${star.f} ${star.c}")
            val numbers = listOf("HR ${star.hr ?: 0}", "HD ${star.hd ?: 0}", "HIP ${star.hip ?: 0}")
            val present = listOf(star.hr != null, star.hd != null, star.hip != null)
            for ((number, isPresent) in numbers.zip(present)) {
                assertEquals(isPresent, obj.designations.contains(number), "$id $number")
            }
            for (designation in expectedDesignations) {
                assertTrue(obj.designations.contains(designation), "$id $designation")
            }
            val catalogNumbers = obj.designations.filter {
                it.startsWith("HR ") || it.startsWith("HD ") || it.startsWith("HIP ")
            }
            assertEquals(numbers.filterIndexed { index, _ -> present[index] }, catalogNumbers, id)
        }
    }

    @Test
    fun testBayerDesignationsListTheLongFormWithTheGenitive() {
        val catalog = CatalogFixture.catalog()
        assertEquals(
            listOf("α CMa", "Alpha Canis Majoris", "9 CMa", "HR 2491", "HD 48915", "HIP 32349"),
            catalog.objectWithID("star.hip32349")?.designations,
        )
        assertEquals(
            listOf("α¹ Cen", "Alpha¹ Centauri", "HR 5459", "HD 128620", "HIP 71683"),
            catalog.objectWithID("star.hip71683")?.designations,
        )
        assertEquals(
            listOf("α UMi", "Alpha Ursae Minoris", "1 UMi"),
            catalog.objectWithID("star.hip11767")?.designations?.take(3),
        )
        assertEquals("Betelgeuse", catalog.objectWithID("star.hip27989")?.name)
        assertEquals(
            listOf("α Ori", "Alpha Orionis"),
            catalog.objectWithID("star.hip27989")?.designations?.take(2),
        )
    }

    @Test
    fun testStarsWithoutAProperNameAreNamedByTheirDesignation() {
        val catalog = CatalogFixture.catalog()
        val stars = CatalogFixture.rawStars()
        val bayer = assertNotNull(stars.firstOrNull { it.n == null && it.b != null })
        val flamsteed = assertNotNull(stars.firstOrNull { it.n == null && it.b == null && it.f != null })
        val harvard = assertNotNull(stars.firstOrNull { it.n == null && it.b == null && it.f == null && it.hr != null })
        val hipparcos = assertNotNull(
            stars.firstOrNull { it.n == null && it.b == null && it.f == null && it.hr == null && it.hip != null },
        )

        fun name(star: CatalogFixture.RawStar): String? =
            catalog.objectWithID(star.hip?.let { "star.hip$it" } ?: "star.hr${star.hr ?: 0}")?.name

        assertEquals("${bayer.b ?: ""} ${bayer.c}", name(bayer))
        assertEquals("${flamsteed.f ?: 0} ${flamsteed.c}", name(flamsteed))
        assertEquals("HR ${harvard.hr ?: 0}", name(harvard))
        assertEquals("HIP ${hipparcos.hip ?: 0}", name(hipparcos))
        assertEquals(true, catalog.objectWithID("star.hip3801")?.name?.endsWith(" Cas"))
    }

    @Test
    fun testStarsWithOnlyAHygRowAreIdentifiedByIt() {
        val catalog = CatalogFixture.catalog()
        val rows = CatalogFixture.rawStars().filter { it.hip == null && it.hr == null }
        assertEquals(7, rows.size)
        for (row in rows) {
            val hyg = assertNotNull(row.hyg)
            val obj = assertNotNull(catalog.objectWithID("star.hyg$hyg"))
            assertEquals(row.n, obj.name)
            assertTrue(obj.designations.contains("HYG $hyg"))
            assertTrue(obj.isListed, "named stars are listed")
        }
        assertEquals("Wolf 359", catalog.objectWithID("star.hyg118720")?.name)
    }

    @Test
    fun testDeepSkyObjectsFollowTheNamingAndCategoryRules() {
        val catalog = CatalogFixture.catalog()
        var merged = 0
        for (record in CatalogFixture.rawDeepSky()) {
            val obj = catalog.objectWithID("dso.${record.id}")
            if (obj == null) {
                merged += 1
                assertTrue(record.t == "*" || record.t == "**", "${record.id} vanished")
                assertNull(record.m)
                continue
            }
            assertEquals((record.p ?: 3) <= 3, obj.isListed, record.id)
            assertEquals(record.p ?: 3, obj.prominence.rawValue, record.id)
            assertEquals(
                obj.category == ObjectCategory.OTHER || obj.category == ObjectCategory.MILKY_WAY,
                obj.group == ObjectGroup.SPECIAL,
            )
            assertEquals(expectedCategory(record.t), obj.category, record.id)
            assertEquals(record.vm ?: record.bm, obj.magnitude, record.id)
            assertEquals(record.c, obj.constellation)
            assertNotNull(obj.constellationName, record.id)
            assertEquals(EquatorialCoordinate(rightAscension = record.ra, declination = record.de), obj.j2000)
            assertEquals((record.names ?: emptyList()).drop(1), obj.alternateNames, record.id)
            assertNull(obj.colorIndex)
            assertNull(obj.spectralType)
            assertNull(obj.distanceLightYears)
            val numberNames = listOfNotNull(record.m?.let { "M $it" }) +
                (record.ngc ?: emptyList()).map { "NGC $it" } + (record.ic ?: emptyList()).map { "IC $it" }
            val firstName = record.names?.firstOrNull()
            if (firstName != null) {
                assertEquals(firstName, obj.name, record.id)
            } else if (numberNames.isNotEmpty()) {
                assertEquals(numberNames.first(), obj.name, record.id)
            }
            assertEquals(numberNames, obj.designations.take(numberNames.size), record.id)
            assertFalse(obj.designations.isEmpty())
            assertEquals(record.maj, obj.angularSize?.majorArcminutes, record.id)
            assertEquals(if (record.maj == null) null else record.min, obj.angularSize?.minorArcminutes, record.id)
            val angle = record.pa
            if (record.maj != null && angle != null) {
                assertEquals(angle % 180.0, obj.angularSize?.positionAngle, record.id)
            }
        }
        assertEquals(catalog.loadReport.deepSkyObjects.merged, merged)
        assertEquals(2, merged)
    }

    @Test
    fun testOtherCatalogDesignationsAreSpelledOut() {
        val catalog = CatalogFixture.catalog()
        assertEquals(listOf("Caldwell 14"), catalog.objectWithID("dso.C14")?.designations)
        assertEquals(listOf("Caldwell 9", "Sh2-155"), catalog.objectWithID("dso.C9")?.designations)
        assertEquals(listOf("Caldwell 41", "Melotte 25", "Collinder 50"), catalog.objectWithID("dso.C41")?.designations)
        assertEquals(listOf("Melotte 111"), catalog.objectWithID("dso.Mel111")?.designations)
        assertEquals(listOf("Barnard 33"), catalog.objectWithID("dso.B33")?.designations)
        assertEquals(listOf("ESO 56-115"), catalog.objectWithID("dso.ESO56-115")?.designations)
        assertEquals(listOf("Collinder 399"), catalog.objectWithID("dso.Cl399")?.designations)
        assertEquals(listOf("Harvard 5", "Collinder 258"), catalog.objectWithID("dso.H5")?.designations)
        assertEquals(listOf("IC 359A"), catalog.objectWithID("dso.IC359A")?.designations)
        assertEquals(listOf("IC 2431 NED02"), catalog.objectWithID("dso.IC2431_NED02")?.designations)
        assertEquals(listOf("HCG 92"), catalog.objectWithID("dso.HCG92")?.designations)
        assertEquals(listOf("PGC 143"), catalog.objectWithID("dso.PGC143")?.designations)
        assertEquals(listOf("UGC 5373"), catalog.objectWithID("dso.UGC5373")?.designations)
        assertEquals(listOf("MWSC 3171"), catalog.objectWithID("dso.MWSC3171")?.designations)
        assertEquals(listOf("NGC 2239", "NGC 2244", "Caldwell 50"), catalog.objectWithID("dso.NGC2239")?.designations)
        assertEquals(listOf("NGC 281", "IC 11", "Sh2-184"), catalog.objectWithID("dso.NGC281")?.designations)
        assertEquals(listOf("M 8", "NGC 6523", "NGC 6533", "Sh2-25"), catalog.objectWithID("dso.M8")?.designations)
    }

    @Test
    fun testListingRulesForEveryKindOfObject() {
        val catalog = CatalogFixture.catalog()
        for (obj in catalog.objects.filter { !it.id.startsWith("star.") && !it.isDeepSky }) {
            assertTrue(obj.isListed, "${obj.id} is a solar-system or special object")
        }
        for (obj in catalog.deepSkyObjects) {
            assertEquals(obj.prominence <= Prominence.NOTABLE, obj.isListed, "${obj.id} is listed unless it is obscure")
        }
        assertTrue(catalog.deepSkyObjects.any { !it.isListed }, "the obscure deep-sky objects are not listed")
        val stars = CatalogFixture.rawStars()

        fun objectOf(star: CatalogFixture.RawStar): CelestialObject =
            assertNotNull(catalog.objectWithID(star.hip?.let { "star.hip$it" } ?: "star.hr${star.hr ?: 0}"))

        val faintUnnamed = assertNotNull(stars.firstOrNull { it.n == null && it.m > 3.5 })
        val faintNamed = assertNotNull(stars.firstOrNull { it.n != null && it.m > 6 })
        val brightUnnamed = assertNotNull(stars.firstOrNull { it.n == null && it.m <= 3.5 })
        val boundary = assertNotNull(stars.firstOrNull { it.n == null && it.m == 3.5 })
        assertFalse(objectOf(faintUnnamed).isListed, "faint unnamed stars are only found by search")
        assertTrue(objectOf(faintNamed).isListed, "named stars are always listed")
        assertTrue(objectOf(brightUnnamed).isListed)
        assertTrue(objectOf(boundary).isListed, "magnitude 3.5 is still listed")
        val listedStars = catalog.listedObjects.filter { it.id.startsWith("star.") }
        assertEquals(stars.count { it.n != null || it.m <= 3.5 }, listedStars.size)
        assertEquals(
            9 + 3 + catalog.deepSkyObjects.count { it.isListed } + listedStars.size,
            catalog.listedObjects.size,
        )
    }

    @Test
    fun testCategoryMappingCoversEveryOpenNGCType() {
        val expected: Map<String, ObjectCategory> = mapOf(
            "G" to ObjectCategory.GALAXY, "GPair" to ObjectCategory.GALAXY_GROUP,
            "GTrpl" to ObjectCategory.GALAXY_GROUP, "GGroup" to ObjectCategory.GALAXY_GROUP,
            "OCl" to ObjectCategory.OPEN_CLUSTER, "GCl" to ObjectCategory.GLOBULAR_CLUSTER,
            "Cl+N" to ObjectCategory.CLUSTER_WITH_NEBULA, "PN" to ObjectCategory.PLANETARY_NEBULA,
            "HII" to ObjectCategory.EMISSION_NEBULA, "EmN" to ObjectCategory.EMISSION_NEBULA,
            "RfN" to ObjectCategory.REFLECTION_NEBULA, "DrkN" to ObjectCategory.DARK_NEBULA,
            "Neb" to ObjectCategory.NEBULA, "*Ass" to ObjectCategory.STAR_ASSOCIATION,
            "**" to ObjectCategory.DOUBLE_STAR, "*" to ObjectCategory.STAR,
            "SNR" to ObjectCategory.SUPERNOVA_REMNANT,
            "Other" to ObjectCategory.OTHER, "Nova" to ObjectCategory.OTHER, "NonEx" to ObjectCategory.OTHER,
            "Dup" to ObjectCategory.OTHER, "" to ObjectCategory.OTHER, "g" to ObjectCategory.OTHER,
            "unknown" to ObjectCategory.OTHER,
        )
        for ((code, category) in expected) {
            assertEquals(category, ObjectCategory.fromOpenNGCType(code), "type code $code")
        }
        // Every type code in the data file is one the mapping knows about, and the counts line up.
        val catalog = CatalogFixture.catalog()
        val records = CatalogFixture.rawDeepSky()
        val rawCounts = HashMap<String, Int>()
        for (record in records) rawCounts[record.t] = (rawCounts[record.t] ?: 0) + 1
        assertTrue(expected.keys.containsAll(rawCounts.keys), "${rawCounts.keys - expected.keys}")
        val deepSky = catalog.objects.filter { it.id.startsWith("dso.") }
        for (category in expected.values.toSet()) {
            val inData = records.filter { ObjectCategory.fromOpenNGCType(it.t) == category }
            val mergedAway = inData.count { catalog.objectWithID("dso.${it.id}") == null }
            assertEquals(inData.size, deepSky.count { it.category == category } + mergedAway, "$category")
        }
        assertEquals(rawCounts["Other"] ?: 0, deepSky.count { it.category == ObjectCategory.OTHER })
        assertEquals(
            listOf("dso.M73", "dso.NGC405"),
            deepSky.filter { it.category == ObjectCategory.OTHER }.map { it.id }.sorted(),
        )
        assertEquals(
            setOf(
                ObjectCategory.GALAXY, ObjectCategory.GALAXY_GROUP, ObjectCategory.OPEN_CLUSTER,
                ObjectCategory.GLOBULAR_CLUSTER, ObjectCategory.CLUSTER_WITH_NEBULA, ObjectCategory.PLANETARY_NEBULA,
                ObjectCategory.EMISSION_NEBULA, ObjectCategory.REFLECTION_NEBULA, ObjectCategory.DARK_NEBULA,
                ObjectCategory.NEBULA, ObjectCategory.SUPERNOVA_REMNANT, ObjectCategory.STAR_ASSOCIATION,
                ObjectCategory.DOUBLE_STAR, ObjectCategory.STAR, ObjectCategory.OTHER,
            ),
            deepSky.map { it.category }.toSet(),
        )
    }

    @Test
    fun testBuiltInSolarSystemObjects() {
        val catalog = CatalogFixture.catalog()
        class Expected(val id: String, val name: String, val category: ObjectCategory, val body: SolarSystemBody)

        val expected = listOf(
            Expected("sun", "Sun", ObjectCategory.SUN, SolarSystemBody.SUN),
            Expected("moon", "Moon", ObjectCategory.MOON, SolarSystemBody.MOON),
            Expected("planet.mercury", "Mercury", ObjectCategory.PLANET, SolarSystemBody.MERCURY),
            Expected("planet.venus", "Venus", ObjectCategory.PLANET, SolarSystemBody.VENUS),
            Expected("planet.mars", "Mars", ObjectCategory.PLANET, SolarSystemBody.MARS),
            Expected("planet.jupiter", "Jupiter", ObjectCategory.PLANET, SolarSystemBody.JUPITER),
            Expected("planet.saturn", "Saturn", ObjectCategory.PLANET, SolarSystemBody.SATURN),
            Expected("planet.uranus", "Uranus", ObjectCategory.PLANET, SolarSystemBody.URANUS),
            Expected("planet.neptune", "Neptune", ObjectCategory.PLANET, SolarSystemBody.NEPTUNE),
        )
        for (item in expected) {
            val obj = assertNotNull(catalog.objectWithID(item.id), item.id)
            assertEquals(item.name, obj.name)
            assertEquals(item.category, obj.category)
            assertEquals(ObjectPosition.SolarSystem(item.body), obj.position)
            assertTrue(obj.isSolarSystem)
            assertTrue(obj.isListed)
            assertEquals(ObjectGroup.SOLAR_SYSTEM, obj.group)
            assertNull(obj.magnitude, "brightness of solar-system bodies changes")
            assertNull(obj.j2000)
            assertNull(obj.constellation)
            assertEquals(item.category.displayName, obj.subtitle)
        }
        assertTrue(assertNotNull(catalog.objectWithID("sun")).notes?.contains("solar filter") ?: false)
        assertNull(catalog.objectWithID("planet.jupiter")?.notes)
    }

    @Test
    fun testBuiltInSpecialObjects() {
        val catalog = CatalogFixture.catalog()
        val center = assertNotNull(catalog.objectWithID("special.galacticCenter"))
        assertEquals(ObjectCategory.MILKY_WAY, center.category)
        assertEquals(ObjectGroup.SPECIAL, center.group)
        assertEquals("Sgr", center.constellation)
        assertEquals("Sagittarius", center.constellationName)
        val position = center.position as? ObjectPosition.Fixed ?: fail("fixed position expected")
        assertNull(position.properMotion, "fixed position expected")
        val coordinate = position.coordinate
        assertEquals(17 + 45.0 / 60 + 40.04 / 3600, coordinate.rightAscensionHours, 1e-9)
        assertEquals(-(29 + 28.1 / 3600), coordinate.declination, 1e-9)
        // Sagittarius A* is at galactic longitude 359.94° and latitude −0.05°.
        val galactic = CoordinateTransforms.equatorialJ2000ToGalactic(coordinate)
        assertEquals(-0.06, AstroMath.normalizeSignedDegrees(galactic.longitude), 0.05)
        assertEquals(-0.05, galactic.latitude, 0.05)

        val north = assertNotNull(catalog.objectWithID("special.ncp"))
        val south = assertNotNull(catalog.objectWithID("special.scp"))
        assertEquals(ObjectPosition.CelestialPole(north = true), north.position)
        assertEquals(ObjectPosition.CelestialPole(north = false), south.position)
        assertEquals(ObjectCategory.CELESTIAL_POLE, north.category)
        assertEquals("UMi", north.constellation)
        assertEquals("Oct", south.constellation)
        assertEquals("North Celestial Pole", north.name)
        assertEquals("South Celestial Pole", south.name)
        assertFalse(north.isSolarSystem)
        assertNull(north.j2000)
        assertEquals(3, catalog.objects(ObjectGroup.SPECIAL).count { it.id.startsWith("special.") })
    }

    @Test
    fun testAcronymsOfDeepSkyObjectsAlsoWorkAsIdentifiers() {
        val catalog = CatalogFixture.catalog()
        assertEquals("dso.ESO56-115", catalog.objectWithID("dso.LMC")?.id)
        assertEquals("Large Magellanic Cloud", catalog.objectWithID("dso.LMC")?.name)
        assertEquals("dso.NGC292", catalog.objectWithID("dso.SMC")?.id)
        assertEquals("Large Magellanic Cloud", catalog.objectWithID("dso.ESO56-115")?.name)
        assertEquals(listOf("Nubecula Major", "LMC"), catalog.objectWithID("dso.ESO56-115")?.alternateNames)
        assertNull(catalog.objectWithID("dso.WLM"))
        assertNull(catalog.objectWithID("dso.lmc"), "identifiers are case-sensitive")
    }

    @Test
    fun testPlainStarRecordsOfTheDeepSkyDataAreMergedIntoTheirStars() {
        val catalog = CatalogFixture.catalog()
        assertNull(catalog.objectWithID("dso.NGC1990"))
        assertNull(catalog.objectWithID("dso.IC1318"))
        assertNotNull(catalog.objectWithID("dso.NGC7114"), "a nova without a star in the catalog stays")
        assertNotNull(catalog.objectWithID("dso.M40"), "Messier objects are never merged")
        val alnilam = assertNotNull(catalog.objectWithID("star.hip26311"))
        assertEquals("Alnilam", alnilam.name)
        assertTrue(alnilam.designations.contains("NGC 1990"))
        assertEquals("NGC 1990", alnilam.designations.last())
        val sadr = assertNotNull(catalog.objectWithID("star.hip100453"))
        assertTrue(sadr.designations.contains("IC 1318"))
        assertEquals(2, catalog.loadReport.deepSkyObjects.merged)
        assertEquals(listOf("star.hip26311"), catalog.search("alnilam").map { it.obj.id })
        assertEquals("star.hip100453", catalog.search("ic 1318").firstOrNull()?.obj?.id)
        assertEquals("NGC 1990", catalog.search("ngc 1990").firstOrNull()?.matchedText)
    }

    @Test
    fun testPopularNamesAreAlternateNames() {
        val catalog = CatalogFixture.catalog()
        val polaris = assertNotNull(catalog.objectWithID("star.hip11767"))
        assertEquals("Polaris", polaris.name)
        assertEquals(listOf("North Star", "Pole Star", "Lodestar"), polaris.alternateNames)
        assertEquals(listOf("Dog Star"), catalog.objectWithID("star.hip32349")?.alternateNames)
        assertEquals(listOf("Sol"), catalog.objectWithID("sun")?.alternateNames)
        assertEquals(listOf("Luna"), catalog.objectWithID("moon")?.alternateNames)
        assertEquals("Pole Star", catalog.search("pole star").firstOrNull()?.matchedText)
        assertEquals(CatalogSearchResult.MatchKind.EXACT, catalog.search("north star").firstOrNull()?.kind)
        // Only the stars of the table have alternate names: the names of the records merged into Alnilam and
        // Sadr are names they answer to already.
        val withAlternates = catalog.objects
            .filter { it.id.startsWith("star.") && it.alternateNames.isNotEmpty() }.map { it.id }
        assertEquals(setOf("star.hip11767", "star.hip32349"), withAlternates.toSet())
        assertEquals(setOf(11767, 32349), CatalogFactory.popularNames.keys)
    }

    @Test
    fun testTheQuasarOfTheStarDataIsNotAStar() {
        val catalog = CatalogFixture.catalog()
        val quasar = assertNotNull(catalog.objectWithID("star.hip60936"))
        assertEquals(60936, CatalogFactory.quasarHipparcosNumber)
        assertEquals("3C 273", quasar.name)
        assertEquals(ObjectCategory.OTHER, quasar.category)
        assertEquals(ObjectGroup.SPECIAL, quasar.group)
        assertTrue(quasar.isListed, "it has a proper name")
        assertEquals("Virgo", quasar.constellationName)
        assertEquals(12.88, quasar.magnitude)
        assertTrue(assertNotNull(quasar.notes).contains("quasar"))
        assertEquals("Other · Virgo · mag 12.9", quasar.subtitle)
        assertEquals("star.hip60936", catalog.search("3c 273").firstOrNull()?.obj?.id)
        assertEquals("star.hip60936", catalog.search("hip 60936").firstOrNull()?.obj?.id)
        assertFalse(catalog.objects(ObjectGroup.STARS).any { it.id == "star.hip60936" })
        assertTrue(catalog.objects(ObjectGroup.SPECIAL).any { it.id == "star.hip60936" })
        assertEquals(
            listOf("star.hip60936"),
            catalog.objects.filter { it.id.startsWith("star.") && it.category != ObjectCategory.STAR }.map { it.id },
            "every other entry of the star data is a star",
        )
        assertTrue(catalog.renderStars.any { it.id == "star.hip60936" }, "it is still a point of light on the sky")
    }

    @Test
    fun testStarsMayShareTheirProperName() {
        val catalog = CatalogFixture.catalog()
        val exact = catalog.search("p Eridani").filter { it.kind == CatalogSearchResult.MatchKind.EXACT }
        assertEquals(listOf("star.hip7751", "star.hr487"), exact.map { it.obj.id }, "two stars, brighter first")
        assertEquals(listOf("p Eridani", "p Eridani"), exact.map { it.obj.name })
        assertNotEquals(exact[0].obj, exact[1].obj)
    }

    @Test
    fun testConstellationsAreCompleteAndFindableByAbbreviation() {
        val catalog = CatalogFixture.catalog()
        assertEquals(88, catalog.constellations.size)
        assertEquals(88, catalog.constellations.map { it.abbreviation }.toSet().size)
        for (constellation in catalog.constellations) {
            assertEquals(constellation.abbreviation, constellation.id)
            assertEquals(3, constellation.abbreviation.length)
            assertFalse(constellation.name.isEmpty())
            assertFalse(constellation.genitive.isEmpty())
            assertFalse(constellation.lines.isEmpty(), constellation.name)
            assertTrue(constellation.lines.all { it.size >= 2 })
            assertTrue(constellation.labelPosition.rightAscension >= 0.0 && constellation.labelPosition.rightAscension < 360.0)
            assertEquals(constellation, catalog.constellation(constellation.abbreviation))
            assertEquals(constellation, catalog.constellation(constellation.abbreviation.lowercase()))
            assertEquals(constellation, catalog.constellation(constellation.abbreviation.uppercase()))
        }
        val orion = assertNotNull(catalog.constellation("Ori"))
        assertEquals("Orion", orion.name)
        assertEquals("Orionis", orion.genitive)
        assertEquals("Canis Major", catalog.constellation("cma")?.name)
        assertEquals("Coronae Australis", catalog.constellation("CrA")?.genitive)
        assertEquals("Crucis", catalog.constellation("Cru")?.genitive)
        assertNull(catalog.constellation("Xyz"))
        assertNull(catalog.constellation(""))

        fun folded(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

        val names = catalog.constellations.map { it.name }
        assertEquals(names, names.sortedWith { a, b -> CatalogText.compare(folded(a), folded(b)) })
        // Every object's constellation resolves to a name.
        for (obj in catalog.objects) {
            val abbreviation = obj.constellation
            if (abbreviation != null) {
                assertEquals(catalog.constellation(abbreviation)?.name, obj.constellationName, obj.id)
            }
        }
    }

    @Test
    fun testMilkyWayOutlineIsWellFormed() {
        val catalog = CatalogFixture.catalog()
        val levels = catalog.milkyWay.levels
        assertEquals(listOf(1, 2, 3, 4, 5), levels.map { it.level })
        assertEquals(1, levels[0].polygons.size)
        assertEquals(8, levels[0].holes.size)
        assertEquals(catalog.loadReport.milkyWayRings.accepted, levels.map { it.polygons.size + it.holes.size }.sum())
        for (level in levels) {
            assertFalse(level.polygons.isEmpty())
            for (ring in level.polygons + level.holes) {
                assertTrue(ring.size >= 3)
                assertTrue(ring.all { it.rightAscension.isFinite() && it.declination.isFinite() })
            }
        }
    }

    @Test
    fun testRenderStarsAreSortedByMagnitudeAndConsistentWithTheObjects() {
        val catalog = CatalogFixture.catalog()
        val stars = catalog.renderStars
        assertEquals(stars.map { it.magnitude }, stars.map { it.magnitude }.sorted())
        assertEquals("star.hip32349", stars.firstOrNull()?.id)
        assertEquals(stars.size, stars.map { it.id }.toSet().size)
        for (star in stars) {
            assertEquals(1.0, star.unitVector.length, 1e-12)
            assertTrue(star.magnitude.isFinite())
        }
        for (star in stars.take(500)) {
            val obj = assertNotNull(catalog.objectWithID(star.id))
            assertEquals(star.magnitude, obj.magnitude)
            assertEquals(star.colorIndex, obj.colorIndex)
            val coordinate = assertNotNull(obj.j2000)
            assertTrue(coordinate.unitVector.angle(star.unitVector) < 1e-9)
        }
        val named = stars.filter { it.name != null }
        assertEquals(464, named.size)
        assertEquals("Sirius", stars.firstOrNull()?.name)
        assertEquals(463, named.mapNotNull { it.name }.toSet().size, "p Eridani is named twice in the data")
    }

    @Test
    fun testGroupsPartitionTheListedObjects() {
        val catalog = CatalogFixture.catalog()
        val seen = ArrayList<String>()
        for (group in ObjectGroup.entries) {
            val objects = catalog.objects(group)
            assertFalse(objects.isEmpty(), "$group")
            assertTrue(objects.all { it.isListed && it.group == group }, "$group")
            assertEquals(
                catalog.sorted(objects, CatalogSortOrder.NAME).map { it.id },
                objects.map { it.id },
                "$group is sorted by name",
            )
            seen += objects.map { it.id }
        }
        assertEquals(catalog.listedObjects.map { it.id }.toSet(), seen.toSet())
        assertEquals(catalog.listedObjects.size, seen.size)
        assertEquals(9, catalog.objects(ObjectGroup.SOLAR_SYSTEM).size)
        assertTrue(catalog.objects(ObjectGroup.GALAXIES).any { it.id == "dso.M31" })
        assertTrue(catalog.objects(ObjectGroup.NEBULAE).any { it.id == "dso.M42" })
        assertTrue(catalog.objects(ObjectGroup.CLUSTERS).any { it.id == "dso.M45" })
        assertTrue(catalog.objects(ObjectGroup.STARS).any { it.id == "star.hip32349" })
    }

    private fun expectedCategory(code: String): ObjectCategory = when (code) {
        "G" -> ObjectCategory.GALAXY
        "GPair", "GTrpl", "GGroup" -> ObjectCategory.GALAXY_GROUP
        "OCl" -> ObjectCategory.OPEN_CLUSTER
        "GCl" -> ObjectCategory.GLOBULAR_CLUSTER
        "Cl+N" -> ObjectCategory.CLUSTER_WITH_NEBULA
        "PN" -> ObjectCategory.PLANETARY_NEBULA
        "HII", "EmN" -> ObjectCategory.EMISSION_NEBULA
        "RfN" -> ObjectCategory.REFLECTION_NEBULA
        "DrkN" -> ObjectCategory.DARK_NEBULA
        "Neb" -> ObjectCategory.NEBULA
        "SNR" -> ObjectCategory.SUPERNOVA_REMNANT
        "*Ass" -> ObjectCategory.STAR_ASSOCIATION
        "**" -> ObjectCategory.DOUBLE_STAR
        "*" -> ObjectCategory.STAR
        else -> ObjectCategory.OTHER
    }
}
