package net.kuecker.lodestar.kit.phototools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun encode(sensor: SensorFormat): String = Json.encodeToString(SensorFormat.serializer(), sensor)

private fun encode(setup: LensSetup): String = Json.encodeToString(LensSetup.serializer(), setup)

private fun decodeSensor(document: String): SensorFormat = Json.decodeFromString(SensorFormat.serializer(), document)

private fun decodeSetup(document: String): LensSetup = Json.decodeFromString(LensSetup.serializer(), document)

// MARK: - Sensor formats

class SensorFormatTests {

    @Test
    fun testPresetsAreListedInPickerOrder() {
        assertEquals(
            listOf("fullFrame", "apsC", "apsCCanon", "microFourThirds", "oneInch", "mediumFormat44x33"),
            SensorFormat.presets.map { it.id },
        )
        assertEquals(SensorFormat.fullFrame, SensorFormat.presets.first())
        assertEquals(SensorFormat.presets.size, SensorFormat.presets.map { it.id }.toSet().size)
        assertEquals(SensorFormat.presets.size, SensorFormat.presets.map { it.name }.toSet().size)
    }

    @Test
    fun testPresetDimensions() {
        val expected = listOf(
            Triple(SensorFormat.fullFrame, 36.0, 24.0),
            Triple(SensorFormat.apsC, 23.5, 15.6),
            Triple(SensorFormat.apsCCanon, 22.3, 14.9),
            Triple(SensorFormat.microFourThirds, 17.3, 13.0),
            Triple(SensorFormat.oneInch, 13.2, 8.8),
            Triple(SensorFormat.mediumFormat44x33, 43.8, 32.9),
        )
        for ((sensor, width, height) in expected) {
            assertEquals(width, sensor.widthMillimeters, sensor.id)
            assertEquals(height, sensor.heightMillimeters, sensor.id)
            assertTrue(SensorFormat.dimensionRange.contains(width) && SensorFormat.dimensionRange.contains(height))
        }
    }

    @Test
    fun testCropFactors() {
        assertEquals(1.0, SensorFormat.fullFrame.cropFactor)
        assertEquals(1.5339, SensorFormat.apsC.cropFactor, 0.0001)
        assertEquals(1.6132, SensorFormat.apsCCanon.cropFactor, 0.0001)
        assertEquals(1.9994, SensorFormat.microFourThirds.cropFactor, 0.0001)
        assertEquals(2.7273, SensorFormat.oneInch.cropFactor, 0.0001)
        assertEquals(0.7898, SensorFormat.mediumFormat44x33.cropFactor, 0.0001)
    }

    @Test
    fun testDiagonal() {
        assertEquals(43.2666, SensorFormat.fullFrame.diagonalMillimeters, 0.0001)
        assertEquals(54.7800, SensorFormat.mediumFormat44x33.diagonalMillimeters, 0.0001)
    }

    // MARK: Custom sizes

    @Test
    fun testCustomSensor() {
        val sensor = SensorFormat.custom(width = 24.0, height = 16.0)
        assertEquals(24.0, sensor.widthMillimeters)
        assertEquals(16.0, sensor.heightMillimeters)
        assertEquals(1.5, sensor.cropFactor, 1e-12)
        assertEquals("custom-24.0x16.0", sensor.id)
        assertEquals("Custom 24 \u00D7 16 mm", sensor.name)
        assertFalse(SensorFormat.presets.contains(sensor))

        assertEquals("Custom 23.46 \u00D7 15.6 mm", SensorFormat.custom(width = 23.456, height = 15.6).name)
    }

