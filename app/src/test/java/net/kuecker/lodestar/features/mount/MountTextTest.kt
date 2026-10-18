package net.kuecker.lodestar.features.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.mount.MountCapabilities
import net.kuecker.lodestar.kit.mount.MountError
import net.kuecker.lodestar.kit.mount.MountNudgeRate
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.MountProtocol
import net.kuecker.lodestar.kit.mount.MountSlewOutcome
import net.kuecker.lodestar.kit.mount.MountStatus
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode
import net.kuecker.lodestar.kit.mount.SkyWatcherPierSide
import net.kuecker.lodestar.model.CoordinateStyle
import net.kuecker.lodestar.services.MountAlignmentStar
import net.kuecker.lodestar.services.MountConnectionState
import net.kuecker.lodestar.services.MountSkyWatcherInfo
import net.kuecker.lodestar.services.MountHints
import net.kuecker.lodestar.services.MountSlewSummary
import net.kuecker.lodestar.services.MountSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MountTextTest {
    private fun connected(phase: MountPhase, outcome: MountSlewOutcome? = null, elapsed: Double? = null, tracking: Boolean? = true) =
        MountSnapshot(
            state = MountConnectionState.CONNECTED,
            status = MountStatus.disconnected.copy(phase = phase, outcome = outcome, slewElapsedSeconds = elapsed, isTracking = tracking),
        )

    @Test
    fun theStateOfTheConnectionIsSaidInWords() {
        assertEquals("Not connected", MountText.phaseLine(MountSnapshot()))
        assertEquals("Connection lost", MountText.phaseLine(MountSnapshot(lostConnection = true)))
        assertEquals("Connecting…", MountText.phaseLine(MountSnapshot(state = MountConnectionState.CONNECTING)))
    }

    @Test
    fun aSlewShowsItsTime() {
        assertEquals("Slewing… 42 s", MountText.phaseLine(connected(MountPhase.SLEWING, elapsed = 42.4)))
        assertEquals("Slewing… 1 min 5 s", MountText.phaseLine(connected(MountPhase.SLEWING, elapsed = 65.0)))
        assertEquals("Moving…", MountText.phaseLine(connected(MountPhase.NUDGING)))
    }

    @Test
    fun theOutcomeOfTheLastSlewIsSaid() {
        assertTrue(MountText.phaseLine(connected(MountPhase.IDLE, MountSlewOutcome.Arrived(0.02))).startsWith("Arrived — "))
        assertEquals("Stopped", MountText.phaseLine(connected(MountPhase.IDLE, MountSlewOutcome.Aborted)))
        assertEquals(
            "The mount stopped 3.1° short of the target.",
            MountText.phaseLine(connected(MountPhase.IDLE, MountSlewOutcome.Failed(MountError.SlewStopped(3.1)))),
        )
        assertEquals("Ready", MountText.phaseLine(connected(MountPhase.IDLE)))
        assertEquals("Ready · tracking is off", MountText.phaseLine(connected(MountPhase.IDLE, tracking = false)))
    }

    @Test
    fun theAnnouncementChangesOnlyWithTheSituation() {
        val first = MountText.announcement(connected(MountPhase.SLEWING, elapsed = 3.0))
        val later = MountText.announcement(connected(MountPhase.SLEWING, elapsed = 30.0))
        assertEquals(first, later)
        assertEquals("Mount arrived at the target", MountText.announcement(connected(MountPhase.IDLE, MountSlewOutcome.Arrived(0.1))))
        assertEquals("Mount connection lost", MountText.announcement(MountSnapshot(lostConnection = true)))
        assertEquals("", MountText.announcement(MountSnapshot()))
    }

    @Test
    fun aSlewIsDescribedInPlainDirections() {
        assertEquals(
            "Moves 38° across the sky: turns 30° clockwise (seen from above) and tilts 15° down.",
            MountText.slew(MountSlewSummary(38.0, 30.0, -15.0)),
        )
        assertEquals("Moves 4.0° across the sky: turns 4.0° counter-clockwise (seen from above).", MountText.slew(MountSlewSummary(4.0, -4.0, 0.2)))
        assertEquals("Moves 24′ across the sky.", MountText.slew(MountSlewSummary(0.4, 0.1, 0.1)))
    }

    @Test
    fun theRatesAreDescribed() {
        assertTrue(MountText.rateDescription(MountNudgeRate.CENTER).startsWith("Center: 8× sidereal"))
        assertTrue(MountText.rateDescription(MountNudgeRate.SLEW).contains("128×"))
    }

    @Test
    fun theAbilitiesFollowTheCapabilities() {
        val capabilities = MountCapabilities(
            MountProtocol.LX200, "OnStep", "4.24", canSlew = true, canSync = true, canAbort = true, canSetTracking = false,
            canNudge = true, canSetSiteAndTime = false, gotoNeedsAlignment = false, isAligned = null,
        )
        assertEquals(listOf("Go to", "Sync", "Stop", "Nudges"), MountText.abilities(capabilities))
    }

    @Test
    fun aPositionFollowsTheCoordinateStyle() {
        val position = EquatorialCoordinate.fromRightAscensionHours(12.5, 45.0)
        assertTrue(MountText.position(position, CoordinateStyle.DECIMAL).contains("187.5"))
        assertTrue(MountText.position(position, CoordinateStyle.SEXAGESIMAL).contains("12"))
    }
}

