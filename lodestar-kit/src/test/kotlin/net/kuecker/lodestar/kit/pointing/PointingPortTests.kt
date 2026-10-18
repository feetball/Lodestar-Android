package net.kuecker.lodestar.kit.pointing

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.PointingTestSupport as Support
import java.lang.management.ManagementFactory
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests that have no Swift counterpart: the allocation-free projection overloads, the JSON forms, the
 * class-based filters and the places where Kotlin needed an explicit decision (IEEE comparisons,
 * grapheme clusters, strict decoding).
 */
class PointingPortTests {
    private companion object {
        val strictJson = Json { ignoreUnknownKeys = false }
        val lenientJson = Json { ignoreUnknownKeys = true }
        val explicitJson = Json { encodeDefaults = true; explicitNulls = true }
    }

    private val projection = assertNotNull(PinholeProjection.fromLongSideFieldOfView(390.0, 844.0, 65.0))

    private fun randomAttitude(random: PointingTestRandom): DeviceAttitude =
        assertNotNull(DeviceAttitude.create(random.rotation(), random.uniform(-180.0, 180.0), 3.0))

    // MARK: Allocation-free projection

    @Test
    fun testProjectIntoGivesTheNumbersOfProject() {
        val random = PointingTestRandom(9001)
        repeat(50) {
            val attitude = randomAttitude(random)
            val offset = random.uniform(-40.0, 40.0)
            val rotation = PinholeProjection.worldToDevice(attitude, offset)
            val out = DoubleArray(5)
            val outFloat = FloatArray(5)
            repeat(100) {
                val direction = random.unitVector() * random.uniform(0.1, 50.0)
                val expected = projection.project(direction, attitude, offset)
                out.fill(-1.0)
                outFloat.fill(-1f)
                val inFront = projection.projectInto(direction.x, direction.y, direction.z, rotation, out, 2)
                val inFrontFloat = projection.projectInto(direction.x, direction.y, direction.z, rotation, outFloat, 2)
                assertEquals(expected != null, inFront)
                assertEquals(expected != null, inFrontFloat)
                if (expected != null) {
                    // Identical arithmetic, so identical to the last bit.
                    assertTrue(expected.x == out[2] && expected.y == out[3], "$expected vs ${out.toList()}")
                    assertEquals(expected.x.toFloat(), outFloat[2])
                    assertEquals(expected.y.toFloat(), outFloat[3])
                }
                // Nothing outside out[2], out[3] is touched, and nothing at all when the point is not projected.
                assertEquals(-1.0, out[0])
                assertEquals(-1.0, out[1])
                assertEquals(-1.0, out[4])
                if (expected == null) {
                    assertEquals(-1.0, out[2])
                    assertEquals(-1.0, out[3])
                    assertEquals(-1f, outFloat[2])
                    assertEquals(-1f, outFloat[3])
                }
            }
        }
    }

    @Test
    fun testProjectIntoRejectsTheSameDirectionsAsProject() {
        val attitude = assertNotNull(DeviceAttitude.create(Matrix3.identity, 0.0, 0.0))
        val rotation = PinholeProjection.worldToDevice(attitude, 0.0)
        val out = DoubleArray(2)
        val bad = listOf(
            Vector3.zero, Vector3(Double.NaN, 0.0, 1.0), Vector3(0.0, Double.POSITIVE_INFINITY, 1.0),
            Vector3(-1e200, -1e200, -1e200), Vector3(1e200, 1e200, 1e200),
            // The camera looks along device −z; the reference frame is NWU, so world up is device +z: behind the camera.
            Vector3(0.0, 0.0, 1.0),
            // 90° off the optical axis.
            Vector3(1.0, 0.0, 0.0),
        )
        for (direction in bad) {
            assertNull(projection.project(direction, attitude, 0.0), "$direction")
            assertFalse(projection.projectInto(direction.x, direction.y, direction.z, rotation, out), "$direction")
        }
        // Straight ahead is the centre of the viewport.
        assertTrue(projection.projectInto(0.0, 0.0, -1.0, rotation, out))
        assertEquals(195.0, out[0], 1e-9)
        assertEquals(422.0, out[1], 1e-9)
    }

