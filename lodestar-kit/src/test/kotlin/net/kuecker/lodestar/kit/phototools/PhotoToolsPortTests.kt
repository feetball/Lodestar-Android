package net.kuecker.lodestar.kit.phototools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for the parts of the port that have no Swift counterpart: the shape of the JSON, its
// independence from the Json configuration, and the Kotlin-only factory and constructor rules.

class PhotoToolsSerializationTests {

    private val fullFrameJson = """{"id":"fullFrame","name":"Full Frame","widthMillimeters":36.0,"heightMillimeters":24.0}"""

    @Test
    fun testSensorFormatJsonShape() {
        assertEquals(fullFrameJson, Json.encodeToString(SensorFormat.serializer(), SensorFormat.fullFrame))
    }

    @Test
    fun testLensSetupJsonShape() {
        val lens = LensSetup(SensorFormat.fullFrame, 24.0, 2.8, 5.9)
        assertEquals(
            """{"sensor":$fullFrameJson,"focalLengthMillimeters":24.0,"aperture":2.8,"pixelPitchMicrometers":5.9}""",
            Json.encodeToString(LensSetup.serializer(), lens),
        )
        val withoutPitch = LensSetup(SensorFormat.fullFrame, 24.0, 2.8)
        assertEquals(
            """{"sensor":$fullFrameJson,"focalLengthMillimeters":24.0,"aperture":2.8}""",
            Json.encodeToString(LensSetup.serializer(), withoutPitch),
        )
    }

    @Test
    fun testUnknownPixelPitchIsOmittedWhateverTheJsonConfiguration() {
        val lens = LensSetup(SensorFormat.apsC, 35.0, 1.8)
        for (json in listOf(Json { encodeDefaults = true }, Json { explicitNulls = true }, Json { explicitNulls = false })) {
            val keys = json.parseToJsonElement(json.encodeToString(LensSetup.serializer(), lens)).jsonObject.keys
            assertEquals(setOf("sensor", "focalLengthMillimeters", "aperture"), keys)
        }
    }

    @Test
    fun testUnknownKeysAreIgnoredLikeSwiftsJsonDecoder() {
        val strict = Json { ignoreUnknownKeys = false }
        val sensor = """{"id":"fullFrame","name":"Full Frame","widthMillimeters":36,"heightMillimeters":24,"future":[1,2,{"a":null}]}"""
        assertEquals(SensorFormat.fullFrame, strict.decodeFromString(SensorFormat.serializer(), sensor))
        val lens = """{"sensor":$sensor,"focalLengthMillimeters":24,"aperture":2.8,"extra":"x"}"""
        assertEquals(LensSetup(SensorFormat.fullFrame, 24.0, 2.8), strict.decodeFromString(LensSetup.serializer(), lens))
    }

    @Test
    fun testNullPixelPitchMeansUnknown() {
        val sensor = Json.encodeToString(SensorFormat.serializer(), SensorFormat.fullFrame)
        val lens = Json.decodeFromString(
            LensSetup.serializer(),
            """{"sensor":$sensor,"focalLengthMillimeters":24,"aperture":2.8,"pixelPitchMicrometers":null}""",
        )
        assertNull(lens.pixelPitchMicrometers)
    }

    @Test
    fun testQuotedAndNonNumericValuesAreRejectedEvenWhenLenient() {
        val lenient = Json { isLenient = true }
        val sensor = Json.encodeToString(SensorFormat.serializer(), SensorFormat.fullFrame)
        val documents = listOf(
            """{"sensor":$sensor,"focalLengthMillimeters":"24","aperture":2.8}""",
            """{"sensor":$sensor,"focalLengthMillimeters":24,"aperture":"2.8"}""",
            """{"sensor":$sensor,"focalLengthMillimeters":true,"aperture":2.8}""",
            """{"sensor":$sensor,"focalLengthMillimeters":24,"aperture":NaN}""",
            """{"sensor":$sensor,"focalLengthMillimeters":24,"aperture":2.8,"pixelPitchMicrometers":"5"}""",
            """{"sensor":$sensor,"focalLengthMillimeters":0x18,"aperture":2.8}""",
            """{"sensor":null,"focalLengthMillimeters":24,"aperture":2.8}""",
            """{"sensor":[],"focalLengthMillimeters":24,"aperture":2.8}""",
        )
        for (document in documents) {
            assertFailsWith<SerializationException>(document) { lenient.decodeFromString(LensSetup.serializer(), document) }
        }
    }

