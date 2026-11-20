package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.events.EventsTestSupport.assertNear
import net.kuecker.lodestar.kit.events.EventsTestSupport.utc
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.phototools.FrameSize
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.kit.planning.NightPlanner
import net.kuecker.lodestar.kit.planning.summary
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Camera advice for meteor showers.
 *
 * References: the ring geometry is checked against the spherical triangle zenith, radiant, view (law of cosines:
 * `sin alt = sin h · cos R + cos h · sin R · cos θ` for a view at distance R and position angle θ from a radiant at
 * altitude h); the rates against the IMO definition of the zenithal hourly rate, `ZHR = HR · r^(6.5 − lm) / sin h`
 * (Rendtel and Arlt (eds.), *Handbook for Meteor Observers*), and the frame's solid angle against the rectangular
 * pyramid `4 · asin(sin(w/2) · sin(h/2))`, which is 4π/6 sr for a 90° × 90° frame (one face of a cube seen from its
 * centre). Sites and instants come from the 2026 IMO calendar used by [EventsMeteorShowerTests].
 */
class EventsMeteorCameraAdviceTests {
    private val boulder = EventsTestSupport.boulder.observer
    private val perseids = assertNotNull(MeteorShower.withCode("PER"))
    private val fullFrame24 = LensSetup(SensorFormat.fullFrame, 24.0, 2.8)

    /** The altitude of the view at position angle [angle] on the ring of [radius] around a radiant [height] up. */
    private fun ringAltitude(height: Double, radius: Double, angle: Double): Double =
        AstroMath.asinDeg(
            AstroMath.sinDeg(height) * AstroMath.cosDeg(radius) +
                AstroMath.cosDeg(height) * AstroMath.sinDeg(radius) * AstroMath.cosDeg(angle),
        )

    /** The azimuth distance of that view from the radiant, from the spherical law of cosines for the side R. */
    private fun ringAzimuthOffset(height: Double, radius: Double, altitude: Double): Double =
        AstroMath.acosDeg(
            (AstroMath.cosDeg(radius) - AstroMath.sinDeg(height) * AstroMath.sinDeg(altitude)) /
                (AstroMath.cosDeg(height) * AstroMath.cosDeg(altitude)),
        )

    // Direction

    @Test
    fun testTheRingPicksTheDirectionNearestTheBestAltitude() {
        // A radiant due south, 60° up. The ring at 40° reaches 55.5° at position angles 60° and 300° (the ring is
        // symmetric about the meridian): the equal scores resolve to the smaller angle, to the west of the radiant.
        val radiant = HorizontalCoordinate(180.0, 60.0)
        val choice = assertNotNull(MeteorCameraAdvice.choose(radiant, null))
        val altitude = ringAltitude(60.0, 40.0, 60.0)
        assertNear(55.5, altitude, 0.05, "reference altitude")
        assertEquals(60.0, choice.positionAngle, 0.0)
        assertNear(altitude, choice.direction.altitude, 1e-9, "altitude")
        assertNear(180.0 + ringAzimuthOffset(60.0, 40.0, altitude), choice.direction.azimuth, 1e-9, "azimuth")
        assertNear(40.0, choice.separationFromRadiant, 1e-9, "separation from the radiant")
        assertNear(40.0, choice.direction.angularSeparation(radiant), 1e-9, "separation by the coordinates")
        assertNull(choice.moonSeparation)
        assertFalse(choice.isSteeredByMoon)
    }

