package net.kuecker.lodestar.kit.phototools

import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import net.kuecker.lodestar.kit.planning.PolarAlignment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StarTrailPlannerTests {
    /** 24 mm on a full-frame camera with 4 µm pixels: 34.377″ per pixel. */
    private val wide = LensSetup(SensorFormat.fullFrame, 24.0, 2.8, 4.0)

    /** The arcseconds in a radian. */
    private val arcsecondsPerRadian = 180.0 * 3600.0 / PI

    private val boulder = Observer(GeographicLocation(latitude = 40.015, longitude = -105.27, elevation = 1650.0))
    private val date = instantFromTimeIntervalSince1970(1_792_209_600.0)

    // MARK: Trail arcs

    @Test
    fun testTrailAtTheEquatorOverAnHourIsFifteenDegrees() {
        val trail = StarTrailPlanner.trail(3600.0, 0.0, wide)
        // The sidereal rate is 15.041° per hour (the text-book "15°" is rounded).
        assertEquals(15.0, trail.rotationDegrees, 0.05)
        assertEquals(15.041, trail.rotationDegrees, 1e-9)
        assertEquals(15.041, trail.arcLengthDegrees, 1e-9)
        assertEquals(90.0, trail.radiusFromPoleDegrees)
        assertEquals(3600.0, trail.duration)
        assertEquals(0.0, trail.declination)
        assertEquals(15.041, StarTrailPlanner.rotationRateDegreesPerHour)
        assertTrue(!trail.usedDefaultPixelPitch)
    }

    @Test
    fun testTrailAtDeclinationSixtyIsHalfAsLong() {
        val trail = StarTrailPlanner.trail(3600.0, 60.0, wide)
        assertEquals(7.5, trail.arcLengthDegrees, 0.05)
        assertEquals(15.041 * 0.5, trail.arcLengthDegrees, 1e-9)
        // The star still turns by the same angle about the pole; its circle is smaller.
        assertEquals(15.041, trail.rotationDegrees, 1e-9)
        assertEquals(30.0, trail.radiusFromPoleDegrees)
        // The south counts like the north.
        assertEquals(trail.arcLengthDegrees, StarTrailPlanner.trail(3600.0, -60.0, wide).arcLengthDegrees, 1e-12)
        assertEquals(30.0, StarTrailPlanner.trail(3600.0, -60.0, wide).radiusFromPoleDegrees)
    }

    @Test
    fun testTrailLengthFollowsTheCosineOfTheDeclination() {
        for (declination in listOf(-89.0, -45.0, 0.0, 10.0, 30.0, 75.0, 90.0)) {
            for (seconds in listOf(1.0, 30.0, 3600.0, 36_000.0)) {
                val trail = StarTrailPlanner.trail(seconds, declination, wide)
                assertEquals(15.041 / 3600 * seconds * cos(declination * PI / 180), trail.arcLengthDegrees, 1e-9)
                assertEquals(15.041 / 3600 * seconds, trail.rotationDegrees, 1e-9)
            }
        }
        assertEquals(0.0, StarTrailPlanner.trail(3600.0, 90.0, wide).arcLengthDegrees, 1e-9)
        assertEquals(0.0, StarTrailPlanner.trail(3600.0, 90.0, wide).radiusFromPoleDegrees)
    }

    @Test
    fun testPixelLengthsForAKnownPixelPitch() {
        // 4 µm behind 24 mm: 206 264.8″ × 0.004 mm / 24 mm = 34.377″ per pixel.
        val scale = arcsecondsPerRadian * 0.004 / 24.0
        assertEquals(34.3775, scale, 1e-4)
        val thirty = StarTrailPlanner.trail(30.0, 0.0, wide)
        assertEquals(15.041 * 30.0 / scale, thirty.arcLengthPixels, 1e-9)
        assertEquals(13.126, thirty.arcLengthPixels, 0.001)
        val hour = StarTrailPlanner.trail(3600.0, 0.0, wide)
        assertEquals(1575.1, hour.arcLengthPixels, 0.1)
        // At declination 60 half as many pixels.
        assertEquals(hour.arcLengthPixels / 2, StarTrailPlanner.trail(3600.0, 60.0, wide).arcLengthPixels, 1e-6)
        // A longer lens and a smaller pixel stretch the trail: 85 mm, 3.76 µm.
        val tele = LensSetup(SensorFormat.fullFrame, 85.0, 1.8, 3.76)
        val teleScale = arcsecondsPerRadian * 0.00376 / 85.0
        assertEquals(15.041 * 30.0 * cos(45.0 * PI / 180) / teleScale, StarTrailPlanner.trail(30.0, 45.0, tele).arcLengthPixels, 1e-9)
        // It agrees with the exposure calculator's own figure.
        assertEquals(ExposureCalculator.trailLengthPixels(30.0, 45.0, tele), StarTrailPlanner.trail(30.0, 45.0, tele).arcLengthPixels)
    }

    @Test
    fun testMissingPixelPitchAssumesFourMicrometres() {
        val unknown = LensSetup(SensorFormat.fullFrame, 24.0, 2.8)
        val trail = StarTrailPlanner.trail(30.0, 0.0, unknown)
        assertTrue(trail.usedDefaultPixelPitch)
        assertEquals(StarTrailPlanner.trail(30.0, 0.0, wide).arcLengthPixels, trail.arcLengthPixels, 1e-9)
    }

    // MARK: Durations

    @Test
    fun testDurationForAGivenArc() {
        assertEquals(3600.0, StarTrailPlanner.duration(15.041, 0.0), 1e-6)
        assertEquals(7200.0, StarTrailPlanner.duration(15.041, 60.0), 1e-6)
        // A trail of 10° at declination 30°: 10 / (15.041/3600 × cos 30°) seconds.
        assertEquals(10.0 / (15.041 / 3600 * cos(30.0 * PI / 180)), StarTrailPlanner.duration(10.0, 30.0), 1e-9)
        // It undoes trail().
        for (declination in listOf(-70.0, 0.0, 25.0, 80.0)) {
            val trail = StarTrailPlanner.trail(1234.5, declination, wide)
            assertEquals(1234.5, StarTrailPlanner.duration(trail.arcLengthDegrees, declination), 1e-6)
        }
    }

    @Test
    fun testDurationForAGivenPixelLength() {
        // The shutter stays open until the trail is 20 pixels long.
        val seconds = StarTrailPlanner.duration(20.0, 0.0, wide)
        assertEquals(20.0 * (arcsecondsPerRadian * 0.004 / 24.0) / 15.041, seconds, 1e-9)
        assertEquals(20.0, StarTrailPlanner.trail(seconds, 0.0, wide).arcLengthPixels, 1e-9)
        assertEquals(20.0, StarTrailPlanner.trail(StarTrailPlanner.duration(20.0, 50.0, wide), 50.0, wide).arcLengthPixels, 1e-9)
    }

    @Test
    fun testDurationErrors() {
        assertEquals(StarTrailError.InvalidArc(0.0), assertFailsWith<StarTrailError.InvalidArc> { StarTrailPlanner.duration(0.0, 0.0) })
        assertFailsWith<StarTrailError.InvalidArc> { StarTrailPlanner.duration(361.0, 0.0) }
        assertFailsWith<StarTrailError.InvalidArc> { StarTrailPlanner.duration(Double.NaN, 0.0) }
        assertFailsWith<StarTrailError.InvalidArc> { StarTrailPlanner.duration(-5.0, 0.0) }
        assertEquals(StarTrailError.InvalidDeclination(91.0), assertFailsWith<StarTrailError.InvalidDeclination> { StarTrailPlanner.duration(5.0, 91.0) })
        assertFailsWith<StarTrailError.InvalidDeclination> { StarTrailPlanner.duration(5.0, Double.NaN) }
        assertEquals(StarTrailError.NoMotionAtThePole, assertFailsWith<StarTrailError.NoMotionAtThePole> { StarTrailPlanner.duration(5.0, 90.0) })
        assertFailsWith<StarTrailError.NoMotionAtThePole> { StarTrailPlanner.duration(5.0, -90.0) }
        // 5° at declination 89.9° would take 5 / (15.041/3600 × 0.001745) s = 190 hours.
        val tooLong = assertFailsWith<StarTrailError.DurationTooLong> { StarTrailPlanner.duration(5.0, 89.9) }
        assertTrue(tooLong.seconds > 86_400.0)
        assertFailsWith<StarTrailError.InvalidArc> { StarTrailPlanner.duration(0.0, 0.0, wide) }
        assertFailsWith<StarTrailError.InvalidArc> { StarTrailPlanner.duration(Double.POSITIVE_INFINITY, 0.0, wide) }
        assertFailsWith<StarTrailError.NoMotionAtThePole> { StarTrailPlanner.duration(10.0, 90.0, wide) }
        assertFailsWith<StarTrailError.DurationTooLong> { StarTrailPlanner.duration(1e6, 0.0, wide) }
    }

    @Test
    fun testTrailErrors() {
        assertEquals(StarTrailError.InvalidDuration(0.0), assertFailsWith<StarTrailError.InvalidDuration> { StarTrailPlanner.trail(0.0, 0.0, wide) })
        assertFailsWith<StarTrailError.InvalidDuration> { StarTrailPlanner.trail(-1.0, 0.0, wide) }
        assertFailsWith<StarTrailError.InvalidDuration> { StarTrailPlanner.trail(Double.NaN, 0.0, wide) }
        assertFailsWith<StarTrailError.InvalidDuration> { StarTrailPlanner.trail(86_401.0, 0.0, wide) }
        assertNotNull(StarTrailPlanner.trail(86_400.0, 0.0, wide))
        assertEquals(StarTrailError.InvalidDeclination(-91.0), assertFailsWith<StarTrailError.InvalidDeclination> { StarTrailPlanner.trail(60.0, -91.0, wide) })
        assertFailsWith<StarTrailError.InvalidDeclination> { StarTrailPlanner.trail(60.0, Double.POSITIVE_INFINITY, wide) }
    }

    // MARK: Stacking

    @Test
    fun testStackingPlanFromTheNpfRule() {
        // NPF: (35 × 2.8 + 30 × 4) / 24 = 9.083 s, used as 9 s. With a one-second gap a frame takes 10 s.
        val plan = StarTrailPlanner.stackingPlan(7200.0, null, wide)
        assertEquals(ExposureRule.NPF, plan.estimate.rule)
        assertEquals(218.0 / 24.0, plan.estimate.seconds, 1e-12)
        assertEquals(9.0, plan.exposureSeconds)
        assertEquals(1.0, plan.gapSeconds)
        assertEquals(720, plan.frameCount)
        assertEquals(7200.0, plan.actualDuration, 1e-9)
        assertEquals(6480.0, plan.lightSeconds, 1e-9)
        assertEquals(7200.0, plan.totalDuration)
        // A star at the equator moves 9 × 15.041″ = 135″ = 3.94 px in a frame and 0.44 px in the gap.
        assertEquals(15.041 * 9.0 / (arcsecondsPerRadian * 0.004 / 24.0), plan.exposureTrailPixels, 1e-9)
        assertEquals(3.94, plan.exposureTrailPixels, 0.01)
        assertEquals(0.4375, plan.gapPixels, 0.001)
        assertEquals(7200.0, plan.trail.duration, 1e-9)
        assertEquals(15.041 * 2, plan.trail.rotationDegrees, 1e-9)
        assertEquals(0.0, plan.trail.declination)
    }

    @Test
    fun testStackingPlanFollowsTheRuleAndTheDeclination() {
        // 500 rule: 500 / 24 = 20.83 s, used as 20 s; frames of 21 s give ceil(7200 / 21) = 343.
        val rule500 = StarTrailPlanner.stackingPlan(7200.0, null, wide, rule = ExposureRule.RULE500)
        assertEquals(20.0, rule500.exposureSeconds)
        assertEquals(343, rule500.frameCount)
        assertEquals(343 * 21.0, rule500.actualDuration, 1e-9)
        // Near the pole stars trail less, so the exposure grows: declination 60° doubles the NPF figure to 18.17 s.
        val polar = StarTrailPlanner.stackingPlan(7200.0, 60.0, wide)
        assertEquals(2.0, polar.estimate.declinationFactor, 1e-12)
        assertEquals(18.0, polar.exposureSeconds)
        assertEquals(379, polar.frameCount)
        assertEquals(60.0, polar.trail.declination)
        assertEquals(polar.trail.rotationDegrees * 0.5, polar.trail.arcLengthDegrees, 1e-9)
        // The caller's own exposure and gap win.
        val own = StarTrailPlanner.stackingPlan(7200.0, null, wide, exposureSeconds = 15.0, gapSeconds = 1.0)
        assertEquals(15.0, own.exposureSeconds)
        assertEquals(450, own.frameCount)
        val noGap = StarTrailPlanner.stackingPlan(3600.0, null, wide, exposureSeconds = 10.0, gapSeconds = 0.0)
        assertEquals(360, noGap.frameCount)
        assertEquals(0.0, noGap.gapPixels)
        // A short stack still takes one frame.
        assertEquals(1, StarTrailPlanner.stackingPlan(5.0, null, wide).frameCount)
        assertEquals(2, StarTrailPlanner.stackingPlan(10.5, null, wide).frameCount)
    }

    @Test
    fun testPracticalExposure() {
        assertEquals(9.0, StarTrailPlanner.practicalExposure(9.083))
        assertEquals(2.0, StarTrailPlanner.practicalExposure(2.0))
        assertEquals(1.5, StarTrailPlanner.practicalExposure(1.57))
        assertEquals(0.1, StarTrailPlanner.practicalExposure(0.1))
        assertEquals(0.043, StarTrailPlanner.practicalExposure(0.043))
        assertEquals(1.9, StarTrailPlanner.practicalExposure(1.99))
    }

    @Test
    fun testStackingPlanErrors() {
        assertFailsWith<StarTrailError.InvalidDuration> { StarTrailPlanner.stackingPlan(0.0, null, wide) }
        assertFailsWith<StarTrailError.InvalidDuration> { StarTrailPlanner.stackingPlan(90_000.0, null, wide) }
        assertEquals(StarTrailError.InvalidDeclination(95.0), assertFailsWith<StarTrailError.InvalidDeclination> { StarTrailPlanner.stackingPlan(60.0, 95.0, wide) })
        assertEquals(StarTrailError.InvalidExposure(0.0), assertFailsWith<StarTrailError.InvalidExposure> { StarTrailPlanner.stackingPlan(60.0, null, wide, exposureSeconds = 0.0) })
        assertFailsWith<StarTrailError.InvalidExposure> { StarTrailPlanner.stackingPlan(60.0, null, wide, exposureSeconds = 4000.0) }
        assertFailsWith<StarTrailError.InvalidExposure> { StarTrailPlanner.stackingPlan(60.0, null, wide, exposureSeconds = Double.NaN) }
        assertEquals(StarTrailError.InvalidGap(-1.0), assertFailsWith<StarTrailError.InvalidGap> { StarTrailPlanner.stackingPlan(60.0, null, wide, gapSeconds = -1.0) })
        assertFailsWith<StarTrailError.InvalidGap> { StarTrailPlanner.stackingPlan(60.0, null, wide, gapSeconds = 61.0) }
        assertFailsWith<StarTrailError.InvalidGap> { StarTrailPlanner.stackingPlan(60.0, null, wide, gapSeconds = Double.NaN) }
        val tooMany = assertFailsWith<StarTrailError.TooManyFrames> {
            StarTrailPlanner.stackingPlan(86_400.0, null, wide, exposureSeconds = 0.5, gapSeconds = 0.0)
        }
        assertEquals(StarTrailPlanner.maximumFrameCount, tooMany.limit)
        assertEquals(172_800, tooMany.count)
        assertNotNull(StarTrailError.NoMotionAtThePole.message)
    }

    // MARK: The pole

    @Test
    fun testPoleIsAtTheLatitude() {
        val frame = SkyFrame(date, boulder)
        val pole = StarTrailPlanner.pole(frame, refraction = false)
        assertEquals(PolarAlignment.Hemisphere.NORTH, pole.hemisphere)
        assertEquals(40.015, pole.direction.altitude, 0.05)
        assertEquals(0.0, pole.direction.azimuth, 1e-6)
        assertTrue(pole.isAboveHorizon)
        // The pole of the reading matches the polar alignment helper.
        assertEquals(PolarAlignment.polarScopeReading(frame, PolarAlignment.Hemisphere.NORTH).poleAltitude, pole.direction.altitude, 1e-9)
        // Refraction lifts it by about a minute of arc.
        val refracted = StarTrailPlanner.pole(frame)
        assertTrue(refracted.direction.altitude > pole.direction.altitude)
        assertEquals(pole.direction.altitude, refracted.direction.altitude, 0.05)
        // The other pole is below the horizon from here, at the same angle.
        val south = StarTrailPlanner.pole(frame, PolarAlignment.Hemisphere.SOUTH, refraction = false)
        assertEquals(-40.015, south.direction.altitude, 0.05)
        assertEquals(180.0, south.direction.azimuth, 1e-6)
        assertTrue(!south.isAboveHorizon)
        // From the southern hemisphere the south pole is the default.
        val sydney = StarTrailPlanner.pole(SkyFrame(date, Observer(GeographicLocation(-33.87, 151.21, 20.0))), refraction = false)
        assertEquals(PolarAlignment.Hemisphere.SOUTH, sydney.hemisphere)
        assertEquals(33.87, sydney.direction.altitude, 0.05)
        assertEquals(180.0, sydney.direction.azimuth, 1e-6)
    }

    @Test
    fun testPolePositionInTheFrame() {
        val frame = SkyFrame(date, boulder)
        val pole = StarTrailPlanner.pole(frame, refraction = false).direction
        val landscape = FieldOfView.of(SensorFormat.fullFrame, 24.0)
        // Aimed at the pole it is in the middle.
        val centered = assertNotNull(StarTrailPlanner.polePosition(pole, pole, landscape))
        assertEquals(0.0, centered.x, 1e-9)
        assertEquals(0.0, centered.y, 1e-9)
        assertEquals(0.0, centered.separation, 1e-6)
        assertTrue(centered.isInFrame)
        // Aimed 5° below the pole the pole is above the middle, a tenth of the way up in half-frame units.
        val below = HorizontalCoordinate(pole.azimuth, pole.altitude - 5.0)
        val above = assertNotNull(StarTrailPlanner.polePosition(pole, below, landscape))
        assertEquals(0.0, above.x, 1e-9)
        assertEquals(tan(5.0 * PI / 180) / tan(landscape.vertical / 2 * PI / 180), above.y, 1e-9)
        assertEquals(5.0, above.separation, 1e-9)
        // Rolled a quarter turn clockwise the top of the picture points to the right and the pole falls to the left.
        val rolled = assertNotNull(StarTrailPlanner.polePosition(pole, below, landscape, roll = 90.0))
        assertEquals(-tan(5.0 * PI / 180) / tan(landscape.horizontal / 2 * PI / 180), rolled.x, 1e-9)
        assertEquals(0.0, rolled.y, 1e-9)
        // Portrait: the frame is turned, with the long side up.
        val portrait = assertNotNull(StarTrailPlanner.polePosition(pole, below, landscape.rotated))
        assertEquals(tan(5.0 * PI / 180) / tan(landscape.horizontal / 2 * PI / 180), portrait.y, 1e-9)
        // 30° away it is outside a 24 mm frame; behind the camera there is no position at all.
        val away = assertNotNull(StarTrailPlanner.polePosition(pole, HorizontalCoordinate(pole.azimuth, pole.altitude - 35.0), landscape))
        assertTrue(!away.isInFrame)
        assertTrue(away.y > 1.0)
        assertNull(StarTrailPlanner.polePosition(pole, HorizontalCoordinate(180.0, 10.0), landscape))
        assertNull(StarTrailPlanner.polePosition(pole, HorizontalCoordinate(90.0, 0.0), landscape))
        // Pointing at the zenith still works: the picture is turned with its top to the north.
        assertNotNull(StarTrailPlanner.polePosition(pole, HorizontalCoordinate(0.0, 90.0), FieldOfView.of(SensorFormat.fullFrame, 14.0)))
        assertFailsWith<StarTrailError.InvalidFrame> { StarTrailPlanner.polePosition(pole, pole, FrameSize(0.0, 10.0, 10.0)) }
        assertFailsWith<StarTrailError.InvalidFrame> { StarTrailPlanner.polePosition(pole, pole, FrameSize(10.0, 180.0, 10.0)) }
    }
}