    @Test
    fun testBoundariesOfTheSupportedRangesDecode() {
        val sensor = Json.encodeToString(SensorFormat.serializer(), SensorFormat.fullFrame)
        for ((focalLength, aperture, pitch) in listOf(
            Triple("4", "0.95", "0.3"),
            Triple("2000", "32", "100"),
        )) {
            val document = """{"sensor":$sensor,"focalLengthMillimeters":$focalLength,"aperture":$aperture,"pixelPitchMicrometers":$pitch}"""
            val lens = Json.decodeFromString(LensSetup.serializer(), document)
            assertEquals(focalLength.toDouble(), lens.focalLengthMillimeters)
            assertEquals(aperture.toDouble(), lens.aperture)
            assertEquals(pitch.toDouble(), lens.pixelPitchMicrometers)
        }
        val edge = """{"id":"e","name":"n","widthMillimeters":1,"heightMillimeters":200}"""
        assertEquals(1.0, Json.decodeFromString(SensorFormat.serializer(), edge).widthMillimeters)
        assertEquals(200.0, Json.decodeFromString(SensorFormat.serializer(), edge).heightMillimeters)
        val maximumLabel = "x".repeat(128)
        val labels = """{"id":"$maximumLabel","name":"${"\u00E9".repeat(64)}","widthMillimeters":36,"heightMillimeters":24}"""
        assertNotNull(Json.decodeFromString(SensorFormat.serializer(), labels))
        val tooLong = """{"id":"a","name":"${"\u00E9".repeat(65)}","widthMillimeters":36,"heightMillimeters":24}"""
        assertFailsWith<SerializationException> { Json.decodeFromString(SensorFormat.serializer(), tooLong) }
    }

    @Serializable
    private data class Settings(val lens: LensSetup, val sensors: List<SensorFormat>, val point: String)

    @Test
    fun testNestedInsideAnotherSerializableClass() {
        val settings = Settings(
            lens = LensSetup(SensorFormat.microFourThirds, 300.0, 4.0, 3.7),
            sensors = SensorFormat.presets + SensorFormat.custom(20.5, 13.7),
            point = "x",
        )
        val text = Json.encodeToString(Settings.serializer(), settings)
        assertEquals(settings, Json.decodeFromString(Settings.serializer(), text))
        val tampered = text.replace("\"focalLengthMillimeters\":300.0", "\"focalLengthMillimeters\":3.0")
        assertFailsWith<SerializationException> { Json.decodeFromString(Settings.serializer(), tampered) }
    }

    @Test
    fun testDecodingFromAJsonTree() {
        val tree = buildJsonObject {
            put("id", JsonPrimitive("custom-1.0x2.0"))
            put("name", JsonPrimitive("Custom 1 \u00D7 2 mm"))
            put("widthMillimeters", JsonPrimitive(1))
            put("heightMillimeters", JsonPrimitive(2.0))
        }
        val sensor = Json.decodeFromJsonElement(SensorFormat.serializer(), tree)
        assertEquals(SensorFormat.custom(1.0, 2.0), sensor)
    }

    @Test
    fun testExposureRuleRawValues() {
        for (rule in ExposureRule.entries) {
            assertEquals(rule, ExposureRule.fromRawValue(rule.rawValue))
            assertEquals("\"${rule.rawValue}\"", Json.encodeToString(ExposureRule.serializer(), rule))
        }
        assertNull(ExposureRule.fromRawValue("rule600"))
    }
}

class PhotoToolsFactoryTests {

    @Test
    fun testLensSetupFactoryLimitsAndValidatedRejects() {
        val limited = LensSetup(SensorFormat.fullFrame, 1e9, -3.0, 1e9)
        assertEquals(2000.0, limited.focalLengthMillimeters)
        assertEquals(0.95, limited.aperture)
        assertNull(limited.pixelPitchMicrometers)
        assertNull(LensSetup.validated(SensorFormat.fullFrame, 1e9, -3.0, 1e9))
        assertNotNull(LensSetup.validated(SensorFormat.fullFrame, 50.0, 2.8))
    }

    @Test
    fun testCustomSensorIdentifiersFollowSwiftsDescriptionOfDouble() {
        assertEquals("custom-36.0x24.0", SensorFormat.custom(36.0, 24.0).id)
        assertEquals("custom-23.5x15.6", SensorFormat.custom(23.5, 15.6).id)
        assertEquals("custom-1.0x200.0", SensorFormat.custom(-5.0, 1e9).id)
        assertEquals("custom-36.0x24.0", SensorFormat.custom(Double.NaN, Double.POSITIVE_INFINITY).id)
        assertEquals("custom-17.299999999999997x13.0", SensorFormat.custom(17.299999999999997, 13.0).id)
        assertEquals("Custom 17.3 \u00D7 13 mm", SensorFormat.custom(17.299999999999997, 13.0).name)
    }

    @Test
    fun testFrameSizeWithNotANumberFollowsSwiftsMinAndMax() {
        // Swift's min(x, y) and max(x, y) return x when the comparison with y is false, so a NaN
        // in the second place is ignored while a NaN in the first place is kept.
        val nanSecond = FrameSize(horizontal = 10.0, vertical = Double.NaN, diagonal = 10.0)
        assertEquals(10.0, nanSecond.longSide)
        assertEquals(10.0, nanSecond.shortSide)
        assertTrue(FrameSize(horizontal = Double.NaN, vertical = 10.0, diagonal = 10.0).longSide.isNaN())
    }
}
