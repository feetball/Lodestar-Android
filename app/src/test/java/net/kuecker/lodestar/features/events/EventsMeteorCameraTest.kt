package net.kuecker.lodestar.features.events

import net.kuecker.lodestar.kit.events.EventKind
import net.kuecker.lodestar.kit.events.EventDetails
import net.kuecker.lodestar.kit.events.MeteorAimAdvice
import net.kuecker.lodestar.kit.events.MeteorCameraAdvice
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventsMeteorCameraTest {
    private val zone = ZoneId.of("America/Chicago")
    private val context = ObserverContext(
        observer = Observer(checkNotNull(GeographicLocation.validated(32.78, -96.80, 150.0))),
        timeZone = zone,
        name = "Dallas",
        source = LocationSource.Device,
    )
    private val now = Instant.parse("2026-10-18T15:00:00Z")
    private val lens = LensSetup(SensorFormat.fullFrame, 24.0, 2.8, 4.3)
    private val lensText = "24 mm f/2.8 · Full Frame"
    private var locale: Locale = Locale.getDefault()

    @BeforeTest
    fun useEnglishNumbers() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(locale)
    }

    private val result: EventsResult by lazy {
        EventsCalculator.compute(context, EventsCalculator.key(context, now))
    }

    @Test
    fun theNightStartsAtNoonOfTheDayBeforeUntilNoon() {
        val denver = ZoneId.of("America/Denver")
        // 02:00 MDT on August 13 belongs to the night that began at noon on August 12.
        assertEquals(Instant.parse("2026-08-12T18:00:00Z"), EventsMeteorCamera.nightStart(Instant.parse("2026-08-13T08:00:00Z"), denver))
        // From noon on it is the night of that day, and noon itself starts it.
        assertEquals(Instant.parse("2026-08-13T18:00:00Z"), EventsMeteorCamera.nightStart(Instant.parse("2026-08-13T18:00:00Z"), denver))
        assertEquals(Instant.parse("2026-08-13T18:00:00Z"), EventsMeteorCamera.nightStart(Instant.parse("2026-08-14T05:00:00Z"), denver))
        // The day the clocks go back (25 hours, November 1, 2026): 03:00 MST is still the night of October 31.
        assertEquals(Instant.parse("2026-10-31T18:00:00Z"), EventsMeteorCamera.nightStart(Instant.parse("2026-11-01T10:00:00Z"), denver))
        assertEquals(Instant.parse("2026-11-01T19:00:00Z"), EventsMeteorCamera.nightStart(Instant.parse("2026-11-01T20:00:00Z"), denver))
    }

    @Test
    fun aVisibleShowerGetsAdviceForTheDarkOfItsNight() {
        val event = result.events.first { it.kind == EventKind.METEOR_SHOWER && it.visibility?.isObservable == true }
        val shower = (event.details as EventDetails.MeteorShower).peak.shower
        val state = EventsMeteorCamera.state(event, shower, context)
        val plan = assertIs<MeteorCameraState.Ready>(state).plan
        val visibility = assertNotNull(event.visibility)
        val window = assertNotNull(visibility.bestWindow)
        // The best time is inside the viewing window and where the engine found the radiant highest.
        assertTrue(plan.time >= window.start.minusSeconds(60) && plan.time <= window.end.plusSeconds(60), "${plan.time} outside $window")
        assertTrue(kotlin.math.abs(plan.time.epochSecond - assertNotNull(visibility.bestTime).epochSecond) <= 900)
        assertTrue(plan.radiantAltitude > 10, "${plan.radiantAltitude}")
        assertEquals(MeteorCameraAdvice.ringRadius, plan.advice.separationFromRadiant, 1e-9)
        assertTrue(plan.advice.altitude > MeteorCameraAdvice.minimumAltitude)
        // A 24 mm lens catches well under two an hour of any shower of the list.
        val perHour = EventsMeteorCamera.expectedPerHour(plan, shower, lens)
        assertTrue(perHour > 0 && perHour < 2, "$perHour")
        // The target is a custom one at the advised place, named for the shower.
        val target = EventsMeteorCamera.target(shower, plan.advice)
        assertEquals("${shower.name} camera field", target.name)
        assertTrue(target.id.startsWith("custom."))
        assertEquals(plan.advice.aimPoint, target.j2000)
    }

    @Test
    fun theAimTextNamesTheDirectionTheHeightAndTheDistanceFromTheRadiant() {
        val advice = MeteorAimAdvice(210.3, 55.2, 40.0, EquatorialCoordinate(10.0, 20.0), null, "")
        assertEquals("Point the camera at azimuth 210° (SSW), altitude 55°, 40° from the radiant.", EventsMeteorCamera.aimText(advice))
        assertEquals(lensText, EventsMeteorCamera.lensText(lens))
        assertEquals("200 mm f/4 · APS-C", EventsMeteorCamera.lensText(LensSetup(SensorFormat.apsC, 200.0, 4.0)))
    }

    @Test
    fun theCountIsWordedForTheRate() {
        fun count(perHour: Double, altitude: Double = 60.0) = EventsMeteorCamera.countText(perHour, altitude, lensText)
        assertEquals("Expect about one meteor every 2.3 hours in your frame at $lensText.", count(0.43105))
        assertEquals("Expect about one meteor every 1.6 hours in your frame at $lensText.", count(0.625))
        assertEquals("Expect about one meteor every 1.7 hours in your frame at $lensText.", count(0.6))
        assertEquals("Expect about one meteor every 90 minutes in your frame at $lensText.", count(60.0 / 90))
        assertEquals("Expect about one meteor every 80 minutes in your frame at $lensText.", count(0.75))
        assertEquals("Expect about one meteor every 40 minutes in your frame at $lensText.", count(1.5))
        assertEquals("Expect about one meteor every 30 minutes in your frame at $lensText.", count(1.99))
        assertEquals("Expect a few meteors an hour in your frame at $lensText.", count(2.0))
        assertEquals("Expect a few meteors an hour in your frame at $lensText.", count(5.9))
        assertEquals("Expect about 6 meteors an hour in your frame at $lensText.", count(6.0))
        assertEquals("Expect about 12 meteors an hour in your frame at $lensText.", count(12.4))
        assertEquals("Expect fewer than one meteor in 10 hours in your frame at $lensText.", count(0.05))
    }

    @Test
    fun noMeteorsAreExpectedWithTheRadiantBelowTheHorizon() {
        val below = "The radiant is below the horizon then, so expect no meteors in your frame."
        assertEquals(below, EventsMeteorCamera.countText(0.0, 60.0, lensText))
        assertEquals(below, EventsMeteorCamera.countText(0.4, -3.0, lensText))
        assertEquals(below, EventsMeteorCamera.countText(Double.NaN, 60.0, lensText))
    }

    @Test
    fun theTipIsTheOneTheBriefAsksFor() {
        assertEquals(
            "Use your widest lens, the NPF exposure, continuous shooting; keep the radiant in or near the frame edge.",
            EventsMeteorCamera.TIP,
        )
    }
}
