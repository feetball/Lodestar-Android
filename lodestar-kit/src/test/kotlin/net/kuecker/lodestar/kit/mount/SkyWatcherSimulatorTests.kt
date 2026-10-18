package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.Vector3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tests of the Sky-Watcher motor controller simulator itself: protocol behaviour, physics, fault injection, true geometry. */
class SkyWatcherSimulatorTests {
    private val countsPerDegree = 9_216_000.0 / 360.0

    private fun make(
        mode: SkyWatcherMountMode = SkyWatcherMountMode.ALT_AZIMUTH,
        errors: SkyWatcherPointingErrors = SkyWatcherPointingErrors(),
        axis1Inverted: Boolean = false,
        seed: Long = 1,
    ): Pair<ManualMountClock, SkyWatcherSimulator> {
        val clock = ManualMountClock(SkyWatcherTestSupport.start)
        val simulator = SkyWatcherSimulator(
            clock, SkyWatcherTestSupport.boulderLocation, mode, errors, axis1Inverted = axis1Inverted, seed = seed,
        )
        return clock to simulator
    }

    private fun ask(simulator: SkyWatcherSimulator, command: String): String {
        simulator.send(MountText.latin1Bytes(command))
        return MountText.latin1(simulator.receive(64, 1.0))
    }

    private fun initialized(): Pair<ManualMountClock, SkyWatcherSimulator> {
        val made = make()
        assertEquals("=\r", ask(made.second, ":F3\r"))
        return made
    }

    private fun counts(simulator: SkyWatcherSimulator, axis: Int): Int {
        val reply = ask(simulator, ":j$axis\r")
        return SkyWatcherCodec.decodePosition(reply.substring(1, 7))
    }

    // MARK: Inquiries and state

    @Test
    fun testTheSimulatorAnswersTheInquiries() {
        val (_, simulator) = make()
        assertEquals("=0322A5\r", ask(simulator, ":e1\r"))
        assertEquals("=0322A5\r", ask(simulator, ":e2\r"))
        assertEquals("=${SkyWatcherCodec.encode24(9_216_000)}\r", ask(simulator, ":a1\r"))
        assertEquals("=${SkyWatcherCodec.encode24(9_216_000)}\r", ask(simulator, ":a2\r"))
        assertEquals("=${SkyWatcherCodec.encode24(1_500_000)}\r", ask(simulator, ":b1\r"))
        assertEquals("=20\r", ask(simulator, ":g1\r"))
        assertEquals("=100\r", ask(simulator, ":f1\r"))
        assertEquals("=000080\r", ask(simulator, ":j1\r"))
        assertEquals("=008000\r", ask(simulator, ":q1010000\r"))
        assertEquals("=C83600\r", ask(simulator, ":D1\r"))
        assertEquals("=${SkyWatcherCodec.encode24(9_216_000 / 40)}\r", ask(simulator, ":s1\r"))
        assertEquals("=000000\r", ask(simulator, ":i1\r"))
        assertEquals("=\r", ask(simulator, ":O11\r"))
        assertEquals("=\r", ask(simulator, ":P12\r"))
    }

    @Test
    fun testMotionNeedsInitializationAndWakefulness() {
        val (_, simulator) = make()
        assertEquals("!4\r", ask(simulator, ":G110\r"))
        assertEquals("!4\r", ask(simulator, ":I1102000\r"))
        assertEquals("!4\r", ask(simulator, ":J1\r"))
        assertEquals("=\r", ask(simulator, ":F1\r"))
        assertEquals("=101\r", ask(simulator, ":f1\r"))
        assertEquals("=\r", ask(simulator, ":G110\r"))
        assertEquals("!4\r", ask(simulator, ":G210\r"))
        assertEquals("=100\r", ask(simulator, ":f2\r"))
        assertEquals("=\r", ask(simulator, ":F3\r"))
        assertEquals("=\r", ask(simulator, ":G210\r"))
        assertEquals("=\r", ask(simulator, ":B11\r"))
        assertEquals("!5\r", ask(simulator, ":G110\r"))
        assertEquals("=\r", ask(simulator, ":B10\r"))
        assertEquals("=\r", ask(simulator, ":G110\r"))
    }

