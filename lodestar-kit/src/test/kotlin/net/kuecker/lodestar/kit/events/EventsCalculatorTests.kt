package net.kuecker.lodestar.kit.events

import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.events.EventsTestSupport.assertNear
import net.kuecker.lodestar.kit.events.EventsTestSupport.utc
import net.kuecker.lodestar.kit.foundation.*
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The calendar as a whole: kinds, options, ordering, bounds, visibility and aim targets. */
class EventsCalculatorTests {
    private val year2026 = Pair(utc("2026-01-01T00:00:00Z"), utc("2026-12-31T23:59:59Z"))

    private fun count(events: List<AstronomicalEvent>, kind: EventKind): Int = events.count { it.kind == kind }

    /** `true` for events without a visibility and for those the observer can see. */
    private fun canBeSeen(event: AstronomicalEvent): Boolean {
        val visibility = event.visibility ?: return true
        return visibility.isObservable
    }

    @Test
    fun testTheYear2026HoldsTheExpectedEvents() {
        val events = EventCalculator.events(year2026.first, year2026.second)
        assertEquals(18, count(events, EventKind.METEOR_SHOWER))
        assertEquals(4, count(events, EventKind.SEASON))
        assertEquals(2, count(events, EventKind.LUNAR_ECLIPSE))
        assertEquals(2, count(events, EventKind.SOLAR_ECLIPSE))
        // Jupiter 10 Jan, Neptune 26 Sep, Saturn 4 Oct, Uranus 25 Nov (Mars has no opposition in 2026).
        assertEquals(4, count(events, EventKind.OPPOSITION))
        // Mercury 19 Feb E, 3 Apr W, 15 Jun E, 2 Aug W, 12 Oct E, 20 Nov W and Venus 15 Aug E.
        assertEquals(7, count(events, EventKind.GREATEST_ELONGATION))
        assertEquals(6, count(events, EventKind.FULL_MOON_SPECIAL))
        assertEquals(0, count(events, EventKind.EARTH_APSIS))
        assertTrue(count(events, EventKind.CONJUNCTION) in 100..140)
        assertTrue(events.all { it.visibility == null })
    }

    @Test
    fun testEventsAreSortedByDateWithTiesByKind() {
        val events = EventCalculator.events(year2026.first, year2026.second)
        for (index in 1 until events.size) {
            val previous = events[index - 1]
            val current = events[index]
            assertTrue(current.date >= previous.date)
            if (current.date == previous.date) assertTrue(current.kind.ordinal >= previous.kind.ordinal)
        }
        assertTrue(events.all { it.date >= year2026.first && it.date <= year2026.second })
    }

    @Test
    fun testKindsSelectTheFinders() {
        for (kind in EventKind.entries) {
            val events = EventCalculator.events(year2026.first, year2026.second, null, EventOptions(kinds = setOf(kind)))
            assertTrue(events.isNotEmpty() || kind == EventKind.EARTH_APSIS, kind.rawValue)
            assertTrue(events.all { it.kind == kind }, kind.rawValue)
        }
        assertTrue(EventCalculator.events(year2026.first, year2026.second, null, EventOptions(kinds = emptySet())).isEmpty())
        val two = EventCalculator.events(
            year2026.first, year2026.second, null, EventOptions(kinds = setOf(EventKind.LUNAR_ECLIPSE, EventKind.SOLAR_ECLIPSE)),
        )
        assertEquals(4, two.size)
        assertEquals(
            listOf(EventKind.SOLAR_ECLIPSE, EventKind.LUNAR_ECLIPSE, EventKind.SOLAR_ECLIPSE, EventKind.LUNAR_ECLIPSE),
            two.map { it.kind },
        )
    }