    @Test
    fun testCustomSensorsWithSameSizeAreEqual() {
        val first = SensorFormat.custom(width = 20.0, height = 13.5)
        val second = SensorFormat.custom(width = 20.0, height = 13.5)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, SensorFormat.custom(width = 20.0, height = 13.6))
        assertNotEquals(first.id, SensorFormat.custom(width = 13.5, height = 20.0).id)
    }

    @Test
    fun testCustomSensorLimitsEachDimensionToTheSupportedRange() {
        assertEquals(1.0, SensorFormat.custom(width = 0.0, height = 24.0).widthMillimeters)
        assertEquals(1.0, SensorFormat.custom(width = -36.0, height = 24.0).widthMillimeters)
        assertEquals(1.0, SensorFormat.custom(width = 36.0, height = 0.5).heightMillimeters)
        assertEquals(200.0, SensorFormat.custom(width = 1e9, height = 24.0).widthMillimeters)
        assertEquals(200.0, SensorFormat.custom(width = 36.0, height = 200.001).heightMillimeters)
        // The other dimension is left alone.
        assertEquals(24.0, SensorFormat.custom(width = 0.0, height = 24.0).heightMillimeters)
        assertEquals(24.0, SensorFormat.custom(width = 1e9, height = 24.0).heightMillimeters)
    }

    @Test
    fun testCustomSensorReplacesNonFiniteDimensionsWithFullFrame() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val width = SensorFormat.custom(width = value, height = 16.0)
            assertEquals(36.0, width.widthMillimeters)
            assertEquals(16.0, width.heightMillimeters)
            val height = SensorFormat.custom(width = 24.0, height = value)
            assertEquals(24.0, height.widthMillimeters)
            assertEquals(24.0, height.heightMillimeters)
        }
    }

    @Test
    fun testCustomSensorIsAlwaysUsable() {
        for (width in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 1e300, 36.0)) {
            for (height in listOf(0.0, -1.0, Double.NaN, Double.NEGATIVE_INFINITY, 1e300, 24.0)) {
                val sensor = SensorFormat.custom(width = width, height = height)
                assertTrue(SensorFormat.dimensionRange.contains(sensor.widthMillimeters))
                assertTrue(SensorFormat.dimensionRange.contains(sensor.heightMillimeters))
                assertTrue(sensor.cropFactor.isFinite() && sensor.cropFactor > 0)
            }
        }
    }

    @Test
    fun testValidatedCustomSensorRejectsInvalidInput() {
        assertEquals(SensorFormat.validated(width = 24.0, height = 16.0), SensorFormat.custom(width = 24.0, height = 16.0))
        assertNotNull(SensorFormat.validated(width = 1.0, height = 1.0))
        assertNotNull(SensorFormat.validated(width = 200.0, height = 200.0))
        val invalid = listOf(
            0.0, -1.0, -36.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.999, 200.001, 1e9, 1e308,
        )
        for (value in invalid) {
            assertNull(SensorFormat.validated(width = value, height = 24.0), "width $value")
            assertNull(SensorFormat.validated(width = 36.0, height = value), "height $value")
        }
    }

    // MARK: Pixel pitch

    @Test
    fun testPixelPitchFromMegapixels() {
        // pitch = sqrt(width x height / pixel count)
        assertEquals(6.0, assertNotNull(SensorFormat.fullFrame.pixelPitchMicrometers(megapixels = 24.0)), 1e-9)
        assertEquals(4.3818, assertNotNull(SensorFormat.fullFrame.pixelPitchMicrometers(megapixels = 45.0)), 0.0001)
        assertEquals(3.7635, assertNotNull(SensorFormat.fullFrame.pixelPitchMicrometers(megapixels = 61.0)), 0.0001)
        assertEquals(3.1975, assertNotNull(SensorFormat.apsCCanon.pixelPitchMicrometers(megapixels = 32.5)), 0.0001)
        assertEquals(3.3534, assertNotNull(SensorFormat.microFourThirds.pixelPitchMicrometers(megapixels = 20.0)), 0.0001)
    }

    @Test
    fun testPixelPitchScalesWithTheSquareRootOfResolution() {
        val low = assertNotNull(SensorFormat.fullFrame.pixelPitchMicrometers(megapixels = 12.0))
        val high = assertNotNull(SensorFormat.fullFrame.pixelPitchMicrometers(megapixels = 48.0))
        assertEquals(2.0, low / high, 1e-12)
    }

    @Test
    fun testPixelPitchRejectsInvalidResolution() {
        val invalid = listOf(
            0.0, -24.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e9, 1e-4, 1e300, -1e-300,
        )
        for (megapixels in invalid) {
            assertNull(SensorFormat.fullFrame.pixelPitchMicrometers(megapixels = megapixels), "$megapixels MP")
        }
        // A pitch outside the supported range is rejected even for a positive resolution.
        assertNull(SensorFormat.oneInch.pixelPitchMicrometers(megapixels = 5000.0))
    }

    // MARK: Codable

    @Test
    fun testCodableRoundTrip() {
        val custom = SensorFormat.custom(width = 20.5, height = 13.7)
        for (sensor in SensorFormat.presets + custom) {
            assertEquals(sensor, decodeSensor(encode(sensor)))
        }
    }

    @Test
    fun testEncodedKeys() {
        val data = encode(SensorFormat.fullFrame)
        val keys = Json.parseToJsonElement(data).jsonObject.keys
        assertEquals(setOf("id", "name", "widthMillimeters", "heightMillimeters"), keys)
    }

    @Test
    fun testDecodingRejectsCorruptedData() {
        val longName = "x".repeat(129)
        val documents = listOf(
            """{"id":"a","name":"b","widthMillimeters":0,"heightMillimeters":24}""",
            """{"id":"a","name":"b","widthMillimeters":36,"heightMillimeters":0}""",
            """{"id":"a","name":"b","widthMillimeters":-36,"heightMillimeters":24}""",
            """{"id":"a","name":"b","widthMillimeters":36,"heightMillimeters":-24}""",
            """{"id":"a","name":"b","widthMillimeters":1e9,"heightMillimeters":24}""",
            """{"id":"a","name":"b","widthMillimeters":36,"heightMillimeters":1e308}""",
            """{"id":"","name":"b","widthMillimeters":36,"heightMillimeters":24}""",
            """{"id":"a","name":"","widthMillimeters":36,"heightMillimeters":24}""",
            """{"id":"$longName","name":"b","widthMillimeters":36,"heightMillimeters":24}""",
            """{"id":"a","name":"$longName","widthMillimeters":36,"heightMillimeters":24}""",
            """{"id":"a","name":"b","widthMillimeters":36}""",
            """{"id":"a","name":"b","widthMillimeters":"36","heightMillimeters":24}""",
            """{"id":1,"name":"b","widthMillimeters":36,"heightMillimeters":24}""",
            """[]""",
            """null""",
        )
        for (document in documents) {
            assertFailsWith<SerializationException>(document) { decodeSensor(document) }
        }
    }

    @Test
    fun testDecodingAcceptsAValidDocument() {
        val document = """{"id":"fullFrame","name":"Full Frame","widthMillimeters":36,"heightMillimeters":24}"""
        assertEquals(SensorFormat.fullFrame, decodeSensor(document))
    }
}

// MARK: - Range limiting

class RangeLimiterTests {

    @Test
    fun testValuesInsideTheRangeAreKept() {
        assertEquals(5.0, RangeLimiter.limit(5.0, 1.0..10.0, fallback = 3.0))
        assertEquals(1.0, RangeLimiter.limit(1.0, 1.0..10.0, fallback = 3.0))
        assertEquals(10.0, RangeLimiter.limit(10.0, 1.0..10.0, fallback = 3.0))
    }

    @Test
    fun testValuesOutsideTheRangeAreLimited() {
        assertEquals(1.0, RangeLimiter.limit(0.5, 1.0..10.0, fallback = 3.0))
        assertEquals(1.0, RangeLimiter.limit(-1e300, 1.0..10.0, fallback = 3.0))
        assertEquals(10.0, RangeLimiter.limit(10.5, 1.0..10.0, fallback = 3.0))
        assertEquals(10.0, RangeLimiter.limit(1e300, 1.0..10.0, fallback = 3.0))
    }

    @Test
    fun testValuesThatAreNotFiniteUseTheFallback() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(3.0, RangeLimiter.limit(value, 1.0..10.0, fallback = 3.0))
        }
    }
}

// MARK: - Lens setups

class LensSetupTests {

    private fun setup(
        sensor: SensorFormat = SensorFormat.fullFrame,
        focalLength: Double = 24.0,
        aperture: Double = 2.8,
        pitch: Double? = null,
    ): LensSetup =
        LensSetup(sensor = sensor, focalLengthMillimeters = focalLength, aperture = aperture, pixelPitchMicrometers = pitch)

    private fun validated(
        sensor: SensorFormat = SensorFormat.fullFrame,
        focalLength: Double = 24.0,
        aperture: Double = 2.8,
        pitch: Double? = null,
    ): LensSetup? = LensSetup.validated(
        sensor = sensor, focalLengthMillimeters = focalLength, aperture = aperture, pixelPitchMicrometers = pitch,
    )

