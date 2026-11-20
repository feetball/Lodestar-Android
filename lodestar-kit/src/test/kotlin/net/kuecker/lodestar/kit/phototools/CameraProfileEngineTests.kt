package net.kuecker.lodestar.kit.phototools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.Vector3
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Camera profiles: lens projections, the fisheye field of view, the frame outline and the presets. Every number that
// is not a formula's own result was computed independently (Python, double precision) from the mappings named in
// LensProjection: r = f·tan θ, r = f·θ, r = 2f·sin(θ/2) and r = 2f·tan(θ/2).

private val zAxis = Vector3(0.0, 0.0, 1.0)

private fun fieldOfViewOf(
    sensor: SensorFormat,
    focalLength: Double,
    projection: LensProjection,
    imageCircle: Double? = null,
): FrameSize = FieldOfView.of(sensor, focalLength, projection, imageCircle)

private fun anglesFromAxis(outline: List<Vector3>): List<Double> = outline.map { it.angle(zAxis) }

// MARK: - Lens projections

class LensProjectionTests {

    @Test
    fun testRawValuesAndIdentifiers() {
        assertEquals(
            listOf("rectilinear", "fisheyeEquidistant", "fisheyeEquisolid", "fisheyeStereographic"),
            LensProjection.entries.map { it.rawValue },
        )
        for (projection in LensProjection.entries) {
            assertEquals(projection.rawValue, projection.id)
            assertEquals(projection, LensProjection.fromRawValue(projection.rawValue))
        }
        assertNull(LensProjection.fromRawValue("fisheye"))
        assertNull(LensProjection.fromRawValue(""))
    }

    @Test
    fun testDisplayNamesAreDistinctAndOnlyTheOrdinaryLensIsNotAFisheye() {
        assertEquals(LensProjection.entries.size, LensProjection.entries.map { it.displayName }.toSet().size)
        assertEquals("Rectilinear", LensProjection.RECTILINEAR.displayName)
        assertFalse(LensProjection.RECTILINEAR.isFisheye)
        for (projection in LensProjection.entries.drop(1)) assertTrue(projection.isFisheye, projection.rawValue)
    }

    @Test
    fun testForwardMappings() {
        // r = f·tan θ: 24 mm at 30° is 13.8564 mm.
        assertEquals(13.856406460551018, assertNotNull(LensProjection.RECTILINEAR.imageRadiusMillimeters(30.0, 24.0)), 1e-9)
        // r = f·θ: 10 mm at 30° is 10 · π/6.
        assertEquals(5.235987755982988, assertNotNull(LensProjection.FISHEYE_EQUIDISTANT.imageRadiusMillimeters(30.0, 10.0)), 1e-9)
        // r = 2f·sin(θ/2): 8 mm at 90° is 16 · sin 45°, the radius of a circular fisheye's 180° image.
        assertEquals(11.31370849898476, assertNotNull(LensProjection.FISHEYE_EQUISOLID.imageRadiusMillimeters(90.0, 8.0)), 1e-9)
        // r = 2f·tan(θ/2): 10 mm at 90° is 20 mm.
        assertEquals(20.0, assertNotNull(LensProjection.FISHEYE_STEREOGRAPHIC.imageRadiusMillimeters(90.0, 10.0)), 1e-9)
        for (projection in LensProjection.entries) {
            assertEquals(0.0, assertNotNull(projection.imageRadiusMillimeters(0.0, 20.0)), 0.0, projection.rawValue)
        }
    }

    @Test
    fun testTheInverseMappingUndoesTheForwardOne() {
        for (projection in LensProjection.entries) {
            for (angle in listOf(0.0, 1.0, 10.0, 45.0, 80.0, 89.0)) {
                val radius = assertNotNull(projection.imageRadiusMillimeters(angle, 17.0))
                assertEquals(angle, projection.angleDegrees(radius, 17.0), 1e-9, "${projection.rawValue} at $angle")
            }
        }
        for (projection in LensProjection.entries.drop(1)) {
            for (angle in listOf(100.0, 135.0, 170.0)) {
                val radius = assertNotNull(projection.imageRadiusMillimeters(angle, 17.0))
                assertEquals(angle, projection.angleDegrees(radius, 17.0), 1e-9, "${projection.rawValue} at $angle")
            }
        }
    }

    @Test
    fun testAnImageDistanceBeyondTheMappingGivesHalfTurn() {
        // An equisolid lens reaches at most 2f from the centre; an equidistant one π·f.
        assertEquals(180.0, LensProjection.FISHEYE_EQUISOLID.angleDegrees(16.0, 8.0), 1e-9)
        assertEquals(180.0, LensProjection.FISHEYE_EQUISOLID.angleDegrees(18.0, 8.0))
        assertEquals(180.0, LensProjection.FISHEYE_EQUIDISTANT.angleDegrees(40.0, 10.0))
        assertTrue(LensProjection.FISHEYE_STEREOGRAPHIC.angleDegrees(1e12, 10.0) <= 180.0)
        assertTrue(LensProjection.RECTILINEAR.angleDegrees(1e12, 10.0) <= 180.0)
    }

    @Test
    fun testTheForwardMappingRefusesWhatALensCannotImage() {
        assertNull(LensProjection.RECTILINEAR.imageRadiusMillimeters(90.0, 24.0))
        assertNull(LensProjection.RECTILINEAR.imageRadiusMillimeters(120.0, 24.0))
        assertNull(LensProjection.FISHEYE_STEREOGRAPHIC.imageRadiusMillimeters(180.0, 10.0))
        assertNotNull(LensProjection.FISHEYE_EQUISOLID.imageRadiusMillimeters(180.0, 10.0))
        for (projection in LensProjection.entries) {
            assertNull(projection.imageRadiusMillimeters(-1.0, 10.0), projection.rawValue)
            assertNull(projection.imageRadiusMillimeters(181.0, 10.0), projection.rawValue)
            assertNull(projection.imageRadiusMillimeters(Double.NaN, 10.0), projection.rawValue)
            assertNull(projection.imageRadiusMillimeters(10.0, 0.0), projection.rawValue)
            assertNull(projection.imageRadiusMillimeters(10.0, Double.NaN), projection.rawValue)
            assertNull(projection.imageRadiusMillimeters(10.0, Double.POSITIVE_INFINITY), projection.rawValue)
        }
    }

