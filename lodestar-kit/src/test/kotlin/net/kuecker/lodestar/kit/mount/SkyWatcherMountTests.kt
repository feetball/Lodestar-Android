package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.alnair
import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.albireo
import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.alpheratz
import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.altair
import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.andromeda
import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.capella
import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.deneb
import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.enif
import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.hamal
import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.markab
import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.mirfak
import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.rasalhague
import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.vega
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests of the Sky-Watcher Wi-Fi driver with the [MountController] against the [SkyWatcherSimulator]: connection,
 * alignment on one to three stars with synthetic mis-pointing, GoTo accuracy, pier side and meridian handling, tracking, nudges,
 * abort, and recovery from lost and damaged datagrams.
 */
class SkyWatcherMountTests {
    private val altAz = SkyWatcherMountMode.ALT_AZIMUTH
    private val equatorial = SkyWatcherMountMode.EQUATORIAL

    private val altAzimuthAlignmentStars = listOf(vega, mirfak, enif)
    private val equatorialAlignmentStars = listOf(vega, mirfak, enif)
    private val gotoTargets = listOf(deneb, altair, andromeda, markab, alpheratz, albireo, hamal, capella, rasalhague)

    private fun connected(
        mode: SkyWatcherMountMode,
        errors: SkyWatcherPointingErrors = SkyWatcherPointingErrors(),
        configure: (SkyWatcherSimulator) -> Unit = {},
    ): SkyWatcherRig {
        val rig = SkyWatcherTestSupport.rig(mode, errors, configure = configure)
        rig.controller.connect()
        return rig
    }

    private fun aligned(rig: SkyWatcherRig, stars: List<EquatorialCoordinate>): SkyWatcherRig {
        for (star in stars) rig.syncOn(star)
        return rig
    }

    /** Goes to [target], waits for the end of the slew and returns how far the real telescope is from it. */
    private fun goto(rig: SkyWatcherRig, target: EquatorialCoordinate): Double {
        rig.controller.goto(target)
        val status = rig.controller.awaitSettled()
        assertTrue(status.outcome is MountSlewOutcome.Arrived, "${status.outcome}")
        return rig.errorTo(target)
    }

    private fun east(place: EquatorialCoordinate): Vector3 {
        val ra = AstroMath.radians(place.rightAscension)
        return Vector3(-kotlin.math.sin(ra), kotlin.math.cos(ra), 0.0)
    }

    private fun north(place: EquatorialCoordinate): Vector3 {
        val ra = AstroMath.radians(place.rightAscension)
        val dec = AstroMath.radians(place.declination)
        return Vector3(-kotlin.math.sin(dec) * kotlin.math.cos(ra), -kotlin.math.sin(dec) * kotlin.math.sin(ra), kotlin.math.cos(dec))
    }

    // MARK: Connecting

    @Test
    fun testConnectInitializesTheMountAndReportsWhatItIs() {
        val rig = SkyWatcherTestSupport.rig(altAz)
        assertFalse(rig.simulator.isInitialized)
        val capabilities = rig.controller.connect()
        assertTrue(rig.simulator.isInitialized)
        assertEquals(MountProtocol.SKYWATCHER, capabilities.kind)
        assertEquals("Sky-Watcher AZ-GTi 3.22", capabilities.name)
        assertEquals("3.22", capabilities.firmware)
        assertTrue(capabilities.canSlew && capabilities.canSync && capabilities.canAbort)
        assertTrue(capabilities.canSetTracking && capabilities.canNudge && capabilities.canSetSiteAndTime)
        assertTrue(capabilities.gotoNeedsAlignment)
        assertEquals(false, capabilities.isAligned)
        assertEquals(MountPhase.IDLE, rig.controller.status.phase)
        assertEquals(MountEpoch.APPARENT_OF_DATE, rig.driver.epoch)
        assertEquals(MountProtocol.SKYWATCHER, rig.driver.kind)
        assertEquals(SkyWatcherVersion(3, 0x22, 0xA5), rig.driver.version)
        assertEquals(altAz, rig.driver.mountMode)
        assertEquals(SkyWatcherTrackingStrategy.SERVO, rig.driver.trackingStrategy)
        assertEquals(SkyWatcherAxisAngles(0.0, 0.0), rig.driver.startupAxisAngles)
        assertEquals("Sky-Watcher Wi-Fi (AZ-GTi, Star Adventurer GTi)", SkyWatcherDriver.menuTitle)
        // Home: level, pointing north.
        val position = assertNotNull(rig.controller.status.position)
        val horizontal = rig.frame().horizontal(position, null, false)
        assertEquals(0.0, horizontal.altitude, 0.01)
        assertTrue(horizontal.azimuth < 0.01 || horizontal.azimuth > 359.99, "${horizontal.azimuth}")
        assertEquals(capabilities, rig.controller.connect())
        // The datagrams of the handshake: version, 2 x counts, timer, 2 x ratio, status (twice: init), init, position.
        assertTrue(rig.driver.datagramsSent in 10..20, "${rig.driver.datagramsSent}")
        assertEquals(0, rig.driver.retries)
    }

    @Test
    fun testTheMountModelPicksTheArrangement() {
        val location = SkyWatcherTestSupport.boulderLocation
        fun mode(code: Int, configuredMode: SkyWatcherMountMode? = null): SkyWatcherMountMode? {
            val rig = SkyWatcherTestSupport.rig(
                altAz, version = SkyWatcherVersion(3, 0x10, code), configuration = SkyWatcherConfiguration(location, mode = configuredMode),
            )
            rig.controller.connect()
            return rig.driver.mountMode
        }
        assertEquals(altAz, mode(0xA5))
        assertEquals(altAz, mode(0xA2))
        assertEquals(equatorial, mode(0x0C))
        assertEquals(equatorial, mode(0x23))
        assertEquals(equatorial, mode(0x00))
        assertEquals(equatorial, mode(0x77))
        assertEquals(equatorial, mode(0xA5, SkyWatcherMountMode.EQUATORIAL))
        assertEquals(altAz, mode(0x0C, SkyWatcherMountMode.ALT_AZIMUTH))
        val eq = SkyWatcherTestSupport.rig(equatorial)
        eq.controller.connect()
        assertEquals(SkyWatcherTrackingStrategy.SIDEREAL, eq.driver.trackingStrategy)
        assertEquals("Sky-Watcher Star Adventurer GTi 3.40", eq.controller.capabilities?.name)
        // An alt-azimuth mount cannot track at the sidereal rate on one axis, whatever is asked for.
        val forced = SkyWatcherTestSupport.rig(
            altAz, configuration = SkyWatcherConfiguration(location, mode = altAz, trackingStrategy = SkyWatcherTrackingStrategy.SIDEREAL),
        )
        forced.controller.connect()
        assertEquals(SkyWatcherTrackingStrategy.SERVO, forced.driver.trackingStrategy)
    }

    @Test
    fun testConnectStopsAMountThatIsMovingAndReportsItsStartPosition() {
        val rig = SkyWatcherTestSupport.rig(altAz)
        // Somebody left the mount turned and running.
        rig.simulator.placeAxes(40.0, 10.0)
        for (command in listOf(":F3\r", ":G110\r", ":G210\r", ":I11C3600\r", ":I21C3600\r", ":J3\r")) {
            rig.simulator.send(MountText.latin1Bytes(command))
            rig.simulator.discardPendingInput()
        }
        assertTrue(rig.simulator.isMoving)
        rig.controller.connect()
        assertFalse(rig.simulator.isMoving)
        val start = assertNotNull(rig.driver.startupAxisAngles)
        assertEquals(40.0, start.axis1Degrees, 0.01)
        assertEquals(10.0, start.axis2Degrees, 0.01)
    }

    @Test
    fun testDeclaringHomeRedefinesTheEncoderZero() {
        // The mount was switched on 40° and 10° away from its home position; the user brought it home with the nudge pad (here: the
        // simulated mount really is at home, and its encoders say 40° and 10°).
        val rig = SkyWatcherTestSupport.rig(altAz, SkyWatcherPointingErrors(axis1Index = -40.0, axis2Index = -10.0))
        rig.simulator.placeAxes(40.0, 10.0)
        rig.controller.connect()
        val start = assertNotNull(rig.driver.startupAxisAngles)
        assertEquals(40.0, start.axis1Degrees, 0.01)
        rig.syncOn(vega)
        assertEquals(1, rig.driver.alignmentPoints.size)
        // (The mount went to Vega for that sync and is now brought back home: its encoders read 40° and 10° again.)
        rig.simulator.placeAxes(40.0, 10.0)
        rig.driver.declareHomePosition()
        // The encoders read zero, the alignment is gone, the telescope did not move and points where the ideal geometry says.
        assertEquals(SkyWatcherAxisAngles(0.0, 0.0), rig.driver.startupAxisAngles)
        assertEquals(0, rig.driver.alignmentPoints.size)
        assertEquals(0.0, rig.simulator.axis1Degrees, 1e-3)
        assertEquals(false, rig.driver.readTracking())
        assertFalse(rig.simulator.isMoving)
        val place = rig.driver.readPosition()
        assertTrue(rig.simulator.truePlace().angularSeparation(place) < 1e-3)
        // With the home position known, one star finds next to no home error.
        rig.syncOn(vega)
        val fit = assertNotNull(rig.driver.alignmentFit)
        assertTrue(abs(fit.model.errors.axis1Index) < 0.01 && abs(fit.model.errors.axis2Index) < 0.01, "${fit.model.errors}")
        assertTrue(goto(rig, deneb) < 0.05)
        assertFailsWith<MountError.NotConnected> { SkyWatcherTestSupport.rig(altAz).driver.declareHomePosition() }
    }

