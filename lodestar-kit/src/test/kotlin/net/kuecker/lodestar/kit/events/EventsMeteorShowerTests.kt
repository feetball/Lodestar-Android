package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.events.EventsTestSupport.assertInstant
import net.kuecker.lodestar.kit.events.EventsTestSupport.assertNear
import net.kuecker.lodestar.kit.events.EventsTestSupport.utc
import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Meteor shower maxima.
 *
 * Reference: International Meteor Organization, *2026 Meteor Shower Calendar* (IMO INFO 3-25, edited by Jürgen Rendtel),
 * archived at web.archive.org/web/20251223170423/https://imo.net/files/meteor-shower/cal2026.pdf: Table 5 (working list),
 * Table 6 (radiant positions) and the shower descriptions, which give the 2026 maxima in UT: Lyrids April 22 19h 40m
 * (λ☉ 32.32°), Perseids August 13 02h to 04h (λ☉ 140.0° to 140.1°), September ε-Perseids September 9 18h (166.7°),
 * Draconids October 9 01h (195.4°), Leonids November 17 23h 45m (235.27°), Geminids December 14 14h (262.2°); the calendar
 * also states that the Quadrantid maximum falls at Full Moon and the Perseid and Geminid maxima in moon-free nights.
 */
class EventsMeteorShowerTests {
    private fun peaks(from: String, to: String): List<Pair<AstronomicalEvent, MeteorShowerPeak>> =
        EventsTestSupport.peaks(MeteorShowerFinder.events(utc(from), utc(to)))

    private fun peak(code: String, year: Int): Pair<AstronomicalEvent, MeteorShowerPeak> =
        assertNotNull(peaks("$year-01-01T00:00:00Z", "$year-12-31T23:59:59Z").firstOrNull { it.second.shower.code == code })

    @Test
    fun testMaximaOf2026AgainstTheImoCalendar() {
        assertInstant("2026-04-22T19:40:00Z", peak("LYR", 2026).first.date, 1_800.0, "Lyrids")
        assertInstant("2026-09-09T18:00:00Z", peak("SPE", 2026).first.date, 1_800.0, "September epsilon-Perseids")
        assertInstant("2026-10-09T01:00:00Z", peak("DRA", 2026).first.date, 2_400.0, "Draconids")
        assertInstant("2026-11-17T23:45:00Z", peak("LEO", 2026).first.date, 1_800.0, "Leonids")
        assertInstant("2026-12-14T14:00:00Z", peak("GEM", 2026).first.date, 1_800.0, "Geminids")
    }

    @Test
    fun testPerseidMaximumOf2026() {
        // IMO: August 13, 02h to 04h UT for λ☉ = 140.0° to 140.1°: the table value 140.0° is the early end.
        val perseids = peak("PER", 2026)
        val date = perseids.first.date
        assertTrue(date >= utc("2026-08-13T01:00:00Z") && date <= utc("2026-08-13T03:00:00Z"), "$date")
        assertEquals("Perseids", perseids.second.shower.name)
        assertEquals(100, perseids.second.shower.zhr)
        // The Sun's longitude (J2000 ecliptic) at the peak is the table value.
        assertNear(140.0, EventSearch.sunLongitudeJ2000(JulianDate(date)), 1e-5, "solar longitude")
        // New Moon on August 12: the Moon is a thin crescent at the peak (IMO: moon-free night).
        assertTrue(perseids.second.moonIlluminatedFraction < 0.01, "${perseids.second.moonIlluminatedFraction}")
    }

    @Test
    fun testMoonIlluminationAtTheMaxima() {
        // IMO 2026: the Quadrantid maximum falls at Full Moon, the Perseids and Geminids in moon-free nights.
        assertTrue(peak("QUA", 2026).second.moonIlluminatedFraction > 0.99)
        assertTrue(peak("PER", 2026).second.moonIlluminatedFraction < 0.01)
        assertTrue(peak("GEM", 2026).second.moonIlluminatedFraction < 0.5)
        // The η-Aquariid maximum is about four days after Full Moon: the Moon is well past full but still bright.
        val aquariids = peak("ETA", 2026).second.moonIlluminatedFraction
        assertTrue(aquariids > 0.7 && aquariids < 0.9, "$aquariids")
    }

