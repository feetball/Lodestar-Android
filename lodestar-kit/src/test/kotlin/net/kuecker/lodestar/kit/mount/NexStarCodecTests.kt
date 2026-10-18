package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * NexStar / SynScan codec tests. Sources: "NexStar Communication Protocol" (Celestron; sha256 of the PDF copy
 * a97100496545aa1f96aeb1060bf0a8a45a1e12f418743a1708d9d48dad194dfe) and "SynScan Serial Communication Protocol" version 3.3
 * (Sky-Watcher, SSAP-F-160414V1-EN; sha256 bbdbb0c785f9b4e61fd1c1ff2e7adbe294ead97c9e98c3d07e55187ced03f33e).
 */
class NexStarCodecTests {
    private fun text(bytes: ByteArray): String = MountText.latin1(bytes)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    // MARK: Angles

    @Test
    fun testDocumentedPositionExampleDecodes() {
        // "If the Get RA/DEC command returns 34AB,12CE then the DEC value is 12CE … 4814/65536 = 0.07346 … 26.4441 degrees."
        val position = NexStarCodec.decodePosition("34AB,12CE")
        assertEquals(26.4441, position.declination, 0.00005)
        assertEquals(0x34AB / 65536.0 * 360.0, position.rightAscension, 1e-9)
        assertEquals(74.0643, position.rightAscension, 0.0001)
    }

    @Test
    fun testDocumentedPrecisePositionExampleDecodes() {
        // "If the precise GET AZM-ALT command returns 12AB0500,40000500 then the AZM value is 12AB0500 … 26.252 degrees."
        assertEquals(26.252, NexStarCodec.decodeAngle("12AB0500"), 0.0005)
        assertEquals(90.0, NexStarCodec.decodeAngle("40000500"), 0.0002)
        // The SynScan edition: 12AB05 in 24 bits is 1,223,429 / 16,777,216 × 360 and the last two digits are ignored.
        assertEquals(1_223_429.0 / 16_777_216.0 * 360.0, NexStarCodec.decodeAngle("12AB0500"), 1e-12)
        assertEquals(NexStarCodec.decodeAngle("12AB0500"), NexStarCodec.decodeAngle("12AB05FF"), 0.0)
    }

    @Test
    fun testGotoCommandsMatchTheDocumentedForms() {
        // "GOTO RA/DEC R34AB,12CE" and "GOTO precise RA/DEC r34AB0500,12CE0500".
        val standard = EquatorialCoordinate(0x34AB / 65536.0 * 360.0, 0x12CE / 65536.0 * 360.0)
        assertEquals("R34AB,12CE", text(NexStarCodec.goto(standard, precise = false)))
        assertEquals("S34AB,12CE", text(NexStarCodec.sync(standard, precise = false)))
        val precise = EquatorialCoordinate(0x34AB05 / 16_777_216.0 * 360.0, 0x12CE05 / 16_777_216.0 * 360.0)
        assertEquals("r34AB0500,12CE0500", text(NexStarCodec.goto(precise, precise = true)))
        assertEquals("s34AB0500,12CE0500", text(NexStarCodec.sync(precise, precise = true)))
        assertEquals("e", text(NexStarCodec.getPosition(true)))
        assertEquals("E", text(NexStarCodec.getPosition(false)))
    }

    @Test
    fun testNegativeDeclinationsAreEncodedAsFractionsOfAFullTurn() {
        // -10° is 350° of a revolution: 350/360 × 65536 = 63715.56 → F8E4; × 2^24 = 16311,… → F8E38E, then the unused byte.
        assertEquals("F8E4", NexStarCodec.encodeAngle(-10.0, precise = false))
        assertEquals("F8E38E00", NexStarCodec.encodeAngle(-10.0, precise = true))
        assertEquals("C000", NexStarCodec.encodeAngle(-90.0, precise = false))
        assertEquals("40000000", NexStarCodec.encodeAngle(90.0, precise = true))
        val southern = NexStarCodec.decodePosition("3B9B,F8E4")
        assertEquals(-10.0, southern.declination, 0.003)
        assertEquals(-90.0, NexStarCodec.decodePosition("0000,C000").declination, 1e-9)
        assertEquals(90.0, NexStarCodec.decodePosition("0000,4000").declination, 1e-9)
    }