    @Test
    fun testProjectAllIntoMatchesTheListApi() {
        val random = PointingTestRandom(9002)
        val attitude = randomAttitude(random)
        val offset = 11.0
        val rotation = PinholeProjection.worldToDevice(attitude, offset)
        val directions = (0 until 300).map { random.unitVector() * random.uniform(0.5, 4.0) } + Vector3.zero
        val expected = projection.project(directions, attitude, offset)
        val packed = DoubleArray(3 * directions.size + 6) // spare room after `count`
        for ((i, v) in directions.withIndex()) {
            packed[3 * i] = v.x
            packed[3 * i + 1] = v.y
            packed[3 * i + 2] = v.z
        }
        val out = DoubleArray(2 * directions.size + 4)
        val outFloat = FloatArray(2 * directions.size + 4)
        val visible = BooleanArray(directions.size + 2)
        val visibleFloat = BooleanArray(directions.size)
        val count = projection.projectAllInto(packed, directions.size, rotation, out, visible)
        val countFloat = projection.projectAllInto(packed, directions.size, rotation, outFloat, visibleFloat)
        assertEquals(expected.count { it != null }, count)
        assertEquals(count, countFloat)
        assertTrue(count in 1 until directions.size, "the test needs points in front of and behind the camera")
        for (i in directions.indices) {
            val point = expected[i]
            assertEquals(point != null, visible[i])
            assertEquals(point != null, visibleFloat[i])
            if (point != null) {
                assertTrue(point.x == out[2 * i] && point.y == out[2 * i + 1])
                assertEquals(point.x.toFloat(), outFloat[2 * i])
                assertEquals(point.y.toFloat(), outFloat[2 * i + 1])
            } else {
                assertTrue(out[2 * i].isNaN() && out[2 * i + 1].isNaN())
                assertTrue(outFloat[2 * i].isNaN() && outFloat[2 * i + 1].isNaN())
            }
        }
        // Entries after `count` are not touched; the visibility array is optional.
        assertEquals(0.0, out[2 * directions.size])
        assertEquals(count, projection.projectAllInto(packed, directions.size, rotation, out))
        assertEquals(0, projection.projectAllInto(packed, 0, rotation, out))
    }

    @Test
    fun testProjectAllIntoChecksTheArraySizes() {
        val rotation = Matrix3.identity
        assertFailsWith<IllegalArgumentException> { projection.projectAllInto(DoubleArray(6), 3, rotation, DoubleArray(6)) }
        assertFailsWith<IllegalArgumentException> { projection.projectAllInto(DoubleArray(9), 3, rotation, DoubleArray(5)) }
        assertFailsWith<IllegalArgumentException> { projection.projectAllInto(DoubleArray(9), -1, rotation, DoubleArray(6)) }
        assertFailsWith<IllegalArgumentException> {
            projection.projectAllInto(DoubleArray(9), 3, rotation, FloatArray(6), BooleanArray(2))
        }
        projection.projectAllInto(DoubleArray(9), 3, rotation, FloatArray(6), BooleanArray(3))
    }

    @Test
    fun testUnprojectIntoGivesTheNumbersOfUnproject() {
        val random = PointingTestRandom(9003)
        repeat(50) {
            val attitude = randomAttitude(random)
            val offset = random.uniform(-40.0, 40.0)
            val rotation = PinholeProjection.deviceToWorld(attitude, offset)
            val out = DoubleArray(5) { -1.0 }
            repeat(50) {
                val x = random.uniform(-300.0, 700.0)
                val y = random.uniform(-500.0, 1300.0)
                val expected = assertNotNull(projection.unproject(x, y, attitude, offset))
                assertTrue(projection.unprojectInto(x, y, rotation, out, 1))
                assertTrue(expected.x == out[1] && expected.y == out[2] && expected.z == out[3], "$expected vs ${out.toList()}")
            }
            assertEquals(-1.0, out[0])
            assertEquals(-1.0, out[4])
        }
        val untouched = DoubleArray(3) { -1.0 }
        assertFalse(projection.unprojectInto(Double.NaN, 3.0, Matrix3.identity, untouched))
        assertFalse(projection.unprojectInto(3.0, Double.POSITIVE_INFINITY, Matrix3.identity, untouched))
        assertContentEquals(doubleArrayOf(-1.0, -1.0, -1.0), untouched)
    }