    @Test
    fun testTheCalculatorAgreesWithTheFinders() {
        val all = EventCalculator.events(year2026.first, year2026.second)
        val lunar = LunarEclipseFinder.events(year2026.first, year2026.second)
        assertEquals(lunar, all.filter { it.kind == EventKind.LUNAR_ECLIPSE })
        val special = FullMoonSpecialFinder.events(year2026.first, year2026.second)
        assertEquals(special, all.filter { it.kind == EventKind.FULL_MOON_SPECIAL })
        assertEquals(SeasonFinder.events(year2026.first, year2026.second), all.filter { it.kind == EventKind.SEASON })
        assertEquals(MeteorShowerFinder.events(year2026.first, year2026.second), all.filter { it.kind == EventKind.METEOR_SHOWER })
        assertEquals(
            ConjunctionFinder.events(year2026.first, year2026.second),
            all.filter { it.kind == EventKind.CONJUNCTION },
        )
        // Running it twice gives the same list.
        assertEquals(all, EventCalculator.events(year2026.first, year2026.second))
    }

    @Test
    fun testInvalidAndLongRanges() {
        val moment = utc("2026-03-20T14:46:00Z")
        assertTrue(EventCalculator.events(moment, moment.minusSeconds(1)).isEmpty())
        assertTrue(EventCalculator.events(moment, moment).isEmpty())
        // A range of a century is cut to ten years: 40 seasons, not 400.
        val seasons = EventOptions(kinds = setOf(EventKind.SEASON))
        val long = EventCalculator.events(utc("2026-01-01T00:00:00Z"), utc("2126-01-01T00:00:00Z"), null, seasons)
        assertTrue(long.size in 39..41, "${long.size}")
        assertTrue(long.last().date <= utc("2026-01-01T00:00:00Z").plusSeconds((EventCalculator.maximumSpanDays * 86_400).toLong()))
        assertEquals(3_660.0, EventCalculator.maximumSpanDays, 0.0)
        // A non-finite instant gives nothing.
        assertTrue(EventCalculator.events(java.time.Instant.MAX, java.time.Instant.MAX).isEmpty())
        assertTrue(EventCalculator.events(java.time.Instant.MIN, moment).isEmpty())
    }

    @Test
    fun testExtremeDatesAreHandled() {
        // The calculator works in the years 1 to 4001; anything outside gives an empty list, inside it computes.
        val outside = instantFromTimeIntervalSince1970(-1.6e11)
        val farFuture = instantFromTimeIntervalSince1970(1.0e11)
        assertTrue(EventCalculator.events(outside, outside.plusSeconds(86_400 * 400)).isEmpty())
        assertTrue(EventCalculator.events(farFuture, farFuture.plusSeconds(86_400)).isEmpty())
        assertTrue(EventCalculator.events(distantPast.minusSeconds(86_400), distantPast.plusSeconds(86_400)).isEmpty())
        val seasons = EventOptions(kinds = setOf(EventKind.SEASON, EventKind.LUNAR_ECLIPSE, EventKind.METEOR_SHOWER))
        val early = EventCalculator.events(distantPast.plusSeconds(86_400 * 30), distantPast.plusSeconds(86_400 * 400), EventsTestSupport.dallas.observer, seasons)
        assertEquals(4, count(early, EventKind.SEASON))
        val late = EventCalculator.events(distantFuture.minusSeconds(86_400 * 400), distantFuture.minusSeconds(86_400 * 30), EventsTestSupport.dallas.observer, seasons)
        assertEquals(4, count(late, EventKind.SEASON))
        assertTrue(count(late, EventKind.METEOR_SHOWER) > 10)
    }

    @Test
    fun testOptionsAreNormalized() {
        val defaults = EventOptions()
        assertEquals(EventKind.defaultKinds, defaults.kinds)
        assertEquals(3.0, defaults.planetConjunctionThreshold, 0.0)
        assertEquals(5.0, defaults.moonConjunctionThreshold, 0.0)
        assertEquals(10.0, defaults.minimumSolarElongation, 0.0)
        assertEquals(10.0, defaults.minimumAltitude, 0.0)
        assertNull(defaults.timeZone)
        assertFalse(defaults.visibleOnly)
        assertEquals(defaults, EventOptions())
        val bad = EventOptions(
            planetConjunctionThreshold = Double.NaN, moonConjunctionThreshold = Double.POSITIVE_INFINITY,
            minimumSolarElongation = -4.0, minimumAltitude = 200.0,
        )
        assertEquals(3.0, bad.planetConjunctionThreshold, 0.0)
        assertEquals(5.0, bad.moonConjunctionThreshold, 0.0)
        assertEquals(0.0, bad.minimumSolarElongation, 0.0)
        assertEquals(90.0, bad.minimumAltitude, 0.0)
        assertEquals(30.0, EventOptions(planetConjunctionThreshold = 99.0).planetConjunctionThreshold, 0.0)
    }

