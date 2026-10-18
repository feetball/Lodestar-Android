package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.catalog.CatalogSmallBodyFixture as Fixture
import net.kuecker.lodestar.kit.catalog.CatalogSearchResult.MatchKind
import net.kuecker.lodestar.kit.comets.SmallBody
import net.kuecker.lodestar.kit.comets.SmallBodyElements
import net.kuecker.lodestar.kit.comets.SmallBodyKind
import net.kuecker.lodestar.kit.comets.SyntheticOrbits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Search, listing, ordering and the runtime add/replace of comets and asteroids in a catalog. */
class CatalogSmallBodySearchTests {
    private val bundled: Catalog = CatalogFixture.catalog()
    private val catalog: Catalog = Fixture.catalog()

    private fun ids(results: List<CatalogSearchResult>): List<String> = results.map { it.id }

    private fun first(query: String): CatalogSearchResult = assertNotNull(catalog.search(query, limit = 5).firstOrNull(), query)

    // MARK: Search by designation and name

    @Test
    fun testCometsAreFoundByDesignationAndName() {
        val byName = first("Tsuchinshan")
        assertEquals("comet.C/2023 A3", byName.id)
        assertEquals(MatchKind.PREFIX, byName.kind)
        assertEquals("Tsuchinshan-ATLAS", byName.matchedText)

        val byDesignation = first("C/2023 A3")
        assertEquals("comet.C/2023 A3", byDesignation.id)
        assertEquals(MatchKind.EXACT, byDesignation.kind)
        assertEquals("C/2023 A3", byDesignation.matchedText)
        for (spelling in listOf("c/2023 a3", "C2023A3", "c 2023 a3", "2023 A3")) {
            assertEquals("comet.C/2023 A3", first(spelling).id, spelling)
        }
        assertEquals(MatchKind.WORD_PREFIX, first("2023 A3").kind)

        val title = first("C/2023 A3 (Tsuchinshan-ATLAS)")
        assertEquals("comet.C/2023 A3", title.id)
        assertEquals(MatchKind.EXACT, title.kind)
        assertEquals("C/2023 A3 (Tsuchinshan-ATLAS)", title.matchedText)

        // "Atlas" is also a star of the Pleiades, an exact match, which ranks above the later word of the comet's name.
        val atlas = catalog.search("atlas")
        assertEquals("star.hip17847", atlas.first().id)
        assertEquals(MatchKind.EXACT, atlas.first().kind)
        val comet = assertNotNull(atlas.firstOrNull { it.id == "comet.C/2023 A3" })
        assertEquals(MatchKind.WORD_PREFIX, comet.kind, "a later word of the name")

        assertEquals("comet.12P", first("12P").id)
        assertEquals(MatchKind.EXACT, first("12P").kind)
        assertEquals("comet.12P", first("Pons-Brooks").id)
        assertEquals("comet.12P", first("pons brooks").id)
        assertEquals("comet.12P", first("12P/Pons-Brooks").id)
        assertEquals("comet.1P", first("Halley").id)
        assertEquals("comet.C/1995 O1", first("Hale-Bopp").id)
        assertEquals("comet.2P", first("encke").id)
    }

    @Test
    fun testAsteroidsAreFoundByNumberAndName() {
        val byName = first("Vesta")
        assertEquals("asteroid.4", byName.id)
        assertEquals(MatchKind.EXACT, byName.kind)
        assertEquals("Vesta", byName.matchedText)

        val byTitle = first("4 Vesta")
        assertEquals("asteroid.4", byTitle.id)
        assertEquals(MatchKind.EXACT, byTitle.kind)
        assertEquals("(4) Vesta", byTitle.matchedText)
        assertEquals("asteroid.4", first("(4) Vesta").id)

        assertEquals("asteroid.1", first("Ceres").id)
        assertEquals("asteroid.1", first("1 Ceres").id)
        assertEquals("asteroid.433", first("Eros").id)
        assertEquals("asteroid.2024 YR4", first("2024 YR4").id)
        assertEquals(MatchKind.EXACT, first("2024 YR4").kind)
        assertEquals("asteroid.2024 YR4", first("yr4").id)

        // The number alone is an exact match of the designation "(4)".
        val byNumber = first("4")
        assertEquals("asteroid.4", byNumber.id)
        assertEquals(MatchKind.EXACT, byNumber.kind)
        assertEquals("(4)", byNumber.matchedText)
    }