    @Test
    fun testConnectFailuresAreReported() {
        val silent = SkyWatcherTestSupport.rig(altAz)
        silent.simulator.inject("e", MountSimulatedFault.Silence, times = 10)
        val before = silent.clock.secondsSince1970
        val error = assertFailsWith<MountError.Timeout> { silent.controller.connect() }
        assertTrue(error.isCommunicationFailure)
        // Three attempts of 0.6 s each.
        assertEquals(1.8, silent.clock.secondsSince1970 - before, 0.2)
        assertEquals(MountPhase.DISCONNECTED, silent.controller.status.phase)
        val garbled = SkyWatcherTestSupport.rig(altAz)
        garbled.simulator.inject("a", MountSimulatedFault.Replace("=000000\r"), times = 10)
        assertFailsWith<MountError.MalformedReply> { garbled.controller.connect() }
        val refused = SkyWatcherTestSupport.rig(altAz)
        refused.simulator.inject("e", MountSimulatedFault.Replace("!5\r"), times = 10)
        assertEquals(MountError.Standby, assertFailsWith<MountError> { refused.controller.connect() })
        assertFailsWith<MountError.NotConnected> { silent.driver.readPosition() }
        assertFailsWith<MountError.NotConnected> { silent.driver.slew(deneb) }
    }

    // MARK: Alignment and GoTo accuracy

    @Test
    fun testGotoNeedsAnAlignment() {
        val rig = connected(altAz, SkyWatcherTestSupport.altAzimuthErrors)
        assertFailsWith<MountError.NotAligned> { rig.controller.goto(deneb) }
        assertEquals(0, rig.simulator.gotosStarted)
        assertFalse(rig.controller.poll().isAligned == true)
        rig.syncOn(vega)
        assertEquals(true, rig.controller.poll().isAligned)
        assertEquals(1, rig.driver.alignmentPoints.size)
        // With the permission, the mount goes by the ideal geometry: accurate only as far as the mount was set up.
        val perfect = SkyWatcherTestSupport.rig(
            altAz, configuration = SkyWatcherConfiguration(SkyWatcherTestSupport.boulderLocation, mode = altAz, allowsGotoWithoutAlignment = true),
        )
        val capabilities = perfect.controller.connect()
        assertFalse(capabilities.gotoNeedsAlignment)
        assertTrue(goto(perfect, deneb) < 0.05)
    }

    @Test
    fun testOneStarFindsTheHomeError() {
        for (mode in listOf(altAz, equatorial)) {
            val errors = SkyWatcherPointingErrors(axis1Index = 3.1, axis2Index = -2.2)
            val rig = connected(mode, errors)
            // The home position was off: the mount believes it points at the pole / north, it really is a few degrees away.
            val result = rig.syncOn(vega)
            assertTrue(result.offsetDegrees > 2.0, "$mode ${result.offsetDegrees}")
            val fit = assertNotNull(rig.driver.alignmentFit)
            assertTrue(fit.maximumResidualDegrees < 1e-6)
            // The star moved a little between the placing and the sync (the mount does not track yet): 0.15 s of sky.
            assertEquals(3.1, fit.model.errors.axis1Index, 1e-3, "$mode")
            assertEquals(-2.2, fit.model.errors.axis2Index, 1e-3, "$mode")
            assertEquals(0.0, fit.model.errors.baseTiltX, 1e-6)
            assertEquals(0.0, assertNotNull(rig.controller.status.position).angularSeparation(vega), 0.002)
            // The index offsets are all that is wrong, so every target is hit.
            for (target in gotoTargets) {
                val error = goto(rig, target)
                assertTrue(error < 0.05, "$mode $target $error")
            }
        }
    }

    @Test
    fun testThreeStarsGotoWithinATenthOfADegree() {
        for (mode in listOf(altAz, equatorial)) {
            val errors = if (mode == altAz) SkyWatcherTestSupport.altAzimuthErrors else SkyWatcherTestSupport.equatorialErrors
            val rig = connected(mode, errors)
            val stars = if (mode == altAz) altAzimuthAlignmentStars else equatorialAlignmentStars
            aligned(rig, stars)
            val fit = assertNotNull(rig.driver.alignmentFit)
            assertEquals(3, rig.driver.alignmentPoints.size)
            assertTrue(fit.maximumResidualDegrees < 0.05, "$mode ${fit.residualsDegrees}")
            var worst = 0.0
            for (target in gotoTargets) {
                val error = goto(rig, target)
                worst = maxOf(worst, error)
                assertTrue(error < 0.1, "$mode $target $error")
            }
            assertTrue(worst < 0.1, "$mode worst $worst")
        }
    }

    @Test
    fun testMoreStarsGiveABetterModel() {
        // The errors that one star cannot see: base tilt, orthogonality and cone. Each added star improves the worst error over the sky
        // (two stars cannot always separate them on an equatorial mount, but never make things much worse).
        for (mode in listOf(altAz, equatorial)) {
            val errors = SkyWatcherPointingErrors(axis1Index = 2.0, axis2Index = -1.0, baseTiltX = 0.6, baseTiltY = -0.5, orthogonality = 0.2, cone = 0.1)
            val worstByStars = ArrayList<Double>()
            val predictedByStars = ArrayList<Double>()
            for (count in 1..3) {
                val rig = connected(mode, errors)
                aligned(rig, equatorialAlignmentStars.take(count))
                var worst = 0.0
                for (target in gotoTargets) worst = maxOf(worst, goto(rig, target))
                worstByStars.add(worst)
                predictedByStars.add(assertNotNull(rig.driver.alignmentFit).predictedErrorDegrees)
            }
            assertTrue(worstByStars[0] > 0.2, "$mode $worstByStars")
            assertTrue(worstByStars[1] < worstByStars[0] * 1.3, "$mode $worstByStars")
            assertTrue(worstByStars[2] < worstByStars[0] / 4, "$mode $worstByStars")
            assertTrue(worstByStars[2] < 0.1, "$mode $worstByStars")
            if (mode == altAz) assertTrue(worstByStars[1] < worstByStars[0] * 0.5, "$mode $worstByStars")
            assertTrue(predictedByStars[0] > predictedByStars[1] && predictedByStars[1] > predictedByStars[2], "$mode $predictedByStars")
        }
    }

    @Test
    fun testSouthernHemisphereEquatorialMount() {
        val observer = SkyWatcherTestSupport.sydney
        val rig = SkyWatcherTestSupport.rig(equatorial, SkyWatcherTestSupport.equatorialErrors, observer = observer)
        rig.controller.connect()
        for (star in listOf(SkyWatcherTestSupport.acrux, SkyWatcherTestSupport.nunki, SkyWatcherTestSupport.peacock)) rig.syncOn(star)
        assertTrue(assertNotNull(rig.driver.alignmentFit).maximumResidualDegrees < 0.05)
        assertTrue(assertNotNull(rig.driver.pointingModel).isSouthernHemisphere)
        for (target in listOf(SkyWatcherTestSupport.kaus, SkyWatcherTestSupport.shaula, SkyWatcherTestSupport.betaCentauri, SkyWatcherTestSupport.alnair)) {
            val error = goto(rig, target)
            assertTrue(error < 0.1, "$target $error")
        }
        // Tracking runs the other way round in the southern hemisphere, and keeps the target.
        val target = SkyWatcherTestSupport.betaCentauri
        goto(rig, target)
        SkyWatcherTestSupport.poll(rig, 300)
        assertTrue(rig.errorTo(target) < 0.05, "${rig.errorTo(target)}")
    }

    @Test
    fun testSouthernHemisphereAltAzimuthMount() {
        val rig = SkyWatcherTestSupport.rig(altAz, SkyWatcherTestSupport.altAzimuthErrors, observer = SkyWatcherTestSupport.sydney)
        rig.controller.connect()
        for (star in listOf(SkyWatcherTestSupport.acrux, SkyWatcherTestSupport.nunki, SkyWatcherTestSupport.peacock)) rig.syncOn(star)
        for (target in listOf(SkyWatcherTestSupport.kaus, SkyWatcherTestSupport.shaula, SkyWatcherTestSupport.betaCentauri, SkyWatcherTestSupport.alnair)) {
            val error = goto(rig, target)
            assertTrue(error < 0.1, "$target $error")
        }
        assertTrue(worstTrackingDrift(rig, 10) < 0.03)
    }