    @Test
    fun testEventKindRawValuesAndCodable() {
        assertEquals(
            listOf(
                "meteorShower", "conjunction", "opposition", "greatestElongation", "earthApsis", "season",
                "fullMoonSpecial", "lunarEclipse", "solarEclipse",
            ),
            EventKind.entries.map { it.rawValue },
        )
        for (kind in EventKind.entries) {
            assertEquals(kind, EventKind.fromRawValue(kind.rawValue))
            assertEquals("\"${kind.rawValue}\"", Json.encodeToString(EventKind.serializer(), kind))
            assertEquals(kind, Json.decodeFromString(EventKind.serializer(), "\"${kind.rawValue}\""))
            assertTrue(kind.displayName.isNotEmpty())
        }
        assertNull(EventKind.fromRawValue("eclipse"))
        assertEquals(EventKind.entries.size - 1, EventKind.defaultKinds.size)
        assertEquals("\"P1\"", Json.encodeToString(EclipseContact.serializer(), EclipseContact.P1))
        assertEquals(EclipseContact.MAXIMUM, EclipseContact.fromRawValue("maximum"))
        assertEquals("\"total\"", Json.encodeToString(LunarEclipseKind.serializer(), LunarEclipseKind.TOTAL))
        assertEquals("\"hybrid\"", Json.encodeToString(SolarEclipseKind.serializer(), SolarEclipseKind.HYBRID))
        assertEquals("\"juneSolstice\"", Json.encodeToString(SeasonKind.serializer(), SeasonKind.JUNE_SOLSTICE))
    }

    @Test
    fun testEventTitlesAreReadable() {
        val events = EventCalculator.events(
            utc("2025-01-01T00:00:00Z"), utc("2026-12-31T23:59:59Z"), null,
            EventOptions(kinds = EventKind.entries.toSet(), planetConjunctionThreshold = 1.0, moonConjunctionThreshold = 0.0),
        )
        val titles = events.map { it.title }.toSet()
        for (expected in listOf(
            "Perseids peak", "Venus and Jupiter", "Mars at opposition", "Venus at greatest eastern elongation",
            "Mercury at greatest western elongation", "Earth at perihelion", "Earth at aphelion", "June solstice",
            "March equinox", "Micromoon and Blue Moon", "Supermoon", "Total lunar eclipse", "Partial lunar eclipse",
            "Annular solar eclipse", "Total solar eclipse", "Saturn at opposition",
        )) {
            assertTrue(expected in titles, "missing $expected in $titles")
        }
        assertTrue(events.all { it.title.isNotEmpty() })
    }

    // MARK: Visibility

    private val dallasYear = Pair(utc("2026-10-18T00:00:00Z"), utc("2027-10-18T00:00:00Z"))

    private fun dallasEvents(options: EventOptions = EventOptions(timeZone = ZoneId.of("America/Chicago"))): List<AstronomicalEvent> =
        EventCalculator.events(dallasYear.first, dallasYear.second, EventsTestSupport.dallas.observer, options)

    @Test
    fun testVisibilityComesWithAnObserver() {
        val events = dallasEvents()
        assertTrue(events.size > 100)
        for (event in events) {
            when (event.kind) {
                EventKind.SEASON, EventKind.EARTH_APSIS -> assertNull(event.visibility, event.kind.rawValue)
                else -> assertNotNull(event.visibility, "${event.kind.rawValue} ${event.date}")
            }
        }
        val observed = events.mapNotNull { it.visibility }
        for (visibility in observed) {
            assertEquals(visibility.sunAltitude < -18, visibility.isDark)
            assertEquals(visibility.bestWindow != null, visibility.isObservable)
            assertEquals(visibility.bestWindow != null, visibility.bestTime != null)
            assertTrue(visibility.altitude >= -90 && visibility.altitude <= 90)
            assertTrue(visibility.azimuth >= 0 && visibility.azimuth < 360)
        }
    }

