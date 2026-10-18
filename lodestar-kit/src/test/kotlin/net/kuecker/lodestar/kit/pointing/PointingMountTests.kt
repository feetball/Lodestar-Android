package net.kuecker.lodestar.kit.pointing

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.PointingTestSupport as Support
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A preset together with the geometry derived by hand: the camera axes in device coordinates and
 * the device-frame screen directions in which a move to the camera's right / up is drawn.
 */
internal class ExpectedMount(
    val mount: MountProfile,
    val axis: Vector3,
    val up: Vector3,
    val right: Vector3,
    val screenRight: Vector3,
    val screenUp: Vector3,
)

private val expectedMountList: List<ExpectedMount> = listOf(
    // Phone upright in portrait, back camera along the lens.
    ExpectedMount(
        MountProfile.backCameraPortrait, axis = Vector3(0.0, 0.0, -1.0), up = Vector3(0.0, 1.0, 0.0),
        right = Vector3(1.0, 0.0, 0.0), screenRight = Vector3(1.0, 0.0, 0.0), screenUp = Vector3(0.0, 1.0, 0.0),
    ),
    // Phone upside down: the camera's up is the bottom edge, its right the left edge.
    ExpectedMount(
        MountProfile.backCameraPortraitUpsideDown, axis = Vector3(0.0, 0.0, -1.0), up = Vector3(0.0, -1.0, 0.0),
        right = Vector3(-1.0, 0.0, 0.0), screenRight = Vector3(-1.0, 0.0, 0.0), screenUp = Vector3(0.0, -1.0, 0.0),
    ),
    // UIDeviceOrientation.landscapeLeft: top edge to the photographer's left, bottom edge to the right.
    ExpectedMount(
        MountProfile.backCameraLandscapeLeft, axis = Vector3(0.0, 0.0, -1.0), up = Vector3(1.0, 0.0, 0.0),
        right = Vector3(0.0, -1.0, 0.0), screenRight = Vector3(0.0, -1.0, 0.0), screenUp = Vector3(1.0, 0.0, 0.0),
    ),
    // UIDeviceOrientation.landscapeRight: top edge to the photographer's right.
    ExpectedMount(
        MountProfile.backCameraLandscapeRight, axis = Vector3(0.0, 0.0, -1.0), up = Vector3(-1.0, 0.0, 0.0),
        right = Vector3(0.0, 1.0, 0.0), screenRight = Vector3(0.0, 1.0, 0.0), screenUp = Vector3(-1.0, 0.0, 0.0),
    ),
    // Lying on top of the camera, top edge towards the lens: right edge = camera right,
    // "tilt up" drawn towards the top edge.
    ExpectedMount(
        MountProfile.flatOnTopTopEdgeForward, axis = Vector3(0.0, 1.0, 0.0), up = Vector3(0.0, 0.0, 1.0),
        right = Vector3(1.0, 0.0, 0.0), screenRight = Vector3(1.0, 0.0, 0.0), screenUp = Vector3(0.0, 1.0, 0.0),
    ),
    // Lying on top with the top edge towards the photographer: the lens points down the screen.
    ExpectedMount(
        MountProfile.flatOnTopTopEdgeBack, axis = Vector3(0.0, -1.0, 0.0), up = Vector3(0.0, 0.0, 1.0),
        right = Vector3(-1.0, 0.0, 0.0), screenRight = Vector3(-1.0, 0.0, 0.0), screenUp = Vector3(0.0, -1.0, 0.0),
    ),
)

/** The hand-derived geometry of every preset, in the order of `MountProfile.presets`. */
internal val Support.expectedMounts: List<ExpectedMount>
    get() = expectedMountList

class PointingMountTests {
    @Test
    fun testPresetsMatchTheHandDerivedGeometry() {
        assertEquals(Support.expectedMounts.size, MountProfile.presets.size)
        for (expected in Support.expectedMounts) {
            val mount = expected.mount
            Support.assertVector(mount.cameraAxis, expected.axis, accuracy = 1e-12, message = mount.id)
            Support.assertVector(mount.cameraUp, expected.up, accuracy = 1e-12, message = mount.id)
            Support.assertVector(mount.cameraRight, expected.right, accuracy = 1e-12, message = mount.id)
            Support.assertVector(mount.screenRight, expected.screenRight, accuracy = 1e-12, message = mount.id)
            Support.assertVector(mount.screenUp, expected.screenUp, accuracy = 1e-12, message = mount.id)
        }
        assertEquals(Support.expectedMounts.map { it.mount.id }, MountProfile.presets.map { it.id })
    }

