package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.horizon.HorizonRecorder.Rejection
import net.kuecker.lodestar.kit.horizon.HorizonRecorder.UnrecordedFill
import net.kuecker.lodestar.kit.horizon.HorizonTestSupport as Support
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HorizonRecorderTests {
    // MARK: Complete sweeps

    @Test
    fun testACleanSweepRecordsTheSkyline() {
        val recorder = HorizonRecorder()
        Support.sweep(recorder, from = 0.0, to = 359.5, rate = 15.0, hertz = 50.0)
        assertEquals(1.0, recorder.coverage, 1e-12)
        assertEquals(360, recorder.recordedBinCount)
        assertTrue(recorder.gaps.isEmpty())
        val recording = recorder.finish()
        assertTrue(recording.isComplete)
        assertEquals(0, recording.interpolatedBinCount)
        assertEquals(1.0, recording.coverage, 1e-12)
        assertTrue(Support.largestError(recording.profile) < 0.35, "${Support.largestError(recording.profile)}")
        assertEquals(30.0, recording.profile.maximumAltitude, 0.35)
        assertEquals(recording.profile, recorder.profile())
        // The ridge, the building and the trees are where they should be.
        assertEquals(30.0, recording.profile.altitude(90.0), 0.35)
        assertEquals(12.0, recording.profile.altitude(190.0), 0.35)
        assertEquals(2.0, recording.profile.altitude(300.0), 0.35)
    }

    @Test
    fun testNoiseAndWildReadingsAreSuppressedByTheMedian() {
        val recorder = HorizonRecorder()
        val random = Support.SeededGenerator(7uL)
        // Ten readings per bin, each off by up to ±1°, and 3 % of them 15° too high.
        Support.sweep(
            recorder, from = 0.0, to = 359.5, rate = 5.0, hertz = 50.0,
            noise = 1.0, outliers = 0.03, random = random,
        )
        val profile = recorder.profile()
        assertTrue(Support.largestError(profile) < 1.3, "largest ${Support.largestError(profile)}")
        assertTrue(Support.meanError(profile) < 0.35, "mean ${Support.meanError(profile)}")
        assertEquals(1.0, recorder.coverage, 1e-12)
    }

    @Test
    fun testHandJitterAndBacktrackingWithinAPassKeepTheMedian() {
        val recorder = HorizonRecorder()
        val random = Support.SeededGenerator(11uL)
        // A slow sweep whose azimuth wobbles back and forth by ±2° (a hand that shakes and backs up).
        var time = 0.0
        var step = 0
        var azimuth = 0.0
        while (azimuth < 359.0) {
            val wobble = 2.0 * kotlin.math.sin(step * 0.07)
            val reported = azimuth + wobble
            recorder.add(reported, Support.truth(reported) + 0.6 * random.nextSigned(), time)
            time += 0.02
            step += 1
            azimuth += 0.08
        }
        assertEquals(1.0, recorder.coverage, 1e-12)
        val profile = recorder.profile()
        // A hand that dwells at one side of a bin shifts its median by the skyline's slope over half a degree, so the
        // steep sides of the building (2.5° per degree) are left out of the strict check.
        assertTrue(Support.largestError(profile, 3..170) < 0.8, "${Support.largestError(profile, 3..170)}")
        assertTrue(Support.largestError(profile, 210..356) < 0.8, "${Support.largestError(profile, 210..356)}")
        assertTrue(Support.meanError(profile, 3..356) < 0.35, "${Support.meanError(profile, 3..356)}")
        assertEquals(0, recorder.rejectedCount(Rejection.TOO_FAST))
    }

    // MARK: Gaps

    @Test
    fun testSmallGapsAreInterpolatedAndLargeOnesAreReported() {
        val recorder = HorizonRecorder()
        // Bins 101…104 are missing (a small gap) and bins 221…250 (a large one).
        Support.sweep(
            recorder, from = 0.0, to = 359.5, rate = 15.0, hertz = 50.0,
            skip = { (it >= 100.5 && it < 104.5) || (it >= 220.5 && it < 250.5) },
        )
        val gaps = recorder.gaps
        assertEquals(1, gaps.size)
        assertEquals(220.5, gaps[0].start, 1e-9)
        assertEquals(30.0, gaps[0].length, 1e-9)
        assertTrue(gaps[0].contains(235.0))
        assertFalse(gaps[0].contains(102.0))
        assertTrue(recorder.coverage < 1.0 && recorder.coverage > 0.9)
        assertFalse(recorder.isRecorded(235.0))
        assertTrue(recorder.isRecorded(150.0))

        val recording = recorder.finish()
        assertFalse(recording.isComplete)
        assertEquals(gaps, recording.gaps)
        // The small gap follows the line between its recorded neighbours.
        val before = recording.profile.altitude(100.0)
        val after = recording.profile.altitude(105.0)
        for (azimuth in 101..104) {
            val expected = before + (after - before) * (azimuth - 100).toDouble() / 5.0
            assertEquals(expected, recording.profile.altitude(azimuth.toDouble()), 0.11, "$azimuth")
            assertEquals(Support.truth(azimuth.toDouble()), recording.profile.altitude(azimuth.toDouble()), 0.4, "$azimuth")
        }
        assertEquals(4, recording.interpolatedBinCount)
        // Without a base the large gap is flat, with one it keeps the base.
        assertEquals(0.0, recording.profile.altitude(235.0))
        val kept = recorder.finish(base = HorizonProfile.constant(7.0)).profile
        assertEquals(7.0, kept.altitude(235.0))
        assertEquals(Support.truth(150.0), kept.altitude(150.0), 0.35)
    }

    @Test
    fun testALargeGapCanBeInterpolatedInstead() {
        val recorder = HorizonRecorder()
        // Bins 61…90 are missing: the ridge's slope up to its peak.
        Support.sweep(recorder, from = 0.0, to = 359.5, rate = 15.0, hertz = 50.0, skip = { it >= 60.5 && it < 90.5 })
        val filled = recorder.finish(fill = UnrecordedFill.INTERPOLATE)
        // Still reported as a gap, but the profile runs straight between the two ends.
        assertEquals(1, filled.gaps.size)
        assertEquals(30.0, filled.gaps[0].length, 1e-9)
        val left = filled.profile.altitude(60.0)
        val right = filled.profile.altitude(91.0)
        assertEquals(Support.truth(60.0), left, 0.35)
        assertEquals(Support.truth(91.0), right, 0.35)
        for (azimuth in listOf(61, 75, 90)) {
            val expected = left + (right - left) * (azimuth - 60).toDouble() / 31.0
            assertEquals(expected, filled.profile.altitude(azimuth.toDouble()), 0.16, "$azimuth")
        }
        assertEquals(30, filled.interpolatedBinCount)
        // Without the policy the gap is flat.
        assertEquals(0.0, recorder.finish().profile.altitude(75.0))
    }

    @Test
    fun testSweepAcrossNorthWrapsAround() {
        val recorder = HorizonRecorder()
        Support.sweep(recorder, from = 330.0, to = 390.0, rate = 12.0, hertz = 50.0)
        assertEquals(61.0 / 360.0, recorder.coverage, 1e-9)
        for (azimuth in listOf(330.0, 355.0, 359.0, 0.0, 5.0, 30.0)) assertTrue(recorder.isRecorded(azimuth), "$azimuth")
        for (azimuth in listOf(329.0, 31.0, 180.0)) assertFalse(recorder.isRecorded(azimuth), "$azimuth")
        val mask = recorder.recordedMask
        assertEquals(360, mask.size)
        assertEquals(61, mask.count { it })
        assertTrue(mask[359] && mask[0])
        val gaps = recorder.gaps
        assertEquals(1, gaps.size)
        assertEquals(30.5, gaps[0].start, 1e-9)
        assertEquals(299.0, gaps[0].length, 1e-9)
        val profile = recorder.profile()
        for (azimuth in listOf(335, 358, 359, 0, 1, 20)) {
            assertEquals(Support.truth(azimuth.toDouble()), profile.altitude(azimuth.toDouble()), 0.3, "$azimuth")
        }
        // The profile is continuous through north.
        assertEquals(profile.altitude(359.0), profile.altitude(360.0), 0.3)
    }

    // MARK: Partial sweeps

    @Test
    fun testAPartialSweepLeavesTheRestFlatOrKeepsTheBaseOrInterpolates() {
        val recorder = HorizonRecorder()
        Support.sweep(recorder, from = 40.0, to = 160.0, rate = 15.0, hertz = 50.0)
        assertEquals(121.0 / 360.0, recorder.coverage, 1e-9)
        assertEquals(1, recorder.gaps.size)
        assertEquals(239.0, recorder.gaps[0].length, 1e-9)
        assertEquals(160.5, recorder.gaps[0].start, 1e-9)

        // Unrecorded azimuths stay at 0°…
        val flat = recorder.finish()
        for (azimuth in listOf(0, 20, 39, 161, 200, 300, 359)) assertEquals(0.0, flat.profile.altitudes[azimuth], "$azimuth")
        assertEquals(Support.truth(90.0), flat.profile.altitude(90.0), 0.35)
        assertEquals(0, flat.interpolatedBinCount)

        // …or keep the profile that was there before…
        val base = HorizonProfile.constant(5.0).setting(18.0, AzimuthRange.between(250.0, 290.0))
        val merged = recorder.finish(UnrecordedFill.KEEP_BASE, base).profile
        for (azimuth in listOf(0, 20, 39, 161, 200, 300, 359)) assertEquals(base.altitudes[azimuth], merged.altitudes[azimuth], "$azimuth")
        assertEquals(18.0, merged.altitude(270.0))
        assertEquals(Support.truth(100.0), merged.altitude(100.0), 0.35)

        // …or run straight from the end of the sweep back to its start through north.
        val line = recorder.finish(UnrecordedFill.INTERPOLATE)
        val atEnd = line.profile.altitude(160.0)
        val atStart = line.profile.altitude(40.0)
        // 160° → 40° through north is 240° of azimuth; 280° is halfway.
        assertEquals((atEnd + atStart) / 2, line.profile.altitude(280.0), 0.2)
        assertEquals(atEnd + (atStart - atEnd) * (60.0 / 240.0), line.profile.altitude(220.0), 0.2)
        assertEquals(239, line.interpolatedBinCount)
    }

    @Test
    fun testNothingRecordedGivesTheBaseAndTheWholeCircleAsGap() {
        val recorder = HorizonRecorder()
        assertEquals(0.0, recorder.coverage)
        assertEquals(listOf(AzimuthRange.full), recorder.gaps)
        assertTrue(recorder.profile().isFlat)
        val base = Support.truthProfile
        val recording = recorder.finish(UnrecordedFill.INTERPOLATE, base)
        assertEquals(base, recording.profile)
        assertEquals(0.0, recording.coverage)
        assertEquals(listOf(AzimuthRange.full), recording.gaps)
        assertFalse(recording.isComplete)
        assertFalse(recorder.isRecorded(10.0))
        assertNull(recorder.lastAzimuth)
        assertNull(recorder.sweepRate)
    }

    @Test
    fun testASingleBinFillsTheCircleWhenInterpolating() {
        val recorder = HorizonRecorder()
        recorder.add(10.0, 8.0, 0.0)
        assertEquals(1, recorder.recordedBinCount)
        val line = recorder.finish(UnrecordedFill.INTERPOLATE).profile
        assertEquals(HorizonProfile.constant(8.0), line)
        assertEquals(8.0, recorder.finish().profile.altitude(10.0))
        assertEquals(0.0, recorder.finish().profile.altitude(100.0))
    }

    // MARK: Backtracking

    @Test
    fun testSweepingBackOverAStretchReplacesTheOldPass() {
        val recorder = HorizonRecorder()
        // The first pass was held 6° too high.
        var time = Support.sweep(recorder, from = 0.0, to = 120.0, rate = 15.0, hertz = 50.0, bias = 6.0)
        val firstPass = recorder.profile()
        assertEquals(Support.truth(30.0) + 6.0, firstPass.altitude(30.0), 0.4)
        // After a pause the user sweeps 120° back to 60° and forward again over the stretch they got wrong.
        time += 5.0
        time = Support.sweep(recorder, from = 120.0, to = 60.0, rate = 15.0, hertz = 50.0, start = time)
        val profile = recorder.profile()
        for (azimuth in 62..118) {
            assertEquals(Support.truth(azimuth.toDouble()), profile.altitude(azimuth.toDouble()), 0.4, "$azimuth")
        }
        // What was not swept again keeps the first pass.
        for (azimuth in 5..55) {
            assertEquals(Support.truth(azimuth.toDouble()) + 6.0, profile.altitude(azimuth.toDouble()), 0.4, "$azimuth")
        }
        assertEquals(121.0 / 360.0, recorder.coverage, 1e-9)
        assertTrue(time > 0)
    }

    @Test
    fun testSamplesWithinAPassOfTheSameBinAreMergedNotReplaced() {
        // Two readings 1 s apart in one bin are the same pass: the median of three is the middle one.
        val recorder = HorizonRecorder()
        recorder.add(50.0, 10.0, 0.0)
        recorder.add(50.1, 20.0, 1.0)
        recorder.add(49.9, 12.0, 2.0)
        assertEquals(12.0, recorder.profile().altitude(50.0))
        // With an even number the two middle values are averaged.
        recorder.add(50.0, 16.0, 2.5)
        assertEquals(14.0, recorder.profile().altitude(50.0))
        // After more than the pass interval the bin starts over.
        recorder.add(50.0, 3.0, 20.0)
        assertEquals(3.0, recorder.profile().altitude(50.0))
        assertEquals(1, recorder.recordedBinCount)
    }

    @Test
    fun testABinKeepsOnlyTheMostRecentSamples() {
        val config = HorizonRecorder.Configuration(maximumSamplesPerBin = 3)
        val recorder = HorizonRecorder(config)
        // Old values 40, 41, 42 are pushed out by 10, 11, 12.
        for ((index, value) in listOf(40.0, 41.0, 42.0, 10.0, 11.0, 12.0).withIndex()) {
            assertNull(recorder.add(70.0, value, index.toDouble()))
        }
        assertEquals(11.0, recorder.profile().altitude(70.0))
    }

    // MARK: Rejected samples

    @Test
    fun testRejectedSamplesAreCountedByReason() {
        val recorder = HorizonRecorder()
        assertNull(recorder.add(10.0, 5.0, 0.0))
        assertEquals(Rejection.INVALID, recorder.add(Double.NaN, 5.0, 0.1))
        assertEquals(Rejection.INVALID, recorder.add(10.0, Double.POSITIVE_INFINITY, 0.1))
        assertEquals(Rejection.INVALID, recorder.add(10.0, 5.0, Double.NaN))
        assertEquals(Rejection.LOW_QUALITY, recorder.add(10.0, 5.0, 0.1, quality = 0.1))
        assertNull(recorder.add(10.0, 5.0, 0.2, quality = 0.25))
        assertNull(recorder.add(10.0, 5.0, 0.3, quality = null))
        assertNull(recorder.add(10.0, 5.0, 0.4, quality = Double.NaN))
        assertEquals(Rejection.OUT_OF_RANGE, recorder.add(10.0, 61.0, 0.5))
        assertEquals(Rejection.OUT_OF_RANGE, recorder.add(10.0, -10.5, 0.6))
        assertNull(recorder.add(10.0, 60.0, 10.0)) // the edge of the range is allowed
        assertEquals(5, recorder.acceptedCount)
        assertEquals(3, recorder.rejectedCount(Rejection.INVALID))
        assertEquals(1, recorder.rejectedCount(Rejection.LOW_QUALITY))
        assertEquals(2, recorder.rejectedCount(Rejection.OUT_OF_RANGE))
        assertEquals(0, recorder.rejectedCount(Rejection.TOO_FAST))
        // The first reason that applies is reported: invalid before quality before range.
        assertEquals(Rejection.INVALID, recorder.add(Double.NaN, 99.0, 1.0, quality = 0.0))
        assertEquals(Rejection.LOW_QUALITY, recorder.add(10.0, 99.0, 1.0, quality = 0.0))
    }

    @Test
    fun testTooFastSamplesAreRejectedAndTheSweepRateIsReported() {
        val recorder = HorizonRecorder()
        assertNull(recorder.add(0.0, 0.0, 0.0))
        assertNull(recorder.sweepRate)
        assertNull(recorder.add(1.0, 0.0, 0.1)) // 10°/s
        assertEquals(10.0, assertNotNull(recorder.sweepRate), 1e-6)
        // 30° in 0.1 s is 300°/s: the camera was whipped round.
        assertEquals(Rejection.TOO_FAST, recorder.add(31.0, 0.0, 0.2))
        assertEquals(300.0, assertNotNull(recorder.sweepRate), 1e-6)
        assertEquals(1, recorder.rejectedCount(Rejection.TOO_FAST))
        assertFalse(recorder.isRecorded(31.0))
        // The next reading, close to the one that was rejected, is accepted at once.
        assertNull(recorder.add(31.5, 0.0, 0.3))
        assertTrue(recorder.isRecorded(31.5))
        assertEquals(31.5, assertNotNull(recorder.lastAzimuth), 1e-12)
        assertEquals(0.0, assertNotNull(recorder.lastAltitude), 1e-12)
        // Altitude jitter does not count, and a sample at the same time or earlier is not judged.
        assertNull(recorder.add(31.5, 45.0, 0.4))
        assertNull(recorder.add(32.5, 44.0, 0.4))
        assertNull(recorder.add(32.5, 44.0, 0.0))
        assertEquals(1, recorder.rejectedCount(Rejection.TOO_FAST))
    }

    @Test
    fun testTheSweepRateAcrossNorthIsTheShortWayRound() {
        val recorder = HorizonRecorder()
        recorder.add(359.0, 0.0, 0.0)
        assertNull(recorder.add(1.0, 0.0, 0.5)) // 4°/s through north, not 716°/s
        assertEquals(4.0, assertNotNull(recorder.sweepRate), 1e-6)
    }

    @Test
    fun testAHighCameraShortensTheAzimuthTravel() {
        // At 60° altitude a turn of 10° of azimuth moves the picture by 10° · cos 60° = 5°.
        val recorder = HorizonRecorder(HorizonRecorder.Configuration(maximumSweepRate = 6.0))
        recorder.add(0.0, 60.0, 0.0)
        assertNull(recorder.add(10.0, 60.0, 1.0)) // 5°/s
        assertEquals(5.0, assertNotNull(recorder.sweepRate), 0.01)
        assertEquals(Rejection.TOO_FAST, recorder.add(22.0, 60.0, 2.0)) // 6.0…
    }

    // MARK: Editing and configuration

    @Test
    fun testClearForgetsAStretchSoItCanBeSweptAgain() {
        val recorder = HorizonRecorder()
        Support.sweep(recorder, from = 0.0, to = 359.5, rate = 15.0, hertz = 50.0)
        recorder.clear(AzimuthRange.between(100.0, 140.0))
        assertEquals(360 - 41, recorder.recordedBinCount)
        assertFalse(recorder.isRecorded(120.0))
        assertTrue(recorder.isRecorded(99.0) && recorder.isRecorded(141.0))
        assertEquals(1, recorder.gaps.size)
        assertEquals(41.0, recorder.gaps[0].length, 1e-9)
        // Sweeping it again right away (the bins are empty, so there is no old pass) fills it.
        Support.sweep(recorder, from = 100.0, to = 140.0, rate = 15.0, hertz = 50.0, start = 30.0)
        assertEquals(1.0, recorder.coverage, 1e-12)
        assertTrue(recorder.gaps.isEmpty())
        assertTrue(Support.largestError(recorder.profile()) < 0.35)
    }

    @Test
    fun testResetStartsOver() {
        val recorder = HorizonRecorder()
        Support.sweep(recorder, from = 0.0, to = 90.0, rate = 15.0, hertz = 50.0)
        recorder.add(Double.NaN, 0.0, 0.0)
        assertTrue(recorder.acceptedCount > 0)
        recorder.reset()
        assertEquals(0, recorder.acceptedCount)
        assertEquals(0, recorder.rejectedCount(Rejection.INVALID))
        assertEquals(0, recorder.recordedBinCount)
        assertNull(recorder.lastAzimuth)
        assertNull(recorder.sweepRate)
        assertTrue(recorder.profile().isFlat)
        // After a reset the first sample is not compared with anything.
        assertNull(recorder.add(200.0, 5.0, 1.0))
    }

    @Test
    fun testFinishDoesNotChangeTheRecorder() {
        val recorder = HorizonRecorder()
        Support.sweep(recorder, from = 0.0, to = 100.0, rate = 15.0, hertz = 50.0)
        val first = recorder.finish()
        val second = recorder.finish()
        assertEquals(first, second)
        Support.sweep(recorder, from = 100.0, to = 359.5, rate = 15.0, hertz = 50.0, start = 7.0)
        assertTrue(recorder.finish().isComplete)
        assertFalse(first.isComplete)
    }

    @Test
    fun testConfigurationFallsBackToDefaultsAndClamps() {
        val defaults = HorizonRecorder.Configuration.standard
        assertEquals(0.25, defaults.minimumQuality)
        assertEquals(60.0, defaults.maximumSweepRate)
        assertEquals(25, defaults.maximumSamplesPerBin)
        assertEquals(3.0, defaults.newPassInterval)
        assertEquals(6, defaults.maximumInterpolatedGap)
        assertEquals(defaults, HorizonRecorder.Configuration())
        val odd = HorizonRecorder.Configuration(Double.NaN, -5.0, 1_000, Double.POSITIVE_INFINITY, 99)
        assertEquals(0.25, odd.minimumQuality)
        assertEquals(60.0, odd.maximumSweepRate)
        assertEquals(100, odd.maximumSamplesPerBin)
        assertEquals(3.0, odd.newPassInterval)
        assertEquals(30, odd.maximumInterpolatedGap)
        val low = HorizonRecorder.Configuration(minimumQuality = -1.0, maximumSamplesPerBin = 0, maximumInterpolatedGap = -3)
        assertEquals(0.0, low.minimumQuality)
        assertEquals(1, low.maximumSamplesPerBin)
        assertEquals(0, low.maximumInterpolatedGap)
        assertEquals(1.0, HorizonRecorder.Configuration(minimumQuality = 4.0).minimumQuality)
    }

    @Test
    fun testTheInterpolationLimitDecidesWhatIsAGap() {
        val tight = HorizonRecorder(HorizonRecorder.Configuration(maximumInterpolatedGap = 2))
        Support.sweep(tight, from = 0.0, to = 359.5, rate = 15.0, hertz = 50.0, skip = { it >= 100.5 && it < 104.5 })
        assertEquals(1, tight.gaps.size)
        assertEquals(4.0, tight.gaps[0].length, 1e-9)
        val loose = HorizonRecorder(HorizonRecorder.Configuration(maximumInterpolatedGap = 10))
        Support.sweep(loose, from = 0.0, to = 359.5, rate = 15.0, hertz = 50.0, skip = { it >= 100.5 && it < 104.5 })
        assertTrue(loose.gaps.isEmpty())
        assertTrue(loose.finish().isComplete)
    }

    @Test
    fun testRejectionAndFillRawValues() {
        assertEquals(
            listOf("invalid", "lowQuality", "outOfRange", "tooFast"),
            Rejection.entries.map { it.rawValue },
        )
        assertEquals(Rejection.TOO_FAST, Rejection.fromRawValue("tooFast"))
        assertNull(Rejection.fromRawValue("slow"))
        assertEquals(listOf("keepBase", "interpolate"), UnrecordedFill.entries.map { it.rawValue })
        assertEquals(UnrecordedFill.INTERPOLATE, UnrecordedFill.fromRawValue("interpolate"))
        assertNull(UnrecordedFill.fromRawValue("zero"))
    }
}