    @Test
    fun testMalformedCommandsGetTheDocumentedErrors() {
        val (_, simulator) = initialized()
        assertEquals("!0\r", ask(simulator, ":x1\r"))
        assertEquals("!1\r", ask(simulator, ":j\r"))
        assertEquals("!1\r", ask(simulator, ":G11\r"))
        assertEquals("!1\r", ask(simulator, ":H12345\r"))
        assertEquals("!3\r", ask(simulator, ":G1AB\r"))
        assertEquals("!3\r", ask(simulator, ":G140\r"))
        assertEquals("!3\r", ask(simulator, ":G102\r"))
        assertEquals("!3\r", ask(simulator, ":H1ZZZZZZ\r"))
        assertEquals("!3\r", ask(simulator, ":H1abcdef\r"))
        assertEquals("!3\r", ask(simulator, ":j9\r"))
        assertEquals("!3\r", ask(simulator, ":j3\r"))
        assertEquals("!3\r", ask(simulator, ":I1000000\r"))
        // Noise in front of the colon is ignored, and a second colon starts the command again.
        assertEquals("=000080\r", ask(simulator, "xx:j1\r"))
        assertEquals("=000080\r", ask(simulator, ":G1:j1\r"))
        assertEquals("=000080\r", ask(simulator, "::j1\r"))
        // A line without a colon gets no answer at all.
        simulator.send(MountText.latin1Bytes("j1\r"))
        assertFailsWith<MountError.Timeout> { simulator.receive(64, 0.5) }
        // Neither does a command that is not finished.
        simulator.send(MountText.latin1Bytes(":j1"))
        assertFailsWith<MountError.Timeout> { simulator.receive(64, 0.5) }
        simulator.send(MountText.latin1Bytes("\r"))
        assertEquals("=000080\r", MountText.latin1(simulator.receive(64, 1.0)))
    }

    @Test
    fun testPositionCanBeSetWhileStopped() {
        val (_, simulator) = initialized()
        assertEquals("=\r", ask(simulator, ":E1120080\r"))
        assertEquals("=120080\r", ask(simulator, ":j1\r"))
        assertEquals("=120080\r", ask(simulator, ":d1\r"))
        assertEquals(0x12, counts(simulator, 1))
        assertEquals("=\r", ask(simulator, ":E2FFFF7F\r"))
        assertEquals(-1, counts(simulator, 2))
        assertEquals("=\r", ask(simulator, ":G110\r"))
        assertEquals("=\r", ask(simulator, ":I1102000\r"))
        assertEquals("=\r", ask(simulator, ":J1\r"))
        assertEquals("!2\r", ask(simulator, ":E1000080\r"))
        assertEquals("!2\r", ask(simulator, ":G110\r"))
        assertEquals("!2\r", ask(simulator, ":J1\r"))
    }

    // MARK: Speed mode