    @Test
    fun testWindowsLieInTheNightOfTheEvent() {
        val observer = EventsTestSupport.dallas.observer
        for (event in dallasEvents()) {
            val visibility = event.visibility ?: continue
            val window = visibility.bestWindow ?: continue
            val best = assertNotNull(visibility.bestTime, event.kind.rawValue)
            assertTrue(best >= window.start && best <= window.end, "${event.kind.rawValue} ${event.date}")
            if (event.kind == EventKind.SOLAR_ECLIPSE || event.kind == EventKind.LUNAR_ECLIPSE) continue
            // Night windows: within a day of the event, with the Sun below the darkness limit and the target up.
            assertTrue(abs(EventsTestSupport.seconds(window.start, event.date)) < 86_400, "${event.kind.rawValue} ${event.date} ${window.start}")
            val sun = SkyFrame(best, observer).horizontal(SolarSystemBody.SUN, false).altitude
            assertTrue(sun <= visibility.darknessLimit + 0.01, "${event.kind.rawValue} ${event.date}: Sun at $sun")
        }
    }

    @Test
    fun testTargetIsAboveTheMinimumAltitudeInTheWindow() {
        val observer = EventsTestSupport.dallas.observer
        val options = EventOptions(minimumAltitude = 25.0, timeZone = ZoneId.of("America/Chicago"))
        var checked = 0
        for (event in dallasEvents(options)) {
            val visibility = event.visibility ?: continue
            val window = visibility.bestWindow ?: continue
            val details = event.details
            val frame = SkyFrame(visibility.bestTime ?: window.start, observer)
            val altitude = when (details) {
                is EventDetails.MeteorShower -> frame.horizontal(details.peak.radiant, null, false).altitude
                is EventDetails.Opposition -> frame.horizontal(details.opposition.planet, false).altitude
                is EventDetails.GreatestElongation -> frame.horizontal(details.elongation.planet, false).altitude
                is EventDetails.FullMoonSpecial -> frame.horizontal(SolarSystemBody.MOON, false).altitude
                else -> continue
            }
            assertTrue(altitude >= 25.0 - 0.2, "${event.kind.rawValue} ${event.date}: $altitude")
            checked += 1
        }
        assertTrue(checked > 10)
    }

    @Test
    fun testVisibleOnlyDropsWhatCannotBeSeen() {
        val all = dallasEvents()
        val visible = dallasEvents(EventOptions(visibleOnly = true, timeZone = ZoneId.of("America/Chicago")))
        assertTrue(visible.size < all.size)
        assertTrue(visible.all { canBeSeen(it) })
        // Equinoxes and solstices have no visibility and stay.
        assertEquals(count(all, EventKind.SEASON), count(visible, EventKind.SEASON))
        // Everything in the full list that is observable is in the short one.
        assertEquals(
            all.count { canBeSeen(it) },
            visible.size,
        )
        // visibleOnly means nothing without an observer.
        val without = EventCalculator.events(dallasYear.first, dallasYear.second, null, EventOptions(visibleOnly = true))
        assertEquals(EventCalculator.events(dallasYear.first, dallasYear.second).size, without.size)
    }

    @Test
    fun testVisibilityOfAnEventComputedWithoutAnObserver() {
        val options = EventOptions(timeZone = ZoneId.of("America/Chicago"))
        val plain = EventCalculator.events(dallasYear.first, dallasYear.second, null, options)
        val observed = dallasEvents(options)
        assertEquals(plain.size, observed.size)
        val observer = EventsTestSupport.dallas.observer
        for (index in plain.indices step 7) {
            assertEquals(observed[index].visibility, EventCalculator.visibility(plain[index], observer, options), "${plain[index].kind} ${plain[index].date}")
        }
        // Equinoxes have none.
        val season = plain.first { it.kind == EventKind.SEASON }
        assertNull(EventCalculator.visibility(season, observer, options))
        // A solar eclipse without local circumstances gets them computed on demand.
        val eclipses = EventCalculator.events(
            utc("2026-08-12T00:00:00Z"), utc("2026-08-13T00:00:00Z"), null, EventOptions(kinds = setOf(EventKind.SOLAR_ECLIPSE)),
        )
        val burgos = assertNotNull(EventCalculator.visibility(eclipses[0], EventsTestSupport.burgos.observer))
        assertTrue(burgos.isObservable && burgos.contacts.size == 5)
    }

