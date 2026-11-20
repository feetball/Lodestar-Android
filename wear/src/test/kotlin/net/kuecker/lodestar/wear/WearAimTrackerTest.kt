package net.kuecker.lodestar.wear

import net.kuecker.lodestar.kit.pointing.GuidanceState
import net.kuecker.lodestar.kit.wrist.AimWristMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WearAimTrackerTest {
    private fun message(sent: Double, onTarget: Boolean = false, below: Boolean = false) = AimWristMessage(
        sent = sent, pan = if (onTarget) 0.0 else 12.0, tilt = if (onTarget) 0.0 else 3.0,
        separation = if (onTarget) 0.1 else 12.4,
        state = if (onTarget) GuidanceState.ON_TARGET else GuidanceState.FAR,
        isBelowHorizon = below, isNearZenith = false, isNight = false, targetName = "M31",
    )

    @Test
    fun theWatchWaitsUntilTheFirstMessage() {
        val tracker = WearAimTracker()
        assertNull(tracker.latest)
        assertNull(tracker.current(1_000))
    }

    @Test
    fun aMessageIsShownForThreeSeconds() {
        val tracker = WearAimTracker()
        tracker.receive(message(1.0), now = 10_000)
        assertEquals(1.0, tracker.current(10_000)?.sent)
        assertEquals(1.0, tracker.current(12_999)?.sent)
        assertEquals(1.0, tracker.current(13_000)?.sent)
        assertNull(tracker.current(13_001))
    }

    @Test
    fun aMessageNoNewerThanTheNewestIsDropped() {
        val tracker = WearAimTracker()
        tracker.receive(message(5.0), now = 1_000)
        tracker.receive(message(4.0), now = 1_100)
        tracker.receive(message(5.0), now = 1_200)
        assertEquals(5.0, tracker.latest?.message?.sent)
        assertEquals(1_000, tracker.latest?.at)
        tracker.receive(message(6.0), now = 1_300)
        assertEquals(6.0, tracker.latest?.message?.sent)
    }

    @Test
    fun reachingTheTargetIsReportedOnce() {
        val tracker = WearAimTracker()
        assertFalse(tracker.receive(message(1.0), now = 0))
        assertTrue(tracker.receive(message(2.0, onTarget = true), now = 500))
        assertFalse(tracker.receive(message(3.0, onTarget = true), now = 1_000))
        assertFalse(tracker.receive(message(4.0), now = 1_500))
        assertTrue(tracker.receive(message(5.0, onTarget = true), now = 2_000))
    }

    @Test
    fun beingOnTargetAfterAPauseCountsAsReachingIt() {
        val tracker = WearAimTracker()
        tracker.receive(message(1.0, onTarget = true), now = 0)
        assertTrue(tracker.receive(message(2.0, onTarget = true), now = 10_000))
    }

    @Test
    fun belowTheHorizonIsNeverOnTarget() {
        val tracker = WearAimTracker()
        assertFalse(tracker.receive(message(1.0, onTarget = true, below = true), now = 0))
    }
}
