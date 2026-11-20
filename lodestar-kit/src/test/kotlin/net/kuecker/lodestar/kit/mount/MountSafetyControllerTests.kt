package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.mount.MountNudgeDirection.EAST
import net.kuecker.lodestar.kit.mount.MountNudgeDirection.NORTH
import net.kuecker.lodestar.kit.mount.MountNudgeDirection.SOUTH
import net.kuecker.lodestar.kit.mount.MountNudgeRate.CENTER
import net.kuecker.lodestar.kit.mount.MountNudgeRate.FIND
import net.kuecker.lodestar.kit.mount.MountNudgeRate.GUIDE
import net.kuecker.lodestar.kit.mount.MountNudgeRate.SLEW
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A driver the test scripts and whose calls it records, for situations the simulators cannot produce: a stop that fails for one
 * direction only, a start that is refused after another direction was stopped, an abort that fails while the nudge stop works.
 */
private class MountSafetyScriptedDriver : MountDriver {
    /** The calls received, oldest first: "start north", "stop north", "abort", "slew". */
    val calls = ArrayList<String>()

    /** Every [startNudge] fails with this while it is set. */
    var startNudgeError: MountError? = null

    /** Every [abort] fails with this while it is set. */
    var abortError: MountError? = null

    /** Stopping one of these directions fails with [stopNudgeError]. */
    var failingStops: Set<MountNudgeDirection> = emptySet()
    var stopNudgeError: MountError = MountError.Busy

    override val kind: MountProtocol get() = MountProtocol.LX200
    override val epoch: MountEpoch get() = MountEpoch.J2000

    override fun connect(): MountCapabilities = MountCapabilities(
        kind = MountProtocol.LX200, name = "Scripted", firmware = null, canSlew = true, canSync = true, canAbort = true,
        canSetTracking = true, canNudge = true, canSetSiteAndTime = true, gotoNeedsAlignment = false, isAligned = true,
    )

    override fun disconnect() {}

    override fun readPosition(): EquatorialCoordinate = EquatorialCoordinate(rightAscension = 100.0, declination = 20.0)

    override fun readSlewing(): Boolean = false

    override fun readTracking(): Boolean? = true

    override fun readAligned(): Boolean? = true

    override fun slew(target: EquatorialCoordinate) {
        calls.add("slew")
    }

    override fun sync(target: EquatorialCoordinate) {
        calls.add("sync")
    }

    override fun abort() {
        calls.add("abort")
        abortError?.let { throw it }
    }

    override fun setTracking(enabled: Boolean) {}

    override fun startNudge(direction: MountNudgeDirection, rate: MountNudgeRate, durationSeconds: Double) {
        calls.add("start ${direction.rawValue}")
        startNudgeError?.let { throw it }
    }

    override fun stopNudge(direction: MountNudgeDirection) {
        calls.add("stop ${direction.rawValue}")
        if (direction in failingStops) throw stopNudgeError
    }

    override fun setSiteAndTime(location: GeographicLocation, date: Instant) {}
}

private class MountSafetyScriptedRig(
    val driver: MountSafetyScriptedDriver,
    val controller: MountController,
    val clock: ManualMountClock,
)

/** A connected controller on a scripted driver whose call record is empty. */
private fun mountSafetyScriptedRig(): MountSafetyScriptedRig {
    val clock = ManualMountClock(MountTestSupport.start)
    val driver = MountSafetyScriptedDriver()
    val controller = MountController(driver, MountTestSupport.boulder, MountSettings.standard, clock)
    controller.connect()
    driver.calls.clear()
    return MountSafetyScriptedRig(driver, controller, clock)
}

/** The stop logic of the controller: a nudge that replaces another one and cannot start, and an abort whose first step fails. */
class MountSafetyControllerTests {
    // MARK: A nudge that replaces another one

    /**
     * Old code: the nudge north is stopped and `nudgeDirection` cleared, the start east throws, and `phase` stays NUDGING with no
     * direction behind it. The phase check fails, and so would the goto (Busy).
     */
    @Test
    fun testAReplacementNudgeThatCannotStartLeavesTheControllerIdle() {
        val rig = mountSafetyScriptedRig()
        rig.controller.nudge(NORTH, CENTER, 5.0)
        assertEquals(MountPhase.NUDGING, rig.controller.status.phase)
        rig.driver.startNudgeError = MountError.Rejected("refused")
        assertEquals(MountError.Rejected("refused"), assertFailsWith<MountError> { rig.controller.nudge(EAST, CENTER, 5.0) })
        assertEquals(listOf("start north", "stop north", "start east", "stop east"), rig.driver.calls)
        assertEquals(MountPhase.IDLE, rig.controller.status.phase)
        assertEquals(MountError.Rejected("refused"), rig.controller.status.lastError)
        // Nothing runs, so Stop has nothing to do and sends nothing.
        rig.controller.stopNudge()
        assertEquals(4, rig.driver.calls.size)
        // The controller is free again.
        rig.driver.startNudgeError = null
        rig.controller.goto(MountTestSupport.andromeda)
        assertEquals(MountPhase.SLEWING, rig.controller.status.phase)
    }