    @Test
    fun testTheObjectsOfAResultAreTheCatalogObjects() {
        for (query in listOf("Vesta", "Tsuchinshan", "12P", "yr4")) {
            val result = first(query)
            assertEquals(catalog.objectWithID(result.id), result.obj, query)
            assertTrue(result.obj in catalog.smallBodies, query)
            assertTrue(result.obj in catalog.objects, query)
        }
        assertEquals(CelestialObject.fromSmallBody(Fixture.vesta), catalog.objectWithID("asteroid.4"))
        assertEquals(Fixture.vesta, catalog.objectWithID("asteroid.4")?.smallBody)
        assertNull(catalog.objectWithID("asteroid.99999"))
        assertNull(bundled.objectWithID("asteroid.4"), "the bundled catalog has no small bodies")
        assertTrue(bundled.search("Vesta").none { it.obj.category == ObjectCategory.ASTEROID })
    }

    @Test
    fun testBundledSearchResultsAreUnchangedByAddedBodies() {
        for (query in listOf("sirius", "m31", "alpha cma", "vega", "orion", "jupiter", "andgal", "zzzz", "m 31", "α¹ cen", "mars")) {
            assertEquals(bundled.search(query), catalog.search(query), query)
            assertEquals(bundled.search(query, limit = 3), catalog.search(query, limit = 3), query)
        }
    }

    @Test
    fun testResultsOfBothSourcesAreMergedByKindThenRank() {
        // Three exact matches of "Sirius": the star, a showpiece comet fainter than it and one brighter than any star.
        val dim = Fixture.comet("C/2040 S1", "Sirius", 3.0)
        val brilliant = Fixture.comet("C/2040 S2", "Sirius", -5.0)
        val merged = bundled.withSmallBodies(listOf(dim, brilliant))
        val results = merged.search("sirius")
        assertEquals(listOf("comet.C/2040 S2", "star.hip32349", "comet.C/2040 S1"), ids(results).take(3))
        assertTrue(results.take(3).all { it.kind == MatchKind.EXACT })
        // The same result list as if all objects were in one index: nothing is lost with a small limit.
        assertEquals(results.take(2), merged.search("sirius", limit = 2))
        assertEquals(results.take(1), merged.search("sirius", limit = 1))
        assertTrue(merged.search("sirius", limit = 0).isEmpty())
        assertTrue(merged.search("sirius", limit = -3).isEmpty())
        // A better kind of match beats a better object: "Siriusfoo" is only a prefix of the comet's name.
        val prefixOnly = bundled.withSmallBodies(listOf(Fixture.comet("C/2040 S3", "Siriusfoo", -9.0)))
        assertEquals(listOf("star.hip32349", "comet.C/2040 S3"), ids(prefixOnly.search("sirius")).take(2))
    }

    @Test
    fun testFuzzySuggestionsOnlyAppearWithoutBetterMatchesInEitherSource() {
        // "ponbro" is only a loose match of "Pons-Brooks" (and of nothing in the bundled data): suggested.
        val loose = catalog.search("ponbro")
        assertEquals(listOf("comet.12P"), ids(loose))
        assertEquals(MatchKind.FUZZY, loose.single().kind)
        // "andgal" is only a loose match of the Andromeda Galaxy; an exact match among the small bodies hides it.
        assertTrue(bundled.search("andgal").all { it.kind == MatchKind.FUZZY })
        val named = bundled.withSmallBodies(listOf(Fixture.comet("C/2041 A1", "Andgal", 9.0)))
        assertEquals(listOf("comet.C/2041 A1"), ids(named.search("andgal")))
        // And the other way round: a loose match among the small bodies does not appear next to a real match.
        val looseComet = bundled.withSmallBodies(listOf(Fixture.comet("C/2041 B1", "Mxx 3y 1z", 9.0)))
        assertEquals(bundled.search("m31"), looseComet.search("m31"))
        assertEquals(listOf("comet.C/2041 B1"), ids(looseComet.search("mxx3y1z")))
    }