    @Test
    fun testRightAscensionWrapsAtAFullTurn() {
        assertEquals("0000", NexStarCodec.encodeAngle(0.0, precise = false))
        assertEquals("0000", NexStarCodec.encodeAngle(360.0, precise = false))
        assertEquals("0000", NexStarCodec.encodeAngle(359.9999, precise = false))
        assertEquals("00000000", NexStarCodec.encodeAngle(359.99999999, precise = true))
        assertEquals("FFFFFB00", NexStarCodec.encodeAngle(359.9999, precise = true))
        assertEquals("8000", NexStarCodec.encodeAngle(180.0, precise = false))
        assertEquals("8000", NexStarCodec.encodeAngle(-180.0, precise = false))
        assertEquals("3B9B", NexStarCodec.encodeAngle(83.8221, precise = false))
        assertEquals("1D58C600", NexStarCodec.encodeAngle(41.2689, precise = true))
    }

    @Test
    fun testAnglesSurviveEncodingAndDecoding() {
        var seed = 99L
        fun next(): Double {
            seed = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return ((seed ushr 11) and 0xFFFFFFFFL) / 4_294_967_296.0
        }
        repeat(500) {
            val ra = next() * 360
            val dec = next() * 180 - 90
            for (precise in listOf(false, true)) {
                val tolerance = if (precise) 360.0 / 16_777_216 / 2 + 1e-9 else 360.0 / 65_536 / 2 + 1e-9
                val reply = NexStarCodec.encodeAngle(ra, precise) + "," + NexStarCodec.encodeAngle(dec, precise)
                val decoded = NexStarCodec.decodePosition(reply)
                var raDifference = kotlin.math.abs(decoded.rightAscension - ra)
                if (raDifference > 180) raDifference = 360 - raDifference
                assertTrue(raDifference <= tolerance, "$ra $precise -> ${decoded.rightAscension}")
                assertTrue(kotlin.math.abs(decoded.declination - dec) <= tolerance, "$dec $precise -> ${decoded.declination}")
            }
        }
    }

    @Test
    fun testGarbledPositionsAreRejected() {
        val bad = listOf(
            "", ",", "34AB", "34AB,", ",12CE", "34AB;12CE", "34AB,12C", "34AB,12CE0500", "34AB0500,12CE", "34AB,12CG",
            "34AB,12CE,", "34AB 12CE", "34AB0500,12CE0500,", "34AB,5000", "34AB,B000", "34AB,7FFF", "-34A,12CE", "34AB,12CE#",
        )
        for (reply in bad) {
            assertFailsWith<MountError.MalformedReply>(reply) { NexStarCodec.decodePosition(reply) }
        }
        // Lower case hexadecimal digits are accepted.
        assertEquals(26.4441, NexStarCodec.decodePosition("34ab,12ce").declination, 0.00005)
        assertFailsWith<MountError.MalformedReply> { NexStarCodec.decodeAngle("123") }
        assertFailsWith<MountError.MalformedReply> { NexStarCodec.decodeAngle("12345") }
    }

