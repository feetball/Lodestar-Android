package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.catalog.CatalogFixture
import net.kuecker.lodestar.kit.catalog.CatalogSmallBodyFixture
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.transit
import net.kuecker.lodestar.kit.comets.topocentricEquatorial
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.topocentricEquatorial
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hour angle, the meridian transit and the flip countdown of `MeridianClock`.
 *
 * Reference instants are the upper culminations of Sirius and the Moon that Skyfield (JPL DE440s) finds in
 * `riseset.json` (Boulder and Sydney, window of 2026-01-15); the sidereal day is 86 164.0905 s (IERS).
 */
class PlanningMeridianClockTests {
    private val boulder = PlanningTestSupport.boulder.observer
    private val sydney = PlanningTestSupport.sydney.observer

    /** Sirius as in the fixture generator (Hipparcos, ICRS J2000.0). */
    private val sirius = EquatorialCoordinate(101.28715539, -16.71611582)
    private val siriusMotion = ProperMotion(-546.01, -1223.08)

    /** Skyfield's upper culminations (`riseset.json`): Sirius at Boulder and Sydney, the Moon at Boulder. */
    private val boulderSiriusTransit = instantFromTimeIntervalSince1970(1_768_543_477.041)
    private val sydneySiriusTransit = instantFromTimeIntervalSince1970(1_768_482_089.926)
    private val boulderMoonCulmination = instantFromTimeIntervalSince1970(1_768_584_605.891)

    private val siderealDay = 86_164.0905
    private val siderealRatio = 360.985_647_366_29 / 360.0

    private fun siriusHourAngle(date: Instant, observer: Observer): Double {
        val apparent = SkyFrame(date, observer).apparentPlace(sirius, siriusMotion)
        return MeridianClock.hourAngle(apparent, date, observer)
    }

    private fun moonHourAngle(date: Instant, observer: Observer): Double {
        val apparent = SkyFrame(date, observer).topocentricEquatorial(SolarSystemBody.MOON)
        return MeridianClock.hourAngle(apparent, date, observer)
    }

    // MARK: Hour angle

    @Test
    fun testHourAngleIsZeroAtSkyfieldsTransitOfSirius() {
        assertEquals(0.0, siriusHourAngle(boulderSiriusTransit, boulder), 0.0005)
        assertEquals(0.0, siriusHourAngle(sydneySiriusTransit, sydney), 0.0005)
    }

    @Test
    fun testHourAngleIsNegativeBeforeTheTransitAndPositiveAfterItAtTheSiderealRate() {
        for (hours in listOf(0.25, 1.0, 5.0, 11.0)) {
            val after = boulderSiriusTransit.addingTimeInterval(hours * 3_600)
            val before = boulderSiriusTransit.addingTimeInterval(-hours * 3_600)
            assertEquals(hours * siderealRatio, siriusHourAngle(after, boulder), 0.001, "$hours h after")
            assertEquals(-hours * siderealRatio, siriusHourAngle(before, boulder), 0.001, "$hours h before")
        }
    }

    @Test
    fun testHourAngleWrapsAtTwelveHours() {
        val date = boulderSiriusTransit
        val siderealTime = SkyFrame(date, boulder).localApparentSiderealTime
        // The places whose hour angle is 179.85° (11.99 h) and 180.15° (−11.99 h).
        val justBefore = EquatorialCoordinate(siderealTime - 179.85, 10.0)
        val justAfter = EquatorialCoordinate(siderealTime - 180.15, 10.0)
        assertEquals(11.99, MeridianClock.hourAngle(justBefore, date, boulder), 1e-9)
        assertEquals(-11.99, MeridianClock.hourAngle(justAfter, date, boulder), 1e-9)
        // A quarter of a turn either side of the meridian is ±6 h.
        assertEquals(6.0, MeridianClock.hourAngle(EquatorialCoordinate(siderealTime - 90.0, 0.0), date, boulder), 1e-9)
        assertEquals(-6.0, MeridianClock.hourAngle(EquatorialCoordinate(siderealTime + 90.0, 0.0), date, boulder), 1e-9)
    }

