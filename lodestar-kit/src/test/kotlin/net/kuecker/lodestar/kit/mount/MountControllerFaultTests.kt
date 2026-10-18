package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.mount.MountTestSupport.andromeda
import net.kuecker.lodestar.kit.mount.MountTestSupport.rigNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MountControllerFaultTests {
    /** The command label of the position query of each rig, and how to make that exchange fail. */
    private fun silence(rig: MountRig, times: Int = 1) {
        rig.streamSimulator?.inject(if (rig.name.startsWith("lx200")) "GR" else "e", MountSimulatedFault.Silence, times)
        rig.alpacaSimulator?.inject("rightascension", AlpacaFault.Timeout, times)
    }

    // MARK: Timeouts

    @Test
    fun testASilentMountEndsTheSessionAfterThreeFailedPolls() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            silence(rig, 100)
            val begin = rig.clock.secondsSince1970
            val first = rig.controller.poll()
            assertIs<MountError.Timeout>(first.lastError, name)
            assertEquals(MountPhase.IDLE, first.phase, name)
            assertEquals(MountPhase.IDLE, rig.controller.poll().phase, name)
            val third = rig.controller.poll()
            assertEquals(MountPhase.DISCONNECTED, third.phase, name)
            assertEquals(MountError.ConnectionLost, third.lastError, name)
            // Three exchanges each waited for their whole timeout.
            assertTrue(rig.clock.secondsSince1970 - begin >= 3 * 2.9, "$name: ${rig.clock.secondsSince1970 - begin}")
            assertFailsWith<MountError.NotConnected>(name) { rig.controller.goto(andromeda) }
        }
    }

    @Test
    fun testOneFailedPollDoesNotCountAgainstLaterOnes() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            silence(rig, 1)
            assertIs<MountError.Timeout>(rig.controller.poll().lastError, name)
            val recovered = rig.controller.poll()
            assertNull(recovered.lastError, name)
            assertEquals(MountPhase.IDLE, recovered.phase, name)
            silence(rig, 2)
            rig.controller.poll()
            assertEquals(MountPhase.IDLE, rig.controller.poll().phase, name)
            assertNull(rig.controller.poll().lastError, name)
        }
    }

    @Test
    fun testALinkThatDiesDuringASlewIsReported() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            rig.controller.goto(andromeda)
            silence(rig, 100)
            rig.controller.poll()
            rig.controller.poll()
            val status = rig.controller.poll()
            assertEquals(MountPhase.DISCONNECTED, status.phase, name)
            assertEquals(MountSlewOutcome.Failed(MountError.ConnectionLost), status.outcome, name)
        }
    }

    @Test
    fun testASlewThatTakesTooLongIsAborted() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name, MountSettings(maximumSlewSeconds = 30.0), slewRate = 0.05)
            rig.controller.connect()
            rig.controller.goto(andromeda)
            val status = rig.controller.awaitSettled()
            assertEquals(MountPhase.IDLE, status.phase, name)
            assertEquals(MountSlewOutcome.Failed(MountError.SlewTimeout(30.0)), status.outcome, name)
            assertFalse(rig.mount.isSlewing, name)
            assertTrue(rig.mount.stopsReceived >= 1, name)
            val seconds = rig.clock.secondsSince1970 - MountTestSupport.start.epochSecond
            assertTrue(seconds in 30.0..45.0, "$name took $seconds")
        }
    }

    @Test
    fun testAMountThatStopsShortIsReportedAsSuch() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            rig.controller.goto(andromeda)
            rig.clock.advance(1.5)
            assertEquals(MountPhase.SLEWING, rig.controller.poll().phase, name)
            rig.mount.stop()
            val status = rig.controller.poll()
            assertEquals(MountPhase.IDLE, status.phase, name)
            val failure = assertIs<MountSlewOutcome.Failed>(status.outcome, name)
            val error = assertIs<MountError.SlewStopped>(failure.error, name)
            assertTrue(error.remainingDegrees > 1.0, name)
            assertTrue(error.userMessage.contains("short of the target"), name)
        }
    }

    @Test
    fun testAMountThatNeverStartsIsReportedAfterTheGracePeriod() {
        val rig = MountTestSupport.rig("lx200-onstep")
        rig.controller.connect()
        rig.controller.goto(andromeda)
        rig.mount.stop()
        assertEquals(MountPhase.SLEWING, rig.controller.poll().phase)
        rig.clock.advance(6.0)
        val status = rig.controller.poll()
        assertEquals(MountPhase.IDLE, status.phase)
        assertIs<MountError.SlewStopped>(assertIs<MountSlewOutcome.Failed>(status.outcome).error)
    }

    @Test
    fun testGotoToTheCurrentPositionArrivesAtOnce() {
        val rig = MountTestSupport.rig("nexstar-synscan")
        rig.controller.connect()
        val here = rig.epoch.toJ2000(MountTestSupport.parkPosition, rig.frame())
        rig.controller.goto(here)
        rig.mount.stop()
        val status = rig.controller.poll()
        assertEquals(MountPhase.IDLE, status.phase)
        assertIs<MountSlewOutcome.Arrived>(status.outcome)
    }

    // MARK: Refusals

    @Test
    fun testOnStepRefusalsAreExplained() {
        val expected = mapOf(
            '1' to MountError.MountHorizonLimit,
            '2' to MountError.MountOverheadLimit,
            '3' to MountError.Standby,
            '4' to MountError.Parked,
            '5' to MountError.SlewInProgress,
            '6' to MountError.OutsideMountLimits,
            '8' to MountError.Busy,
        )
        for ((code, error) in expected) {
            val rig = MountTestSupport.rig("lx200-onstep")
            rig.controller.connect()
            (rig.streamSimulator as LX200Simulator).refusalCode = code
            assertEquals(error, assertFailsWith<MountError> { rig.controller.goto(andromeda) }, "code $code")
            assertEquals(MountPhase.IDLE, rig.controller.status.phase, "code $code")
            assertEquals(0, rig.mount.slewsStarted, "code $code")
            assertEquals(error, rig.controller.status.lastError, "code $code")
        }
        val fault = MountTestSupport.rig("lx200-onstep")
        fault.controller.connect()
        val faulty = fault.streamSimulator as LX200Simulator
        faulty.refusalCode = '7'
        assertIs<MountError.HardwareFault>(assertFailsWith<MountError> { fault.controller.goto(andromeda) })
        faulty.refusalCode = '9'
        assertIs<MountError.Rejected>(assertFailsWith<MountError> { fault.controller.goto(andromeda) })
    }

    @Test
    fun testMeadeRefusalsCarryTheirTextAndAreStillTyped() {
        val rig = MountTestSupport.rig("lx200-meade-low")
        rig.controller.connect()
        val simulator = rig.streamSimulator as LX200Simulator
        simulator.refusalCode = '1'
        assertEquals(MountError.MountHorizonLimit, assertFailsWith<MountError> { rig.controller.goto(andromeda) })
        simulator.refusalCode = '2'
        assertEquals(MountError.MountOverheadLimit, assertFailsWith<MountError> { rig.controller.goto(andromeda) })
        // The text after the digit was consumed: the next exchange is clean.
        simulator.refusalCode = null
        rig.controller.goto(andromeda)
        assertEquals(MountPhase.SLEWING, rig.controller.status.phase)
    }

    @Test
    fun testOnStepSyncRefusalsAreExplained() {
        val rig = MountTestSupport.rig("lx200-onstep")
        rig.controller.connect()
        val simulator = rig.streamSimulator as LX200Simulator
        simulator.refusalCode = '6'
        assertEquals(MountError.OutsideMountLimits, assertFailsWith<MountError> { rig.controller.sync(andromeda) })
        simulator.refusalCode = null
        rig.mount.isParked = true
        assertEquals(MountError.Parked, assertFailsWith<MountError> { rig.controller.sync(andromeda) })
        assertEquals(0, rig.mount.syncsPerformed)
    }

    @Test
    fun testAnUnalignedHandControlRefusesToSlew() {
        for (name in listOf("nexstar-celestron", "nexstar-synscan")) {
            val rig = MountTestSupport.rig(name)
            rig.mount.isAligned = false
            val capabilities = rig.controller.connect()
            assertEquals(false, capabilities.isAligned, name)
            assertEquals(MountError.NotAligned, assertFailsWith<MountError> { rig.controller.goto(andromeda) }, name)
            assertEquals(0, rig.mount.slewsStarted, name)
            assertTrue(rig.controller.status.lastError?.userMessage?.contains("star alignment") == true, name)
            // Aligning the mount later is picked up without reconnecting.
            rig.mount.isAligned = true
            rig.controller.goto(andromeda)
            assertEquals(MountPhase.SLEWING, rig.controller.status.phase, name)
        }
    }

    @Test
    fun testAlpacaErrorNumbersBecomeTypedErrors() {
        val cases = listOf(
            Triple(1035, "Target is below the horizon limit", MountError.MountHorizonLimit),
            Triple(1035, "The telescope is not aligned", MountError.NotAligned),
            Triple(1032, "", MountError.Parked),
            Triple(1280, "Slewing is in progress", MountError.Busy),
            Triple(1281, "Meridian limit reached", MountError.OutsideMountLimits),
        )
        for ((number, message, expected) in cases) {
            val rig = MountTestSupport.rig("alpaca-topocentric")
            rig.controller.connect()
            rig.alpacaSimulator?.inject("slewtocoordinatesasync", AlpacaFault.Error(number, message))
            assertEquals(expected, assertFailsWith<MountError> { rig.controller.goto(andromeda) }, "$number $message")
            assertEquals(0, rig.mount.slewsStarted)
            assertEquals(MountPhase.IDLE, rig.controller.status.phase)
        }
        val unknown = MountTestSupport.rig("alpaca-topocentric")
        unknown.controller.connect()
        unknown.alpacaSimulator?.inject("slewtocoordinatesasync", AlpacaFault.Error(1290, "Axis 2 motor stalled"))
        val error = assertIs<MountError.Remote>(assertFailsWith<MountError> { unknown.controller.goto(andromeda) })
        assertEquals(1290, error.code)
        assertEquals("Axis 2 motor stalled", error.detail)
    }

    @Test
    fun testAlpacaMountsThatAreParkedOrNotTrackingSayWhy() {
        val parked = MountTestSupport.rig("alpaca-j2000")
        parked.controller.connect()
        parked.mount.isParked = true
        assertEquals(MountError.Parked, assertFailsWith<MountError> { parked.controller.goto(andromeda) })
        val notTracking = MountTestSupport.rig("alpaca-j2000")
        notTracking.controller.connect()
        notTracking.controller.setTracking(false)
        assertEquals(MountError.NotTracking, assertFailsWith<MountError> { notTracking.controller.goto(andromeda) })
        notTracking.controller.setTracking(true)
        notTracking.controller.goto(andromeda)
        assertEquals(MountPhase.SLEWING, notTracking.controller.status.phase)
    }

    // MARK: Garbled and partial replies

    @Test
    fun testAGarbledReplyIsReportedAndTheNextPollRecovers() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            when {
                name.startsWith("lx200") -> rig.streamSimulator?.inject("GR", MountSimulatedFault.Replace("12:7x:00#"))
                name.startsWith("nexstar") -> rig.streamSimulator?.inject("e", MountSimulatedFault.Replace("34AB0500;12CE0500#"))
                else -> rig.alpacaSimulator?.inject("rightascension", AlpacaFault.Body("{\"Value\": 12.5,"))
            }
            val status = rig.controller.poll()
            assertIs<MountError.MalformedReply>(status.lastError, name)
            assertEquals(MountPhase.IDLE, status.phase, name)
            assertNull(rig.controller.poll().lastError, name)
        }
    }

    @Test
    fun testATruncatedReplyTimesOutAndTheNextPollRecovers() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            when {
                name.startsWith("lx200") -> rig.streamSimulator?.inject("GR", MountSimulatedFault.Truncate(4))
                name.startsWith("nexstar") -> rig.streamSimulator?.inject("e", MountSimulatedFault.Truncate(10))
                else -> rig.alpacaSimulator?.inject("rightascension", AlpacaFault.Body("{\"Value\": 12.5, \"ClientTr"))
            }
            val status = rig.controller.poll()
            val error = assertNotNull(status.lastError, name)
            // The stream is cut short and the mount stays silent, so the read runs out of time; the JSON is just invalid.
            assertTrue(error is MountError.Timeout || error is MountError.MalformedReply, "$name: $error")
            assertNull(rig.controller.poll().lastError, name)
        }
    }

    @Test
    fun testRepliesInSingleByteChunksStillWork() {
        for (name in rigNames) {
            if (!name.startsWith("lx200") && !name.startsWith("nexstar")) continue
            val rig = MountTestSupport.rig(name, chunkSize = 1)
            rig.controller.connect()
            rig.controller.goto(andromeda)
            val status = rig.controller.awaitSettled()
            assertIs<MountSlewOutcome.Arrived>(status.outcome, name)
            assertTrue(rig.controller.poll().lastError == null, name)
        }
    }

    @Test
    fun testALateReplyOfAnEarlierCommandIsDiscarded() {
        val rig = MountTestSupport.rig("lx200-onstep")
        rig.controller.connect()
        rig.controller.poll()
        // A stray reply that arrived after its command timed out must not be read as the next answer.
        val simulator = rig.streamSimulator as LX200Simulator
        simulator.send(MountText.ascii(":GVP#"))
        val status = rig.controller.poll()
        assertNull(status.lastError)
        assertEquals(0.0, assertNotNull(status.position).angularSeparation(rig.mountJ2000()), 0.01)
    }

    @Test
    fun testBusyControllersAreRetriedAndGiveUpEventually() {
        val rig = MountTestSupport.rig("lx200-onstep")
        val simulator = rig.streamSimulator as LX200Simulator
        simulator.busyReplies = 2
        rig.controller.connect()
        assertEquals(MountPhase.IDLE, rig.controller.status.phase)
        simulator.busyReplies = 4
        val status = rig.controller.poll()
        assertEquals(MountError.Busy, status.lastError)
        assertNull(rig.controller.poll().lastError)
    }

    @Test
    fun testADroppedConnectionIsReportedAsATransportError() {
        for (name in rigNames) {
            if (name.startsWith("alpaca")) continue
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            rig.streamSimulator?.inject(if (name.startsWith("lx200")) "GR" else "e", MountSimulatedFault.Disconnect)
            val status = rig.controller.poll()
            assertIs<MountError.Transport>(status.lastError, name)
            rig.controller.poll()
            assertEquals(MountPhase.DISCONNECTED, rig.controller.poll().phase, name)
        }
    }

    // MARK: Failures around commands

    @Test
    fun testAGotoThatCannotBeConfirmedTriesToStopTheMount() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            when {
                name.startsWith("lx200") -> rig.streamSimulator?.inject("MS", MountSimulatedFault.Silence)
                name.startsWith("nexstar") -> rig.streamSimulator?.inject("r", MountSimulatedFault.Silence)
                else -> rig.alpacaSimulator?.inject("slewtocoordinatesasync", AlpacaFault.Timeout)
            }
            val error = assertFailsWith<MountError>(name) { rig.controller.goto(andromeda) }
            assertIs<MountError.Timeout>(error, name)
            assertEquals(MountPhase.IDLE, rig.controller.status.phase, name)
            assertTrue(rig.mount.stopsReceived >= 1, name)
        }
    }

    @Test
    fun testAbortIsRepeatedWhenTheLinkFails() {
        for (name in listOf("nexstar-celestron", "alpaca-topocentric")) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            rig.controller.goto(andromeda)
            if (name.startsWith("nexstar")) {
                rig.streamSimulator?.inject("M", MountSimulatedFault.Silence, 2)
            } else {
                rig.alpacaSimulator?.inject("abortslew", AlpacaFault.Timeout, 2)
            }
            rig.controller.abort()
            assertEquals(MountPhase.IDLE, rig.controller.status.phase, name)
            assertFalse(rig.mount.isSlewing, name)

            val stubborn = MountTestSupport.rig(name)
            stubborn.controller.connect()
            stubborn.controller.goto(andromeda)
            if (name.startsWith("nexstar")) {
                stubborn.streamSimulator?.inject("M", MountSimulatedFault.Silence, 3)
            } else {
                stubborn.alpacaSimulator?.inject("abortslew", AlpacaFault.Timeout, 3)
            }
            assertIs<MountError.Timeout>(assertFailsWith<MountError>(name) { stubborn.controller.abort() })
            // Still slewing as far as the controller knows; the abort can be repeated.
            assertEquals(MountPhase.SLEWING, stubborn.controller.status.phase, name)
            stubborn.controller.abort()
            assertEquals(MountPhase.IDLE, stubborn.controller.status.phase, name)
            assertFalse(stubborn.mount.isSlewing, name)
        }
    }

    @Test
    fun testAConnectionThatFailsLeavesTheControllerDisconnected() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            when {
                name.startsWith("lx200") -> rig.streamSimulator?.inject("GR", MountSimulatedFault.Silence, 5)
                name.startsWith("nexstar") -> rig.streamSimulator?.inject("V", MountSimulatedFault.Silence, 5)
                else -> rig.alpacaSimulator?.inject("connected", AlpacaFault.Timeout, 5)
            }
            assertIs<MountError.Timeout>(assertFailsWith<MountError>(name) { rig.controller.connect() })
            assertEquals(MountPhase.DISCONNECTED, rig.controller.status.phase, name)
            assertNull(rig.controller.capabilities, name)
        }
    }
}