    @Test
    fun testTheCentreOfTheFrameIsOnTheAxis() {
        for (projection in LensProjection.entries) {
            assertEquals(0.0, projection.angleDegrees(0.0, 24.0), projection.rawValue)
        }
    }
}

// MARK: - Field of view with a projection

class FisheyeFieldOfViewTests {

    @Test
    fun testTheRectilinearNumbersAreUnchanged() {
        // The formula the engine used before projections existed, evaluated in the same order.
        fun legacy(extent: Double, focalLength: Double): Double = 2.0 * atan(extent / (2.0 * focalLength)) * 180.0 / PI
        for (sensor in SensorFormat.presets + SensorFormat.custom(width = 24.0, height = 16.0)) {
            for (focalLength in listOf(4.0, 8.0, 14.0, 24.0, 35.0, 50.0, 85.0, 200.0, 600.0, 2000.0)) {
                val frame = FieldOfView.of(sensor, focalLength)
                assertEquals(legacy(sensor.widthMillimeters, focalLength), frame.horizontal, "${sensor.id} $focalLength")
                assertEquals(legacy(sensor.heightMillimeters, focalLength), frame.vertical, "${sensor.id} $focalLength")
                assertEquals(legacy(sensor.diagonalMillimeters, focalLength), frame.diagonal, "${sensor.id} $focalLength")
                assertEquals(frame, fieldOfViewOf(sensor, focalLength, LensProjection.RECTILINEAR))
            }
        }
    }

    @Test
    fun testAnEightMillimetreEquisolidLensOnFullFrameSeesHalfTheSphereOrMore() {
        // r = 18, 12 and 21.6 mm all lie beyond the 2f = 16 mm edge of the mapping or past 90°, so the angles are capped.
        val frame = fieldOfViewOf(SensorFormat.fullFrame, 8.0, LensProjection.FISHEYE_EQUISOLID)
        assertEquals(180.0, frame.horizontal)
        assertEquals(180.0, frame.vertical)
        assertEquals(180.0, frame.diagonal)
    }

    @Test
    fun testAFifteenMillimetreEquisolidLensOnFullFrame() {
        // 4·asin(18/30) = 147.48°, 4·asin(12/30) = 94.31°; the diagonal 2·θ(21.63 mm) = 184.6° is capped.
        val frame = fieldOfViewOf(SensorFormat.fullFrame, 15.0, LensProjection.FISHEYE_EQUISOLID)
        assertEquals(147.479591, frame.horizontal, 1e-6)
        assertEquals(94.312714, frame.vertical, 1e-6)
        assertEquals(180.0, frame.diagonal)
        assertEquals(frame.horizontal, FieldOfView.horizontal(SensorFormat.fullFrame, 15.0, LensProjection.FISHEYE_EQUISOLID))
        assertEquals(frame.vertical, FieldOfView.vertical(SensorFormat.fullFrame, 15.0, LensProjection.FISHEYE_EQUISOLID))
        assertEquals(frame.diagonal, FieldOfView.diagonal(SensorFormat.fullFrame, 15.0, LensProjection.FISHEYE_EQUISOLID))
    }

    @Test
    fun testATwelveMillimetreEquisolidLensSeesExactlyOneHundredTwentyDegreesVertically() {
        // 12 mm half height at f = 12: sin(θ/2) = 12/24 = 1/2, so θ = 60°.
        val frame = fieldOfViewOf(SensorFormat.fullFrame, 12.0, LensProjection.FISHEYE_EQUISOLID)
        assertEquals(120.0, frame.vertical, 1e-9)
        assertEquals(180.0, frame.horizontal)
    }

    @Test
    fun testEquidistantAndStereographicLenses() {
        val equidistant = fieldOfViewOf(SensorFormat.fullFrame, 20.0, LensProjection.FISHEYE_EQUIDISTANT)
        assertEquals(103.132403, equidistant.horizontal, 1e-6)
        assertEquals(68.754935, equidistant.vertical, 1e-6)
        assertEquals(123.949723, equidistant.diagonal, 1e-6)
        val stereographic = fieldOfViewOf(SensorFormat.fullFrame, 10.0, LensProjection.FISHEYE_STEREOGRAPHIC)
        assertEquals(167.94885, stereographic.horizontal, 1e-5)
        assertEquals(123.855026, stereographic.vertical, 1e-6)
        assertEquals(180.0, stereographic.diagonal)
    }

    @Test
    fun testAnImageCircleLimitsTheFrame() {
        // A 22.627 mm circle is the 180° image of an 8 mm equisolid lens: 180° every way on a sensor that is taller
        // than the circle, and on a shorter one the height only (15.6 mm: 4·asin(7.8 / 16) = 116.7°).
        val circle = 4.0 * 8.0 * sin(PI / 4.0)
        for (sensor in listOf(SensorFormat.fullFrame, SensorFormat.mediumFormat44x33)) {
            val frame = fieldOfViewOf(sensor, 8.0, LensProjection.FISHEYE_EQUISOLID, circle)
            assertEquals(180.0, frame.horizontal, 1e-9, sensor.id)
            assertEquals(180.0, frame.vertical, 1e-9, sensor.id)
            assertEquals(180.0, frame.diagonal, 1e-9, sensor.id)
        }
        val apsC = fieldOfViewOf(SensorFormat.apsC, 8.0, LensProjection.FISHEYE_EQUISOLID, circle)
        assertEquals(180.0, apsC.horizontal, 1e-9)
        assertEquals(116.7, apsC.vertical, 0.05)
        assertEquals(180.0, apsC.diagonal, 1e-9)
        // A 12 mm circle sees 4·asin(6/16) = 88.097° in every direction.
        val small = fieldOfViewOf(SensorFormat.fullFrame, 8.0, LensProjection.FISHEYE_EQUISOLID, 12.0)
        assertEquals(88.097251, small.horizontal, 1e-6)
        assertEquals(88.097251, small.vertical, 1e-6)
        assertEquals(88.097251, small.diagonal, 1e-6)
    }