    @Test
    fun testSyncResidualsAreExposedAndTheSameStarReplacesItsPoint() {
        val rig = connected(altAz, SkyWatcherTestSupport.altAzimuthErrors)
        aligned(rig, altAzimuthAlignmentStars)
        val fit = assertNotNull(rig.driver.alignmentFit)
        assertEquals(3, fit.residualsDegrees.size)
        assertTrue(fit.residualsDegrees.all { it < 0.01 })
        assertTrue(fit.rmsDegrees < 0.01)
        assertTrue(rig.driver.alignmentPoints.all { it.timestamp >= SkyWatcherTestSupport.start })
        // Syncing a star again, ten minutes later, replaces its point (a better centring), it does not add a fourth.
        rig.clock.advance(600.0)
        rig.syncOn(vega)
        assertEquals(3, rig.driver.alignmentPoints.size)
        assertEquals(3, assertNotNull(rig.driver.alignmentFit).residualsDegrees.size)
        // Syncing the same place again right away changes next to nothing.
        val again = rig.controller.sync(vega)
        assertTrue(again.offsetDegrees < 0.01, "${again.offsetDegrees}")
    }

    @Test
    fun testAWrongStarIsRejected() {
        val rig = connected(altAz, SkyWatcherTestSupport.altAzimuthErrors)
        rig.syncOn(vega)
        rig.syncOn(mirfak)
        val modelBefore = assertNotNull(rig.driver.pointingModel)
        // The user centred Enif but tells the app it was Hamal.
        rig.placeOn(enif)
        val error = assertFailsWith<MountError.Rejected> { rig.controller.sync(hamal) }
        assertTrue(error.detail.contains("right star"), error.detail)
        assertEquals(2, rig.driver.alignmentPoints.size)
        assertEquals(modelBefore, rig.driver.pointingModel)
        // The right one is accepted.
        rig.controller.sync(enif)
        assertEquals(3, rig.driver.alignmentPoints.size)
        // And a mount that was not at home at all (home error of 70°) cannot be aligned with a plausible model.
        val lost = connected(equatorial, SkyWatcherPointingErrors(axis1Index = 70.0))
        lost.placeOn(vega)
        assertFailsWith<MountError.Rejected> { lost.controller.sync(vega) }
        assertEquals(0, lost.driver.alignmentPoints.size)
    }

    @Test
    fun testAlignmentCanBeManagedByTheApp() {
        val rig = connected(altAz, SkyWatcherTestSupport.altAzimuthErrors)
        aligned(rig, altAzimuthAlignmentStars)
        val points = rig.driver.alignmentPoints
        val predictedBefore = assertNotNull(rig.driver.alignmentFit).predictedErrorDegrees
        val worstBefore = gotoTargets.maxOf { goto(rig, it) }
        assertTrue(worstBefore < 0.1)
        rig.driver.removeAlignmentPoint(2)
        assertEquals(2, rig.driver.alignmentPoints.size)
        assertTrue(assertNotNull(rig.driver.alignmentFit).predictedErrorDegrees > predictedBefore)
        assertFailsWith<MountError.InvalidInput> { rig.driver.removeAlignmentPoint(5) }
        assertFailsWith<MountError.InvalidInput> { rig.driver.removeAlignmentPoint(-1) }
        rig.driver.removeAlignmentPoint(1)
        rig.driver.removeAlignmentPoint(0)
        assertEquals(0, rig.driver.alignmentPoints.size)
        assertNull(rig.driver.alignmentFit)
        assertFalse(rig.driver.readAligned())
        assertFailsWith<MountError.NotAligned> { rig.controller.goto(deneb) }
        rig.driver.restoreAlignment(points)
        assertEquals(3, rig.driver.alignmentPoints.size)
        assertTrue(goto(rig, deneb) < 0.1)
        rig.driver.clearAlignment()
        assertFailsWith<MountError.NotAligned> { rig.controller.goto(altair) }
        assertFailsWith<MountError.InvalidInput> { rig.driver.restoreAlignment(emptyList()) }
        // At most eight points are kept: the oldest go first.
        val many = connected(altAz, SkyWatcherPointingErrors(axis1Index = 1.0))
        for (step in 0 until 11) many.syncOn(EquatorialCoordinate(vega.rightAscension + step * 3.0, vega.declination - step))
        assertEquals(SkyWatcherAlignmentSolver.maximumPoints, many.driver.alignmentPoints.size)
    }

    @Test
    fun testSiteChangesKeepOrDropTheAlignment() {
        val rig = connected(altAz, SkyWatcherTestSupport.altAzimuthErrors)
        aligned(rig, altAzimuthAlignmentStars)
        val here = SkyWatcherTestSupport.boulderLocation
        // The same place with a better GPS fix: the alignment stays.
        rig.controller.setSiteAndTime(GeographicLocation(here.latitude + 0.01, here.longitude - 0.01, here.elevation))
        assertEquals(3, rig.driver.alignmentPoints.size)
        assertEquals(here.latitude + 0.01, rig.driver.location.latitude, 1e-12)
        // A different place: the points no longer describe the sky, the alignment is dropped.
        rig.controller.setSiteAndTime(GeographicLocation(here.latitude + 1.0, here.longitude, here.elevation))
        assertEquals(0, rig.driver.alignmentPoints.size)
        assertFailsWith<MountError.NotAligned> { rig.controller.goto(deneb) }
        // Crossing the equator drops it as well (and mirrors the model).
        val south = connected(equatorial, SkyWatcherTestSupport.equatorialErrors)
        south.syncOn(vega)
        south.controller.setSiteAndTime(GeographicLocation(-here.latitude, here.longitude, 0.0))
        assertEquals(0, south.driver.alignmentPoints.size)
        assertTrue(assertNotNull(south.driver.pointingModel).isSouthernHemisphere)
        val disconnected = SkyWatcherTestSupport.rig(altAz)
        assertFailsWith<MountError.NotConnected> { disconnected.controller.setSiteAndTime(here) }
    }

    // MARK: Slewing

    @Test
    fun testGotoSlewsAtAFiniteSpeedAndArrivesTracking() {
        val rig = connected(altAz, SkyWatcherTestSupport.altAzimuthErrors)
        aligned(rig, altAzimuthAlignmentStars)
        val before = rig.clock.secondsSince1970
        val startAxis1 = rig.simulator.axis1Degrees
        rig.controller.goto(altair)
        assertEquals(MountPhase.SLEWING, rig.controller.status.phase)
        val status = rig.controller.awaitSettled()
        val seconds = rig.clock.secondsSince1970 - before
        assertEquals(MountPhase.IDLE, status.phase)
        assertTrue(status.outcome is MountSlewOutcome.Arrived)
        // The mount cannot be faster than its maximum speed: the azimuth change takes at least that long.
        val travelled = abs(rig.simulator.axis1Degrees - startAxis1)
        assertTrue(travelled > 20.0, "$travelled")
        assertTrue(seconds >= travelled / 3.0, "$seconds s for $travelled°")
        assertTrue(rig.simulator.peakSpeedDegreesPerSecond <= 3.0 + 1e-6, "${rig.simulator.peakSpeedDegreesPerSecond}")
        assertTrue(rig.simulator.peakSpeedDegreesPerSecond > 2.0)
        assertTrue(rig.errorTo(altair) < 0.05)
        rig.controller.poll()
        assertEquals(true, rig.controller.status.isTracking)
        assertEquals(altair, rig.controller.status.target)
        assertTrue((rig.controller.status.separation ?: 9.0) < 0.05)
        assertNull(rig.controller.status.slewElapsedSeconds)
    }

    @Test
    fun testGotoRefusalsAndMountLimits() {
        val rig = connected(altAz, SkyWatcherTestSupport.altAzimuthErrors)
        aligned(rig, altAzimuthAlignmentStars)
        val moves = rig.simulator.gotosStarted
        // Below the user's altitude limit: the controller refuses before anything is sent.
        assertFailsWith<MountError.BelowHorizonLimit> { rig.controller.goto(MountTestSupport.orionNebula) }
        // Near the zenith of an alt-azimuth mount: the mount's own overhead limit.
        val frame = rig.frame()
        val zenith = MountEpoch.APPARENT_OF_DATE.toJ2000(
            EquatorialCoordinate(frame.localApparentSiderealTime, SkyWatcherTestSupport.boulderLocation.latitude - 0.3), frame,
        )
        assertFailsWith<MountError.MountOverheadLimit> { rig.controller.goto(zenith) }
        assertEquals(moves, rig.simulator.gotosStarted)
        assertEquals(MountPhase.IDLE, rig.controller.status.phase)
        // The mount's horizon limit is configurable: Rasalhague (altitude 17) is too low for a limit of 30.
        val limited = SkyWatcherTestSupport.rig(
            altAz, SkyWatcherTestSupport.altAzimuthErrors,
            configuration = SkyWatcherConfiguration(
                SkyWatcherTestSupport.boulderLocation, mode = altAz, limits = SkyWatcherLimits(minimumAltitudeDegrees = 30.0),
            ),
        )
        limited.controller.connect()
        aligned(limited, altAzimuthAlignmentStars)
        assertFailsWith<MountError.MountHorizonLimit> { limited.controller.goto(rasalhague) }
        assertTrue(goto(limited, deneb) < 0.1)
        // Cable wrap: with the azimuth axis limited to +-130° Markab (azimuth 169°) cannot be reached, Alpheratz (116°) can.
        val wrapped = SkyWatcherTestSupport.rig(
            altAz, SkyWatcherTestSupport.altAzimuthErrors,
            configuration = SkyWatcherConfiguration(
                SkyWatcherTestSupport.boulderLocation, mode = altAz,
                limits = SkyWatcherLimits(azimuthMinimumDegrees = -130.0, azimuthMaximumDegrees = 130.0),
            ),
        )
        wrapped.controller.connect()
        aligned(wrapped, listOf(andromeda, mirfak, hamal))
        assertFailsWith<MountError.OutsideMountLimits> { wrapped.controller.goto(markab) }
        assertTrue(goto(wrapped, alpheratz) < 0.1)
        // The way round: vega at azimuth 290° is axis angle -70°, inside the limits, the short way.
        assertTrue(goto(wrapped, vega) < 0.1)
        assertTrue(wrapped.simulator.axis1Degrees < -60.0 && wrapped.simulator.axis1Degrees > -80.0, "${wrapped.simulator.axis1Degrees}")
    }