    @Test
    fun testContainsOnNumbersMatchesContainsOnAPoint() {
        val random = PointingTestRandom(9008)
        repeat(1000) {
            val x = random.uniform(-100.0, 500.0)
            val y = random.uniform(-100.0, 950.0)
            val margin = random.uniform(0.0, 60.0)
            assertEquals(projection.contains(ProjectedPoint(x, y, 1.0), margin), projection.contains(x, y, margin))
            assertEquals(projection.contains(ProjectedPoint(x, y, 1.0)), projection.contains(x, y))
        }
        assertTrue(projection.contains(0.0, 0.0))
        assertTrue(projection.contains(390.0, 844.0))
        assertFalse(projection.contains(Double.NaN, 10.0))
        assertFalse(projection.contains(-0.5, 10.0))
        assertTrue(projection.contains(-0.5, 10.0, 1.0))
    }

    @Test
    fun testTheRotationHelpersApplyTheHeadingOffset() {
        val random = PointingTestRandom(9004)
        repeat(20) {
            val attitude = randomAttitude(random)
            val offset = random.uniform(-90.0, 90.0)
            assertEquals(attitude.withHeadingOffset(offset).worldToDevice, PinholeProjection.worldToDevice(attitude, offset))
            assertEquals(attitude.withHeadingOffset(offset).deviceToWorld, PinholeProjection.deviceToWorld(attitude, offset))
            Support.assertMatrix(
                PinholeProjection.worldToDevice(attitude, offset) * PinholeProjection.deviceToWorld(attitude, offset),
                Matrix3.identity,
            )
        }
    }

    @Test
    fun testTheProjectionOverloadsDoNotAllocate() {
        val bean = try {
            ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        } catch (_: LinkageError) {
            null
        }
        if (bean == null || !bean.isThreadAllocatedMemorySupported || !bean.isThreadAllocatedMemoryEnabled) return
        val random = PointingTestRandom(9005)
        val attitude = randomAttitude(random)
        val rotation = PinholeProjection.worldToDevice(attitude, 5.0)
        val inverse = PinholeProjection.deviceToWorld(attitude, 5.0)
        val directions = DoubleArray(3 * 2000)
        for (i in 0 until 2000) {
            val v = random.unitVector()
            directions[3 * i] = v.x
            directions[3 * i + 1] = v.y
            directions[3 * i + 2] = v.z
        }
        val out = DoubleArray(2 * 2000)
        val outFloat = FloatArray(2 * 2000)
        val visible = BooleanArray(2000)
        val ray = DoubleArray(3)
        fun frame() {
            for (i in 0 until 2000) {
                projection.projectInto(directions[3 * i], directions[3 * i + 1], directions[3 * i + 2], rotation, out, 2 * i)
                projection.projectInto(directions[3 * i], directions[3 * i + 1], directions[3 * i + 2], rotation, outFloat, 2 * i)
                projection.unprojectInto(out[2 * i], out[2 * i + 1], inverse, ray)
            }
            projection.projectAllInto(directions, 2000, rotation, out, visible)
            projection.projectAllInto(directions, 2000, rotation, outFloat, visible)
        }
        repeat(10) { frame() } // loads the classes and warms the code up
        val before = bean.currentThreadAllocatedBytes
        repeat(10) { frame() }
        val allocated = bean.currentThreadAllocatedBytes - before
        // 10 frames make 60 000 projections; one object per call would be about a megabyte. What is left is
        // the JVM's own bookkeeping while it compiles the loops.
        assertTrue(allocated < 8 * 1024, "$allocated bytes allocated by the allocation-free overloads")
    }