    @Test
    fun testAnImageCircleBetweenTheSidesOnlyLimitsTheLongerOnes() {
        // 30 mm on a 36 × 24 mm sensor: the width and diagonal reach the circle, the height does not.
        val frame = fieldOfViewOf(SensorFormat.fullFrame, 15.0, LensProjection.FISHEYE_EQUISOLID, 30.0)
        assertEquals(120.0, frame.horizontal, 1e-9)
        assertEquals(94.312714, frame.vertical, 1e-6)
        assertEquals(120.0, frame.diagonal, 1e-9)
    }

    @Test
    fun testAnImageCircleLargerThanTheSensorChangesNothing() {
        for (projection in LensProjection.entries) {
            val plain = fieldOfViewOf(SensorFormat.fullFrame, 24.0, projection)
            assertEquals(plain, fieldOfViewOf(SensorFormat.fullFrame, 24.0, projection, 60.0), projection.rawValue)
            assertEquals(plain, fieldOfViewOf(SensorFormat.fullFrame, 24.0, projection, SensorFormat.fullFrame.diagonalMillimeters))
        }
    }

    @Test
    fun testAnUnusableImageCircleIsIgnored() {
        val plain = fieldOfViewOf(SensorFormat.fullFrame, 15.0, LensProjection.FISHEYE_EQUISOLID)
        for (circle in listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(plain, fieldOfViewOf(SensorFormat.fullFrame, 15.0, LensProjection.FISHEYE_EQUISOLID, circle), "$circle")
        }
    }

    @Test
    fun testNoAngleExceedsHalfAGreatCircle() {
        for (projection in LensProjection.entries) {
            for (focalLength in listOf(1e-9, 0.5, 4.0, 8.0, 15.0, 50.0, 1e6)) {
                val frame = fieldOfViewOf(SensorFormat.mediumFormat44x33, focalLength, projection)
                for (angle in listOf(frame.horizontal, frame.vertical, frame.diagonal)) {
                    assertTrue(angle.isFinite() && angle >= 0.0 && angle <= 180.0, "$angle ${projection.rawValue} at $focalLength mm")
                }
            }
        }
    }

    @Test
    fun testAShorterFocalLengthSeesMoreThroughEveryProjection() {
        for (projection in LensProjection.entries) {
            var previous = 0.0
            for (focalLength in listOf(200.0, 100.0, 50.0, 28.0, 20.0, 14.0, 10.0)) {
                val horizontal = FieldOfView.horizontal(SensorFormat.fullFrame, focalLength, projection)
                assertTrue(horizontal > previous, "${projection.rawValue} at $focalLength mm")
                previous = horizontal
            }
        }
    }

    @Test
    fun testALensSetupReportsTheFieldOfViewOfItsProjectionAndImageCircle() {
        val fisheye = LensSetup(
            SensorFormat.fullFrame, 15.0, 2.8, null, LensProjection.FISHEYE_EQUISOLID, 30.0,
        )
        assertEquals(fieldOfViewOf(SensorFormat.fullFrame, 15.0, LensProjection.FISHEYE_EQUISOLID, 30.0), fisheye.fieldOfView)
        val ordinary = LensSetup(SensorFormat.fullFrame, 15.0, 2.8)
        assertEquals(FieldOfView.of(SensorFormat.fullFrame, 15.0), ordinary.fieldOfView)
        assertTrue(fisheye.fieldOfView.horizontal > ordinary.fieldOfView.horizontal)
    }
}

// MARK: - Lens setups with a projection

class LensSetupProjectionTests {

    private fun keys(setup: LensSetup): Set<String> =
        Json.parseToJsonElement(Json.encodeToString(LensSetup.serializer(), setup)).jsonObject.keys

    private fun decode(document: String): LensSetup = Json.decodeFromString(LensSetup.serializer(), document)

    private val fullFrameJson = """{"id":"fullFrame","name":"Full Frame","widthMillimeters":36.0,"heightMillimeters":24.0}"""

    @Test
    fun testASetupIsRectilinearWithoutAnImageCircleByDefault() {
        val setup = LensSetup(SensorFormat.fullFrame, 24.0, 2.8)
        assertEquals(LensProjection.RECTILINEAR, setup.projection)
        assertNull(setup.imageCircleDiameterMillimeters)
        assertNotNull(LensSetup.validated(SensorFormat.fullFrame, 24.0, 2.8)).let {
            assertEquals(setup, it)
        }
    }

    @Test
    fun testTheProjectionAndTheImageCircleAreKept() {
        val setup = LensSetup(SensorFormat.fullFrame, 8.0, 3.5, 5.9, LensProjection.FISHEYE_EQUISOLID, 22.6)
        assertEquals(LensProjection.FISHEYE_EQUISOLID, setup.projection)
        assertEquals(22.6, setup.imageCircleDiameterMillimeters)
        assertEquals(setup, LensSetup.validated(SensorFormat.fullFrame, 8.0, 3.5, 5.9, LensProjection.FISHEYE_EQUISOLID, 22.6))
        assertNotEquals(setup, LensSetup(SensorFormat.fullFrame, 8.0, 3.5, 5.9))
    }