    @Test
    fun testValidValuesAreKept() {
        val lens = setup(pitch = 5.9)
        assertEquals(SensorFormat.fullFrame, lens.sensor)
        assertEquals(24.0, lens.focalLengthMillimeters)
        assertEquals(2.8, lens.aperture)
        assertEquals(5.9, lens.pixelPitchMicrometers)
        assertNull(setup().pixelPitchMicrometers)
        assertNull(LensSetup(sensor = SensorFormat.apsC, focalLengthMillimeters = 85.0, aperture = 1.8).pixelPitchMicrometers)
    }

    @Test
    fun testDefaultsMatchTheStartingSettings() {
        assertEquals(24.0, LensSetup.defaultFocalLengthMillimeters)
        assertEquals(2.8, LensSetup.defaultAperture)
        assertTrue(LensSetup.focalLengthRange.contains(LensSetup.defaultFocalLengthMillimeters))
        assertTrue(LensSetup.apertureRange.contains(LensSetup.defaultAperture))
    }

    // MARK: Limiting

    @Test
    fun testFocalLengthIsLimitedToTheSupportedRange() {
        assertEquals(4.0, setup(focalLength = 4.0).focalLengthMillimeters)
        assertEquals(2000.0, setup(focalLength = 2000.0).focalLengthMillimeters)
        assertEquals(4.0, setup(focalLength = 3.999).focalLengthMillimeters)
        assertEquals(4.0, setup(focalLength = 0.0).focalLengthMillimeters)
        assertEquals(4.0, setup(focalLength = -24.0).focalLengthMillimeters)
        assertEquals(2000.0, setup(focalLength = 2000.001).focalLengthMillimeters)
        assertEquals(2000.0, setup(focalLength = 1e308).focalLengthMillimeters)
    }

    @Test
    fun testApertureIsLimitedToTheSupportedRange() {
        assertEquals(0.95, setup(aperture = 0.95).aperture)
        assertEquals(32.0, setup(aperture = 32.0).aperture)
        assertEquals(0.95, setup(aperture = 0.94).aperture)
        assertEquals(0.95, setup(aperture = 0.0).aperture)
        assertEquals(0.95, setup(aperture = -2.8).aperture)
        assertEquals(32.0, setup(aperture = 64.0).aperture)
    }

    @Test
    fun testNonFiniteValuesBecomeTheDefaults() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(24.0, setup(focalLength = value).focalLengthMillimeters, "focal length $value")
            assertEquals(2.8, setup(aperture = value).aperture, "aperture $value")
            assertNull(setup(pitch = value).pixelPitchMicrometers, "pixel pitch $value")
        }
    }

    @Test
    fun testPixelPitchOutsideTheSupportedRangeIsTreatedAsUnknown() {
        assertEquals(0.3, setup(pitch = 0.3).pixelPitchMicrometers)
        assertEquals(100.0, setup(pitch = 100.0).pixelPitchMicrometers)
        for (value in listOf(0.29, 100.1, 0.0, -5.0, 1e9)) {
            assertNull(setup(pitch = value).pixelPitchMicrometers, "pixel pitch $value")
        }
    }

    @Test
    fun testEveryInputProducesAUsableSetup() {
        val values = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0, 1e-300, 1e300, 4.0, 24.0, 2000.0)
        for (focalLength in values) {
            for (aperture in values) {
                for (pitch in listOf<Double?>(null, 0.0, 1e300, 4.0)) {
                    val lens = setup(focalLength = focalLength, aperture = aperture, pitch = pitch)
                    assertTrue(LensSetup.focalLengthRange.contains(lens.focalLengthMillimeters))
                    assertTrue(LensSetup.apertureRange.contains(lens.aperture))
                    assertTrue(lens.pixelPitchMicrometers?.let { SensorFormat.pixelPitchRange.contains(it) } ?: true)
                    assertTrue(lens.fieldOfView.horizontal.isFinite())
                }
            }
        }
    }

    // MARK: Validation

    @Test
    fun testValidatedAcceptsEverythingInRange() {
        assertEquals(setup(pitch = 5.9), validated(pitch = 5.9))
        assertEquals(setup(), validated())
        assertNotNull(validated(focalLength = 4.0))
        assertNotNull(validated(focalLength = 2000.0))
        assertNotNull(validated(aperture = 0.95))
        assertNotNull(validated(aperture = 32.0))
        assertNotNull(validated(pitch = 0.3))
        assertNotNull(validated(pitch = 100.0))
    }

    @Test
    fun testValidatedRejectsAnythingOutOfRange() {
        assertNull(validated(focalLength = 3.999))
        assertNull(validated(focalLength = 2000.001))
        assertNull(validated(aperture = 0.94))
        assertNull(validated(aperture = 32.01))
        assertNull(validated(pitch = 0.29))
        assertNull(validated(pitch = 100.1))
        for (value in listOf(0.0, -24.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e308)) {
            assertNull(validated(focalLength = value), "focal length $value")
            assertNull(validated(aperture = value), "aperture $value")
            assertNull(validated(pitch = value), "pixel pitch $value")
        }
    }

    // MARK: Derived values

    @Test
    fun testEquivalentFocalLength() {
        assertEquals(50.0, setup(focalLength = 50.0).equivalentFocalLengthMillimeters, 1e-12)
        val crop = SensorFormat.custom(width = 24.0, height = 16.0)
        assertEquals(36.0, setup(sensor = crop, focalLength = 24.0).equivalentFocalLengthMillimeters, 1e-9)
        assertEquals(
            38.7177,
            setup(sensor = SensorFormat.apsCCanon, focalLength = 24.0).equivalentFocalLengthMillimeters,
            0.0001,
        )
    }

    @Test
    fun testImageScale() {
        // 206.265 x pitch(um) / focal length(mm) arcseconds per pixel.
        assertEquals(20.6265, assertNotNull(setup(focalLength = 50.0, pitch = 5.0).imageScaleArcsecondsPerPixel), 0.0001)
        assertEquals(51.3084, assertNotNull(setup(focalLength = 24.0, pitch = 5.97).imageScaleArcsecondsPerPixel), 0.0001)
        assertNull(setup(focalLength = 50.0).imageScaleArcsecondsPerPixel)
    }

    @Test
    fun testFieldOfViewMatchesTheStaticFunctions() {
        val lens = setup(sensor = SensorFormat.apsC, focalLength = 35.0)
        assertEquals(FieldOfView.horizontal(sensor = SensorFormat.apsC, focalLength = 35.0), lens.fieldOfView.horizontal)
        assertEquals(FieldOfView.vertical(sensor = SensorFormat.apsC, focalLength = 35.0), lens.fieldOfView.vertical)
        assertEquals(FieldOfView.diagonal(sensor = SensorFormat.apsC, focalLength = 35.0), lens.fieldOfView.diagonal)
        assertEquals(FieldOfView.of(sensor = SensorFormat.apsC, focalLength = 35.0), lens.fieldOfView)
    }

    // MARK: Codable

    @Test
    fun testCodableRoundTrip() {
        for (lens in listOf(setup(), setup(sensor = SensorFormat.microFourThirds, focalLength = 300.0, aperture = 4.0, pitch = 3.7))) {
            assertEquals(lens, decodeSetup(encode(lens)))
        }
    }

    @Test
    fun testEncodingOmitsAnUnknownPixelPitch() {
        val data = encode(setup())
        val keys = Json.parseToJsonElement(data).jsonObject.keys
        assertEquals(setOf("sensor", "focalLengthMillimeters", "aperture"), keys)
    }

    @Test
    fun testDecodingRejectsOutOfRangeValues() {
        val sensor = encode(SensorFormat.fullFrame)
        val documents = listOf(
            """{"sensor":$sensor,"focalLengthMillimeters":3,"aperture":2.8}""",
            """{"sensor":$sensor,"focalLengthMillimeters":5000,"aperture":2.8}""",
            """{"sensor":$sensor,"focalLengthMillimeters":24,"aperture":0}""",
            """{"sensor":$sensor,"focalLengthMillimeters":24,"aperture":64}""",
            """{"sensor":$sensor,"focalLengthMillimeters":24,"aperture":2.8,"pixelPitchMicrometers":1000}""",
            """{"sensor":$sensor,"focalLengthMillimeters":24,"aperture":2.8,"pixelPitchMicrometers":-4}""",
            """{"sensor":{"id":"a","name":"b","widthMillimeters":0,"heightMillimeters":24},"focalLengthMillimeters":24,"aperture":2.8}""",
            """{"focalLengthMillimeters":24,"aperture":2.8}""",
            """{"sensor":$sensor,"aperture":2.8}""",
            """{"sensor":$sensor,"focalLengthMillimeters":"24","aperture":2.8}""",
        )
        for (document in documents) {
            assertFailsWith<SerializationException>(document) { decodeSetup(document) }
        }
        val valid = """{"sensor":$sensor,"focalLengthMillimeters":24,"aperture":2.8,"pixelPitchMicrometers":5.9}"""
        decodeSetup(valid)
    }
}