    @Test
    fun testTheMoonTwentyDegreesAwayMovesTheViewToTheOtherSide() {
        val radiant = HorizontalCoordinate(180.0, 60.0)
        val plain = assertNotNull(MeteorCameraAdvice.choose(radiant, null))
        // A full Moon 20° from the plain pick, straight above it.
        val moonPlace = HorizontalCoordinate(plain.direction.azimuth, plain.direction.altitude + 20)
        assertNear(20.0, moonPlace.angularSeparation(plain.direction), 1e-9, "Moon distance")
        val moon = MeteorCameraMoon(moonPlace, 1.0)
        val choice = assertNotNull(MeteorCameraAdvice.choose(radiant, moon))
        // The mirror image on the east side: same height, far from the Moon.
        assertEquals(300.0, choice.positionAngle, 0.0)
        assertNear(plain.direction.altitude, choice.direction.altitude, 1e-9, "same altitude")
        assertNear(360.0 - plain.direction.azimuth, choice.direction.azimuth, 1e-9, "mirrored azimuth")
        assertTrue(choice.direction.altitude > MeteorCameraAdvice.minimumAltitude)
        assertNear(choice.direction.angularSeparation(moonPlace), assertNotNull(choice.moonSeparation), 1e-9, "Moon separation")
        assertTrue(assertNotNull(choice.moonSeparation) > MeteorCameraAdvice.moonInfluenceRadius)
        assertNear(40.0, choice.separationFromRadiant, 1e-9, "separation from the radiant")
        assertTrue(choice.isSteeredByMoon)
    }

    @Test
    fun testTheMoonOnlyCountsWhenItIsUpAndNear() {
        val radiant = HorizontalCoordinate(180.0, 60.0)
        val plain = assertNotNull(MeteorCameraAdvice.choose(radiant, null))
        // Below the horizon, however bright and close, it is ignored.
        val setMoon = MeteorCameraMoon(HorizontalCoordinate(plain.direction.azimuth, -5.0), 1.0)
        assertEquals(plain, assertNotNull(MeteorCameraAdvice.choose(radiant, setMoon)))
        // A new Moon (nothing lit) costs nothing even when it stands in the view.
        val dark = assertNotNull(MeteorCameraAdvice.choose(radiant, MeteorCameraMoon(plain.direction, 0.0)))
        assertEquals(plain.direction, dark.direction)
        assertFalse(dark.isSteeredByMoon)
        // A bright Moon far from every direction on the ring that can be picked leaves the choice alone.
        val farMoon = MeteorCameraMoon(HorizontalCoordinate(plain.direction.azimuth + 180.0, 10.0), 1.0)
        val far = assertNotNull(MeteorCameraAdvice.choose(radiant, farMoon))
        assertEquals(plain.direction, far.direction)
        assertFalse(far.isSteeredByMoon)
        assertTrue(assertNotNull(far.moonSeparation) > MeteorCameraAdvice.moonInfluenceRadius)
        // A Moon that is not a number is ignored like a Moon that is down.
        assertEquals(plain.direction, assertNotNull(MeteorCameraAdvice.choose(radiant, MeteorCameraMoon(plain.direction, Double.NaN))).direction)
    }

    @Test
    fun testTheAltitudePreferencePeaksAt55AndEndsAt25And85() {
        assertEquals(1.0, MeteorCameraAdvice.altitudePreference(55.0), 0.0)
        assertEquals(0.0, MeteorCameraAdvice.altitudePreference(25.0), 0.0)
        assertEquals(0.0, MeteorCameraAdvice.altitudePreference(85.0), 0.0)
        assertEquals(0.5, MeteorCameraAdvice.altitudePreference(40.0), 1e-12)
        assertEquals(0.5, MeteorCameraAdvice.altitudePreference(70.0), 1e-12)
        assertEquals(0.0, MeteorCameraAdvice.altitudePreference(10.0), 0.0)
        assertEquals(0.0, MeteorCameraAdvice.altitudePreference(90.0), 0.0)
    }

    @Test
    fun testRadiantAtTheZenithGivesTheNorthSide() {
        // Every direction on the ring is 50° up: the first one, north, is taken.
        val choice = assertNotNull(MeteorCameraAdvice.choose(HorizontalCoordinate(0.0, 90.0), null))
        assertEquals(0.0, choice.positionAngle, 0.0)
        assertNear(50.0, choice.direction.altitude, 1e-9, "altitude")
        assertNear(0.0, choice.direction.azimuth, 1e-9, "azimuth")
        assertNear(40.0, choice.separationFromRadiant, 1e-9, "separation")
    }