    @Test
    fun testAnImageCircleOutsideTheRangeIsDroppedOrRejected() {
        for (circle in listOf(0.0, 0.5, 201.0, -3.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val limited = LensSetup(SensorFormat.fullFrame, 8.0, 3.5, null, LensProjection.FISHEYE_EQUISOLID, circle)
            assertNull(limited.imageCircleDiameterMillimeters, "$circle")
            assertEquals(LensProjection.FISHEYE_EQUISOLID, limited.projection)
            assertNull(
                LensSetup.validated(SensorFormat.fullFrame, 8.0, 3.5, null, LensProjection.FISHEYE_EQUISOLID, circle),
                "$circle",
            )
        }
        assertEquals(1.0..200.0, LensSetup.imageCircleRange)
    }

    @Test
    fun testTheJsonOfAnOrdinaryLensIsUnchanged() {
        assertEquals(setOf("sensor", "focalLengthMillimeters", "aperture"), keys(LensSetup(SensorFormat.fullFrame, 24.0, 2.8)))
        assertEquals(
            setOf("sensor", "focalLengthMillimeters", "aperture", "pixelPitchMicrometers"),
            keys(LensSetup(SensorFormat.fullFrame, 24.0, 2.8, 5.9)),
        )
    }

    @Test
    fun testAFisheyeWritesItsProjectionAndImageCircleAndReadsThemBack() {
        val setup = LensSetup(SensorFormat.fullFrame, 8.0, 3.5, 5.9, LensProjection.FISHEYE_EQUISOLID, 22.6)
        assertEquals(
            setOf("sensor", "focalLengthMillimeters", "aperture", "pixelPitchMicrometers", "projection", "imageCircleDiameterMillimeters"),
            keys(setup),
        )
        assertEquals(setup, decode(Json.encodeToString(LensSetup.serializer(), setup)))
        for (projection in LensProjection.entries) {
            val plain = LensSetup(SensorFormat.apsC, 10.0, 2.8, null, projection)
            assertEquals(plain, decode(Json.encodeToString(LensSetup.serializer(), plain)), projection.rawValue)
        }
    }

    @Test
    fun testAMissingOrNullProjectionAndImageCircleReadAsTheDefaults() {
        val missing = decode("""{"sensor":$fullFrameJson,"focalLengthMillimeters":24,"aperture":2.8}""")
        assertEquals(LensSetup(SensorFormat.fullFrame, 24.0, 2.8), missing)
        val nulls = decode(
            """{"sensor":$fullFrameJson,"focalLengthMillimeters":24,"aperture":2.8,"projection":null,"imageCircleDiameterMillimeters":null}""",
        )
        assertEquals(missing, nulls)
    }

    @Test
    fun testAnUnknownProjectionOrAnImageCircleOutsideTheRangeIsRejected() {
        val head = """{"sensor":$fullFrameJson,"focalLengthMillimeters":24,"aperture":2.8"""
        assertFailsWith<SerializationException> { decode("""$head,"projection":"cylindrical"}""") }
        assertFailsWith<SerializationException> { decode("""$head,"projection":""}""") }
        assertFailsWith<SerializationException> { decode("""$head,"projection":3}""") }
        assertFailsWith<SerializationException> { decode("""$head,"imageCircleDiameterMillimeters":0}""") }
        assertFailsWith<SerializationException> { decode("""$head,"imageCircleDiameterMillimeters":500}""") }
        assertFailsWith<SerializationException> { decode("""$head,"imageCircleDiameterMillimeters":"22"}""") }
    }
}

// MARK: - The frame outline

class CameraFrameOutlineTests {

    /** The border the engine drew before projections existed: tangents of the half angles, as the apps used it. */
    private fun legacyOutline(setup: LensSetup, samplesPerEdge: Int): List<Vector3> {
        val frame = setup.fieldOfView
        val halfWidth = AstroMath.tanDeg(frame.horizontal / 2)
        val halfHeight = AstroMath.tanDeg(frame.vertical / 2)
        val cornerRight = listOf(-1.0, 1.0, 1.0, -1.0)
        val cornerUp = listOf(1.0, 1.0, -1.0, -1.0)
        val outline = ArrayList<Vector3>()
        for (edge in 0 until 4) {
            val next = (edge + 1) % 4
            for (step in 0 until samplesPerEdge) {
                val t = step.toDouble() / samplesPerEdge.toDouble()
                val right = (cornerRight[edge] + (cornerRight[next] - cornerRight[edge]) * t) * halfWidth
                val up = (cornerUp[edge] + (cornerUp[next] - cornerUp[edge]) * t) * halfHeight
                outline.add(Vector3(right, up, 1.0).normalized)
            }
        }
        return outline
    }

    private fun assertSameDirections(expected: List<Vector3>, actual: List<Vector3>, tolerance: Double, message: String) {
        assertEquals(expected.size, actual.size, message)
        for (index in expected.indices) {
            assertEquals(expected[index].x, actual[index].x, tolerance, "$message x[$index]")
            assertEquals(expected[index].y, actual[index].y, tolerance, "$message y[$index]")
            assertEquals(expected[index].z, actual[index].z, tolerance, "$message z[$index]")
        }
    }

    @Test
    fun testARectilinearFrameIsTheOutlineOfBefore() {
        for (sensor in listOf(SensorFormat.fullFrame, SensorFormat.apsC, SensorFormat.microFourThirds)) {
            for (focalLength in listOf(14.0, 24.0, 85.0, 400.0)) {
                for (samples in listOf(1, 4, 8)) {
                    val setup = LensSetup(sensor, focalLength, 2.8)
                    assertSameDirections(
                        legacyOutline(setup, samples), CameraFrameOutline.directions(setup, samples), 1e-12,
                        "${sensor.id} $focalLength mm, $samples samples",
                    )
                }
            }
        }
    }