// MARK: - Field of view and framing

class FieldOfViewTests {

    private fun frame(sensor: SensorFormat, focalLength: Double): FrameSize =
        FieldOfView.of(sensor = sensor, focalLength = focalLength)

    @Test
    fun testFiftyMillimetreOnFullFrame() {
        val frame = frame(SensorFormat.fullFrame, 50.0)
        assertEquals(39.6, frame.horizontal, 0.05)
        assertEquals(27.0, frame.vertical, 0.05)
        assertEquals(46.8, frame.diagonal, 0.05)
        assertEquals(39.597753, frame.horizontal, 1e-6)
        assertEquals(26.991467, frame.vertical, 1e-6)
        assertEquals(46.793003, frame.diagonal, 1e-6)
    }

    @Test
    fun testOtherFullFrameLenses() {
        val wide = frame(SensorFormat.fullFrame, 14.0)
        assertEquals(104.250033, wide.horizontal, 1e-6)
        assertEquals(81.202589, wide.vertical, 1e-6)
        assertEquals(114.182131, wide.diagonal, 1e-6)

        val normal = frame(SensorFormat.fullFrame, 24.0)
        // tan(36.87 degrees) is exactly 3/4, so 24 mm on 36 mm is 2 x atan(0.75).
        assertEquals(73.739795, normal.horizontal, 1e-6)
        assertEquals(53.130102, normal.vertical, 1e-6)

        val tele = frame(SensorFormat.fullFrame, 100.0)
        assertEquals(20.407947, tele.horizontal, 1e-6)
        assertEquals(13.685547, tele.vertical, 1e-6)
    }

    @Test
    fun testStaticFunctionsAgreeWithFrameSize() {
        val frame = frame(SensorFormat.apsC, 85.0)
        assertEquals(frame.horizontal, FieldOfView.horizontal(sensor = SensorFormat.apsC, focalLength = 85.0))
        assertEquals(frame.vertical, FieldOfView.vertical(sensor = SensorFormat.apsC, focalLength = 85.0))
        assertEquals(frame.diagonal, FieldOfView.diagonal(sensor = SensorFormat.apsC, focalLength = 85.0))
    }

    @Test
    fun testLongerLensesSeeLess() {
        var previous = Double.POSITIVE_INFINITY
        for (focalLength in listOf(8.0, 14.0, 24.0, 50.0, 135.0, 400.0, 2000.0)) {
            val horizontal = frame(SensorFormat.fullFrame, focalLength).horizontal
            assertTrue(horizontal < previous)
            previous = horizontal
        }
    }

    @Test
    fun testSmallerSensorsSeeLess() {
        var previous = Double.POSITIVE_INFINITY
        val sensors = listOf(
            SensorFormat.mediumFormat44x33, SensorFormat.fullFrame, SensorFormat.apsC, SensorFormat.apsCCanon,
            SensorFormat.microFourThirds, SensorFormat.oneInch,
        )
        for (sensor in sensors) {
            val horizontal = frame(sensor, 50.0).horizontal
            assertTrue(horizontal < previous, sensor.id)
            previous = horizontal
        }
    }

    @Test
    fun testCropFactorEquivalence() {
        // A sensor with crop factor 1.5 at 24 mm frames like full frame at 36 mm.
        val crop = SensorFormat.custom(width = 24.0, height = 16.0)
        val cropped = frame(crop, 24.0)
        val equivalent = frame(SensorFormat.fullFrame, 36.0)
        assertEquals(equivalent.horizontal, cropped.horizontal, 1e-9)
        assertEquals(equivalent.vertical, cropped.vertical, 1e-9)
        assertEquals(equivalent.diagonal, cropped.diagonal, 1e-9)
    }

