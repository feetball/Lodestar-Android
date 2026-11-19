package net.kuecker.lodestar.kit.ephemeris

import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Jupiter's physical ephemeris, the Galilean moons and the Great Red Spot.
 *
 * References:
 * - Meeus, *Astronomical Algorithms* (2nd ed.), Example 43.a (physical ephemeris of Jupiter, 1992 December 16, 0h TD,
 *   JDE 2448972.50068): ω1 = 268.06°, ω2 = 72.74°, DE = −2.48°, DS = −2.20°, P = 24.80°; Example 43.b (the low-accuracy
 *   formulae of the same chapter): DS = −2.19°, DE = −2.50°.
 * - Meeus, Example 44.a (satellites of Jupiter, the same instant): X, Y of Io −3.44 +0.21, Europa +7.44 +0.25,
 *   Ganymede +1.24 +0.65, Callisto +7.08 +1.10, and the exercise of 1988 November 23 (Callisto and Ganymede at
 *   conjunction with Jupiter's centre).
 * - Flandrau Science Center (Tucson), *Skywatcher's Guide: April and May 2026*, Great Red Spot meridian transits
 *   from Sky & Telescope's tool for 2026 (91° on June 1, 1.75° a month), local times MST (UTC−7).
 */
class EphemerisJupiterTests {
    private fun utc(text: String): Instant = Instant.parse(text)

    private fun assertNear(expected: Double, actual: Double, tolerance: Double, label: String) {
        assertTrue(abs(expected - actual) <= tolerance, "$label: $actual differs from $expected by more than $tolerance")
    }

    private val example = JulianDate.fromTt(2_448_972.50068)

    // MARK: Physical ephemeris

    @Test
    fun testJupiterPhysicalMatchesMeeusExample43a() {
        val state = JupiterPhysical.state(example)
        // The book prints two decimals; the port gives 268.0628, 72.7351, -2.4847, -2.1981 and 24.8008.
        assertNear(268.06, state.systemILongitude, 0.01, "ω1")
        assertNear(72.74, state.systemIILongitude, 0.01, "ω2")
        assertNear(-2.48, state.earthDeclination, 0.01, "DE")
        assertNear(-2.20, state.sunDeclination, 0.01, "DS")
        assertNear(24.80, state.positionAngle, 0.01, "P")
        assertNear(5.6, state.distanceAU, 0.5, "Δ")
    }

    @Test
    fun testCentralMeridiansAdvanceAtTheRotationRates() {
        val first = JupiterPhysical.state(JulianDate(2_461_000.0))
        val second = JupiterPhysical.state(JulianDate(2_461_000.0 + 1.0 / 24))
        // System I turns 877.9° a day and System II 870.27°: 36.58° and 36.26° an hour (the light-time wiggle is tiny).
        assertNear(36.58, AstroMath.normalizeDegrees(second.systemILongitude - first.systemILongitude), 0.02, "I per hour")
        assertNear(36.26, AstroMath.normalizeDegrees(second.systemIILongitude - first.systemIILongitude), 0.02, "II per hour")
        for (state in listOf(first, second)) {
            assertTrue(state.systemILongitude in 0.0..360.0 && state.systemIILongitude in 0.0..360.0)
            assertTrue(abs(state.earthDeclination) < 4 && abs(state.sunDeclination) < 4)
            assertTrue(state.positionAngle in 0.0..360.0)
        }
    }

    // MARK: Galilean moons

    @Test
    fun testGalileanMoonsMatchMeeusExample44a() {
        val state = GalileanMoons.positions(example)
        assertEquals(listOf(GalileanMoon.IO, GalileanMoon.EUROPA, GalileanMoon.GANYMEDE, GalileanMoon.CALLISTO), state.moons.map { it.moon })
        val expected = listOf(-3.44 to 0.21, 7.44 to 0.25, 1.24 to 0.65, 7.08 to 1.10)
        for ((index, moon) in state.moons.withIndex()) {
            assertNear(expected[index].first, moon.x, 0.006, "${moon.moon} X")
            assertNear(expected[index].second, moon.y, 0.006, "${moon.moon} Y")
        }
        assertNear(-2.50, state.earthDeclination, 0.01, "DE of example 43.b")
        assertNear(-2.19, state.sunDeclination, 0.01, "DS of example 43.b")
    }

    @Test
    fun testGalileanMoonsConjunctionExerciseOfMeeus() {
        // Meeus's exercise (1988 November 23, times in UT): Ganymede (III) crosses X = 0 at 7h28m with Y = -0.84 and
        // Callisto (IV) at 5h15m with X = +0.06, Y = +1.48.
        val ganymede = GalileanMoons.positions(JulianDate(utc("1988-11-23T07:28:00Z"))).moons[2]
        assertNear(0.0, ganymede.x, 0.02, "Ganymede X")
        assertNear(-0.84, ganymede.y, 0.02, "Ganymede Y")
        val callisto = GalileanMoons.positions(JulianDate(utc("1988-11-23T05:15:00Z"))).moons[3]
        assertNear(0.06, callisto.x, 0.02, "Callisto X")
        assertNear(1.48, callisto.y, 0.02, "Callisto Y")
    }

    @Test
    fun testGalileanMoonsAgreeWithJplHorizons() {
        // JPL Horizons vector tables (jup365 satellites, DE441 Jupiter, Sun and Earth; queried 2026-11-19) turned into
        // Jupiter's axes (X west along the equator of the IAU 2015 pole, Y north) as seen from the geocentre: the four
        // moons 2615 s of light-time before 2026-11-19 00:42:15.8 UTC, in Jupiter radii (equatorial 71 492 km).
        val truth = listOf(0.889 to 0.027, -2.349 to -0.111, 4.544 to 0.062, -20.011 to -0.028)
        val state = GalileanMoons.positions(JulianDate(utc("2026-11-19T00:42:15.8Z")))
        for ((index, moon) in state.moons.withIndex()) {
            assertNear(truth[index].first, moon.x, 0.12, "${moon.moon} X")
            assertNear(truth[index].second, moon.y, 0.15, "${moon.moon} Y")
        }
    }

    @Test
    fun testGalileanMoonEventsAgreeWithJplHorizons() {
        // The same Horizons vectors give the contacts of the phenomena (transit: the centre inside the disc in front;
        // shadow transit: the centre of the shadow on the visible hemisphere; occultation and eclipse likewise), found
        // on a 2-minute grid and quoted at the middle of the step, observer time. The low-accuracy method agrees to
        // about 2 minutes for Io and Europa and 6 for Callisto (139 events in three six-day windows from 2026-11-19 to
        // 2027-02-16: rms 1.8 minutes, worst 6 minutes), the basis of `GalileanMoons.accuracyMinutes`.
        val truth = listOf(
            Triple(GalileanMoon.IO, GalileanPhenomenon.TRANSIT, GalileanEdge.END) to "2026-11-19T00:49:15Z",
            Triple(GalileanMoon.IO, GalileanPhenomenon.ECLIPSE, GalileanEdge.START) to "2026-11-19T18:27:10Z",
            Triple(GalileanMoon.IO, GalileanPhenomenon.OCCULTATION, GalileanEdge.START) to "2026-11-19T19:43:09Z",
            Triple(GalileanMoon.EUROPA, GalileanPhenomenon.SHADOW_TRANSIT, GalileanEdge.START) to "2026-11-20T11:45:04Z",
            Triple(GalileanMoon.EUROPA, GalileanPhenomenon.TRANSIT, GalileanEdge.START) to "2026-11-20T14:19:03Z",
            Triple(GalileanMoon.IO, GalileanPhenomenon.SHADOW_TRANSIT, GalileanEdge.START) to "2026-11-20T15:47:03Z",
            Triple(GalileanMoon.IO, GalileanPhenomenon.TRANSIT, GalileanEdge.START) to "2026-11-20T17:01:03Z",
            Triple(GalileanMoon.GANYMEDE, GalileanPhenomenon.ECLIPSE, GalileanEdge.START) to "2026-11-21T23:12:53Z",
        )
        val events = GalileanMoons.events(utc("2026-11-19T00:00:00Z"), utc("2026-11-23T00:00:00Z"))
        for ((key, expected) in truth) {
            val found = events.filter { it.moon == key.first && it.phenomenon == key.second && it.edge == key.third }
            val nearest = found.minOfOrNull { abs(it.date.timeIntervalSince(utc(expected))) }
            assertTrue(nearest != null && nearest <= 6.5 * 60, "$key: ${nearest} s from $expected")
        }
    }

    @Test
    fun testMoonOffsetsStayOnTheirOrbits() {
        val radii = listOf(5.9057, 9.3966, 14.9883, 26.3627)
        for (day in 0 until 40) {
            val state = GalileanMoons.positions(JulianDate(2_461_000.5 + day * 0.9))
            for ((index, moon) in state.moons.withIndex()) {
                val distance = kotlin.math.sqrt(moon.x * moon.x + moon.y * moon.y + moon.z * moon.z)
                assertNear(radii[index], distance, 0.2, "${moon.moon} distance")
                assertEquals(moon.x * moon.x + moon.y * moon.y < 1 && moon.z > 0, moon.isInTransit)
                assertFalse(moon.isInTransit && moon.isBehindDisc)
            }
        }
    }

    @Test
    fun testMoonPhenomenaStartBeforeTheyEnd() {
        val from = utc("2026-11-19T00:00:00Z")
        val events = GalileanMoons.events(from, utc("2026-11-29T00:00:00Z"))
        assertTrue(events.size > 40, "events: ${events.size}")
        assertEquals(events.sortedBy { it.date }, events)
        assertTrue(events.all { it.date >= from && it.date <= utc("2026-11-29T00:00:00Z") })
        for (moon in GalileanMoon.entries) {
            for (phenomenon in GalileanPhenomenon.entries) {
                val series = events.filter { it.moon == moon && it.phenomenon == phenomenon }
                // Edges alternate, and a phenomenon that is under way at the start of the search begins with an end.
                for ((previous, next) in series.zipWithNext()) {
                    assertTrue(previous.edge != next.edge, "$moon $phenomenon alternates")
                }
                for (start in series.filter { it.edge == GalileanEdge.START }) {
                    val end = series.firstOrNull { it.edge == GalileanEdge.END && it.date > start.date } ?: continue
                    val minutes = end.date.timeIntervalSince(start.date) / 60
                    assertTrue(minutes in 20.0..800.0, "$moon $phenomenon lasts $minutes min")
                }
            }
        }
    }

    @Test
    fun testIoTransitsRepeatEveryOrbit() {
        val starts = GalileanMoons.events(utc("2026-11-19T00:00:00Z"), utc("2026-12-19T00:00:00Z"))
            .filter { it.moon == GalileanMoon.IO && it.phenomenon == GalileanPhenomenon.TRANSIT && it.edge == GalileanEdge.START }
        assertTrue(starts.size in 16..18, "Io transits: ${starts.size}")
        for ((previous, next) in starts.zipWithNext()) {
            val days = next.date.timeIntervalSince(previous.date) / 86_400
            // Io's synodic period is 1.7699 days.
            assertNear(1.7699, days, 0.08, "Io transit spacing")
        }
    }

    @Test
    fun testShadowTransitAndTransitAreSeparatedByTheGeometry() {
        // Io's shadow enters the disc before Io itself leads or trails it by a fraction of its orbit, never by more than
        // a quarter: the shadow lies within about a 12° phase angle of the moon's own line of sight.
        val events = GalileanMoons.events(utc("2026-11-19T00:00:00Z"), utc("2026-11-29T00:00:00Z"))
        val transits = events.filter { it.moon == GalileanMoon.IO && it.phenomenon == GalileanPhenomenon.TRANSIT && it.edge == GalileanEdge.START }
        val shadows = events.filter { it.moon == GalileanMoon.IO && it.phenomenon == GalileanPhenomenon.SHADOW_TRANSIT && it.edge == GalileanEdge.START }
        assertTrue(transits.isNotEmpty() && shadows.isNotEmpty())
        for (shadow in shadows) {
            val nearest = transits.minOf { abs(it.date.timeIntervalSince(shadow.date)) }
            assertTrue(nearest < 0.25 * 1.77 * 86_400, "shadow is $nearest s from the nearest transit")
        }
    }

    @Test
    fun testMoonEventsEdgeCases() {
        val from = utc("2026-11-19T00:00:00Z")
        assertTrue(GalileanMoons.events(utc("2026-11-20T00:00:00Z"), from).isEmpty())
        assertTrue(GalileanMoons.events(from, from).isEmpty())
        // Longer intervals are cut at the limit.
        val cut = GalileanMoons.events(from, utc("2027-11-19T00:00:00Z"))
        assertTrue(cut.last().date <= from.addingTimeInterval(GalileanMoons.maximumSpanDays * 86_400))
        val event = GalileanMoonEvent(GalileanMoon.IO, GalileanPhenomenon.SHADOW_TRANSIT, GalileanEdge.START, from)
        assertEquals("Io's shadow enters the disc", event.title)
        assertEquals("Europa reappears from behind Jupiter",
            GalileanMoonEvent(GalileanMoon.EUROPA, GalileanPhenomenon.OCCULTATION, GalileanEdge.END, from).title)
        assertEquals("Callisto enters Jupiter's shadow",
            GalileanMoonEvent(GalileanMoon.CALLISTO, GalileanPhenomenon.ECLIPSE, GalileanEdge.START, from).title)
        assertEquals("Ganymede leaves the disc",
            GalileanMoonEvent(GalileanMoon.GANYMEDE, GalileanPhenomenon.TRANSIT, GalileanEdge.END, from).title)
        assertEquals(listOf("I", "E", "G", "C"), GalileanMoon.entries.map { it.initial })
        for (moon in GalileanMoon.entries) {
            assertEquals(moon, GalileanMoon.fromRawValue(moon.rawValue))
            assertEquals("\"${moon.rawValue}\"", Json.encodeToString(GalileanMoon.serializer(), moon))
        }
        assertNull(GalileanMoon.fromRawValue("titan"))
        assertEquals(GalileanPhenomenon.ECLIPSE, GalileanPhenomenon.fromRawValue("eclipse"))
        assertEquals(GalileanEdge.END, GalileanEdge.fromRawValue("end"))
    }

    // MARK: Great Red Spot

    @Test
    fun testGreatRedSpotTransitsAreAboutTenHoursApart() {
        val transits = GreatRedSpot.reference.transits(utc("2026-11-19T00:00:00Z"), utc("2026-11-22T00:00:00Z"))
        assertTrue(transits.size in 7..8, "transits: ${transits.size}")
        for ((previous, next) in transits.zipWithNext()) {
            val minutes = next.date.timeIntervalSince(previous.date) / 60
            // System II turns once in 9 h 55.7 min (360° at 870.27° a day, a little longer as the spot drifts east).
            assertNear(9 * 60 + 55.7, minutes, 0.5, "spacing")
        }
        for (transit in transits) {
            val jupiter = JupiterPhysical.state(JulianDate(transit.date))
            assertNear(transit.longitude, jupiter.systemIILongitude, 0.001, "CM II at the transit")
            assertEquals(100.0 * 60, transit.wellPlaced.duration, 1e-6)
            assertEquals(transit.date, transit.wellPlaced.start.addingTimeInterval(50 * 60.0))
        }
    }

    @Test
    fun testCentralMeridianAgreesWithPublishedGreatRedSpotTransitTimes() {
        // Flandrau Science Center, Tucson (MST = UTC-7): meridian transit 2026-04-05 20:18, 04-07 21:57, 04-10 19:28
        // and 05-30 21:03 local time (to the minute). The table's own longitude for the spot is not stated (it is not
        // Sky & Telescope's 91° on June 1: that would put the transits 5 to 10 minutes later), so the check is that the
        // System II central meridian stands at one longitude, drifting slowly, at the four instants: 84.4°, 84.4°, 84.7°
        // and 85.0° (the spot's drift of about 0.4° a month in their table).
        val published = listOf(
            "2026-04-06T03:18:00Z" to 84.4, "2026-04-08T04:57:00Z" to 84.4, "2026-04-11T02:28:00Z" to 84.7,
            "2026-05-31T04:03:00Z" to 85.0,
        )
        for ((text, longitude) in published) {
            val meridian = JupiterPhysical.state(JulianDate(utc(text))).systemIILongitude
            assertNear(longitude, meridian, 0.3, text)
        }
    }

    @Test
    fun testGreatRedSpotLongitudeDriftsFromTheEpoch() {
        val spot = GreatRedSpot.reference
        assertNear(91.0, spot.systemIILongitude(GreatRedSpot.referenceEpoch), 1e-9, "epoch")
        assertNear(91.0 + 1.75, spot.systemIILongitude(GreatRedSpot.referenceEpoch.addingTimeInterval(30 * 86_400.0)), 1e-9, "30 days")
        assertNear(91.0 - 1.75 / 30 * 61, spot.systemIILongitude(utc("2026-04-01T00:00:00Z")), 1e-9, "before")
        val wrapped = assertNotNull(GreatRedSpot.create(-10.0, utc("2026-06-01T00:00:00Z"), 0.0))
        assertNear(350.0, wrapped.longitude, 1e-9, "normalized")
        assertNear(350.0, wrapped.systemIILongitude(utc("2030-01-01T00:00:00Z")), 1e-9, "no drift")
    }

    @Test
    fun testGreatRedSpotTransitMovesWithTheLongitude() {
        // 1° of longitude is 1.65 minutes of transit time.
        val day = utc("2026-11-19T00:00:00Z")
        val epoch = utc("2026-11-19T00:00:00Z")
        val first = assertNotNull(GreatRedSpot.create(100.0, epoch, 0.0)).transits(day, day.addingTimeInterval(86_400.0))
        val second = assertNotNull(GreatRedSpot.create(110.0, epoch, 0.0)).transits(day, day.addingTimeInterval(86_400.0))
        val shift = second[0].date.timeIntervalSince(first[0].date) / 60
        // Ten degrees later in longitude means the planet has to turn 10° further: 16.5 minutes later (or a period earlier).
        val period = 9 * 60 + 55.7
        assertNear(16.5, ((shift % period) + period) % period, 0.4, "shift")
    }

    @Test
    fun testGreatRedSpotValidationAndLimits() {
        val epoch = utc("2026-06-01T00:00:00Z")
        assertNull(GreatRedSpot.create(Double.NaN, epoch))
        assertNull(GreatRedSpot.create(100.0, epoch, Double.POSITIVE_INFINITY))
        assertNull(GreatRedSpot.create(100.0, epoch, 31.0))
        assertNull(GreatRedSpot.create(100.0, Instant.MAX))
        assertNotNull(GreatRedSpot.create(100.0, epoch, -30.0))
        val spot = GreatRedSpot.reference
        val from = utc("2026-11-19T00:00:00Z")
        assertTrue(spot.transits(utc("2026-11-20T00:00:00Z"), from).isEmpty())
        val year = spot.transits(from, utc("2030-01-01T00:00:00Z"))
        assertTrue(year.size in 880..890, "a capped year: ${year.size}")
        assertTrue(year.last().date <= from.addingTimeInterval(GreatRedSpot.maximumSpanDays * 86_400))
        assertEquals(50.0, GreatRedSpot.wellPlacedMinutes)
    }
}