    // MARK: JSON forms

    @Test
    fun testDeviceAttitudeJsonForm() {
        val attitude = assertNotNull(DeviceAttitude.create(Matrix3.identity, 10.0, 2.5))
        val text = Json.encodeToString(DeviceAttitude.serializer(), attitude)
        assertEquals(
            """{"matrix":{"r0":{"x":1.0,"y":0.0,"z":0.0},"r1":{"x":0.0,"y":1.0,"z":0.0},"r2":{"x":0.0,"y":0.0,"z":1.0}},""" +
                """"referenceAzimuth":10.0,"timestamp":2.5}""",
            text,
        )
        assertEquals(attitude, Json.decodeFromString(DeviceAttitude.serializer(), text))
        // The azimuth is normalised on the way in: -190 is 170.
        val shifted = text.replace(""""referenceAzimuth":10.0""", """"referenceAzimuth":-190""")
        assertEquals(170.0, Json.decodeFromString(DeviceAttitude.serializer(), shifted).referenceAzimuth)
    }

    @Test
    fun testMountProfileJsonForm() {
        val text = Json.encodeToString(MountProfile.serializer(), MountProfile.backCameraPortrait)
        assertEquals(
            """{"id":"backCamera.portrait","name":"Back camera, portrait",""" +
                """"cameraAxis":{"x":0.0,"y":0.0,"z":-1.0},"cameraUp":{"x":0.0,"y":1.0,"z":0.0}}""",
            text,
        )
        assertEquals(MountProfile.backCameraPortrait, Json.decodeFromString(MountProfile.serializer(), text))
        for (preset in MountProfile.presets) {
            assertEquals(preset, Json.decodeFromString(MountProfile.serializer(), Json.encodeToString(MountProfile.serializer(), preset)))
        }
    }

    @Test
    fun testAlignmentPointJsonFormUsesTheReferenceDateAndOmitsAMissingName() {
        val attitude = assertNotNull(DeviceAttitude.create(Matrix3.identity, 0.0, 0.0))
        val timestamp = Instant.parse("2001-01-01T00:00:10Z")
        val unnamed = AlignmentPoint(attitude, HorizontalCoordinate(10.0, 20.0), timestamp)
        val named = AlignmentPoint(attitude, HorizontalCoordinate(10.0, 20.0), timestamp, "Vega")
        for (config in listOf(Json, explicitJson)) {
            val unnamedObject = config.parseToJsonElement(config.encodeToString(AlignmentPoint.serializer(), unnamed)).jsonObject
            val namedObject = config.parseToJsonElement(config.encodeToString(AlignmentPoint.serializer(), named)).jsonObject
            assertEquals(setOf("attitude", "target", "timestamp"), unnamedObject.keys)
            assertEquals(setOf("attitude", "target", "timestamp", "name"), namedObject.keys)
            assertEquals(JsonPrimitive(10.0), unnamedObject["timestamp"])
            assertEquals(JsonPrimitive("Vega"), namedObject["name"])
            assertEquals(
                JsonObject(mapOf("azimuth" to JsonPrimitive(10.0), "altitude" to JsonPrimitive(20.0))), unnamedObject["target"],
            )
            assertEquals(named, config.decodeFromString(AlignmentPoint.serializer(), config.encodeToString(AlignmentPoint.serializer(), named)))
        }
        // A stored `null` name means "no name", like Swift's decodeIfPresent.
        val withNull = JsonObject(
            Json.parseToJsonElement(Json.encodeToString(AlignmentPoint.serializer(), unnamed)).jsonObject + ("name" to JsonNull),
        )
        assertEquals(unnamed, Json.decodeFromJsonElement(AlignmentPoint.serializer(), withNull))
    }