    @Test
    fun testFocalLengthsBelowTheLensRangeAreStillCalculated() {
        // A phone camera has a real focal length of a few millimetres; the maths holds for it.
        val expected = 2 * atan(13.2 / 4) * 180 / PI
        assertEquals(expected, FieldOfView.horizontal(sensor = SensorFormat.oneInch, focalLength = 2.0), 1e-9)
    }

    @Test
    fun testInvalidFocalLengthsUseTheDefaultLens() {
        val reference = frame(SensorFormat.fullFrame, LensSetup.defaultFocalLengthMillimeters)
        for (focalLength in listOf(0.0, -50.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0)) {
            assertEquals(
                reference.horizontal,
                FieldOfView.horizontal(sensor = SensorFormat.fullFrame, focalLength = focalLength),
                "$focalLength",
            )
            assertEquals(
                reference.vertical,
                FieldOfView.vertical(sensor = SensorFormat.fullFrame, focalLength = focalLength),
                "$focalLength",
            )
            assertEquals(
                reference.diagonal,
                FieldOfView.diagonal(sensor = SensorFormat.fullFrame, focalLength = focalLength),
                "$focalLength",
            )
            assertEquals(reference, FieldOfView.of(sensor = SensorFormat.fullFrame, focalLength = focalLength), "$focalLength")
        }
    }

    @Test
    fun testResultsAreAlwaysValidAngles() {
        for (focalLength in listOf(1e-300, 1e-9, 1e-3, 1.0, 1e3, 1e9, 1e300)) {
            val size = frame(SensorFormat.fullFrame, focalLength)
            for (angle in listOf(size.horizontal, size.vertical, size.diagonal)) {
                assertTrue(angle.isFinite() && angle >= 0 && angle <= 180, "$angle at $focalLength mm")
            }
        }
        assertEquals(180.0, frame(SensorFormat.fullFrame, 1e-9).horizontal, 1e-6)
        assertEquals(2.0626e-6, frame(SensorFormat.fullFrame, 1e9).horizontal, 1e-9)
    }

    @Test
    fun testFrameSizeGeometry() {
        val size = FrameSize(horizontal = 20.0, vertical = 13.0, diagonal = 24.0)
        assertEquals(20.0, size.longSide)
        assertEquals(13.0, size.shortSide)
        assertEquals(FrameSize(horizontal = 13.0, vertical = 20.0, diagonal = 24.0), size.rotated)
        assertEquals(size, size.rotated.rotated)
        assertEquals(20.0, size.rotated.longSide)
        assertEquals(13.0, size.rotated.shortSide)
    }

    // MARK: Framing

    @Test
    fun testAndromedaInA135mmFrame() {
        // M31 spans about 190 x 60 arcminutes.
        val framing = assertNotNull(frame(SensorFormat.fullFrame, 135.0).framing(objectMajorArcmin = 190.0, minorArcmin = 60.0))
        assertEquals(0.208480, framing.majorAxisFraction, 1e-6)
        assertEquals(0.098433, framing.minorAxisFraction, 1e-6)
        assertEquals(framing.majorAxisFraction, framing.fillFraction)
        assertTrue(framing.fitsInFrame)
    }

    @Test
    fun testObjectThatDoesNotFit() {
        val tight = assertNotNull(frame(SensorFormat.fullFrame, 800.0).framing(objectMajorArcmin = 190.0, minorArcmin = 60.0))
        assertEquals(1.228402, tight.majorAxisFraction, 1e-6)
        assertEquals(0.581820, tight.minorAxisFraction, 1e-6)
        assertEquals(tight.majorAxisFraction, tight.fillFraction)
        assertFalse(tight.fitsInFrame)

        val snug = assertNotNull(frame(SensorFormat.fullFrame, 600.0).framing(objectMajorArcmin = 190.0, minorArcmin = 60.0))
        assertEquals(0.921422, snug.majorAxisFraction, 1e-6)
        assertTrue(snug.fitsInFrame)
    }

    @Test
    fun testFramingUsesTheTighterDimension() {
        // A nearly round object is limited by the short side of the frame.
        val framing = assertNotNull(frame(SensorFormat.fullFrame, 135.0).framing(objectMajorArcmin = 500.0, minorArcmin = 480.0))
        assertTrue(framing.minorAxisFraction > framing.majorAxisFraction)
        assertEquals(framing.minorAxisFraction, framing.fillFraction)
        assertEquals(480 / (10.159216 * 60), framing.minorAxisFraction, 1e-6)
    }

    @Test
    fun testFramingIsIndependentOfOrientationAndArgumentOrder() {
        val landscape = frame(SensorFormat.fullFrame, 200.0)
        val first = assertNotNull(landscape.framing(objectMajorArcmin = 120.0, minorArcmin = 40.0))
        assertEquals(first, landscape.rotated.framing(objectMajorArcmin = 120.0, minorArcmin = 40.0))
        assertEquals(first, landscape.framing(objectMajorArcmin = 40.0, minorArcmin = 120.0))
    }

    @Test
    fun testFramingBoundary() {
        val size = FrameSize(horizontal = 2.0, vertical = 1.0, diagonal = 2.3)
        val exact = assertNotNull(size.framing(objectMajorArcmin = 120.0, minorArcmin = 30.0))
        assertEquals(1.0, exact.majorAxisFraction, 1e-12)
        assertTrue(exact.fitsInFrame)
        val over = assertNotNull(size.framing(objectMajorArcmin = 120.01, minorArcmin = 30.0))
        assertFalse(over.fitsInFrame)
    }

    @Test
    fun testFramingOfLineLikeObjects() {
        val framing = assertNotNull(frame(SensorFormat.fullFrame, 135.0).framing(objectMajorArcmin = 60.0, minorArcmin = 0.0))
        assertEquals(0.0, framing.minorAxisFraction)
        assertTrue(framing.majorAxisFraction > 0)
    }