    @Test
    fun testHourAngleOfAnUnusableCoordinateIsNotANumber() {
        assertTrue(MeridianClock.hourAngle(EquatorialCoordinate(Double.NaN, 0.0), boulderSiriusTransit, boulder).isNaN())
        assertTrue(MeridianClock.hourAngle(EquatorialCoordinate(Double.POSITIVE_INFINITY, 0.0), boulderSiriusTransit, boulder).isNaN())
    }

    // MARK: Transit

    @Test
    fun testTransitOfSiriusMatchesSkyfield() {
        for ((observer, expected) in listOf(boulder to boulderSiriusTransit, sydney to sydneySiriusTransit)) {
            val before = MeridianClock.transit(sirius, siriusMotion, expected.addingTimeInterval(-3 * 3_600.0), observer)
            assertEquals(0.0, assertNotNull(before.next).timeIntervalSince(expected), 1.5)
            val after = MeridianClock.transit(sirius, siriusMotion, expected.addingTimeInterval(3 * 3_600.0), observer)
            assertEquals(0.0, assertNotNull(after.previous).timeIntervalSince(expected), 1.5)
        }
    }

    @Test
    fun testTransitsBracketTheDateAWholeSiderealDayApart() {
        val date = boulderSiriusTransit.addingTimeInterval(5_000.0)
        val transits = MeridianClock.transit(sirius, siriusMotion, date, boulder)
        val next = assertNotNull(transits.next)
        val previous = assertNotNull(transits.previous)
        assertTrue(previous <= date && date < next)
        assertEquals(siderealDay, next.timeIntervalSince(previous), 1.0)
        assertEquals(0.0, previous.timeIntervalSince(boulderSiriusTransit), 1.5)
    }

    @Test
    fun testTransitsAreSearchedWithinOneDayOfTheDate() {
        assertEquals(86_400.0, MeridianClock.searchHalfSpan)
        for (offset in listOf(-40_000.0, 0.0, 10.0, 43_000.0, 80_000.0)) {
            val date = boulderSiriusTransit.addingTimeInterval(offset)
            val transits = MeridianClock.transit(sirius, siriusMotion, date, boulder)
            val next = assertNotNull(transits.next, "next at $offset")
            val previous = assertNotNull(transits.previous, "previous at $offset")
            assertTrue(next > date && next.timeIntervalSince(date) <= 86_400.0, "next at $offset")
            assertTrue(previous <= date && date.timeIntervalSince(previous) <= 86_400.0, "previous at $offset")
        }
    }

    @Test
    fun testHourAngleIsZeroAtEveryTransitTheSearchFinds() {
        val date = boulderSiriusTransit.addingTimeInterval(7_200.0)
        val moon = SolarSystemBody.MOON
        val jupiter = SolarSystemBody.JUPITER
        val ceres = CelestialObject.fromSmallBody(CatalogSmallBodyFixture.ceres)
        val cases: List<Pair<String, MeridianTransits>> = listOf(
            "Sirius" to MeridianClock.transit(sirius, siriusMotion, date, boulder),
            "Moon" to MeridianClock.transit(moon, date, boulder),
            "Jupiter" to MeridianClock.transit(jupiter, date, boulder),
            "Ceres" to MeridianClock.transit(ceres, date, boulder),
        )
        for ((name, transits) in cases) {
            for (transit in listOfNotNull(transits.next, transits.previous)) {
                val frame = SkyFrame(transit, boulder)
                val apparent = when (name) {
                    "Sirius" -> frame.apparentPlace(sirius, siriusMotion)
                    "Moon" -> frame.topocentricEquatorial(moon)
                    "Jupiter" -> frame.topocentricEquatorial(jupiter)
                    else -> assertNotNull(frame.topocentricEquatorial(CatalogSmallBodyFixture.ceres))
                }
                assertEquals(0.0, MeridianClock.hourAngle(apparent, transit, boulder), 0.0001, "$name at $transit")
            }
        }
    }

