package net.kuecker.lodestar.kit.pointing

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.PointingTestSupport as Support
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A small deterministic random source (SplitMix64) so the property tests give the same results on
 * every platform and compiler version.
 */
internal class PointingTestRandom(seed: ULong) {
    private var state: ULong = seed

    constructor(seed: Int) : this(seed.toULong())

    fun nextBits(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    /** Uniform in [low, high). */
    fun uniform(low: Double, high: Double): Double =
        low + (high - low) * (nextBits() shr 11).toDouble() / 9_007_199_254_740_992.0

    /** Standard normal (Box–Muller). */
    fun gaussian(): Double {
        val u1 = maxOf(uniform(0.0, 1.0), 1e-300)
        val u2 = uniform(0.0, 1.0)
        return sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
    }

    fun unitVector(): Vector3 {
        val z = uniform(-1.0, 1.0)
        val angle = uniform(0.0, 2 * PI)
        val r = sqrt(1 - z * z)
        return Vector3(r * cos(angle), r * sin(angle), z)
    }

    /** A uniformly distributed proper rotation (from a normalised Gaussian quaternion). */
    fun rotation(): Matrix3 {
        var w = gaussian()
        var x = gaussian()
        var y = gaussian()
        var z = gaussian()
        val norm = sqrt(w * w + x * x + y * y + z * z)
        w /= norm
        x /= norm
        y /= norm
        z /= norm
        return Matrix3(
            Vector3(1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)),
            Vector3(2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)),
            Vector3(2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)),
        )
    }

    fun horizontal(altitude: ClosedFloatingPointRange<Double> = -90.0..90.0): HorizontalCoordinate {
        val azimuth = uniform(0.0, 360.0)
        return HorizontalCoordinate(azimuth, uniform(altitude.start, altitude.endInclusive))
    }

    /** A small random rotation whose three rotation-vector components have standard deviation `sigmaDegrees`. */
    fun smallRotation(sigmaDegrees: Double): Matrix3 {
        val x = gaussian()
        val y = gaussian()
        val z = gaussian()
        val vector = Vector3(x, y, z) * (sigmaDegrees * PI / 180)
        return Matrix3.rotation(vector, vector.length)
    }
}

/**
 * Test fixtures built from the geometric definition of the frames, deliberately not from the
 * library's own formulas.
 */
internal object PointingTestSupport {
    /** The camera axes in the world (the Swift `(right:, up:, forward:)` tuple). */
    data class CameraFrame(val right: Vector3, val up: Vector3, val forward: Vector3)

    /**
     * A phone attitude from the device axes written in ENU world coordinates. The rows of the
     * CoreMotion matrix are the device axes in the reference frame, whose x axis has true azimuth
     * `referenceAzimuth`, whose z axis is up and whose y axis is 90° counter-clockwise of x.
     */
    fun attitude(
        deviceX: Vector3,
        deviceY: Vector3,
        deviceZ: Vector3,
        referenceAzimuth: Double = 0.0,
        timestamp: Double = 0.0,
    ): DeviceAttitude {
        val psi = referenceAzimuth
        val s = sin(psi * PI / 180)
        val c = cos(psi * PI / 180)
        val referenceX = Vector3(s, c, 0.0)
        val referenceY = Vector3(-c, s, 0.0)
        val referenceZ = Vector3(0.0, 0.0, 1.0)
        fun row(axis: Vector3) = Vector3(axis.dot(referenceX), axis.dot(referenceY), axis.dot(referenceZ))
        return assertNotNull(
            DeviceAttitude.create(Matrix3(row(deviceX), row(deviceY), row(deviceZ)), psi, timestamp),
        )
    }

    /**
     * The camera axes in the world for a boresight and a roll: roll 0 has the camera's right side
     * level, positive roll tilts the right side down (clockwise as seen by the photographer).
     */
    fun cameraFrame(boresight: HorizontalCoordinate, rollDegrees: Double): CameraFrame {
        val forward = boresight.unitVector
        val levelRight = forward.cross(Vector3(0.0, 0.0, 1.0)).normalized
        val levelUp = levelRight.cross(forward)
        val s = sin(rollDegrees * PI / 180)
        val c = cos(rollDegrees * PI / 180)
        return CameraFrame(levelRight * c - levelUp * s, levelUp * c + levelRight * s, forward)
    }

