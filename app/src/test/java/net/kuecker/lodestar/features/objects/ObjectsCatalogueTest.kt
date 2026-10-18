package net.kuecker.lodestar.features.objects

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.catalog.ObjectGroup
import net.kuecker.lodestar.kit.catalog.Prominence
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObjectsCatalogueTest {
    private val catalog: Catalog by lazy { Catalog.loadBundled() }
    private val index: ObjectsCatalogIndex by lazy { ObjectsCatalogIndex(catalog) }
    private val observer = Observer(checkNotNull(GeographicLocation.validated(45.0, -100.0, 300.0)))
    private val date = Instant.parse("2026-10-18T04:00:00Z")

    private fun sections(options: ObjectsListOptions, positions: Map<String, ObjectSkyPosition> = emptyMap()) =
        ObjectsListBuilder.sections(options, catalog, index, emptyList(), emptyList(), positions)

    @Test
    fun aCatalogueListsEveryNumberedObjectInCatalogueOrder() {
        val messier = index.pool(ObjectsCatalogueFilter.MESSIER)
        assertEquals(110, messier.size)
        assertEquals("M 1", messier.first().catalogNumbers.first().label)
        val numbers = messier.map { ObjectsCatalogueFilter.MESSIER.numberKey(it) ?: -1.0 }
        assertEquals(numbers.sorted(), numbers)
        assertEquals(109, index.pool(ObjectsCatalogueFilter.CALDWELL).size)
        // The Sharpless list holds objects the browse list leaves out.
        val sharpless = index.pool(ObjectsCatalogueFilter.SHARPLESS)
        assertTrue(sharpless.size > 200)
        assertTrue(sharpless.any { !it.isListed })
        // NGC objects come before IC objects.
        val ngcIc = index.pool(ObjectsCatalogueFilter.NGC_IC)
        assertTrue(ngcIc.size > 1000)
        val firstIc = ngcIc.indexOfFirst { obj -> obj.catalogNumbers.none { it.catalogue.abbreviation == "NGC" } }
        assertTrue(ngcIc.take(firstIc).all { obj -> obj.catalogNumbers.any { it.catalogue.abbreviation == "NGC" } })
        // The browse list is the listed objects in name order.
        assertEquals(catalog.listedObjects.size, index.pool(ObjectsCatalogueFilter.ALL).size)
    }

    @Test
    fun theNumberSortKeyPutsALetterAfterItsNumber() {
        val barnard = index.pool(ObjectsCatalogueFilter.BARNARD)
        val keys = barnard.map { ObjectsCatalogueFilter.BARNARD.numberKey(it) ?: -1.0 }
        assertEquals(keys.sorted(), keys)
        assertNull(ObjectsCatalogueFilter.BARNARD.numberKey(assertNotNull(catalog.objectWithID("dso.M31"))))
    }

    @Test
    fun aCatalogueSectionHoldsOnlyItsObjectsAndAPopularFilterKeepsTheBest() {
        val options = ObjectsListOptions().choosingCatalogue(ObjectsCatalogueFilter.SHARPLESS)
        assertEquals(ObjectsSortOrder.NUMBER, options.sortOrder)
        val all = sections(options.copy(grouping = ObjectsGrouping.NONE)).single()
        assertEquals("Sharpless", all.title)
        assertTrue(all.objects.all { ObjectsCatalogueFilter.SHARPLESS.numberKey(it) != null })
        val popular = sections(options.copy(grouping = ObjectsGrouping.NONE, showsOnlyPopular = true)).single()
        assertTrue(popular.objects.size < all.objects.size)
        assertTrue(popular.objects.all { it.prominence <= Prominence.PROMINENT })
        // Going back to all objects sorts by name again.
        assertEquals(ObjectsSortOrder.NAME, options.choosingCatalogue(ObjectsCatalogueFilter.ALL).sortOrder)
    }

    @Test
    fun darkNebulaeAreListedUnderNebulaeAndTheProminenceGroupingSplitsByRank() {
        val byType = sections(ObjectsListOptions())
        val nebulae = byType.first { it.kind == ObjectsListSection.Kind.Group(ObjectGroup.NEBULAE) }
        assertTrue(nebulae.objects.any { it.category == ObjectCategory.DARK_NEBULA })
        val byRank = sections(ObjectsListOptions(grouping = ObjectsGrouping.PROMINENCE))
        val ranks = byRank.map { (it.kind as ObjectsListSection.Kind.ByProminence).prominence }
        assertEquals(ranks.sorted(), ranks)
        for (section in byRank) {
            val prominence = (section.kind as ObjectsListSection.Kind.ByProminence).prominence
            assertTrue(section.objects.all { it.prominence == prominence })
        }
        // Obscure deep-sky objects are not in the browse list.
        assertTrue(byRank.flatMap { it.objects }.none { it.isDeepSky && it.prominence == Prominence.OBSCURE })
    }

    @Test
    fun theProminenceOrderPutsShowpiecesFirstEvenWithoutAMagnitude() {
        val options = ObjectsListOptions(grouping = ObjectsGrouping.NONE, sortOrder = ObjectsSortOrder.PROMINENCE)
        val list = sections(options).single().objects
        val ranks = list.map { it.prominence.rawValue }
        assertEquals(ranks.sorted(), ranks)
        val horsehead = list.indexOfFirst { it.id == "dso.B33" }
        val faintest = list.indexOfLast { it.prominence == Prominence.NOTABLE }
        assertTrue(horsehead in 0 until faintest)
    }

    @Test
    fun theRowShowsTheCatalogueNumbersNotTheName() {
        val cave = assertNotNull(catalog.objectWithID("dso.C9"))
        val line = assertNotNull(ObjectsFormat.rowDesignations(cave))
        assertTrue(line.contains("Sh2-155"), line)
        assertTrue(line.contains("C 9"), line)
        val andromeda = assertNotNull(catalog.objectWithID("dso.M31"))
        assertEquals("M 31 · NGC 224", ObjectsFormat.rowDesignations(andromeda))
        // A designation equal to the name is not repeated.
        val flyingBat = assertNotNull(catalog.objectWithID("dso.Sh2-129"))
        assertTrue(ObjectsFormat.rowDesignations(flyingBat)?.contains("Flying Bat") != true)
    }

    @Test
    fun aSkylineDecidesWhatIsUpAndWhenItClearsIt() {
        val objects = listOf("dso.M31", "dso.M42", "dso.M45", "moon").mapNotNull { catalog.objectWithID(it) }
        val open = ObjectsPositionCalculator.positions(objects, observer, date)
        val walled = ObjectsPositionCalculator.positions(objects, observer, date, HorizonProfile.constant(60.0))
        for (obj in objects) {
            val plain = assertNotNull(open[obj.id])
            val behind = assertNotNull(walled[obj.id])
            assertFalse(plain.usesSkyline)
            assertTrue(behind.usesSkyline)
            assertEquals(plain.altitude, behind.altitude, 1e-9)
            // Nothing is up behind a 60° wall (unless it is higher than that).
            assertEquals(behind.altitude > 61, behind.isUp, obj.name)
            if (plain.altitude > 0 && behind.altitude <= 60) assertTrue(behind.isBehindSkyline, obj.name)
            // A rising behind a wall is no earlier than the open one.
            val openRise = plain.nextRise
            val wallRise = behind.nextRise
            if (openRise != null && wallRise != null && !plain.isUp) assertTrue(wallRise >= openRise)
        }
        val context = ObserverContext(observer, ZoneId.of("America/Chicago"), "Ridge", LocationSource.Device, horizon = HorizonProfile.flat)
        assertNull(context.skyline)
        assertNotNull(context.copy(horizon = HorizonProfile.constant(5.0)).skyline)
    }

    @Test
    fun theDetailKnowsWhenAnObjectIsBehindTheSkyline() {
        val m31 = assertNotNull(catalog.objectWithID("dso.M31"))
        val open = ObjectDetailCalculator.current(m31, observer, date)
        val walled = ObjectDetailCalculator.current(m31, observer, date, HorizonProfile.constant(60.0))
        assertNull(open.skylineAltitude)
        assertEquals(60.0, walled.skylineAltitude)
        assertEquals(open.horizontal, walled.horizontal)
        assertEquals(open.horizontal.altitude > 0, open.isUp)
        assertEquals(walled.horizontal.altitude > 61, walled.isUp)
        assertEquals(open.horizontal.altitude > 0 && !walled.isUp, walled.isBehindSkyline)
    }
}