    @Test
    fun testALowRadiantStillGivesAHighView() {
        // At −10° the ring reaches 30°: the one direction above 25° is offered (the vertical circle, towards the zenith).
        val choice = assertNotNull(MeteorCameraAdvice.choose(HorizontalCoordinate(90.0, -10.0), null))
        assertEquals(0.0, choice.positionAngle, 0.0)
        assertNear(30.0, choice.direction.altitude, 1e-9, "altitude")
        assertNear(90.0, choice.direction.azimuth, 1e-9, "azimuth")
        // Lower than −10° the chooser refuses; at −45° the ring does not even reach 25°.
        assertNull(MeteorCameraAdvice.choose(HorizontalCoordinate(90.0, -10.01), null))
        assertNull(MeteorCameraAdvice.choose(HorizontalCoordinate(90.0, -45.0), null))
        assertNull(MeteorCameraAdvice.choose(HorizontalCoordinate(Double.NaN, Double.NaN), null))
    }

    @Test
    fun testTheAdviceForThePerseidsAtBoulder() {
        // 04:00 MDT on August 13, 2026 (New Moon on August 12): the radiant is 58° up in the north-east.
        val date = utc("2026-08-13T10:00:00Z")
        val advice = assertNotNull(MeteorCameraAdvice.bestAim(perseids, boulder, date))
        val frame = SkyFrame(date, boulder)
        val radiant = frame.horizontal(perseids.radiant(date), null, false)
        assertTrue(radiant.altitude > 55 && radiant.altitude < 60, "${radiant.altitude}")
        assertNear(40.0, advice.separationFromRadiant, 1e-9, "separation")
        assertNear(40.0, HorizontalCoordinate(advice.azimuth, advice.altitude).angularSeparation(radiant), 1e-9, "separation by the coordinates")
        assertTrue(advice.altitude > 50 && advice.altitude < 60, "${advice.altitude}")
        assertTrue(advice.azimuth >= 0 && advice.azimuth < 360)
        // The aim point is a J2000 place: 40° from the J2000 radiant, and back in the sky where the advice says.
        assertNear(40.0, advice.aimPoint.angularSeparation(perseids.radiant(date)), 0.01, "J2000 separation")
        val back = frame.horizontal(advice.aimPoint, null, false)
        assertNear(advice.altitude, back.altitude, 0.01, "altitude of the aim point")
        assertNear(advice.azimuth, back.azimuth, 0.01, "azimuth of the aim point")
        // The Moon is a 1 % crescent below the horizon.
        assertEquals("The Moon is below the horizon.", advice.moonNote)
        assertEquals(
            "Meteors trail longest about 40° from the radiant. This view is high enough to clear the horizon haze.",
            advice.reason,
        )
        // Without the Moon the view is the same and there is no note.
        val without = assertNotNull(MeteorCameraAdvice.bestAim(perseids, boulder, date, moonConsidered = false))
        assertNull(without.moonNote)
        assertEquals(advice.copy(moonNote = null), without)
    }

    @Test
    fun testABrightMoonHighInTheSkyIsNamedInTheNote() {
        // The Quadrantids at the Full Moon of January 3, 2026, 03:00 MST: the Moon is 52° up in the west, 71° from the view.
        val quadrantids = assertNotNull(MeteorShower.withCode("QUA"))
        val date = utc("2026-01-03T10:00:00Z")
        val advice = assertNotNull(MeteorCameraAdvice.bestAim(quadrantids, boulder, date))
        val moonNote = assertNotNull(advice.moonNote)
        val frame = SkyFrame(date, boulder)
        val moon = frame.horizontal(SolarSystemBody.MOON, false)
        val separation = moon.angularSeparation(HorizontalCoordinate(advice.azimuth, advice.altitude))
        assertEquals("The Moon is ${Math.round(moon.altitude)}° up, ${Math.round(separation)}° from this view, 100% lit.", moonNote)
        assertTrue(separation > 60, "$separation")
    }