    @Test
    fun testTheOutlineHasFourTimesTheSamplesOfUnitVectors() {
        for (projection in LensProjection.entries) {
            for (samples in listOf(1, 2, 4, 7)) {
                val setup = LensSetup(SensorFormat.apsC, 12.0, 2.8, null, projection)
                val outline = CameraFrameOutline.directions(setup, samples)
                assertEquals(4 * samples, outline.size, projection.rawValue)
                for (direction in outline) {
                    assertTrue(direction.isFinite, projection.rawValue)
                    assertEquals(1.0, direction.length, 1e-12, projection.rawValue)
                }
            }
        }
    }

    @Test
    fun testTheOutlineStartsAtTheTopLeftAndRunsClockwise() {
        val setup = LensSetup(SensorFormat.fullFrame, 24.0, 2.8)
        val outline = CameraFrameOutline.directions(setup, 4)
        // Top-left corner, then along the top edge to the right.
        assertTrue(outline[0].x < 0 && outline[0].y > 0)
        assertEquals(outline[0].x, -outline[4].x, 1e-12)
        assertEquals(outline[0].y, outline[4].y, 1e-12)
        for (step in 0 until 3) assertTrue(outline[step].x < outline[step + 1].x)
        assertEquals(0.0, outline[2].x, 1e-12)
        assertTrue(outline[8].x > 0 && outline[8].y < 0)
        assertTrue(outline[12].x < 0 && outline[12].y < 0)
    }

    @Test
    fun testTheOutlineIsSymmetricAboutTheAxis() {
        // A half turn about the axis maps the point at index i to the one at i + half the count.
        for (projection in LensProjection.entries) {
            for (circle in listOf<Double?>(null, 14.0, 30.0)) {
                for (samples in listOf(1, 3, 4, 8)) {
                    val setup = LensSetup(SensorFormat.fullFrame, 15.0, 2.8, null, projection, circle)
                    val outline = CameraFrameOutline.directions(setup, samples)
                    val half = outline.size / 2
                    for (index in 0 until half) {
                        val a = outline[index]
                        val b = outline[index + half]
                        val message = "${projection.rawValue} circle $circle, $samples samples, point $index"
                        assertEquals(-a.x, b.x, 1e-12, message)
                        assertEquals(-a.y, b.y, 1e-12, message)
                        assertEquals(a.z, b.z, 1e-12, message)
                    }
                    assertEquals(0.0, outline.sumOf { it.x }, 1e-9)
                    assertEquals(0.0, outline.sumOf { it.y }, 1e-9)
                }
            }
        }
    }

    @Test
    fun testTheCornersOfAFisheyeFrameLieAtTheAngleOfTheirImageDistance() {
        val setup = LensSetup(SensorFormat.fullFrame, 15.0, 2.8, null, LensProjection.FISHEYE_EQUISOLID)
        val outline = CameraFrameOutline.directions(setup, 4)
        // The corner is 21.633 mm from the centre: 2·asin(21.633 / 30) = 92.29° from the axis, behind the sensor's plane.
        assertEquals(92.292443, outline[0].angle(zAxis), 1e-6)
        assertTrue(outline[0].z < 0)
        // The middle of the top edge is 12 mm out: 2·asin(12 / 30) = 47.16°; the middle of the right edge, 18 mm: 73.74°.
        assertEquals(47.156357, outline[2].angle(zAxis), 1e-6)
        assertEquals(73.739795, outline[6].angle(zAxis), 1e-6)
        assertEquals(outline[2].y, sin(47.156357 * PI / 180.0), 1e-6)
        assertEquals(0.0, outline[2].x, 1e-12)
    }

    @Test
    fun testACircularFisheyeFrameIsAnEvenCircle() {
        // The 180° image of an 8 mm equisolid lens: every point is 90° from the axis, evenly spaced.
        val diameter = 4.0 * 8.0 * sin(PI / 4.0)
        val setup = LensSetup(SensorFormat.fullFrame, 8.0, 3.5, null, LensProjection.FISHEYE_EQUISOLID, diameter)
        val outline = CameraFrameOutline.directions(setup, 4)
        assertEquals(16, outline.size)
        for (direction in outline) assertEquals(90.0, direction.angle(zAxis), 1e-6)
        // Starts 45° anticlockwise from the top and turns clockwise by 22.5° a point.
        assertTrue(outline[0].x < 0 && outline[0].y > 0)
        assertEquals(-sin(PI / 4.0), outline[0].x, 1e-12)
        assertEquals(0.0, outline[2].x, 1e-12)
        assertEquals(1.0, outline[2].y, 1e-12)
        for (index in outline.indices) {
            val next = outline[(index + 1) % outline.size]
            assertEquals(22.5, outline[index].angle(next), 1e-6, "step $index")
        }
    }

    @Test
    fun testACircleThatOnlyTrimsTheCornersMovesThemOntoTheCircle() {
        // 30 mm on a 36 × 24 mm sensor: 15 mm radius, 2·asin(15 / 30) = 60° from the axis.
        val setup = LensSetup(SensorFormat.fullFrame, 15.0, 2.8, null, LensProjection.FISHEYE_EQUISOLID, 30.0)
        val outline = CameraFrameOutline.directions(setup, 4)
        assertEquals(60.0, outline[0].angle(zAxis), 1e-6)
        assertEquals(60.0, outline[1].angle(zAxis), 1e-6)
        assertEquals(47.156357, outline[2].angle(zAxis), 1e-6)
        assertEquals(60.0, outline[3].angle(zAxis), 1e-6)
        // The right edge lies beyond the circle too, so it is trimmed to it as well.
        assertEquals(60.0, outline[5].angle(zAxis), 1e-6)
        assertEquals(60.0, outline[6].angle(zAxis), 1e-6)
    }