    @Test
    fun testSpeedModeRunsAtTheStepPeriod() {
        val (clock, simulator) = initialized()
        val sidereal = SkyWatcherCodec.speed(SkyWatcherSky.siderealDegreesPerSecond, 9_216_000, 1_500_000, 32)!!
        assertEquals("=\r", ask(simulator, ":G110\r"))
        assertEquals("=\r", ask(simulator, ":I1${SkyWatcherCodec.encode24(sidereal.period)}\r"))
        assertEquals("=\r", ask(simulator, ":J1\r"))
        assertEquals("=111\r", ask(simulator, ":f1\r"))
        clock.advance(100.0)
        // 100 s of sidereal motion (plus the latency of the replies, about 0.15 s): 106.96 counts per second.
        assertEquals(10_696.0, counts(simulator, 1).toDouble(), 15.0)
        assertEquals(100.15 * SkyWatcherSky.siderealDegreesPerSecond, simulator.axis1Degrees, 1e-4)
        assertEquals(0, counts(simulator, 2))
        assertEquals(1, simulator.speedStarts)
        assertEquals("=${SkyWatcherCodec.encode24(sidereal.period)}\r", ask(simulator, ":i1\r"))
        // A new period changes the speed while the axis runs (low-speed mode).
        assertEquals("=\r", ask(simulator, ":I1${SkyWatcherCodec.encode24(sidereal.period / 2)}\r"))
        val before = counts(simulator, 1)
        clock.advance(10.0)
        assertEquals(2 * 10.05 * 106.96, (counts(simulator, 1) - before).toDouble(), 8.0)
    }

    @Test
    fun testDirectionAndHighSpeedMode() {
        val (clock, simulator) = initialized()
        // Axis 2 backwards at high speed: 3°/s.
        assertEquals("=\r", ask(simulator, ":G231\r"))
        assertEquals("=\r", ask(simulator, ":I2${SkyWatcherCodec.encode24(625)}\r"))
        assertEquals("=\r", ask(simulator, ":J2\r"))
        // B0 speed mode, B1 counter-clockwise, B2 fast: digit 7.
        assertEquals("=711\r", ask(simulator, ":f2\r"))
        clock.advance(10.0)
        assertEquals(-30.0, simulator.axis2Degrees, 0.3)
        assertEquals(3.0, simulator.peakSpeedDegreesPerSecond, 0.01)
        // In high-speed mode the period cannot be changed while the axis runs.
        assertEquals("!2\r", ask(simulator, ":I2${SkyWatcherCodec.encode24(700)}\r"))
        // The controller never exceeds its maximum speed: period 6 would be far faster.
        assertEquals("=\r", ask(simulator, ":L2\r"))
        assertEquals("=\r", ask(simulator, ":G230\r"))
        assertEquals("=\r", ask(simulator, ":I2${SkyWatcherCodec.encode24(6)}\r"))
        assertEquals("=\r", ask(simulator, ":J2\r"))
        val start = simulator.axis2Degrees
        clock.advance(1.0)
        assertEquals(3.0, simulator.axis2Degrees - start, 0.01)
    }

    @Test
    fun testStoppingBrakesOrStopsAtOnce() {
        val (clock, simulator) = initialized()
        assertEquals("=\r", ask(simulator, ":G130\r"))
        assertEquals("=\r", ask(simulator, ":I1${SkyWatcherCodec.encode24(625)}\r"))
        assertEquals("=\r", ask(simulator, ":J1\r"))
        clock.advance(10.0)
        val before = simulator.axis1Degrees
        assertEquals("=\r", ask(simulator, ":K1\r"))
        // Still decelerating right after the command (speed mode, fast, running).
        assertEquals("=511\r", ask(simulator, ":f1\r"))
        clock.advance(2.0)
        assertEquals("=101\r", ask(simulator, ":f1\r"))
        // Braking from 3°/s at 6°/s² takes 0.5 s and 0.75°.
        assertEquals(0.75, simulator.axis1Degrees - before, 0.05)
        // L stops instantly.
        assertEquals("=\r", ask(simulator, ":G130\r"))
        assertEquals("=\r", ask(simulator, ":J1\r"))
        clock.advance(5.0)
        assertEquals("=\r", ask(simulator, ":L1\r"))
        val stopped = simulator.axis1Degrees
        clock.advance(5.0)
        assertEquals(stopped, simulator.axis1Degrees, 1e-9)
        assertEquals("=101\r", ask(simulator, ":f1\r"))
        assertEquals("=\r", ask(simulator, ":K3\r"))
    }

    // MARK: GOTO

