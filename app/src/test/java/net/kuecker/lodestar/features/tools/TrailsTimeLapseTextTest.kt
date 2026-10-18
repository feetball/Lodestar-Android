package net.kuecker.lodestar.features.tools

import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.kit.phototools.StarTrailPlanner
import net.kuecker.lodestar.kit.phototools.TimeLapsePlanner
import net.kuecker.lodestar.kit.phototools.TimeLapseTarget
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrailsTimeLapseTextTest {
    init {
        Locale.setDefault(Locale.US)
    }

    private val camera = LensSetup(SensorFormat.fullFrame, 24.0, 2.8, 4.0)

    @Test
    fun anHourAtTheEquatorTurnsTheSkyByFifteenDegrees() {
        val result = TrailsText.starTrail { StarTrailPlanner.trail(3600.0, 0.0, camera) }
        val trail = assertIs<Planned.Ok<*>>(result).value as net.kuecker.lodestar.kit.phototools.StarTrail
        assertEquals(15.041, trail.arcLengthDegrees, 0.001)
    }

    @Test
    fun aStarAtThePoleNeverDrawsATrailAndTheScreenSaysSo() {
        val result = TrailsText.starTrail { StarTrailPlanner.duration(10.0, 90.0) }
        val message = assertIs<Planned.Failed<String>>(result).failure
        assertTrue("does not move" in message, message)
    }

    @Test
    fun aTrailThatTakesMoreThanADayIsExplained() {
        val result = TrailsText.starTrail { StarTrailPlanner.duration(360.0, 89.0) }
        val message = assertIs<Planned.Failed<String>>(result).failure
        assertTrue("more than 24 hours" in message, message)
        assertTrue("farther from the pole" in message, message)
    }

    @Test
    fun aStackThatNeedsTooManyFramesSaysWhatToChange() {
        val result = TrailsText.starTrail {
            StarTrailPlanner.stackingPlan(86_400.0, 0.0, camera, exposureSeconds = 0.5, gapSeconds = 0.0)
        }
        val message = assertIs<Planned.Failed<String>>(result).failure
        assertTrue("100,000" in message, message)
        assertTrue("longer frame length" in message, message)
    }

    @Test
    fun aGapThatIsTooShortNamesTheIntervalThatWorks() {
        val result = TrailsText.timeLapse {
            TimeLapsePlanner.plan(5.0, 4.5, 24.0, TimeLapseTarget.ClipLength(10.0))
        }
        val failure = assertIs<Planned.Failed<TimeLapseFailure>>(result).failure
        assertEquals(5.5, assertNotNull(failure.suggestedInterval), 1e-9)
        assertTrue("at least 5.5 s" in failure.message, failure.message)
        assertTrue("0.5 s is left" in failure.message, failure.message)
    }

    @Test
    fun anExposureLongerThanTheIntervalIsExplainedDifferently() {
        val result = TrailsText.timeLapse {
            TimeLapsePlanner.plan(5.0, 8.0, 24.0, TimeLapseTarget.ClipLength(10.0))
        }
        val failure = assertIs<Planned.Failed<TimeLapseFailure>>(result).failure
        assertEquals(9.0, assertNotNull(failure.suggestedInterval), 1e-9)
        assertTrue("longer than the interval" in failure.message, failure.message)
    }

    @Test
    fun aWorkingTimeLapseHasNothingToExplain() {
        val result = TrailsText.timeLapse {
            TimeLapsePlanner.plan(15.0, 10.0, 24.0, TimeLapseTarget.ClipLength(10.0), frameSizeMegabytes = 25.0)
        }
        val plan = assertIs<Planned.Ok<*>>(result).value as net.kuecker.lodestar.kit.phototools.TimeLapsePlan
        assertEquals(240, plan.frameCount)
        assertEquals("1h 00m", TrailsText.duration(plan.shootingTime))
        assertEquals("6 GB", TrailsText.storage(assertNotNull(plan.storageMegabytes)))
    }

    @Test
    fun otherRefusalsHaveWordsButNoSuggestedInterval() {
        val result = TrailsText.timeLapse {
            TimeLapsePlanner.plan(15.0, 10.0, 24.0, TimeLapseTarget.ClipLength(10_000.0))
        }
        val failure = assertIs<Planned.Failed<TimeLapseFailure>>(result).failure
        assertNull(failure.suggestedInterval)
        assertTrue("100,000" in failure.message, failure.message)
    }

    @Test
    fun numbersReadAsPixelsStorageAndGroupedCounts() {
        assertEquals("0.8 px", TrailsText.pixels(0.8))
        assertEquals("1,240 px", TrailsText.pixels(1240.2))
        assertEquals("600 MB", TrailsText.storage(600.0))
        assertEquals("9 GB", TrailsText.storage(9000.0))
        assertEquals("1.5 GB", TrailsText.storage(1500.0))
        assertEquals("12,000", TrailsText.count(12_000))
    }

    @Test
    fun gapsBelowTwoPixelsLookUnbroken() {
        assertTrue("unbroken" in TrailsText.gapVerdict(1.2))
        assertTrue("breaks" in TrailsText.gapVerdict(5.0))
    }
}