    @Test
    fun testAzimuthTakesTheShortWayWithinTheCableWrapLimits() {
        val rig = connected(altAz, SkyWatcherTestSupport.altAzimuthErrors)
        aligned(rig, altAzimuthAlignmentStars)
        // Vega is at azimuth 290° (axis angle -70° or 290°), Mirfak at 54°: going between them never turns the long way.
        goto(rig, vega)
        val atVega = rig.simulator.axis1Degrees
        assertTrue(abs(atVega) < 200.0, "$atVega")
        goto(rig, mirfak)
        val atMirfak = rig.simulator.axis1Degrees
        assertTrue(abs(atMirfak - atVega) < 180.0, "$atVega -> $atMirfak")
    }

    @Test
    fun testASecondGotoIsRefusedAndAbortStopsTheSlew() {
        val rig = connected(altAz, SkyWatcherTestSupport.altAzimuthErrors)
        aligned(rig, altAzimuthAlignmentStars)
        rig.controller.goto(capella)
        rig.clock.advance(3.0)
        rig.controller.poll()
        assertEquals(MountPhase.SLEWING, rig.controller.status.phase)
        assertFailsWith<MountError.SlewInProgress> { rig.controller.goto(deneb) }
        assertFailsWith<MountError.SlewInProgress> { rig.controller.sync(vega) }
        rig.controller.abort()
        assertEquals(MountPhase.IDLE, rig.controller.status.phase)
        assertTrue(rig.controller.status.outcome is MountSlewOutcome.Aborted)
        rig.clock.advance(2.0)
        // The mount decelerated and did not arrive; it stands (or resumes tracking, which is far slower than a slew).
        assertTrue(rig.simulator.peakSpeedDegreesPerSecond > 1.0)
        val position = rig.controller.poll().position
        assertTrue(assertNotNull(position).angularSeparation(capella) > 1.0)
        rig.clock.advance(5.0)
        rig.controller.poll()
        // Tracking resumes at the place where the mount was stopped.
        val first = rig.trueJ2000()
        SkyWatcherTestSupport.poll(rig, 60)
        assertTrue(rig.trueJ2000().angularSeparation(first) < 0.05, "${rig.trueJ2000().angularSeparation(first)}")
        // A new goto works after an abort.
        assertTrue(goto(rig, deneb) < 0.1)
    }

    @Test
    fun testLongSlewsAreCorrectedForTheMovingSky() {
        // A planner that believes the mount is ten times slower aims far behind the target; the correction slews fix it.
        val slow = SkyWatcherTestSupport.rig(
            altAz, SkyWatcherTestSupport.altAzimuthErrors,
            configuration = SkyWatcherConfiguration(SkyWatcherTestSupport.boulderLocation, mode = altAz, assumedSlewDegreesPerSecond = 0.25),
        )
        slow.controller.connect()
        aligned(slow, altAzimuthAlignmentStars)
        val gotosBefore = slow.simulator.gotosStarted
        assertTrue(goto(slow, altair) < 0.05)
        assertTrue(slow.simulator.gotosStarted - gotosBefore > 2, "corrections: ${slow.simulator.gotosStarted - gotosBefore}")
        val good = connected(altAz, SkyWatcherTestSupport.altAzimuthErrors)
        aligned(good, altAzimuthAlignmentStars)
        val goodBefore = good.simulator.gotosStarted
        assertTrue(goto(good, altair) < 0.05)
        assertTrue(good.simulator.gotosStarted - goodBefore <= slow.simulator.gotosStarted - gotosBefore)
    }

    // MARK: Equatorial mounts: pier side and meridian

    private fun hourAnglePlace(rig: SkyWatcherRig, hourAngle: Double, declination: Double): EquatorialCoordinate {
        val frame = rig.frame()
        return MountEpoch.APPARENT_OF_DATE.toJ2000(EquatorialCoordinate(frame.localApparentSiderealTime - hourAngle, declination), frame)
    }

    @Test
    fun testEquatorialMountKeepsTheCounterweightDown() {
        val rig = connected(equatorial, SkyWatcherTestSupport.equatorialErrors)
        aligned(rig, equatorialAlignmentStars)
        val index = SkyWatcherTestSupport.equatorialErrors.axis1Index
        var side: SkyWatcherPierSide? = null
        for (target in listOf(altair, hamal, deneb, capella, andromeda, rasalhague, alpheratz)) {
            rig.controller.goto(target)
            var worstAxis = 0.0
            while (rig.controller.status.phase == MountPhase.SLEWING) {
                rig.controller.poll()
                worstAxis = maxOf(worstAxis, abs(rig.simulator.axis1Degrees + index))
                rig.clock.advance(1.0)
            }
            assertTrue(rig.errorTo(target) < 0.1, "$target ${rig.errorTo(target)}")
            // Along the whole slew and afterwards: the counterweight stays within a few degrees of horizontal or lower.
            worstAxis = maxOf(worstAxis, abs(rig.simulator.axis1Degrees + index))
            assertTrue(worstAxis <= 95.0 + 1e-6, "$target $worstAxis")
            side = rig.driver.pierSide
            val frame = rig.frame()
            val hourAngle = AstroMath.normalizeSignedDegrees(
                frame.localApparentSiderealTime - MountEpoch.APPARENT_OF_DATE.fromJ2000(target, frame).rightAscension,
            )
            // West of the meridian the telescope is on the east side of the mount (normal), east of it on the west side.
            if (hourAngle > 6.0) assertEquals(SkyWatcherPierSide.EAST, side, "$target H=$hourAngle")
            if (hourAngle < -6.0) assertEquals(SkyWatcherPierSide.WEST, side, "$target H=$hourAngle")
        }
        assertNotNull(side)
    }

    @Test
    fun testTheCurrentSideIsKeptNearTheMeridian() {
        val rig = connected(equatorial, SkyWatcherTestSupport.equatorialErrors)
        aligned(rig, equatorialAlignmentStars)
        // Right on the meridian either side works (within the 5° overlap): the mount stays on the side it is on.
        val onMeridian = hourAnglePlace(rig, 2.0, 25.0)
        goto(rig, hamal)
        assertEquals(SkyWatcherPierSide.WEST, rig.driver.pierSide)
        assertTrue(goto(rig, onMeridian) < 0.1)
        assertEquals(SkyWatcherPierSide.WEST, rig.driver.pierSide)
        goto(rig, altair)
        assertEquals(SkyWatcherPierSide.EAST, rig.driver.pierSide)
        assertTrue(goto(rig, hourAnglePlace(rig, -2.0, 25.0)) < 0.1)
        assertEquals(SkyWatcherPierSide.EAST, rig.driver.pierSide)
        // Past the overlap the side must change: the meridian flip is a goto to the same star.
        val east = hourAnglePlace(rig, -20.0, 25.0)
        assertTrue(goto(rig, east) < 0.1)
        assertEquals(SkyWatcherPierSide.WEST, rig.driver.pierSide)
    }

    @Test
    fun testTrackingStopsAtTheMeridianLimitAndAGotoFlipsTheMount() {
        val configuration = SkyWatcherConfiguration(
            SkyWatcherTestSupport.boulderLocation, mode = equatorial, limits = SkyWatcherLimits(meridianOverlapDegrees = 0.0),
        )
        val rig = SkyWatcherTestSupport.rig(equatorial, SkyWatcherPointingErrors(axis1Index = 1.0), configuration = configuration)
        rig.controller.connect()
        aligned(rig, equatorialAlignmentStars)
        // Two degrees east of the meridian: on the west side of the mount, the counterweight 2° from horizontal.
        val target = hourAnglePlace(rig, -2.0, 30.0)
        assertTrue(goto(rig, target) < 0.1)
        assertEquals(SkyWatcherPierSide.WEST, rig.driver.pierSide)
        assertEquals(true, rig.controller.poll().isTracking)
        // The sky carries the star across the meridian in 8 minutes; the mount stops there instead of turning the counterweight up.
        SkyWatcherTestSupport.poll(rig, 120)
        assertEquals(true, rig.controller.poll().isTracking)
        SkyWatcherTestSupport.poll(rig, 600)
        val status = rig.controller.poll()
        assertEquals(false, status.isTracking)
        assertEquals(MountError.OutsideMountLimits, rig.driver.trackingStopReason)
        assertFalse(rig.simulator.isMoving)
        assertTrue(rig.simulator.axis1Degrees + 1.0 < 90.5)
        // The meridian flip: a goto to the same star now chooses the other side, and tracking runs again.
        assertTrue(goto(rig, target) < 0.1)
        assertEquals(SkyWatcherPierSide.EAST, rig.driver.pierSide)
        assertNull(rig.driver.trackingStopReason)
        SkyWatcherTestSupport.poll(rig, 300)
        assertEquals(true, rig.controller.poll().isTracking)
        assertTrue(rig.errorTo(target) < 0.05, "${rig.errorTo(target)}")
    }

