package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.PointingTestSupport as Support
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PointingGuidanceSpeechTests {
    private val start: Instant = Instant.ofEpochSecond(1_700_000_000)

    /**
     * The guidance for a camera at `boresight` (azimuth 100°, altitude 20° unless given) and a target `pan` degrees of
     * azimuth and `tilt` degrees of altitude away from it.
     */
    private fun guidance(
        pan: Double,
        tilt: Double,
        boresightAltitude: Double = 20.0,
        threshold: Double = 0.5,
    ): Guidance {
        val boresight = HorizontalCoordinate(100.0, boresightAltitude)
        val mount = MountProfile.backCameraPortrait
        val frame = Support.cameraFrame(boresight, 0.0)
        val solution = PointingSolution.solve(Support.attitude(mount = mount, frame = frame), mount)
        val target = HorizontalCoordinate(AstroMath.normalizeDegrees(100.0 + pan), boresightAltitude + tilt)
        return assertNotNull(GuidanceTracker(onTargetThreshold = threshold).update(solution, target))
    }

    private fun speak(
        guidance: Guidance,
        previous: SpokenState? = null,
        at: Instant = start,
    ): SpokenPhrase? = GuidanceSpeech.phrase(guidance, previous, at)

    private fun after(seconds: Double): Instant = start.addingTimeInterval(seconds)

    // MARK: Wording

    @Test
    fun testTheFirstPhraseNamesTheUnitOnce() {
        val first = assertNotNull(speak(guidance(pan = 12.0, tilt = 3.0)))
        assertEquals("Pan right 12, tilt up 3 degrees", first.text)
        assertEquals(SpokenKind.GUIDANCE, first.state.kind)
        assertTrue(first.state.hasSaidDegrees)
        assertEquals(12.0, first.state.pan, 1e-12)
        assertEquals(3.0, first.state.tilt, 1e-12)
        assertEquals(start, first.state.time)

        val later = assertNotNull(speak(guidance(pan = -8.4, tilt = -2.6), first.state, after(6.0)))
        assertEquals("Pan left 8, tilt down 3", later.text)
        assertTrue(later.state.hasSaidDegrees)
    }

    @Test
    fun testOneDegreeIsSingular() {
        assertEquals("Pan right 12, tilt up 1 degree", speak(guidance(pan = 12.0, tilt = 1.2))?.text)
        assertEquals("Pan left 12, tilt down 1 degree", speak(guidance(pan = -12.0, tilt = -1.0))?.text)
    }

    @Test
    fun testAComponentThatRoundsToZeroIsLeftOut() {
        assertEquals("Pan right 12 degrees", speak(guidance(pan = 12.2, tilt = 0.4))?.text)
        assertEquals("Tilt up 6 degrees", speak(guidance(pan = 0.3, tilt = 6.0))?.text)
        assertEquals("Pan left 6 degrees", speak(guidance(pan = -6.0, tilt = -0.49))?.text)
    }

    @Test
    fun testDegreesRoundHalfAwayFromZero() {
        assertEquals("Pan right 13, tilt down 3 degrees", speak(guidance(pan = 12.5, tilt = -2.5))?.text)
        assertEquals("Pan left 13, tilt up 3 degrees", speak(guidance(pan = -12.5, tilt = 2.5))?.text)
        assertEquals("Pan right 12, tilt up 2 degrees", speak(guidance(pan = 12.49, tilt = 2.4))?.text)
    }

    @Test
    fun testNearTheZenithOnlyTheTiltIsSpoken() {
        val g = guidance(pan = 30.0, tilt = -3.0, boresightAltitude = 88.0)
        assertTrue(g.isNearZenith)
        assertEquals("Tilt down 3 degrees", speak(g)?.text)
        // Without a tilt to make, the pan is all there is to say.
        val level = guidance(pan = 80.0, tilt = 0.2, boresightAltitude = 88.0)
        assertTrue(level.isNearZenith)
        assertEquals("Pan right 80 degrees", speak(level)?.text)
    }

    // MARK: Almost there, on target, below the horizon

    @Test
    fun testAlmostThereUnderTwoDegrees() {
        val g = guidance(pan = 1.0, tilt = 0.5)
        assertTrue(g.separation < GuidanceSpeech.almostThereSeparation)
        assertEquals(GuidanceState.CLOSE, g.state)
        val phrase = assertNotNull(speak(g))
        assertEquals("Almost there", phrase.text)
        assertEquals(SpokenKind.ALMOST_THERE, phrase.state.kind)
        assertFalse(phrase.state.hasSaidDegrees)
    }

    @Test
    fun testAlmostThereIsRepeatedOnlyAfterFiveSeconds() {
        val g = guidance(pan = 1.0, tilt = 0.5)
        val first = assertNotNull(speak(g))
        assertNull(speak(g, first.state, after(4.9)))
        assertEquals("Almost there", speak(g, first.state, after(5.0))?.text)
    }

    @Test
    fun testAlmostThereIsLeftOnlyBeyondThreeDegrees() {
        val almost = assertNotNull(speak(guidance(pan = 1.0, tilt = 0.5)))
        // 2.5° is beyond the limit for entering "Almost there" but within the limit for leaving it.
        assertEquals("Tilt up 3 degrees", speak(guidance(pan = 0.0, tilt = 2.5))?.text)
        assertNull(speak(guidance(pan = 0.0, tilt = 2.5), almost.state, after(1.0)))
        assertEquals("Tilt up 4 degrees", speak(guidance(pan = 0.0, tilt = 3.5), almost.state, after(1.0))?.text)
    }

    @Test
    fun testOnTargetIsSaidOnceThenSilence() {
        val g = guidance(pan = 0.0, tilt = 0.0)
        assertEquals(GuidanceState.ON_TARGET, g.state)
        val phrase = assertNotNull(speak(g))
        assertEquals("On target", phrase.text)
        assertEquals(SpokenKind.ON_TARGET, phrase.state.kind)
        assertNull(speak(g, phrase.state, after(1.0)))
        assertNull(speak(g, phrase.state, after(600.0)))
    }

    @Test
    fun testDriftingOffTargetIsSpokenAtOnce() {
        val onTarget = assertNotNull(speak(guidance(pan = 0.0, tilt = 0.0)))
        val drifted = assertNotNull(speak(guidance(pan = 5.0, tilt = 0.0), onTarget.state, after(0.1)))
        assertEquals("Pan right 5 degrees", drifted.text)
        // Then it is on target again, which is worth saying again.
        val again = assertNotNull(speak(guidance(pan = 0.0, tilt = 0.0), drifted.state, after(2.0)))
        assertEquals("On target", again.text)
    }

    @Test
    fun testBelowTheHorizonIsSaidOnceAndNothingElse() {
        val g = guidance(pan = 20.0, tilt = -25.0)
        assertTrue(g.targetBelowHorizon)
        val phrase = assertNotNull(speak(g))
        assertEquals("Target is below the horizon", phrase.text)
        assertEquals(SpokenKind.BELOW_HORIZON, phrase.state.kind)
        assertNull(speak(g, phrase.state, after(1.0)))
        assertNull(speak(g, phrase.state, after(600.0)))
        // Once it has risen, the guidance is spoken at once.
        assertEquals("Pan right 20, tilt down 10 degrees", speak(guidance(pan = 20.0, tilt = -10.0), phrase.state, after(601.0))?.text)
    }

    @Test
    fun testBelowTheHorizonComesBeforeOnTarget() {
        val boresight = guidance(pan = 0.0, tilt = 0.0, boresightAltitude = -5.0)
        assertEquals(GuidanceState.ON_TARGET, boresight.state)
        assertEquals("Target is below the horizon", speak(boresight)?.text)
    }

    // MARK: Repeating

    @Test
    fun testSmallChangesAreNotRepeatedBeforeFiveSeconds() {
        val first = assertNotNull(speak(guidance(pan = 12.0, tilt = 3.0)))
        assertNull(speak(guidance(pan = 12.0, tilt = 3.0), first.state, after(1.0)))
        assertNull(speak(guidance(pan = 12.9, tilt = 3.5), first.state, after(2.0)))
        assertNull(speak(guidance(pan = 11.1, tilt = 2.2), first.state, after(4.9)))
    }

    @Test
    fun testAChangeOfOneDegreeIsRepeated() {
        val first = assertNotNull(speak(guidance(pan = 12.0, tilt = 3.0)))
        assertEquals("Pan right 13, tilt up 3", speak(guidance(pan = 13.0, tilt = 3.0), first.state, after(1.0))?.text)
        assertEquals("Pan right 12, tilt up 4", speak(guidance(pan = 12.0, tilt = 4.0), first.state, after(1.0))?.text)
        assertEquals("Pan right 11, tilt up 3", speak(guidance(pan = 11.0, tilt = 3.0), first.state, after(1.0))?.text)
    }

    @Test
    fun testChangesAreCountedFromTheLastPhraseSpoken() {
        val first = assertNotNull(speak(guidance(pan = 12.0, tilt = 3.0)))
        // Drifting by 0.6° twice is a change of 1.2° from what was said.
        assertNull(speak(guidance(pan = 12.6, tilt = 3.0), first.state, after(1.0)))
        assertNotNull(speak(guidance(pan = 13.2, tilt = 3.0), first.state, after(2.0)))
    }

    @Test
    fun testUnchangedGuidanceIsRepeatedAfterFiveSeconds() {
        val first = assertNotNull(speak(guidance(pan = 12.0, tilt = 3.0)))
        assertNull(speak(guidance(pan = 12.0, tilt = 3.0), first.state, after(4.9)))
        val repeated = assertNotNull(speak(guidance(pan = 12.0, tilt = 3.0), first.state, after(5.0)))
        assertEquals("Pan right 12, tilt up 3", repeated.text)
        assertEquals(after(5.0), repeated.state.time)
    }

    @Test
    fun testThePanChangeTakesTheShortWayRoundTheCircle() {
        // 179.5° and −179.8° are 0.7° apart.
        val first = assertNotNull(speak(guidance(pan = 179.5, tilt = 0.0)))
        assertEquals("Pan right 180 degrees", first.text)
        assertNull(speak(guidance(pan = 180.2, tilt = 0.0), first.state, after(1.0)))
        assertEquals("Pan left 179", speak(guidance(pan = 181.0, tilt = 0.0), first.state, after(1.0))?.text)
    }

    @Test
    fun testAClockThatWentBackwardsRepeats() {
        val first = assertNotNull(speak(guidance(pan = 12.0, tilt = 3.0), at = after(100.0)))
        assertNotNull(speak(guidance(pan = 12.0, tilt = 3.0), first.state, start))
    }

    @Test
    fun testChangesOfPanAreIgnoredWhereOnlyTheTiltIsSpoken() {
        val zenith = guidance(pan = 30.0, tilt = -3.0, boresightAltitude = 88.0)
        val first = assertNotNull(speak(zenith))
        val swung = guidance(pan = 45.0, tilt = -3.0, boresightAltitude = 88.0)
        assertNull(speak(swung, first.state, after(1.0)))
    }

    // MARK: A session

    @Test
    fun testASessionFromFarToOnTarget() {
        val steps = listOf(
            Triple(guidance(pan = 40.0, tilt = 15.0), 0.0, "Pan right 40, tilt up 15 degrees"),
            Triple(guidance(pan = 39.5, tilt = 14.8), 0.5, null),
            Triple(guidance(pan = 25.0, tilt = 9.0), 1.0, "Pan right 25, tilt up 9"),
            Triple(guidance(pan = 24.7, tilt = 8.9), 1.5, null),
            Triple(guidance(pan = 6.0, tilt = 2.0), 3.0, "Pan right 6, tilt up 2"),
            Triple(guidance(pan = 1.2, tilt = 0.4), 4.0, "Almost there"),
            Triple(guidance(pan = 1.0, tilt = 0.3), 6.0, null),
            Triple(guidance(pan = 0.0, tilt = 0.0), 7.0, "On target"),
            Triple(guidance(pan = 0.0, tilt = 0.0), 9.0, null),
            Triple(guidance(pan = 0.2, tilt = -0.1), 20.0, null),
            Triple(guidance(pan = -5.0, tilt = 1.0), 21.0, "Pan left 5, tilt up 1"),
            Triple(guidance(pan = -0.2, tilt = 0.0), 23.0, "On target"),
        )
        var state: SpokenState? = null
        for ((g, seconds, expected) in steps) {
            val phrase = speak(g, state, after(seconds))
            assertEquals(expected, phrase?.text, "at $seconds s")
            if (phrase != null) state = phrase.state
        }
    }
}