    @Test
    fun testTimeZoneOptionAndItsDefault() {
        assertEquals(ZoneOffset.UTC, EventVisibilityCalculator.defaultZone(null))
        assertEquals(ZoneOffset.ofHours(-6), EventVisibilityCalculator.defaultZone(EventsTestSupport.dallas.observer))
        assertEquals(ZoneOffset.ofHours(10), EventVisibilityCalculator.defaultZone(EventsTestSupport.sydney.observer))
        assertEquals(ZoneOffset.ofHours(12), EventVisibilityCalculator.defaultZone(Observer(GeographicLocation(0.0, 179.9))))
        assertEquals(ZoneOffset.ofHours(-12), EventVisibilityCalculator.defaultZone(Observer(GeographicLocation(0.0, -179.9))))
        // The viewing window follows the zone's night: the same event with an eastern and a western zone differs by the
        // noon-to-noon cut, but not when the event is in the middle of a night in both.
        val event = EventCalculator.events(utc("2026-12-14T00:00:00Z"), utc("2026-12-15T00:00:00Z"), null, EventOptions(kinds = setOf(EventKind.METEOR_SHOWER)))[0]
        val chicago = EventCalculator.visibility(event, EventsTestSupport.dallas.observer, EventOptions(timeZone = ZoneId.of("America/Chicago")))
        val derived = EventCalculator.visibility(event, EventsTestSupport.dallas.observer, EventOptions())
        assertEquals(chicago?.sunAltitude, derived?.sunAltitude)
        assertNotNull(chicago?.bestWindow)
    }

    @Test
    fun testPolarObserversAndTheSouthernHemisphere() {
        val options = EventOptions(kinds = setOf(EventKind.METEOR_SHOWER, EventKind.OPPOSITION, EventKind.FULL_MOON_SPECIAL))
        // Tromsø (69.65N) in June has no astronomical night: the Daytime Arietids (7 June) have no window and nothing breaks.
        val tromso = Observer(GeographicLocation(69.65, 18.96, 0.0))
        val june = EventCalculator.events(utc("2026-06-01T00:00:00Z"), utc("2026-07-31T00:00:00Z"), tromso, options)
        assertTrue(june.isNotEmpty())
        for (event in june) {
            if (event.kind == EventKind.METEOR_SHOWER) assertNull(event.visibility?.bestWindow, "${event.date}")
        }
        // Tromsø in December has dark all day: the Geminid window is the whole night.
        val december = EventCalculator.events(utc("2026-12-13T00:00:00Z"), utc("2026-12-16T00:00:00Z"), tromso, options)
        val geminids = assertNotNull(december.firstOrNull { it.kind == EventKind.METEOR_SHOWER }?.visibility)
        assertNotNull(geminids.bestWindow)
        // Sydney sees the Geminid radiant low in the north.
        val sydney = EventCalculator.events(utc("2026-12-13T00:00:00Z"), utc("2026-12-16T00:00:00Z"), EventsTestSupport.sydney.observer, options)
        assertNotNull(sydney.firstOrNull { it.kind == EventKind.METEOR_SHOWER })
    }

    // MARK: Aim targets

    @Test
    fun testAimTargetsPerKind() {
        val events = EventCalculator.events(
            year2026.first, year2026.second, null, EventOptions(kinds = EventKind.entries.toSet()),
        )
        for (event in events) {
            val targets = event.aimTargets
            when (val details = event.details) {
                is EventDetails.MeteorShower -> assertEquals(listOf<AimTarget>(AimTarget.Radiant(details.peak.shower)), targets)
                is EventDetails.Conjunction -> {
                    assertEquals(2, targets.size)
                    assertEquals(details.conjunction.first.aimTarget, targets[0])
                    assertEquals(details.conjunction.second.aimTarget, targets[1])
                }
                is EventDetails.Opposition -> assertEquals(listOf<AimTarget>(AimTarget.Body(details.opposition.planet)), targets)
                is EventDetails.GreatestElongation -> assertEquals(listOf<AimTarget>(AimTarget.Body(details.elongation.planet)), targets)
                is EventDetails.FullMoonSpecial, is EventDetails.LunarEclipse -> assertEquals(listOf<AimTarget>(AimTarget.Body(SolarSystemBody.MOON)), targets)
                is EventDetails.SolarEclipse -> assertEquals(listOf<AimTarget>(AimTarget.Body(SolarSystemBody.SUN)), targets)
                is EventDetails.Season, is EventDetails.EarthApsis -> assertTrue(targets.isEmpty())
            }
        }
    }