    @Test
    fun testAlignmentModelJsonFormLeavesOutWhatIsUnknown() {
        val text = Json.encodeToString(AlignmentModel.serializer(), AlignmentModel.identity)
        assertEquals("""{"headingOffset":0.0,"deviceCorrection":{"x":0.0,"y":0.0,"z":0.0},"points":[],"residuals":[]}""", text)
        assertEquals(text, explicitJson.encodeToString(AlignmentModel.serializer(), AlignmentModel.identity))
        val mount = MountProfile.backCameraPortrait
        val attitude = Support.attitude(mount = mount, frame = Support.cameraFrame(HorizontalCoordinate(10.0, 30.0), 0.0))
        val model = AlignmentModel.solve(
            listOf(AlignmentPoint(attitude, HorizontalCoordinate(12.0, 31.0), Instant.parse("2027-01-15T08:00:00Z"))), mount,
        )
        val keys = Json.parseToJsonElement(Json.encodeToString(AlignmentModel.serializer(), model)).jsonObject.keys
        assertEquals(
            setOf("headingOffset", "deviceCorrection", "points", "residuals", "pointingUncertainty", "calibratedMount"), keys,
        )
    }

    @Test
    fun testHeadingOffsetOfADecodedModelIsNormalised() {
        fun decoded(heading: Double): Double {
            val text = """{"headingOffset":$heading,"deviceCorrection":{"x":0,"y":0,"z":0},"points":[],"residuals":[]}"""
            return Json.decodeFromString(AlignmentModel.serializer(), text).headingOffset
        }
        assertEquals(180.0, decoded(-180.0)) // the range is (−180, 180]
        assertEquals(180.0, decoded(180.0))
        assertEquals(-179.0, decoded(-179.0))
        assertFailsWith<SerializationException> { decoded(180.5) }
    }

    @Test
    fun testDecodingIgnoresUnknownKeysWhateverTheJsonConfiguration() {
        val model = AlignmentModel.solve(
            listOf(
                run {
                    val mount = MountProfile.backCameraPortrait
                    val attitude = Support.attitude(mount = mount, frame = Support.cameraFrame(HorizontalCoordinate(10.0, 30.0), 0.0))
                    AlignmentPoint(attitude, HorizontalCoordinate(12.0, 31.0), Instant.parse("2027-01-15T08:00:00Z"), "Vega")
                },
            ),
            MountProfile.backCameraPortrait,
        )
        fun JsonElement.withExtra(): JsonElement = when (this) {
            is JsonObject -> JsonObject(mapValues { it.value.withExtra() } + ("extra" to JsonPrimitive("ignored")))
            is JsonArray -> JsonArray(map { it.withExtra() })
            else -> this
        }
        val noisy = Json.parseToJsonElement(Json.encodeToString(AlignmentModel.serializer(), model)).withExtra()
        assertEquals(model, Json.decodeFromJsonElement(AlignmentModel.serializer(), noisy))
        assertEquals(model, strictJson.decodeFromString(AlignmentModel.serializer(), noisy.toString()))
        assertEquals(model, lenientJson.decodeFromString(AlignmentModel.serializer(), noisy.toString()))
    }