    @Test
    fun testTheMoonCrossesTheMeridianAwayFromItsHighestPoint() {
        // The Moon moves in declination, so its greatest altitude (Skyfield's culmination) is not at hour angle 0: here
        // by 39 s (over January 2026 the gap reaches 4 min at Boulder and 12 min at Tromsø).
        val date = boulderMoonCulmination.addingTimeInterval(-6 * 3_600.0)
        val transit = assertNotNull(MeridianClock.transit(SolarSystemBody.MOON, date, boulder).next)
        assertEquals(-39.0, transit.timeIntervalSince(boulderMoonCulmination), 3.0)
        assertEquals(0.0, moonHourAngle(transit, boulder), 0.0001)
        // The Moon drifts east, so its hour angle grows a little slower than a star's: 0.0105 h after 39 s.
        assertEquals(0.0105, moonHourAngle(boulderMoonCulmination, boulder), 0.001)
    }

    @Test
    fun testTheMoonsOtherTransitCanBeBeyondADay() {
        // The Moon transits every 24 h 50 min: just after one, the next is out of the day's reach, and just before
        // one, the previous is.
        val transit = assertNotNull(MeridianClock.transit(SolarSystemBody.MOON, boulderMoonCulmination.addingTimeInterval(-6 * 3_600.0), boulder).next)
        val after = MeridianClock.transit(SolarSystemBody.MOON, transit.addingTimeInterval(60.0), boulder)
        assertNull(after.next)
        assertEquals(0.0, assertNotNull(after.previous).timeIntervalSince(transit), 0.5)
        val before = MeridianClock.transit(SolarSystemBody.MOON, transit.addingTimeInterval(-60.0), boulder)
        assertNull(before.previous)
        assertEquals(0.0, assertNotNull(before.next).timeIntervalSince(transit), 0.5)
    }

    @Test
    fun testTheMoonTransitsAboutTwentyFourFiftyApart() {
        val transits = MeridianClock.transit(SolarSystemBody.MOON, boulderMoonCulmination.addingTimeInterval(-3_600.0), boulder)
        val first = assertNotNull(transits.next)
        val second = assertNotNull(MeridianClock.transit(SolarSystemBody.MOON, first.addingTimeInterval(3_600.0), boulder).next)
        assertEquals(24.84 * 3_600, second.timeIntervalSince(first), 20 * 60.0)
    }

    // MARK: Catalog objects

    @Test
    fun testObjectOverloadsFollowThePositionOverloads() {
        val catalog = CatalogFixture.catalog()
        val date = boulderSiriusTransit.addingTimeInterval(7_200.0)
        val star = assertNotNull(catalog.objectWithID("star.hip32349"))
        val fixed = MeridianClock.transit(star, date, boulder)
        val position = MeridianClock.transit(sirius, siriusMotion, date, boulder)
        assertEquals(position.previous, fixed.previous)
        assertEquals(position.next, fixed.next)
        assertEquals(0.0, assertNotNull(fixed.previous).timeIntervalSince(boulderSiriusTransit), 1.5)
        val moon = assertNotNull(catalog.objectWithID("moon"))
        assertEquals(MeridianClock.transit(SolarSystemBody.MOON, date, boulder), MeridianClock.transit(moon, date, boulder))
    }

    @Test
    fun testACelestialPoleNeverTransits() {
        val catalog = CatalogFixture.catalog()
        for (id in listOf("special.ncp", "special.scp")) {
            val pole = assertNotNull(catalog.objectWithID(id), id)
            assertEquals(MeridianTransits(next = null, previous = null), MeridianClock.transit(pole, boulderSiriusTransit, boulder), id)
        }
    }

    @Test
    fun testACometOrAsteroidTransitsLikeAnyObject() {
        val ceres = CelestialObject.fromSmallBody(CatalogSmallBodyFixture.ceres)
        val transits = MeridianClock.transit(ceres, boulderSiriusTransit, boulder)
        val next = assertNotNull(transits.next)
        val previous = assertNotNull(transits.previous)
        // Ceres is a main-belt asteroid: a transit comes about a sidereal day (plus its own motion) after the last.
        assertEquals(siderealDay, next.timeIntervalSince(previous), 600.0)
    }