    /**
     * The phone attitude for which the camera (the mount's axes turned by `correction`, a rotation
     * vector in degrees applied in the device frame) has the given world frame.
     */
    fun attitude(
        mount: MountProfile,
        correction: Vector3 = Vector3.zero,
        frame: CameraFrame,
        referenceAzimuth: Double = 0.0,
        timestamp: Double = 0.0,
    ): DeviceAttitude {
        val turn = Matrix3.rotation(correction, correction.length * PI / 180)
        val axis = turn * mount.cameraAxis
        val up = turn * mount.cameraUp
        val right = axis.cross(up)
        // Device axis i in the world: the camera frame vectors weighted by that axis's components.
        fun deviceAxis(component: (Vector3) -> Double): Vector3 =
            frame.right * component(right) + frame.up * component(up) + frame.forward * component(axis)
        return attitude(
            deviceX = deviceAxis { it.x }, deviceY = deviceAxis { it.y }, deviceZ = deviceAxis { it.z },
            referenceAzimuth = referenceAzimuth, timestamp = timestamp,
        )
    }

    fun assertVector(a: Vector3, b: Vector3, accuracy: Double = 1e-9, message: String = "") {
        val text = message.ifEmpty { null }
        assertEquals(b.x, a.x, accuracy, text)
        assertEquals(b.y, a.y, accuracy, text)
        assertEquals(b.z, a.z, accuracy, text)
    }

    fun assertMatrix(a: Matrix3, b: Matrix3, accuracy: Double = 1e-9) {
        assertVector(a.r0, b.r0, accuracy)
        assertVector(a.r1, b.r1, accuracy)
        assertVector(a.r2, b.r2, accuracy)
    }

    fun assertRotation(m: Matrix3, accuracy: Double = 1e-9) {
        assertMatrix(m * m.transposed, Matrix3.identity, accuracy)
        assertEquals(1.0, m.determinant, accuracy)
    }

    /** Signed azimuth difference in degrees, wrapped to (−180, 180]. */
    fun azimuthDifference(a: Double, b: Double): Double = AstroMath.normalizeSignedDegrees(a - b)

    /**
     * Swift's `XCTAssertEqual(a, b)` for `Double`s: IEEE equality (so `-0.0 == 0.0`), unlike `assertEquals`,
     * which compares the boxed values with `equals`.
     */
    fun assertExactly(expected: Double, actual: Double, message: String? = null) {
        assertTrue(expected == actual, (message ?: "") + " expected <$expected> but was <$actual>")
    }
}

/** Conventions of the device attitude: frames, signs and sanity orientations. */
class PointingAttitudeTests {
    // MARK: Reference frames

    @Test
    fun testReferenceTransformAtZeroAzimuthIsNorthWestUp() {
        val t = DeviceAttitude.referenceTransform(0.0)
        Support.assertVector(t * Vector3(0.0, 1.0, 0.0), Vector3(1.0, 0.0, 0.0), accuracy = 1e-15) // north is x
        Support.assertVector(t * Vector3(-1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), accuracy = 1e-15) // west is y
        Support.assertVector(t * Vector3(0.0, 0.0, 1.0), Vector3(0.0, 0.0, 1.0), accuracy = 1e-15) // up is z
        Support.assertVector(t * Vector3(1.0, 0.0, 0.0), Vector3(0.0, -1.0, 0.0), accuracy = 1e-15) // east is −y
    }

    @Test
    fun testReferenceTransformPutsTheAzimuthDirectionOnTheXAxis() {
        for (psi in listOf(-170.0, -90.0, -12.5, 0.0, 33.0, 90.0, 135.0, 180.0, 271.0)) {
            val t = DeviceAttitude.referenceTransform(psi)
            val along = HorizontalCoordinate(psi, 0.0).unitVector
            val counterClockwise = HorizontalCoordinate(psi - 90, 0.0).unitVector
            Support.assertVector(t * along, Vector3(1.0, 0.0, 0.0), accuracy = 1e-12)
            Support.assertVector(t * counterClockwise, Vector3(0.0, 1.0, 0.0), accuracy = 1e-12)
            Support.assertRotation(t, accuracy = 1e-12)
        }
    }