    @Test
    fun testLimitsAreConsistentWithTheFullResultAndTheOrderIsTotal() {
        val many = bundled.withSmallBodies(SyntheticOrbits(5).bodies(400) + Fixture.comets + Fixture.asteroids)
        for (query in listOf("a", "m", "c", "ve", "1", "s1", "c/s", "orion", "andgal", "pons", "4", "(s", "xyzzy")) {
            val full = many.search(query, limit = Int.MAX_VALUE)
            assertEquals(full.size, full.map { it.id }.toSet().size, "$query: every object once")
            for (limit in listOf(1, 2, 3, 7, 50, 200)) {
                assertEquals(full.take(limit), many.search(query, limit), "$query limit $limit")
            }
            // Better kinds first; equal kinds in the order of listed, prominence, brightness estimate, name, identifier.
            for ((lhs, rhs) in full.zipWithNext()) {
                val kinds = lhs.kind.rawValue.compareTo(rhs.kind.rawValue)
                val ranks = CatalogOrdering.searchComparator.compare(CatalogOrdering.item(lhs.obj), CatalogOrdering.item(rhs.obj))
                assertTrue(kinds < 0 || (kinds == 0 && ranks < 0), "$query: ${lhs.id} before ${rhs.id}")
            }
        }
    }

    // MARK: Listing and ordering

    @Test
    fun testSolarSystemListsIncludeTheListedSmallBodies() {
        val group = catalog.objects(ObjectGroup.SOLAR_SYSTEM)
        val listedSmall = catalog.smallBodies.filter { it.isListed }
        assertEquals(9 + listedSmall.size, group.size)
        assertTrue(group.containsAll(bundled.objects(ObjectGroup.SOLAR_SYSTEM)))
        assertTrue(group.containsAll(listedSmall))
        assertFalse(group.any { it.id == "asteroid.2024 YR4" }, "an obscure asteroid is not listed")
        assertEquals(group, catalog.sorted(group, CatalogSortOrder.NAME), "sorted by name")
        // The other groups are the bundled ones, shared.
        for (other in ObjectGroup.entries.filter { it != ObjectGroup.SOLAR_SYSTEM }) {
            assertSame(bundled.objects(other), catalog.objects(other), other.rawValue)
        }
        assertTrue(catalog.listedObjects.containsAll(listedSmall))
        assertFalse(catalog.listedObjects.any { it.id == "asteroid.2024 YR4" })
        assertEquals(bundled.listedObjects.size + listedSmall.size, catalog.listedObjects.size)
        assertEquals(bundled.listedObjects, catalog.listedObjects.take(bundled.listedObjects.size))
    }

    @Test
    fun testSmallBodiesComeAfterTheBundledObjectsBrightestFirst() {
        assertEquals(bundled.objects.size + 9, catalog.objects.size)
        assertEquals(bundled.objects, catalog.objects.take(bundled.objects.size))
        assertEquals(catalog.smallBodies, catalog.objects.drop(bundled.objects.size))
        val estimates = catalog.smallBodies.map { it.rankingMagnitude }
        assertEquals(estimates.sorted(), estimates)
        assertEquals("comet.C/1995 O1", catalog.smallBodies.first().id)
        assertEquals("asteroid.2024 YR4", catalog.smallBodies.last().id)
        assertEquals(
            listOf("asteroid.4", "asteroid.1"),
            catalog.sorted(listOf(Fixture.ceres, Fixture.vesta).map { CelestialObject.fromSmallBody(it) }, CatalogSortOrder.MAGNITUDE).map { it.id },
        )
        assertEquals(
            listOf("asteroid.1", "asteroid.4"),
            catalog.sorted(listOf(Fixture.vesta, Fixture.ceres).map { CelestialObject.fromSmallBody(it) }, CatalogSortOrder.NAME).map { it.id },
        )
    }