    @Test
    fun testTooFewOrTooManySamplesAreHandled() {
        val setup = LensSetup(SensorFormat.fullFrame, 24.0, 2.8)
        assertTrue(CameraFrameOutline.directions(setup, 0).isEmpty())
        assertTrue(CameraFrameOutline.directions(setup, -3).isEmpty())
        assertEquals(4 * CameraFrameOutline.maximumSamplesPerEdge, CameraFrameOutline.directions(setup, 100_000).size)
        assertEquals(1000, CameraFrameOutline.maximumSamplesPerEdge)
    }

    @Test
    fun testTheMiddleOfTheTopEdge() {
        val ordinary = LensSetup(SensorFormat.fullFrame, 24.0, 2.8)
        val top = CameraFrameOutline.topMiddle(ordinary)
        assertEquals(0.0, top.x, 1e-12)
        assertEquals(ordinary.fieldOfView.vertical / 2, top.angle(zAxis), 1e-9)
        assertSameDirections(listOf(CameraFrameOutline.directions(ordinary, 4)[2]), listOf(top), 1e-12, "rectilinear")

        val fisheye = LensSetup(SensorFormat.fullFrame, 15.0, 2.8, null, LensProjection.FISHEYE_EQUISOLID)
        assertEquals(47.156357, CameraFrameOutline.topMiddle(fisheye).angle(zAxis), 1e-6)
        assertSameDirections(listOf(CameraFrameOutline.directions(fisheye, 4)[2]), listOf(CameraFrameOutline.topMiddle(fisheye)), 1e-12, "fisheye")

        // The top of a circle smaller than the frame is the top of the circle.
        val circular = LensSetup(SensorFormat.fullFrame, 8.0, 3.5, null, LensProjection.FISHEYE_EQUISOLID, 12.0)
        assertEquals(44.048626, CameraFrameOutline.topMiddle(circular).angle(zAxis), 1e-6)
        assertTrue(CameraFrameOutline.topMiddle(circular).y > 0)
    }

    @Test
    fun testEveryPresetAndProjectionGivesFiniteUnitVectors() {
        for (preset in CameraPreset.all) {
            for (projection in LensProjection.entries) {
                val setup = LensSetup(preset.sensor, preset.focalLengthMillimeters, preset.aperture, null, projection)
                for (direction in CameraFrameOutline.directions(setup, 8) + CameraFrameOutline.topMiddle(setup)) {
                    assertTrue(direction.isFinite, "${preset.id} ${projection.rawValue}")
                    assertEquals(1.0, direction.length, 1e-12, "${preset.id} ${projection.rawValue}")
                }
            }
        }
    }

    @Test
    fun testTheOutlineOfARectilinearFrameStaysInFrontOfTheCamera() {
        val setup = LensSetup(SensorFormat.mediumFormat44x33, 4.0, 2.8)
        assertTrue(setup.fieldOfView.diagonal < 180.0)
        for (direction in CameraFrameOutline.directions(setup, 6)) assertTrue(direction.z > 0)
    }
}

// MARK: - Presets

class CameraPresetTests {

    /** The vendor's published field of view in degrees: the number, what it measures and how far ours may be off. */
    private data class Published(val degrees: Double, val across: String, val tolerance: Double)

    @Test
    fun testPresetsAreListedInPickerOrderWithDistinctIdentifiersAndNames() {
        assertEquals(
            listOf(
                "fullFrame24", "seestarS50", "seestarS30", "seestarS50Pro", "seestarS30Pro", "dwarf3Tele", "dwarf3Wide",
                "dwarf2Tele", "vesperaII",
            ),
            CameraPreset.all.map { it.id },
        )
        assertEquals(CameraPreset.fullFrame24, CameraPreset.all.first())
        assertEquals(CameraPreset.smartTelescopes, CameraPreset.all.drop(1))
        assertEquals(CameraPreset.all.size, CameraPreset.all.map { it.name }.toSet().size)
        assertTrue(CameraPreset.all.all { it.name.isNotEmpty() && it.name.length <= 40 })
    }

    @Test
    fun testTheStarterIsAFullFrame24MillimetreF28Lens() {
        val preset = CameraPreset.fullFrame24
        assertEquals("Full-frame 24 mm f/2.8", preset.name)
        assertEquals(LensSetup(SensorFormat.fullFrame, 24.0, 2.8), preset.setup)
        assertNull(preset.pixelPitchMicrometers)
        assertEquals(LensProjection.RECTILINEAR, preset.projection)
    }

    @Test
    fun testASmartTelescopeSensorIsItsPixelCountTimesItsPixelPitch() {
        // 1920 × 1080 pixels of 2.9 µm.
        val seestar = CameraPreset.seestarS50
        assertEquals(5.568, seestar.sensor.widthMillimeters, 1e-9)
        assertEquals(3.132, seestar.sensor.heightMillimeters, 1e-9)
        assertEquals(2.9, seestar.pixelPitchMicrometers)
        assertEquals(250.0, seestar.focalLengthMillimeters)
        assertEquals(5.0, seestar.aperture)
        // 3840 × 2160 pixels of 2 µm.
        assertEquals(7.68, CameraPreset.dwarf3Tele.sensor.widthMillimeters, 1e-9)
        assertEquals(4.32, CameraPreset.dwarf3Tele.sensor.heightMillimeters, 1e-9)
        // The image scale follows from the pitch and the focal length: 2.9 µm at 250 mm is 2.39 arcseconds a pixel.
        assertEquals(2.39, assertNotNull(CameraPreset.vesperaII.setup.imageScaleArcsecondsPerPixel), 0.005)
    }

