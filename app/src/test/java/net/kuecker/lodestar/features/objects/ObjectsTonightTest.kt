package net.kuecker.lodestar.features.objects

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.horizon.AzimuthRange
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.planning.UnobstructedVisibility
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

class ObjectsTonightTest {
    private val catalog: Catalog by lazy { Catalog.loadBundled() }
    private val index: ObjectsCatalogIndex by lazy { ObjectsCatalogIndex(catalog) }
    private val zone = ZoneId.of("America/Chicago")
    private val observer = Observer(checkNotNull(GeographicLocation.validated(45.0, -100.0, 300.0)))

    // 23:00 on Oct 17 in Chicago: the night that started at noon on Oct 17 is under way.
    private val late = Instant.parse("2026-10-18T04:00:00Z")
    private val night = ObjectsTonight.night(observer, zone, late)

    private fun objects(vararg ids: String): List<CelestialObject> = ids.map { assertNotNull(catalog.objectWithID(it), it) }

    private fun darkHours() = assertNotNull(night.darkness).duration / 3600

    @Test
    fun tonightIsTheNightUnderWayAndByDayTheComingOne() {
        assertEquals(Instant.parse("2026-10-17T17:00:00Z"), night.start)
        assertNotNull(night.darkness)
        // Soon after the Sun has risen on the night before it is the coming night.
        val morning = ObjectsTonight.night(observer, zone, Instant.parse("2026-10-18T14:00:00Z"))
        assertEquals(Instant.parse("2026-10-18T17:00:00Z"), morning.start)
        assertEquals(night.start, ObjectsTonight.night(observer, zone, Instant.parse("2026-10-18T10:00:00Z")).start)
    }

    @Test
    fun hoursAreAboveTheMinimumAltitudeAndInsideTheDark() {
        val list = objects("dso.M31", "dso.M42", "dso.M45", "moon", "planet.jupiter", "sun")
        val open = ObjectsTonight.visibility(list, observer, night, null)
        assertEquals(list.map { it.id }.toSet(), open.keys)
        for (obj in list) {
            val found = assertNotNull(open[obj.id])
            assertTrue(found.hours in 0.0..darkHours() + 1e-9, "${obj.name} ${found.hours}")
            assertFalse(found.isBehindSkyline, obj.name)
        }
        // Andromeda is circumpolar-ish at 45°N in October: most of the night is above 20°.
        assertTrue(assertNotNull(open["dso.M31"]).hours > 6)
        assertEquals(0.0, assertNotNull(open["sun"]).hours)
    }

    @Test
    fun theBatchAndTheOneObjectCallsAgree() {
        val list = objects("dso.M31", "dso.M42", "dso.M45", "dso.M13", "moon", "planet.saturn")
        val wall = HorizonProfile.flat.setting(35.0, AzimuthRange.between(60.0, 140.0))
        val batch = ObjectsTonight.visibility(list, observer, night, wall)
        val darkness = assertNotNull(night.darkness)
        for (obj in list) {
            val single = ObjectsTonight.visibility(obj, observer, darkness, wall)
            val found = assertNotNull(batch[obj.id])
            assertEquals(single.hours, found.hours, 0.002, obj.name)
            assertEquals(single.isBehindSkyline, found.isBehindSkyline, obj.name)
        }
    }

    @Test
    fun aSkylineTakesHoursAwayAndSaysWhatIsBehindIt() {
        val list = objects("dso.M31", "dso.M42", "dso.M45", "dso.M13", "planet.jupiter")
        val open = ObjectsTonight.visibility(list, observer, night, null)
        // A 60° wall all round hides everything that does not get higher than that.
        val walled = ObjectsTonight.visibility(list, observer, night, HorizonProfile.constant(60.0))
        for (obj in list) {
            val plain = assertNotNull(open[obj.id])
            val behind = assertNotNull(walled[obj.id])
            assertTrue(behind.hours <= plain.hours + 0.002, obj.name)
            assertEquals(plain.hours > 0.05 && behind.hours == 0.0, behind.isBehindSkyline, obj.name)
        }
        assertTrue(list.any { assertNotNull(walled[it.id]).isBehindSkyline })
        // A skyline lower than the minimum altitude changes nothing.
        val low = ObjectsTonight.visibility(list, observer, night, HorizonProfile.constant(3.0))
        for (obj in list) assertEquals(assertNotNull(open[obj.id]).hours, assertNotNull(low[obj.id]).hours, 0.02, obj.name)
    }

    @Test
    fun withoutDarknessNothingIsVisible() {
        val polar = Observer(checkNotNull(GeographicLocation.validated(69.65, 18.96, 0.0)))
        val summer = ObjectsTonight.night(polar, ZoneId.of("Europe/Oslo"), Instant.parse("2026-06-21T22:00:00Z"))
        assertNull(summer.darkness)
        val result = ObjectsTonight.visibility(objects("dso.M31", "moon"), polar, summer, null)
        assertTrue(result.values.all { it.intervals.isEmpty() && !it.isBehindSkyline })
    }