    @Test
    fun testTheDateWorksEveryYear() {
        // The solar longitude fixes the date: the Perseid maximum moves by about six hours a year and one day in four
        // years (leap day), staying within August 12–14.
        for (year in 2026..2040) {
            val date = peak("PER", year).first.date.toString()
            assertTrue(date.startsWith("$year-08-12") || date.startsWith("$year-08-13") || date.startsWith("$year-08-14"), date)
            val geminids = peak("GEM", year).first.date.toString()
            assertTrue(geminids.startsWith("$year-12-13") || geminids.startsWith("$year-12-14") || geminids.startsWith("$year-12-15"), geminids)
        }
        // The Quadrantids belong to January of the year they are asked for.
        assertTrue(peak("QUA", 2027).first.date.toString().startsWith("2027-01-0"))
    }

    @Test
    fun testEveryShowerPeaksOncePerYear() {
        val found = peaks("2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z")
        assertEquals(MeteorShower.all.size, found.size)
        assertEquals(MeteorShower.all.map { it.code }.toSet(), found.map { it.second.shower.code }.toSet())
        for (index in 1 until found.size) assertTrue(found[index].first.date >= found[index - 1].first.date)
        // Three years hold three peaks of each.
        assertEquals(3 * MeteorShower.all.size, peaks("2026-01-01T00:00:00Z", "2028-12-31T23:59:59Z").size)
    }

    @Test
    fun testRangeEdgesSelectPeaks() {
        // The Perseids peak at 02:09 UT on August 13, 2026: a range ending before it does not contain it.
        assertTrue(peaks("2026-08-13T00:00:00Z", "2026-08-13T02:00:00Z").none { it.second.shower.code == "PER" })
        assertTrue(peaks("2026-08-13T02:00:00Z", "2026-08-13T03:00:00Z").any { it.second.shower.code == "PER" })
        assertTrue(peaks("2026-08-13T02:30:00Z", "2026-08-13T04:00:00Z").none { it.second.shower.code == "PER" })
    }

    @Test
    fun testTheListMatchesTable5() {
        val all = MeteorShower.all
        assertEquals(18, all.size)
        assertEquals(all.size, all.map { it.code }.toSet().size)
        assertEquals(all.size, all.map { it.iauNumber }.toSet().size)
        for (shower in all) {
            assertTrue(shower.peakSolarLongitude >= 0 && shower.peakSolarLongitude < 360, shower.code)
            assertTrue(shower.radiant.rightAscension >= 0 && shower.radiant.rightAscension < 360, shower.code)
            assertTrue(shower.radiant.declination in -90.0..90.0, shower.code)
            assertTrue(shower.zhr >= 5 && shower.velocity in 10.0..80.0 && shower.populationIndex in 1.5..3.5, shower.code)
            assertTrue(shower.activityStartMonth in 1..12 && shower.activityEndMonth in 1..12, shower.code)
            assertTrue(shower.activityStartDay in 1..31 && shower.activityEndDay in 1..31, shower.code)
            assertTrue(abs(shower.radiantDriftRightAscension) < 2 && abs(shower.radiantDriftDeclination) < 1, shower.code)
        }
        // Table 5 of the 2026 calendar for the showers the brief names.
        val perseids = assertNotNull(MeteorShower.withCode("per"))
        assertEquals(7, perseids.iauNumber)
        assertEquals(140.0, perseids.peakSolarLongitude, 0.0)
        assertEquals(48.0, perseids.radiant.rightAscension, 0.0)
        assertEquals(58.0, perseids.radiant.declination, 0.0)
        assertEquals(59.0, perseids.velocity, 0.0)
        assertEquals(2.2, perseids.populationIndex, 0.0)
        val geminids = assertNotNull(MeteorShower.withCode("GEM"))
        assertEquals(262.2, geminids.peakSolarLongitude, 0.0)
        assertEquals(150, geminids.zhr)
        assertEquals(112.0, geminids.radiant.rightAscension, 0.0)
        assertEquals(33.0, geminids.radiant.declination, 0.0)
        val quadrantids = assertNotNull(MeteorShower.withCode("QUA"))
        assertEquals(283.15, quadrantids.peakSolarLongitude, 0.0)
        assertEquals(80, quadrantids.zhr)
        for (code in listOf("QUA", "LYR", "ETA", "SDA", "CAP", "PER", "DRA", "ORI", "STA", "NTA", "LEO", "GEM", "URS")) {
            assertNotNull(MeteorShower.withCode(code), code)
        }
        assertNull(MeteorShower.withCode("XXX"))
    }