    @Test
    fun testFramingRejectsInvalidInput() {
        val size = frame(SensorFormat.fullFrame, 135.0)
        assertNull(size.framing(objectMajorArcmin = 0.0, minorArcmin = 0.0))
        assertNull(size.framing(objectMajorArcmin = -1.0, minorArcmin = 1.0))
        assertNull(size.framing(objectMajorArcmin = 10.0, minorArcmin = -1.0))
        assertNull(size.framing(objectMajorArcmin = Double.NaN, minorArcmin = 1.0))
        assertNull(size.framing(objectMajorArcmin = 10.0, minorArcmin = Double.NaN))
        assertNull(size.framing(objectMajorArcmin = Double.POSITIVE_INFINITY, minorArcmin = 1.0))
        assertNull(FrameSize(horizontal = 0.0, vertical = 10.0, diagonal = 10.0).framing(objectMajorArcmin = 10.0, minorArcmin = 5.0))
        assertNull(FrameSize(horizontal = -4.0, vertical = 10.0, diagonal = 10.0).framing(objectMajorArcmin = 10.0, minorArcmin = 5.0))
        assertNull(FrameSize(horizontal = Double.NaN, vertical = 10.0, diagonal = 10.0).framing(objectMajorArcmin = 10.0, minorArcmin = 5.0))
    }
}

// MARK: - Exposure rules

class ExposureCalculatorTests {

    private fun lens(
        sensor: SensorFormat = SensorFormat.fullFrame,
        focalLength: Double = 24.0,
        aperture: Double = 2.8,
        pitch: Double? = null,
    ): LensSetup =
        LensSetup(sensor = sensor, focalLengthMillimeters = focalLength, aperture = aperture, pixelPitchMicrometers = pitch)

    private val crop15 = SensorFormat.custom(width = 24.0, height = 16.0)

    // MARK: 500, 400 and 300