    @Test
    fun testOrderDoesNotDependOnTheOrderOfTheInput() {
        val all = Fixture.comets + Fixture.asteroids
        val forward = bundled.withSmallBodies(all)
        val backward = bundled.withSmallBodies(all.reversed())
        assertEquals(forward.smallBodies, backward.smallBodies)
        assertEquals(forward.objects(ObjectGroup.SOLAR_SYSTEM), backward.objects(ObjectGroup.SOLAR_SYSTEM))
        for (query in listOf("a", "c/20", "vesta", "p", "1")) {
            assertEquals(forward.search(query, limit = 20), backward.search(query, limit = 20), query)
        }
    }

    // MARK: Add and replace at run time

    @Test
    fun testWithSmallBodiesLeavesTheOriginalAndSharesTheBundledData() {
        val objectCount = bundled.objects.size
        val added = bundled.withSmallBodies(listOf(Fixture.tsuchinshanAtlas, Fixture.ceres))
        assertEquals(objectCount, bundled.objects.size)
        assertTrue(bundled.smallBodies.isEmpty())
        assertNull(bundled.objectWithID("comet.C/2023 A3"))
        assertEquals(setOf("comet.C/2023 A3", "asteroid.1"), added.smallBodies.map { it.id }.toSet())
        assertEquals(objectCount + 2, added.objects.size)
        assertNotNull(added.objectWithID("comet.C/2023 A3"))
        assertNotNull(added.objectWithID("star.hip32349"), "the bundled objects are still found")
        assertSame(bundled.renderStars, added.renderStars)
        assertSame(bundled.deepSkyObjects, added.deepSkyObjects)
        assertSame(bundled.constellations, added.constellations)
        assertSame(bundled.milkyWay, added.milkyWay)
        assertSame(bundled.loadReport, added.loadReport)
        assertSame(bundled.deepSkyObjects(Prominence.SHOWPIECE), added.deepSkyObjects(Prominence.SHOWPIECE))
        assertSame(bundled.objects(DeepSkyCatalogue.MESSIER), added.objects(DeepSkyCatalogue.MESSIER))
        assertSame(bundled.searchIndex, added.searchIndex)
        assertSame(bundled.constellation("CMa"), added.constellation("CMa"))
    }

    @Test
    fun testWithSmallBodiesReplacesTheEarlierSet() {
        val first = bundled.withSmallBodies(listOf(Fixture.tsuchinshanAtlas, Fixture.ceres))
        val second = first.withSmallBodies(listOf(Fixture.vesta))
        assertEquals(listOf("asteroid.4"), second.smallBodies.map { it.id })
        assertNull(second.objectWithID("comet.C/2023 A3"))
        assertNull(second.objectWithID("asteroid.1"))
        assertTrue(second.search("Tsuchinshan").none { it.obj.category == ObjectCategory.COMET })
        assertEquals("asteroid.4", second.search("vesta").first().id)
        // The first catalog still has its set.
        assertEquals(2, first.smallBodies.size)
        assertEquals("comet.C/2023 A3", first.search("Tsuchinshan").first().id)
        // An empty list removes them all; on a catalog without any, it is the catalog itself.
        val none = second.withSmallBodies(emptyList())
        assertTrue(none.smallBodies.isEmpty())
        assertEquals(bundled.objects, none.objects)
        assertSame(bundled, bundled.withSmallBodies(emptyList()))
        assertEquals(bundled.search("vesta"), none.search("vesta"))
    }

    @Test
    fun testAddingSmallBodiesKeepsTheOthersAndReplacesTheSameIdentifier() {
        val first = bundled.withSmallBodies(listOf(Fixture.tsuchinshanAtlas, Fixture.ceres))
        val fresherCeres = withNewerEpoch(Fixture.ceres)
        val second = first.addingSmallBodies(listOf(Fixture.vesta, fresherCeres))
        assertEquals(setOf("comet.C/2023 A3", "asteroid.1", "asteroid.4"), second.smallBodies.map { it.id }.toSet())
        assertEquals(3, second.smallBodies.size)
        assertEquals(fresherCeres, second.objectWithID("asteroid.1")?.smallBody, "the new elements replace the old ones")
        assertEquals(Fixture.ceres, first.objectWithID("asteroid.1")?.smallBody, "the first catalog is unchanged")
        assertEquals(1, second.search("Ceres").count { it.id == "asteroid.1" }, "no duplicate in the results")
        assertEquals(second.smallBodies, bundled.withSmallBodies(listOf(Fixture.tsuchinshanAtlas, Fixture.vesta, fresherCeres)).smallBodies)
        // Adding nothing changes nothing.
        assertEquals(first.smallBodies, first.addingSmallBodies(emptyList()).smallBodies)
        assertEquals(bundled.objects, bundled.addingSmallBodies(emptyList()).objects)
    }

