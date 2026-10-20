package net.kuecker.lodestar.features.widget

import net.kuecker.lodestar.kit.ephemeris.MoonPhaseName
import net.kuecker.lodestar.kit.ephemeris.RiseTransitSet.Circumstance
import net.kuecker.lodestar.kit.events.EventKind
import net.kuecker.lodestar.kit.foundation.DateInterval
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetCopyTest {
    private val zone = ZoneId.of("UTC")
    private val midnight = Instant.parse("2026-08-08T00:00:00Z")

    private fun at(hour: Int, minute: Int = 0, day: Int = 8): Instant =
        Instant.parse("2026-08-%02dT%02d:%02d:00Z".format(day, hour, minute))

    private fun window(from: Int, to: Int, fromDay: Int = 8, toDay: Int = 9) =
        DateInterval(at(from, day = fromDay), at(to, day = toDay))

    private val moon = WidgetMoon(
        phase = MoonPhaseName.WANING_CRESCENT, illuminatedFraction = 0.174, isWaxing = false, isSouthernHemisphere = false,
        rise = at(2, 10, day = 9), set = at(17, 9), circumstance = Circumstance.NORMAL, upIntervals = emptyList(),
    )
    private val core = WidgetCore(
        hasDarkness = true, visibleWindows = listOf(window(21, 0)), bestTime = at(22), maxAltitude = 21.6, bestAzimuth = 180.0,
    )
    private val event = WidgetEvent(
        title = "Perseids peak", kind = EventKind.METEOR_SHOWER, date = at(20, 8, day = 12), isVisibleFromHere = true,
        bestWindow = window(21, 4, fromDay = 12, toDay = 13), showerRate = 100,
    )
    private val night = WidgetNight(
        placeName = "Dark Sky Site", zone = zone,
        darkness = WidgetDarkness(listOf(window(21, 4)), hours = 6.0 + 41.0 / 60, moonFreeHours = 4.0 + 24.0 / 60),
        moon = moon, core = core, event = event,
    )

    private fun copy(night: WidgetNight = this.night, now: Instant = at(8)) = WidgetCopy.of(night, now, WidgetTestFormat)

    @Test
    fun theDarknessIsAWindowWithItsLengthAndTheMoonsShare() {
        val dark = copy().dark
        assertEquals("21:00 – 04:00", dark.value)
        assertEquals("6h 41m of darkness", dark.detail)
        assertEquals("4h 24m without the Moon", dark.extra)
        assertNull(dark.badge)
        assertEquals("Dark", dark.label)
    }

    @Test
    fun aTwelveHourTimeNeverBreaksBetweenTheTimeAndItsAmOrPm() {
        val twelveHour = object : WidgetTimeFormat {
            override fun time(instant: Instant, zone: ZoneId): String =
                DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(zone).format(instant)

            override fun day(instant: Instant, zone: ZoneId): String = WidgetTestFormat.day(instant, zone)
        }
        val dark = WidgetCopy.of(night, at(8), twelveHour).dark
        assertEquals("9:00 PM – 4:00 AM", dark.value)
        // Said out loud, the times keep their ordinary spaces.
        assertTrue(dark.spoken.contains("from 9:00 PM to 4:00 AM"))
    }

    @Test
    fun theDarknessIsMarkedWhileItLasts() {
        assertEquals("now", copy(now = at(23)).dark.badge)
        assertNull(copy(now = at(4, 30, day = 9)).dark.badge)
        assertTrue(copy(now = at(23)).dark.spoken.startsWith("Darkness now, from 21:00 to 04:00"))
    }

    @Test
    fun theMoonSharesAreWordedFromTheHoursOfDarkness() {
        fun extra(moonFree: Double) =
            copy(night.copy(darkness = night.darkness.copy(hours = 6.0, moonFreeHours = moonFree))).dark.extra
        assertEquals("The Moon stays down", extra(6.0))
        assertEquals("Moonlit all through", extra(0.0))
        assertEquals("2h 30m without the Moon", extra(2.5))
    }

    @Test
    fun withoutDarknessTheWidgetSaysSo() {
        val bright = night.copy(
            darkness = WidgetDarkness(emptyList(), 0.0, 0.0),
            core = WidgetCore(hasDarkness = false, emptyList(), null, null, null),
        )
        val text = copy(bright)
        assertEquals("No full darkness", text.dark.value)
        assertEquals("No darkness", text.core.value)
        assertNull(text.dark.badge)
    }

    @Test
    fun severalWindowsAreListed() {
        val split = night.copy(darkness = night.darkness.copy(windows = listOf(window(21, 23), window(1, 4, 9, 9))))
        assertEquals("21:00 – 23:00, 01:00 – 04:00", copy(split).dark.value)
        assertTrue(copy(split).dark.spoken.contains("21:00 to 23:00 and 01:00 to 04:00"))
    }

    @Test
    fun theMoonHasItsPhaseItsLitPartAndWhenItIsUp() {
        val text = copy().moon
        assertEquals("Waning Crescent", text.value)
        assertEquals("17%", text.trailing)
        // The Moon sets at 17:09 and rises at 02:10 the next morning.
        assertEquals("Sets 17:09 · rises 02:10", text.detail)
        assertEquals("Moon: Waning Crescent, 17% lit. It sets at 17:09 and rises at 02:10.", text.spoken)
        assertNull(text.badge)
        val risingFirst = moon.copy(rise = at(22, 35), set = at(11, 0, day = 9))
        assertEquals("Rises 22:35 · sets 11:00", copy(night.copy(moon = risingFirst)).moon.detail)
    }

    @Test
    fun theMoonIsMarkedWhileItIsUp() {
        val up = moon.copy(upIntervals = listOf(window(17, 9, 8, 9)))
        assertEquals("up", copy(night.copy(moon = up), now = at(23)).moon.badge)
        assertTrue(copy(night.copy(moon = up), now = at(23)).moon.spoken.contains("up now"))
        assertNull(copy(night.copy(moon = up), now = at(10, day = 9)).moon.badge)
    }

    @Test
    fun theMoonCanBeUpOrDownAllNightOrDoOnlyOneOfBoth() {
        fun detail(moon: WidgetMoon) = copy(night.copy(moon = moon)).moon.detail
        assertEquals("Up all night", detail(moon.copy(circumstance = Circumstance.ALWAYS_UP, rise = null, set = null)))
        assertEquals("Down all night", detail(moon.copy(circumstance = Circumstance.ALWAYS_DOWN, rise = null, set = null)))
        assertEquals("Rises 22:35", detail(moon.copy(rise = at(22, 35), set = null)))
        assertEquals("Sets 04:10", detail(moon.copy(rise = null, set = at(4, 10))))
    }

    @Test
    fun theLitPartIsAWholePercentage() {
        assertEquals("72%", WidgetCopy.percent(0.724))
        assertEquals("100%", WidgetCopy.percent(1.0))
        assertEquals("0%", WidgetCopy.percent(0.004))
        assertEquals("100%", WidgetCopy.percent(1.3))
        assertEquals("—", WidgetCopy.percent(Double.NaN))
    }

    @Test
    fun theCoreIsAWindowWithItsBestMoment() {
        val text = copy().core
        assertEquals("21:00 – 00:00", text.value)
        assertEquals("Best 22:00 · 22° S", text.detail)
        assertEquals("Milky Way", text.shortLabel)
        assertEquals("Milky Way core", text.label)
        assertEquals(
            "Milky Way core visible from 21:00 to 00:00. Best at 22:00, 22° up in the south.",
            text.spoken,
        )
        assertEquals("now", copy(now = at(22)).core.badge)
    }

    @Test
    fun aCoreThatStaysLowIsSaidToBeLow() {
        val low = night.copy(core = core.copy(visibleWindows = emptyList(), bestTime = null, maxAltitude = null, bestAzimuth = null))
        val text = copy(low).core
        assertEquals("Low in the dark", text.value)
        assertEquals("Below 10° while it is dark", text.detail)
    }

    @Test
    fun theEventHasItsTitleItsTimeAndWhatToExpect() {
        val text = copy().event
        assertEquals("Perseids peak", text.value)
        assertEquals("Wed, Aug 12 · 20:08", text.detail)
        assertEquals("Up to 100 an hour · Best view 21:00 – 04:00", text.extra)
        assertEquals("in 4 days", text.badge)
        assertEquals("Next event", text.label)
        assertEquals("Next", text.shortLabel)
        assertEquals(
            "Next event: Perseids peak, Wed, Aug 12 at 20:08. Up to 100 an hour · Best view 21:00 – 04:00.",
            text.spoken,
        )
    }

    @Test
    fun theEventSaysHowSoonItIs() {
        fun badge(now: Instant) = copy(now = now).event.badge
        assertEquals("in 4 days", badge(at(8)))
        assertEquals("tomorrow", badge(at(8, day = 11)))
        assertEquals("today", badge(at(8, day = 12)))
        // Passed (the widget has not been redrawn since).
        assertNull(badge(at(8, day = 13)))
    }

    @Test
    fun anEventThatCannotBeSeenSaysSo() {
        val hidden = event.copy(isVisibleFromHere = false, bestWindow = null, showerRate = null, kind = EventKind.LUNAR_ECLIPSE)
        assertEquals("Not visible from here", copy(night.copy(event = hidden)).event.extra)
        val season = event.copy(isVisibleFromHere = null, bestWindow = null, showerRate = null, kind = EventKind.SEASON)
        assertNull(copy(night.copy(event = season)).event.extra)
    }

    @Test
    fun noEventIsSaidToo() {
        val text = copy(night.copy(event = null)).event
        assertEquals("Nothing in view", text.value)
        assertNull(text.badge)
    }

    @Test
    fun theWholeWidgetIsOneSpokenSummary() {
        val text = copy()
        assertTrue(text.spoken.startsWith("Tonight at Dark Sky Site. Darkness from 21:00 to 04:00"))
        for (item in listOf(text.dark, text.moon, text.core, text.event)) assertTrue(text.spoken.contains(item.spoken))
    }

    @Test
    fun lengthsOfTimeAreSpokenInWords() {
        assertEquals("7 hours 9 minutes", WidgetCopy.spokenDuration(7.15))
        assertEquals("1 hour", WidgetCopy.spokenDuration(1.0))
        assertEquals("1 hour 1 minute", WidgetCopy.spokenDuration(1.0 + 1.0 / 60))
        assertEquals("30 minutes", WidgetCopy.spokenDuration(0.5))
        assertEquals("0 minutes", WidgetCopy.spokenDuration(0.0))
        assertEquals("0 minutes", WidgetCopy.spokenDuration(Double.NaN))
    }

    @Test
    fun theLocationRequestIsOneLine() {
        assertEquals("Open Lodestar to set your location", WidgetCopy.NEEDS_LOCATION)
    }
}