    @Test
    fun testReferenceTransformComposesWithAZRotation() {
        for ((psi, delta) in listOf(0.0 to 12.0, -40.0 to 75.0, 170.0 to 30.0, 33.0 to -200.0)) {
            val combined = DeviceAttitude.referenceTransform(psi + delta)
            val composed = DeviceAttitude.referenceTransform(psi) * Matrix3.rotationZ(delta * PI / 180)
            Support.assertMatrix(combined, composed, accuracy = 1e-12)
        }
    }

    // MARK: Sanity orientations

    @Test
    fun testUprightFacingNorthWithScreenTowardsTheUser() {
        // Device x = east, y = up, z = south; in NWU the rows of the CoreMotion matrix are
        // (0,−1,0), (0,0,1), (−1,0,0).
        val nwu = Matrix3(Vector3(0.0, -1.0, 0.0), Vector3(0.0, 0.0, 1.0), Vector3(-1.0, 0.0, 0.0))
        val attitude = assertNotNull(DeviceAttitude.create(nwu))
        Support.assertVector(attitude.deviceToWorld * DeviceAttitude.backCameraAxis, Vector3(0.0, 1.0, 0.0), accuracy = 1e-15)
        val solution = PointingSolution.solve(attitude, MountProfile.backCameraPortrait)
        assertEquals(0.0, solution.boresight.azimuth, 1e-9)
        assertEquals(0.0, solution.boresight.altitude, 1e-9)
        assertEquals(0.0, solution.roll, 1e-9)
        // The same orientation built from the physical axes gives the same matrix.
        val built = Support.attitude(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 0.0, 1.0), Vector3(0.0, -1.0, 0.0))
        Support.assertMatrix(built.matrix, nwu, accuracy = 1e-15)
    }

    @Test
    fun testFlatOnATableScreenUpTopEdgeEastLooksAtTheNadir() {
        // Device y = east, z = up, so x = y × z = south.
        val attitude = Support.attitude(Vector3(0.0, -1.0, 0.0), Vector3(1.0, 0.0, 0.0), Vector3(0.0, 0.0, 1.0))
        val nwu = Matrix3(Vector3(-1.0, 0.0, 0.0), Vector3(0.0, -1.0, 0.0), Vector3(0.0, 0.0, 1.0))
        Support.assertMatrix(attitude.matrix, nwu, accuracy = 1e-15)
        val camera = attitude.deviceToWorld * DeviceAttitude.backCameraAxis
        Support.assertVector(camera, Vector3(0.0, 0.0, -1.0), accuracy = 1e-15)
        assertEquals(-90.0, PointingSolution.solve(attitude, MountProfile.backCameraPortrait).boresight.altitude, 1e-9)
    }

    @Test
    fun testUprightPhoneFacingEachCompassPoint() {
        // Held upright the back camera looks along the horizon; the right edge points 90° clockwise of it.
        for (azimuth in listOf(0.0, 90.0, 180.0, 270.0, 45.0, 359.5)) {
            val forward = HorizontalCoordinate(azimuth, 0.0).unitVector
            val right = HorizontalCoordinate(azimuth + 90, 0.0).unitVector
            val attitude = Support.attitude(right, Vector3(0.0, 0.0, 1.0), forward * -1.0)
            val solution = PointingSolution.solve(attitude, MountProfile.backCameraPortrait)
            assertEquals(0.0, Support.azimuthDifference(solution.boresight.azimuth, azimuth), 1e-9)
            assertEquals(0.0, solution.boresight.altitude, 1e-9)
        }
        // The lead's example, written out: facing east the right edge points south, z points west.
        val east = Matrix3(Vector3(-1.0, 0.0, 0.0), Vector3(0.0, 0.0, 1.0), Vector3(0.0, 1.0, 0.0))
        val solution = PointingSolution.solve(assertNotNull(DeviceAttitude.create(east)), MountProfile.backCameraPortrait)
        assertEquals(90.0, solution.boresight.azimuth, 1e-9)
    }

    @Test
    fun testTiltingTheUprightPhoneBackRaisesTheCamera() {
        // Facing north, top edge leaning back by 30°: the back camera looks 30° above the horizon.
        val up = AstroMath.radians(30.0)
        val top = Vector3(0.0, -sin(up), cos(up)) // leaning away from the camera direction
        val towardsUser = Vector3(0.0, -cos(up), -sin(up))
        val attitude = Support.attitude(Vector3(1.0, 0.0, 0.0), top, towardsUser)
        val boresight = PointingSolution.solve(attitude, MountProfile.backCameraPortrait).boresight
        assertEquals(30.0, boresight.altitude, 1e-9)
        assertEquals(0.0, boresight.azimuth, 1e-9)
    }

    @Test
    fun testReferenceAzimuthTurnsAnyReferenceFrameIntoTheWorld() {
        // The same physical pose (upright, facing the reference frame's own x axis) read in frames
        // of different heading: the camera azimuth equals the frame's azimuth.
        val facing = Matrix3(Vector3(0.0, -1.0, 0.0), Vector3(0.0, 0.0, 1.0), Vector3(-1.0, 0.0, 0.0))
        for (psi in listOf(12.0, -8.0, 135.0, -170.0, 180.0)) {
            val attitude = assertNotNull(DeviceAttitude.create(facing, psi))
            val boresight = PointingSolution.solve(attitude, MountProfile.backCameraPortrait).boresight
            assertEquals(0.0, Support.azimuthDifference(boresight.azimuth, psi), 1e-9, "ψ = $psi")
        }
    }

    @Test
    fun testHeadingOffsetAddsToTheAzimuth() {
        val attitude = Support.attitude(
            Vector3(1.0, 0.0, 0.0), Vector3(0.0, 0.0, 1.0), Vector3(0.0, -1.0, 0.0), referenceAzimuth = 20.0,
        )
        for (offset in listOf(12.0, -30.0, 400.0, 0.0)) {
            val shifted = attitude.withHeadingOffset(offset)
            val before = HorizontalCoordinate(attitude.deviceToWorld * DeviceAttitude.backCameraAxis)
            val after = HorizontalCoordinate(shifted.deviceToWorld * DeviceAttitude.backCameraAxis)
            assertEquals(0.0, Support.azimuthDifference(after.azimuth, before.azimuth + offset), 1e-9)
            assertEquals(before.altitude, after.altitude, 1e-9)
            Support.assertExactly(attitude.timestamp, shifted.timestamp)
        }
        assertEquals(attitude, attitude.withHeadingOffset(Double.NaN))
        assertEquals(attitude, attitude.withHeadingOffset(Double.POSITIVE_INFINITY))
    }

    // MARK: Validation

    @Test
    fun testNearlyOrthonormalMatricesAreCleanedUp() {
        val random = PointingTestRandom(11)
        repeat(50) {
            val exact = random.rotation()
            val noisy = Matrix3(
                exact.r0 * 1.01 + random.unitVector() * 0.004,
                exact.r1 * 0.99 + random.unitVector() * 0.004,
                exact.r2 * 1.005 + random.unitVector() * 0.004,
            )
            val attitude = assertNotNull(DeviceAttitude.create(noisy))
            Support.assertRotation(attitude.matrix, accuracy = 1e-12)
            // The cleaned matrix stays close to the exact one.
            assertTrue((attitude.matrix.r0 - exact.r0).length < 0.02)
        }
    }

    @Test
    fun testInvalidMatricesAreRejected() {
        val nan = Matrix3(Vector3(Double.NaN, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0))
        val zero = Matrix3(Vector3.zero, Vector3.zero, Vector3.zero)
        val reflection = Matrix3(Vector3(-1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0))
        val stretched = Matrix3(Vector3(2.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0))
        val skewed = Matrix3(Vector3(1.0, 0.5, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0))
        for (matrix in listOf(nan, zero, reflection, stretched, skewed)) {
            assertNull(DeviceAttitude.create(matrix))
        }
        assertNull(DeviceAttitude.create(Matrix3.identity, referenceAzimuth = Double.NaN))
        assertNull(DeviceAttitude.create(Matrix3.identity, referenceAzimuth = Double.POSITIVE_INFINITY))
        assertNull(DeviceAttitude.create(Matrix3.identity, timestamp = Double.NaN))
        assertNotNull(DeviceAttitude.create(Matrix3.identity))
    }

    @Test
    fun testReferenceAzimuthIsNormalisedToSignedDegrees() {
        assertEquals(-170.0, assertNotNull(DeviceAttitude.create(Matrix3.identity, referenceAzimuth = 190.0)).referenceAzimuth, 1e-12)
        assertEquals(180.0, assertNotNull(DeviceAttitude.create(Matrix3.identity, referenceAzimuth = -180.0)).referenceAzimuth, 1e-12)
        assertEquals(5.0, assertNotNull(DeviceAttitude.create(Matrix3.identity, referenceAzimuth = 725.0)).referenceAzimuth, 1e-12)
    }

    @Test
    fun testCodableRoundTripAndRejection() {
        val random = PointingTestRandom(5)
        val original = assertNotNull(DeviceAttitude.create(random.rotation(), -12.5, 1234.5))
        val data = Json.encodeToString(DeviceAttitude.serializer(), original)
        val decoded = Json.decodeFromString(DeviceAttitude.serializer(), data)
        assertEquals(original, decoded)

        val corrupted = """
        {"matrix":{"r0":{"x":2,"y":0,"z":0},"r1":{"x":0,"y":1,"z":0},"r2":{"x":0,"y":0,"z":1}},
         "referenceAzimuth":0,"timestamp":0}
        """
        assertFailsWith<SerializationException> { Json.decodeFromString(DeviceAttitude.serializer(), corrupted) }
    }

    // MARK: Properties of random attitudes

    @Test
    fun testWorldAndDeviceTransformsAreInverseRotations() {
        val random = PointingTestRandom(21)
        repeat(200) {
            val attitude = assertNotNull(DeviceAttitude.create(random.rotation(), random.uniform(-180.0, 180.0)))
            Support.assertRotation(attitude.worldToDevice)
            Support.assertRotation(attitude.deviceToWorld)
            Support.assertMatrix(attitude.worldToDevice * attitude.deviceToWorld, Matrix3.identity)
            // World up expressed in the device frame is the third column of the CoreMotion matrix,
            // whatever the reference heading.
            Support.assertVector(attitude.worldToDevice * Vector3(0.0, 0.0, 1.0), attitude.matrix.c2)
        }
    }

    @Test
    fun testDeviceAxesInTheWorldMatchTheirDefinition() {
        val random = PointingTestRandom(22)
        repeat(100) {
            val x = random.unitVector()
            val helper = random.unitVector()
            val y = (helper - x * x.dot(helper)).normalized
            val z = x.cross(y)
            val psi = random.uniform(-180.0, 180.0)
            val attitude = Support.attitude(x, y, z, referenceAzimuth = psi)
            Support.assertVector(attitude.deviceToWorld * Vector3(1.0, 0.0, 0.0), x)
            Support.assertVector(attitude.deviceToWorld * Vector3(0.0, 1.0, 0.0), y)
            Support.assertVector(attitude.deviceToWorld * Vector3(0.0, 0.0, 1.0), z)
        }
    }

    // MARK: Gyro-lock reference frames

    @Test
    fun testArbitraryFrameAzimuthIsRecoveredFromSimultaneousAttitudes() {
        val random = PointingTestRandom(31)
        repeat(200) {
            val x = random.unitVector()
            val helper = random.unitVector()
            val y = (helper - x * x.dot(helper)).normalized
            val z = x.cross(y)
            val trueNorth = Support.attitude(x, y, z)
            val psi = random.uniform(-180.0, 180.0)
            val arbitrary = Support.attitude(x, y, z, referenceAzimuth = psi)
            val recovered = assertNotNull(ReferenceFrames.referenceAzimuth(arbitrary.matrix, trueNorth.matrix))
            assertEquals(0.0, Support.azimuthDifference(recovered, psi), 1e-9)
        }
    }

    @Test
    fun testArbitraryFrameAzimuthWorksFromAMagneticFrame() {
        val x = Vector3(0.3, 0.5, 0.2).normalized
        val y = (Vector3(0.0, 0.0, 1.0) - x * x.z).normalized
        val z = x.cross(y)
        val declination = -14.0
        val magnetic = Support.attitude(x, y, z, referenceAzimuth = declination)
        val arbitrary = Support.attitude(x, y, z, referenceAzimuth = 77.0)
        val recovered = assertNotNull(ReferenceFrames.referenceAzimuth(arbitrary.matrix, magnetic.matrix, declination))
        assertEquals(77.0, recovered, 1e-9)
    }

    @Test
    fun testGyroLockKeepsTheCameraDirectionAcrossTheSwitch() {
        // Aim the camera, then switch to a gyro-only frame: the camera direction must not change.
        val random = PointingTestRandom(32)
        repeat(50) {
            val frame = Support.cameraFrame(random.horizontal(-60.0..80.0), random.uniform(-30.0, 30.0))
            val compass = Support.attitude(mount = MountProfile.backCameraPortrait, frame = frame)
            val gyro = Support.attitude(
                mount = MountProfile.backCameraPortrait, frame = frame, referenceAzimuth = random.uniform(-180.0, 180.0),
            )
            val psi = assertNotNull(ReferenceFrames.referenceAzimuth(gyro.matrix, compass.matrix))
            val adopted = assertNotNull(DeviceAttitude.create(gyro.matrix, psi))
            Support.assertVector(
                adopted.deviceToWorld * DeviceAttitude.backCameraAxis,
                compass.deviceToWorld * DeviceAttitude.backCameraAxis,
            )
        }
    }

    @Test
    fun testArbitraryFrameAzimuthToleratesSmallInconsistencies() {
        val random = PointingTestRandom(33)
        val x = Vector3(0.6, -0.3, 0.4).normalized
        val y = (Vector3(0.0, 0.0, 1.0) - x * x.z).normalized
        val z = x.cross(y)
        val trueNorth = Support.attitude(x, y, z)
        repeat(50) {
            val psi = random.uniform(-180.0, 180.0)
            val arbitrary = Support.attitude(x, y, z, referenceAzimuth = psi)
            // The two samples are 0.05° apart in attitude, as when the phone is not perfectly still.
            val jittered = random.smallRotation(0.05) * arbitrary.matrix
            val recovered = assertNotNull(ReferenceFrames.referenceAzimuth(jittered, trueNorth.matrix))
            assertEquals(0.0, Support.azimuthDifference(recovered, psi), 0.5)
        }
    }

    @Test
    fun testArbitraryFrameAzimuthRejectsInconsistentInput() {
        val level = Matrix3.identity
        val tilted = Matrix3.rotationX(AstroMath.radians(25.0))
        assertNull(ReferenceFrames.referenceAzimuth(tilted, level))
        val invalid = Matrix3(Vector3(Double.NaN, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0))
        assertNull(ReferenceFrames.referenceAzimuth(invalid, level))
        assertNull(ReferenceFrames.referenceAzimuth(level, invalid))
        assertNull(ReferenceFrames.referenceAzimuth(level, level, Double.POSITIVE_INFINITY))
        // Identical matrices in frames of equal heading give zero.
        val sameFrame = assertNotNull(ReferenceFrames.referenceAzimuth(level, level))
        assertEquals(0.0, sameFrame, 1e-12)
    }

    // MARK: Rotation helpers

    @Test
    fun testRotationVectorMatrixMatchesAxisAngleRotation() {
        val random = PointingTestRandom(41)
        repeat(200) {
            val vector = random.unitVector() * random.uniform(0.0, 3.0)
            val expected = Matrix3.rotation(vector, vector.length)
            Support.assertMatrix(PointingMath.rotationMatrix(vector), expected, accuracy = 1e-12)
        }
        for (tiny in listOf(0.0, 1e-12, 1e-9, 1e-6, 1e-4)) {
            val vector = Vector3(0.6, -0.48, 0.64) * tiny
            val matrix = PointingMath.rotationMatrix(vector)
            Support.assertRotation(matrix, accuracy = 1e-14)
            Support.assertVector(
                matrix * Vector3(0.0, 0.0, 1.0), Vector3(0.0, 0.0, 1.0) + vector.cross(Vector3(0.0, 0.0, 1.0)), accuracy = 1e-8,
            )
        }
    }

    @Test
    fun testYawedRaisesTheAzimuth() {
        val random = PointingTestRandom(42)
        repeat(100) {
            val direction = random.horizontal(-80.0..80.0)
            val offset = random.uniform(-200.0, 200.0)
            val yawed = HorizontalCoordinate(PointingMath.yawed(direction.unitVector, offset))
            assertEquals(0.0, Support.azimuthDifference(yawed.azimuth, direction.azimuth + offset), 1e-9)
            assertEquals(direction.altitude, yawed.altitude, 1e-9)
        }
    }
}