    /**
     * Pins the part that stays as it was: when the old direction cannot be stopped it still runs as far as anyone knows, so the
     * controller keeps it and a later Stop sends the stop for it. The new nudge is never started.
     */
    @Test
    fun testAReplacementNudgeWhoseOldDirectionCannotBeStoppedStaysStoppable() {
        val rig = mountSafetyScriptedRig()
        rig.controller.nudge(NORTH, CENTER, 5.0)
        rig.driver.failingStops = setOf(NORTH)
        assertEquals(MountError.Busy, assertFailsWith<MountError> { rig.controller.nudge(EAST, CENTER, 5.0) })
        assertEquals(MountPhase.NUDGING, rig.controller.status.phase)
        assertEquals(listOf("start north", "stop north"), rig.driver.calls)
        rig.driver.failingStops = emptySet()
        rig.controller.stopNudge()
        assertEquals(listOf("start north", "stop north", "stop north"), rig.driver.calls)
        assertEquals(MountPhase.IDLE, rig.controller.status.phase)
    }

    /**
     * The new move may be running although neither its start nor its stop was confirmed (a lost reply): it must stay on the books.
     * Old code: `nudgeDirection` is null after the failure, so `stopNudge()` returns at once and sends nothing.
     */
    @Test
    fun testAMoveThatCannotBeStartedOrConfirmedStoppedStaysOnTheBooksForStop() {
        val rig = mountSafetyScriptedRig()
        rig.controller.nudge(NORTH, CENTER, 5.0)
        rig.driver.startNudgeError = MountError.Timeout("move")
        rig.driver.failingStops = setOf(EAST)
        rig.driver.stopNudgeError = MountError.Timeout("stop")
        assertEquals(MountError.Timeout("move"), assertFailsWith<MountError> { rig.controller.nudge(EAST, CENTER, 5.0) })
        assertEquals(MountPhase.NUDGING, rig.controller.status.phase)
        rig.driver.calls.clear()
        rig.driver.failingStops = emptySet()
        rig.controller.stopNudge()
        assertEquals(listOf("stop east"), rig.driver.calls)
        assertEquals(MountPhase.IDLE, rig.controller.status.phase)
    }

    /**
     * The same situation, ended by the poll after the duration instead of by Stop. Old code: the poll needs a direction to end the
     * nudge, finds none, and the phase stays NUDGING for good.
     */
    @Test
    fun testTheEndOfTheDurationStopsAMoveThatCouldNotBeConfirmedStopped() {
        val rig = mountSafetyScriptedRig()
        rig.controller.nudge(NORTH, CENTER, 5.0)
        rig.driver.startNudgeError = MountError.Timeout("move")
        rig.driver.failingStops = setOf(EAST)
        rig.driver.stopNudgeError = MountError.Timeout("stop")
        assertEquals(MountError.Timeout("move"), assertFailsWith<MountError> { rig.controller.nudge(EAST, CENTER, 5.0) })
        rig.clock.advance(6.0)
        rig.driver.calls.clear()
        rig.driver.failingStops = emptySet()
        val status = rig.controller.poll()
        assertEquals(listOf("stop east"), rig.driver.calls)
        assertEquals(MountPhase.IDLE, status.phase)
    }

    /**
     * Alpaca mount: a guide-rate pulse that the mount refuses replaces a running move. Old code: the move is stopped, and the
     * controller stays in NUDGING with no direction, so the goto is refused as busy.
     */
    @Test
    fun testARefusedReplacementNudgeOnAlpacaLeavesTheControllerIdle() {
        val rig = MountTestSupport.rig("alpaca-topocentric")
        rig.controller.connect()
        rig.controller.nudge(NORTH, FIND, 5.0)
        assertTrue(rig.mount.isNudging)
        rig.alpacaSimulator?.inject("pulseguide", AlpacaFault.Error(1025, "bad duration"))
        val error = assertFailsWith<MountError> { rig.controller.nudge(EAST, GUIDE, 5.0) }
        assertIs<MountError.Rejected>(error)
        assertFalse(rig.mount.isNudging)
        assertEquals(MountPhase.IDLE, rig.controller.status.phase)
        rig.controller.goto(MountTestSupport.andromeda)
        assertEquals(MountPhase.SLEWING, rig.controller.status.phase)
    }

    // MARK: Abort