    @Test
    fun testARealMoonSteersTheViewAwayFromIt() {
        // The Aurigids, September 1, 2026, 03:30 MDT: the Moon is 81 % lit and 64° up in the south; the plain pick,
        // east-south-east of the radiant, is 22° from it, so the view goes to the north side instead.
        val aurigids = assertNotNull(MeteorShower.withCode("AUR"))
        val date = utc("2026-09-01T09:30:00Z")
        val with = assertNotNull(MeteorCameraAdvice.bestAim(aurigids, boulder, date))
        val without = assertNotNull(MeteorCameraAdvice.bestAim(aurigids, boulder, date, moonConsidered = false))
        val moon = SkyFrame(date, boulder).horizontal(SolarSystemBody.MOON, false)
        assertTrue(moon.altitude > 60)
        val withSeparation = moon.angularSeparation(HorizontalCoordinate(with.azimuth, with.altitude))
        val withoutSeparation = moon.angularSeparation(HorizontalCoordinate(without.azimuth, without.altitude))
        assertTrue(withoutSeparation < 40, "$withoutSeparation")
        assertTrue(withSeparation > 50, "$withSeparation")
        assertNear(40.0, with.separationFromRadiant, 1e-9, "separation from the radiant")
        assertTrue(with.reason.endsWith("This view is high in the sky and turned away from the Moon."), with.reason)
        assertTrue(assertNotNull(with.moonNote).contains("% lit."))
        assertFalse(with.moonNote.orEmpty().contains("close enough"))
    }

    @Test
    fun testTheMoonNote() {
        val choiceAway = MeteorCameraChoice(HorizontalCoordinate(100.0, 55.0), 60.0, 40.0, 72.4, false)
        val choiceNear = MeteorCameraChoice(HorizontalCoordinate(100.0, 55.0), 60.0, 40.0, 19.5, false)
        val up = MeteorCameraMoon(HorizontalCoordinate(250.0, 40.4), 0.8)
        assertEquals("The Moon is 40° up, 72° from this view, 80% lit.", MeteorCameraAdvice.moonNote(up, choiceAway))
        assertEquals(
            "The Moon is 40° up, 20° from this view, 80% lit. It is close enough to wash out the fainter meteors.",
            MeteorCameraAdvice.moonNote(up, choiceNear),
        )
        // A thin Moon close to the view is not worth a warning.
        assertEquals(
            "The Moon is 40° up, 20° from this view, 10% lit.",
            MeteorCameraAdvice.moonNote(MeteorCameraMoon(HorizontalCoordinate(250.0, 40.4), 0.1), choiceNear),
        )
        assertEquals(
            "The Moon is below the horizon.",
            MeteorCameraAdvice.moonNote(MeteorCameraMoon(HorizontalCoordinate(250.0, -3.0), 1.0), choiceAway),
        )
    }

    @Test
    fun testNothingToAdviseWhenTheRadiantStaysFarBelowTheHorizon() {
        // At 66.5°S the Perseid radiant (+58°) never gets above −34°.
        val antarctica = EventsTestSupport.antarctica.observer
        for (hour in 0 until 24 step 3) {
            assertNull(MeteorCameraAdvice.bestAim(perseids, antarctica, utc("2026-08-13T00:00:00Z").plusSeconds(hour * 3600L)))
        }
    }

    @Test
    fun testAShowerWithBadValuesGivesNoAdviceInsteadOfTrapping() {
        val broken = MeteorShower(
            "BAD", 0, "Broken", 13, 40, 0, 0, Double.NaN, 0, EquatorialCoordinate(400.0, 120.0),
            Double.NaN, Double.POSITIVE_INFINITY, Double.NaN, Double.NaN,
        )
        val date = utc("2026-08-13T10:00:00Z")
        broken.radiant(date)
        MeteorCameraAdvice.bestAim(broken, boulder, date)
        MeteorCameraAdvice.bestAim(broken, boulder, date, moonConsidered = false)
        assertEquals(0.0, MeteorCameraAdvice.observedHourlyRate(broken, 60.0), 0.0)
        assertEquals(0.0, MeteorCameraAdvice.expectedInFrame(50.0, broken, fullFrame24), 0.0)
        assertNull(MeteorCameraAdvice.meanMinutesPerMeteor(50.0, broken, fullFrame24))
    }

    // Count

