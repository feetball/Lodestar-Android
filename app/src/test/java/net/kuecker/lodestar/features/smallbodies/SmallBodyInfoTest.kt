package net.kuecker.lodestar.features.smallbodies

import net.kuecker.lodestar.features.smallbodies.SmallBodyFixtures as Fixtures
import net.kuecker.lodestar.kit.comets.SmallBodyEphemeris
import net.kuecker.lodestar.kit.comets.state
import net.kuecker.lodestar.kit.foundation.SkyFrame
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmallBodyInfoTest {
    private val frame = SkyFrame(Fixtures.date, Fixtures.observer)

    @Test
    fun theInfoCarriesTheEngineState() {
        for (body in Fixtures.comets + Fixtures.asteroids) {
            val state = assertNotNull(frame.state(body), body.id)
            val info = assertNotNull(SmallBodyInfo.compute(body, frame), body.id)
            assertEquals(state.distanceAU, info.earthDistanceAU, body.id)
            assertEquals(state.heliocentricDistanceAU, info.sunDistanceAU, body.id)
            assertEquals(state.elongation, info.elongation, body.id)
            assertEquals(state.phaseAngle, info.phaseAngle, body.id)
            assertEquals(state.apparentMagnitude, info.magnitude, body.id)
            assertEquals(state.isPhotographable(), info.isPhotographable, body.id)
            // The observer does not matter: the date alone gives the same numbers.
            assertEquals(info, SmallBodyInfo.compute(body, Fixtures.date), body.id)
        }
    }

    @Test
    fun anOrbitThatCannotBeEvaluatedHasNoInfo() {
        assertNull(SmallBodyInfo.compute(Fixtures.longGone, frame))
        assertNull(SmallBodyDetail.compute(Fixtures.longGone, frame))
    }

    @Test
    fun twilightGlowBelowTwentyFiveDegreesFromTheSun() {
        val info = assertNotNull(SmallBodyInfo.compute(Fixtures.vesta, frame))
        assertTrue(info.copy(elongation = 24.9).isInTwilightGlow)
        assertFalse(info.copy(elongation = 25.0).isInTwilightGlow)
        assertTrue(SmallBodyWarning.TWILIGHT_GLOW in info.copy(elongation = 10.0).warnings)
        assertFalse(SmallBodyWarning.TWILIGHT_GLOW in info.copy(elongation = 90.0).warnings)
    }

    @Test
    fun cometElementsOlderThanSixtyDaysAreApproximate() {
        val info = assertNotNull(SmallBodyInfo.compute(Fixtures.tsuchinshanAtlas, frame))
        assertTrue(info.elementsAgeDays < 2.0, "the file's elements are of the day before: ${info.elementsAgeDays}")
        assertFalse(info.hasApproximatePosition)
        assertFalse(info.copy(elementsAgeDays = 60.0).hasApproximatePosition)
        assertTrue(info.copy(elementsAgeDays = 60.1).hasApproximatePosition)
        assertTrue(SmallBodyWarning.POSITION_APPROXIMATE in info.copy(elementsAgeDays = 61.0).warnings)
        // In real time: elements of 2026-10-17 are 76 days old on 2027-01-01.
        val later = assertNotNull(SmallBodyInfo.compute(Fixtures.tsuchinshanAtlas, Instant.parse("2027-01-01T00:00:00Z")))
        assertTrue(later.hasApproximatePosition, "${later.elementsAgeDays}")
    }

    @Test
    fun asteroidElementsOlderThanAYearAreApproximate() {
        val info = assertNotNull(SmallBodyInfo.compute(Fixtures.vesta, frame))
        // The bundled asteroids' elements are of 2026-06-09: 131 days before the date.
        assertTrue(info.elementsAgeDays in 130.0..132.0, "${info.elementsAgeDays}")
        assertFalse(info.hasApproximatePosition, "a comet's limit of 60 days does not apply")
        assertFalse(info.copy(elementsAgeDays = 365.0).hasApproximatePosition)
        assertTrue(info.copy(elementsAgeDays = 365.1).hasApproximatePosition)
        val next = assertNotNull(SmallBodyInfo.compute(Fixtures.vesta, Instant.parse("2027-07-01T00:00:00Z")))
        assertTrue(next.hasApproximatePosition)
    }

    @Test
    fun aBodyWithoutMagnitudeParametersSaysSoAndIsNotPhotographable() {
        val info = assertNotNull(SmallBodyInfo.compute(Fixtures.withoutMagnitude, frame))
        assertNull(info.magnitude)
        assertTrue(info.hasNoBrightnessData)
        assertFalse(info.isPhotographable)
        assertTrue(SmallBodyWarning.NO_BRIGHTNESS_DATA in info.warnings)
    }

    @Test
    fun warningsComeMostImportantFirst() {
        val info = assertNotNull(SmallBodyInfo.compute(Fixtures.withoutMagnitude, frame))
        val all = info.copy(elongation = 10.0, elementsAgeDays = 400.0).warnings
        assertEquals(
            listOf(SmallBodyWarning.TWILIGHT_GLOW, SmallBodyWarning.POSITION_APPROXIMATE, SmallBodyWarning.NO_BRIGHTNESS_DATA),
            all,
        )
        assertTrue(info.copy(elongation = 90.0, elementsAgeDays = 1.0, magnitude = 7.0).warnings.isEmpty())
    }

    @Test
    fun theTailIsForCometsOnly() {
        val comet = assertNotNull(SmallBodyDetail.compute(Fixtures.tsuchinshanAtlas, frame))
        val tail = assertNotNull(comet.tail)
        val state = assertNotNull(frame.state(Fixtures.tsuchinshanAtlas))
        assertEquals(assertNotNull(SmallBodyEphemeris.tailAngleFromVertical(state, frame)), tail.angleFromVertical)
        assertEquals(state.phaseAngle, tail.phaseAngle)
        assertTrue(tail.angleFromVertical > -180.0 && tail.angleFromVertical <= 180.0)
        assertNull(assertNotNull(SmallBodyDetail.compute(Fixtures.vesta, frame)).tail)
    }

    @Test
    fun theCameraTiltLaysTheTailAlongASideOfTheFrame() {
        fun tilt(angle: Double) = SmallBodyTail(angle, 90.0).cameraTilt
        assertEquals(0.0, tilt(0.0))
        assertEquals(35.0, tilt(35.0))
        assertEquals(-35.0, tilt(-35.0))
        // Past 45° it is nearer to turn the tail to the horizontal.
        assertEquals(-40.0, tilt(50.0))
        assertEquals(40.0, tilt(-50.0))
        assertEquals(0.0, tilt(90.0))
        assertEquals(0.0, tilt(180.0))
        // A tail pointing a little past straight down.
        assertEquals(10.0, tilt(-170.0))
        assertEquals(-10.0, tilt(170.0))
        for (angle in -179..180) assertTrue(tilt(angle.toDouble()) in -45.0..45.0, "$angle")
    }

    @Test
    fun aTailSeenEndOnIsForeshortened() {
        assertTrue(SmallBodyTail(10.0, 5.0).isForeshortened)
        assertTrue(SmallBodyTail(10.0, 175.0).isForeshortened)
        assertFalse(SmallBodyTail(10.0, 90.0).isForeshortened)
        assertFalse(SmallBodyTail(10.0, 15.0).isForeshortened)
    }
}
