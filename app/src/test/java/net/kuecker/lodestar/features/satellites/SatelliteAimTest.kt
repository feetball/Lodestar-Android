package net.kuecker.lodestar.features.satellites

import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.satellites.ElementSetGroup
import net.kuecker.lodestar.kit.satellites.Satellite
import net.kuecker.lodestar.kit.satellites.SatellitePass
import net.kuecker.lodestar.kit.satellites.SatellitePassFinder
import net.kuecker.lodestar.kit.satellites.SatelliteSkyContext
import net.kuecker.lodestar.services.SatelliteGroup
import net.kuecker.lodestar.services.SatellitePassItem
import net.kuecker.lodestar.services.issOmmJson
import net.kuecker.lodestar.services.ommArray
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SatelliteAimTest {
    private val observer = Observer(GeographicLocation(32.78, -96.8, 150.0))
    private val epoch = Instant.parse("2026-10-18T20:00:00Z")
    private val satellite = Satellite(SatelliteGroup.parse(ElementSetGroup.STATIONS, ommArray(issOmmJson), epoch).value.newest!!)
    private var previousLocale: Locale? = null

    @BeforeTest
    fun useEnglish() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun restoreLocale() {
        previousLocale?.let(Locale::setDefault)
    }

    /** A pass of the ISS found by the engine, whenever it is above the horizon within three days. */
    private fun anyPass(): SatellitePass =
        SatellitePassFinder(observer, DateInterval(epoch, 3 * 86_400.0)).passes(satellite).first()

    // Following

    @Test
    fun followingTargetsTheApparentDirectionOfTheLookInOneSkyContext() {
        val source = SatelliteFollowSource(satellite, "ISS", observer)
        val date = epoch.plusSeconds(1_234)
        val fix = assertNotNull(source.fix(date))
        val look = assertNotNull(satellite.look(SatelliteSkyContext(date, observer)))
        assertEquals(look.apparentHorizontal, fix.horizontal)
        assertEquals(look, fix.look)
        assertEquals("ISS", fix.label)
        assertNull(fix.secondsToPeak)
        assertEquals(look.apparentDirection.x, fix.direction.x, 1e-12)
        assertEquals(look.apparentDirection.z, fix.direction.z, 1e-12)
    }

    @Test
    fun theTargetMovesAtTheSatellitesAngularSpeed() {
        val source = SatelliteFollowSource(satellite, "ISS", observer)
        // Take a moment when the ISS is above the horizon so that refraction does not matter.
        val pass = anyPass()
        val date = pass.culmination.date
        val before = assertNotNull(source.fix(date))
        val after = assertNotNull(source.fix(date.plusMillis(1_000)))
        val moved = before.direction.angle(after.direction)
        val speed = assertNotNull(before.look).angularSpeed
        assertTrue(speed > 0.05, "ISS speed $speed")
        assertEquals(speed, moved, speed * 0.05)
        assertNotEquals(before.horizontal, after.horizontal)
    }

    @Test
    fun theLookCarriesRangeSpeedAndLightForTheReadout() {
        val pass = anyPass()
        val fix = assertNotNull(SatelliteFollowSource(satellite, "ISS", observer).fix(pass.culmination.date))
        val look = assertNotNull(fix.look)
        assertTrue(look.rangeKilometers in 400.0..3_000.0)
        assertEquals(pass.culmination.isSunlit, look.isSunlit)
        assertTrue(look.altitude > 9.0)
    }

    // Waiting at the peak

    @Test
    fun theFixedPeakTargetStandsStillAndCountsDown() {
        val peak = epoch.plusSeconds(600)
        val target = HorizontalCoordinate(310.0, 52.0)
        val source = SatellitePeakSource("ISS", observer, peak, target)
        val early = source.fix(epoch)
        val later = source.fix(epoch.plusSeconds(500))
        assertEquals(target, early.horizontal)
        assertEquals(target, later.horizontal)
        assertEquals(600.0, early.secondsToPeak!!, 1e-9)
        assertEquals(100.0, later.secondsToPeak!!, 1e-9)
        assertEquals(-30.0, source.fix(peak.plusSeconds(30)).secondsToPeak!!, 1e-9)
        assertNull(early.look)
        assertEquals("ISS peak", early.label)
    }

    @Test
    fun thePeakTargetIsTheApparentDirectionAtTheCulmination() {
        val pass = anyPass()
        val item = SatellitePassItem(satellite, ElementSetGroup.STATIONS, pass)
        val source = SatellitePeakSource.create(item, observer)
        val look = assertNotNull(satellite.look(pass.culmination.date, observer))
        assertEquals(pass.culmination.date, source.peak)
        assertEquals(look.apparentHorizontal, source.horizontal)
        // Refraction only raises it, and by a fraction of a degree at this height.
        assertTrue(source.horizontal.altitude >= pass.culmination.altitude)
        assertTrue(source.horizontal.altitude - pass.culmination.altitude < 0.2)
    }

    // The session

    @Test
    fun sourcesHaveDistinctKeys() {
        val a = SatelliteFollowSource(satellite, "ISS", observer)
        val b = SatelliteFollowSource(satellite, "ISS", observer)
        assertNotEquals(a.key, b.key)
    }

    @Test
    fun theSessionEndsWhenTheTargetChanges() {
        val session = SatelliteAimSession()
        val source = SatelliteFollowSource(satellite, "ISS", observer)
        session.start(source, "M31")
        session.targetChanged("M31")
        assertEquals(source, session.source)
        session.targetChanged("M42")
        assertNull(session.source)
        session.start(source, null)
        session.targetChanged(null)
        assertEquals(source, session.source)
        session.targetChanged("M31")
        assertNull(session.source)
    }

    @Test
    fun theSessionCanBeStopped() {
        val session = SatelliteAimSession()
        session.start(SatelliteFollowSource(satellite, "ISS", observer), "M31")
        session.stop()
        assertNull(session.source)
    }

    // Texts

    @Test
    fun theCountdownReadsAsMinutesAndSeconds() {
        assertEquals("Peak in 2:41", SatelliteText.peakCountdown(161.0))
        assertEquals("Peak in 0:05", SatelliteText.peakCountdown(4.6))
        assertEquals("Peak in 1:02:03", SatelliteText.peakCountdown(3_723.0))
        assertEquals("At the peak now", SatelliteText.peakCountdown(1.2))
        assertEquals("At the peak now", SatelliteText.peakCountdown(-1.9))
        assertEquals("Peak passed 0:42 ago", SatelliteText.peakCountdown(-42.0))
        assertEquals("—", SatelliteText.peakCountdown(Double.NaN))
    }

    @Test
    fun theFollowReadoutShowsRangeSpeedAndLight() {
        assertEquals("Range 612 km · 0.62°/s · Sunlit", SatelliteText.followInfo(612.4, 0.6213, true, Locale.US))
        assertEquals("Range 1,234 km · 0.05°/s · In Earth's shadow", SatelliteText.followInfo(1234.0, 0.049, false, Locale.US))
    }

    @Test
    fun theNoteAboutThePassAppearsOnlyOutsideIt() {
        val rise = Instant.parse("2026-10-18T21:34:00Z")
        val set = Instant.parse("2026-10-18T21:40:00Z")
        val time: (Instant) -> String = { "T" }
        assertEquals("Rises T (in 12m 00s)", SatelliteText.passNote(rise.minusSeconds(720), rise, set, time))
        assertNull(SatelliteText.passNote(rise.plusSeconds(30), rise, set, time))
        assertEquals("The pass is over", SatelliteText.passNote(set.plusSeconds(1), rise, set, time))
    }

    @Test
    fun timesWithSecondsFollowTheClockStyle() {
        val date = Instant.parse("2026-10-18T21:34:12Z")
        assertEquals("21:34:12", SatelliteText.timeWithSeconds(date, ZoneOffset.UTC, true, Locale.US))
        assertEquals("9:34:12 PM", SatelliteText.timeWithSeconds(date, ZoneOffset.UTC, false, Locale.US))
        assertEquals("5 min 18 s", SatelliteText.duration(318.0))
        assertEquals("42 s", SatelliteText.duration(41.6))
    }
}