    @Test
    fun testFastGotoAcceleratesCruisesAndBrakes() {
        val (clock, simulator) = initialized()
        val increment = (90.0 * countsPerDegree).toInt()
        assertEquals("=\r", ask(simulator, ":G100\r"))
        assertEquals("=\r", ask(simulator, ":H1${SkyWatcherCodec.encode24(increment)}\r"))
        assertEquals("=\r", ask(simulator, ":M1${SkyWatcherCodec.encode24(3200)}\r"))
        assertEquals("=\r", ask(simulator, ":J1\r"))
        // GOTO mode (B0 = 0), fast (B2): digit 4.
        assertEquals("=411\r", ask(simulator, ":f1\r"))
        assertEquals("=${SkyWatcherCodec.encodePosition(increment)}\r", ask(simulator, ":h1\r"))
        assertEquals("=${SkyWatcherCodec.encode24(increment)}\r", ask(simulator, ":k10\r"))
        clock.advance(10.0)
        assertTrue(simulator.isMoving)
        assertTrue(simulator.axis1Degrees in 20.0..40.0, "${simulator.axis1Degrees}")
        // 90° at 3°/s with 6°/s² ramps: 30.5 s.
        clock.advance(19.0)
        assertTrue(simulator.isMoving)
        clock.advance(2.0)
        assertTrue(!simulator.isMoving)
        assertEquals(90.0, simulator.axis1Degrees, 1e-3)
        assertEquals(increment, counts(simulator, 1))
        assertEquals("=101\r", ask(simulator, ":f1\r"))
        assertEquals(1, simulator.gotosStarted)
        assertEquals(3.0, simulator.peakSpeedDegreesPerSecond, 0.01)
        // Backwards: the direction digit 1 decreases the count by the increment.
        assertEquals("=\r", ask(simulator, ":G101\r"))
        assertEquals("=\r", ask(simulator, ":H1${SkyWatcherCodec.encode24(increment / 3)}\r"))
        assertEquals("=\r", ask(simulator, ":J1\r"))
        clock.advance(60.0)
        assertEquals(60.0, simulator.axis1Degrees, 1e-3)
    }

    @Test
    fun testSlowGotoRunsAtConstantSpeedAndAbsoluteTargetsWork() {
        val (clock, simulator) = initialized()
        val increment = (5.0 * countsPerDegree).toInt()
        assertEquals("=\r", ask(simulator, ":G220\r"))
        assertEquals("=\r", ask(simulator, ":H2${SkyWatcherCodec.encode24(increment)}\r"))
        assertEquals("=\r", ask(simulator, ":J2\r"))
        // Slow GOTO: digit 0 (B0 = 0 goto, B2 = 0 slow).
        assertEquals("=011\r", ask(simulator, ":f2\r"))
        clock.advance(5.0)
        assertEquals(2.5, simulator.axis2Degrees, 0.1)
        clock.advance(6.0)
        assertEquals(5.0, simulator.axis2Degrees, 1e-3)
        assertTrue(!simulator.isMoving)
        // An absolute target (S) needs no increment.
        assertEquals("=\r", ask(simulator, ":G100\r"))
        assertEquals("=\r", ask(simulator, ":S1${SkyWatcherCodec.encodePosition(-50_000)}\r"))
        assertEquals("=\r", ask(simulator, ":J1\r"))
        clock.advance(30.0)
        assertEquals(-50_000, counts(simulator, 1))
        // Both axes at once with J3.
        assertEquals("=\r", ask(simulator, ":G100\r"))
        assertEquals("=\r", ask(simulator, ":G200\r"))
        assertEquals("=\r", ask(simulator, ":H1${SkyWatcherCodec.encode24(1000)}\r"))
        assertEquals("=\r", ask(simulator, ":H2${SkyWatcherCodec.encode24(2000)}\r"))
        assertEquals("=\r", ask(simulator, ":J3\r"))
        clock.advance(10.0)
        assertEquals(-49_000, counts(simulator, 1))
        assertEquals(increment + 2000, counts(simulator, 2))
        assertEquals(4, simulator.gotosStarted)
    }

