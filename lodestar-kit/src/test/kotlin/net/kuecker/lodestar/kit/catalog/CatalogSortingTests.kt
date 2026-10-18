package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.foundation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CatalogSortingTests {
    private fun deepSky(id: String, name: String? = null, magnitude: Double? = null, number: Int? = null): String {
        val names = name?.let { ""","names":["$it"]""" } ?: ""
        val magnitudeMember = magnitude?.let { ""","vm":$it""" } ?: ""
        val messier = number?.let { ""","m":$it""" } ?: ""
        return """{"id":"$id"$messier$names,"t":"G","ra":10.0,"de":20.0,"c":"And"$magnitudeMember}"""
    }

    private fun <T> shuffled(items: List<T>, seed: Long): List<T> {
        var state = seed
        val result = items.toMutableList()
        for (index in result.size - 1 downTo 1) {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            java.util.Collections.swap(result, index, ((state ushr 33) % (index + 1)).toInt())
        }
        return result
    }

    // MARK: By name

    @Test
    fun testNamesSortAlphabeticallyWithNumbersInNumericOrder() {
        val records = ArrayList<String>()
        for (number in 1..12) {
            records.add(deepSky(id = "M$number", number = number))
        }
        records += listOf(deepSky(id = "NGC9"), deepSky(id = "NGC10"), deepSky(id = "NGC100"))
        val catalog = CatalogFixture.syntheticCatalog(deepSky = records)
        val objects = catalog.objects.filter { it.id.startsWith("dso.") }
        val expected = (1..12).map { "M $it" } + listOf("NGC 9", "NGC 10", "NGC 100")
        assertEquals(expected, catalog.sorted(objects, CatalogSortOrder.NAME).map { it.name })
        for (seed in 1..5) {
            assertEquals(
                expected, catalog.sorted(shuffled(objects, seed.toLong()), CatalogSortOrder.NAME).map { it.name },
                "seed $seed",
            )
        }
    }

    @Test
    fun testNamesSortIgnoringCaseDiacriticsAndGreekLetters() {
        val stars = listOf(
            CatalogFixture.star(hip = 1, name = "Zed"), CatalogFixture.star(hip = 2, name = "Éclair"),
            CatalogFixture.star(hip = 3, name = "Eagle"), CatalogFixture.star(hip = 4, name = "Ångström"),
            CatalogFixture.star(hip = 5, name = "fomalhaut"), CatalogFixture.star(hip = 6, extra = ""","b":"γ""""),
            CatalogFixture.star(hip = 7, name = "Hadar"), CatalogFixture.star(hip = 8, name = "the-end"),
        )
        val catalog = CatalogFixture.syntheticCatalog(stars = stars)
        val objects = catalog.objects.filter { it.id.startsWith("star.") }
        assertEquals(
            listOf("Ångström", "Eagle", "Éclair", "fomalhaut", "γ Ori", "Hadar", "the-end", "Zed"),
            catalog.sorted(objects, CatalogSortOrder.NAME).map { it.name },
        )
    }

    @Test
    fun testNamesWithoutLatinLettersOrDigitsComeFirstInCharacterOrder() {
        val catalog = CatalogFixture.catalog()
        val position = EquatorialCoordinate(rightAscension = 1.0, declination = 1.0)
        val targets = listOf("Орион", "日本", "Zed", "Андромеда", "★★", "M 5").map { CelestialObject.custom(it, position) }
        val expected = listOf("Андромеда", "Орион", "★★", "日本", "M 5", "Zed")
        assertEquals(expected, catalog.sorted(targets, CatalogSortOrder.NAME).map { it.name })
        assertEquals(expected, catalog.sorted(targets.reversed(), CatalogSortOrder.NAME).map { it.name })
        assertEquals(expected, catalog.sorted(shuffled(targets, 3), CatalogSortOrder.NAME).map { it.name })
    }

    @Test
    fun testEqualNamesAreOrderedByIdentifier() {
        val stars = listOf(
            CatalogFixture.star(hip = 30, name = "Twin"), CatalogFixture.star(hip = 20, name = "twin"),
            CatalogFixture.star(hip = 10, name = "TWIN"), CatalogFixture.star(hip = 100, name = "Twin "),
        )
        val catalog = CatalogFixture.syntheticCatalog(stars = stars)
        val objects = catalog.objects.filter { it.id.startsWith("star.") }
        val expected = listOf("star.hip10", "star.hip100", "star.hip20", "star.hip30")
        assertEquals(expected, catalog.sorted(objects, CatalogSortOrder.NAME).map { it.id })
        assertEquals(expected, catalog.sorted(objects.reversed(), CatalogSortOrder.NAME).map { it.id })
        assertEquals(expected, catalog.sorted(shuffled(objects, 7), CatalogSortOrder.NAME).map { it.id })
    }

    // MARK: By magnitude

    @Test
    fun testMagnitudeOrderPutsBrightObjectsFirstAndUnknownMagnitudesLast() {
        val stars = listOf(
            CatalogFixture.star(hip = 1, name = "Dim", magnitude = 6.2),
            CatalogFixture.star(hip = 2, name = "Bright", magnitude = -0.5),
            CatalogFixture.star(hip = 3, name = "Middle", magnitude = 3.1),
            CatalogFixture.star(hip = 4, name = "Twin B", magnitude = 3.1),
            CatalogFixture.star(hip = 5, name = "Twin A", magnitude = 3.1),
        )
        val dsos = listOf(
            deepSky(id = "NGC1", name = "Faint Smudge", magnitude = 11.0), deepSky(id = "NGC2", name = "Unknown Cloud"),
            deepSky(id = "NGC3", name = "Another Unknown"), deepSky(id = "NGC4", name = "Negative", magnitude = -2.5),
        )
        val catalog = CatalogFixture.syntheticCatalog(stars = stars, deepSky = dsos)
        val sorted = catalog.sorted(catalog.objects, CatalogSortOrder.MAGNITUDE)
        val names = sorted.map { it.name }
        // Solar-system bodies are placed by a typical peak magnitude, among the other objects.
        assertEquals(
            listOf(
                "Sun", "Moon", "Venus", "Jupiter", "Negative", "Mars", "Mercury", "Bright", "Saturn", "Middle", "Twin A",
                "Twin B", "Uranus", "Dim", "Neptune", "Faint Smudge",
            ),
            names.take(16),
        )
        // Objects without a magnitude come last, ordered by name.
        val tail = sorted.takeLast(5)
        assertTrue(tail.all { it.rankingMagnitude == Double.POSITIVE_INFINITY })
        assertEquals(
            listOf("Another Unknown", "Galactic Center", "North Celestial Pole", "South Celestial Pole", "Unknown Cloud"),
            tail.map { it.name },
        )
        for (seed in 1..5) {
            assertEquals(
                sorted.map { it.id },
                catalog.sorted(shuffled(catalog.objects, seed.toLong()), CatalogSortOrder.MAGNITUDE).map { it.id },
                "seed $seed",
            )
        }
    }

    @Test
    fun testSolarSystemBodiesSortByTypicalBrightness() {
        val catalog = CatalogFixture.catalog()
        assertEquals(
            listOf(
                "sun", "moon", "planet.venus", "planet.jupiter", "planet.mars", "planet.mercury", "planet.saturn",
                "planet.uranus", "planet.neptune",
            ),
            catalog.sorted(catalog.objects(ObjectGroup.SOLAR_SYSTEM), CatalogSortOrder.MAGNITUDE).map { it.id },
        )
        assertEquals(
            listOf(
                "planet.jupiter", "planet.mars", "planet.mercury", "moon", "planet.neptune", "planet.saturn", "sun",
                "planet.uranus", "planet.venus",
            ),
            catalog.sorted(catalog.objects(ObjectGroup.SOLAR_SYSTEM), CatalogSortOrder.NAME).map { it.id },
        )
    }

    @Test
    fun testSortingTheWholeCatalog() {
        val catalog = CatalogFixture.catalog()
        val byMagnitude = catalog.sorted(catalog.objects, CatalogSortOrder.MAGNITUDE)
        assertEquals(catalog.objects.size, byMagnitude.size)
        assertEquals(catalog.objects.map { it.id }.toSet(), byMagnitude.map { it.id }.toSet())
        val rankings = byMagnitude.map { it.rankingMagnitude }
        assertEquals(rankings.sorted(), rankings)
        assertEquals(listOf("sun", "moon", "planet.venus"), byMagnitude.take(3).map { it.id })
        val unknown = byMagnitude.dropWhile { it.rankingMagnitude != Double.POSITIVE_INFINITY }
        assertFalse(unknown.isEmpty())
        assertTrue(unknown.all { it.magnitude == null && !it.isSolarSystem }, "unknown magnitudes are contiguous at the end")
        assertTrue(unknown.any { it.id == "special.ncp" })

        val byName = catalog.sorted(catalog.listedObjects, CatalogSortOrder.NAME)
        val keys = byName.map { SearchText.key(it.name).bytes }
        for ((first, second) in keys.zip(keys.drop(1))) {
            assertFalse(SearchText.naturalPrecedes(second, first))
        }
        assertEquals(byName.map { it.id }, catalog.sorted(byName, CatalogSortOrder.NAME).map { it.id }, "sorting is idempotent")
        assertEquals(byName.map { it.id }, catalog.sorted(byName.reversed(), CatalogSortOrder.NAME).map { it.id })
        assertEquals(byName.map { it.id }, catalog.sorted(shuffled(byName, 99), CatalogSortOrder.NAME).map { it.id })
    }

    @Test
    fun testSortingEdgeCases() {
        val catalog = CatalogFixture.catalog()
        assertTrue(catalog.sorted(emptyList(), CatalogSortOrder.NAME).isEmpty())
        assertTrue(catalog.sorted(emptyList(), CatalogSortOrder.MAGNITUDE).isEmpty())
        val single = listOf(assertNotNull(catalog.objectWithID("dso.M31")))
        assertEquals(single, catalog.sorted(single, CatalogSortOrder.NAME))
        assertEquals(single + single, catalog.sorted(single + single, CatalogSortOrder.MAGNITUDE), "duplicates are kept")
        val custom = CelestialObject.custom("Alpha custom", EquatorialCoordinate(rightAscension = 1.0, declination = 1.0))
        val mixed = catalog.sorted(
            listOf(assertNotNull(catalog.objectWithID("dso.M31")), custom, assertNotNull(catalog.objectWithID("sun"))),
            CatalogSortOrder.NAME,
        )
        assertEquals(listOf("Alpha custom", "Andromeda Galaxy", "Sun"), mixed.map { it.name }, "objects that are not in the catalog sort too")
        assertEquals(listOf(CatalogSortOrder.NAME, CatalogSortOrder.MAGNITUDE), CatalogSortOrder.entries)
    }
}