    /** The altitude of where the real telescope points. */
    private fun realAltitude(rig: SkyWatcherRig): Double =
        SkyWatcherSky.altitude(rig.simulator.truePlace(), rig.frame().localApparentSiderealTime, rig.simulator.location.latitude)

    /** Slews to [end] and returns the lowest altitude the real telescope had on the way (sampled every tenth of a second). */
    private fun lowestAltitudeOnTheWayTo(rig: SkyWatcherRig, end: EquatorialCoordinate): Double {
        rig.controller.goto(end)
        var lowest = realAltitude(rig)
        while (rig.controller.status.phase == MountPhase.SLEWING) {
            for (step in 0 until 8) {
                rig.clock.advance(0.1)
                lowest = minOf(lowest, realAltitude(rig))
            }
            rig.controller.poll()
        }
        assertTrue(rig.controller.status.outcome is MountSlewOutcome.Arrived, "${rig.controller.status.outcome}")
        return lowest
    }

    @Test
    fun testSlewsThatWouldDipBelowTheHorizonAreSplit() {
        fun rigFor(limits: SkyWatcherLimits): SkyWatcherRig {
            val configuration = SkyWatcherConfiguration(SkyWatcherTestSupport.boulderLocation, mode = equatorial, limits = limits)
            val rig = SkyWatcherTestSupport.rig(equatorial, configuration = configuration, settings = MountSettings(altitudeLimit = 10.0))
            rig.controller.connect()
            return rig
        }
        // From a star near the lower pole to one on the other side of the meridian: moving both axes at once sweeps the telescope down to
        // 10° above the horizon, moving the right ascension axis first keeps it above 20°.
        val direct = rigFor(SkyWatcherLimits())
        val begin = hourAnglePlace(direct, 164.6, 84.2)
        val end = hourAnglePlace(direct, 39.1, -7.7)
        direct.syncOn(begin)
        assertTrue(realAltitude(direct) > 20.0)
        val lowestDirect = lowestAltitudeOnTheWayTo(direct, end)
        assertTrue(lowestDirect < 15.0, "$lowestDirect")
        val split = rigFor(SkyWatcherLimits(minimumAltitudeDegrees = 20.0, pathMarginDegrees = 0.0))
        split.syncOn(begin)
        val lowestSplit = lowestAltitudeOnTheWayTo(split, end)
        assertTrue(lowestSplit > 19.0, "$lowestSplit")
        assertTrue(split.errorTo(end) < 0.1)
        // Both ways dip below the limit: refused, and the mount does not move.
        val blocked = rigFor(SkyWatcherLimits(minimumAltitudeDegrees = 20.0, pathMarginDegrees = 0.0))
        val from = hourAnglePlace(blocked, 54.4, -4.3)
        val to = hourAnglePlace(blocked, -29.9, -8.7)
        blocked.syncOn(from)
        val before = blocked.simulator.gotosStarted
        assertFailsWith<MountError.OutsideMountLimits> { blocked.controller.goto(to) }
        assertEquals(before, blocked.simulator.gotosStarted)
        assertEquals(MountPhase.IDLE, blocked.controller.status.phase)
    }

    // MARK: Tracking

    /** Polls once a second for [minutes] minutes and returns how far the real telescope drifted from where it was at the start. */
    private fun worstTrackingDrift(rig: SkyWatcherRig, minutes: Int): Double {
        val start = rig.trueJ2000()
        var worst = 0.0
        for (minute in 0 until minutes) {
            SkyWatcherTestSupport.poll(rig, 60)
            worst = maxOf(worst, rig.trueJ2000().angularSeparation(start))
        }
        return worst
    }

    @Test
    fun testAltAzimuthTrackingKeepsTheTarget() {
        val rig = connected(altAz, SkyWatcherTestSupport.altAzimuthErrors)
        aligned(rig, altAzimuthAlignmentStars)
        for (target in listOf(deneb, hamal, rasalhague)) {
            assertTrue(goto(rig, target) < 0.1)
            val worst = worstTrackingDrift(rig, 20)
            assertTrue(worst < 0.03, "$target $worst")
        }
        // Both axes run in speed mode the whole time, with rates that follow the sky.
        assertEquals(true, rig.controller.status.isTracking)
        assertTrue(rig.simulator.isMoving)
        assertTrue(rig.simulator.speedStarts > 5, "${rig.simulator.speedStarts}")
    }

    @Test
    fun testEquatorialTrackingKeepsTheTargetAndAServoCompensatesThePolarError() {
        val errors = SkyWatcherPointingErrors(axis1Index = 1.5, axis2Index = -1.0)
        val rig = connected(equatorial, errors)
        aligned(rig, equatorialAlignmentStars)
        assertTrue(goto(rig, deneb) < 0.05)
        assertTrue(worstTrackingDrift(rig, 20) < 0.03)
        // Sidereal tracking on one axis: it was started once, after the slew, and never restarted.
        assertEquals(1, rig.simulator.speedStarts)
        // A polar alignment error makes the target drift while the mount tracks at the sidereal rate; the servo strategy, which knows
        // the error from the alignment, removes that drift.
        val tilted = SkyWatcherPointingErrors(axis1Index = 1.5, axis2Index = -1.0, baseTiltX = 0.8, baseTiltY = -0.6)
        val sidereal = connected(equatorial, tilted)
        aligned(sidereal, equatorialAlignmentStars)
        goto(sidereal, altair)
        val drifting = worstTrackingDrift(sidereal, 30)
        val servo = SkyWatcherTestSupport.rig(
            equatorial, tilted,
            configuration = SkyWatcherConfiguration(
                SkyWatcherTestSupport.boulderLocation, mode = equatorial, trackingStrategy = SkyWatcherTrackingStrategy.SERVO,
            ),
        )
        servo.controller.connect()
        aligned(servo, equatorialAlignmentStars)
        goto(servo, altair)
        val compensated = worstTrackingDrift(servo, 30)
        assertTrue(drifting > 0.05, "sidereal drift $drifting")
        assertTrue(compensated < 0.03, "servo $compensated")
        assertTrue(compensated < drifting / 2)
    }

    @Test
    fun testAltAzimuthTrackingStopsAtTheCableWrapLimit() {
        val configuration = SkyWatcherConfiguration(
            SkyWatcherTestSupport.boulderLocation, mode = altAz, limits = SkyWatcherLimits(azimuthMinimumDegrees = -20.0, azimuthMaximumDegrees = 100.0),
        )
        val rig = SkyWatcherTestSupport.rig(altAz, SkyWatcherPointingErrors(axis1Index = 1.0), configuration = configuration)
        rig.controller.connect()
        aligned(rig, listOf(altair, mirfak, enif))
        // Hamal is at azimuth 96° and rises: the azimuth axis reaches the limit of 100° after a while and tracking stops there.
        assertTrue(goto(rig, hamal) < 0.1)
        assertEquals(true, rig.controller.poll().isTracking)
        assertTrue(rig.simulator.axis1Degrees in 90.0..99.0, "${rig.simulator.axis1Degrees}")
        SkyWatcherTestSupport.poll(rig, 3000)
        val status = rig.controller.poll()
        assertEquals(false, status.isTracking)
        assertEquals(MountError.OutsideMountLimits, rig.driver.trackingStopReason)
        assertFalse(rig.simulator.isMoving)
        assertEquals(100.0, rig.simulator.axis1Degrees, 1.0)
        // Tracking can be switched on again, but the cause is still there: the mount refuses and stays where it is.
        assertFailsWith<MountError.OutsideMountLimits> { rig.controller.setTracking(true) }
        SkyWatcherTestSupport.poll(rig, 5)
        assertEquals(false, rig.controller.poll().isTracking)
        assertFalse(rig.simulator.isMoving)
    }

    @Test
    fun testServoUpdatesAreRateLimited() {
        // The app may poll as often as it likes: the rates of the axes are updated at most once per update interval.
        fun updates(intervalSeconds: Double): Int {
            val rig = SkyWatcherTestSupport.rig(
                altAz, SkyWatcherTestSupport.altAzimuthErrors,
                configuration = SkyWatcherConfiguration(
                    SkyWatcherTestSupport.boulderLocation, mode = altAz, trackingUpdateIntervalSeconds = intervalSeconds,
                ),
            )
            rig.controller.connect()
            aligned(rig, altAzimuthAlignmentStars)
            goto(rig, deneb)
            rig.simulator.clearLog()
            val begin = rig.clock.secondsSince1970
            while (rig.clock.secondsSince1970 - begin < 60.0) {
                rig.controller.poll()
                rig.clock.advance(0.1)
            }
            return rig.simulator.commandLog.count { it == "I1" }
        }
        val fast = updates(1.0)
        val slow = updates(5.0)
        assertTrue(fast in 40..70, "$fast")
        assertTrue(slow in 8..16, "$slow")
    }