    @Test
    fun testStoppingAGotoAndErrorsOfTheGotoSequence() {
        val (clock, simulator) = initialized()
        assertEquals("=\r", ask(simulator, ":G100\r"))
        assertEquals("=\r", ask(simulator, ":H1${SkyWatcherCodec.encode24(2_000_000)}\r"))
        assertEquals("=\r", ask(simulator, ":J1\r"))
        assertEquals("!2\r", ask(simulator, ":J1\r"))
        clock.advance(5.0)
        assertEquals("=\r", ask(simulator, ":K1\r"))
        clock.advance(3.0)
        assertTrue(!simulator.isMoving)
        assertTrue(simulator.axis1Degrees in 10.0..20.0, "${simulator.axis1Degrees}")
        // Starting in speed mode without a period is refused.
        assertEquals("=\r", ask(simulator, ":G210\r"))
        assertEquals("!3\r", ask(simulator, ":J2\r"))
    }

    // MARK: Faults

    @Test
    fun testInjectedFaultsDamageTheReply() {
        val (_, simulator) = make()
        simulator.inject("j", MountSimulatedFault.Silence)
        simulator.send(MountText.latin1Bytes(":j1\r"))
        assertFailsWith<MountError.Timeout> { simulator.receive(64, 0.6) }
        assertEquals("=000080\r", ask(simulator, ":j1\r"))
        simulator.inject("j1", MountSimulatedFault.Replace("=12"))
        assertEquals("=12", ask(simulator, ":j1\r"))
        simulator.inject("f", MountSimulatedFault.Truncate(2))
        assertEquals("=1", ask(simulator, ":f1\r"))
        simulator.inject("a", MountSimulatedFault.Disconnect)
        simulator.send(MountText.latin1Bytes(":a1\r"))
        assertFailsWith<MountError.Transport> { simulator.receive(64, 0.6) }
        assertFailsWith<MountError.Transport> { simulator.send(MountText.latin1Bytes(":j1\r")) }
        assertEquals(listOf("j1", "j1", "j1", "f1"), simulator.commandLog.take(4))
    }

    @Test
    fun testRandomLossAndGarbleAreSeededAndBounded() {
        val (_, simulator) = make(seed = 99)
        simulator.requestLossProbability = 0.3
        var answered = 0
        for (index in 0 until 1000) {
            simulator.send(MountText.latin1Bytes(":j1\r"))
            try {
                simulator.receive(64, 0.1)
                answered += 1
            } catch (error: MountError.Timeout) {
                // lost
            }
            simulator.discardPendingInput()
        }
        assertTrue(answered in 620..780, "$answered answered")
        // A lost reply still changes the mount's state.
        val (_, other) = make(seed = 5)
        other.replyLossProbability = 1.0
        other.send(MountText.latin1Bytes(":F1\r"))
        assertFailsWith<MountError.Timeout> { other.receive(64, 0.1) }
        other.replyLossProbability = 0.0
        assertEquals("=101\r", ask(other, ":f1\r"))
        // All four kinds of damage occur, and every damaged reply is either rejected by the codec or still readable.
        val (_, damaged) = make(seed = 3)
        damaged.garbleProbability = 1.0
        val kinds = HashSet<String>()
        for (index in 0 until 200) {
            damaged.send(MountText.latin1Bytes(":j1\r"))
            val reply = try {
                MountText.latin1Bytes(MountText.latin1(damaged.receive(64, 0.1)))
            } catch (error: MountError.Timeout) {
                kinds.add("incomplete")
                continue
            }
            damaged.discardPendingInput()
            if (!reply.contains(0x0D.toByte())) {
                kinds.add("incomplete")
                continue
            }
            try {
                val parsed = SkyWatcherCodec.parseDatagram(reply)
                kinds.add(if (reply.size > 9) "doubled" else "junk in front")
                assertEquals(SkyWatcherReply.Success("000080"), parsed)
            } catch (error: MountError.MalformedReply) {
                kinds.add("bad character")
            }
        }
        assertEquals(setOf("incomplete", "doubled", "junk in front", "bad character"), kinds)
    }

