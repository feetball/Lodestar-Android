package net.kuecker.lodestar.kit.mosaic

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.CoordinateTransforms
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import net.kuecker.lodestar.kit.pointing.GuidanceState
import net.kuecker.lodestar.kit.pointing.GuidanceTracker
import net.kuecker.lodestar.kit.pointing.MountProfile
import net.kuecker.lodestar.kit.pointing.PointingSolution
import net.kuecker.lodestar.kit.pointing.PointingTestSupport as Support
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MosaicGuidanceTests {
    private val boulder = Observer(GeographicLocation(latitude = 40.015, longitude = -105.27, elevation = 1650.0))

    /** 2026-10-17 04:00 UTC, about 22:00 the evening before in Colorado: right ascension 22.7 h is on the meridian. */
    private val date = instantFromTimeIntervalSince1970(1_792_209_600.0)

    private val region = MosaicRegion.Equatorial(EquatorialCoordinate.fromRightAscensionHours(22.7, 20.0), 60.0, 30.0)

    private fun plan(positionAngle: Double = 0.0): MosaicPlan = MosaicPlanner.plan(
        MosaicRegion.Equatorial(EquatorialCoordinate.fromRightAscensionHours(22.7, 20.0), 60.0, 30.0, positionAngle),
        MosaicTestSupport.frame(50.0),
    )

    // MARK: Directions

    @Test
    fun testEquatorialPanelsPointWhereTheSkyFrameSaysTheyAre() {
        val plan = plan()
        val frame = SkyFrame(date, boulder)
        val pointings = MosaicGuidance.pointings(plan, frame)
        assertEquals(plan.panelCount, pointings.size)
        assertTrue(plan.panelCount >= 4)
        for ((index, pointing) in pointings.withIndex()) {
            val panel = plan.panels[index]
            assertEquals(index, pointing.panelIndex)
            assertEquals(frame.horizontal(panel.center.equatorialCoordinate!!, null, true), pointing.direction)
            assertEquals(panel.corners.map { frame.horizontal(it.equatorialCoordinate!!, null, true) }, pointing.corners)
            assertEquals(pointing, MosaicGuidance.pointing(panel, plan, frame))
            assertEquals(pointing.direction.altitude > 0, pointing.isAboveHorizon)
            assertTrue(pointing.lowestAltitude <= pointing.direction.altitude)
        }
        val geometric = MosaicGuidance.pointings(plan, frame, refraction = false)
        for ((index, pointing) in geometric.withIndex()) {
            assertEquals(frame.horizontal(plan.panels[index].center.equatorialCoordinate!!, null, false), pointing.direction)
        }
    }

    @Test
    fun testRefractionRaisesPanelsNearTheHorizon() {
        val low = MosaicPlanner.plan(
            MosaicRegion.Equatorial(EquatorialCoordinate.fromRightAscensionHours(22.7, -45.0), 10.0, 10.0),
            MosaicTestSupport.frame(50.0),
        )
        val frame = SkyFrame(date, boulder)
        val refracted = MosaicGuidance.pointing(low.panels[0], low, frame, refraction = true)
        val geometric = MosaicGuidance.pointing(low.panels[0], low, frame, refraction = false)
        assertTrue(geometric.direction.altitude < 10.0)
        assertTrue(refracted.direction.altitude > geometric.direction.altitude)
        assertEquals(geometric.direction.azimuth, refracted.direction.azimuth, 1e-9)
        val high = plan()
        val middle = high.panels.maxByOrNull { MosaicGuidance.pointing(it, high, frame, false).direction.altitude }!!
        val highRefracted = MosaicGuidance.pointing(middle, high, frame, true).direction.altitude
        val highGeometric = MosaicGuidance.pointing(middle, high, frame, false).direction.altitude
        assertEquals(highGeometric, highRefracted, 0.02)
    }

    @Test
    fun testHorizontalPanelsStayWhereTheyAreAndStayLevel() {
        val horizontal = MosaicPlanner.plan(MosaicRegion.Horizontal(150.0, 110.0, 0.0, 60.0), MosaicTestSupport.frame(24.0))
        val first = MosaicGuidance.pointings(horizontal, SkyFrame(date, boulder))
        val later = MosaicGuidance.pointings(horizontal, SkyFrame(instantFromTimeIntervalSince1970(1_792_209_600.0 + 7200), boulder))
        assertEquals(first, later)
        for ((index, pointing) in first.withIndex()) {
            assertEquals(horizontal.panels[index].center.horizontalCoordinate, pointing.direction)
            assertEquals(horizontal.panels[index].corners.map { it.horizontalCoordinate!! }, pointing.corners)
            assertEquals(0.0, pointing.roll)
            assertTrue(pointing.isAboveHorizon)
            assertTrue(pointing.lowestAltitude < pointing.direction.altitude)
        }
    }

    @Test
    fun testPanelsBelowTheHorizonAreFlagged() {
        val south = MosaicPlanner.plan(
            MosaicRegion.Equatorial(EquatorialCoordinate.fromRightAscensionHours(10.0, -70.0), 20.0, 10.0),
            MosaicTestSupport.frame(50.0),
        )
        val frame = SkyFrame(date, boulder)
        for (pointing in MosaicGuidance.pointings(south, frame)) {
            assertTrue(!pointing.isAboveHorizon)
            assertTrue(pointing.lowestAltitude < 0)
        }
        val partly = MosaicPlanner.plan(MosaicRegion.Horizontal(150.0, 110.0, -10.0, 20.0), MosaicTestSupport.frame(24.0))
        val pointing = MosaicGuidance.pointings(partly, frame).first()
        assertTrue(pointing.isAboveHorizon)
        assertTrue(pointing.lowestAltitude < 0)
    }

    // MARK: Roll

    @Test
    fun testRollEqualsTheParallacticAngleOfThePanel() {
        val plan = plan()
        var west = 0
        var east = 0
        for (hours in listOf(0.0, 2.0, 5.0)) {
            val frame = SkyFrame(instantFromTimeIntervalSince1970(1_792_209_600.0 + hours * 3600), boulder)
            for (pointing in MosaicGuidance.pointings(plan, frame)) {
                val panel = plan.panels[pointing.panelIndex]
                val apparent = frame.apparentPlace(panel.center.equatorialCoordinate!!)
                val hourAngle = frame.hourAngle(apparent.rightAscension)
                val expected = MosaicGuidance.parallacticAngle(hourAngle, apparent.declination, boulder.location.latitude)
                // The plan's pole is the J2000 pole; the pole of date is up to 0.4° away.
                assertEquals(0.0, AstroMath.normalizeSignedDegrees(pointing.roll - expected), 0.5, "panel ${panel.id} at +$hours h")
                if (expected > 5) {
                    assertTrue(pointing.roll > 0)
                    west += 1
                }
                if (expected < -5) {
                    assertTrue(pointing.roll < 0)
                    east += 1
                }
            }
        }
        assertTrue(west > 0 && east > 0)
    }

    @Test
    fun testRollTurnsTheTopOfThePictureTowardsThePole() {
        val plan = plan()
        val frame = SkyFrame(date, boulder)
        val pole = frame.horizontalOfCelestialPole(north = true, refraction = false).unitVector
        for (pointing in MosaicGuidance.pointings(plan, frame, refraction = false)) {
            val camera = Support.cameraFrame(pointing.direction, pointing.roll)
            val forward = pointing.direction.unitVector
            val towardsPole = (pole - forward * pole.dot(forward)).normalized
            assertEquals(0.0, camera.up.angle(towardsPole), 0.5, "panel ${pointing.panelIndex}")
        }
    }

    @Test
    fun testParallacticAngleKnownValues() {
        // On the meridian the pole is straight up or down.
        assertEquals(0.0, MosaicGuidance.parallacticAngle(0.0, 20.0, 40.0), 1e-9)
        assertEquals(0.0, MosaicGuidance.parallacticAngle(0.0, -30.0, 40.0), 1e-9)
        // On the equator a star on the celestial equator six hours west is on the horizon in the west: the pole is to the right.
        assertEquals(90.0, MosaicGuidance.parallacticAngle(90.0, 0.0, 0.0), 1e-9)
        assertEquals(-90.0, MosaicGuidance.parallacticAngle(-90.0, 0.0, 0.0), 1e-9)
        // Meeus, Astronomical Algorithms, chapter 14: tan q = sin H / (tan φ cos δ − sin δ cos H).
        val meeus = AstroMath.atan2Deg(
            AstroMath.sinDeg(45.0),
            kotlin.math.tan(AstroMath.radians(40.0)) * AstroMath.cosDeg(20.0) - AstroMath.sinDeg(20.0) * AstroMath.cosDeg(45.0),
        )
        assertEquals(meeus, MosaicGuidance.parallacticAngle(45.0, 20.0, 40.0), 1e-9)
        // It is also the angle between the directions to the pole and the zenith, measured on the sky.
        for (latitude in listOf(-60.0, -20.0, 0.0, 35.0, 70.0)) {
            for (declination in listOf(-50.0, -10.0, 15.0, 60.0)) {
                for (hourAngle in listOf(-150.0, -75.0, -20.0, 15.0, 80.0, 140.0)) {
                    val star = CoordinateTransforms.equatorialToHorizontal(hourAngle, declination, latitude).unitVector
                    val nearby = CoordinateTransforms.equatorialToHorizontal(hourAngle, declination + 0.05, latitude).unitVector
                    val towardsPole = (nearby - star * nearby.dot(star)).normalized
                    val level = (Vector3(0.0, 0.0, 1.0) - star * star.z)
                    if (level.length < 1e-3) continue
                    val up = level.normalized
                    val measured = AstroMath.atan2Deg(towardsPole.dot(star.cross(up)), towardsPole.dot(up))
                    val formula = MosaicGuidance.parallacticAngle(hourAngle, declination, latitude)
                    assertEquals(0.0, AstroMath.normalizeSignedDegrees(measured - formula), 0.05, "lat $latitude dec $declination H $hourAngle")
                }
            }
        }
    }

    @Test
    fun testPositionAngleTurnsTheRollWithIt() {
        // With one panel at the centre, the top of the picture points along the position angle: turning the region
        // by an angle east of north turns the picture counter-clockwise on the sky, and the roll falls by that angle.
        val frame = SkyFrame(date, boulder)
        val center = EquatorialCoordinate.fromRightAscensionHours(23.5, 20.0)
        fun roll(positionAngle: Double): Double {
            val single = MosaicPlanner.plan(MosaicRegion.Equatorial(center, 2.0, 2.0, positionAngle), MosaicTestSupport.frame(50.0))
            assertEquals(1, single.panelCount)
            return MosaicGuidance.pointing(single.panels[0], single, frame).roll
        }
        val base = roll(0.0)
        for (angle in listOf(10.0, 45.0, 90.0, 170.0, -30.0)) {
            assertEquals(0.0, AstroMath.normalizeSignedDegrees(roll(angle) - (base - angle)), 1e-3, "angle $angle")
        }
    }

    @Test
    fun testCameraRollRoundTripsThroughACameraFrame() {
        // The roll of a camera is the roll it was built with; the zenith has no level direction.
        for (boresight in listOf(
            HorizontalCoordinate(0.0, 0.0), HorizontalCoordinate(120.0, 35.0), HorizontalCoordinate(250.0, -20.0),
            HorizontalCoordinate(40.0, 80.0), HorizontalCoordinate(300.0, 89.0),
        )) {
            for (roll in listOf(-170.0, -90.0, -33.3, 0.0, 12.5, 90.0, 179.0)) {
                val camera = Support.cameraFrame(boresight, roll)
                assertEquals(roll, MosaicGuidance.roll(camera.forward, camera.up), 1e-6, "$boresight $roll")
                // The scale of the vectors and a part of up along the axis do not matter.
                assertEquals(roll, MosaicGuidance.roll(camera.forward * 3.0, camera.up * 0.5 + camera.forward * 2.0), 1e-6)
            }
        }
        val zenith = Support.cameraFrame(HorizontalCoordinate(0.0, 90.0), 30.0)
        assertEquals(0.0, MosaicGuidance.roll(zenith.forward, zenith.up))
        assertEquals(0.0, MosaicGuidance.roll(Vector3(1.0, 0.0, 0.0), Vector3(2.0, 0.0, 0.0)))
        // For a panel it is the roll of the pointing: the camera built from the pointing has that roll.
        val plan = plan()
        val frame = SkyFrame(date, boulder)
        for (pointing in MosaicGuidance.pointings(plan, frame, refraction = false)) {
            val camera = Support.cameraFrame(pointing.direction, pointing.roll)
            assertEquals(pointing.roll, MosaicGuidance.roll(camera.forward, camera.up), 1e-6)
        }
    }

    @Test
    fun testRollNearThePoleIsFinite() {
        val polar = MosaicPlanner.plan(
            MosaicRegion.Equatorial(EquatorialCoordinate.fromRightAscensionHours(3.0, 85.0), 30.0, 15.0),
            MosaicTestSupport.frame(35.0),
        )
        val frame = SkyFrame(date, boulder)
        for (pointing in MosaicGuidance.pointings(polar, frame)) {
            assertTrue(pointing.roll.isFinite() && abs(pointing.roll) <= 180.0)
            assertTrue(pointing.direction.altitude > 10)
        }
    }

    // MARK: The aiming guidance

    @Test
    fun testAimingGuidanceReachesEveryPanelInShootingOrder() {
        // What the Aim screen does: take the panels in shooting order, ask for their direction at the current time,
        // hand it to the guidance as the target, and move on once it reports on target.
        val plan = MosaicPlanner.plan(region, MosaicTestSupport.frame(50.0), observerLatitude = boulder.location.latitude)
        val frame = SkyFrame(date, boulder)
        val mount = MountProfile.backCameraPortrait
        val tracker = GuidanceTracker(onTargetThreshold = 0.5)
        var reached = 0
        for (panel in plan.orderedPanels) {
            val pointing = MosaicGuidance.pointing(panel, plan, frame)
            tracker.reset()
            // A camera pointing 8° to the side is far from the panel...
            val off = HorizontalCoordinate(pointing.direction.azimuth + 8.0, pointing.direction.altitude)
            val offAttitude = Support.attitude(mount, frame = Support.cameraFrame(off, pointing.roll))
            val away = assertNotNull(tracker.update(PointingSolution.solve(offAttitude, mount), pointing.direction))
            assertTrue(away.state != GuidanceState.ON_TARGET)
            // ...and on target once it is aimed at the direction, with the roll of the panel.
            val attitude = Support.attitude(mount, frame = Support.cameraFrame(pointing.direction, pointing.roll))
            val guidance = assertNotNull(tracker.update(PointingSolution.solve(attitude, mount), pointing.direction))
            assertEquals(GuidanceState.ON_TARGET, guidance.state, "panel ${panel.id}")
            reached += 1
        }
        assertEquals(plan.panelCount, reached)
    }

    @Test
    fun testPointingAWholePlanIsFast() {
        val plan = MosaicPlanner.plan(
            MosaicRegion.Equatorial(EquatorialCoordinate.fromRightAscensionHours(22.7, 20.0), 100.0, 60.0),
            MosaicTestSupport.frame(35.0),
        )
        val frame = SkyFrame(date, boulder)
        MosaicGuidance.pointings(plan, frame)
        val start = System.nanoTime()
        repeat(100) { MosaicGuidance.pointings(plan, frame) }
        val perCallMilliseconds = (System.nanoTime() - start) / 100.0 / 1e6
        assertTrue(perCallMilliseconds < 20.0, "$perCallMilliseconds ms for ${plan.panelCount} panels")
    }
}