    @Test
    fun testNonFiniteAnglesAreHandled() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals("0000", NexStarCodec.encodeAngle(value, precise = false))
            assertEquals("00000000", NexStarCodec.encodeAngle(value, precise = true))
        }
    }

    // MARK: Commands

    @Test
    fun testSimpleCommands() {
        assertEquals("V", text(NexStarCodec.getVersion))
        assertEquals("m", text(NexStarCodec.getModel))
        assertEquals("J", text(NexStarCodec.getAlignmentComplete))
        assertEquals("L", text(NexStarCodec.getGotoInProgress))
        assertEquals("M", text(NexStarCodec.cancelGoto))
        assertEquals("t", text(NexStarCodec.getTrackingMode))
        assertContentEquals(bytes('T'.code, 0), NexStarCodec.setTrackingMode(NexStarTrackingMode.OFF))
        assertContentEquals(bytes('T'.code, 1), NexStarCodec.setTrackingMode(NexStarTrackingMode.ALT_AZ))
        assertContentEquals(bytes('T'.code, 2), NexStarCodec.setTrackingMode(NexStarTrackingMode.EQ_NORTH))
        assertContentEquals(bytes('T'.code, 3), NexStarCodec.setTrackingMode(NexStarTrackingMode.EQ_SOUTH))
        assertEquals(NexStarTrackingMode.EQ_NORTH, NexStarTrackingMode.fromCode(2))
        assertEquals(null, NexStarTrackingMode.fromCode(4))
    }

    @Test
    fun testVariableRateSlewMatchesTheDocumentedExample() {
        // "if the desired tracking rate is 150 arcseconds/second: trackRateHigh = (150 * 4) \ 256 = 2, trackRateLow = 600 mod 256 = 88"
        assertContentEquals(
            bytes('P'.code, 3, 16, 6, 2, 88, 0, 0),
            NexStarCodec.variableRateSlew(NexStarAxis.AZIMUTH_RIGHT_ASCENSION, positive = true, arcsecondsPerSecond = 150.0),
        )
        assertContentEquals(
            bytes('P'.code, 3, 16, 7, 2, 88, 0, 0),
            NexStarCodec.variableRateSlew(NexStarAxis.AZIMUTH_RIGHT_ASCENSION, positive = false, arcsecondsPerSecond = 150.0),
        )
        assertContentEquals(
            bytes('P'.code, 3, 17, 6, 0, 1, 0, 0),
            NexStarCodec.variableRateSlew(NexStarAxis.ALTITUDE_DECLINATION, positive = true, arcsecondsPerSecond = 0.25),
        )
        assertContentEquals(
            bytes('P'.code, 3, 17, 7, 255, 255, 0, 0),
            NexStarCodec.variableRateSlew(NexStarAxis.ALTITUDE_DECLINATION, positive = false, arcsecondsPerSecond = 16_383.75),
        )
        for (rate in listOf(-1.0, 16_383.76, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<MountError.InvalidInput>("$rate") {
                NexStarCodec.variableRateSlew(NexStarAxis.ALTITUDE_DECLINATION, true, rate)
            }
        }
    }

    @Test
    fun testFixedRateSlewAndDeviceVersionCommands() {
        assertContentEquals(
            bytes('P'.code, 2, 16, 36, 5, 0, 0, 0),
            NexStarCodec.fixedRateSlew(NexStarAxis.AZIMUTH_RIGHT_ASCENSION, positive = true, rate = 5),
        )
        assertContentEquals(
            bytes('P'.code, 2, 17, 37, 0, 0, 0, 0),
            NexStarCodec.fixedRateSlew(NexStarAxis.ALTITUDE_DECLINATION, positive = false, rate = 0),
        )
        assertFailsWith<MountError.InvalidInput> { NexStarCodec.fixedRateSlew(NexStarAxis.ALTITUDE_DECLINATION, true, 10) }
        assertFailsWith<MountError.InvalidInput> { NexStarCodec.fixedRateSlew(NexStarAxis.ALTITUDE_DECLINATION, true, -1) }
        assertContentEquals(bytes('P'.code, 1, 16, 254, 0, 0, 0, 2), NexStarCodec.getDeviceVersion(NexStarAxis.AZIMUTH_RIGHT_ASCENSION))
        assertContentEquals(bytes('P'.code, 1, 17, 254, 0, 0, 0, 2), NexStarCodec.getDeviceVersion(NexStarAxis.ALTITUDE_DECLINATION))
    }

    @Test
    fun testLocationCommandMatchesTheDocumentedExample() {
        // "to set the location to 118°20'17" W, 33°50'41" N … "W" & chr(33) & chr(50) & chr(41) & chr(0) & chr(118) & chr(20) & chr(17) & chr(1)"
        val location = GeographicLocation(33 + 50 / 60.0 + 41 / 3600.0, -(118 + 20 / 60.0 + 17 / 3600.0), 0.0)
        assertContentEquals(bytes('W'.code, 33, 50, 41, 0, 118, 20, 17, 1), NexStarCodec.setLocation(location))
        val southEast = GeographicLocation(-33.8688, 151.2093, 0.0)
        assertContentEquals(bytes('W'.code, 33, 52, 8, 1, 151, 12, 33, 0), NexStarCodec.setLocation(southEast))
        assertContentEquals(bytes('W'.code, 90, 0, 0, 0, 180, 0, 0, 0), NexStarCodec.setLocation(GeographicLocation(90.0, 180.0, 0.0)))
    }

    @Test
    fun testTimeCommandMatchesTheDocumentedExample() {
        // "to set the time to 3:26:00PM on April 6, 2005 in the Eastern time zone (-5 UTC: 256-5 = 251) … chr(15) chr(26) chr(0)
        // chr(4) chr(6) chr(5) chr(251) chr(1)"
        val date = MountDateTime(2005, 4, 6, 15, 26, 0, 0)
        assertContentEquals(bytes('H'.code, 15, 26, 0, 4, 6, 5, 251, 1), NexStarCodec.setTime(date, -5, true))
        assertContentEquals(bytes('H'.code, 15, 26, 0, 4, 6, 5, 0, 0), NexStarCodec.setTime(date, 0, false))
        assertContentEquals(bytes('H'.code, 15, 26, 0, 4, 6, 5, 14, 0), NexStarCodec.setTime(date, 14, false))
        assertFailsWith<MountError.InvalidInput> { NexStarCodec.setTime(date, 15, false) }
        assertFailsWith<MountError.InvalidInput> { NexStarCodec.setTime(date, -13, false) }
        assertFailsWith<MountError.InvalidInput> { NexStarCodec.setTime(date.copy(year = 1999), 0, false) }
        assertFailsWith<MountError.InvalidInput> { NexStarCodec.setTime(date.copy(year = 2100), 0, false) }
        val now = assertNotNull(MountCalendar.fields(instantFromTimeIntervalSince1970(1_792_296_000.0)))
        assertContentEquals(bytes('H'.code, 4, 0, 0, 10, 18, 26, 0, 0), NexStarCodec.setTime(now, 0, false))
    }

    // MARK: Replies

    @Test
    fun testVersionReplies() {
        val celestron = NexStarCodec.decodeVersion(bytes(5, 28))
        assertEquals(NexStarVersion(NexStarFlavor.CELESTRON, 5, 28, 0), celestron)
        assertEquals("5.28", celestron.description)
        // "if the version is 04.37.07, then the hand control responses '042507#'"
        val synScan = NexStarCodec.decodeVersion(MountText.ascii("042507"))
        assertEquals(NexStarVersion(NexStarFlavor.SYNSCAN, 4, 37, 7), synScan)
        assertEquals("4.37.07", synScan.description)
        assertEquals(NexStarVersion(NexStarFlavor.SYNSCAN, 3, 38, 0), NexStarCodec.decodeVersion(MountText.ascii("032600")))
        assertEquals(NexStarVersion(NexStarFlavor.SYNSCAN, 4, 38, 6), NexStarCodec.decodeVersion(MountText.ascii("042606")))
        for (bad in listOf(bytes(), bytes(4), bytes(4, 1, 2), MountText.ascii("04250G"), MountText.ascii("04250"), MountText.ascii("0425070"))) {
            assertFailsWith<MountError.MalformedReply> { NexStarCodec.decodeVersion(bad) }
        }
    }

    @Test
    fun testVersionComparison() {
        val version = NexStarVersion(NexStarFlavor.SYNSCAN, 4, 37, 3)
        assertTrue(version.isAtLeast(4, 37, 3))
        assertTrue(version.isAtLeast(4, 37))
        assertTrue(version.isAtLeast(3, 99))
        assertFalse(version.isAtLeast(4, 37, 4))
        assertFalse(version.isAtLeast(4, 38))
        assertFalse(version.isAtLeast(5, 0))
    }

    @Test
    fun testModelNames() {
        assertEquals("Celestron NexStar 6/8 SE", NexStarCodec.modelName(12, NexStarFlavor.CELESTRON))
        assertEquals("Celestron CPC", NexStarCodec.modelName(9, NexStarFlavor.CELESTRON))
        assertEquals("Celestron NexStar", NexStarCodec.modelName(99, NexStarFlavor.CELESTRON))
        assertEquals("Sky-Watcher EQ6 GOTO", NexStarCodec.modelName(0, NexStarFlavor.SYNSCAN))
        assertEquals("Sky-Watcher AZ GOTO", NexStarCodec.modelName(131, NexStarFlavor.SYNSCAN))
        assertEquals("Sky-Watcher Dobsonian GOTO", NexStarCodec.modelName(150, NexStarFlavor.SYNSCAN))
        assertEquals("Sky-Watcher AllView GOTO", NexStarCodec.modelName(160, NexStarFlavor.SYNSCAN))
        assertEquals("Sky-Watcher SynScan", NexStarCodec.modelName(77, NexStarFlavor.SYNSCAN))
    }
}