    @Test
    fun testObservedRateIsTheZenithalRateTimesSineOfTheAltitude() {
        assertNear(100.0, MeteorCameraAdvice.observedHourlyRate(perseids, 90.0), 1e-9, "radiant at the zenith")
        assertNear(50.0, MeteorCameraAdvice.observedHourlyRate(perseids, 30.0), 1e-9, "radiant at 30°")
        assertNear(100 * sin(60 * PI / 180), MeteorCameraAdvice.observedHourlyRate(perseids, 60.0), 1e-9, "radiant at 60°")
        assertEquals(100.0, MeteorCameraAdvice.observedHourlyRate(perseids, 120.0), 1e-9)
        // The IMO definition read backwards: a sky with a limiting magnitude one worse than perfect shows 1/r of the meteors.
        assertNear(86.60254037844386 / 2.2, MeteorCameraAdvice.observedHourlyRate(perseids, 60.0, 5.5), 1e-9, "lm 5.5")
        assertNear(86.60254037844386 * 2.2, MeteorCameraAdvice.observedHourlyRate(perseids, 60.0, 7.5), 1e-9, "lm 7.5")
    }

    @Test
    fun testObservedRateIsZeroWithTheRadiantBelowTheHorizon() {
        assertEquals(0.0, MeteorCameraAdvice.observedHourlyRate(perseids, 0.0), 0.0)
        assertEquals(0.0, MeteorCameraAdvice.observedHourlyRate(perseids, -0.001), 0.0)
        assertEquals(0.0, MeteorCameraAdvice.observedHourlyRate(perseids, -45.0), 0.0)
        assertEquals(0.0, MeteorCameraAdvice.observedHourlyRate(perseids, Double.NaN), 0.0)
        assertEquals(0.0, MeteorCameraAdvice.observedHourlyRate(perseids, Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.0, MeteorCameraAdvice.observedHourlyRate(perseids, 60.0, Double.NaN), 0.0)
    }

    @Test
    fun testTheFrameSolidAngle() {
        // 24 mm on full frame: 73.74° × 53.13° (tan(w/2) = 18/24, tan(h/2) = 12/24), a pyramid of 1.0866 sr, 17.3 % of the sky above.
        assertNear(1.086628494710296, MeteorCameraAdvice.frameSolidAngle(fullFrame24), 1e-9, "24 mm full frame")
        // One face of a cube seen from its centre.
        assertNear(4 * PI / 6, MeteorCameraAdvice.frameSolidAngle(FrameSize(90.0, 90.0, 120.0)), 1e-12, "90° × 90°")
        // A frame almost 180° wide each way is nearly the whole half sky.
        assertNear(2 * PI, MeteorCameraAdvice.frameSolidAngle(FrameSize(179.999, 179.999, 180.0)), 1e-3, "nearly the half sky")
        // Not a frame: nothing.
        assertEquals(0.0, MeteorCameraAdvice.frameSolidAngle(FrameSize(180.0, 90.0, 180.0)), 0.0)
        assertEquals(0.0, MeteorCameraAdvice.frameSolidAngle(FrameSize(0.0, 90.0, 90.0)), 0.0)
        assertEquals(0.0, MeteorCameraAdvice.frameSolidAngle(FrameSize(Double.NaN, 90.0, 90.0)), 0.0)
        // Wider lenses cover more, a smaller sensor less.
        val wide = MeteorCameraAdvice.frameSolidAngle(LensSetup(SensorFormat.fullFrame, 14.0, 2.8))
        val narrow = MeteorCameraAdvice.frameSolidAngle(LensSetup(SensorFormat.fullFrame, 50.0, 1.8))
        val crop = MeteorCameraAdvice.frameSolidAngle(LensSetup(SensorFormat.apsC, 24.0, 2.8))
        assertTrue(wide > MeteorCameraAdvice.frameSolidAngle(fullFrame24) && MeteorCameraAdvice.frameSolidAngle(fullFrame24) > narrow)
        assertTrue(crop < MeteorCameraAdvice.frameSolidAngle(fullFrame24))
    }

    @Test
    fun testExpectedPerseidsInAFullFrame24mmFrame() {
        // ZHR 100, radiant 60° up, r = 2.2, 24 mm full frame. The eye sees 100 · sin 60° = 86.60 an hour; the frame is
        // 1.0866 / 2π = 17.29 % of the sky; a camera that reaches magnitude 2 loses r^4.5 = 34.7 of every 34.7
        // + 1 meteors: 86.60 · 0.17294 · 2.2^−4.5 = 0.4311 an hour, one meteor every 2.3 hours (139.2 minutes).
        val observed = MeteorCameraAdvice.observedHourlyRate(perseids, 60.0)
        val expected = MeteorCameraAdvice.expectedInFrame(observed, perseids, fullFrame24)
        val byHand = 100 * sin(60 * PI / 180) * (4 * asin(0.6 * (12 / sqrt(720.0)))) / (2 * PI) * 2.2.pow(-4.5)
        assertNear(byHand, expected, 1e-12, "closed form")
        assertNear(0.43105212360156964, expected, 1e-9, "Perseids, 24 mm full frame")
        assertNear(139.19430322876505, assertNotNull(MeteorCameraAdvice.meanMinutesPerMeteor(observed, perseids, fullFrame24)), 1e-6, "minutes per meteor")
        // The camera limit and the eye limit are the documented ones.
        assertEquals(2.0, MeteorCameraAdvice.cameraLimitingMagnitude, 0.0)
        assertEquals(6.5, MeteorCameraAdvice.eyeLimitingMagnitude, 0.0)
    }

    @Test
    fun testNoMeteorsExpectedWithTheRadiantBelowTheHorizon() {
        val observed = MeteorCameraAdvice.observedHourlyRate(perseids, -5.0)
        assertEquals(0.0, MeteorCameraAdvice.expectedInFrame(observed, perseids, fullFrame24), 0.0)
        assertNull(MeteorCameraAdvice.meanMinutesPerMeteor(observed, perseids, fullFrame24))
    }

    @Test
    fun testTheExpectedCountScalesWithTheFrameAndTheShower() {
        val observed = 80.0
        val wide = MeteorCameraAdvice.expectedInFrame(observed, perseids, LensSetup(SensorFormat.fullFrame, 14.0, 2.8))
        val normal = MeteorCameraAdvice.expectedInFrame(observed, perseids, fullFrame24)
        val tele = MeteorCameraAdvice.expectedInFrame(observed, perseids, LensSetup(SensorFormat.fullFrame, 85.0, 1.8))
        assertTrue(wide > normal && normal > tele && tele > 0)
        // Twice the observed rate, twice the count.
        assertNear(2 * normal, MeteorCameraAdvice.expectedInFrame(2 * observed, perseids, fullFrame24), 1e-12, "linear in the rate")
        // A shower with a steeper mass distribution (higher r) loses more to the camera's limit.
        val steep = perseids.copy(populationIndex = 3.0)
        assertTrue(MeteorCameraAdvice.expectedInFrame(observed, steep, fullFrame24) < normal)
        assertNear(
            normal * (3.0 / 2.2).pow(-4.5),
            MeteorCameraAdvice.expectedInFrame(observed, steep, fullFrame24),
            1e-12,
            "population index",
        )
        // Rates that are not usable count nothing.
        for (bad in listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(0.0, MeteorCameraAdvice.expectedInFrame(bad, perseids, fullFrame24), 0.0, "$bad")
        }
        assertEquals(0.0, MeteorCameraAdvice.expectedInFrame(observed, perseids.copy(populationIndex = 0.0), fullFrame24), 0.0)
        assertEquals(0.0, MeteorCameraAdvice.expectedInFrame(observed, perseids.copy(populationIndex = Double.NaN), fullFrame24), 0.0)
    }

    // Best time

    /** The altitude of the radiant at [time] at [observer]. */
    private fun radiantAltitude(shower: MeteorShower, observer: Observer, time: java.time.Instant): Double =
        SkyFrame(time, observer).horizontal(shower.radiant(time), null, false).altitude

    @Test
    fun testBestTimeIsTheEndOfDarknessWhenTheRadiantKeepsRising() {
        // Perseids, Boulder, night of August 12 to 13, 2026: the radiant culminates at about 05:00 MDT, after astronomical
        // dawn, so it is still climbing when the dark ends and the end of the dark interval is the best time.
        val zone = ZoneId.of("America/Denver")
        val summary = NightPlanner.summary(utc("2026-08-12T18:00:00Z"), boulder, zone)
        val dark = summary.astronomicalDarknessIntervals.single()
        val best = assertNotNull(MeteorCameraAdvice.bestTimeTonight(perseids, summary))
        assertEquals(dark.end, best)
        val altitude = radiantAltitude(perseids, boulder, best)
        assertTrue(altitude > 60, "$altitude")
        // The radiant is still climbing, so no minute of the dark is higher, and the last minute is within a minute's climb.
        var highest = -90.0
        var minute = dark.start
        while (minute <= dark.end) {
            highest = maxOf(highest, radiantAltitude(perseids, boulder, minute))
            minute = minute.plusSeconds(60)
        }
        assertTrue(altitude >= highest && altitude - highest < 0.2, "$altitude against $highest")
    }

    @Test
    fun testBestTimeIsTheCulminationWhenItFallsInTheDark() {
        // Geminids, Boulder, night of December 13 to 14, 2026: the radiant is highest at about 02:00 MST, mid-night.
        val zone = ZoneId.of("America/Denver")
        val summary = NightPlanner.summary(utc("2026-12-13T19:00:00Z"), boulder, zone)
        val dark = summary.astronomicalDarknessIntervals.single()
        val geminids = assertNotNull(MeteorShower.withCode("GEM"))
        val best = assertNotNull(MeteorCameraAdvice.bestTimeTonight(geminids, summary))
        assertTrue(best > dark.start && best < dark.end)
        // On the grid of ten minutes from the start of the dark.
        assertEquals(0.0, best.timeIntervalSince(dark.start) % 600.0, 1e-6)
        val altitude = radiantAltitude(geminids, boulder, best)
        var highest = -90.0
        var highestTime = dark.start
        var minute = dark.start
        while (minute <= dark.end) {
            val value = radiantAltitude(geminids, boulder, minute)
            if (value > highest) {
                highest = value
                highestTime = minute
            }
            minute = minute.plusSeconds(60)
        }
        assertTrue(abs(best.timeIntervalSince(highestTime)) <= 300.0 + 1e-6, "${best.timeIntervalSince(highestTime)} s from the true culmination")
        assertTrue(highest - altitude < 0.05, "${highest - altitude}")
        assertTrue(altitude > 80, "$altitude")
    }

    @Test
    fun testNoBestTimeWithoutDarkness() {
        // 65°N in mid-August: the Sun stays above −18° all night.
        val north = Observer(GeographicLocation(65.0, 25.0, 0.0))
        val summary = NightPlanner.summary(utc("2026-08-12T09:00:00Z"), north, ZoneId.of("Europe/Helsinki"))
        assertTrue(summary.astronomicalDarknessIntervals.isEmpty())
        assertNull(MeteorCameraAdvice.bestTimeTonight(perseids, summary))
    }

    @Test
    fun testTheBestTimeFeedsTheAdvice() {
        // The brief's chain: the best time of the night, then the advice for it, then the expected count at that time.
        val zone = ZoneId.of("America/Denver")
        val summary = NightPlanner.summary(utc("2026-08-12T18:00:00Z"), boulder, zone)
        val best = assertNotNull(MeteorCameraAdvice.bestTimeTonight(perseids, summary))
        val advice = assertNotNull(MeteorCameraAdvice.bestAim(perseids, boulder, best))
        assertTrue(advice.altitude > MeteorCameraAdvice.minimumAltitude)
        assertNear(40.0, advice.separationFromRadiant, 1e-9, "separation")
        val observed = MeteorCameraAdvice.observedHourlyRate(perseids, radiantAltitude(perseids, boulder, best))
        val perHour = MeteorCameraAdvice.expectedInFrame(observed, perseids, fullFrame24)
        assertTrue(perHour > 0.4 && perHour < 0.5, "$perHour")
    }
}