    @Test
    fun testDecodingIsAsStrictAsSwiftsJSONDecoder() {
        val good = """{"matrix":{"r0":{"x":1,"y":0,"z":0},"r1":{"x":0,"y":1,"z":0},"r2":{"x":0,"y":0,"z":1}},"referenceAzimuth":5,"timestamp":1.5}"""
        assertEquals(5.0, Json.decodeFromString(DeviceAttitude.serializer(), good).referenceAzimuth)
        val rejected = listOf(
            good.replace(""""referenceAzimuth":5""", """"referenceAzimuth":"5""""), // a quoted number
            good.replace(""""referenceAzimuth":5""", """"referenceAzimuth":null"""),
            good.replace(""""referenceAzimuth":5""", """"referenceAzimuth":true"""),
            good.replace(""""referenceAzimuth":5""", """"referenceAzimuth":1e999"""), // does not fit a Double
            good.replace(""","timestamp":1.5""", ""),
            good.replace(""""r2":{"x":0,"y":0,"z":1}""", """"r2":[0,0,1]"""),
            good.replace(""""r2":{"x":0,"y":0,"z":1}""", """"r2":{"x":0,"y":0}"""),
            """[1,2,3]""",
            """"attitude"""",
        )
        for (text in rejected) {
            assertFailsWith<SerializationException>(text) { Json.decodeFromString(DeviceAttitude.serializer(), text) }
        }
        val badMount = """{"id":5,"name":"x","cameraAxis":{"x":0,"y":0,"z":-1},"cameraUp":{"x":0,"y":1,"z":0}}"""
        assertFailsWith<SerializationException> { Json.decodeFromString(MountProfile.serializer(), badMount) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun testDecodingNeedsJson() {
        // The decoders read the JSON tree, so another format is refused clearly instead of misreading it.
        val decoder = object : kotlinx.serialization.encoding.AbstractDecoder() {
            override val serializersModule = kotlinx.serialization.modules.EmptySerializersModule()
            override fun decodeElementIndex(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) =
                kotlinx.serialization.encoding.CompositeDecoder.DECODE_DONE
        }
        assertFailsWith<SerializationException> { DeviceAttitude.serializer().deserialize(decoder) }
    }

    // MARK: Text, IEEE comparisons and value semantics

    @Test
    fun testNamesAreCappedByCharactersNotByUtf16Units() {
        // "é" written as e + combining acute is two UTF-16 units but one character, as in Swift.
        val decomposed = "é".repeat(130)
        val attitude = assertNotNull(DeviceAttitude.create(Matrix3.identity))
        val point = AlignmentPoint(attitude, HorizontalCoordinate(1.0, 2.0), Instant.EPOCH, decomposed)
        assertEquals("é".repeat(120), point.name)
        // A surrogate pair (one character) is never cut in half.
        val emoji = "😀".repeat(130)
        assertEquals("😀".repeat(120), emoji.prefixCharacters(120))
        assertEquals(240, emoji.prefixCharacters(120).length)
        if (Runtime.version().feature() >= 20) {
            // JDK 20 and Android segment emoji sequences like Swift: a family, a flag and a skin tone are one character each.
            val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
            val flag = "\uD83C\uDDFA\uD83C\uDDF8"
            val thumb = "\uD83D\uDC4D\uD83C\uDFFD"
            assertEquals(family.repeat(3), (family.repeat(5)).prefixCharacters(3))
            assertEquals(flag.repeat(2), (flag.repeat(5)).prefixCharacters(2))
            assertEquals(thumb, (thumb.repeat(5)).prefixCharacters(1))
        }
        assertEquals("abc", "abc".prefixCharacters(120))
        assertEquals("", "abc".prefixCharacters(0))
        assertEquals("ab", "abc".prefixCharacters(2))
        val mount = assertNotNull(
            MountProfile.custom(name = decomposed, cameraAxis = Vector3(0.0, 0.0, -1.0), cameraUp = Vector3(0.0, 1.0, 0.0)),
        )
        assertEquals("é".repeat(80), mount.name)
    }

    @Test
    fun testHugeVectorsCountAsZeroLikeInSwift() {
        // Their length overflows, so the normalised vector is -0.0 or 0.0 in every component, which Swift's
        // `== .zero` accepts (a data-class equals would tell -0.0 from 0.0).
        val up = Vector3(0.0, 1.0, 0.0)
        assertNull(MountProfile.custom(cameraAxis = Vector3(-1e200, -1e200, -1e200), cameraUp = up))
        assertNull(MountProfile.custom(cameraAxis = Vector3(1e200, 1e200, 1e200), cameraUp = up))
        val attitude = assertNotNull(DeviceAttitude.create(Matrix3.identity))
        assertNull(projection.project(Vector3(-1e200, -1e200, -1e200), attitude, 0.0))
        assertTrue(PointingMath.isZero(Vector3(-0.0, 0.0, -0.0)))
        assertFalse(PointingMath.isZero(Vector3(0.0, 1e-300, 0.0)))
    }

    @Test
    fun testSmoothersAreIndependentStatefulObjects() {
        val base = Matrix3.rotation(Vector3(1.0, 2.0, 3.0), 0.9)
        fun sample(degrees: Double, time: Double) = assertNotNull(
            DeviceAttitude.create(Matrix3.rotation(Vector3(0.3, -0.5, 0.8), AstroMath.radians(degrees)) * base, 0.0, time),
        )
        val first = AttitudeSmoother()
        val second = AttitudeSmoother()
        val inputs = (0 until 60).map { sample(it * 0.05, it / 60.0) }
        val outputs = inputs.map { first.update(it) }
        // The second filter has seen nothing yet: its first sample passes through, unlike the first filter's.
        assertSame(inputs[10], second.update(inputs[10]))
        assertNotSame(inputs[10], outputs[10])
        // Same input sequence, same filter parameters, same output.
        val replay = AttitudeSmoother()
        assertEquals(outputs, inputs.map { replay.update(it) })
        // The second filter carries on from its own history, which the first one knows nothing about.
        val twin = AttitudeSmoother()
        twin.update(inputs[10])
        assertEquals(twin.update(inputs[11]), second.update(inputs[11]))
        // `reset` forgets the state of one object only.
        first.reset()
        assertSame(inputs[30], first.update(inputs[30]))
        assertNotSame(inputs[12], second.update(inputs[12]))
    }

    @Test
    fun testTrackerEqualityFollowsItsParametersAndState() {
        val solution = PointingSolution.solve(
            assertNotNull(DeviceAttitude.create(Matrix3.identity)), MountProfile.backCameraPortrait,
        )
        val a = GuidanceTracker(1.0)
        val b = GuidanceTracker(1.0)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, GuidanceTracker(2.0))
        assertNotEquals(a, GuidanceTracker(1.0, 9.0))
        val target = solution.boresight // exactly on target
        assertEquals(GuidanceState.ON_TARGET, assertNotNull(a.update(solution, target)).state)
        assertNotEquals(a, b) // `a` is on target now, `b` is not
        a.reset()
        assertEquals(a, b)
        assertTrue(a.toString().contains("onTargetThreshold=1.0"))
    }

    @Test
    fun testGuidanceMatchesTheAnglesOfTheSolution() {
        val random = PointingTestRandom(9006)
        repeat(100) {
            val attitude = randomAttitude(random)
            val solution = PointingSolution.solve(attitude, MountProfile.backCameraPortrait)
            val target = random.horizontal()
            val guidance = assertNotNull(GuidanceTracker(0.5).update(solution, target))
            assertEquals(solution.boresight.angularSeparation(target), guidance.separation, 1e-9)
            assertEquals(
                AstroMath.normalizeSignedDegrees(target.azimuth - solution.boresight.azimuth), guidance.panDelta, 1e-12,
            )
            assertEquals(target.altitude - solution.boresight.altitude, guidance.tiltDelta, 1e-12)
            assertEquals(solution.boresightVector.dot(target.unitVector) > 0, guidance.isTargetInFront)
        }
    }

    @Test
    fun testAlignmentErrorsAreValuesWithMessages() {
        assertEquals<AlignmentError>(AlignmentError.BoresightTooHigh(85.0), AlignmentError.BoresightTooHigh(85.0))
        assertNotEquals<AlignmentError>(AlignmentError.TargetTooHigh(82.0), AlignmentError.BoresightTooHigh(82.0))
        assertEquals<AlignmentError>(AlignmentError.NonFiniteInput, AlignmentError.NonFiniteInput)
        assertTrue(AlignmentError.BoresightTooHigh(85.0).message.orEmpty().contains("85.0"))
        assertTrue(AlignmentError.TargetTooHigh(82.5).message.orEmpty().contains("82.5"))
        assertTrue(AlignmentError.ImplausibleCorrection(60.0).message.orEmpty().contains("60.0"))
        assertTrue(AlignmentError.NonFiniteInput.message.orEmpty().contains("finite"))
        // `when` over the sealed class is exhaustive.
        val error: AlignmentError = AlignmentError.TargetTooHigh(82.0)
        val text = when (error) {
            AlignmentError.NonFiniteInput -> "non-finite"
            is AlignmentError.BoresightTooHigh -> "boresight ${error.altitude}"
            is AlignmentError.TargetTooHigh -> "target ${error.altitude}"
            is AlignmentError.ImplausibleCorrection -> "correction ${error.degrees}"
        }
        assertEquals("target 82.0", text)
    }

    @Test
    fun testAlignmentModelKeepsItsOwnCopyOfTheLists() {
        val mount = MountProfile.backCameraPortrait
        val attitude = Support.attitude(mount = mount, frame = Support.cameraFrame(HorizontalCoordinate(10.0, 30.0), 0.0))
        val point = AlignmentPoint(attitude, HorizontalCoordinate(10.0, 30.0), Instant.EPOCH)
        val points = mutableListOf(point)
        val residuals = mutableListOf(0.0)
        val model = AlignmentModel(
            headingOffset = 190.0, deviceCorrection = Vector3.zero, points = points, residuals = residuals,
            pointingUncertainty = 1.0, calibratedMount = mount,
        )
        points.clear()
        residuals.add(5.0)
        assertEquals(listOf(point), model.points)
        assertEquals(listOf(0.0), model.residuals)
        assertEquals(-170.0, model.headingOffset) // normalised to (−180, 180]
        assertEquals(0.0, model.rmsResidual)
    }

    @Test
    fun testHeadingOffsetForAMountIsZeroUnlessTheModelApplies() {
        val mount = MountProfile.backCameraPortrait
        val attitude = Support.attitude(mount = mount, frame = Support.cameraFrame(HorizontalCoordinate(100.0, 30.0), 0.0))
        val model = AlignmentModel.solve(
            listOf(AlignmentPoint(attitude, HorizontalCoordinate(110.0, 33.0), Instant.EPOCH)), mount,
        )
        assertEquals(10.0, model.headingOffset, 1e-9)
        assertEquals(model.headingOffset, model.headingOffset(mount))
        assertEquals(0.0, model.headingOffset(MountProfile.backCameraLandscapeLeft))
        assertEquals(0.0, AlignmentModel.identity.headingOffset(mount))
    }

    @Test
    fun testSolvingNeverMutatesTheArguments() {
        val mount = MountProfile.backCameraPortrait
        val random = PointingTestRandom(9007)
        val scenario = AlignmentTestScenario(headingOffset = 7.0, correction = Vector3(1.0, 0.5, 0.0), mount = mount)
        val points = (0 until 5).map { index ->
            scenario.point(HorizontalCoordinate(index * 70.0, 20 + 10 * index.toDouble()), 5.0 * index, random = random, index = index)
        }
        val copy = points.toList()
        val first = AlignmentModel.solve(points, mount)
        val second = AlignmentModel.solve(points, mount)
        assertEquals(copy, points)
        assertEquals(first, second) // deterministic
        assertTrue(abs(first.headingOffset - 7.0) < 0.05)
    }

    @Test
    fun testPresetsAreSharedImmutableValues() {
        assertSame(MountProfile.backCameraPortrait, MountProfile.presets[0])
        assertEquals(6, MountProfile.presets.size)
        assertEquals(
            listOf(
                "backCamera.portrait", "backCamera.portraitUpsideDown", "backCamera.landscapeLeft",
                "backCamera.landscapeRight", "flatOnTop.topEdgeForward", "flatOnTop.topEdgeBack",
            ),
            MountProfile.presets.map { it.id },
        )
    }
}
