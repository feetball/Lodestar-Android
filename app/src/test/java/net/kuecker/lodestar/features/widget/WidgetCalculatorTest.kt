package net.kuecker.lodestar.features.widget

import net.kuecker.lodestar.features.events.EventsCalculator
import net.kuecker.lodestar.features.events.EventsFormat
import net.kuecker.lodestar.features.events.upcomingEvents
import net.kuecker.lodestar.features.tonight.TonightCalculator
import net.kuecker.lodestar.features.tonight.TonightInput
import net.kuecker.lodestar.features.tonight.TonightNightChoice
import net.kuecker.lodestar.features.tonight.TonightNightKey
import net.kuecker.lodestar.kit.ephemeris.MoonPhaseCalculator
import net.kuecker.lodestar.kit.ephemeris.TwilightCalculator
import net.kuecker.lodestar.kit.events.EventCalculator
import net.kuecker.lodestar.kit.events.EventKind
import net.kuecker.lodestar.kit.events.EventOptions
import net.kuecker.lodestar.kit.foundation.Atmosphere
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.model.NightModeSetting
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetCalculatorTest {
    private val zone = ZoneId.of("America/Chicago")
    private val location = GeographicLocation(32.78, -96.80, 150.0)
    private val observer = Observer(location, Atmosphere(1010.0, 10.0))
    private val dallas = WidgetInputs(
        WidgetPlace("Dallas", 32.78, -96.80, 150.0, timeZoneID = zone.id),
        nightMode = NightModeSetting.AUTOMATIC,
    )

    /** 13:00 on October 18th, 2026, at Dallas: the night to come starts this evening. */
    private val afternoon = Instant.parse("2026-10-18T18:00:00Z")

    private fun night(inputs: WidgetInputs, now: Instant) = assertNotNull(WidgetCalculator.snapshot(inputs, now).night)

    // Darkness

    @Test
    fun theDarknessIsTheKitsAstronomicalNightOfTheEvening() {
        val expected = assertNotNull(TwilightCalculator.night(afternoon, observer, zone).astronomicalNight)
        val windows = night(dallas, afternoon).darkness.windows
        assertEquals(listOf(expected), windows)
        assertEquals(expected.duration / 3600, night(dallas, afternoon).darkness.hours, 1e-9)
        assertTrue(night(dallas, afternoon).darkness.moonFreeHours <= night(dallas, afternoon).darkness.hours)
    }

    @Test
    fun afterMidnightTheNightThatBeganTheEveningBeforeIsStillTonight() {
        val threeInTheMorning = Instant.parse("2026-10-18T08:00:00Z")
        val yesterday = Instant.parse("2026-10-17T18:00:00Z")
        val expected = assertNotNull(TwilightCalculator.night(yesterday, observer, zone).astronomicalNight)
        val windows = night(dallas, threeInTheMorning).darkness.windows
        assertEquals(listOf(expected), windows)
        assertTrue(threeInTheMorning in windows.single())
    }

    @Test
    fun afterSunriseTheComingNightIsShown() {
        val morning = Instant.parse("2026-10-18T14:30:00Z")
        val windows = night(dallas, morning).darkness.windows
        assertTrue(windows.single().start > morning)
        assertEquals(night(dallas, afternoon).darkness.windows, windows)
    }

    @Test
    fun nearTheCircleWhereTheSunNeverGetsLowEnoughThereIsNoDarkness() {
        val tromso = WidgetInputs(WidgetPlace("Tromsø", 69.65, 18.96, 10.0, timeZoneID = "Europe/Oslo"))
        val tonight = night(tromso, Instant.parse("2026-06-21T10:00:00Z"))
        assertTrue(tonight.darkness.windows.isEmpty())
        assertEquals(0.0, tonight.darkness.hours)
        assertFalse(tonight.core.hasDarkness)
        assertTrue(tonight.core.visibleWindows.isEmpty())
    }

    // Moon

    @Test
    fun theMoonIsItsPhaseAtDuskAndItsRiseAndSet() {
        val tonight = night(dallas, afternoon)
        val dusk = tonight.darkness.windows.single().start
        val phase = MoonPhaseCalculator.info(dusk)
        assertEquals(phase.name, tonight.moon.phase)
        assertEquals(phase.illuminatedFraction, tonight.moon.illuminatedFraction, 1e-9)
        assertTrue(tonight.moon.isWaxing)
        assertTrue(tonight.moon.illuminatedFraction in 0.4..0.7, "a first quarter Moon is about half lit")
        assertFalse(tonight.moon.isSouthernHemisphere)
        assertNotNull(tonight.moon.rise)
        assertNotNull(tonight.moon.set)
        assertTrue(tonight.moon.upIntervals.isNotEmpty())
    }

    @Test
    fun inTheSouthTheMoonIsMarkedForTheSouthernSky() {
        val sydney = WidgetInputs(WidgetPlace("Sydney", -33.87, 151.21, 20.0, timeZoneID = "Australia/Sydney"))
        assertTrue(night(sydney, afternoon).moon.isSouthernHemisphere)
    }

    // Milky Way core

    @Test
    fun theCoreIsVisibleOnlyWhileItIsDark() {
        val july = Instant.parse("2026-07-14T18:00:00Z")
        val tonight = night(dallas, july)
        val core = tonight.core
        assertTrue(core.hasDarkness)
        assertTrue(core.visibleWindows.isNotEmpty())
        val dark = tonight.darkness.windows
        for (window in core.visibleWindows) {
            assertTrue(dark.any { window.start >= it.start && window.end <= it.end }, "$window is not inside $dark")
            assertTrue(window.duration > 3600)
        }
        val best = assertNotNull(core.bestTime)
        assertTrue(core.visibleWindows.any { best in it })
        assertTrue(assertNotNull(core.maxAltitude) >= 10.0)
        assertNotNull(core.bestAzimuth)
    }

    // Events

    @Test
    fun theNextEventIsTheFirstOneTheEventsCardLists() {
        val event = assertNotNull(night(dallas, afternoon).event)
        val options = EventOptions(
            timeZone = zone,
            planetConjunctionThreshold = EventsCalculator.CONJUNCTION_THRESHOLD,
            moonConjunctionThreshold = EventsCalculator.CONJUNCTION_THRESHOLD,
        )
        val events = EventCalculator.events(afternoon, afternoon.plus(60, ChronoUnit.DAYS), observer, options)
        val expected = upcomingEvents(events, afternoon).first { EventsFormat.isVisibleFromHere(it) != false }
        assertEquals(expected.title, event.title)
        assertEquals(EventsFormat.displayDate(expected), event.date)
        assertEquals(expected.kind, event.kind)
        // The Orionids peak on the 21st and 22nd of October.
        assertEquals(EventKind.METEOR_SHOWER, event.kind)
        assertTrue(event.title.startsWith("Orionids"), event.title)
        assertTrue(event.date in Instant.parse("2026-10-20T00:00:00Z")..Instant.parse("2026-10-23T00:00:00Z"))
        assertEquals(true, event.isVisibleFromHere)
        assertNotNull(event.bestWindow)
        assertNotNull(event.showerRate)
    }

    @Test
    fun anEventThatHasPassedGivesWayToTheNextOne() {
        val before = assertNotNull(night(dallas, afternoon).event)
        val after = assertNotNull(night(dallas, before.date.plusSeconds(3600)).event)
        assertNotEquals(before.date, after.date)
        assertTrue(after.date > before.date)
    }

    @Test
    fun anEventThatCannotBeSeenIsOnlyShownWhenNothingElseIsComing() {
        // Midnight Sun: nothing is visible for weeks, so the first event is shown, marked as not visible.
        val tromso = WidgetInputs(WidgetPlace("Tromsø", 69.65, 18.96, 10.0, timeZoneID = "Europe/Oslo"))
        val event = assertNotNull(night(tromso, Instant.parse("2026-06-21T10:00:00Z")).event)
        assertTrue(event.date >= Instant.parse("2026-06-21T10:00:00Z"))
        if (event.isVisibleFromHere == false) assertNull(event.bestWindow)
    }

    // No place

    @Test
    fun withoutAPlaceThereIsNoNightAndTheWidgetAsksForOne() {
        val snapshot = WidgetCalculator.snapshot(WidgetInputs(), afternoon)
        assertNull(snapshot.night)
        assertTrue(snapshot.nextRefresh > afternoon)
        assertTrue(snapshot.nextRefresh <= afternoon.plus(24, ChronoUnit.HOURS).plusSeconds(60))
    }

    // Night palette

    @Test
    fun theNightPaletteFollowsTheAppsRule() {
        val noon = afternoon
        val threeInTheMorning = Instant.parse("2026-10-18T08:00:00Z")
        val on = dallas.copy(nightMode = NightModeSetting.ON, nightIntensity = 0.4)
        val off = dallas.copy(nightMode = NightModeSetting.OFF)
        assertTrue(WidgetCalculator.appearance(on, noon, observer).isNight)
        assertEquals(0.4, WidgetCalculator.appearance(on, noon, observer).intensity)
        assertFalse(WidgetCalculator.appearance(off, threeInTheMorning, observer).isNight)
        assertFalse(WidgetCalculator.appearance(dallas, noon, observer).isNight)
        assertTrue(WidgetCalculator.appearance(dallas, threeInTheMorning, observer).isNight)
        // Dusk: the Sun is not yet 6° below the horizon at sunset.
        val sunset = assertNotNull(TwilightCalculator.night(noon, observer, zone).sunset)
        assertFalse(WidgetCalculator.appearance(dallas, sunset, observer).isNight)
        val civilDusk = assertNotNull(TwilightCalculator.night(noon, observer, zone).civilDusk)
        assertTrue(WidgetCalculator.appearance(dallas, civilDusk.plusSeconds(60), observer).isNight)
    }

    @Test
    fun withoutAnObserverTheAutomaticNightPaletteFollowsTheClock() {
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val inputs = WidgetInputs(nightMode = NightModeSetting.AUTOMATIC)
            assertTrue(WidgetCalculator.appearance(inputs, Instant.parse("2026-10-18T22:00:00Z"), null).isNight)
            assertTrue(WidgetCalculator.appearance(inputs, Instant.parse("2026-10-18T03:00:00Z"), null).isNight)
            assertFalse(WidgetCalculator.appearance(inputs, Instant.parse("2026-10-18T12:00:00Z"), null).isNight)
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    // Refreshes

    @Test
    fun theNextRefreshIsTheFirstMomentAfterNowPlusAMargin() {
        val now = Instant.parse("2026-10-18T18:00:00Z")
        val soon = now.plus(90, ChronoUnit.MINUTES)
        val later = now.plus(5, ChronoUnit.HOURS)
        val past = now.minusSeconds(600)
        val margin = Duration.ofSeconds(WidgetCalculator.REFRESH_MARGIN_SECONDS)
        assertEquals(soon.plus(margin), WidgetCalculator.nextRefresh(now, listOf(later, soon, past)))
        assertEquals(later.plus(margin), WidgetCalculator.nextRefresh(now, listOf(later, past)))
        // A moment a second away is not missed: the refresh comes just after it.
        assertEquals(now.plusSeconds(1).plus(margin), WidgetCalculator.nextRefresh(now, listOf(now.plusSeconds(1), later)))
    }

    @Test
    fun theNextRefreshIsNeverMoreThanTwelveHoursAway() {
        val now = Instant.parse("2026-10-18T11:00:00Z")
        val limit = now.plus(WidgetCalculator.LONGEST_WAIT_HOURS, ChronoUnit.HOURS).plusSeconds(WidgetCalculator.REFRESH_MARGIN_SECONDS)
        assertEquals(limit, WidgetCalculator.nextRefresh(now, emptyList()))
        assertEquals(limit, WidgetCalculator.nextRefresh(now, listOf(now.plus(20, ChronoUnit.HOURS))))
    }

    @Test
    fun midnightIsAMomentOnlyWhenItChangesHowFarAwayTheEventIs() {
        val summary = TonightCalculator.report(
            TonightInput(TonightNightKey(assertNotNull(dallas.place?.observerContext()), TonightNightChoice.Tonight), afternoon, null),
        ).summary
        val midnight = Instant.parse("2026-10-19T05:00:00Z")
        val tomorrow = WidgetEvent("Tomorrow's", EventKind.SEASON, midnight.plus(20, ChronoUnit.HOURS), null, null, null)
        val inThreeDays = tomorrow.copy(date = midnight.plus(3, ChronoUnit.DAYS))
        val inThreeMonths = tomorrow.copy(date = midnight.plus(100, ChronoUnit.DAYS))
        val evening = Instant.parse("2026-10-19T03:00:00Z")
        fun moments(event: WidgetEvent?) =
            WidgetCalculator.transitions(summary, evening.plus(8, ChronoUnit.HOURS), event, false, evening, zone)
        assertTrue(midnight in moments(tomorrow))
        assertTrue(midnight in moments(inThreeDays))
        assertFalse(midnight in moments(inThreeMonths))
        assertFalse(midnight in moments(null))
    }

    @Test
    fun theNightPaletteOnlyMakesMomentsWhenItFollowsTheSun() {
        val summary = TonightCalculator.report(
            TonightInput(TonightNightKey(assertNotNull(dallas.place?.observerContext()), TonightNightChoice.Tonight), afternoon, null),
        ).summary
        val dusk = assertNotNull(summary.twilight.civilDusk)
        val expiry = afternoon.plus(20, ChronoUnit.HOURS)
        assertTrue(dusk in WidgetCalculator.transitions(summary, expiry, null, true, afternoon, zone))
        assertFalse(dusk in WidgetCalculator.transitions(summary, expiry, null, false, afternoon, zone))
    }

    /**
     * The widget changes only when it is redrawn: between a snapshot and its next refresh, nothing it shows is
     * different from what the snapshot said. A change the refresh missed would show wrongly until the next one.
     */
    @Test
    fun nothingChangesBetweenARefreshAndTheNextOne() {
        val places = listOf(
            dallas,
            dallas.copy(nightMode = NightModeSetting.ON),
            WidgetInputs(WidgetPlace("Dark Sky Site", 38.9, -105.4, 2300.0, timeZoneID = "America/Denver")),
            WidgetInputs(WidgetPlace("Sydney", -33.87, 151.21, 20.0, timeZoneID = "Australia/Sydney")),
        )
        val starts = listOf(
            Instant.parse("2026-10-18T13:00:00Z"), Instant.parse("2026-10-19T01:00:00Z"),
            Instant.parse("2026-07-14T03:00:00Z"), Instant.parse("2026-12-01T09:00:00Z"),
        )
        for (inputs in places) {
            for (start in starts) {
                val first = WidgetCalculator.snapshot(inputs, start)
                val text = look(inputs, first)
                assertTrue(first.nextRefresh > start)
                val span = Duration.between(start, first.nextRefresh.minusSeconds(WidgetCalculator.REFRESH_MARGIN_SECONDS + 1))
                for (step in 1..5) {
                    val at = start.plus(span.multipliedBy(step.toLong()).dividedBy(6))
                    assertEquals(text, look(inputs, WidgetCalculator.snapshot(inputs, at)), "$inputs changed between $start and $at")
                }
                // And the refresh itself brings the change (the schedule is not made of moments nothing happens at).
                val after = WidgetCalculator.snapshot(inputs, first.nextRefresh)
                assertNotEquals(text, look(inputs, after), "nothing changed at the refresh after $start for ${inputs.place?.name}")
            }
        }
    }

    /** Everything a widget shows or is coloured by. */
    private fun look(inputs: WidgetInputs, snapshot: WidgetSnapshot): String {
        val night = assertNotNull(snapshot.night)
        val copy = WidgetCopy.of(night, snapshot.computedAt, WidgetTestFormat)
        return "${snapshot.appearance} $copy ${night.darkness.windows}"
    }

    @Test
    fun theSampleOfThePickerIsACompleteNight() {
        val sample = night(WidgetSample.inputs, WidgetSample.now)
        assertEquals("Dark Sky Site", sample.placeName)
        assertTrue(sample.darkness.windows.isNotEmpty())
        assertTrue(sample.core.visibleWindows.isNotEmpty())
        assertNotNull(sample.event)
        assertFalse(WidgetCalculator.snapshot(WidgetSample.inputs, WidgetSample.now).appearance.isNight)
        // It is nowhere near dark when the sample is taken, so none of the "now" marks shows.
        val copy = WidgetCopy.of(sample, WidgetSample.now, WidgetTestFormat)
        assertNull(copy.dark.badge)
        assertNull(copy.moon.badge)
        assertNull(copy.core.badge)
    }

}