    @Test
    fun testPresetsAreOrthonormalRightHandedAndDistinct() {
        for (mount in MountProfile.presets) {
            assertEquals(1.0, mount.cameraAxis.length, 1e-12, mount.id)
            assertEquals(1.0, mount.cameraUp.length, 1e-12, mount.id)
            assertEquals(0.0, mount.cameraAxis.dot(mount.cameraUp), 1e-12, mount.id)
            assertEquals(1.0, mount.cameraRight.length, 1e-12, mount.id)
            assertEquals(1.0, mount.screenRight.length, 1e-12, mount.id)
            assertEquals(1.0, mount.screenUp.length, 1e-12, mount.id)
            Support.assertExactly(0.0, mount.screenRight.z, mount.id)
            Support.assertExactly(0.0, mount.screenUp.z, mount.id)
            assertEquals(0.0, mount.screenRight.dot(mount.screenUp), 1e-12, mount.id)
            // Seen from the front of the screen the arrow plane is not mirrored.
            Support.assertVector(mount.screenRight.cross(mount.screenUp), Vector3(0.0, 0.0, 1.0), accuracy = 1e-12, message = mount.id)
        }
        assertEquals(MountProfile.presets.size, MountProfile.presets.map { it.id }.toSet().size)
        assertEquals(MountProfile.presets.size, MountProfile.presets.map { it.name }.toSet().size)
    }

    @Test
    fun testLandscapeNamesFollowUIDeviceOrientation() {
        // landscapeLeft: the top edge (device +y) is on the photographer's left, the bottom edge
        // (home button / charging port) on the right.
        val left = MountProfile.backCameraLandscapeLeft
        assertTrue(left.cameraRight.dot(Vector3(0.0, 1.0, 0.0)) < -0.99)
        assertTrue(left.cameraRight.dot(Vector3(0.0, -1.0, 0.0)) > 0.99)
        // The portrait phone's right edge (+x) is then at the top of the picture.
        assertTrue(left.cameraUp.dot(Vector3(1.0, 0.0, 0.0)) > 0.99)
        val right = MountProfile.backCameraLandscapeRight
        assertTrue(right.cameraRight.dot(Vector3(0.0, 1.0, 0.0)) > 0.99)
        assertTrue(right.cameraUp.dot(Vector3(-1.0, 0.0, 0.0)) > 0.99)
    }

    @Test
    fun testFlatOnTopMountsPointAlongTheTopOrBottomEdge() {
        assertEquals(Vector3(0.0, 1.0, 0.0), MountProfile.flatOnTopTopEdgeForward.cameraAxis)
        assertEquals(Vector3(0.0, -1.0, 0.0), MountProfile.flatOnTopTopEdgeBack.cameraAxis)
        // The screen normal (towards the user, device +z) is the camera's up for both.
        assertEquals(Vector3(0.0, 0.0, 1.0), MountProfile.flatOnTopTopEdgeForward.cameraUp)
        assertEquals(Vector3(0.0, 0.0, 1.0), MountProfile.flatOnTopTopEdgeBack.cameraUp)
    }

    @Test
    fun testTiltingTheMountKeepsTheScreenMapping() {
        // Between the upright phone (axis −z) and the phone lying down (axis +y) the lens axis
        // swings in the y–z plane; the screen keeps showing right as right and up as up.
        var degrees = 0.0
        while (degrees <= 90.0) {
            val theta = AstroMath.radians(degrees)
            val mount = assertNotNull(
                MountProfile.custom(
                    cameraAxis = Vector3(0.0, sin(theta), -cos(theta)), cameraUp = Vector3(0.0, cos(theta), sin(theta)),
                ),
            )
            Support.assertVector(mount.screenRight, Vector3(1.0, 0.0, 0.0), accuracy = 1e-12, message = "$degrees°")
            Support.assertVector(mount.screenUp, Vector3(0.0, 1.0, 0.0), accuracy = 1e-12, message = "$degrees°")
            degrees += 15.0
        }
    }

    @Test
    fun testScreenMappingIsAlwaysAProperRotationOfTheViewPlane() {
        val random = PointingTestRandom(71)
        var accepted = 0
        repeat(500) {
            val mount = MountProfile.custom(cameraAxis = random.unitVector(), cameraUp = random.unitVector()) ?: return@repeat
            accepted += 1
            Support.assertExactly(0.0, mount.screenRight.z)
            Support.assertExactly(0.0, mount.screenUp.z)
            assertEquals(1.0, mount.screenRight.length, 1e-12)
            assertEquals(1.0, mount.screenUp.length, 1e-12)
            assertEquals(0.0, mount.screenRight.dot(mount.screenUp), 1e-9)
            Support.assertVector(mount.screenRight.cross(mount.screenUp), Vector3(0.0, 0.0, 1.0), accuracy = 1e-9)
        }
        assertTrue(accepted > 400)
    }