    @Test
    fun testTrackingCanBeSwitchedOffAndOn() {
        for (mode in listOf(altAz, equatorial)) {
            val rig = connected(mode, SkyWatcherPointingErrors(axis1Index = 1.0, axis2Index = 0.5))
            aligned(rig, listOf(vega, mirfak, enif))
            assertTrue(goto(rig, deneb) < 0.1)
            rig.controller.setTracking(false)
            SkyWatcherTestSupport.poll(rig, 2)
            assertFalse(rig.simulator.isMoving, "$mode")
            assertEquals(false, rig.controller.poll().isTracking)
            val start = rig.trueJ2000()
            SkyWatcherTestSupport.poll(rig, 60)
            // The sky moves 15 arcseconds per second: a minute of drift is a quarter of a degree (cos δ = 0.7 for Deneb).
            val drift = rig.trueJ2000().angularSeparation(start)
            assertTrue(drift in 0.15..0.30, "$mode $drift")
            rig.controller.setTracking(true)
            assertEquals(true, rig.controller.poll().isTracking)
            val resumed = rig.trueJ2000()
            SkyWatcherTestSupport.poll(rig, 120)
            assertTrue(rig.trueJ2000().angularSeparation(resumed) < 0.05, "$mode")
            // Switching it off twice is harmless.
            rig.controller.setTracking(false)
            rig.controller.setTracking(false)
        }
    }

    @Test
    fun testDisconnectStopsAServoButLeavesSiderealTrackingRunning() {
        val altAzRig = connected(altAz, SkyWatcherPointingErrors(axis1Index = 1.0))
        aligned(altAzRig, altAzimuthAlignmentStars)
        goto(altAzRig, deneb)
        SkyWatcherTestSupport.poll(altAzRig, 3)
        assertTrue(altAzRig.simulator.isMoving)
        altAzRig.controller.disconnect()
        altAzRig.clock.advance(2.0)
        assertFalse(altAzRig.simulator.isMoving)
        assertEquals(MountPhase.DISCONNECTED, altAzRig.controller.status.phase)
        val equatorialRig = connected(equatorial, SkyWatcherPointingErrors(axis1Index = 1.0))
        aligned(equatorialRig, equatorialAlignmentStars)
        goto(equatorialRig, deneb)
        equatorialRig.controller.disconnect()
        equatorialRig.clock.advance(2.0)
        assertTrue(equatorialRig.simulator.isMoving)
        // Connecting again stops the mount: the session starts from rest.
        equatorialRig.controller.connect()
        assertFalse(equatorialRig.simulator.isMoving)
        assertEquals(false, equatorialRig.controller.poll().isTracking)
    }

    @Test
    fun testGotoByAbsoluteTargetsWorksToo() {
        // `S` (absolute target) instead of `H` (increment) and `M` (brake point).
        for (mode in listOf(altAz, equatorial)) {
            val errors = if (mode == altAz) SkyWatcherTestSupport.altAzimuthErrors else SkyWatcherTestSupport.equatorialErrors
            val rig = SkyWatcherTestSupport.rig(
                mode, errors,
                configuration = SkyWatcherConfiguration(SkyWatcherTestSupport.boulderLocation, mode = mode, usesAbsoluteGotoTarget = true),
            )
            rig.controller.connect()
            aligned(rig, listOf(vega, mirfak, enif))
            for (target in listOf(deneb, hamal)) assertTrue(goto(rig, target) < 0.1, "$mode $target")
            assertTrue(rig.simulator.commandLog.any { it.startsWith("S") })
            assertFalse(rig.simulator.commandLog.any { it.startsWith("H") || it.startsWith("M") })
        }
    }

    // MARK: Nudges

    private fun skyOffset(from: EquatorialCoordinate, to: EquatorialCoordinate): Pair<Double, Double> {
        val delta = to.unitVector - from.unitVector
        return delta.dot(east(from)) to delta.dot(north(from))
    }

    @Test
    fun testNudgesMoveTheViewAcrossTheSky() {
        for (mode in listOf(altAz, equatorial)) {
            val rig = connected(mode, SkyWatcherPointingErrors(axis1Index = 1.0, axis2Index = -0.5))
            aligned(rig, listOf(vega, mirfak, enif))
            goto(rig, deneb)
            val sidereal = MountNudgeRate.siderealDegreesPerSecond
            for (direction in MountNudgeDirection.entries) {
                val before = rig.trueJ2000()
                rig.controller.nudge(direction, MountNudgeRate.FIND, 3.0)
                assertEquals(MountPhase.NUDGING, rig.controller.status.phase)
                // Hold for about three seconds, polling every half second as the app does while a button is down.
                val begin = rig.clock.secondsSince1970
                while (rig.clock.secondsSince1970 - begin < 3.0) {
                    rig.clock.advance(0.5)
                    rig.controller.poll()
                }
                rig.controller.stopNudge()
                assertEquals(MountPhase.IDLE, rig.controller.status.phase)
                val after = rig.trueJ2000()
                val (eastward, northward) = skyOffset(before, after)
                val seconds = rig.clock.secondsSince1970 - begin
                val expected = 32.0 * sidereal * seconds * AstroMath.radians(1.0)
                val moved = kotlin.math.hypot(eastward, northward)
                assertTrue(moved in expected * 0.7..expected * 1.4, "$mode $direction moved $moved expected $expected")
                when (direction) {
                    MountNudgeDirection.EAST -> assertTrue(eastward > 0 && abs(northward) < 0.2 * eastward, "$mode east $eastward $northward")
                    MountNudgeDirection.WEST -> assertTrue(eastward < 0 && abs(northward) < 0.2 * -eastward, "$mode west $eastward $northward")
                    MountNudgeDirection.NORTH -> assertTrue(northward > 0 && abs(eastward) < 0.2 * northward, "$mode north $eastward $northward")
                    MountNudgeDirection.SOUTH -> assertTrue(northward < 0 && abs(eastward) < 0.2 * -northward, "$mode south $eastward $northward")
                }
                goto(rig, deneb)
            }
            // After a nudge tracking resumes at the new place.
            val place = rig.trueJ2000()
            SkyWatcherTestSupport.poll(rig, 60)
            assertTrue(rig.trueJ2000().angularSeparation(place) < 0.05)
        }
    }

    @Test
    fun testANudgeIntoTheOverheadLimitEndsAndTrackingContinues() {
        val rig = SkyWatcherTestSupport.rig(altAz, SkyWatcherPointingErrors(axis1Index = 1.0), settings = MountSettings(maximumNudgeSeconds = 30.0))
        rig.controller.connect()
        aligned(rig, altAzimuthAlignmentStars)
        // Scheat is 77° up, south of the zenith: north is straight up, towards the mount's overhead limit of 88°.
        val scheat = EquatorialCoordinate.fromRightAscensionHours(23.0629, 28.0828)
        assertTrue(goto(rig, scheat) < 0.1)
        rig.controller.nudge(MountNudgeDirection.NORTH, MountNudgeRate.SLEW, 30.0)
        for (step in 0 until 60) {
            rig.clock.advance(0.5)
            rig.controller.poll()
        }
        rig.controller.stopNudge()
        val altitude = realAltitude(rig)
        assertTrue(altitude in 85.0..88.6, "$altitude")
        assertNull(rig.driver.trackingStopReason)
        // The mount tracks at the place where the nudge ended.
        val place = rig.trueJ2000()
        SkyWatcherTestSupport.poll(rig, 60)
        val moved = rig.trueJ2000().angularSeparation(place)
        assertTrue(moved < 0.05, "moved $moved degrees from altitude $altitude")
        assertEquals(true, rig.controller.poll().isTracking, "tracking stopped: ${rig.driver.trackingStopReason}")
    }

    @Test
    fun testANudgeIntoTheAzimuthLimitEndsInsteadOfUnwinding() {
        val rig = SkyWatcherTestSupport.rig(altAz, settings = MountSettings(maximumNudgeSeconds = 30.0))
        rig.controller.connect()
        // Three degrees short of the cable limit of 270°, in the west and 40° up: a nudge to the west turns the azimuth axis on.
        rig.simulator.placeAxes(267.0, 40.0)
        rig.controller.nudge(MountNudgeDirection.WEST, MountNudgeRate.SLEW, 20.0)
        var highest = rig.simulator.axis1Degrees
        for (step in 0 until 30) {
            rig.clock.advance(0.5)
            rig.controller.poll()
            highest = max(highest, rig.simulator.axis1Degrees)
        }
        // The axis went towards the limit and stopped there; it did not run back the other way round at full speed.
        assertTrue(highest > 268.0 && highest < 271.0, "$highest")
        assertTrue(rig.simulator.axis1Degrees > 268.0, "${rig.simulator.axis1Degrees}")
        assertFalse(rig.simulator.isMoving)
    }