    @Test
    fun testEveryPresetIsAValidSetupThatKeepsItsValues() {
        for (preset in CameraPreset.all) {
            val setup = preset.setup
            assertEquals(preset.sensor, setup.sensor, preset.id)
            assertEquals(preset.focalLengthMillimeters, setup.focalLengthMillimeters, preset.id)
            assertEquals(preset.aperture, setup.aperture, preset.id)
            assertEquals(preset.pixelPitchMicrometers, setup.pixelPitchMicrometers, preset.id)
            assertEquals(preset.projection, setup.projection, preset.id)
            assertNull(setup.imageCircleDiameterMillimeters, preset.id)
            assertNotNull(
                LensSetup.validated(preset.sensor, preset.focalLengthMillimeters, preset.aperture, preset.pixelPitchMicrometers),
                preset.id,
            )
        }
    }

    @Test
    fun testTheFieldOfViewIsWhatTheVendorPublishes() {
        // Published values are rounded and nominal, so each has a tolerance that covers that; "long" and "short" are
        // the longer and shorter side of the frame, "diagonal" the corner to corner angle.
        val published = mapOf(
            // https://www.seestar.com/products/seestar-s50: 0.73° × 1.29°.
            "seestarS50" to listOf(Published(1.29, "long", 0.02), Published(0.73, "short", 0.02)),
            // https://www.seestar.com/products/seestar-s30-all-in-one-smart-telescope: 2.46°.
            "seestarS30" to listOf(Published(2.46, "diagonal", 0.03)),
            // https://www.seestar.com/products/seestar-s50-pro-smart-telescope: 2.8°.
            "seestarS50Pro" to listOf(Published(2.8, "diagonal", 0.03)),
            // https://www.seestar.com/products/seestar-s30-pro: 4.6°.
            "seestarS30Pro" to listOf(Published(4.6, "diagonal", 0.05)),
            // https://dwarflab.com/products/dwarf-3-smart-telescope: D 3.38° and D 50.6°.
            "dwarf3Tele" to listOf(Published(3.38, "diagonal", 0.03)),
            "dwarf3Wide" to listOf(Published(50.6, "diagonal", 0.5)),
            // https://dwarflab.com/products/dwarf-2-smart-telescope: D 3.72°.
            "dwarf2Tele" to listOf(Published(3.72, "diagonal", 0.1)),
            // https://vaonis.com/products/vespera-2: 2.5° × 1.4°.
            "vesperaII" to listOf(Published(2.5, "long", 0.1), Published(1.4, "short", 0.1)),
        )
        assertEquals(CameraPreset.smartTelescopes.map { it.id }.toSet(), published.keys)
        for (preset in CameraPreset.smartTelescopes) {
            val frame = preset.setup.fieldOfView
            for (value in assertNotNull(published[preset.id])) {
                val computed = when (value.across) {
                    "long" -> frame.longSide
                    "short" -> frame.shortSide
                    else -> frame.diagonal
                }
                assertEquals(value.degrees, computed, value.tolerance, "${preset.id} ${value.across}")
            }
        }
    }

    @Test
    fun testFieldOfViewNumbersOfThePresets() {
        // Our own numbers, to the precision of the arithmetic.
        val expected = mapOf(
            "seestarS50" to Triple(1.2760, 0.7178, 1.4640),
            "seestarS30" to Triple(2.1266, 1.1963, 2.4398),
            "seestarS50Pro" to Triple(2.4536, 1.3803, 2.8150),
            "seestarS30Pro" to Triple(3.9862, 2.2428, 4.5729),
            "dwarf3Tele" to Triple(2.9329, 1.6500, 3.3648),
            "dwarf3Wide" to Triple(45.1280, 26.3113, 50.9788),
            "dwarf2Tele" to Triple(3.1894, 1.7944, 3.6591),
            "vesperaII" to Triple(2.5518, 1.4355, 2.9276),
        )
        for (preset in CameraPreset.smartTelescopes) {
            val frame = preset.setup.fieldOfView
            val (horizontal, vertical, diagonal) = assertNotNull(expected[preset.id])
            assertEquals(horizontal, frame.horizontal, 1e-4, preset.id)
            assertEquals(vertical, frame.vertical, 1e-4, preset.id)
            assertEquals(diagonal, frame.diagonal, 1e-4, preset.id)
        }
        assertFalse(CameraPreset.smartTelescopes.any { it.projection.isFisheye })
    }
}

// MARK: - Photo metadata

class CameraPhotoMetadataTests {

    private fun metadata(
        model: String? = null,
        focalLength: Double? = null,
        equivalent: Double? = null,
        aperture: Double? = null,
        width: Int? = null,
        height: Int? = null,
    ) = CameraPhotoMetadata(model, focalLength, equivalent, aperture, width, height)

    @Test
    fun testAFullFrameCameraGivesTheFullFrameSensorAndItsPixelPitch() {
        val suggestion = assertNotNull(metadata("NIKON D850", 24.0, 24.0, 2.8, 8256, 5504).suggestion())
        assertEquals("NIKON D850", suggestion.name)
        assertEquals(SensorFormat.fullFrame, suggestion.sensor)
        assertEquals(1.0, assertNotNull(suggestion.cropFactor), 1e-12)
        assertEquals(24.0, suggestion.focalLengthMillimeters)
        assertEquals(2.8, suggestion.aperture)
        // 36 mm over 8256 pixels.
        assertEquals(4.3605, assertNotNull(suggestion.pixelPitchMicrometers), 1e-4)
        assertEquals(45.441024, assertNotNull(suggestion.megapixels), 1e-6)
    }