    @Test
    fun testAxisPointingOutOfTheScreenStillHasAMapping() {
        val mount = assertNotNull(MountProfile.custom(cameraAxis = Vector3(0.0, 0.0, 1.0), cameraUp = Vector3(0.0, 1.0, 0.0)))
        assertEquals(1.0, mount.screenRight.length, 1e-12)
        assertEquals(1.0, mount.screenUp.length, 1e-12)
        Support.assertVector(mount.screenRight.cross(mount.screenUp), Vector3(0.0, 0.0, 1.0), accuracy = 1e-12)
    }

    @Test
    fun testCustomMountMatchesTheEquivalentPreset() {
        for (expected in Support.expectedMounts) {
            val custom = assertNotNull(MountProfile.custom(cameraAxis = expected.axis * 3.0, cameraUp = expected.up * 0.5))
            Support.assertVector(custom.cameraAxis, expected.mount.cameraAxis, accuracy = 1e-12)
            Support.assertVector(custom.cameraUp, expected.mount.cameraUp, accuracy = 1e-12)
            Support.assertVector(custom.screenRight, expected.mount.screenRight, accuracy = 1e-12)
            Support.assertVector(custom.screenUp, expected.mount.screenUp, accuracy = 1e-12)
        }
    }

    @Test
    fun testCustomMountMakesUpPerpendicularToTheAxis() {
        val mount = assertNotNull(
            MountProfile.custom(name = "Tilted", cameraAxis = Vector3(0.0, 0.0, -2.0), cameraUp = Vector3(0.1, 1.0, 0.3)),
        )
        assertEquals("Tilted", mount.name)
        assertEquals("custom", mount.id)
        Support.assertVector(mount.cameraAxis, Vector3(0.0, 0.0, -1.0), accuracy = 1e-12)
        Support.assertVector(mount.cameraUp, Vector3(0.1, 1.0, 0.0).normalized, accuracy = 1e-12)
    }

    @Test
    fun testCustomMountRejectsDegenerateInput() {
        val up = Vector3(0.0, 1.0, 0.0)
        assertNull(MountProfile.custom(cameraAxis = Vector3.zero, cameraUp = up))
        assertNull(MountProfile.custom(cameraAxis = up, cameraUp = Vector3.zero))
        assertNull(MountProfile.custom(cameraAxis = up, cameraUp = up * 2.0)) // parallel
        assertNull(MountProfile.custom(cameraAxis = up, cameraUp = up * -1.0)) // anti-parallel
        assertNull(MountProfile.custom(cameraAxis = up, cameraUp = Vector3(0.05, 1.0, 0.0))) // within 3° of parallel
        assertNotNull(MountProfile.custom(cameraAxis = up, cameraUp = Vector3(0.5, 1.0, 0.0)))
        assertNull(MountProfile.custom(cameraAxis = Vector3(Double.NaN, 0.0, 1.0), cameraUp = up))
        assertNull(MountProfile.custom(cameraAxis = up, cameraUp = Vector3(0.0, Double.POSITIVE_INFINITY, 0.0)))
    }

    @Test
    fun testNamesAreLengthCapped() {
        val mount = assertNotNull(
            MountProfile.custom(
                name = "x".repeat(500), cameraAxis = Vector3(0.0, 0.0, -1.0), cameraUp = Vector3(0.0, 1.0, 0.0),
            ),
        )
        assertEquals(80, mount.name.length)
    }

    @Test
    fun testCodableRoundTrip() {
        val custom = assertNotNull(
            MountProfile.custom(
                name = "Hot shoe, tilted", cameraAxis = Vector3(0.1, 0.5, -0.8), cameraUp = Vector3(0.0, 0.8, 0.5),
            ),
        )
        for (mount in MountProfile.presets + custom) {
            val data = Json.encodeToString(MountProfile.serializer(), mount)
            val decoded = Json.decodeFromString(MountProfile.serializer(), data)
            assertEquals(mount.id, decoded.id)
            assertEquals(mount.name, decoded.name)
            Support.assertVector(decoded.cameraAxis, mount.cameraAxis, accuracy = 1e-15)
            Support.assertVector(decoded.cameraUp, mount.cameraUp, accuracy = 1e-15)
            Support.assertVector(decoded.screenRight, mount.screenRight, accuracy = 1e-15)
            Support.assertVector(decoded.screenUp, mount.screenUp, accuracy = 1e-15)
        }
    }

    @Test
    fun testDecodingRejectsInvalidGeometry() {
        val degenerate = """
        {"id":"x","name":"x","cameraAxis":{"x":0,"y":0,"z":0},"cameraUp":{"x":0,"y":1,"z":0}}
        """
        assertFailsWith<SerializationException> { Json.decodeFromString(MountProfile.serializer(), degenerate) }
        val parallel = """
        {"id":"x","name":"x","cameraAxis":{"x":0,"y":1,"z":0},"cameraUp":{"x":0,"y":2,"z":0}}
        """
        assertFailsWith<SerializationException> { Json.decodeFromString(MountProfile.serializer(), parallel) }
    }
}
