package net.kuecker.lodestar.features.events

import net.kuecker.lodestar.kit.events.EventDetails
import net.kuecker.lodestar.kit.events.EventKind
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventsPlanningTest {
    private val zone = ZoneId.of("America/Chicago")
    private val context = ObserverContext(
        observer = Observer(checkNotNull(GeographicLocation.validated(32.78, -96.80, 150.0))),
        timeZone = zone,
        name = "Dallas",
        source = LocationSource.Device,
    )
    private val now = Instant.parse("2026-10-18T15:00:00Z")
    private val lens = LensSetup(SensorFormat.fullFrame, 24.0, 2.8, 4.3)

    private val result: EventsResult by lazy {
        EventsCalculator.compute(context, EventsCalculator.key(context, now))
    }

    @Test
    fun theCalendarCoversTheNextYearInOrderWithVisibility() {
        val events = result.events
        assertTrue(events.size > 50)
        assertEquals(events.map { it.date }, events.map { it.date }.sorted())
        val start = result.key.day.atStartOfDay(zone).toInstant()
        assertTrue(events.all { it.date >= start })
        // Every event but the seasons has visibility for the observer.
        assertTrue(events.filter { it.kind != EventKind.SEASON }.all { it.visibility != null })
        assertTrue(events.filter { it.kind == EventKind.SEASON }.all { it.visibility == null })
        // The kinds a calendar of the next year must hold.
        for (kind in listOf(EventKind.METEOR_SHOWER, EventKind.CONJUNCTION, EventKind.SEASON, EventKind.OPPOSITION)) {
            assertTrue(events.any { it.kind == kind }, "no $kind")
        }
    }

    @Test
    fun theKeyChangesWithTheDayThePlaceAndTheZoneButNotWithGpsNoise() {
        val key = EventsCalculator.key(context, now)
        assertEquals(key, EventsCalculator.key(context, now.plusSeconds(3600)))
        assertNotEquals(key, EventsCalculator.key(context, now.plusSeconds(2 * 86_400)))
        val nudged = context.copy(observer = Observer(checkNotNull(GeographicLocation.validated(32.7801, -96.8001, 150.0))))
        assertEquals(key, EventsCalculator.key(nudged, now))
        val elsewhere = context.copy(observer = Observer(checkNotNull(GeographicLocation.validated(40.0, -105.0, 1600.0))))
        assertNotEquals(key, EventsCalculator.key(elsewhere, now))
        assertNotEquals(key, EventsCalculator.key(context.copy(timeZone = ZoneId.of("America/Denver")), now))
    }

    @Test
    fun theFiltersNarrowTheListByGroupAndVisibility() {
        val events = result.events
        val filter = EventsFilter()
        assertEquals(events, filter.apply(events))
        filter.toggle(EventsGroup.METEOR_SHOWERS)
        assertTrue(filter.apply(events).all { it.kind == EventKind.METEOR_SHOWER })
        filter.toggle(EventsGroup.ECLIPSES)
        val shown = filter.apply(events)
        assertTrue(shown.all { it.kind in setOf(EventKind.METEOR_SHOWER, EventKind.LUNAR_ECLIPSE, EventKind.SOLAR_ECLIPSE) })
        filter.isVisibleOnly = true
        assertTrue(filter.apply(events).all { it.visibility?.isObservable != false })
        assertTrue(filter.apply(events).size <= shown.size)
        filter.reset()
        assertEquals(events, filter.apply(events))
        // The Moon group holds the full-Moon specials and the conjunctions that involve the Moon.
        filter.toggle(EventsGroup.MOON)
        val moon = filter.apply(events)
        assertTrue(moon.isNotEmpty())
        assertTrue(moon.all { it.kind == EventKind.FULL_MOON_SPECIAL || (it.details as EventDetails.Conjunction).conjunction.involvesMoon })
    }

    @Test
    fun theUpcomingCardSkipsMoonConjunctionsAndKeepsAFewEvents() {
        val upcoming = upcomingEvents(result.events, now)
        assertTrue(upcoming.size in 1..4)
        assertTrue(upcoming.all { EventsFormat.displayDate(it) >= now })
        for (event in upcoming) {
            val conjunction = (event.details as? EventDetails.Conjunction)?.conjunction ?: continue
            assertTrue(!conjunction.involvesMoon || conjunction.topocentric?.isOccultation == true)
        }
    }

    // The texts below that print dates go through android.text.format.DateFormat, which a JVM unit test cannot call; the
    // kinds without dates in their text are exercised here.
    @Test
    fun theKindsWithoutDatesInTheirTextHaveASummaryAndAGuide() {
        val kinds = listOf(
            EventKind.CONJUNCTION, EventKind.OPPOSITION, EventKind.GREATEST_ELONGATION, EventKind.SEASON,
            EventKind.FULL_MOON_SPECIAL,
        )
        for (kind in kinds) {
            val event = result.events.firstOrNull { it.kind == kind } ?: continue
            val plan = EventsPlanner.plan(event, context)
            assertTrue(EventsFormat.summary(event, context).isNotBlank(), event.title)
            val guide = EventsGuide.guide(event, context, plan, lens)
            assertTrue(guide.what.isNotEmpty() && guide.why.isNotBlank() && guide.tips.isNotEmpty(), event.title)
            assertEquals(kind != EventKind.SEASON, plan.moon != null, event.title)
        }
        val lunar = result.events.firstOrNull { it.kind == EventKind.LUNAR_ECLIPSE } ?: return
        val guide = EventsGuide.guide(lunar, context, EventsPlanner.plan(lunar, context), lens)
        assertTrue(guide.tips.isNotEmpty())
    }

    @Test
    fun aLunarEclipseHasAnAltitudeForEveryContactThatHappens() {
        val lunar = result.events.firstOrNull { it.kind == EventKind.LUNAR_ECLIPSE } ?: return
        val details = lunar.details as EventDetails.LunarEclipse
        val altitudes = lunar.visibility?.contacts.orEmpty().map { it.contact }
        assertTrue(altitudes.isNotEmpty())
        assertTrue(details.eclipse.contacts.map { it.contact }.containsAll(altitudes))
        for (contact in details.eclipse.contacts) {
            assertTrue(EventsFormat.contactTitle(contact.contact).isNotBlank())
        }
    }

    @Test
    fun aMeteorShowerIsFramedAboutFortyDegreesFromTheRadiant() {
        val shower = result.events.first { it.kind == EventKind.METEOR_SHOWER && it.visibility?.isObservable == true }
        val plan = EventsPlanner.plan(shower, context)
        val radiant = assertNotNull(plan.radiant)
        val frame = assertNotNull(plan.meteorFrame)
        assertEquals(EventsPlanner.METEOR_OFFSET_DEGREES, radiant.angularSeparation(frame), 0.01)
        assertTrue(frame.altitude > 0)
    }

    @Test
    fun theFocalLengthSuggestionFramesBothBodiesAndGrowsWithTheirDistance() {
        val sensor = SensorFormat.fullFrame
        val tight = EventsGuide.framingFocalLength(1.0, sensor)
        val wide = EventsGuide.framingFocalLength(6.0, sensor)
        assertTrue(tight > wide)
        // 6° apart need a 15° field or more, and the pair must fit the long side with room to spare.
        assertTrue(wide in 50.0..150.0, "wide $wide")
        assertTrue(tight in 300.0..800.0, "tight $tight")
        // A pair almost touching is capped, not infinite.
        assertTrue(EventsGuide.framingFocalLength(0.01, sensor) <= 1000.0)
        assertTrue(EventsGuide.framingTip(2.0, lens).contains("mm"))
    }

    @Test
    fun theSolarEclipseTextsKeepTheSafetyRule() {
        val eclipse = result.events.firstOrNull { it.kind == EventKind.SOLAR_ECLIPSE } ?: return
        val guide = EventsGuide.guide(eclipse, context, null, lens)
        assertTrue(guide.tips.first().contains(SOLAR_FILTER_RULE))
        assertTrue(SOLAR_FILTER_RULE.contains("solar filter"))
    }
}
