package net.kuecker.lodestar.features.aim

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The date logic and the wording of the time preview in Aim. */
class AimPreviewTest {
    private val now = Instant.parse("2026-10-17T19:07:30Z")

    @Test
    fun anOffsetIsHeldWithin48HoursEitherWay() {
        assertEquals(48 * 3600.0, AimPreview.normalized(60 * 3600.0))
        assertEquals(-48 * 3600.0, AimPreview.normalized(-1e9))
        assertEquals(48 * 3600.0, AimPreview.normalized(48 * 3600.0))
        assertEquals(5 * 3600.0, AimPreview.normalized(5 * 3600.0))
    }

    @Test
    fun anOffsetNearNowOrNotANumberIsLive() {
        assertEquals(0.0, AimPreview.normalized(0.0))
        assertEquals(0.0, AimPreview.normalized(29.0))
        assertEquals(0.0, AimPreview.normalized(-29.9))
        assertEquals(30.0, AimPreview.normalized(30.0))
        assertEquals(0.0, AimPreview.normalized(Double.NaN))
        assertEquals(0.0, AimPreview.normalized(Double.POSITIVE_INFINITY))
    }

    @Test
    fun theDateIsNowMovedByTheOffset() {
        assertEquals(now, AimPreview.date(now, 0.0))
        assertEquals(Instant.parse("2026-10-17T21:07:30Z"), AimPreview.date(now, 7200.0))
        assertEquals(Instant.parse("2026-10-17T18:52:30Z"), AimPreview.date(now, -900.0))
        // A fraction of a millisecond is rounded, not cut off.
        assertEquals(now.plusMillis(1235), AimPreview.date(now, 1.2349))
    }

    @Test
    fun theOffsetToADateIsHeldWithinTheLimit() {
        assertEquals(6 * 3600.0, AimPreview.offset(Instant.parse("2026-10-18T01:07:30Z"), now))
        assertEquals(0.0, AimPreview.offset(now.plusSeconds(10), now))
        assertEquals(48 * 3600.0, AimPreview.offset(Instant.parse("2026-12-01T00:00:00Z"), now))
        assertEquals(-48 * 3600.0, AimPreview.offset(Instant.parse("2026-01-01T00:00:00Z"), now))
    }

    @Test
    fun stepsReturnToLiveExactly() {
        var offset = 0.0
        offset = AimPreview.normalized(offset + AimPreview.Step.FORWARD_HOUR.seconds)
        assertEquals(3600.0, offset)
        offset = AimPreview.normalized(offset + AimPreview.Step.BACK_QUARTER.seconds)
        assertEquals(2700.0, offset)
        offset = AimPreview.normalized(offset + AimPreview.Step.BACK_HOUR.seconds)
        assertEquals(-900.0, offset)
        offset = AimPreview.normalized(offset + AimPreview.Step.FORWARD_QUARTER.seconds)
        assertEquals(0.0, offset)
    }

    @Test
    fun anHourBackAndAnHourForwardIsLive() {
        val back = AimPreview.normalized(AimPreview.Step.BACK_HOUR.seconds)
        assertEquals(-3600.0, back)
        assertEquals(0.0, AimPreview.normalized(back + AimPreview.Step.FORWARD_HOUR.seconds))
    }

    @Test
    fun stepsStopAtTheLimit() {
        var offset = 47.5 * 3600
        repeat(3) { offset = AimPreview.normalized(offset + AimPreview.Step.FORWARD_HOUR.seconds) }
        assertEquals(AimPreview.LIMIT_SECONDS, offset)
    }

    @Test
    fun theSunIsUpWhenItsUpperLimbIsAboveTheHorizon() {
        assertTrue(AimPreview.isDaylight(30.0))
        assertTrue(AimPreview.isDaylight(0.0))
        assertFalse(AimPreview.isDaylight(-0.27))
        assertFalse(AimPreview.isDaylight(-6.0))
        assertFalse(AimPreview.isDaylight(-40.0))
    }

    @Test
    fun theSameDayIsTheObserversDay() {
        val berlin = ZoneId.of("Europe/Berlin")
        // 23:30 Berlin time and 00:30 the next day are different days there, though 59 minutes apart in UTC.
        val late = Instant.parse("2026-10-17T21:30:00Z")
        assertTrue(AimPreview.isSameDay(late, late.plusSeconds(1800), berlin))
        assertFalse(AimPreview.isSameDay(late.plusSeconds(3600), late, berlin))
        assertTrue(AimPreview.isSameDay(late.plusSeconds(3600), late, ZoneId.of("UTC")))
    }

    @Test
    fun theChipSaysWhenAndWhetherTheSunIsUp() {
        assertEquals("Preview 02:15", AimPreview.chipText("02:15", null, false))
        assertEquals("Preview Sun 02:15", AimPreview.chipText("02:15", "Sun", false))
        assertEquals("Preview · Daylight at 14:20", AimPreview.chipText("14:20", null, true))
        assertEquals("Previewing the sky at 02:15.", AimPreview.spokenChip("02:15", null, false))
        assertEquals("Previewing the sky at Sun 14:20. The Sun is up.", AimPreview.spokenChip("14:20", "Sun", true))
    }

    @Test
    fun theTargetNoteIsInTheFutureTenseAheadAndPastBefore() {
        assertEquals("Will be at altitude 34° at 02:15", AimPreview.placeNote(34.2, "02:15", isFuture = true))
        assertEquals("Was at altitude 34° at 20:15", AimPreview.placeNote(34.2, "20:15", isFuture = false))
        assertEquals("Will be at altitude 0° at 02:15", AimPreview.placeNote(0.0, "02:15", isFuture = true))
        assertEquals("Will be below the horizon at 02:15", AimPreview.placeNote(-3.0, "02:15", isFuture = true))
        assertEquals("Will be behind the recorded skyline at 02:15", AimPreview.skylineNote("02:15", isFuture = true))
        assertEquals("Was behind the recorded skyline at 02:15", AimPreview.skylineNote("02:15", isFuture = false))
    }

    @Test
    fun aTargetBelowTheHorizonSaysWhenItRises() {
        assertEquals(
            "Will be below the horizon at 02:15 · rises 05:41",
            AimPreview.belowHorizonNote("02:15", "05:41", doesNotRise = false, isFuture = true),
        )
        assertEquals(
            "Will be below the horizon at 02:15 · does not rise in the next 24 hours",
            AimPreview.belowHorizonNote("02:15", null, doesNotRise = true, isFuture = true),
        )
        assertEquals(
            "Was below the horizon at 02:15",
            AimPreview.belowHorizonNote("02:15", null, doesNotRise = false, isFuture = false),
        )
    }

    @Test
    fun theSpokenPlaceNamesTheTarget() {
        assertEquals(
            "Jupiter will be at altitude 34 degrees at 02:15.",
            AimPreview.spokenPlace("Jupiter", 34.2, "02:15", isFuture = true),
        )
        assertEquals("Jupiter was below the horizon at 02:15.", AimPreview.spokenPlace("Jupiter", -2.0, "02:15", isFuture = false))
    }
}