    @Test
    fun testRadiantDriftsWithTheDate() {
        val perseids = assertNotNull(MeteorShower.withCode("PER"))
        val maximum = peak("PER", 2026).first.date
        val atPeak = perseids.radiant(maximum)
        assertNear(48.0, atPeak.rightAscension, 1e-3, "radiant at the peak")
        assertNear(58.0, atPeak.declination, 1e-3, "radiant at the peak")
        val tenDaysLater = perseids.radiant(maximum.plusSeconds(10 * 86_400))
        assertNear(48.0 + 14.2, tenDaysLater.rightAscension, 1e-3, "RA ten days after")
        assertNear(58.0 + 2.0, tenDaysLater.declination, 1e-3, "Dec ten days after")
        // IMO Table 6 (rounded to whole degrees): Perseid radiant on Aug 5 at 37° +56° and on Aug 15 at 51° +58°.
        val august5 = perseids.radiant(utc("2026-08-05T00:00:00Z"))
        assertNear(37.0, august5.rightAscension, 1.0, "Table 6, August 5 right ascension")
        assertNear(56.0, august5.declination, 1.0, "Table 6, August 5 declination")
        val august15 = perseids.radiant(utc("2026-08-15T00:00:00Z"))
        assertNear(51.0, august15.rightAscension, 1.0, "Table 6, August 15 right ascension")
        assertNear(58.0, august15.declination, 1.0, "Table 6, August 15 declination")
        val before = perseids.radiant(maximum.minusSeconds(8 * 86_400))
        assertNear(48.0 - 8 * 1.42, before.rightAscension, 1e-3, "RA eight days before")
        // Long after the activity period the drift stops at its end.
        val december = perseids.radiant(maximum.plusSeconds(100 * 86_400))
        val end = perseids.radiant(utc("2026-08-24T23:59:00Z"))
        assertNear(end.rightAscension, december.rightAscension, 0.2, "clamped at the end of the activity")
        // The Draconids do not drift.
        val draconids = assertNotNull(MeteorShower.withCode("DRA"))
        assertEquals(draconids.radiant, draconids.radiant(utc("2026-10-07T00:00:00Z")))
    }

    @Test
    fun testActivityPeriods() {
        val perseids = assertNotNull(MeteorShower.withCode("PER"))
        val maximum = peak("PER", 2026)
        assertEquals(utc("2026-07-17T00:00:00Z"), maximum.second.activityStart)
        assertEquals(utc("2026-08-25T00:00:00Z"), maximum.second.activityEnd)
        assertEquals(DateInterval(utc("2026-07-17T00:00:00Z"), utc("2026-08-25T00:00:00Z")), perseids.activityPeriod(maximum.first.date))
        assertTrue(perseids.isActive(utc("2026-08-01T00:00:00Z")))
        assertTrue(!perseids.isActive(utc("2026-09-01T00:00:00Z")))
        assertTrue(!perseids.isActive(utc("2026-07-10T00:00:00Z")))
        // The Quadrantids are active over the turn of the year, Dec 28 to Jan 12.
        val quadrantids = peak("QUA", 2026)
        assertEquals(utc("2025-12-28T00:00:00Z"), quadrantids.second.activityStart)
        assertEquals(utc("2026-01-13T00:00:00Z"), quadrantids.second.activityEnd)
        val shower = assertNotNull(MeteorShower.withCode("QUA"))
        assertTrue(shower.isActive(utc("2026-01-01T12:00:00Z")))
        assertTrue(shower.isActive(utc("2025-12-30T12:00:00Z")))
        assertTrue(!shower.isActive(utc("2026-02-01T00:00:00Z")))
    }

