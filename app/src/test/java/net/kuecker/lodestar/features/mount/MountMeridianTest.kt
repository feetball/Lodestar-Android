package net.kuecker.lodestar.features.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.addingTimeInterval
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.MountStatus
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode
import net.kuecker.lodestar.kit.mount.SkyWatcherPierSide
import net.kuecker.lodestar.kit.planning.MeridianClock
import net.kuecker.lodestar.kit.planning.MeridianFlipWindow
import net.kuecker.lodestar.services.MountConnectionState
import net.kuecker.lodestar.services.MountSkyWatcherInfo
import net.kuecker.lodestar.services.MountSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MountMeridianTest {
    private val observer = Observer(checkNotNull(GeographicLocation.validated(40.0, -105.27, 1650.0)))

    /** Sirius, J2000: where a mount reports it points. */
    private val sirius = EquatorialCoordinate(101.28715539, -16.71611582)

    /** The instant Sirius is next on the meridian, after 2026-01-16 00:00 UT. */
    private val transit: Instant = assertNotNull(MeridianClock.transit(sirius, null, Instant.parse("2026-01-16T00:00:00Z"), observer).next)

    private fun snapshot(
        tracking: Boolean? = true,
        phase: MountPhase = MountPhase.IDLE,
        position: EquatorialCoordinate? = sirius,
        skyWatcher: MountSkyWatcherInfo? = null,
    ) = MountSnapshot(
        state = MountConnectionState.CONNECTED,
        status = MountStatus.disconnected.copy(phase = phase, position = position, isTracking = tracking),
        skyWatcher = skyWatcher,
    )

    private fun skyWatcher(mode: SkyWatcherMountMode, pier: SkyWatcherPierSide?, limit: Double?) = MountSkyWatcherInfo(
        mode = mode, startAxis1Degrees = 0.0, startAxis2Degrees = 0.0, startsAwayFromHome = false, pierSide = pier,
        trackingStopReason = null, stars = emptyList(), predictedErrorDegrees = null, maximumResidualDegrees = null,
        homeErrorDegrees = null, meridianLimitDegrees = limit,
    )

    private fun chip(window: Double) = MountMeridian.chip(MeridianFlipWindow(window))

    @Test
    fun theCountdownReadsInMinutesAndHoursAndWarnsUnderFifteenMinutes() {
        assertEquals(MountMeridian.Chip("Meridian in 25 min", MountMeridian.Phase.AHEAD), chip(25 * 60.0 + 30))
        assertEquals(MountMeridian.Chip("Meridian in 1 h 23 min", MountMeridian.Phase.AHEAD), chip(83 * 60.0 + 5))
        assertEquals(MountMeridian.Chip("Meridian in 2 h", MountMeridian.Phase.AHEAD), chip(2 * 3_600.0))
        assertEquals(MountMeridian.Chip("Meridian in 15 min", MountMeridian.Phase.AHEAD), chip(900.0))
        assertEquals(MountMeridian.Chip("Meridian in 14 min", MountMeridian.Phase.SOON), chip(899.0))
        assertEquals(MountMeridian.Chip("Meridian in under 1 min", MountMeridian.Phase.SOON), chip(30.0))
    }

    @Test
    fun theChipSaysFlipOnceTheLimitIsReached() {
        assertEquals(MountMeridian.Chip("Past the meridian: flip", MountMeridian.Phase.PAST), chip(0.0))
        assertEquals(MountMeridian.Chip("Past the meridian: flip", MountMeridian.Phase.PAST), chip(-600.0))
    }

    @Test
    fun aTrackingMountCountsDownToTheMeridianWithoutALimit() {
        val twentyFiveMinutes = MountMeridian.chip(snapshot(), observer, transit.addingTimeInterval(-(25 * 60.0 + 30)))
        assertEquals(MountMeridian.Chip("Meridian in 25 min", MountMeridian.Phase.AHEAD), twentyFiveMinutes)
        val tenMinutes = MountMeridian.chip(snapshot(), observer, transit.addingTimeInterval(-(10 * 60.0 + 30)))
        assertEquals(MountMeridian.Chip("Meridian in 10 min", MountMeridian.Phase.SOON), tenMinutes)
        val past = MountMeridian.chip(snapshot(), observer, transit.addingTimeInterval(120.0))
        assertEquals(MountMeridian.Chip("Past the meridian: flip", MountMeridian.Phase.PAST), past)
    }

    @Test
    fun theDriversMeridianLimitMovesTheFlipLater() {
        // 5° past the meridian is 19.9 minutes of clock time later.
        val west = snapshot(skyWatcher = skyWatcher(SkyWatcherMountMode.EQUATORIAL, SkyWatcherPierSide.WEST, 5.0))
        assertEquals(
            MountMeridian.Chip("Meridian in 29 min", MountMeridian.Phase.AHEAD),
            MountMeridian.chip(west, observer, transit.addingTimeInterval(-10 * 60.0)),
        )
        assertEquals(
            MountMeridian.Chip("Meridian in 4 min", MountMeridian.Phase.SOON),
            MountMeridian.chip(west, observer, transit.addingTimeInterval(15 * 60.0)),
        )
        assertEquals(
            MountMeridian.Chip("Past the meridian: flip", MountMeridian.Phase.PAST),
            MountMeridian.chip(west, observer, transit.addingTimeInterval(25 * 60.0)),
        )
    }

    @Test
    fun aMountThatHasNothingToCountDownShowsNoChip() {
        val now = transit.addingTimeInterval(-30 * 60.0)
        assertNotNull(MountMeridian.chip(snapshot(), observer, now))
        assertNull(MountMeridian.chip(snapshot(tracking = false), observer, now))
        assertNull(MountMeridian.chip(snapshot(tracking = null), observer, now))
        assertNull(MountMeridian.chip(snapshot(phase = MountPhase.SLEWING), observer, now))
        assertNull(MountMeridian.chip(snapshot(position = null), observer, now))
        assertNull(MountMeridian.chip(MountSnapshot(), observer, now))
        val altAzimuth = skyWatcher(SkyWatcherMountMode.ALT_AZIMUTH, null, null)
        assertNull(MountMeridian.chip(snapshot(skyWatcher = altAzimuth), observer, now))
    }

    @Test
    fun aMountOnItsNormalPierSideHasFlippedAlready() {
        // Looking west it can track on past the meridian: no countdown, and no call to flip.
        val east = snapshot(skyWatcher = skyWatcher(SkyWatcherMountMode.EQUATORIAL, SkyWatcherPierSide.EAST, 5.0))
        assertNull(MountMeridian.chip(east, observer, transit.addingTimeInterval(-30 * 60.0)))
        assertNull(MountMeridian.chip(east, observer, transit.addingTimeInterval(30 * 60.0)))
    }
}
