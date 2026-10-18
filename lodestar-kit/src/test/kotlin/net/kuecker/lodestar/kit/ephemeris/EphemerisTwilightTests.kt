package net.kuecker.lodestar.kit.ephemeris

import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EphemerisTwilightTests {
    /** The wall-clock time `hour`:00 on a local calendar date (Swift: a Gregorian `Calendar` in `timeZone`). */
    private fun date(year: Int, month: Int, day: Int, hour: Int = 0, timeZone: ZoneId): Instant =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, timeZone).toInstant()

    /** The night that starts on a local calendar day, from any instant `hour` o'clock that day. */
    private fun nightStarting(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        observer: Observer,
        zone: ZoneId,
    ): NightTwilight =
        TwilightCalculator.night(date(year, month, day, hour, zone), observer, zone)

    private fun observer(latitude: Double, longitude: Double): Observer =
        Observer(GeographicLocation(latitude, longitude))

    /** The Sun's geometric altitude, from a fresh `SkyFrame`. */
    private fun sunAltitude(date: Instant, observer: Observer): Double =
        SkyFrame(date, observer).horizontal(SolarSystemBody.SUN, false).altitude

    private fun orderedEvents(night: NightTwilight): List<Instant?> =
        listOf(
            night.sunset, night.civilDusk, night.nauticalDusk, night.astronomicalDusk,
            night.astronomicalDawn, night.nauticalDawn, night.civilDawn, night.sunrise,
        )

    // MARK: Ordinary nights

    @Test
    fun testNightAtMidLatitudesHasTheTwilightsInOrder() {
        val zone = ZoneId.of("America/Denver")
        val boulder = observer(40.0, -105.27)
        val night = nightStarting(2026, 10, 17, hour = 9, observer = boulder, zone = zone)
        val events = orderedEvents(night).map { assertNotNull(it) }

        for ((earlier, later) in events.zip(events.drop(1))) assertTrue(earlier < later)
        assertTrue(night.interval.contains(events.firstOrNull() ?: distantPast))
        assertTrue(night.interval.contains(events.lastOrNull() ?: distantFuture))
        assertEquals(date(2026, 10, 17, hour = 12, timeZone = zone), night.interval.start)
        assertEquals(date(2026, 10, 18, hour = 12, timeZone = zone), night.interval.end)

        // At each event the Sun is at the corresponding altitude (to the resolution of the search).
        val thresholds = listOf(-0.8333, -6.0, -12.0, -18.0, -18.0, -12.0, -6.0, -0.8333)
        for ((event, threshold) in events.zip(thresholds)) {
            assertEquals(threshold, sunAltitude(event, boulder), 0.002)
        }
        // Boulder, 17 October: sunset 18:18 and sunrise 07:15 MDT (Skyfield, DE440s).
        val sunsetHour = assertNotNull(night.sunset).atZone(zone).hour
        val sunriseHour = assertNotNull(night.sunrise).atZone(zone).hour
        assertEquals(18, sunsetHour)
        assertEquals(7, sunriseHour)
        val astronomicalNight = assertNotNull(night.astronomicalNight)
        assertEquals(night.astronomicalDusk, astronomicalNight.start)
        assertEquals(night.astronomicalDawn, astronomicalNight.end)
        // The Sun takes 90 minutes to go from sunset to −18° at this latitude and season.
        assertEquals(90.0, astronomicalNight.start.timeIntervalSince(assertNotNull(night.sunset)) / 60, 3.0)
    }

    @Test
    fun testSolarMidnightIsTheLowestPointOfTheSun() {
        val zone = ZoneId.of("Europe/Berlin")
        val berlin = observer(52.52, 13.4)
        val night = nightStarting(2026, 3, 20, observer = berlin, zone = zone)
        assertTrue(night.interval.contains(night.solarMidnight))
        assertEquals(sunAltitude(night.solarMidnight, berlin), night.sunAltitudeAtMidnight, 1e-3)
        for (offset in listOf(-3600.0, -600.0, 600.0, 3600.0)) {
            assertTrue(
                sunAltitude(night.solarMidnight.addingTimeInterval(offset), berlin) > night.sunAltitudeAtMidnight,
            )
        }
        // At the equinox the Sun reaches −(90° − 52.52°) = −37.5° at midnight (−37.3° with the
        // declination of that night, +0.1°).
        assertEquals(-37.35, night.sunAltitudeAtMidnight, 0.15)
        // Solar midnight in Berlin (13.4° E, equation of time −7 min) is at 00:14 CET.
        val local = night.solarMidnight.atZone(zone)
        assertEquals(0, local.hour)
        assertEquals(14.0, local.minute.toDouble(), 4.0)
    }

    @Test
    fun testSouthernHemisphereAndLargeOffsets() {
        val cases = listOf(
            Triple("Australia/Sydney", -33.87, 151.21), Triple("Pacific/Auckland", -36.85, 174.76),
            Triple("Pacific/Kiritimati", 1.87, -157.4), Triple("America/Anchorage", 61.2, -149.9),
            Triple("Asia/Kolkata", 28.6, 77.2), Triple("Asia/Kathmandu", 27.7, 85.3),
        )
        for ((identifier, latitude, longitude) in cases) {
            val zone = ZoneId.of(identifier)
            val night = TwilightCalculator.night(
                date(2026, 1, 15, hour = 12, timeZone = zone), observer(latitude, longitude), zone,
            )
            val events = orderedEvents(night).map { assertNotNull(it, identifier) }
            for ((earlier, later) in events.zip(events.drop(1))) assertTrue(earlier < later, identifier)
            assertEquals(86_400.0, night.interval.duration, 1.0, identifier)
            assertTrue(night.interval.contains(night.solarMidnight), identifier)
        }
    }

    // MARK: Local dates and daylight saving

    @Test
    fun testIntervalRunsFromLocalNoonToLocalNoonAcrossDaylightSavingChanges() {
        val zone = ZoneId.of("America/New_York")
        val newYork = observer(40.71, -74.0)

        // Clocks go forward at 02:00 on 2026-03-08 and back at 02:00 on 2026-11-01.
        val springNight = nightStarting(2026, 3, 7, hour = 20, observer = newYork, zone = zone)
        assertEquals(23.0 * 3600, springNight.interval.duration, 0.001)
        val autumnNight = nightStarting(2026, 10, 31, hour = 20, observer = newYork, zone = zone)
        assertEquals(25.0 * 3600, autumnNight.interval.duration, 0.001)
        val ordinary = nightStarting(2026, 5, 5, hour = 3, observer = newYork, zone = zone)
        assertEquals(24.0 * 3600, ordinary.interval.duration, 0.001)

        for (night in listOf(springNight, autumnNight, ordinary)) {
            for (boundary in listOf(night.interval.start, night.interval.end)) {
                val local = boundary.atZone(zone)
                assertEquals(12, local.hour)
                assertEquals(0, local.minute)
                assertEquals(0, local.second)
            }
            for (event in orderedEvents(night)) {
                assertTrue(night.interval.contains(assertNotNull(event)))
            }
        }
        // The events follow the Sun, not the clock: the wall-clock gap across the change is an hour
        // different in the two directions but the physical times are continuous (about 4 minutes a day).
        val before = nightStarting(2026, 3, 6, hour = 12, observer = newYork, zone = zone)
        val shift = assertNotNull(springNight.sunset).timeIntervalSince(assertNotNull(before.sunset)) - 86_400
        assertEquals(90.0, shift, 60.0)
    }

    @Test
    fun testAnyInstantOfTheLocalDayGivesTheSameNight() {
        val zone = ZoneId.of("Asia/Tokyo")
        val tokyo = observer(35.68, 139.69)
        val firstSecond = date(2026, 6, 10, hour = 0, timeZone = zone).addingTimeInterval(1.0)
        val lastSecond = date(2026, 6, 10, hour = 23, timeZone = zone).addingTimeInterval(3599.0)
        val morning = TwilightCalculator.night(firstSecond, tokyo, zone)
        val evening = TwilightCalculator.night(lastSecond, tokyo, zone)
        assertEquals(evening, morning)
        // The following local day is a different night.
        val next = nightStarting(2026, 6, 11, hour = 1, observer = tokyo, zone = zone)
        assertEquals(morning.interval.end, next.interval.start)
    }

    @Test
    fun testTheSameInstantIsADifferentNightInDifferentTimeZones() {
        // 2026-10-17 03:00 UTC is already the 17th in Sydney but still the 16th in Denver.
        val instant = instantFromTimeIntervalSince1970(1_792_206_000.0)
        val denver = ZoneId.of("America/Denver")
        val sydney = ZoneId.of("Australia/Sydney")
        val inDenver = TwilightCalculator.night(instant, observer(40.0, -105.27), denver)
        val inSydney = TwilightCalculator.night(instant, observer(-33.87, 151.21), sydney)
        assertEquals(16, inDenver.interval.start.atZone(denver).dayOfMonth)
        assertEquals(17, inSydney.interval.start.atZone(sydney).dayOfMonth)
    }

    /**
     * A clock that is ten hours ahead of the Sun puts local noon at 02:00 solar time, so the window holds
     * a morning sunrise, the evening sunset, solar midnight, and ends before the next sunrise.
     */
    @Test
    fun testEventsBeforeSolarMidnightAreDuskAndAfterAreDawn() {
        val zone = ZoneOffset.ofTotalSeconds(10 * 3600)
        val night = nightStarting(2026, 3, 20, hour = 6, observer = observer(0.0, 0.0), zone = zone)
        val sunset = assertNotNull(night.sunset)
        assertNull(night.sunrise)
        assertNull(night.civilDawn)
        assertTrue(sunset < night.solarMidnight)
        // At the equator on the equinox the Sun sets at 18:07 solar time (18:00 plus semi-diameter and refraction).
        val solarTime = sunset.timeIntervalSince(night.interval.start) / 3600 + 2.0
        assertEquals(18.1, solarTime, 0.1)
        // The morning sunrise at about 06:00 solar time is before solar midnight and is not a dawn.
        val crossings = RiseSetCalculator.crossings(SolarSystemBody.SUN, observer(0.0, 0.0), night.interval)
        assertEquals(
            listOf(AltitudeCrossing.Kind.RISING, AltitudeCrossing.Kind.SETTING),
            crossings.map { it.kind },
        )
        assertEquals(
            night.astronomicalDusk?.let { DateInterval(it, night.interval.end) },
            night.astronomicalNight,
        )
    }

    // MARK: Polar regions

    @Test
    fun testMidnightSunAtTromsoHasNoSunsetOrTwilight() {
        val zone = ZoneId.of("Europe/Oslo")
        val night = nightStarting(2026, 6, 21, observer = observer(69.65, 18.96), zone = zone)
        for (event in orderedEvents(night)) assertNull(event)
        assertNull(night.astronomicalNight)
        // The Sun is 3° above the horizon at its lowest, around local solar midnight.
        assertEquals(3.08, night.sunAltitudeAtMidnight, 0.05)
    }

    @Test
    fun testPolarWinterAtTromsoHasTwilightsButNoSunrise() {
        val zone = ZoneId.of("Europe/Oslo")
        val tromso = observer(69.65, 18.96)
        val night = nightStarting(2026, 12, 21, observer = tromso, zone = zone)
        assertNull(night.sunset)
        assertNull(night.sunrise)
        // The Sun peaks at −3.1° at noon: it crosses −6°, −12° and −18° on its way down and up again.
        val twilights = listOf(
            night.civilDusk, night.nauticalDusk, night.astronomicalDusk,
            night.astronomicalDawn, night.nauticalDawn, night.civilDawn,
        )
        val events = twilights.map { assertNotNull(it) }
        for ((earlier, later) in events.zip(events.drop(1))) assertTrue(earlier < later)
        assertNotNull(night.astronomicalNight)
    }

    @Test
    fun testPolarWinterAtTheNorthPoleKeepsTheSunBelowEverything() {
        val zone = ZoneId.of("UTC")
        val pole = observer(89.9, 0.0)
        val night = nightStarting(2026, 12, 21, observer = pole, zone = zone)
        for (event in orderedEvents(night)) assertNull(event)
        // Below −18° for the whole interval: the entire interval is astronomical night.
        assertEquals(-23.54, night.sunAltitudeAtMidnight, 0.1)
        assertEquals(night.interval, night.astronomicalNight)
    }

    @Test
    fun testWhiteNightsLeaveCivilTwilightOnly() {
        // Helsinki at midsummer: the Sun dips to −6.4°, so only civil twilight is crossed.
        val zone = ZoneId.of("Europe/Helsinki")
        val night = nightStarting(2026, 6, 21, observer = observer(60.17, 24.94), zone = zone)
        assertNotNull(night.sunset)
        assertNotNull(night.civilDusk)
        assertNull(night.nauticalDusk)
        assertNull(night.astronomicalDusk)
        assertNull(night.astronomicalDawn)
        assertNull(night.nauticalDawn)
        assertNotNull(night.civilDawn)
        assertNotNull(night.sunrise)
        assertNull(night.astronomicalNight)
        assertEquals(-6.395, night.sunAltitudeAtMidnight, 0.05)
    }

    // MARK: astronomicalNight

    @Test
    fun testAstronomicalNightFollowsTheDuskAndDawnItHas() {
        val start = instantFromTimeIntervalSinceReferenceDate(1_000_000.0)
        val interval = DateInterval(start, 86_400.0)
        val dusk = start.addingTimeInterval(40_000.0)
        val dawn = start.addingTimeInterval(60_000.0)
        fun night(dusk: Instant?, dawn: Instant?, midnightAltitude: Double): NightTwilight =
            NightTwilight(
                sunset = null, civilDusk = null, nauticalDusk = null, astronomicalDusk = dusk,
                astronomicalDawn = dawn, nauticalDawn = null, civilDawn = null, sunrise = null,
                interval = interval, solarMidnight = start.addingTimeInterval(50_000.0),
                sunAltitudeAtMidnight = midnightAltitude,
            )

        val bothEvents = night(dusk, dawn, -30.0)
        assertEquals(DateInterval(dusk, dawn), bothEvents.astronomicalNight)
        val duskOnly = night(dusk, null, -30.0)
        assertEquals(DateInterval(dusk, interval.end), duskOnly.astronomicalNight)
        val dawnOnly = night(null, dawn, -30.0)
        assertEquals(DateInterval(start, dawn), dawnOnly.astronomicalNight)
        assertEquals(interval, night(null, null, -30.0).astronomicalNight)
        assertNull(night(null, null, -10.0).astronomicalNight)
    }

    // MARK: Reference fixture

    @Serializable
    private class FixtureCrossing(val kind: String, val unix: Double)

    @Serializable
    private class FixtureHorizon(val crossings: List<FixtureCrossing>)

    @Serializable
    private class FixtureSun(
        val horizon: FixtureHorizon,
        val civil: FixtureHorizon,
        val nautical: FixtureHorizon,
        val astronomical: FixtureHorizon,
    )

    @Serializable
    private class FixtureObjects(val sun: FixtureSun)

    @Serializable
    private class FixtureDay(
        val site: String,
        val lat: Double,
        val lon: Double,
        val elevationM: Double,
        val date: String,
        val windowStartUtc: String,
        val windowEndUtc: String,
        val objects: FixtureObjects,
    )

    @Serializable
    private class FixtureFile(val cases: List<FixtureDay>)

    /** One horizon of the fixture with the dusk and dawn the library found for it. */
    private data class HorizonCase(val name: String, val list: FixtureHorizon, val dusk: Instant?, val dawn: Instant?)

    /**
     * `riseset.json` windows run from local solar noon to the next; `ZoneOffset.ofTotalSeconds` with the
     * site's longitude in time gives the same local noon, so `night(localDate)` must reproduce the
     * fixture's Sun events: dusk is a set, dawn a rise.
     */
    @Test
    fun testNightsMatchSkyfieldForEverySiteAndDate() {
        val days = EphemerisFixtureStore.load<FixtureFile>("riseset.json").cases
        assertEquals(30, days.size)
        for (day in days) {
            val label = "${day.site} ${day.date}"
            val zone = ZoneOffset.ofTotalSeconds((day.lon * 240).roundedHalfAwayFromZero().toInt())
            val start = Instant.parse(day.windowStartUtc)
            val end = Instant.parse(day.windowEndUtc)
            val night = TwilightCalculator.night(start, observer(day.lat, day.lon), zone)
            assertEquals(0.0, night.interval.start.timeIntervalSince(start), 0.5, label)
            assertEquals(0.0, night.interval.end.timeIntervalSince(end), 0.5, label)

            val lists = listOf(
                HorizonCase("horizon", day.objects.sun.horizon, night.sunset, night.sunrise),
                HorizonCase("civil", day.objects.sun.civil, night.civilDusk, night.civilDawn),
                HorizonCase("nautical", day.objects.sun.nautical, night.nauticalDusk, night.nauticalDawn),
                HorizonCase(
                    "astronomical", day.objects.sun.astronomical, night.astronomicalDusk, night.astronomicalDawn,
                ),
            )
            for ((name, list, dusk, dawn) in lists) {
                val expectedDusk = list.crossings.firstOrNull { it.kind == "set" }
                val expectedDawn = list.crossings.firstOrNull { it.kind == "rise" }
                assertEquals(dusk == null, expectedDusk == null, "$label $name dusk present")
                assertEquals(dawn == null, expectedDawn == null, "$label $name dawn present")
                if (dusk != null && expectedDusk != null) {
                    assertEquals(expectedDusk.unix, dusk.timeIntervalSince1970, 1.5, "$label $name dusk")
                }
                if (dawn != null && expectedDawn != null) {
                    assertEquals(expectedDawn.unix, dawn.timeIntervalSince1970, 1.5, "$label $name dawn")
                }
            }
        }
    }

    // MARK: Consistency with the rise/set search

    @Test
    fun testNightAgreesWithTheGenericCrossingSearch() {
        val zone = ZoneId.of("Europe/London")
        val greenwich = observer(51.48, 0.0)
        val night = nightStarting(2026, 9, 22, observer = greenwich, zone = zone)
        val horizon = RiseSetCalculator.crossings(SolarSystemBody.SUN, greenwich, night.interval)
        assertEquals(
            listOf(AltitudeCrossing.Kind.SETTING, AltitudeCrossing.Kind.RISING),
            horizon.map { it.kind },
        )
        assertEquals(night.sunset, horizon[0].time)
        assertEquals(night.sunrise, horizon[1].time)
        // Twilight 18° below the horizon: the Sun is 18° down 1.5–2 hours after sunset at this latitude and date.
        val darkness = assertNotNull(night.astronomicalDusk).timeIntervalSince(assertNotNull(night.sunset)) / 3600
        assertTrue(darkness in 1.5..2.2, "dusk after $darkness h")
    }
}
