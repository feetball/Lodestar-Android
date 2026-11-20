package net.kuecker.lodestar.features.system

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemIntegrationTest {
    // Night mode and the torch

    @Test
    fun aToggleSwitchesNightModeOnWhenItIsNotOnAndOffWhenItIs() {
        assertTrue(NightModeChange.TOGGLE.resultingIsOn(false))
        assertEquals(false, NightModeChange.TOGGLE.resultingIsOn(true))
    }

    @Test
    fun onAndOffDoNotDependOnTheCurrentState() {
        for (current in listOf(false, true)) {
            assertTrue(NightModeChange.ON.resultingIsOn(current))
            assertEquals(false, NightModeChange.OFF.resultingIsOn(current))
        }
    }

    @Test
    fun aTapAsksBeforeWhiteLightAndOnlyTheConfirmationTurnsItOn() {
        assertEquals(TorchLight.CONFIRMING_WHITE, TorchLight.RED.afterTap)
        assertEquals(TorchLight.RED, TorchLight.RED.afterConfirm)
        assertEquals(TorchLight.WHITE, TorchLight.CONFIRMING_WHITE.afterConfirm)
        assertEquals(TorchLight.RED, TorchLight.CONFIRMING_WHITE.afterCancel)
        assertEquals(TorchLight.RED, TorchLight.CONFIRMING_WHITE.afterTap)
    }

    @Test
    fun aTapOnWhiteGoesBackToRedAtOnce() {
        assertEquals(TorchLight.RED, TorchLight.WHITE.afterTap)
        assertEquals(TorchLight.WHITE, TorchLight.WHITE.afterCancel)
    }

    // Launch intents

    @Test
    fun theActionsOfTheShortcutsAndTilesMapToCommands() {
        assertEquals(SystemCommand.ShowAim, SystemLaunch.command(SystemLaunch.ACTION_AIM, null))
        assertEquals(SystemCommand.ShowTorch, SystemLaunch.command(SystemLaunch.ACTION_TORCH, null))
        assertEquals(SystemCommand.NightMode(NightModeChange.TOGGLE), SystemLaunch.command(SystemLaunch.ACTION_NIGHT_TOGGLE, null))
        assertEquals(SystemCommand.NightMode(NightModeChange.ON), SystemLaunch.command(SystemLaunch.ACTION_NIGHT_ON, null))
        assertEquals(SystemCommand.NightMode(NightModeChange.OFF), SystemLaunch.command(SystemLaunch.ACTION_NIGHT_OFF, null))
        assertEquals(SystemCommand.AimAt("dso.M31"), SystemLaunch.command(SystemLaunch.ACTION_AIM_AT, "dso.M31"))
    }

    @Test
    fun anUnknownActionOrAnAimWithoutAUsableObjectIsIgnored() {
        assertNull(SystemLaunch.command(null, null))
        assertNull(SystemLaunch.command("nonsense", "dso.M31"))
        assertNull(SystemLaunch.command(SystemLaunch.ACTION_AIM_AT, null))
        assertNull(SystemLaunch.command(SystemLaunch.ACTION_AIM_AT, "  "))
        assertNull(SystemLaunch.command(SystemLaunch.ACTION_AIM_AT, "x".repeat(SystemLaunch.MAXIMUM_OBJECT_ID_LENGTH + 1)))
    }

    @Test
    fun theShortcutOfAFavouriteHasAStableIdAndALabel() {
        assertEquals("aim.dso.M31", SystemShortcuts.shortcutID("dso.M31"))
        assertEquals("Aim at Andromeda Galaxy", SystemShortcuts.label("Andromeda Galaxy"))
    }

    // The satellite pass plan

    private val rise: Instant = Instant.parse("2026-11-19T18:00:00Z")

    private fun plan() = SatellitePassPlan(
        name = "ISS", rise = rise, peak = rise.plusSeconds(180), set = rise.plusSeconds(360),
        riseAzimuth = 315.0, peakAzimuth = 45.0, setAzimuth = 135.0, peakAltitude = 63.6, zoneID = "UTC",
    )

    @Test
    fun theStagesFollowTheRiseThePeakAndTheSet() {
        val plan = plan()
        assertEquals(SatellitePassPlan.Stage.WAITING, plan.stage(rise.minusSeconds(1)))
        assertEquals(SatellitePassPlan.Stage.RISEN, plan.stage(rise))
        assertEquals(SatellitePassPlan.Stage.RISEN, plan.stage(rise.plusSeconds(179)))
        assertEquals(SatellitePassPlan.Stage.PEAKED, plan.stage(rise.plusSeconds(180)))
        assertEquals(SatellitePassPlan.Stage.PEAKED, plan.stage(rise.plusSeconds(359)))
        assertEquals(SatellitePassPlan.Stage.OVER, plan.stage(rise.plusSeconds(360)))
    }

    @Test
    fun theNextChangeIsTheNextMilestoneAndNoneAfterThePass() {
        val plan = plan()
        assertEquals(rise, plan.nextChange(rise.minusSeconds(60)))
        assertEquals(rise.plusSeconds(180), plan.nextChange(rise.plusSeconds(10)))
        assertEquals(rise.plusSeconds(360), plan.nextChange(rise.plusSeconds(200)))
        assertNull(plan.nextChange(rise.plusSeconds(400)))
    }

    @Test
    fun theCountdownRunsToTheRiseThenToThePeakAndThenStops() {
        val plan = plan()
        assertEquals(rise, plan.countdownTarget(SatellitePassPlan.Stage.WAITING))
        assertEquals(rise.plusSeconds(180), plan.countdownTarget(SatellitePassPlan.Stage.RISEN))
        assertNull(plan.countdownTarget(SatellitePassPlan.Stage.PEAKED))
        assertNull(plan.countdownTarget(SatellitePassPlan.Stage.OVER))
    }

    @Test
    fun theNotificationLivesUntilAShortWhileAfterTheSet() {
        val plan = plan()
        assertEquals(360L + SatellitePassPlan.END_GRACE.seconds, plan.lifetime(rise).seconds)
        assertEquals(1L, plan.lifetime(rise.plusSeconds(10_000)).seconds)
    }

    @Test
    fun aPlanSurvivesTheMapTheWorkCarries() {
        val plan = plan()
        assertEquals(plan, SatellitePassPlan.fromMap(plan.toMap()))
    }

    @Test
    fun aMapWithAMissingWrongOrOutOfOrderValueIsNoPlan() {
        val good = plan().toMap()
        assertNull(SatellitePassPlan.fromMap(good - "rise"))
        assertNull(SatellitePassPlan.fromMap(good + ("peakAltitude" to "high")))
        assertNull(SatellitePassPlan.fromMap(good + ("peakAzimuth" to Double.NaN)))
        assertNull(SatellitePassPlan.fromMap(good + ("peak" to rise.minusSeconds(5).toEpochMilli())))
        assertNotNull(SatellitePassPlan.fromMap(good))
    }

    @Test
    fun theWordsSayWhenAndWhereBeforeTheRiseAndAfterIt() {
        val plan = plan()
        val time = { _: Instant -> "6:00 PM" }
        assertEquals(
            "Rises 6:00 PM in the NW (315°) · peak 64° in the NE (45°)",
            SatellitePassWords.body(plan, SatellitePassPlan.Stage.WAITING, time),
        )
        assertEquals("Up now · peak 64° in the NE (45°)", SatellitePassWords.body(plan, SatellitePassPlan.Stage.RISEN, time))
        assertEquals(
            "Past the peak · sets 6:00 PM in the SE (135°)",
            SatellitePassWords.body(plan, SatellitePassPlan.Stage.PEAKED, time),
        )
        assertEquals("ISS pass", SatellitePassWords.title(plan))
        assertEquals("Counting down to the rise", SatellitePassWords.note(SatellitePassPlan.Stage.WAITING))
        assertEquals("Counting down to the peak", SatellitePassWords.note(SatellitePassPlan.Stage.RISEN))
        assertNull(SatellitePassWords.note(SatellitePassPlan.Stage.PEAKED))
    }
}