    @Test
    fun testAimDirectionFramesAConjunction() {
        // Venus and Jupiter on 2026-06-09 19:48 UT, 1.6° apart, seen from Dallas (afternoon: the numbers do not care).
        val events = EventCalculator.events(
            utc("2026-06-09T12:00:00Z"), utc("2026-06-10T00:00:00Z"), null,
            EventOptions(kinds = setOf(EventKind.CONJUNCTION), planetConjunctionThreshold = 2.0, moonConjunctionThreshold = 0.0),
        )
        val conjunction = events.first { (it.details as EventDetails.Conjunction).conjunction.first == ConjunctionTarget.Body(SolarSystemBody.VENUS) }
        val frame = SkyFrame(conjunction.date, EventsTestSupport.dallas.observer)
        val aim = assertNotNull(conjunction.aimDirection(frame, false))
        val venus = frame.horizontal(SolarSystemBody.VENUS, false)
        val jupiter = frame.horizontal(SolarSystemBody.JUPITER, false)
        val half = venus.angularSeparation(jupiter) / 2
        assertNear(half, aim.angularSeparation(venus), 0.01, "to Venus")
        assertNear(half, aim.angularSeparation(jupiter), 0.01, "to Jupiter")
        // One target: the target itself; none: nothing.
        val opposition = EventCalculator.events(
            utc("2026-01-10T00:00:00Z"), utc("2026-01-11T00:00:00Z"), null, EventOptions(kinds = setOf(EventKind.OPPOSITION)),
        )[0]
        val jupiterFrame = SkyFrame(opposition.date, EventsTestSupport.dallas.observer)
        assertEquals(jupiterFrame.horizontal(SolarSystemBody.JUPITER, true), opposition.aimDirection(jupiterFrame))
        val season = EventCalculator.events(
            utc("2026-03-20T00:00:00Z"), utc("2026-03-21T00:00:00Z"), null, EventOptions(kinds = setOf(EventKind.SEASON)),
        )[0]
        assertNull(season.aimDirection(jupiterFrame))
        assertTrue(season.aimTargets.isEmpty())
    }

    @Test
    fun testAimTargetsResolveToTheSky() {
        val frame = SkyFrame(utc("2026-06-09T20:00:00Z"), EventsTestSupport.boulder.observer)
        assertEquals(frame.horizontal(SolarSystemBody.MOON, true), AimTarget.Body(SolarSystemBody.MOON).horizontal(frame))
        val regulus = AimTarget.Fixed(ReferenceStar.REGULUS.position, ReferenceStar.REGULUS.properMotion)
        assertEquals(frame.horizontal(ReferenceStar.REGULUS.position, ReferenceStar.REGULUS.properMotion, false), regulus.horizontal(frame, false))
    }

    // MARK: Cost

    @Test
    fun testAYearCostsWellUnderASecond() {
        // The brief: a whole year for one observer in well under a second on a phone. A desktop JVM needs about 0.2 s
        // once warm (see API.md); the bound here is loose so that a slow machine and a cold JIT do not fail the build.
        val warmUp = EventCalculator.events(utc("2026-10-18T00:00:00Z"), utc("2026-11-18T00:00:00Z"), EventsTestSupport.dallas.observer)
        assertTrue(warmUp.isNotEmpty())
        val start = System.nanoTime()
        val events = EventCalculator.events(dallasYear.first, dallasYear.second, EventsTestSupport.dallas.observer)
        val seconds = (System.nanoTime() - start) / 1e9
        assertTrue(events.size > 100)
        assertTrue(seconds < 3.0, "a year with an observer took $seconds s")
    }
}
