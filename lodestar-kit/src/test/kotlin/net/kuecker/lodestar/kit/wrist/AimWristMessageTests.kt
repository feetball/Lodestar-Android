package net.kuecker.lodestar.kit.wrist

import net.kuecker.lodestar.kit.pointing.GuidanceState
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AimWristMessageTests {
    private fun message(
        pan: Double = 12.0, tilt: Double = 3.0, separation: Double = 12.4, state: GuidanceState = GuidanceState.FAR,
        below: Boolean = false, zenith: Boolean = false, night: Boolean = false, target: String = "Orion Nebula",
    ) = AimWristMessage(1_700_000_000.5, pan, tilt, separation, state, below, zenith, night, target)

    private fun decoded(message: AimWristMessage): AimWristMessage = assertNotNull(AimWristMessage.decode(message.encode()))

    private fun raw(text: String): AimWristMessage? = AimWristMessage.decode(text.toByteArray(Charsets.UTF_8))

    // Encoding

    @Test
    fun aMessageSurvivesTheRoundTrip() {
        val sent = message(night = true, zenith = true)
        assertEquals(sent, decoded(sent))
        val m = decoded(message(below = true, state = GuidanceState.ON_TARGET))
        assertTrue(m.isBelowHorizon)
        assertFalse(m.isNight)
        assertEquals(GuidanceState.ON_TARGET, m.state)
    }

    @Test
    fun theEncodingIsOneShortLine() {
        val text = String(message().encode(), Charsets.UTF_8)
        assertEquals("LA1|1.7000000005E9|12.0|3.0|12.4|far|0|0|0|Orion Nebula", text)
        assertTrue(message().encode().size < 100)
    }

    @Test
    fun aTargetNameMayHoldTheSeparator() {
        assertEquals("M31 | Andromeda", decoded(message(target = "M31 | Andromeda")).targetName)
    }

    @Test
    fun aLongTargetNameIsCut() {
        val long = "x".repeat(500)
        assertEquals(AimWristMessage.MAXIMUM_NAME_LENGTH, decoded(message(target = long)).targetName.length)
        assertEquals(AimWristMessage.MAXIMUM_NAME_LENGTH, raw("LA1|1.0|1.0|1.0|5.0|far|0|0|0|$long")?.targetName?.length)
    }

    // Decoding

    @Test
    fun messagesThatAreNotAimMessagesAreDropped() {
        assertNull(raw(""))
        assertNull(raw("hello"))
        assertNull(raw("LA2|1.0|1.0|1.0|5.0|far|0|0|0|x"))
        assertNull(raw("LA1|1.0|1.0|1.0|5.0|far|0|0|0"))
        assertNotNull(raw("LA1|1.0|1.0|1.0|5.0|far|0|0|0|"))
    }

    @Test
    fun valuesOutOfRangeOrNotFiniteAreDropped() {
        assertNull(raw("LA1|1.0|NaN|1.0|5.0|far|0|0|0|x"))
        assertNull(raw("LA1|1.0|1.0|Infinity|5.0|far|0|0|0|x"))
        assertNull(raw("LA1|NaN|1.0|1.0|5.0|far|0|0|0|x"))
        assertNull(raw("LA1|1.0|181.0|1.0|5.0|far|0|0|0|x"))
        assertNull(raw("LA1|1.0|1.0|-180.5|5.0|far|0|0|0|x"))
        assertNull(raw("LA1|1.0|1.0|1.0|-1.0|far|0|0|0|x"))
        assertNull(raw("LA1|1.0|1.0|1.0|181.0|far|0|0|0|x"))
        assertNull(raw("LA1|1.0|1.0|1.0|5.0|far away|0|0|0|x"))
        assertNull(raw("LA1|one|1.0|1.0|5.0|far|0|0|0|x"))
    }

    @Test
    fun theStatesAreTheEnginesRawValues() {
        for (state in GuidanceState.entries) {
            assertEquals(state, raw("LA1|1.0|1.0|1.0|5.0|${state.rawValue}|0|0|0|x")?.state)
        }
    }

    // The arrow

    @Test
    fun theArrowPointsAlongThePanAndTiltAxes() {
        assertEquals(0.0, message(pan = 10.0, tilt = 0.0).arrowAngle, 1e-12)
        assertEquals(PI / 2, message(pan = 0.0, tilt = 10.0).arrowAngle, 1e-12)
        assertEquals(PI, message(pan = -10.0, tilt = 0.0).arrowAngle, 1e-12)
        assertEquals(-PI / 2, message(pan = 0.0, tilt = -10.0).arrowAngle, 1e-12)
        assertEquals(PI / 4, message(pan = 10.0, tilt = 10.0).arrowAngle, 1e-12)
    }

    @Test
    fun nearTheZenithTheArrowFollowsTheTiltOnly() {
        assertEquals(PI / 2, message(pan = 90.0, tilt = 3.0, zenith = true).arrowAngle, 1e-12)
        assertEquals(-PI / 2, message(pan = 90.0, tilt = -3.0, zenith = true).arrowAngle, 1e-12)
        // With no tilt to make, the pan is all there is.
        assertEquals(atan2(0.2, 90.0), message(pan = 90.0, tilt = 0.2, zenith = true).arrowAngle, 1e-12)
    }

    // Words

    @Test
    fun theMovesAreInWholeDegrees() {
        assertEquals(listOf("Pan right 12°", "Tilt up 3°"), message(pan = 12.4, tilt = 2.6).moves)
        assertEquals(listOf("Pan left 13°", "Tilt down 3°"), message(pan = -12.5, tilt = -2.5).moves)
        assertEquals(listOf("Pan right 12°"), message(pan = 12.2, tilt = 0.4).moves)
        assertEquals(listOf("Tilt up 6°"), message(pan = 0.3, tilt = 6.0).moves)
    }

    @Test
    fun nearTheZenithOnlyTheTiltIsListed() {
        assertEquals(listOf("Tilt down 3°"), message(pan = 30.0, tilt = -3.0, zenith = true).moves)
        assertEquals(
            listOf("Pan right 80°"), message(pan = 80.0, tilt = 0.2, separation = 2.5, zenith = true).moves,
        )
    }

    @Test
    fun closeToTheTargetItIsAlmostThere() {
        assertEquals(
            listOf("Almost there"), message(pan = 1.0, tilt = 0.5, separation = 1.1, state = GuidanceState.CLOSE).moves,
        )
        assertEquals(
            listOf("Almost there"), message(pan = 0.2, tilt = 0.1, separation = 2.5, state = GuidanceState.CLOSE).moves,
        )
    }

    @Test
    fun theDistanceHasADecimalOnlyBelowTenDegrees() {
        assertEquals("26° away", message(separation = 26.4).distanceText)
        assertEquals("10.0° away", message(separation = 9.96).distanceText)
        assertEquals("3.2° away", message(separation = 3.2).distanceText)
    }

    @Test
    fun onTargetIsNotClaimedBelowTheHorizon() {
        val on = message(pan = 0.0, tilt = 0.0, separation = 0.1, state = GuidanceState.ON_TARGET)
        assertTrue(on.isOnTarget)
        assertEquals("On target, Orion Nebula", on.spokenSummary)
        val below = on.copy(isBelowHorizon = true)
        assertFalse(below.isOnTarget)
        assertEquals("Target is below the horizon", below.spokenSummary)
    }

    @Test
    fun theSpokenSummaryNamesMovesDistanceAndTarget() {
        assertEquals("Pan right 12°, Tilt up 3°, 12° away, Orion Nebula", message().spokenSummary)
        assertEquals("Pan right 12°, Tilt up 3°, 12° away", message(target = "").spokenSummary)
    }
}