    @Test
    fun testTheLastBodyWithAnIdentifierWins() {
        val fresherCeres = withNewerEpoch(Fixture.ceres)
        val catalogWithBoth = bundled.withSmallBodies(listOf(Fixture.ceres, Fixture.vesta, fresherCeres, Fixture.ceres))
        assertEquals(2, catalogWithBoth.smallBodies.size)
        assertEquals(Fixture.ceres, catalogWithBoth.objectWithID("asteroid.1")?.smallBody, "the last one is the original again")
        val other = bundled.withSmallBodies(listOf(Fixture.ceres, fresherCeres))
        assertEquals(fresherCeres, other.objectWithID("asteroid.1")?.smallBody)
        assertEquals(1, other.smallBodies.size)
    }

    @Test
    fun testACometAndAnAsteroidWithTheSameNumberDoNotClash() {
        // The identifier includes the kind: "comet.1P" and "asteroid.1" never meet, and 1P is not Ceres.
        val both = bundled.withSmallBodies(listOf(Fixture.halley, Fixture.ceres))
        assertEquals("comet.1P", both.search("1P").first().id)
        assertEquals("asteroid.1", both.search("1 Ceres").first().id)
        assertEquals(2, both.smallBodies.size)
    }

    @Test
    fun testAtMostTheMaximumNumberOfBodiesIsTaken() {
        assertEquals(100_000, Catalog.maximumSmallBodyCount)
        val synthetic = SyntheticOrbits(20_261_018).bodies(Catalog.maximumSmallBodyCount + 50)
        assertEquals(Catalog.maximumSmallBodyCount + 50, synthetic.size)
        val many = bundled.withSmallBodies(synthetic)
        assertEquals(Catalog.maximumSmallBodyCount, many.smallBodies.size)
        assertEquals(synthetic.take(Catalog.maximumSmallBodyCount).map { it.id }.toSet(), many.smallBodies.map { it.id }.toSet())
        assertNull(many.objectWithID(synthetic.last().id))
        assertNotNull(many.objectWithID(synthetic.first().id))
        // A body beyond the limit that replaces one within it is still taken.
        val replaced = SyntheticOrbits(1).bodies(3)
        assertEquals(3, bundled.withSmallBodies(replaced + replaced).smallBodies.size)
    }

    @Test
    fun testNameAndIdentifierLookupsOfSyntheticBodies() {
        val bodies = SyntheticOrbits(77).bodies(300)
        val many = bundled.withSmallBodies(bodies)
        assertEquals(300, many.smallBodies.size)
        for (body in bodies.take(40)) {
            val obj = assertNotNull(many.objectWithID(body.id), body.id)
            assertEquals(body, obj.smallBody)
            assertEquals(body.id, many.search(body.title, limit = 3).first().id, body.id)
            assertEquals(if (body.kind == SmallBodyKind.COMET) ObjectCategory.COMET else ObjectCategory.ASTEROID, obj.category)
        }
    }

    // MARK: Helpers

    /** The same asteroid with another epoch: a refreshed element set that has the identifier of the old one. */
    private fun withNewerEpoch(body: SmallBody): SmallBody {
        val orbit = assertNotNull(
            SmallBodyElements.fromMeanAnomaly(
                semiMajorAxisAU = assertNotNull(body.orbit.semiMajorAxisAU),
                eccentricity = body.orbit.eccentricity, inclination = body.orbit.inclination,
                argumentOfPerihelion = body.orbit.argumentOfPerihelion,
                longitudeOfAscendingNode = body.orbit.longitudeOfAscendingNode, meanAnomaly = 100.0,
                epochJulianDayTT = body.orbit.epochJulianDayTT + 200.0,
            ),
        )
        return assertNotNull(SmallBody.create(body.designation, body.name, body.kind, body.number, orbit, body.photometry))
    }
}