    @Test
    fun testLosingTheLinkDuringANudgeStopsTheAxes() {
        val rig = SkyWatcherTestSupport.rig(
            altAz, SkyWatcherPointingErrors(axis1Index = 1.0), settings = MountSettings(maximumNudgeSeconds = 30.0),
        )
        rig.controller.connect()
        aligned(rig, altAzimuthAlignmentStars)
        goto(rig, deneb)
        rig.controller.nudge(MountNudgeDirection.EAST, MountNudgeRate.FIND, 30.0)
        rig.clock.advance(1.0)
        assertTrue(rig.simulator.isMoving)
        rig.simulator.inject("j1", MountSimulatedFault.Silence, times = 100)
        rig.controller.poll()
        rig.controller.poll()
        val ended = rig.controller.poll()
        assertEquals(MountPhase.DISCONNECTED, ended.phase)
        assertEquals(MountError.ConnectionLost, ended.lastError)
        // The nudge was running when the session ended: the axes were told to stop, tracking or not.
        rig.clock.advance(2.0)
        assertFalse(rig.simulator.isMoving)
    }

    @Test
    fun testNudgeRatesFollowTheSiderealMultiples() {
        val rig = connected(equatorial, SkyWatcherPointingErrors(axis1Index = 1.0))
        aligned(rig, equatorialAlignmentStars)
        goto(rig, deneb)
        for (rate in listOf(MountNudgeRate.GUIDE, MountNudgeRate.CENTER, MountNudgeRate.FIND)) {
            val before = rig.trueJ2000()
            rig.controller.nudge(MountNudgeDirection.NORTH, rate, 4.0)
            val begin = rig.clock.secondsSince1970
            while (rig.clock.secondsSince1970 - begin < 4.0) {
                rig.clock.advance(0.5)
                rig.controller.poll()
            }
            rig.controller.stopNudge()
            val moved = rig.trueJ2000().angularSeparation(before)
            val expected = rate.degreesPerSecond * (rig.clock.secondsSince1970 - begin)
            assertTrue(moved in expected * 0.7..expected * 1.3, "$rate moved $moved expected $expected")
            goto(rig, deneb)
        }
    }

    @Test
    fun testFastNudgesUseTheHighSpeedMode() {
        val rig = connected(altAz, SkyWatcherPointingErrors(axis1Index = 1.0))
        aligned(rig, altAzimuthAlignmentStars)
        // Markab is high on the meridian: east is the azimuth direction there, and the azimuth axis must run fast to carry 0.53°/s.
        goto(rig, markab)
        val slewPeak = rig.simulator.peakSpeedDegreesPerSecond
        rig.controller.nudge(MountNudgeDirection.EAST, MountNudgeRate.SLEW, 2.0)
        // The view moves at 128 times sidereal (0.53°/s); the azimuth axis carries it at 0.53/cos(65°) and runs in the fast mode.
        var fast = false
        for (axis in listOf(1, 2)) {
            rig.simulator.send(MountText.latin1Bytes(":f$axis\r"))
            val reply = MountText.latin1(rig.simulator.receive(64, 1.0))
            if (reply[1] in "4567") fast = true
        }
        assertTrue(fast)
        rig.clock.advance(1.0)
        rig.controller.poll()
        assertTrue(rig.simulator.peakSpeedDegreesPerSecond > 0.5)
        assertTrue(rig.simulator.peakSpeedDegreesPerSecond <= max(slewPeak, 3.0))
        rig.controller.stopNudge()
    }

    @Test
    fun testANudgeNobodyStopsEndsByItself() {
        val rig = connected(altAz, SkyWatcherPointingErrors(axis1Index = 1.0))
        aligned(rig, altAzimuthAlignmentStars)
        goto(rig, deneb)
        rig.driver.startNudge(MountNudgeDirection.WEST, MountNudgeRate.FIND, 1.0)
        rig.clock.advance(6.0)
        // The app is gone: the next call of the driver ends the nudge after its duration plus a grace period.
        assertFalse(rig.driver.readSlewing())
        val stopped = rig.trueJ2000()
        SkyWatcherTestSupport.poll(rig, 30)
        assertTrue(rig.trueJ2000().angularSeparation(stopped) < 0.05)
    }

    @Test
    fun testAbortStopsANudgeAndNudgesAreRefusedWhileSlewing() {
        val rig = connected(equatorial, SkyWatcherPointingErrors(axis1Index = 1.0))
        aligned(rig, equatorialAlignmentStars)
        goto(rig, deneb)
        rig.controller.nudge(MountNudgeDirection.EAST, MountNudgeRate.CENTER, 5.0)
        rig.clock.advance(1.0)
        rig.controller.abort()
        assertEquals(MountPhase.IDLE, rig.controller.status.phase)
        rig.clock.advance(2.0)
        rig.controller.poll()
        // Stopped nudging, tracking continues.
        val place = rig.trueJ2000()
        SkyWatcherTestSupport.poll(rig, 30)
        assertTrue(rig.trueJ2000().angularSeparation(place) < 0.05)
        rig.controller.goto(altair)
        assertFailsWith<MountError.SlewInProgress> { rig.controller.nudge(MountNudgeDirection.EAST) }
        rig.controller.abort()
    }

    // MARK: Axis senses

    @Test
    fun testInvertedAxesWork() {
        for (mode in listOf(altAz, equatorial)) {
            val errors = if (mode == altAz) SkyWatcherTestSupport.altAzimuthErrors else SkyWatcherTestSupport.equatorialErrors
            val rig = SkyWatcherTestSupport.rig(mode, errors, axis1Inverted = true, axis2Inverted = true)
            rig.controller.connect()
            aligned(rig, listOf(vega, mirfak, enif))
            for (target in listOf(deneb, altair, hamal)) assertTrue(goto(rig, target) < 0.1, "$mode $target")
            assertTrue(worstTrackingDrift(rig, 10) < 0.03)
            val before = rig.trueJ2000()
            rig.controller.nudge(MountNudgeDirection.NORTH, MountNudgeRate.FIND, 2.0)
            for (step in 0 until 4) {
                rig.clock.advance(0.5)
                rig.controller.poll()
            }
            rig.controller.stopNudge()
            val (eastward, northward) = skyOffset(before, rig.trueJ2000())
            assertTrue(northward > 0.1 * 1.0 * AstroMath.radians(1.0) && abs(eastward) < 0.3 * northward, "$mode $eastward $northward")
        }
        // A driver that does not know about the inverted axes aims the wrong way and cannot be aligned plausibly.
        val wrong = SkyWatcherTestSupport.rig(
            equatorial, SkyWatcherTestSupport.equatorialErrors,
            configuration = SkyWatcherConfiguration(SkyWatcherTestSupport.boulderLocation, mode = equatorial),
            axis1Inverted = true,
        )
        wrong.controller.connect()
        wrong.syncOn(vega)
        var rejected = false
        try {
            wrong.syncOn(mirfak)
            wrong.syncOn(enif)
        } catch (error: MountError.Rejected) {
            rejected = true
        }
        assertTrue(rejected)
    }

    // MARK: Link

    @Test
    fun testPollingCostsAFewDatagrams() {
        val rig = connected(equatorial, SkyWatcherTestSupport.equatorialErrors)
        aligned(rig, equatorialAlignmentStars)
        goto(rig, deneb)
        rig.controller.poll()
        val before = rig.driver.datagramsSent
        rig.controller.poll()
        // Two positions and two statuses.
        assertEquals(4, rig.driver.datagramsSent - before)
        val idle = connected(altAz, SkyWatcherPointingErrors(axis1Index = 1.0))
        idle.controller.poll()
        val idleBefore = idle.driver.datagramsSent
        idle.controller.poll()
        assertEquals(4, idle.driver.datagramsSent - idleBefore)
        // A servo tick (alt-azimuth tracking) reads the position again and sets the speed of both axes: eight datagrams, and more only
        // while an axis changes its direction (stop, mode, period and start).
        val servo = connected(altAz, SkyWatcherPointingErrors(axis1Index = 1.0))
        aligned(servo, altAzimuthAlignmentStars)
        goto(servo, deneb)
        var worst = 0
        var total = 0
        for (second in 0 until 30) {
            servo.clock.advance(1.0)
            val sent = servo.driver.datagramsSent
            servo.controller.poll()
            worst = maxOf(worst, servo.driver.datagramsSent - sent)
            total += servo.driver.datagramsSent - sent
        }
        assertTrue(worst <= 20, "$worst")
        assertTrue(total <= 30 * 8 + 12, "$total")
    }

    @Test
    fun testALostOrGarbledReplyIsRepeated() {
        val rig = connected(equatorial, SkyWatcherTestSupport.equatorialErrors)
        aligned(rig, equatorialAlignmentStars)
        val faults = listOf<Pair<String, MountSimulatedFault>>(
            "j1" to MountSimulatedFault.Silence,
            "j2" to MountSimulatedFault.Replace("=12\r"),
            "f1" to MountSimulatedFault.Replace("=00000G\r"),
            "f2" to MountSimulatedFault.Truncate(2),
            "j" to MountSimulatedFault.Replace("!1\r"),
            "f" to MountSimulatedFault.Replace("!0\r"),
            "j" to MountSimulatedFault.Replace("!3\r"),
            "f" to MountSimulatedFault.Replace("garbage\r"),
            "j2" to MountSimulatedFault.Replace("=\r"),
            "f1" to MountSimulatedFault.Replace("=1234567\r"),
        )
        for ((label, fault) in faults) {
            rig.simulator.inject(label, fault)
            val before = rig.driver.retries
            val timeBefore = rig.clock.secondsSince1970
            val status = rig.controller.poll()
            assertNull(status.lastError, "$label $fault ${status.lastError}")
            assertEquals(1, rig.driver.retries - before, "$label $fault")
            if (fault == MountSimulatedFault.Silence || fault is MountSimulatedFault.Truncate) {
                assertTrue(rig.clock.secondsSince1970 - timeBefore >= 0.6)
            }
        }
        // The mount's own refusals are not repeated: !2 (not stopped) is the answer.
        rig.simulator.inject("K", MountSimulatedFault.Replace("!2\r"))
        val retries = rig.driver.retries
        assertEquals(MountError.Busy, assertFailsWith<MountError> { rig.controller.abort() })
        assertEquals(retries, rig.driver.retries)
    }

