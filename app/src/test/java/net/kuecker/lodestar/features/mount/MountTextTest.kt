package net.kuecker.lodestar.features.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.mount.MountCapabilities
import net.kuecker.lodestar.kit.mount.MountError
import net.kuecker.lodestar.kit.mount.MountNudgeRate
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.MountProtocol
import net.kuecker.lodestar.kit.mount.MountSlewOutcome
import net.kuecker.lodestar.kit.mount.MountStatus
import net.kuecker.lodestar.model.CoordinateStyle
import net.kuecker.lodestar.services.MountConnectionState
import net.kuecker.lodestar.services.MountSlewSummary
import net.kuecker.lodestar.services.MountSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
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
