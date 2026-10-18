package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MountSupportTests {
    @Test
    fun testCalendarFieldsAreExact() {
        fun fields(seconds: Double) = assertNotNull(MountCalendar.fields(instantFromTimeIntervalSince1970(seconds)))
        assertEquals(MountDateTime(1970, 1, 1, 0, 0, 0, 0), fields(0.0))
        assertEquals(MountDateTime(2000, 1, 1, 12, 0, 0, 0), fields(946_728_000.0))
        assertEquals(MountDateTime(2000, 2, 29, 23, 59, 59, 123), fields(951_868_799.123))
        assertEquals(MountDateTime(2000, 3, 1, 0, 0, 0, 0), fields(951_868_800.0))
        assertEquals(MountDateTime(2026, 10, 18, 4, 0, 0, 0), fields(1_792_296_000.0))
        assertEquals(MountDateTime(2038, 1, 19, 3, 14, 8, 0), fields(2_147_483_648.0))
        assertEquals(MountDateTime(2100, 3, 1, 0, 0, 0, 0), fields(4_107_542_400.0))
        assertEquals(MountDateTime(2024, 12, 31, 23, 59, 59, 999), fields(1_735_689_599.9999))
        assertEquals(MountDateTime(9999, 12, 31, 23, 59, 59, 0), fields(253_402_300_799.0))
        assertNull(MountCalendar.fields(instantFromTimeIntervalSince1970(-1.0)))
        assertNull(MountCalendar.fields(instantFromTimeIntervalSince1970(253_402_300_800.0)))
    }

    @Test
    fun testCalendarAgainstTheJavaTimeLibraryOverManyDays() {
        var seconds = 0L
        while (seconds < 4_102_444_800L) {
            val expected = java.time.Instant.ofEpochSecond(seconds).atZone(java.time.ZoneOffset.UTC)
            val actual = assertNotNull(MountCalendar.fields(instantFromTimeIntervalSince1970(seconds.toDouble())))
            assertEquals(
                listOf(expected.year, expected.monthValue, expected.dayOfMonth, expected.hour, expected.minute, expected.second),
                listOf(actual.year, actual.month, actual.day, actual.hour, actual.minute, actual.second),
                "$seconds",
            )
            seconds += 86_399L * 7 + 1_234L
        }
    }

    @Test
    fun testSettingsAreClamped() {
        val standard = MountSettings.standard
        assertEquals(0.0, standard.altitudeLimit, 0.0)
        assertEquals(60.0, standard.horizonLookaheadSeconds, 0.0)
        assertEquals(300.0, standard.maximumSlewSeconds, 0.0)
        assertEquals(3, standard.maximumConsecutiveFailures)
        val wild = MountSettings(
            altitudeLimit = 500.0, horizonLookaheadSeconds = -5.0, maximumSlewSeconds = 1.0e9, slewStartGraceSeconds = Double.NaN,
            arrivalToleranceDegrees = 0.0, pollIntervalSeconds = 0.0, maximumConsecutiveFailures = 0, maximumNudgeSeconds = 1.0e9,
            maximumSyncOffsetDegrees = 1000.0,
        )
        assertEquals(60.0, wild.altitudeLimit, 0.0)
        assertEquals(0.0, wild.horizonLookaheadSeconds, 0.0)
        assertEquals(3600.0, wild.maximumSlewSeconds, 0.0)
        assertEquals(5.0, wild.slewStartGraceSeconds, 0.0)
        assertEquals(0.01, wild.arrivalToleranceDegrees, 0.0)
        assertEquals(0.1, wild.pollIntervalSeconds, 0.0)
        assertEquals(1, wild.maximumConsecutiveFailures)
        assertEquals(30.0, wild.maximumNudgeSeconds, 0.0)
        assertEquals(180.0, wild.maximumSyncOffsetDegrees, 0.0)
        assertEquals(-10.0, MountSettings(altitudeLimit = -50.0).altitudeLimit, 0.0)
        assertEquals(10, MountSettings(maximumConsecutiveFailures = 99).maximumConsecutiveFailures)
    }

    @Test
    fun testDriverConfigurationsAreClamped() {
        assertEquals(30.0, LX200Configuration(exchangeTimeoutSeconds = 1.0e6).exchangeTimeoutSeconds, 0.0)
        assertEquals(0.5, LX200Configuration(exchangeTimeoutSeconds = 0.0).exchangeTimeoutSeconds, 0.0)
        assertEquals(3.0, LX200Configuration(exchangeTimeoutSeconds = Double.NaN).exchangeTimeoutSeconds, 0.0)
        assertEquals(10, LX200Configuration(busyRetries = 99).busyRetries)
        assertEquals(0, LX200Configuration(busyRetries = -3).busyRetries)
        assertEquals(MountEpoch.MEAN_OF_DATE, LX200Configuration.standard.epoch)
        assertNull(NexStarConfiguration.standard.epoch)
        assertEquals(5.5, NexStarConfiguration.standard.exchangeTimeoutSeconds, 0.0)
        assertEquals(NexStarTrackingMode.ALT_AZ, NexStarConfiguration(preferredTrackingMode = NexStarTrackingMode.OFF).preferredTrackingMode)
        assertEquals(1.0, NexStarConfiguration(exchangeTimeoutSeconds = 0.0).exchangeTimeoutSeconds, 0.0)
        assertEquals(0, AlpacaConfiguration(deviceNumber = -4).deviceNumber)
        assertEquals(1, AlpacaConfiguration(clientID = 0).clientID)
        assertEquals(60.0, AlpacaConfiguration(exchangeTimeoutSeconds = 1.0e9).exchangeTimeoutSeconds, 0.0)
    }

    @Test
    fun testErrorsExplainThemselves() {
        val errors: List<MountError> = listOf(
            MountError.NotConnected, MountError.Busy, MountError.SlewInProgress, MountError.BelowHorizonLimit(-3.26, 0.0),
            MountError.MountHorizonLimit, MountError.MountOverheadLimit, MountError.OutsideMountLimits, MountError.NotAligned,
            MountError.Parked, MountError.Standby, MountError.NotTracking, MountError.NotSupported(MountFeature.SYNC),
            MountError.HardwareFault(""), MountError.HardwareFault("motor 2"), MountError.Rejected(""), MountError.Rejected("tracking"),
            MountError.Timeout("read position"), MountError.SlewTimeout(300.0), MountError.SlewStopped(12.34),
            MountError.SyncOffsetTooLarge(45.0, 30.0), MountError.ConnectionLost, MountError.Transport("reset"),
            MountError.MalformedReply("position"), MountError.Remote(7, ""), MountError.Remote(7, "x"), MountError.InvalidInput("bad"),
            MountError.UnsupportedCoordinateSystem(4),
        )
        for (error in errors) {
            assertTrue(error.userMessage.length > 10, "$error")
            assertEquals(error.message, error.userMessage)
            assertTrue(error.userMessage.endsWith("."), error.userMessage)
        }
        assertEquals("The target is below the mount's horizon limit.", MountError.MountHorizonLimit.userMessage)
        assertEquals("The mount is not aligned. Do a star alignment on the mount first, then try again.", MountError.NotAligned.userMessage)
        assertEquals("The mount is busy. Try again in a moment.", MountError.Busy.userMessage)
        assertEquals(
            "The target is at -3.3°, below your altitude limit of 0.0°. Lodestar won't slew there.",
            MountError.BelowHorizonLimit(-3.26, 0.0).userMessage,
        )
        assertEquals("This mount doesn't support syncing to a position.", MountError.NotSupported(MountFeature.SYNC).userMessage)
        assertEquals("The mount stopped 12.3° short of the target.", MountError.SlewStopped(12.34).userMessage)
        assertEquals("The slew did not finish within 300 s, so it was stopped.", MountError.SlewTimeout(300.0).userMessage)
        assertTrue(MountError.Timeout("x").isCommunicationFailure)
        assertTrue(MountError.Transport("x").isCommunicationFailure)
        assertTrue(MountError.MalformedReply("x").isCommunicationFailure)
        assertTrue(MountError.ConnectionLost.isCommunicationFailure)
        assertTrue(!MountError.Busy.isCommunicationFailure)
        assertTrue(!MountError.Parked.isCommunicationFailure)
    }

    @Test
    fun testManualClockAndSystemClock() {
        val clock = ManualMountClock(instantFromTimeIntervalSince1970(1000.0))
        clock.sleep(2.5)
        clock.advance(0.5)
        clock.advance(-10.0)
        clock.advance(Double.NaN)
        clock.advance(Double.POSITIVE_INFINITY)
        assertEquals(1003.0, clock.secondsSince1970, 0.0)
        assertEquals(instantFromTimeIntervalSince1970(1003.0), clock.now())
        val system = SystemMountClock()
        val before = system.now()
        system.sleep(0.01)
        system.sleep(-1.0)
        system.sleep(Double.NaN)
        assertTrue(system.now() >= before)
    }
}