    @Test
    fun testAllAttemptsLostFailsThePollAndThreeFailuresEndTheSession() {
        val rig = connected(altAz, SkyWatcherPointingErrors(axis1Index = 1.0))
        rig.simulator.inject("j1", MountSimulatedFault.Silence, times = 100)
        val before = rig.clock.secondsSince1970
        val status = rig.controller.poll()
        assertTrue(status.lastError is MountError.Timeout, "${status.lastError}")
        assertEquals(1.8, rig.clock.secondsSince1970 - before, 0.3)
        rig.controller.poll()
        val ended = rig.controller.poll()
        assertEquals(MountPhase.DISCONNECTED, ended.phase)
        assertEquals(MountError.ConnectionLost, ended.lastError)
        val dropped = connected(altAz)
        dropped.simulator.inject("f", MountSimulatedFault.Disconnect)
        val failed = dropped.controller.poll()
        assertTrue(failed.lastError is MountError.Transport, "${failed.lastError}")
    }

    @Test
    fun testALostStartReplyDoesNotStartTheMotionTwice() {
        val rig = connected(altAz, SkyWatcherPointingErrors(axis1Index = 1.0))
        aligned(rig, altAzimuthAlignmentStars)
        val before = rig.simulator.gotosStarted
        rig.simulator.inject("J1", MountSimulatedFault.Silence)
        rig.controller.goto(altair)
        // The reply to the start of axis 1 was lost, yet the axis runs: the driver looked at the status instead of sending it again.
        assertEquals(2, rig.simulator.gotosStarted - before)
        val status = rig.controller.awaitSettled()
        assertTrue(status.outcome is MountSlewOutcome.Arrived)
        assertTrue(rig.errorTo(altair) < 0.05)
        // The same for the start of axis 2.
        val lost = connected(altAz, SkyWatcherPointingErrors(axis1Index = 1.0))
        aligned(lost, altAzimuthAlignmentStars)
        lost.simulator.inject("J2", MountSimulatedFault.Silence)
        lost.controller.goto(altair)
        assertTrue(lost.controller.awaitSettled().outcome is MountSlewOutcome.Arrived)
    }

    @Test
    fun testALostStartReplyOfAShortGotoDoesNotRunTheAxisAway() {
        // The declination axis of an equatorial mount stands still while tracking. A GOTO of a few counts is over before the status is
        // read; if the reply to its start was lost, sending the start again would run the axis at its last speed.
        val rig = connected(equatorial, SkyWatcherPointingErrors(axis1Index = 1.0))
        aligned(rig, equatorialAlignmentStars)
        goto(rig, deneb)
        val place = rig.trueJ2000()
        val nearby = EquatorialCoordinate(place.rightAscension, place.declination + 0.004)
        val starts = rig.simulator.speedStarts
        rig.simulator.inject("J2", MountSimulatedFault.Silence)
        rig.controller.goto(nearby)
        assertTrue(rig.controller.awaitSettled().outcome is MountSlewOutcome.Arrived)
        SkyWatcherTestSupport.poll(rig, 30)
        assertTrue(rig.errorTo(nearby) < 0.03, "${rig.errorTo(nearby)}")
        // Axis 2 was not started in speed mode (axis 1 resumes sidereal tracking once).
        assertEquals(1, rig.simulator.speedStarts - starts)
    }

    @Test
    fun testTheWholeFlowSurvivesLostAndDamagedDatagrams() {
        val configuration = SkyWatcherConfiguration(
            SkyWatcherTestSupport.boulderLocation, mode = altAz, attempts = 6,
        )
        val rig = SkyWatcherTestSupport.rig(altAz, SkyWatcherTestSupport.altAzimuthErrors, configuration = configuration) {
            it.requestLossProbability = 0.08
            it.replyLossProbability = 0.08
            it.garbleProbability = 0.08
        }
        rig.controller.connect()
        aligned(rig, altAzimuthAlignmentStars)
        for (target in listOf(deneb, hamal)) {
            rig.controller.goto(target)
            assertTrue(rig.controller.awaitSettled().outcome is MountSlewOutcome.Arrived)
            assertTrue(rig.errorTo(target) < 0.1, "$target ${rig.errorTo(target)}")
        }
        var worst = 0.0
        for (minute in 0 until 5) {
            SkyWatcherTestSupport.poll(rig, 60)
            worst = maxOf(worst, rig.errorTo(hamal))
        }
        assertTrue(worst < 0.1, "$worst")
        val sent = rig.driver.datagramsSent
        val retries = rig.driver.retries
        assertTrue(retries > sent / 20, "$retries of $sent")
        assertTrue(retries < sent / 2, "$retries of $sent")
        assertEquals(true, rig.controller.poll().isTracking)
    }

    @Test
    fun testSlowAndChoppedLinksStillWork() {
        // A quarter of a second per reply and replies that arrive one byte at a time.
        val rig = SkyWatcherTestSupport.rig(altAz, SkyWatcherTestSupport.altAzimuthErrors, latencySeconds = 0.25, chunkSize = 1)
        rig.controller.connect()
        aligned(rig, altAzimuthAlignmentStars)
        assertTrue(goto(rig, deneb) < 0.1)
        assertTrue(worstTrackingDrift(rig, 5) < 0.05)
        assertEquals(0, rig.driver.retries)
    }

    @Test
    fun testReconnectingStartsANewSession() {
        val rig = connected(altAz, SkyWatcherTestSupport.altAzimuthErrors)
        aligned(rig, altAzimuthAlignmentStars)
        goto(rig, deneb)
        rig.controller.disconnect()
        assertEquals(MountPhase.DISCONNECTED, rig.controller.status.phase)
        assertFailsWith<MountError.NotConnected> { rig.driver.readPosition() }
        assertFailsWith<MountError.NotConnected> { rig.driver.slew(altair) }
        assertFailsWith<MountError.NotConnected> { rig.driver.sync(altair) }
        assertFailsWith<MountError.NotConnected> { rig.driver.abort() }
        assertFailsWith<MountError.NotConnected> { rig.driver.setTracking(true) }
        assertFailsWith<MountError.NotConnected> { rig.driver.startNudge(MountNudgeDirection.NORTH, MountNudgeRate.GUIDE, 1.0) }
        assertNull(rig.driver.pointingModel)
        val capabilities = rig.controller.connect()
        assertEquals(false, capabilities.isAligned)
        assertEquals(0, rig.driver.alignmentPoints.size)
        assertFailsWith<MountError.NotAligned> { rig.controller.goto(altair) }
        // The axes are still where they were; the new session begins from there.
        assertTrue(rig.driver.startupAxisAngles?.axis1Degrees != 0.0)
        aligned(rig, altAzimuthAlignmentStars)
        assertTrue(goto(rig, altair) < 0.1)
    }

    @Test
    fun testNudgesRefuseUnusableDurations() {
        val rig = connected(altAz, SkyWatcherPointingErrors(axis1Index = 1.0))
        for (duration in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<MountError.InvalidInput>("$duration") { rig.driver.startNudge(MountNudgeDirection.NORTH, MountNudgeRate.GUIDE, duration) }
        }
        assertFailsWith<MountError.InvalidInput> { rig.controller.nudge(MountNudgeDirection.NORTH, MountNudgeRate.GUIDE, 11.0) }
        assertFailsWith<MountError.InvalidInput> { rig.controller.goto(EquatorialCoordinate.fromRightAscensionHours(Double.NaN, 10.0)) }
    }

    @Test
    fun testTheSimulationIsDeterministic() {
        fun run(): List<Double> {
            val rig = SkyWatcherTestSupport.rig(
                equatorial, SkyWatcherTestSupport.equatorialErrors,
                configuration = SkyWatcherConfiguration(SkyWatcherTestSupport.boulderLocation, mode = equatorial, attempts = 6),
            ) {
                it.requestLossProbability = 0.05
                it.replyLossProbability = 0.05
                it.garbleProbability = 0.05
            }
            rig.controller.connect()
            aligned(rig, equatorialAlignmentStars)
            goto(rig, altair)
            SkyWatcherTestSupport.poll(rig, 100)
            return listOf(
                rig.driver.datagramsSent.toDouble(), rig.driver.retries.toDouble(), rig.simulator.axis1Degrees, rig.simulator.axis2Degrees,
                rig.errorTo(altair), rig.clock.secondsSince1970 - SkyWatcherTestSupport.start.epochSecond,
            )
        }
        assertEquals(run(), run())
    }
}