    @Test
    fun theDetailHasAnUnobstructedStretchOnlyWithASkyline() {
        // The Orion Nebula rises late in the east and is up for hours, so a higher skyline costs it time.
        val m42 = assertNotNull(catalog.objectWithID("dso.M42"))
        fun context(horizon: HorizonProfile?) = ObserverContext(observer, zone, "Ridge", LocationSource.Device, horizon = horizon)
        assertNull(ObjectDetailCalculator.night(m42, context(null), late).unobstructed)
        assertNull(ObjectDetailCalculator.night(m42, context(HorizonProfile.flat), late).unobstructed)
        val ridge = HorizonProfile.constant(25.0)
        val detail = assertNotNull(ObjectDetailCalculator.night(m42, context(ridge), late).unobstructed)
        val list = assertNotNull(ObjectsTonight.visibility(listOf(m42), observer, night, ridge)[m42.id])
        assertEquals(list.hours, detail.hours, 0.002)
        val open = assertNotNull(ObjectsTonight.visibility(listOf(m42), observer, night, null)[m42.id])
        assertTrue(detail.hours < open.hours, "${detail.hours} against ${open.hours}")
        assertTrue(detail.intervals.isNotEmpty())
    }

    @Test
    fun theListOrdersByHoursAndFiltersByAtLeastOneHour() {
        val ids = listOf("dso.M31", "dso.M42", "dso.M45", "dso.M13", "dso.M1")
        val hours = mapOf("dso.M31" to 3.4, "dso.M42" to 0.5, "dso.M45" to 7.0, "dso.M13" to 1.0)
        val tonight = hours.mapValues { sample(it.value) }
        fun sections(options: ObjectsListOptions, map: Map<String, UnobstructedVisibility>) =
            ObjectsListBuilder.sections(
                options.copy(grouping = ObjectsGrouping.NONE), catalog, index, ids, emptyList(), emptyMap(), map,
            )

        // Favorites come first; their order follows the sort order: most hours first, unknown hours last.
        val ordered = sections(ObjectsListOptions(sortOrder = ObjectsSortOrder.HOURS_TONIGHT), tonight)
            .first { it.kind == ObjectsListSection.Kind.Favorites }.objects.map { it.id }
        assertEquals(listOf("dso.M45", "dso.M31", "dso.M13", "dso.M42", "dso.M1"), ordered)

        val visible = sections(ObjectsListOptions(showsOnlyVisibleTonight = true), tonight)
            .first { it.kind == ObjectsListSection.Kind.Favorites }.objects.map { it.id }
        assertEquals(setOf("dso.M45", "dso.M31", "dso.M13"), visible.toSet())

        // Until the hours arrive the order falls back to name order and the filter waits.
        val waiting = sections(ObjectsListOptions(sortOrder = ObjectsSortOrder.HOURS_TONIGHT, showsOnlyVisibleTonight = true), emptyMap())
            .first { it.kind == ObjectsListSection.Kind.Favorites }.objects.map { it.id }
        val byName = sections(ObjectsListOptions(), emptyMap()).first { it.kind == ObjectsListSection.Kind.Favorites }.objects.map { it.id }
        assertEquals(byName, waiting)
        assertEquals(ids.size, waiting.size)
    }

    @Test
    fun theRowSaysHowManyHoursTonightOrThatTheObjectIsBehindTheSkyline() {
        assertEquals("3.4 h tonight", ObjectsFormat.tonightText(sample(3.4)).replace(',', '.'))
        assertEquals("0 h tonight", ObjectsFormat.tonightText(sample(0.0)))
        assertEquals("0 h tonight", ObjectsFormat.tonightText(sample(0.04)))
        assertEquals("behind skyline", ObjectsFormat.tonightText(UnobstructedVisibility(emptyList(), isBehindSkyline = true)))
        assertEquals("3.4 hours tonight", ObjectsFormat.tonightText(sample(3.4), spoken = true).replace(',', '.'))
        assertEquals("behind your skyline tonight", ObjectsFormat.tonightText(UnobstructedVisibility(emptyList(), true), spoken = true))
        val m31 = assertNotNull(catalog.objectWithID("dso.M31"))
        val spoken = ObjectsFormat.spokenSummary(m31, null, false, zone, sample(2.0))
        assertTrue(spoken.contains("tonight"), spoken)
    }

    @Test
    fun theOptionsRememberTheNewChoices() {
        assertFalse(ObjectsListOptions().isCustomized)
        assertTrue(ObjectsListOptions(showsOnlyVisibleTonight = true).isCustomized)
        assertTrue(ObjectsListOptions(sortOrder = ObjectsSortOrder.HOURS_TONIGHT).isCustomized)
        assertTrue(ObjectsListOptions(showsOnlyVisibleTonight = true).needsTonightHours)
        assertTrue(ObjectsListOptions(sortOrder = ObjectsSortOrder.HOURS_TONIGHT).needsTonightHours)
        assertFalse(ObjectsListOptions(sortOrder = ObjectsSortOrder.ALTITUDE).needsTonightHours)
        assertEquals(ObjectsSortOrder.HOURS_TONIGHT, ObjectsSortOrder.fromRawValue("hoursTonight"))
    }

    /** A record with one stretch of [hours] hours. */
    private fun sample(hours: Double): UnobstructedVisibility {
        val start = Instant.parse("2026-10-18T03:00:00Z")
        val span = DateInterval(start, hours * 3600)
        return UnobstructedVisibility(if (hours > 0) listOf(span) else emptyList(), isBehindSkyline = false)
    }
}