    @Test
    fun testRuleOf500OnFullFrame() {
        val estimate = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = lens())
        assertEquals(500.0 / 24, estimate.seconds, 1e-9)
        assertEquals(ExposureRule.RULE500, estimate.rule)
        assertFalse(estimate.usedDefaultPixelPitch)
        assertEquals(1.0, estimate.declinationFactor)
        assertTrue(estimate.notes.isEmpty())
    }

    @Test
    fun testRuleOf500OnA1Point5CropSensor() {
        // 500 / (24 mm x 1.5) = 13.9 s
        val estimate = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = lens(sensor = crop15))
        assertEquals(13.9, estimate.seconds, 0.05)
        assertEquals(500.0 / 36, estimate.seconds, 1e-9)
    }

    @Test
    fun testRuleOf500OnRealCropSensors() {
        val canon = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = lens(sensor = SensorFormat.apsCCanon))
        assertEquals(12.9140, canon.seconds, 0.0001)
        val apsC = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = lens(sensor = SensorFormat.apsC))
        assertEquals(500 / (24 * SensorFormat.apsC.cropFactor), apsC.seconds, 1e-9)
        // The larger the crop factor, the shorter the exposure.
        assertTrue(
            canon.seconds > ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = lens(sensor = SensorFormat.oneInch)).seconds,
        )
    }

    @Test
    fun testRulesOf400And300() {
        val setup = lens()
        assertEquals(400.0 / 24, ExposureCalculator.maxExposure(rule = ExposureRule.RULE400, setup = setup).seconds, 1e-9)
        assertEquals(12.5, ExposureCalculator.maxExposure(rule = ExposureRule.RULE300, setup = setup).seconds, 1e-9)
        val order = ExposureRule.entries.filter { it != ExposureRule.NPF }
            .map { ExposureCalculator.maxExposure(rule = it, setup = setup).seconds }
        assertEquals(order.sortedDescending(), order, "500 is the most lenient rule and 300 the strictest")
    }

    @Test
    fun testRulesOf500FamilyIgnorePixelPitchAndAperture() {
        val reference = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = lens()).seconds
        val other = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = lens(aperture = 8.0, pitch = 2.0)).seconds
        assertEquals(reference, other)
    }

    // MARK: NPF

    @Test
    fun testNPFAgainstPublishedExample() {
        // Light Stalking's worked example: Nikon D750 (35.9 mm / 6016 px = 5.97 um), 24 mm at f/2.8,
        // (35 x 2.8 + 30 x 5.97) / 24 = 11.54 s.
        val estimate = ExposureCalculator.maxExposure(rule = ExposureRule.NPF, setup = lens(pitch = 5.97))
        assertEquals(11.54, estimate.seconds, 0.01)
        assertEquals((35 * 2.8 + 30 * 5.97) / 24, estimate.seconds, 1e-12)
        assertEquals(11.545833, estimate.seconds, 1e-6)
        assertFalse(estimate.usedDefaultPixelPitch)
        assertTrue(estimate.notes.isEmpty())
    }

    @Test
    fun testNPFWithAnotherLens() {
        val setup = lens(sensor = SensorFormat.fullFrame, focalLength = 85.0, aperture = 1.8, pitch = 3.76)
        assertEquals(2.068235, ExposureCalculator.maxExposure(rule = ExposureRule.NPF, setup = setup).seconds, 1e-6)
    }

    @Test
    fun testNPFUsesTheRealFocalLengthNotTheEquivalent() {
        // The same lens, aperture and pixel pitch give the same NPF exposure on any sensor size.
        val fullFrame = ExposureCalculator.maxExposure(rule = ExposureRule.NPF, setup = lens(sensor = SensorFormat.fullFrame, pitch = 4.0))
        val apsC = ExposureCalculator.maxExposure(rule = ExposureRule.NPF, setup = lens(sensor = SensorFormat.apsC, pitch = 4.0))
        val oneInch = ExposureCalculator.maxExposure(rule = ExposureRule.NPF, setup = lens(sensor = SensorFormat.oneInch, pitch = 4.0))
        assertEquals(apsC.seconds, fullFrame.seconds)
        assertEquals(oneInch.seconds, fullFrame.seconds)
    }

    @Test
    fun testNPFGrowsWithPixelPitchAndAperture() {
        val small = ExposureCalculator.maxExposure(rule = ExposureRule.NPF, setup = lens(pitch = 3.0)).seconds
        val large = ExposureCalculator.maxExposure(rule = ExposureRule.NPF, setup = lens(pitch = 6.0)).seconds
        assertTrue(large > small)
        val fast = ExposureCalculator.maxExposure(rule = ExposureRule.NPF, setup = lens(aperture = 1.8, pitch = 4.0)).seconds
        val slow = ExposureCalculator.maxExposure(rule = ExposureRule.NPF, setup = lens(aperture = 5.6, pitch = 4.0)).seconds
        assertTrue(slow > fast)
    }

    @Test
    fun testNPFFallsBackToTheDefaultPixelPitch() {
        assertEquals(4.0, ExposureCalculator.defaultPixelPitchMicrometers)
        val estimate = ExposureCalculator.maxExposure(rule = ExposureRule.NPF, setup = lens(focalLength = 14.0))
        assertEquals((35 * 2.8 + 30 * 4.0) / 14, estimate.seconds, 1e-12)
        assertEquals(15.571429, estimate.seconds, 1e-6)
        assertTrue(estimate.usedDefaultPixelPitch)
        assertEquals(1, estimate.notes.size)
        assertTrue(estimate.notes[0].contains("4.0"), estimate.notes[0])
        assertTrue(estimate.notes[0].lowercase().contains("pixel pitch"), estimate.notes[0])
    }

    @Test
    fun testOnlyNPFReportsTheDefaultPixelPitch() {
        for (rule in ExposureRule.entries) {
            val estimate = ExposureCalculator.maxExposure(rule = rule, setup = lens())
            assertEquals(rule == ExposureRule.NPF, estimate.usedDefaultPixelPitch, rule.rawValue)
            val known = ExposureCalculator.maxExposure(rule = rule, setup = lens(pitch = 5.0))
            assertFalse(known.usedDefaultPixelPitch, rule.rawValue)
        }
    }

    // MARK: Declination

    @Test
    fun testDeclinationLengthensTheExposure() {
        val setup = lens()
        val equator = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = setup)
        val sixty = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = setup, declination = 60.0)
        assertEquals(2.0, sixty.declinationFactor, 1e-12)
        assertEquals(equator.seconds * 2, sixty.seconds, 1e-9)
        assertEquals(1, sixty.notes.size)
        assertTrue(sixty.notes[0].contains("+60.0\u00B0"), sixty.notes[0])
        assertTrue(sixty.notes[0].contains("\u00D72.0 "), sixty.notes[0])

        val thirty = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = setup, declination = 30.0)
        assertEquals(1.154701, thirty.declinationFactor, 1e-6)
        val seventy = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = setup, declination = 70.0)
        assertEquals(2.923804, seventy.declinationFactor, 1e-6)
        assertTrue(seventy.notes[0].contains("\u00D72.9"), seventy.notes[0])
    }

    @Test
    fun testDeclinationIsSymmetricAndAppliesToEveryRule() {
        val setup = lens(pitch = 5.0)
        for (rule in ExposureRule.entries) {
            val north = ExposureCalculator.maxExposure(rule = rule, setup = setup, declination = 45.0)
            val south = ExposureCalculator.maxExposure(rule = rule, setup = setup, declination = -45.0)
            val equator = ExposureCalculator.maxExposure(rule = rule, setup = setup, declination = 0.0)
            assertEquals(south.seconds, north.seconds, 1e-9, rule.rawValue)
            assertEquals(equator.seconds * (1 / cos(PI / 4)), north.seconds, 1e-9, rule.rawValue)
            assertTrue(south.notes[0].contains("\u221245.0\u00B0"), south.notes[0])
        }
    }

    @Test
    fun testDeclinationAtTheEquatorChangesNothing() {
        val setup = lens()
        val plain = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = setup)
        for (declination in listOf(0.0, -0.0, 1.0, -1.0, 2.0)) {
            val estimate = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = setup, declination = declination)
            assertTrue(estimate.notes.isEmpty(), "$declination should not need a note: ${estimate.notes}")
            assertEquals(plain.seconds, estimate.seconds, plain.seconds * 0.001)
        }
    }

    @Test
    fun testDeclinationFactorIsCapped() {
        assertEquals(5.0, ExposureCalculator.maximumDeclinationFactor)
        val setup = lens()
        val base = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = setup).seconds
        // 1 / cos(78.46 degrees) = 5; beyond that the cap applies.
        for (declination in listOf(80.0, 85.0, 89.9, 90.0, -80.0, -90.0)) {
            val estimate = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = setup, declination = declination)
            assertEquals(5.0, estimate.declinationFactor, declination.toString())
            assertEquals(base * 5, estimate.seconds, 1e-9, declination.toString())
            assertEquals(1, estimate.notes.size)
            assertTrue(estimate.notes[0].contains("\u00D75.0, the maximum"), estimate.notes[0])
        }
        val justBelow = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = setup, declination = 78.0)
        assertTrue(justBelow.declinationFactor < 5)
        assertFalse(justBelow.notes[0].contains("maximum"))
    }

    @Test
    fun testInvalidDeclinationIsIgnoredWithANote() {
        val setup = lens()
        val plain = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = setup)
        for (declination in listOf(91.0, -90.0001, 180.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e300)) {
            val estimate = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = setup, declination = declination)
            assertEquals(plain.seconds, estimate.seconds, "$declination")
            assertEquals(1.0, estimate.declinationFactor)
            assertEquals(1, estimate.notes.size)
            assertTrue(estimate.notes[0].contains("ignored"), estimate.notes[0])
        }
    }

    @Test
    fun testNPFNotesCombine() {
        val estimate = ExposureCalculator.maxExposure(rule = ExposureRule.NPF, setup = lens(), declination = 60.0)
        assertEquals(2, estimate.notes.size)
        assertTrue(estimate.usedDefaultPixelPitch)
        assertEquals((35 * 2.8 + 30 * 4.0) / 24 * 2, estimate.seconds, 1e-9)
    }

    @Test
    fun testEveryLensGivesAFinitePositiveExposure() {
        val focalLengths = listOf(Double.NaN, 0.0, 4.0, 24.0, 2000.0, 1e300)
        for (rule in ExposureRule.entries) {
            for (focalLength in focalLengths) {
                val setup = lens(focalLength = focalLength, aperture = Double.NaN)
                val estimate = ExposureCalculator.maxExposure(rule = rule, setup = setup, declination = 45.0)
                assertTrue(estimate.seconds.isFinite() && estimate.seconds > 0, "$rule at $focalLength mm")
            }
        }
    }

    // MARK: Rule metadata

    @Test
    fun testExposureRuleMetadata() {
        assertEquals(
            listOf(ExposureRule.RULE500, ExposureRule.RULE400, ExposureRule.RULE300, ExposureRule.NPF),
            ExposureRule.entries,
        )
        assertEquals(listOf("500 rule", "400 rule", "300 rule", "NPF rule"), ExposureRule.entries.map { it.displayName })
        assertEquals(listOf("rule500", "rule400", "rule300", "npf"), ExposureRule.entries.map { it.id })
        for (rule in ExposureRule.entries) {
            assertFalse(rule.summary.isEmpty())
        }
    }

    @Test
    fun testExposureRuleCodableUsesStableRawValues() {
        val data = Json.encodeToString<List<ExposureRule>>(ExposureRule.entries)
        assertEquals("""["rule500","rule400","rule300","npf"]""", data)
        assertEquals(ExposureRule.entries, Json.decodeFromString<List<ExposureRule>>(data))
        assertFailsWith<SerializationException> { Json.decodeFromString<ExposureRule>("\"rule600\"") }
    }

    // MARK: Star trails

    @Test
    fun testSiderealRate() {
        assertEquals(15.041, ExposureCalculator.siderealRateArcsecondsPerSecond)
        // 360 degrees in one sidereal day of 86164.0905 s.
        assertEquals(360 * 3600 / 86164.0905, ExposureCalculator.siderealRateArcsecondsPerSecond, 0.0001)
    }

    @Test
    fun testTrailLengthInArcseconds() {
        assertEquals(150.41, ExposureCalculator.trailLengthArcseconds(exposureSeconds = 10.0), 1e-9)
        assertEquals(150.41, ExposureCalculator.trailLengthArcseconds(exposureSeconds = 10.0, declination = 0.0), 1e-9)
        assertEquals(75.205, ExposureCalculator.trailLengthArcseconds(exposureSeconds = 10.0, declination = 60.0), 1e-9)
        assertEquals(75.205, ExposureCalculator.trailLengthArcseconds(exposureSeconds = 10.0, declination = -60.0), 1e-9)
        assertEquals(0.0, ExposureCalculator.trailLengthArcseconds(exposureSeconds = 10.0, declination = 90.0), 1e-9)
        assertEquals(0.0, ExposureCalculator.trailLengthArcseconds(exposureSeconds = 0.0))
        // One sidereal day of exposure at the equator is a full circle, to the precision of the rate.
        assertEquals(360.0 * 3600, ExposureCalculator.trailLengthArcseconds(exposureSeconds = 86164.0905), 10.0)
    }

    @Test
    fun testTrailLengthInPixels() {
        val setup = lens(focalLength = 50.0, aperture = 2.8, pitch = 5.0)
        // 150.41 arcsec at 20.6265 arcsec per pixel.
        assertEquals(
            7.292083,
            ExposureCalculator.trailLengthPixels(exposureSeconds = 10.0, declination = null, setup = setup),
            1e-6,
        )
        assertEquals(7.292083, ExposureCalculator.trailLengthPixels(exposureSeconds = 10.0, setup = setup), 1e-6)
        assertEquals(
            3.646041,
            ExposureCalculator.trailLengthPixels(exposureSeconds = 10.0, declination = 60.0, setup = setup),
            1e-6,
        )
    }

    @Test
    fun testTrailLengthScalesWithFocalLengthAndPixelPitch() {
        fun pixels(focalLength: Double, pitch: Double): Double =
            ExposureCalculator.trailLengthPixels(exposureSeconds = 8.0, setup = lens(focalLength = focalLength, pitch = pitch))

        val base = pixels(focalLength = 50.0, pitch = 5.0)
        assertEquals(base * 2, pixels(focalLength = 100.0, pitch = 5.0), 1e-9)
        assertEquals(base / 2, pixels(focalLength = 50.0, pitch = 10.0), 1e-9)
    }

    @Test
    fun testTrailLengthAssumesTheDefaultPixelPitchWhenUnknown() {
        val unknown = ExposureCalculator.trailLengthPixels(exposureSeconds = 10.0, setup = lens(focalLength = 50.0))
        val assumed = ExposureCalculator.trailLengthPixels(
            exposureSeconds = 10.0,
            setup = lens(focalLength = 50.0, pitch = ExposureCalculator.defaultPixelPitchMicrometers),
        )
        assertEquals(assumed, unknown)
        assertEquals(150.41 / (206.264806 * 4.0 / 50), unknown, 1e-5)
    }

    @Test
    fun testTrailLengthTreatsInvalidInputAsHarmless() {
        val setup = lens(pitch = 5.0)
        for (exposure in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(0.0, ExposureCalculator.trailLengthArcseconds(exposureSeconds = exposure), "$exposure")
            assertEquals(0.0, ExposureCalculator.trailLengthPixels(exposureSeconds = exposure, setup = setup), "$exposure")
        }
        // An unusable declination counts as the equator, where trails are longest.
        val equator = ExposureCalculator.trailLengthArcseconds(exposureSeconds = 10.0, declination = 0.0)
        val equatorPixels = ExposureCalculator.trailLengthPixels(exposureSeconds = 10.0, declination = 0.0, setup = setup)
        for (declination in listOf(90.5, -91.0, Double.NaN, Double.POSITIVE_INFINITY, 1e300)) {
            assertEquals(
                equator,
                ExposureCalculator.trailLengthArcseconds(exposureSeconds = 10.0, declination = declination),
                "$declination",
            )
            assertEquals(
                equatorPixels,
                ExposureCalculator.trailLengthPixels(exposureSeconds = 10.0, declination = declination, setup = setup),
                "$declination",
            )
        }
    }

    @Test
    fun testRuleOf500TrailsSeveralPixelsOnAModernSensor() {
        // A sanity check on the physics: at the 500-rule limit of a 24 mm lens on a 45 MP full-frame
        // camera (4.38 um pixels) a star trails over eight pixels; NPF allows about half that.
        val pitch = assertNotNull(SensorFormat.fullFrame.pixelPitchMicrometers(megapixels = 45.0))
        val setup = lens(focalLength = 24.0, pitch = pitch)
        val limit = ExposureCalculator.maxExposure(rule = ExposureRule.RULE500, setup = setup).seconds
        val trail = ExposureCalculator.trailLengthPixels(exposureSeconds = limit, setup = setup)
        assertEquals(8.3209, trail, 0.0001)
        val npf = ExposureCalculator.maxExposure(rule = ExposureRule.NPF, setup = setup).seconds
        val npfTrail = ExposureCalculator.trailLengthPixels(exposureSeconds = npf, setup = setup)
        assertEquals(3.8185, npfTrail, 0.0001)
        assertTrue(npfTrail < trail)
    }
}
