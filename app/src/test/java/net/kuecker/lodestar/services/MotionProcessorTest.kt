package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.Matrix3
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.model.HeadingSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionProcessorTest {
    private val availability = MotionFrameAvailability(magneticNorth = true, trueNorth = false)

    /** A level phone, screen up, top edge towards [azimuth] (degrees) of its reference frame (north-west-up rows). */
    private fun levelMatrix(azimuth: Double): Matrix3 {
        val s = AstroMath.sinDeg(azimuth)
        val c = AstroMath.cosDeg(azimuth)
        // Device y (top edge) points at azimuth: in north-west-up coordinates (cos a, -sin a, 0).
        val top = Vector3(c, -s, 0.0)
        val up = Vector3(0.0, 0.0, 1.0)
        val right = top.cross(up)
        return Matrix3(right, top, up)
    }

    private fun input(matrix: Matrix3, time: Double, speed: Double = 0.0, yawRate: Double = 0.0, field: Double = 48.0) =
        MotionInput(
            matrix = matrix, gravity = Vector3(0.0, 0.0, -1.0), angularSpeed = speed, yawRate = yawRate,
            fieldMicrotesla = field, fieldAccuracy = CompassQuality.HIGH, timestamp = time,
        )

    private fun assertSameOrientation(expected: Matrix3, actual: Matrix3) {
        for ((e, a) in listOf(expected.r0 to actual.r0, expected.r1 to actual.r1, expected.r2 to actual.r2)) {
            assertTrue((e - a).length < 1e-9, "rows differ: $e vs $a")
        }
    }

    @Test
    fun compassSamplesCarryTheDeclinationAsReferenceAzimuth() {
        val processor = MotionProcessor()
        processor.setMagneticReference(MagneticReference(declination = 9.5, expectedFieldMicrotesla = 48.0))
        val start = processor.start(availability)
        assertEquals(MotionStream.MAGNETIC_NORTH, start.stream)
        processor.process(input(levelMatrix(30.0), 1.0), start.id)
        val sample = assertNotNull(processor.latest)
        assertEquals(9.5, sample.rawAttitude.referenceAzimuth, 1e-12)
        assertEquals(CompassQuality.HIGH, sample.compassQuality)
        assertEquals(0.0, assertNotNull(processor.snapshot.magneticDisturbance), 1e-12)
    }

    @Test
    fun samplesOfAnOldStreamAreIgnored() {
        val processor = MotionProcessor()
        val first = processor.start(availability)
        val second = processor.start(availability)
        assertNull(processor.process(input(levelMatrix(0.0), 1.0), first.id))
        assertNull(processor.latest)
        processor.process(input(levelMatrix(0.0), 1.0), second.id)
        assertNotNull(processor.latest)
    }

    @Test
    fun gyroLockTransfersTheCompassHeadingToTheGyroFrame() {
        val processor = MotionProcessor()
        processor.setMagneticReference(MagneticReference(declination = 7.0, expectedFieldMicrotesla = 48.0))
        processor.setSource(HeadingSource.GYRO_LOCK, availability)
        val compass = processor.start(availability)
        // Stand still in the compass frame until it has settled (1 s) and been steady (0.25 s).
        var time = 0.0
        var event: MotionEvent? = null
        while (event == null && time < 3.0) {
            event = processor.process(input(levelMatrix(40.0), time), compass.id)
            time += 0.02
        }
        assertTrue(event is MotionEvent.CompassSteady)
        val compassWorldToDevice = assertNotNull(processor.latest).rawAttitude.worldToDevice

        val gyro = assertNotNull(processor.switchToGyroFrame())
        assertEquals(MotionStream.ARBITRARY, gyro.stream)
        // The gyro frame's reference is turned by 123° about the vertical: the same orientation reads differently.
        repeat(30) {
            processor.process(input(levelMatrix(40.0 - 123.0), time, field = 0.0), gyro.id)
            time += 0.02
        }
        assertTrue(processor.snapshot.phase == GyroLockPhase.REFERENCED)
        assertSameOrientation(compassWorldToDevice, assertNotNull(processor.latest).rawAttitude.worldToDevice)
    }

    @Test
    fun turningDuringTheTransferRefusesIt() {
        val processor = MotionProcessor()
        processor.setSource(HeadingSource.GYRO_LOCK, availability)
        val compass = processor.start(availability)
        var time = 0.0
        var event: MotionEvent? = null
        while (event == null && time < 3.0) {
            event = processor.process(input(levelMatrix(0.0), time), compass.id)
            time += 0.02
        }
        val epoch = processor.frameEpoch
        val gyro = assertNotNull(processor.switchToGyroFrame())
        // 20°/s about the vertical for half a second: 10°, far beyond the 1° allowed.
        repeat(25) {
            processor.process(input(levelMatrix(0.0), time, speed = 20.0, yawRate = 20.0, field = 0.0), gyro.id)
            time += 0.02
        }
        assertEquals(GyroLockPhase.UNREFERENCED, processor.snapshot.phase)
        assertTrue(processor.frameEpoch > epoch)
    }

    @Test
    fun aDeclinationStepBeyondHalfADegreeStartsANewAlignmentEpoch() {
        val processor = MotionProcessor()
        processor.setMagneticReference(MagneticReference(3.0, 48.0))
        processor.start(availability)
        val epoch = processor.frameEpoch
        processor.setMagneticReference(MagneticReference(3.2, 48.0))
        assertEquals(epoch, processor.frameEpoch)
        processor.setMagneticReference(MagneticReference(4.0, 48.0))
        assertEquals(epoch + 1, processor.frameEpoch)
    }

    @Test
    fun aDisturbedFieldIsReportedRelativeToTheModel() {
        val processor = MotionProcessor()
        processor.setMagneticReference(MagneticReference(0.0, 50.0))
        val start = processor.start(availability)
        processor.process(input(levelMatrix(0.0), 1.0, field = 75.0), start.id)
        assertTrue(abs(assertNotNull(processor.snapshot.magneticDisturbance) - 0.5) < 1e-9)
    }
}