    @Test
    fun testTheRandomGeneratorIsIdenticalInBothLanguages() {
        val first = SkyWatcherSimulatedRandom(1)
        val expected = listOf(0.28083505005035947, 0.6711372530266764, 0.7258461452833668, 0.303529299965799, 0.056176763098259475)
        for (value in expected) assertEquals(value, first.next(), 1e-15)
        val second = SkyWatcherSimulatedRandom(12345)
        for (value in listOf(0.5950924872394683, 0.7531544145408081, 0.07656564459726423)) assertEquals(value, second.next(), 1e-15)
        assertEquals(SkyWatcherSimulatedRandom(0).next(), SkyWatcherSimulatedRandom(0x2545F4914F6CDD1DL).next(), 0.0)
        val random = SkyWatcherSimulatedRandom(77)
        for (index in 0 until 1000) assertTrue(random.next() in 0.0..<1.0)
    }

    // MARK: True geometry

    private fun assertVector(expected: Vector3, actual: Vector3, tolerance: Double = 1e-9) {
        assertEquals(expected.x, actual.x, tolerance, "x $actual")
        assertEquals(expected.y, actual.y, tolerance, "y $actual")
        assertEquals(expected.z, actual.z, tolerance, "z $actual")
    }

    @Test
    fun testTruePointingFollowsTheGeometry() {
        val (_, home) = make()
        assertVector(Vector3(0.0, 1.0, 0.0), home.truePointing())
        home.placeAxes(90.0, 45.0)
        assertVector(Vector3(AstroMath.cosDeg(45.0), 0.0, AstroMath.sinDeg(45.0)), home.truePointing())
        assertEquals(90.0, home.axis1Degrees, 1e-9)
        assertEquals(counts(home, 1), (90.0 * countsPerDegree).toInt())
        val (_, tilted) = make(errors = SkyWatcherPointingErrors(baseTiltX = 1.0))
        assertVector(Vector3(0.0, AstroMath.cosDeg(1.0), AstroMath.sinDeg(1.0)), tilted.truePointing())
        val (_, polar) = make(SkyWatcherMountMode.EQUATORIAL, SkyWatcherPointingErrors(baseTiltY = 0.5))
        assertVector(Vector3(AstroMath.sinDeg(0.5), 0.0, AstroMath.cosDeg(0.5)), polar.truePointing())
        val (_, index) = make(SkyWatcherMountMode.EQUATORIAL, SkyWatcherPointingErrors(axis2Index = 3.0))
        // Home with a dec index error: 3° away from the pole, towards the west (positive angles).
        assertVector(Vector3(0.0, -AstroMath.sinDeg(3.0), AstroMath.cosDeg(3.0)), index.truePointing())
    }

    @Test
    fun testInvertedAxesCountBackwards() {
        val (clock, simulator) = make(axis1Inverted = true)
        simulator.placeAxes(10.0, 0.0)
        assertEquals(10.0, simulator.axis1Degrees, 1e-9)
        assertEquals(-(10.0 * countsPerDegree).toInt(), counts(simulator, 1))
        assertEquals("=\r", ask(simulator, ":F3\r"))
        assertEquals("=\r", ask(simulator, ":G110\r"))
        assertEquals("=\r", ask(simulator, ":I1${SkyWatcherCodec.encode24(6000)}\r"))
        assertEquals("=\r", ask(simulator, ":J1\r"))
        clock.advance(10.0)
        // Direction digit 0 increases the count, which turns an inverted axis towards smaller angles.
        assertTrue(simulator.axis1Degrees < 10.0)
        assertTrue(abs(simulator.truePointing().length - 1.0) < 1e-12)
    }
}