    @Test
    fun testShowerWithBadValuesDoesNotCrash() {
        // A shower assembled by the caller may hold anything: nothing may trap.
        val broken = MeteorShower(
            "BAD", 0, "Broken", 13, 40, 0, 0, Double.NaN, 0, EquatorialCoordinate(400.0, 120.0),
            Double.NaN, Double.POSITIVE_INFINITY, Double.NaN, Double.NaN,
        )
        val moment = utc("2026-08-13T00:00:00Z")
        broken.peak(moment)
        broken.radiant(moment)
        broken.activityPeriod(moment)
        assertTrue(!broken.isActive(moment))
        val bent = MeteorShower("BNT", 0, "Bent", 2, 30, 14, 99, 1_000.0, 5, EquatorialCoordinate(10.0, 10.0), 1.0, 0.0, 20.0, 2.0)
        bent.peak(moment)
        bent.radiant(moment)
        bent.activityPeriod(moment)
        assertEquals(EquatorialCoordinate(40.0, 90.0), MeteorShower("X", 0, "X", 1, 1, 1, 2, 10.0, 5, EquatorialCoordinate(40.0, 90.0), 0.0, 0.0, 20.0, 2.0).radiant)
    }

    @Test
    fun testRadiantIsAimable() {
        val perseids = assertNotNull(MeteorShower.withCode("PER"))
        val maximum = peak("PER", 2026)
        val observer = EventsTestSupport.boulder.observer
        val frame = SkyFrame(maximum.first.date.plusSeconds(86_400 * 5), observer)
        val target = AimTarget.Radiant(perseids)
        val expected = frame.horizontal(perseids.radiant(frame.date), null, true)
        assertEquals(expected, target.horizontal(frame))
        assertEquals(frame.horizontal(perseids.radiant(frame.date), null, false), target.horizontal(frame, false))
        assertEquals(listOf<AimTarget>(AimTarget.Radiant(perseids)), maximum.first.aimTargets)
        // aimDirection of a single target is that target.
        assertEquals(expected, maximum.first.aimDirection(frame))
    }

    @Test
    fun testRadiantAltitudeAndWindowAtADarkSite() {
        // Boulder (40N 105.27W), Perseids 2026: the peak, 02:09 UT = 20:09 MDT on August 12, comes in daylight at the
        // observer's place; the radiant is highest before dawn and the window lies in the dark of the night.
        val options = EventOptions(kinds = setOf(EventKind.METEOR_SHOWER), timeZone = java.time.ZoneId.of("America/Denver"))
        val events = EventCalculator.events(
            utc("2026-08-12T00:00:00Z"), utc("2026-08-14T00:00:00Z"), EventsTestSupport.boulder.observer, options,
        )
        val perseids = events.first { (it.details as EventDetails.MeteorShower).peak.shower.code == "PER" }
        val visibility = assertNotNull(perseids.visibility)
        val window = assertNotNull(visibility.bestWindow)
        assertEquals(-18.0, visibility.darknessLimit, 0.0)
        // Astronomical dusk at Boulder on August 12 is about 22:00 MDT (04:00 UT August 13) and dawn about 04:50 MDT (10:50 UT).
        assertTrue(window.start >= utc("2026-08-13T03:00:00Z") && window.start <= utc("2026-08-13T05:00:00Z"), "${window.start}")
        assertTrue(window.end >= utc("2026-08-13T10:00:00Z") && window.end <= utc("2026-08-13T11:30:00Z"), "${window.end}")
        val best = assertNotNull(visibility.bestTime)
        assertTrue(best >= window.start && best <= window.end)
        val radiant = SkyFrame(best, EventsTestSupport.boulder.observer).horizontal(perseids.aimTargets[0].let { (it as AimTarget.Radiant).shower.radiant }, null, false)
        assertTrue(radiant.altitude > 60, "the Perseid radiant is high before dawn: ${radiant.altitude}")
        // At the peak itself the radiant is in the north-east sky but the Sun is still up.
        assertTrue(!visibility.isDark)
        assertTrue(visibility.sunAltitude > -18)
    }
}