    /** Old code: the nudge is stopped only if the abort succeeded, so the calls end at "abort" and the nudge runs on. */
    @Test
    fun testAbortStopsTheNudgeEvenWhenTheAbortFails() {
        val rig = mountSafetyScriptedRig()
        rig.controller.nudge(SOUTH, CENTER, 5.0)
        rig.driver.abortError = MountError.Rejected("jammed")
        assertEquals(MountError.Rejected("jammed"), assertFailsWith<MountError> { rig.controller.abort() })
        assertEquals(listOf("start south", "abort", "stop south"), rig.driver.calls)
        // The failure leaves the phase alone, and the call can be repeated.
        assertEquals(MountPhase.NUDGING, rig.controller.status.phase)
        assertEquals(MountError.Rejected("jammed"), rig.controller.status.lastError)
        rig.driver.abortError = null
        rig.controller.abort()
        assertEquals(listOf("start south", "abort", "stop south", "abort", "stop south"), rig.driver.calls)
        assertEquals(MountPhase.IDLE, rig.controller.status.phase)
    }

    /**
     * Both steps keep their three attempts on communication failures, and the failure of the abort (the first) is the one
     * reported. Old code: only the three aborts are sent.
     */
    @Test
    fun testAbortRepeatsBothStepsAndReportsTheFirstFailure() {
        val rig = mountSafetyScriptedRig()
        rig.controller.nudge(SOUTH, CENTER, 5.0)
        rig.driver.abortError = MountError.Timeout("abort")
        rig.driver.failingStops = setOf(SOUTH)
        rig.driver.stopNudgeError = MountError.Transport("gone")
        assertEquals(MountError.Timeout("abort"), assertFailsWith<MountError> { rig.controller.abort() })
        assertEquals(
            listOf("start south", "abort", "abort", "abort", "stop south", "stop south", "stop south"),
            rig.driver.calls,
        )
        assertEquals(MountPhase.NUDGING, rig.controller.status.phase)
    }

    /** Pins the other order: the abort worked and the nudge stop failed, which is reported as before and leaves the phase alone. */
    @Test
    fun testAbortReportsTheFailureOfTheNudgeStopWhenTheAbortWorked() {
        val rig = mountSafetyScriptedRig()
        rig.controller.nudge(SOUTH, CENTER, 5.0)
        rig.driver.failingStops = setOf(SOUTH)
        assertEquals(MountError.Busy, assertFailsWith<MountError> { rig.controller.abort() })
        assertEquals(listOf("start south", "abort", "stop south"), rig.driver.calls)
        assertEquals(MountPhase.NUDGING, rig.controller.status.phase)
        rig.driver.failingStops = emptySet()
        rig.controller.abort()
        assertEquals(MountPhase.IDLE, rig.controller.status.phase)
    }

    /**
     * NexStar and Alpaca: `abort` cancels only a GoTo. With the abort failing on every attempt the nudge is still stopped. The
     * Alpaca simulator drops the failed `abortslew` before it acts, so its move keeps running on the old code; the NexStar
     * simulator acts on a command whose reply it then drops, so there the stop that follows the aborts is looked for in the log.
     */
    @Test
    fun testAbortStopsTheNudgeOfMountsWhoseAbortOnlyCancelsAGoTo() {
        val alpaca = MountTestSupport.rig("alpaca-topocentric")
        alpaca.controller.connect()
        alpaca.controller.nudge(SOUTH, SLEW, 5.0)
        assertTrue(alpaca.mount.isNudging)
        alpaca.alpacaSimulator?.inject("abortslew", AlpacaFault.Timeout, times = 3)
        assertIs<MountError.Timeout>(assertFailsWith<MountError> { alpaca.controller.abort() })
        assertFalse(alpaca.mount.isNudging)
        assertEquals(MountPhase.NUDGING, alpaca.controller.status.phase)
        alpaca.controller.abort()
        assertEquals(MountPhase.IDLE, alpaca.controller.status.phase)

        val nexStar = MountTestSupport.rig("nexstar-celestron")
        nexStar.controller.connect()
        nexStar.controller.nudge(SOUTH, SLEW, 5.0)
        assertTrue(nexStar.mount.isNudging)
        val simulator = assertNotNull(nexStar.streamSimulator)
        simulator.inject("M", MountSimulatedFault.Silence, times = 3)
        assertIs<MountError.Timeout>(assertFailsWith<MountError> { nexStar.controller.abort() })
        val log = simulator.commandLog.toList()
        val lastCancel = log.lastIndexOf("M")
        val lastMove = log.lastIndexOf("P")
        assertTrue(lastCancel >= 0, "$log")
        assertTrue(lastMove > lastCancel, "$log")
        assertFalse(nexStar.mount.isNudging)
        assertEquals(MountPhase.NUDGING, nexStar.controller.status.phase)
        nexStar.controller.abort()
        assertEquals(MountPhase.IDLE, nexStar.controller.status.phase)
    }
}