class MountSkyWatcherTextTest {
    private fun info(
        stars: List<MountAlignmentStar> = emptyList(),
        predicted: Double? = null,
        maximum: Double? = null,
        pier: SkyWatcherPierSide? = null,
        stop: MountError? = null,
        mode: SkyWatcherMountMode = SkyWatcherMountMode.ALT_AZIMUTH,
        away: Boolean = false,
    ) = MountSkyWatcherInfo(mode, if (away) 31.2 else 0.0, if (away) -4.0 else 0.0, away, pier, stop, stars, predicted, maximum, null)

    private fun star(name: String, residual: Double) = MountAlignmentStar(name, null, residual)

    @Test
    fun theAlignmentLineSaysHowManyStarsAndHowWell() {
        assertEquals("Not aligned: centre a star and tap Sync", MountText.alignmentLine(info()))
        assertEquals(
            "Aligned on 3 stars, 0.02° residual, ≈ 0.2° accuracy",
            MountText.alignmentLine(info(listOf(star("A", 0.0), star("B", 0.01), star("C", 0.02)), predicted = 0.2, maximum = 0.02)),
        )
        // One star cannot have a residual worth showing.
        assertEquals("Aligned on 1 star, ≈ 1.0° accuracy", MountText.alignmentLine(info(listOf(star("A", 0.0)), predicted = 1.0, maximum = 0.0)))
        assertEquals("0.05°", MountText.accuracyText(0.05))
        assertEquals("0.3°", MountText.accuracyText(0.3))
        assertEquals("12°", MountText.accuracyText(12.0))
    }

    @Test
    fun thePierSideIsNamedFromTheTelescopesPointOfView() {
        assertEquals("Telescope east of the pier", MountText.pierSideLine(SkyWatcherPierSide.EAST))
        assertEquals("Telescope west of the pier", MountText.pierSideLine(SkyWatcherPierSide.WEST))
    }

    private fun snapshot(info: MountSkyWatcherInfo, tracking: Boolean?) = MountSnapshot(
        state = MountConnectionState.CONNECTED,
        status = MountStatus.disconnected.copy(phase = MountPhase.IDLE, isTracking = tracking),
        skyWatcher = info,
    )

    @Test
    fun aStopAtTheMeridianTellsTheUserToFlip() {
        val equatorial = info(mode = SkyWatcherMountMode.EQUATORIAL, stop = MountError.OutsideMountLimits)
        assertEquals("Tracking stopped at the meridian. Tap Go to to flip the mount", MountText.trackingStopLine(snapshot(equatorial, false)))
        // While tracking runs again (after the flip) the message is gone.
        assertNull(MountText.trackingStopLine(snapshot(equatorial, true)))
        assertNull(MountText.trackingStopLine(snapshot(info(), false)))
        val altAz = info(stop = MountError.OutsideMountLimits)
        assertTrue(MountText.trackingStopLine(snapshot(altAz, false))!!.startsWith("Tracking stopped at a limit of the mount"))
        assertTrue(MountText.trackingStopLine(snapshot(info(stop = MountError.MountOverheadLimit), false))!!.contains("zenith"))
        assertEquals("Tracking", MountText.trackingLine(snapshot(altAz, true)))
        assertEquals("Tracking is off", MountText.trackingLine(snapshot(altAz, false)))
        assertNull(MountText.trackingLine(snapshot(altAz, null)))
    }

    @Test
    fun theStartPositionCheckHasTwoOutcomes() {
        assertEquals("The mount started at its home position.", MountText.startPositionLine(info()))
        val away = MountText.startPositionLine(info(away = true))
        assertTrue(away.contains("not at its home position") && away.contains("This is home"), away)
        assertEquals("+31.2° / −4.0°", MountText.axisAngles(info(away = true)).replace("-4.0", "−4.0"))
    }

    @Test
    fun theBannerBeforeTheFirstSyncDependsOnTheMount() {
        val capabilities = MountCapabilities(
            MountProtocol.SKYWATCHER, "Sky-Watcher AZ-GTi 3.22", "3.22", canSlew = true, canSync = true, canAbort = true,
            canSetTracking = true, canNudge = true, canSetSiteAndTime = true, gotoNeedsAlignment = true, isAligned = false,
        )
        val unaligned = snapshot(info(), null).copy(capabilities = capabilities)
        assertEquals("Not aligned yet. Centre a star with the nudge pad, then tap Sync.", MountText.notAlignedBanner(unaligned))
        val aligned = unaligned.copy(skyWatcher = info(listOf(star("Vega", 0.0))))
        assertNull(MountText.notAlignedBanner(aligned))
        val handController = MountSnapshot(
            state = MountConnectionState.CONNECTED,
            status = MountStatus.disconnected.copy(isAligned = false),
            capabilities = capabilities.copy(kind = MountProtocol.NEXSTAR),
        )
        assertEquals(MountText.HAND_CONTROLLER_NOT_ALIGNED, MountText.notAlignedBanner(handController))
    }
}
