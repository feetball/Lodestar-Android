package net.kuecker.lodestar.kit.phototools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeLapsePlannerTests {
    // MARK: Arithmetic

    @Test
    fun testPlanFromAClipLength() {
        // 10 s of video at 24 frames per second is 240 frames; one every 10 s is 40 minutes of shooting.
        val plan = TimeLapsePlanner.plan(10.0, 5.0, 24.0, TimeLapseTarget.ClipLength(10.0))
        assertEquals(240, plan.frameCount)
        assertEquals(2400.0, plan.shootingTime)
        assertEquals(10.0, plan.clipLength)
        assertEquals(5.0, plan.gap)
        assertEquals(240.0, plan.speedUp)
        assertEquals(10.0, plan.interval)
        assertEquals(5.0, plan.exposure)
        assertEquals(24.0, plan.frameRate)
        assertEquals(240, plan.exposureCount)
    }

    @Test
    fun testPlanFromAShootingTime() {
        // An hour at one frame every 10 s is 360 frames, 15 s of video at 24 frames per second.
        val plan = TimeLapsePlanner.plan(10.0, 8.0, 24.0, TimeLapseTarget.ShootingTime(3600.0))
        assertEquals(360, plan.frameCount)
        assertEquals(3600.0, plan.shootingTime)
        assertEquals(15.0, plan.clipLength)
        assertEquals(240.0, plan.speedUp)
        // Only whole intervals fit.
        assertEquals(359, TimeLapsePlanner.plan(10.0, 8.0, 24.0, TimeLapseTarget.ShootingTime(3599.0)).frameCount)
        assertEquals(360, TimeLapsePlanner.plan(10.0, 8.0, 24.0, TimeLapseTarget.ShootingTime(3609.9)).frameCount)
        assertEquals(3590.0, TimeLapsePlanner.plan(10.0, 8.0, 24.0, TimeLapseTarget.ShootingTime(3599.0)).shootingTime)
        // Seven hours of night at one frame every 15 s.
        val night = TimeLapsePlanner.plan(15.0, 10.0, 30.0, TimeLapseTarget.ShootingTime(7 * 3600.0))
        assertEquals(1680, night.frameCount)
        assertEquals(56.0, night.clipLength, 1e-12)
        assertEquals(450.0, night.speedUp)
    }

    @Test
    fun testPlanFromAFrameCount() {
        val plan = TimeLapsePlanner.plan(30.0, 20.0, 25.0, TimeLapseTarget.FrameCount(100))
        assertEquals(100, plan.frameCount)
        assertEquals(3000.0, plan.shootingTime)
        assertEquals(4.0, plan.clipLength)
        assertEquals(10.0, plan.gap)
        assertEquals(750.0, plan.speedUp)
    }

    @Test
    fun testClipLengthRoundsToWholeFrames() {
        assertEquals(24, TimeLapsePlanner.plan(5.0, 2.0, 24.0, TimeLapseTarget.ClipLength(1.02)).frameCount)
        assertEquals(25, TimeLapsePlanner.plan(5.0, 2.0, 24.0, TimeLapseTarget.ClipLength(1.03)).frameCount)
        assertEquals(1, TimeLapsePlanner.plan(5.0, 2.0, 24.0, TimeLapseTarget.ClipLength(0.03)).frameCount)
        // 29.97 frames per second, a common video rate.
        val ntsc = TimeLapsePlanner.plan(5.0, 2.0, 29.97, TimeLapseTarget.ClipLength(20.0))
        assertEquals(599, ntsc.frameCount)
        assertEquals(599 / 29.97, ntsc.clipLength, 1e-12)
    }

    @Test
    fun testStorageEstimate() {
        // 360 raw files of 25 MB are 9000 MB, 9 GB.
        val plan = TimeLapsePlanner.plan(10.0, 8.0, 24.0, TimeLapseTarget.ShootingTime(3600.0), frameSizeMegabytes = 25.0)
        assertEquals(25.0, plan.frameSizeMegabytes)
        assertEquals(9000.0, plan.storageMegabytes)
        assertEquals(9.0, plan.storageGigabytes)
        val small = TimeLapsePlanner.plan(10.0, 8.0, 24.0, TimeLapseTarget.FrameCount(7), frameSizeMegabytes = 7.5)
        assertEquals(52.5, small.storageMegabytes!!, 1e-12)
        assertEquals(0.0525, small.storageGigabytes!!, 1e-12)
        val unknown = TimeLapsePlanner.plan(10.0, 8.0, 24.0, TimeLapseTarget.FrameCount(7))
        assertNull(unknown.frameSizeMegabytes)
        assertNull(unknown.storageMegabytes)
        assertNull(unknown.storageGigabytes)
    }

    // MARK: Gap

    @Test
    fun testGapMustCoverTheWriteTime() {
        // Exposing 4.5 s every 5 s leaves 0.5 s, less than the default 1 s the camera needs.
        val error = assertFailsWith<TimeLapseError.GapTooShort> {
            TimeLapsePlanner.plan(5.0, 4.5, 24.0, TimeLapseTarget.FrameCount(10))
        }
        assertEquals(0.5, error.gapSeconds)
        assertEquals(1.0, error.minimumGapSeconds)
        assertEquals(5.5, error.minimumIntervalSeconds)
        // A camera that writes faster is fine with it, and exactly the minimum passes.
        assertEquals(0.5, TimeLapsePlanner.plan(5.0, 4.5, 24.0, TimeLapseTarget.FrameCount(10), minimumGapSeconds = 0.5).gap)
        assertEquals(1.0, TimeLapsePlanner.plan(10.0, 9.0, 24.0, TimeLapseTarget.FrameCount(10)).gap)
        assertEquals(0.0, TimeLapsePlanner.plan(10.0, 10.0, 24.0, TimeLapseTarget.FrameCount(10), minimumGapSeconds = 0.0).gap)
        // An exposure as long as the interval, or longer, never works with a write time.
        assertFailsWith<TimeLapseError.GapTooShort> { TimeLapsePlanner.plan(10.0, 10.0, 24.0, TimeLapseTarget.FrameCount(10)) }
        val longer = assertFailsWith<TimeLapseError.GapTooShort> { TimeLapsePlanner.plan(10.0, 12.0, 24.0, TimeLapseTarget.FrameCount(10)) }
        assertEquals(-2.0, longer.gapSeconds)
        assertEquals(13.0, longer.minimumIntervalSeconds)
    }

    @Test
    fun testMinimumInterval() {
        assertEquals(31.0, TimeLapsePlanner.minimumInterval(30.0))
        assertEquals(32.5, TimeLapsePlanner.minimumInterval(30.0, 2.5))
        assertEquals(5.5, TimeLapsePlanner.minimumInterval(5.0, minimumGapSeconds = 0.5))
        assertEquals(30.0, TimeLapsePlanner.minimumInterval(30.0, 0.0))
        assertEquals(TimeLapseError.InvalidExposure(0.0), assertFailsWith<TimeLapseError.InvalidExposure> { TimeLapsePlanner.minimumInterval(0.0) })
        assertFailsWith<TimeLapseError.InvalidExposure> { TimeLapsePlanner.minimumInterval(Double.NaN) }
        assertFailsWith<TimeLapseError.InvalidExposure> { TimeLapsePlanner.minimumInterval(4000.0) }
        assertFailsWith<TimeLapseError.InvalidMinimumGap> { TimeLapsePlanner.minimumInterval(10.0, -1.0) }
        assertFailsWith<TimeLapseError.InvalidMinimumGap> { TimeLapsePlanner.minimumInterval(10.0, 61.0) }
        // The shortest interval that it names plans.
        val interval = TimeLapsePlanner.minimumInterval(30.0)
        assertNotNull(TimeLapsePlanner.plan(interval, 30.0, 24.0, TimeLapseTarget.FrameCount(3)))
    }

    // MARK: Battery

    @Test
    fun testBatteryDemandFollowsTheNumberOfExposures() {
        assertEquals(BatteryDemand.LIGHT, TimeLapsePlanner.batteryDemand(1))
        assertEquals(BatteryDemand.LIGHT, TimeLapsePlanner.batteryDemand(300))
        assertEquals(BatteryDemand.MODERATE, TimeLapsePlanner.batteryDemand(301))
        assertEquals(BatteryDemand.MODERATE, TimeLapsePlanner.batteryDemand(1000))
        assertEquals(BatteryDemand.HEAVY, TimeLapsePlanner.batteryDemand(1001))
        assertEquals(BatteryDemand.HEAVY, TimeLapsePlanner.batteryDemand(2500))
        assertEquals(BatteryDemand.VERY_HEAVY, TimeLapsePlanner.batteryDemand(2501))
        val plan = TimeLapsePlanner.plan(10.0, 8.0, 24.0, TimeLapseTarget.ShootingTime(3600.0))
        assertEquals(BatteryDemand.MODERATE, plan.batteryDemand)
        assertTrue(plan.batteryNote.startsWith("About 360 exposures"), plan.batteryNote)
        assertEquals(TimeLapsePlanner.batteryNote(360), plan.batteryNote)
        assertTrue(TimeLapsePlanner.batteryNote(50).contains("one charged battery"))
        assertTrue(TimeLapsePlanner.batteryNote(2000).contains("spare battery"))
        assertTrue(TimeLapsePlanner.batteryNote(5000).contains("external power"))
        assertTrue(TimeLapsePlanner.batteryNote(5000).startsWith("About 5000 exposures"))
        assertEquals(BatteryDemand.VERY_HEAVY, TimeLapsePlanner.plan(10.0, 8.0, 24.0, TimeLapseTarget.FrameCount(4000)).batteryDemand)
        assertEquals(BatteryDemand.VERY_HEAVY, BatteryDemand.fromRawValue("veryHeavy"))
        assertNull(BatteryDemand.fromRawValue("flat"))
    }

    // MARK: Validation

    @Test
    fun testInvalidInputIsRejected() {
        val target = TimeLapseTarget.FrameCount(10)
        assertEquals(TimeLapseError.InvalidInterval(0.0), assertFailsWith<TimeLapseError.InvalidInterval> { TimeLapsePlanner.plan(0.0, 1.0, 24.0, target) })
        assertFailsWith<TimeLapseError.InvalidInterval> { TimeLapsePlanner.plan(-5.0, 1.0, 24.0, target) }
        assertFailsWith<TimeLapseError.InvalidInterval> { TimeLapsePlanner.plan(Double.NaN, 1.0, 24.0, target) }
        assertFailsWith<TimeLapseError.InvalidInterval> { TimeLapsePlanner.plan(3601.0, 1.0, 24.0, target) }
        assertEquals(TimeLapseError.InvalidExposure(0.0), assertFailsWith<TimeLapseError.InvalidExposure> { TimeLapsePlanner.plan(10.0, 0.0, 24.0, target) })
        assertFailsWith<TimeLapseError.InvalidExposure> { TimeLapsePlanner.plan(10.0, Double.POSITIVE_INFINITY, 24.0, target) }
        assertEquals(TimeLapseError.InvalidFrameRate(0.0), assertFailsWith<TimeLapseError.InvalidFrameRate> { TimeLapsePlanner.plan(10.0, 5.0, 0.0, target) })
        assertFailsWith<TimeLapseError.InvalidFrameRate> { TimeLapsePlanner.plan(10.0, 5.0, 241.0, target) }
        assertFailsWith<TimeLapseError.InvalidFrameRate> { TimeLapsePlanner.plan(10.0, 5.0, Double.NaN, target) }
        assertNotNull(TimeLapsePlanner.plan(10.0, 5.0, 240.0, target))
        assertFailsWith<TimeLapseError.InvalidMinimumGap> { TimeLapsePlanner.plan(10.0, 5.0, 24.0, target, minimumGapSeconds = -0.1) }
        assertFailsWith<TimeLapseError.InvalidMinimumGap> { TimeLapsePlanner.plan(10.0, 5.0, 24.0, target, minimumGapSeconds = Double.NaN) }
        assertEquals(TimeLapseError.InvalidFrameSize(0.0), assertFailsWith<TimeLapseError.InvalidFrameSize> { TimeLapsePlanner.plan(10.0, 5.0, 24.0, target, frameSizeMegabytes = 0.0) })
        assertFailsWith<TimeLapseError.InvalidFrameSize> { TimeLapsePlanner.plan(10.0, 5.0, 24.0, target, frameSizeMegabytes = 20_000.0) }
        assertFailsWith<TimeLapseError.InvalidFrameSize> { TimeLapsePlanner.plan(10.0, 5.0, 24.0, target, frameSizeMegabytes = Double.NaN) }
        assertFailsWith<TimeLapseError.InvalidTarget> { TimeLapsePlanner.plan(10.0, 5.0, 24.0, TimeLapseTarget.ClipLength(0.0)) }
        assertFailsWith<TimeLapseError.InvalidTarget> { TimeLapsePlanner.plan(10.0, 5.0, 24.0, TimeLapseTarget.ClipLength(-3.0)) }
        assertFailsWith<TimeLapseError.InvalidTarget> { TimeLapsePlanner.plan(10.0, 5.0, 24.0, TimeLapseTarget.ClipLength(Double.NaN)) }
        assertFailsWith<TimeLapseError.InvalidTarget> { TimeLapsePlanner.plan(10.0, 5.0, 24.0, TimeLapseTarget.ClipLength(0.01)) }
        assertFailsWith<TimeLapseError.InvalidTarget> { TimeLapsePlanner.plan(10.0, 5.0, 24.0, TimeLapseTarget.ShootingTime(9.0)) }
        assertFailsWith<TimeLapseError.InvalidTarget> { TimeLapsePlanner.plan(10.0, 5.0, 24.0, TimeLapseTarget.ShootingTime(Double.POSITIVE_INFINITY)) }
        assertEquals(TimeLapseError.InvalidTarget(0.0), assertFailsWith<TimeLapseError.InvalidTarget> { TimeLapsePlanner.plan(10.0, 5.0, 24.0, TimeLapseTarget.FrameCount(0)) })
        assertFailsWith<TimeLapseError.InvalidTarget> { TimeLapsePlanner.plan(10.0, 5.0, 24.0, TimeLapseTarget.FrameCount(-4)) }
        assertEquals(1, TimeLapsePlanner.plan(10.0, 5.0, 24.0, TimeLapseTarget.ShootingTime(10.0)).frameCount)
        assertNotNull(TimeLapseError.InvalidTarget(1.0).message)
    }

    @Test
    fun testTooManyFramesIsAnError() {
        val error = assertFailsWith<TimeLapseError.TooManyFrames> {
            TimeLapsePlanner.plan(10.0, 5.0, 24.0, TimeLapseTarget.FrameCount(100_001))
        }
        assertEquals(100_001, error.count)
        assertEquals(TimeLapsePlanner.maximumFrameCount, error.limit)
        assertFailsWith<TimeLapseError.TooManyFrames> { TimeLapsePlanner.plan(2.0, 1.0, 24.0, TimeLapseTarget.ShootingTime(1e7)) }
        assertFailsWith<TimeLapseError.TooManyFrames> { TimeLapsePlanner.plan(10.0, 5.0, 30.0, TimeLapseTarget.ClipLength(5000.0)) }
        assertEquals(100_000, TimeLapsePlanner.plan(10.0, 5.0, 24.0, TimeLapseTarget.FrameCount(100_000)).frameCount)
    }

    @Test
    fun testPlansAreValues() {
        val a = TimeLapsePlanner.plan(10.0, 5.0, 24.0, TimeLapseTarget.ClipLength(10.0), frameSizeMegabytes = 30.0)
        val b = TimeLapsePlanner.plan(10.0, 5.0, 24.0, TimeLapseTarget.FrameCount(240), frameSizeMegabytes = 30.0)
        assertEquals(a, b)
        assertTrue(a != TimeLapsePlanner.plan(10.0, 5.0, 25.0, TimeLapseTarget.FrameCount(240), frameSizeMegabytes = 30.0))
    }
}
