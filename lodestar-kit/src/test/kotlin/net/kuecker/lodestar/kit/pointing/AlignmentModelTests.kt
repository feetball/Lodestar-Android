package net.kuecker.lodestar.kit.pointing

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.PointingTestSupport as Support
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlignmentModelTests {
    private val mount = MountProfile.backCameraPortrait

    /**
     * A point for a camera that points exactly where the phone says (no errors at all), unless
     * `reportedAltitudeError` shifts the phone's idea of the altitude.
     */
    private fun point(
        azimuth: Double,
        altitude: Double,
        reportedAltitudeError: Double = 0.0,
        index: Int = 0,
        name: String? = null,
        mount: MountProfile? = null,
    ): AlignmentPoint {
        val usedMount = mount ?: this.mount
        val reported = HorizontalCoordinate(azimuth, altitude + reportedAltitudeError)
        val attitude = Support.attitude(mount = usedMount, frame = Support.cameraFrame(reported, 0.0))
        return AlignmentPoint(
            attitude, HorizontalCoordinate(azimuth, altitude),
            instantFromTimeIntervalSince1970(1_800_000_000.0 + index.toDouble()), name,
        )
    }

    // MARK: Identity

    @Test
    fun testIdentityModelDoesNothing() {
        val identity = AlignmentModel.identity
        Support.assertExactly(0.0, identity.headingOffset)
        assertEquals(Vector3.zero, identity.deviceCorrection)
        assertTrue(identity.points.isEmpty())
        assertFalse(identity.isAligned)
        Support.assertExactly(0.0, identity.rmsResidual)
        assertEquals(identity, AlignmentModel.solve(emptyList(), mount))
        for (preset in MountProfile.presets) assertTrue(identity.isApplicable(preset))
        Support.assertMatrix(identity.deviceCorrectionMatrix, Matrix3.identity, accuracy = 1e-15)
    }

    @Test
    fun testModelRecordsTheMountItWasSolvedFor() {
        val model = AlignmentModel.solve(listOf(point(10.0, 30.0)), mount)
        assertTrue(model.isAligned)
        assertEquals(mount.id, model.calibratedMount?.id)
        assertTrue(model.isApplicable(mount))
        assertFalse(model.isApplicable(MountProfile.backCameraLandscapeLeft))
        assertFalse(model.isApplicable(MountProfile.flatOnTopTopEdgeForward))
        // The same geometry under another name or id is the same mount.
        val sameGeometry = assertNotNull(
            MountProfile.custom("Mine", Vector3(0.0, 0.0, -1.0), Vector3(0.0, 2.0, 0.0)),
        )
        assertTrue(model.isApplicable(sameGeometry))
        val tilted = assertNotNull(MountProfile.custom(cameraAxis = Vector3(0.0, 0.05, -1.0), cameraUp = Vector3(0.0, 1.0, 0.0)))
        assertFalse(model.isApplicable(tilted))
    }

    // MARK: Rejection

    @Test
    fun testPointsNearTheZenithOrNadirAreRejected() {
        // Target too high (the camera follows it, so the boresight is too).
        val tooHigh = assertFailsWith<AlignmentError.TargetTooHigh> {
            AlignmentModel.solve(listOf(point(0.0, 82.0)), mount)
        }
        assertEquals(82.0, tooHigh.altitude, 1e-9)
        assertFailsWith<AlignmentError.TargetTooHigh> {
            AlignmentModel.solve(listOf(point(0.0, -80.5)), mount)
        }
        // Target fine, but the phone says the camera looks at 85°.
        val nearZenith = point(0.0, 60.0, reportedAltitudeError = 25.0)
        val boresightTooHigh = assertFailsWith<AlignmentError.BoresightTooHigh> {
            AlignmentModel.solve(listOf(nearZenith), mount)
        }
        assertEquals(85.0, boresightTooHigh.altitude, 1e-9)
        val nearNadir = point(0.0, -60.0, reportedAltitudeError = -25.0)
        assertFailsWith<AlignmentError.BoresightTooHigh> {
            AlignmentModel.solve(listOf(nearNadir), mount)
        }
        // Just inside the limit.
        AlignmentModel.solve(listOf(point(0.0, 79.9)), mount)
        AlignmentModel.solve(listOf(point(0.0, -79.9)), mount)
        // One bad point spoils the whole set.
        val fine = point(0.0, 30.0)
        assertFailsWith<AlignmentError> { AlignmentModel.solve(listOf(fine, point(0.0, 83.0)), mount) }
    }

    @Test
    fun testNonFiniteTargetsAreRejected() {
        val good = point(10.0, 30.0)
        val bad = AlignmentPoint(good.attitude, HorizontalCoordinate(Double.NaN, 30.0), good.timestamp)
        val error = assertFailsWith<AlignmentError> { AlignmentModel.solve(listOf(bad), mount) }
        assertEquals(AlignmentError.NonFiniteInput, error)
        assertFailsWith<AlignmentError> { AlignmentModel.solve(listOf(good, bad), mount) }
        assertFailsWith<AlignmentError> { AlignmentModel.identity.adding(bad, mount) }
    }

    @Test
    fun testImplausibleCorrectionsAreRejected() {
        // The phone says 10° altitude, the object is at 70°: a 60° "mount error" means a wrong object
        // was centred or the wrong mount is selected.
        val wrong = point(0.0, 70.0, reportedAltitudeError = -60.0)
        val error = assertFailsWith<AlignmentError.ImplausibleCorrection> { AlignmentModel.solve(listOf(wrong), mount) }
        assertEquals(60.0, error.degrees, 1e-6)
        // 30° is large but possible.
        AlignmentModel.solve(listOf(point(0.0, 60.0, reportedAltitudeError = -30.0)), mount)
    }

    @Test
    fun testPointNamesAreLengthCapped() {
        val long = point(0.0, 30.0, name = "N".repeat(1000))
        assertEquals(120, long.name?.length)
        assertNull(point(0.0, 30.0).name)
    }

    // MARK: Managing points

    @Test
    fun testAddingRefitsAndKeepsOnlyTheNewestTwelve() {
        var model = AlignmentModel.identity
        for (index in 0 until 15) {
            val azimuth = index.toDouble() * 23
            val altitude = 20 + (index % 5).toDouble() * 10
            model = model.adding(point(azimuth, altitude, reportedAltitudeError = 1.0, index = index, name = "p$index"), mount)
            assertTrue(model.points.size <= AlignmentModel.maximumPointCount)
            assertEquals(model.points.size, model.residuals.size)
        }
        assertEquals(12, model.points.size)
        assertEquals("p3", model.points.firstOrNull()?.name)
        assertEquals("p14", model.points.lastOrNull()?.name)
        // `solve` keeps the newest points of a long list in the same way.
        val all = (0 until 15).map { index ->
            point(
                index.toDouble() * 23, 20 + (index % 5).toDouble() * 10, reportedAltitudeError = 1.0,
                index = index, name = "p$index",
            )
        }
        val direct = AlignmentModel.solve(all, mount)
        assertEquals(model.points.map { it.name }, direct.points.map { it.name })
        assertEquals(12, AlignmentModel.maximumPointCount)
    }

    @Test
    fun testAddingAPointForAnotherMountStartsOver() {
        val first = AlignmentModel.solve(listOf(point(10.0, 30.0, index = 0, name = "old")), mount)
        val landscape = MountProfile.backCameraLandscapeLeft
        val added = first.adding(point(200.0, 40.0, index = 1, name = "new", mount = landscape), landscape)
        assertEquals(listOf<String?>("new"), added.points.map { it.name })
        assertEquals(landscape.id, added.calibratedMount?.id)
    }

    @Test
    fun testRemovingAPointRefitsTheRest() {
        val good1 = point(20.0, 25.0, index = 0, name = "a")
        val good2 = point(140.0, 55.0, index = 1, name = "b")
        // A mistaken point: the camera was not on this object.
        val outlier = point(260.0, 40.0, reportedAltitudeError = 6.0, index = 2, name = "oops")
        val model = AlignmentModel.solve(listOf(good1, good2, outlier), mount)
        assertTrue(model.rmsResidual > 0.5)
        val cleaned = model.removingPoint(2, mount)
        assertEquals(listOf<String?>("a", "b"), cleaned.points.map { it.name })
        assertTrue(cleaned.rmsResidual < 1e-3)
        assertTrue(cleaned.deviceCorrection.length < 1e-3)
        assertEquals(0.0, cleaned.headingOffset, 1e-3)
        // Out-of-range indices change nothing; removing the last point gives the identity.
        assertEquals(model, model.removingPoint(3, mount))
        assertEquals(model, model.removingPoint(-1, mount))
        val single = AlignmentModel.solve(listOf(good1), mount)
        assertEquals(AlignmentModel.identity, single.removingPoint(0, mount))
    }

    @Test
    fun testAlignmentBringsTheArrowToZero() {
        // The phone reads 10° too far west and 3° too low. Before aligning, guidance to the object is
        // off; after aligning on it, the camera is on target there and guidance is right nearby.
        val target = HorizontalCoordinate(150.0, 40.0)
        val reported = HorizontalCoordinate(140.0, 37.0)
        val attitude = Support.attitude(mount = mount, frame = Support.cameraFrame(reported, 0.0))
        val tracker = GuidanceTracker(onTargetThreshold = 0.5)
        val before = assertNotNull(tracker.update(PointingSolution.solve(attitude, mount), target))
        assertTrue(before.separation > 8)
        val model = AlignmentModel.identity.adding(
            AlignmentPoint(attitude, target, instantFromTimeIntervalSince1970(0.0)), mount,
        )
        tracker.reset()
        val aligned = PointingSolution.solve(attitude, mount, model)
        val after = assertNotNull(tracker.update(aligned, target))
        assertTrue(after.separation < 1e-6)
        assertEquals(GuidanceState.ON_TARGET, after.state)
    }

    // MARK: Persistence

    @Test
    fun testCodableRoundTripIsExact() {
        val random = PointingTestRandom(601)
        val scenario = AlignmentTestScenario(
            headingOffset = -23.0, correction = Vector3(1.2, -0.7, 0.0), mount = MountProfile.backCameraLandscapeRight,
        )
        val points = (0 until 4).map { index ->
            scenario.point(
                HorizontalCoordinate(random.uniform(0.0, 360.0), 20 + 12 * index.toDouble()),
                random.uniform(-30.0, 30.0), noiseDegrees = 0.1, random = random, index = index, name = "Star $index",
            )
        }
        val model = AlignmentModel.solve(points, MountProfile.backCameraLandscapeRight)
        val data = Json.encodeToString(AlignmentModel.serializer(), model)
        val decoded = Json.decodeFromString(AlignmentModel.serializer(), data)
        assertEquals(model, decoded)
        val identity = Json.decodeFromString(
            AlignmentModel.serializer(), Json.encodeToString(AlignmentModel.serializer(), AlignmentModel.identity),
        )
        assertEquals(AlignmentModel.identity, identity)
    }

    @Test
    fun testDecodingRejectsInconsistentModels() {
        val model = AlignmentModel.solve(
            listOf(
                point(10.0, 30.0, reportedAltitudeError = 2.0),
                point(120.0, 50.0, reportedAltitudeError = 2.0, index = 1),
            ),
            mount,
        )
        val data = Json.encodeToString(AlignmentModel.serializer(), model)
        val original = Json.parseToJsonElement(data).jsonObject

        fun decode(changes: (MutableMap<String, JsonElement>) -> Unit): AlignmentModel {
            val copy = original.toMutableMap()
            changes(copy)
            return Json.decodeFromString(AlignmentModel.serializer(), Json.encodeToString(JsonObject.serializer(), JsonObject(copy)))
        }
        decode { }
        assertFailsWith<SerializationException> { decode { it["headingOffset"] = JsonPrimitive(400.0) } }
        assertFailsWith<SerializationException> { decode { it["residuals"] = JsonArray(listOf(JsonPrimitive(0.1))) } }
        assertFailsWith<SerializationException> {
            decode { it["residuals"] = JsonArray(listOf(JsonPrimitive(0.1), JsonPrimitive(-0.2))) }
        }
        assertFailsWith<SerializationException> { decode { it["pointingUncertainty"] = JsonPrimitive(-1.0) } }
        // an aligned model must say how good it is
        assertFailsWith<SerializationException> { decode { it.remove("pointingUncertainty") } }
        assertFailsWith<SerializationException> {
            decode {
                it["deviceCorrection"] = JsonObject(
                    mapOf("x" to JsonPrimitive(60.0), "y" to JsonPrimitive(0.0), "z" to JsonPrimitive(0.0)),
                )
            }
        }
        val point = assertNotNull(original["points"]?.jsonArray?.firstOrNull()?.jsonObject)
        assertFailsWith<SerializationException> {
            decode {
                val bad = point.toMutableMap()
                bad["target"] = JsonObject(mapOf("azimuth" to JsonPrimitive(400.0), "altitude" to JsonPrimitive(10.0)))
                it["points"] = JsonArray(listOf(JsonObject(bad), JsonObject(bad)))
            }
        }
        assertFailsWith<SerializationException> {
            decode {
                val bad = point.toMutableMap()
                bad["target"] = JsonObject(mapOf("azimuth" to JsonPrimitive(40.0), "altitude" to JsonPrimitive(95.0)))
                it["points"] = JsonArray(listOf(JsonObject(bad), JsonObject(bad)))
            }
        }
        assertFailsWith<SerializationException> {
            decode {
                it["points"] = JsonArray(List(13) { point })
                it["residuals"] = JsonArray(List(13) { JsonPrimitive(0.1) })
            }
        }
        assertFailsWith<SerializationException> {
            decode {
                val bad = point.toMutableMap()
                fun vector(x: Double, y: Double, z: Double) =
                    JsonObject(mapOf("x" to JsonPrimitive(x), "y" to JsonPrimitive(y), "z" to JsonPrimitive(z)))
                bad["attitude"] = JsonObject(
                    mapOf(
                        "matrix" to JsonObject(
                            mapOf("r0" to vector(3.0, 0.0, 0.0), "r1" to vector(0.0, 1.0, 0.0), "r2" to vector(0.0, 0.0, 1.0)),
                        ),
                        "referenceAzimuth" to JsonPrimitive(0.0),
                        "timestamp" to JsonPrimitive(0.0),
                    ),
                )
                it["points"] = JsonArray(listOf(JsonObject(bad), JsonObject(bad)))
            }
        }
    }
}