    // MARK: Flip window

    @Test
    fun testFlipCountdownWithATenDegreeLimitIsFortyMinutesOfSiderealTime() {
        val window = assertNotNull(MeridianClock.flipWindow(hourAngle = 0.0, limitDegrees = 10.0))
        // 10° of hour angle is 40 minutes of sidereal time, 2400 sidereal seconds: a little less of clock time.
        assertEquals(2_400.0 / siderealRatio, window.secondsUntilLimit, 1e-6)
        assertEquals(39.89, window.secondsUntilLimit / 60, 0.005)
        assertFalse(window.isPastLimit)
    }

    @Test
    fun testFlipCountdownWithoutALimitRunsToTheMeridian() {
        val window = assertNotNull(MeridianClock.flipWindow(hourAngle = -1.0, limitDegrees = 0.0))
        assertEquals(3_600.0 / siderealRatio, window.secondsUntilLimit, 1e-6)
        assertFalse(window.isPastLimit)
        // The countdown agrees with the sky: Sirius is on the meridian one clock hour after this instant.
        val date = boulderSiriusTransit.addingTimeInterval(-3_600.0)
        val countdown = assertNotNull(MeridianClock.flipWindow(siriusHourAngle(date, boulder), 0.0))
        assertEquals(3_600.0, countdown.secondsUntilLimit, 2.0)
    }

    @Test
    fun testTheMountMustFlipOnceTheLimitIsReached() {
        val atLimit = assertNotNull(MeridianClock.flipWindow(hourAngle = 10.0 / 15.0, limitDegrees = 10.0))
        assertEquals(0.0, atLimit.secondsUntilLimit, 1e-9)
        assertTrue(atLimit.isPastLimit)
        val past = assertNotNull(MeridianClock.flipWindow(hourAngle = 1.0, limitDegrees = 10.0))
        assertTrue(past.isPastLimit)
        // 5° (20 sidereal minutes) beyond the limit.
        assertEquals(-1_200.0 / siderealRatio, past.secondsUntilLimit, 1e-6)
        val unlimited = assertNotNull(MeridianClock.flipWindow(hourAngle = 0.001, limitDegrees = 0.0))
        assertTrue(unlimited.isPastLimit)
    }

    @Test
    fun testFlipWindowWrapsTheHourAngleAndClampsTheLimit() {
        val wrapped = assertNotNull(MeridianClock.flipWindow(hourAngle = 13.0, limitDegrees = 0.0))
        assertEquals(assertNotNull(MeridianClock.flipWindow(hourAngle = -11.0, limitDegrees = 0.0)).secondsUntilLimit, wrapped.secondsUntilLimit, 1e-6)
        assertEquals(0.0..30.0, MeridianClock.limitDegreesRange)
        assertEquals(
            assertNotNull(MeridianClock.flipWindow(0.0, 30.0)).secondsUntilLimit,
            assertNotNull(MeridianClock.flipWindow(0.0, 45.0)).secondsUntilLimit,
        )
        assertEquals(
            assertNotNull(MeridianClock.flipWindow(0.0, 0.0)).secondsUntilLimit,
            assertNotNull(MeridianClock.flipWindow(0.0, -5.0)).secondsUntilLimit,
        )
    }

    @Test
    fun testFlipWindowRefusesInputThatIsNotFinite() {
        assertNull(MeridianClock.flipWindow(hourAngle = Double.NaN, limitDegrees = 0.0))
        assertNull(MeridianClock.flipWindow(hourAngle = 0.0, limitDegrees = Double.NaN))
        assertNull(MeridianClock.flipWindow(hourAngle = Double.POSITIVE_INFINITY, limitDegrees = 0.0))
        assertNull(MeridianClock.flipWindow(hourAngle = 0.0, limitDegrees = Double.NEGATIVE_INFINITY))
    }
}