    @Test
    fun testTheNearestPresetIsChosenByCropFactor() {
        // 16 mm that frames like 24 mm: crop 1.5, Nikon-style APS-C (1.534), not Canon's (1.613).
        assertEquals(SensorFormat.apsC, metadata(null, 16.0, 24.0, null, 6000, 4000).suggestion()?.sensor)
        // 18 mm that frames like 29 mm: crop 1.611, Canon APS-C.
        assertEquals(SensorFormat.apsCCanon, metadata(null, 18.0, 29.0, null, 6000, 4000).suggestion()?.sensor)
        assertEquals(SensorFormat.microFourThirds, metadata(null, 12.0, 24.0, null, 5184, 3888).suggestion()?.sensor)
        assertEquals(SensorFormat.oneInch, metadata(null, 9.0, 24.0, null, 5472, 3648).suggestion()?.sensor)
        assertEquals(SensorFormat.mediumFormat44x33, metadata(null, 50.0, 40.0, null, 8256, 6192).suggestion()?.sensor)
    }

    @Test
    fun testThePixelPitchFollowsFromTheSensorAndThePictureSize() {
        assertEquals(3.9167, assertNotNull(metadata(null, 16.0, 24.0, null, 6000, 4000).suggestion()?.pixelPitchMicrometers), 1e-4)
        assertEquals(3.7167, assertNotNull(metadata(null, 18.0, 29.0, null, 6000, 4000).suggestion()?.pixelPitchMicrometers), 1e-4)
        assertEquals(3.3372, assertNotNull(metadata(null, 12.0, 24.0, null, 5184, 3888).suggestion()?.pixelPitchMicrometers), 1e-4)
    }

    @Test
    fun testAPictureTurnedToPortraitGivesTheSameSuggestion() {
        val landscape = metadata("NIKON D850", 24.0, 24.0, 2.8, 8256, 5504).suggestion()
        val portrait = metadata("NIKON D850", 24.0, 24.0, 2.8, 5504, 8256).suggestion()
        assertEquals(landscape, portrait)
    }

    @Test
    fun testACropFactorThatMatchesNoPresetMakesACustomSensorWithThePictureShape() {
        // A phone: 6.9 mm that frames like 24 mm, crop 3.478; 12.44 mm diagonal at 4:3.
        val suggestion = assertNotNull(metadata("Pixel", 6.9, 24.0, 1.7, 4000, 3000).suggestion())
        val sensor = assertNotNull(suggestion.sensor)
        assertEquals(9.9513, sensor.widthMillimeters, 1e-4)
        assertEquals(7.4635, sensor.heightMillimeters, 1e-4)
        assertEquals(12.4392, sensor.diagonalMillimeters, 1e-4)
        assertEquals(2.4878, assertNotNull(suggestion.pixelPitchMicrometers), 1e-4)
        // Without the picture size the shape is 3:2 and no pixel pitch can be estimated.
        val unsized = assertNotNull(metadata(null, 6.9, 24.0, null).suggestion())
        assertEquals(10.35, assertNotNull(unsized.sensor).widthMillimeters, 1e-9)
        assertEquals(6.9, assertNotNull(unsized.sensor).heightMillimeters, 1e-9)
        assertNull(unsized.pixelPitchMicrometers)
        assertNull(unsized.megapixels)
    }

    @Test
    fun testWithoutAnEquivalentFocalLengthThereIsNoSensor() {
        val suggestion = assertNotNull(metadata("Camera", 35.0, null, 1.8, 6000, 4000).suggestion())
        assertNull(suggestion.sensor)
        assertNull(suggestion.cropFactor)
        assertNull(suggestion.pixelPitchMicrometers)
        assertEquals(35.0, suggestion.focalLengthMillimeters)
        assertEquals(24.0, assertNotNull(suggestion.megapixels), 1e-9)
    }

    @Test
    fun testAPhotoWithoutAUsableFocalLengthSuggestsNothing() {
        assertNull(metadata("Camera", null, 35.0, 1.8, 6000, 4000).suggestion())
        for (focalLength in listOf(0.0, -24.0, 2.0, 5000.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(metadata("Camera", focalLength, 35.0, 1.8, 6000, 4000).suggestion(), "$focalLength")
        }
    }

    @Test
    fun testValuesOutsideTheirRangesAreIgnoredNotCorrected() {
        for (aperture in listOf(0.5, 100.0, Double.NaN, -2.8)) {
            assertNull(metadata(null, 24.0, 24.0, aperture).suggestion()?.aperture, "$aperture")
        }
        // A crop factor of 20 or 0.1 is not believed.
        assertNull(metadata(null, 24.0, 480.0).suggestion()?.cropFactor)
        assertNull(metadata(null, 24.0, 2.4).suggestion()?.cropFactor)
        assertNull(metadata(null, 24.0, 24.0, null, 0, 4000).suggestion()?.pixelPitchMicrometers)
        assertNull(metadata(null, 24.0, 24.0, null, 6000, -4000).suggestion()?.pixelPitchMicrometers)
        assertNull(metadata(null, 24.0, 24.0, null, 6000, 200_000).suggestion()?.pixelPitchMicrometers)
        // 36 mm over 20 pixels would be 1800 µm a pixel.
        assertNull(metadata(null, 24.0, 24.0, null, 20, 10).suggestion()?.pixelPitchMicrometers)
        for (equivalent in listOf(0.0, -35.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(metadata(null, 24.0, equivalent).suggestion()?.sensor, "$equivalent")
        }
    }

    @Test
    fun testTheModelNameIsCleanedAndCut() {
        assertEquals("NIKON D850", metadata("  NIKON\u0000 D850 \n", 24.0, 24.0).suggestion()?.name)
        assertNull(metadata("", 24.0, 24.0).suggestion()?.name)
        assertNull(metadata("  \t ", 24.0, 24.0).suggestion()?.name)
        assertNull(metadata(null, 24.0, 24.0).suggestion()?.name)
        val long = "A".repeat(60)
        assertEquals("A".repeat(CameraPhotoMetadata.maximumNameLength), metadata(long, 24.0, 24.0).suggestion()?.name)
        assertEquals(40, CameraPhotoMetadata.maximumNameLength)
    }
}
