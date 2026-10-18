package net.kuecker.lodestar.kit.horizon

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kuecker.lodestar.kit.horizon.HorizonTestSupport as Support
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HorizonProfileTests {
    private fun profile(altitudes: Map<Int, Double>): HorizonProfile {
        val values = MutableList(360) { 0.0 }
        for ((index, altitude) in altitudes) values[index] = altitude
        return assertNotNull(HorizonProfile.create(values))
    }

    private fun document(tenths: List<Int>, version: Int = 1): String =
        """{"version":$version,"altitudeTenths":[${tenths.joinToString(",")}]}"""

    /** The document the tests decode: tenths `(37 i) mod 701 − 100`, a spread over the whole allowed range. */
    private val patternTenths: List<Int> = List(360) { (it * 37) % 701 - 100 }

    // MARK: Altitude and interpolation

    @Test
    fun testFlatProfileIsFlatEverywhere() {
        val flat = HorizonProfile.flat
        assertEquals(360, flat.altitudes.size)
        assertTrue(flat.isFlat)
        assertEquals(0.0, flat.maximumAltitude)
        assertEquals(0.0, flat.minimumAltitude)
        for (azimuth in listOf(0.0, 0.3, 90.0, 179.99, 359.99, -45.0, 725.5)) {
            assertEquals(0.0, flat.altitude(azimuth))
        }
        assertEquals(HorizonProfile.flat, HorizonProfile.constant(0.0))
    }

    @Test
    fun testInterpolationIsLinearBetweenWholeDegrees() {
        val skyline = profile(mapOf(10 to 10.0, 11 to 20.0, 12 to 14.0))
        assertEquals(10.0, skyline.altitude(10.0), 1e-12)
        assertEquals(20.0, skyline.altitude(11.0), 1e-12)
        assertEquals(15.0, skyline.altitude(10.5), 1e-12)
        assertEquals(12.5, skyline.altitude(10.25), 1e-12)
        assertEquals(17.0, skyline.altitude(11.5), 1e-12)
        assertEquals(5.0, skyline.altitude(9.5), 1e-12) // halfway between node 9 (0°) and node 10 (10°)
        assertFalse(skyline.isFlat)
        assertEquals(20.0, skyline.maximumAltitude)
    }

    @Test
    fun testInterpolationWrapsThroughNorth() {
        val skyline = profile(mapOf(359 to 20.0, 0 to 10.0))
        assertEquals(15.0, skyline.altitude(359.5), 1e-12)
        assertEquals(15.0, skyline.altitude(-0.5), 1e-12)
        assertEquals(10.0, skyline.altitude(360.0), 1e-12)
        assertEquals(20.0, skyline.altitude(-1.0), 1e-12)
        assertEquals(17.5, skyline.altitude(359.25), 1e-12)
        assertEquals(15.0, skyline.altitude(719.5), 1e-12)
        // Node 358 is 0° and node 359 is 20°.
        assertEquals(10.0, skyline.altitude(358.5), 1e-12)
    }

    @Test
    fun testAzimuthsOutsideTheCircleAreWrapped() {
        val skyline = Support.truthProfile
        for (azimuth in listOf(12.34, 90.5, 199.99, 271.0)) {
            assertEquals(skyline.altitude(azimuth), skyline.altitude(azimuth + 360), 1e-9)
            assertEquals(skyline.altitude(azimuth), skyline.altitude(azimuth - 720), 1e-9)
        }
        // The value just below 360° and the one just below 0° are on the same side of north.
        assertEquals(skyline.altitude(359.999), skyline.altitude(-0.001), 1e-9)
    }

    @Test
    fun testNonFiniteAzimuthGivesZero() {
        val skyline = Support.truthProfile
        assertEquals(0.0, skyline.altitude(Double.NaN))
        assertEquals(0.0, skyline.altitude(Double.POSITIVE_INFINITY))
        assertEquals(0.0, skyline.altitude(Double.NEGATIVE_INFINITY))
    }

    // MARK: Creation and validation

    @Test
    fun testAltitudesAreRoundedToTenthsOfADegree() {
        val values = MutableList(360) { 0.0 }
        values[0] = 12.34
        values[1] = 12.36
        values[2] = -3.04
        values[3] = -0.04
        values[4] = 59.96
        val skyline = assertNotNull(HorizonProfile.create(values))
        assertEquals(12.3, skyline.altitudes[0])
        assertEquals(12.4, skyline.altitudes[1])
        assertEquals(-3.0, skyline.altitudes[2])
        assertEquals(0.0, skyline.altitudes[3])
        assertEquals(60.0, skyline.altitudes[4])
        // Zero never carries a sign, so equal profiles compare equal.
        assertEquals(Double.POSITIVE_INFINITY, 1.0 / skyline.altitudes[3])
        assertEquals(skyline, assertNotNull(HorizonProfile.create(skyline.altitudes)))
    }

    @Test
    fun testCreateValidatesLengthFiniteAndRange() {
        assertNull(HorizonProfile.create(emptyList()))
        assertNull(HorizonProfile.create(List(359) { 0.0 }))
        assertNull(HorizonProfile.create(List(361) { 0.0 }))
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 60.06, -10.06, 90.0, -90.0)) {
            val values = MutableList(360) { 1.0 }
            values[200] = bad
            assertNull(HorizonProfile.create(values), "$bad")
        }
        // The ends of the range are valid, and so are values that round into it.
        val edges = MutableList(360) { 0.0 }
        edges[0] = -10.0
        edges[1] = 60.0
        edges[2] = 60.04
        edges[3] = -10.04
        val skyline = assertNotNull(HorizonProfile.create(edges))
        assertEquals(60.0, skyline.maximumAltitude)
        assertEquals(-10.0, skyline.minimumAltitude)
    }

    @Test
    fun testMaximumMinimumAndFlatness() {
        val skyline = profile(mapOf(5 to 3.0, 200 to 45.5, 300 to -2.5))
        assertEquals(45.5, skyline.maximumAltitude)
        assertEquals(-2.5, skyline.minimumAltitude)
        assertFalse(skyline.isFlat)
        assertTrue(profile(emptyMap()).isFlat)
        assertEquals(2.0, Support.truthProfile.minimumAltitude)
        assertEquals(30.0, Support.truthProfile.maximumAltitude)
    }

    @Test
    fun testConstantProfileClampsAndHandlesNonFinite() {
        assertEquals(List(360) { 7.5 }, HorizonProfile.constant(7.5).altitudes)
        assertEquals(60.0, HorizonProfile.constant(80.0).minimumAltitude)
        assertEquals(-10.0, HorizonProfile.constant(-40.0).maximumAltitude)
        assertTrue(HorizonProfile.constant(Double.NaN).isFlat)
        assertEquals(5.0, HorizonProfile.constant(5.0).altitude(123.4))
    }

    @Test
    fun testEqualityFollowsTheAltitudes() {
        val first = profile(mapOf(10 to 5.0))
        val second = profile(mapOf(10 to 5.0))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, profile(mapOf(10 to 5.1)))
        assertNotEquals(first, HorizonProfile.flat)
    }

    // MARK: JSON

    @Test
    fun testJsonRoundTripIsExact() {
        for (skyline in listOf(HorizonProfile.flat, Support.truthProfile, HorizonProfile.constant(60.0),
            HorizonProfile.constant(-10.0), assertNotNull(HorizonProfile.create(patternTenths.map { it / 10.0 })))) {
            val text = Json.encodeToString(HorizonProfile.serializer(), skyline)
            val decoded = Json.decodeFromString(HorizonProfile.serializer(), text)
            assertEquals(skyline, decoded)
            assertEquals(text, Json.encodeToString(HorizonProfile.serializer(), decoded))
        }
    }

    @Test
    fun testJsonShapeAndSize() {
        val text = Json.encodeToString(HorizonProfile.serializer(), Support.truthProfile)
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("version", "altitudeTenths"), root.keys)
        assertEquals(1, root.getValue("version").jsonPrimitive.int)
        val tenths = root.getValue("altitudeTenths").jsonArray
        assertEquals(360, tenths.size)
        assertEquals(300, tenths[90].jsonPrimitive.int)
        assertEquals(20, tenths[0].jsonPrimitive.int)
        assertTrue(tenths.all { !it.jsonPrimitive.isString && it.jsonPrimitive.content.toIntOrNull() != null })
        assertTrue(text.length < 1_700, "a typical skyline is ${text.length} bytes")
        // The largest possible document is about 2 KB.
        for (extreme in listOf(HorizonProfile.constant(-10.0), HorizonProfile.constant(60.0))) {
            val size = Json.encodeToString(HorizonProfile.serializer(), extreme).length
            assertTrue(size < 2_000, "$size bytes")
        }
        assertTrue(Json.encodeToString(HorizonProfile.serializer(), HorizonProfile.flat).length < 800)
    }

    @Test
    fun testDecodingAFixedDocument() {
        val decoded = Json.decodeFromString(HorizonProfile.serializer(), document(patternTenths))
        for (index in 0 until 360) assertEquals(patternTenths[index] / 10.0, decoded.altitudes[index])
        assertEquals(60.0, decoded.maximumAltitude)
        assertEquals(-10.0, decoded.minimumAltitude)
        // Unknown keys are ignored, numbers written with a decimal point are accepted.
        val tolerant = """{"extra":[1,2],"version":1.0,"altitudeTenths":[${patternTenths.joinToString(",") { "$it.0" }}]}"""
        assertEquals(decoded, Json.decodeFromString(HorizonProfile.serializer(), tolerant))
    }

    @Test
    fun testDecodingRejectsInvalidDocuments() {
        val zeros = List(360) { 0 }
        fun fails(text: String) {
            assertFailsWith<SerializationException>(text.take(80)) { Json.decodeFromString(HorizonProfile.serializer(), text) }
        }
        fails(document(zeros, version = 2))
        fails(document(zeros, version = 0))
        fails(document(zeros.dropLast(1)))
        fails(document(zeros + 0))
        fails(document(emptyList()))
        fails(document(zeros.mapIndexed { index, value -> if (index == 7) 601 else value }))
        fails(document(zeros.mapIndexed { index, value -> if (index == 7) -101 else value }))
        fails("""{"version":1,"altitudeTenths":[${zeros.mapIndexed { index, value -> if (index == 7) "12.5" else "$value" }.joinToString(",")}]}""")
        fails("""{"version":1,"altitudeTenths":[${zeros.mapIndexed { index, value -> if (index == 7) "\"3\"" else "$value" }.joinToString(",")}]}""")
        fails("""{"version":1,"altitudeTenths":[${zeros.mapIndexed { index, value -> if (index == 7) "null" else "$value" }.joinToString(",")}]}""")
        fails("""{"altitudeTenths":[${zeros.joinToString(",")}]}""")
        fails("""{"version":1}""")
        fails("""{"version":"1","altitudeTenths":[${zeros.joinToString(",")}]}""")
        fails("""{"version":1,"altitudeTenths":"0,0,0"}""")
        fails("""[1,2,3]""")
        fails("""{"version":1,"altitudeTenths":[${zeros.mapIndexed { index, value -> if (index == 7) "1e400" else "$value" }.joinToString(",")}]}""")
    }

    // MARK: Editing

    @Test
    fun testRaisedAndLoweredRangeWrapsThroughNorth() {
        val base = HorizonProfile.constant(10.0)
        val raised = base.raised(5.0, AzimuthRange.between(350.0, 10.0))
        for (azimuth in listOf(350, 355, 359, 0, 5, 10)) assertEquals(15.0, raised.altitudes[azimuth], "$azimuth")
        for (azimuth in listOf(349, 11, 100, 200)) assertEquals(10.0, raised.altitudes[azimuth], "$azimuth")
        val lowered = raised.raised(-12.5, AzimuthRange(80.0, 20.0))
        for (azimuth in 80..100) assertEquals(-2.5, lowered.altitudes[azimuth], "$azimuth")
        assertEquals(10.0, lowered.altitudes[79])
        assertEquals(10.0, lowered.altitudes[101])
        assertEquals(lowered, base.raised(5.0, AzimuthRange.between(350.0, 10.0)).raised(-12.5, AzimuthRange(80.0, 20.0)))
        assertEquals(base, base.raised(Double.NaN, AzimuthRange.full))
        assertEquals(base, base.raised(3.0, AzimuthRange(40.0, 0.0)).raised(-3.0, AzimuthRange(40.0, 0.0)))
    }

    @Test
    fun testRaisedClampsToTheAllowedRange() {
        val base = HorizonProfile.constant(50.0)
        assertEquals(60.0, base.raised(30.0, AzimuthRange.full).minimumAltitude)
        assertEquals(-10.0, base.raised(-100.0, AzimuthRange.full).maximumAltitude)
        val half = base.raised(100.0, AzimuthRange(0.0, 180.0))
        assertEquals(60.0, half.altitude(90.0))
        assertEquals(50.0, half.altitude(270.0))
    }

    @Test
    fun testSettingReplacingAndMaximum() {
        val base = Support.truthProfile
        val wall = base.setting(25.0, AzimuthRange.between(250.0, 280.0))
        assertEquals(25.0, wall.altitude(250.0))
        assertEquals(25.0, wall.altitude(265.5))
        assertEquals(25.0, wall.altitude(280.0))
        assertEquals(base.altitude(249.0), wall.altitude(249.0))
        assertEquals(base.altitude(281.0), wall.altitude(281.0))
        assertEquals(base, base.setting(Double.NaN, AzimuthRange.full))
        assertEquals(HorizonProfile.constant(60.0), base.setting(90.0, AzimuthRange.full))

        // Replace one stretch by another profile's.
        val other = HorizonProfile.constant(20.0)
        val merged = base.replacing(AzimuthRange.between(100.0, 130.0), other)
        for (azimuth in 100..130) assertEquals(20.0, merged.altitudes[azimuth])
        assertEquals(base.altitudes[99], merged.altitudes[99])
        assertEquals(base.altitudes[131], merged.altitudes[131])

        // Both sets of obstacles.
        val higher = base.maximum(other)
        for (azimuth in 0 until 360) assertEquals(maxOf(base.altitudes[azimuth], 20.0), higher.altitudes[azimuth])
        assertEquals(higher, other.maximum(base))
        assertEquals(base, base.maximum(HorizonProfile.flat))
    }

    @Test
    fun testAzimuthRangeContainsAndWraps() {
        val through = AzimuthRange.between(350.0, 20.0)
        assertEquals(350.0, through.start)
        assertEquals(30.0, through.length, 1e-12)
        assertEquals(20.0, through.end, 1e-12)
        assertTrue(through.contains(350.0))
        assertTrue(through.contains(0.0))
        assertTrue(through.contains(20.0))
        assertTrue(through.contains(-5.0))
        assertTrue(through.contains(380.0))
        assertFalse(through.contains(20.5))
        assertFalse(through.contains(349.9))
        assertFalse(through.contains(Double.NaN))
        assertTrue(AzimuthRange.full.contains(123.0))
        assertEquals(360.0, AzimuthRange(-10.0, 500.0).length)
        assertEquals(350.0, AzimuthRange(-10.0, 5.0).start)
        assertEquals(0.0, AzimuthRange(Double.NaN, Double.NaN).length)
        assertEquals(0.0, AzimuthRange.between(40.0, 40.0).length)
        assertEquals(0.0, AzimuthRange.between(Double.NaN, 40.0).start)
        assertTrue(AzimuthRange.between(40.0, 40.0).contains(40.0))
        assertFalse(AzimuthRange.between(40.0, 40.0).contains(41.0))
    }

    // MARK: Drawing

    @Test
    fun testPolylineOfTheWholeCircle() {
        val skyline = Support.truthProfile
        val line = skyline.polyline()
        assertEquals(361, line.size)
        assertEquals(0.0, line.first().azimuth)
        assertEquals(360.0, line.last().azimuth)
        assertEquals(line.first().altitude, line.last().altitude)
        for (index in 0 until 360) {
            assertEquals(index.toDouble(), line[index].azimuth)
            assertEquals(skyline.altitudes[index], line[index].altitude)
        }
        // A coarser step keeps the ends and the multiples of the step.
        val coarse = skyline.polyline(step = 10.0)
        assertEquals(37, coarse.size)
        assertEquals(90.0, coarse[9].azimuth)
        assertEquals(30.0, coarse[9].altitude)
    }

    @Test
    fun testPolylineAcrossNorthKeepsIncreasingAzimuths() {
        val skyline = profile(mapOf(358 to 4.0, 359 to 6.0, 0 to 8.0, 1 to 10.0, 2 to 12.0))
        val line = skyline.polyline(from = 357.5, to = 362.25)
        assertEquals(listOf(357.5, 358.0, 359.0, 360.0, 361.0, 362.0, 362.25), line.map { it.azimuth })
        assertEquals(listOf(2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 9.0), line.map { it.altitude })
        assertTrue(line.zipWithNext().all { it.second.azimuth > it.first.azimuth })
        assertEquals(1, skyline.polyline(from = 10.0, to = 10.0).size)
    }

    @Test
    fun testPolylineStepAndInvalidInputs() {
        val skyline = Support.truthProfile
        assertTrue(skyline.polyline(from = 10.0, to = 5.0).isEmpty())
        assertTrue(skyline.polyline(from = Double.NaN, to = 5.0).isEmpty())
        assertTrue(skyline.polyline(from = 0.0, to = Double.POSITIVE_INFINITY).isEmpty())
        assertTrue(skyline.polyline(from = 1e300, to = 1e301).isEmpty())
        assertTrue(skyline.polyline(from = -2e9, to = 0.0).isEmpty())
        assertEquals(2, skyline.polyline(from = 1e9 - 1, to = 1e9).size)
        // Longer than a full circle: cut to 360°.
        val long = skyline.polyline(from = 100.0, to = 1000.0)
        assertEquals(100.0, long.first().azimuth)
        assertEquals(460.0, long.last().azimuth)
        // The step is limited to 0.1…45; garbage falls back to 1.
        assertEquals(3_601, skyline.polyline(step = 0.0001).size)
        assertEquals(9, skyline.polyline(step = 1000.0).size)
        assertEquals(361, skyline.polyline(step = Double.NaN).size)
    }
}
