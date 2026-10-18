package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LX200 codec tests. Sources: Meade Telescope Serial Command Protocol, revision 2007.10 (17 October 2007), commands
 * `:GR#`, `:GD#`, `:Sr`, `:Sd`, `:MS#`, `:CM#`, `:Q#`, `:U#`, `:D#`, `:Gg#`, `:GG#`, `:SC`, `:SL`; OnStep 4.24 `Command.ino`
 * (`:GU#`, `:GW#`, `:MS#` digits 0 to 9, `:CM#` answers `N/A#` and `En#`, `:Sg`, `:SG`).
 */
class LX200CodecTests {
    private fun hms(hours: Int, minutes: Int, seconds: Double) = hours + minutes / 60.0 + seconds / 3600.0

    // MARK: Commands

    @Test
    fun testFixedCommandsMatchTheReference() {
        assertEquals(":GR#", LX200Codec.getRightAscension)
        assertEquals(":GD#", LX200Codec.getDeclination)
        assertEquals(":MS#", LX200Codec.slewToTarget)
        assertEquals(":CM#", LX200Codec.syncToTarget)
        assertEquals(":Q#", LX200Codec.halt)
        assertEquals(":U#", LX200Codec.togglePrecision)
        assertEquals(":D#", LX200Codec.getSlewDistance)
        assertEquals(":GVP#", LX200Codec.getProductName)
        assertEquals(":GVN#", LX200Codec.getFirmwareNumber)
        assertEquals(":GU#", LX200Codec.getStatus)
        assertEquals(":GW#", LX200Codec.getAlignmentStatus)
        assertEquals(":Te#", LX200Codec.trackingOn)
        assertEquals(":Td#", LX200Codec.trackingOff)
        assertEquals(0x15, LX200Codec.busyByte)
    }

    @Test
    fun testMovementCommands() {
        assertEquals(":Mn#", LX200Codec.move(MountNudgeDirection.NORTH))
        assertEquals(":Ms#", LX200Codec.move(MountNudgeDirection.SOUTH))
        assertEquals(":Me#", LX200Codec.move(MountNudgeDirection.EAST))
        assertEquals(":Mw#", LX200Codec.move(MountNudgeDirection.WEST))
        assertEquals(":Qn#", LX200Codec.halt(MountNudgeDirection.NORTH))
        assertEquals(":Qs#", LX200Codec.halt(MountNudgeDirection.SOUTH))
        assertEquals(":Qe#", LX200Codec.halt(MountNudgeDirection.EAST))
        assertEquals(":Qw#", LX200Codec.halt(MountNudgeDirection.WEST))
        assertEquals(":RG#", LX200Codec.setRate(MountNudgeRate.GUIDE))
        assertEquals(":RC#", LX200Codec.setRate(MountNudgeRate.CENTER))
        assertEquals(":RM#", LX200Codec.setRate(MountNudgeRate.FIND))
        assertEquals(":RS#", LX200Codec.setRate(MountNudgeRate.SLEW))
    }

    // MARK: Right ascension

    @Test
    fun testRightAscensionHighPrecisionEncoding() {
        assertEquals("00:00:00", LX200Codec.formatRightAscension(0.0, LX200Precision.HIGH))
        assertEquals("12:30:00", LX200Codec.formatRightAscension(12.5, LX200Precision.HIGH))
        assertEquals("05:35:00", LX200Codec.formatRightAscension(hms(5, 35, 0.0), LX200Precision.HIGH))
        // Vega, 18h 36m 56.3s, rounds to the nearest second.
        assertEquals("18:36:56", LX200Codec.formatRightAscension(hms(18, 36, 56.3), LX200Precision.HIGH))
        assertEquals("18:36:57", LX200Codec.formatRightAscension(hms(18, 36, 56.5), LX200Precision.HIGH))
        // 24 h wrap: rounding up to 24:00:00 gives 00:00:00, and out-of-range hours are wrapped.
        assertEquals("00:00:00", LX200Codec.formatRightAscension(23.9999, LX200Precision.HIGH))
        assertEquals("23:59:59", LX200Codec.formatRightAscension(hms(23, 59, 59.4), LX200Precision.HIGH))
        assertEquals("23:00:00", LX200Codec.formatRightAscension(-1.0, LX200Precision.HIGH))
        assertEquals("01:00:00", LX200Codec.formatRightAscension(25.0, LX200Precision.HIGH))
        assertEquals("00:00:00", LX200Codec.formatRightAscension(24.0, LX200Precision.HIGH))
    }

    @Test
    fun testRightAscensionLowPrecisionEncoding() {
        assertEquals("00:00.0", LX200Codec.formatRightAscension(0.0, LX200Precision.LOW))
        assertEquals("12:30.0", LX200Codec.formatRightAscension(12.5, LX200Precision.LOW))
        assertEquals("05:35.0", LX200Codec.formatRightAscension(hms(5, 35, 0.0), LX200Precision.LOW))
        assertEquals("00:06.0", LX200Codec.formatRightAscension(0.1, LX200Precision.LOW))
        assertEquals("18:36.9", LX200Codec.formatRightAscension(hms(18, 36, 56.3), LX200Precision.LOW))
        assertEquals("23:59.4", LX200Codec.formatRightAscension(23.99, LX200Precision.LOW))
        assertEquals("00:00.0", LX200Codec.formatRightAscension(23.9999, LX200Precision.LOW))
    }

    @Test
    fun testRightAscensionDecoding() {
        val high = LX200Codec.decodeRightAscension("12:34:56")
        assertEquals(hms(12, 34, 56.0), high.value, 1e-12)
        assertEquals(LX200Precision.HIGH, high.precision)
        val low = LX200Codec.decodeRightAscension("12:34.5")
        assertEquals(12.0 + 34.5 / 60, low.value, 1e-12)
        assertEquals(LX200Precision.LOW, low.precision)
        assertEquals(0.0, LX200Codec.decodeRightAscension("00:00:00").value, 0.0)
        assertEquals(hms(23, 59, 59.0), LX200Codec.decodeRightAscension("23:59:59").value, 1e-12)
        // 24:00:00 is the same point as 00:00:00.
        assertEquals(0.0, LX200Codec.decodeRightAscension("24:00:00").value, 0.0)
        assertEquals(0.0, LX200Codec.decodeRightAscension("24:00.0").value, 0.0)
        // OnStep's highest precision (":GRH#"): fractional seconds.
        val fine = LX200Codec.decodeRightAscension("05:34:31.9490")
        assertEquals(hms(5, 34, 31.949), fine.value, 1e-9)
        assertEquals(LX200Precision.HIGH, fine.precision)
        assertEquals(hms(5, 34, 31.9), LX200Codec.decodeRightAscension("05:34:31.9").value, 1e-9)
    }

    @Test
    fun testRightAscensionDecodingRejectsGarbage() {
        val bad = listOf(
            "", "#", "12", "12:3", "12:34", "12:34:", "12:34:5", "12:34:56.", "12:34:56.12345", "12:34.", "12:34.5x",
            "12:34.1234", "12:60:00", "12:34:60", "25:00:00", "24:00:01", "24:01.0", "ab:cd:ef", "12-34-56", "1:34:56",
            "12:34:56#", " 12:34:56", "-1:00:00", "12;34;56", "12:7x:00",
        )
        for (text in bad) {
            assertFailsWith<MountError.MalformedReply>(text) { LX200Codec.decodeRightAscension(text) }
        }
    }

    // MARK: Declination

    @Test
    fun testDeclinationHighPrecisionEncoding() {
        assertEquals("+00*00:00", LX200Codec.formatDeclination(0.0, LX200Precision.HIGH))
        assertEquals("+45*30:15", LX200Codec.formatDeclination(hms(45, 30, 15.0), LX200Precision.HIGH))
        assertEquals("-23*26:21", LX200Codec.formatDeclination(-hms(23, 26, 21.4), LX200Precision.HIGH))
        assertEquals("-00*30:00", LX200Codec.formatDeclination(-0.5, LX200Precision.HIGH))
        assertEquals("-90*00:00", LX200Codec.formatDeclination(-90.0, LX200Precision.HIGH))
        assertEquals("+90*00:00", LX200Codec.formatDeclination(90.0, LX200Precision.HIGH))
        // Out of range values are clamped, tiny negatives do not produce a negative zero, carries propagate.
        assertEquals("+90*00:00", LX200Codec.formatDeclination(90.5, LX200Precision.HIGH))
        assertEquals("-90*00:00", LX200Codec.formatDeclination(-120.0, LX200Precision.HIGH))
        assertEquals("+00*00:00", LX200Codec.formatDeclination(-0.0001, LX200Precision.HIGH))
        assertEquals("+30*00:00", LX200Codec.formatDeclination(29.9999999, LX200Precision.HIGH))
        assertEquals("-30*00:00", LX200Codec.formatDeclination(-29.9999999, LX200Precision.HIGH))
    }

    @Test
    fun testDeclinationLowPrecisionEncoding() {
        assertEquals("+00*00", LX200Codec.formatDeclination(0.0, LX200Precision.LOW))
        assertEquals("+45*30", LX200Codec.formatDeclination(45.5, LX200Precision.LOW))
        assertEquals("-00*15", LX200Codec.formatDeclination(-0.25, LX200Precision.LOW))
        assertEquals("-33*52", LX200Codec.formatDeclination(-33.8688, LX200Precision.LOW))
        assertEquals("+00*00", LX200Codec.formatDeclination(-0.001, LX200Precision.LOW))
        assertEquals("+30*00", LX200Codec.formatDeclination(29.9999, LX200Precision.LOW))
    }

    @Test
    fun testDeclinationDecoding() {
        val high = LX200Codec.decodeDeclination("+45*30:15")
        assertEquals(hms(45, 30, 15.0), high.value, 1e-12)
        assertEquals(LX200Precision.HIGH, high.precision)
        val low = LX200Codec.decodeDeclination("-00*30")
        assertEquals(-0.5, low.value, 1e-12)
        assertEquals(LX200Precision.LOW, low.precision)
        // Meade's document writes the seconds separator as an apostrophe: sDD*MM'SS.
        assertEquals(hms(45, 30, 15.0), LX200Codec.decodeDeclination("+45*30'15").value, 1e-12)
        // Some Meade scopes send their LCD's degree sign (byte 0xDF) or the ISO degree sign instead of '*'.
        assertEquals(hms(45, 30, 15.0), LX200Codec.decodeDeclination("+45ß30'15").value, 1e-12)
        assertEquals(-hms(12, 5, 0.0), LX200Codec.decodeDeclination("-12°05:00").value, 1e-12)
        assertEquals(-hms(23, 26, 21.448), LX200Codec.decodeDeclination("-23*26:21.448").value, 1e-9)
        // The sign is optional.
        assertEquals(45.5, LX200Codec.decodeDeclination("45*30").value, 1e-12)
        assertEquals(90.0, LX200Codec.decodeDeclination("+90*00:00").value, 0.0)
        assertEquals(-90.0, LX200Codec.decodeDeclination("-90*00").value, 0.0)
        assertEquals(0.0, LX200Codec.decodeDeclination("+00*00:00").value, 0.0)
    }

    @Test
    fun testDeclinationDecodingRejectsGarbage() {
        val bad = listOf(
            "", "+", "+4*30", "+45*3", "+45x30", "+45*30:", "+45*30:1", "+45*30:15x", "+45*30:15.", "+45*60", "+45*30:60",
            "+91*00", "+90*01", "+90*00:01", "-91*00:00", "++45*30", "+45*30#", "+4a*30", "abc", "+45*30:15.12345",
        )
        for (text in bad) {
            assertFailsWith<MountError.MalformedReply>(text) { LX200Codec.decodeDeclination(text) }
        }
    }

    @Test
    fun testPositionsSurviveEncodingAndDecoding() {
        var seed = 12_345L
        fun next(): Double {
            seed = (seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L)
            return ((seed ushr 11) and 0xFFFFFFFFL) / 4_294_967_296.0
        }
        repeat(500) {
            val hours = next() * 24
            val degrees = next() * 180 - 90
            for (precision in LX200Precision.entries) {
                val ra = LX200Codec.decodeRightAscension(LX200Codec.formatRightAscension(hours, precision))
                val tolerance = if (precision == LX200Precision.HIGH) 0.5 / 3600 else 0.05 / 60
                var difference = kotlin.math.abs(ra.value - hours)
                if (difference > 12) difference = 24 - difference
                assertTrue(difference <= tolerance + 1e-12, "$hours $precision -> ${ra.value}")
                assertEquals(precision, ra.precision)
                val dec = LX200Codec.decodeDeclination(LX200Codec.formatDeclination(degrees, precision))
                val decTolerance = if (precision == LX200Precision.HIGH) 0.5 / 3600 else 0.5 / 60
                assertTrue(kotlin.math.abs(dec.value - degrees) <= decTolerance + 1e-12, "$degrees $precision -> ${dec.value}")
            }
        }
    }

    @Test
    fun testTargetCommandsCombineTheFormats() {
        assertEquals(":Sr12:30:00#", LX200Codec.setTargetRightAscension(12.5, LX200Precision.HIGH))
        assertEquals(":Sr12:30.0#", LX200Codec.setTargetRightAscension(12.5, LX200Precision.LOW))
        assertEquals(":Sd+45*30:00#", LX200Codec.setTargetDeclination(45.5, LX200Precision.HIGH))
        assertEquals(":Sd-00*15#", LX200Codec.setTargetDeclination(-0.25, LX200Precision.LOW))
    }

    // MARK: Replies

    @Test
    fun testSlewRefusalDigits() {
        assertNull(LX200Codec.slewRefusal('0'))
        assertEquals(MountError.MountHorizonLimit, LX200Codec.slewRefusal('1'))
        assertEquals(MountError.MountOverheadLimit, LX200Codec.slewRefusal('2'))
        assertEquals(MountError.Standby, LX200Codec.slewRefusal('3'))
        assertEquals(MountError.Parked, LX200Codec.slewRefusal('4'))
        assertEquals(MountError.SlewInProgress, LX200Codec.slewRefusal('5'))
        assertEquals(MountError.OutsideMountLimits, LX200Codec.slewRefusal('6'))
        assertTrue(LX200Codec.slewRefusal('7') is MountError.HardwareFault)
        assertEquals(MountError.Busy, LX200Codec.slewRefusal('8'))
        assertTrue(LX200Codec.slewRefusal('9') is MountError.Rejected)
        for (bad in listOf('a', '#', ' ', 'E', '\u0015')) {
            assertFailsWith<MountError.MalformedReply> { LX200Codec.slewRefusal(bad) }
        }
    }

    @Test
    fun testDistanceBarsMeanSlewing() {
        assertFalse(LX200Codec.isSlewing(""))
        assertTrue(LX200Codec.isSlewing("\u007f"))
        assertTrue(LX200Codec.isSlewing("|||"))
    }

    @Test
    fun testOnStepStatusString() {
        val tracking = LX200Codec.decodeStatus("NpEo000")
        assertTrue(tracking.isTracking)
        assertFalse(tracking.isGotoActive)
        assertFalse(tracking.isParked)
        val idle = LX200Codec.decodeStatus("nNpEo000")
        assertFalse(idle.isTracking)
        val slewing = LX200Codec.decodeStatus("pEo000")
        assertTrue(slewing.isGotoActive)
        assertTrue(slewing.isTracking)
        val parked = LX200Codec.decodeStatus("nNPHEo000")
        assertTrue(parked.isParked)
        assertTrue(parked.isAtHome)
        assertTrue(LX200Codec.decodeStatus("nNIEo000").isParking)
        assertTrue(LX200Codec.decodeStatus("nNFEo000").parkFailed)
        for (bad in listOf("", "NEo000", "NpPEo", "N p", "\u0007pN", "N".repeat(41) + "p")) {
            assertFailsWith<MountError.MalformedReply>(bad) { LX200Codec.decodeStatus(bad) }
        }
    }

    @Test
    fun testAlignmentStatus() {
        val status = LX200Codec.decodeAlignmentStatus("GT2")
        assertEquals('G', status.mountType)
        assertTrue(status.isTracking)
        assertEquals(2, status.alignedStars)
        assertEquals(0, LX200Codec.decodeAlignmentStatus("AN0").alignedStars)
        assertFalse(LX200Codec.decodeAlignmentStatus("AN0").isTracking)
        for (bad in listOf("", "GT", "GT22", "XT2", "GX2", "GTa")) {
            assertFailsWith<MountError.MalformedReply>(bad) { LX200Codec.decodeAlignmentStatus(bad) }
        }
    }

    @Test
    fun testDialectDetection() {
        assertEquals(LX200Dialect.ONSTEP, LX200Codec.dialect("On-Step"))
        assertEquals(LX200Dialect.ONSTEP, LX200Codec.dialect("OnStep 4.24"))
        assertEquals(LX200Dialect.MEADE, LX200Codec.dialect("LX200 Classic"))
        assertEquals(LX200Dialect.MEADE, LX200Codec.dialect(""))
    }

    // MARK: Site and time

    @Test
    fun testLatitudeCommand() {
        assertEquals(":St+40*01#", LX200Codec.setLatitude(40.015))
        assertEquals(":St-33*52#", LX200Codec.setLatitude(-33.8688))
        assertEquals(":St+00*00#", LX200Codec.setLatitude(0.0))
        assertEquals(":St+00*00#", LX200Codec.setLatitude(-0.001))
        assertEquals(":St+90*00#", LX200Codec.setLatitude(91.0))
        assertEquals(":St+52*00#", LX200Codec.setLatitude(51.9999))
    }

    @Test
    fun testLongitudeCommandCountsWestwards() {
        // "East Longitudes are expressed as negative" (:Gg#), so 105.27° W is sent as 105*16 and 10° E as 350*00.
        assertEquals(":Sg105*16#", LX200Codec.setLongitude(-105.27))
        assertEquals(":Sg350*00#", LX200Codec.setLongitude(10.0))
        assertEquals(":Sg000*00#", LX200Codec.setLongitude(0.0))
        assertEquals(":Sg180*00#", LX200Codec.setLongitude(180.0))
        assertEquals(":Sg180*00#", LX200Codec.setLongitude(-180.0))
        assertEquals(":Sg000*00#", LX200Codec.setLongitude(0.0001))
        assertEquals(":Sg000*00#", LX200Codec.setLongitude(359.9999))
        assertEquals(":Sg350*00#", LX200Codec.setLongitude(370.0))
    }

    @Test
    fun testUTCOffsetCommandForBothDialects() {
        // ":GG#" is "the number of decimal hours to add to local time to convert it to UTC": west of Greenwich positive.
        assertEquals(":SG+00.0#", LX200Codec.setUTCOffset(0.0, LX200Dialect.MEADE))
        assertEquals(":SG-01.0#", LX200Codec.setUTCOffset(1.0, LX200Dialect.MEADE))
        assertEquals(":SG+05.0#", LX200Codec.setUTCOffset(-5.0, LX200Dialect.MEADE))
        assertEquals(":SG-05.5#", LX200Codec.setUTCOffset(5.5, LX200Dialect.MEADE))
        assertEquals(":SG+00#", LX200Codec.setUTCOffset(0.0, LX200Dialect.ONSTEP))
        assertEquals(":SG-01#", LX200Codec.setUTCOffset(1.0, LX200Dialect.ONSTEP))
        assertEquals(":SG+07#", LX200Codec.setUTCOffset(-7.0, LX200Dialect.ONSTEP))
        assertEquals(":SG-05:30#", LX200Codec.setUTCOffset(5.5, LX200Dialect.ONSTEP))
        assertEquals(":SG-05:45#", LX200Codec.setUTCOffset(5.75, LX200Dialect.ONSTEP))
        assertEquals(":SG+14#", LX200Codec.setUTCOffset(-20.0, LX200Dialect.ONSTEP))
    }

    @Test
    fun testDateAndTimeCommands() {
        val fields = assertNotNull(MountCalendar.fields(instantFromTimeIntervalSince1970(1_792_296_000.0)))
        assertEquals(":SC10/18/26#", LX200Codec.setDate(fields))
        assertEquals(":SL04:00:00#", LX200Codec.setTime(fields))
        val other = assertNotNull(MountCalendar.fields(instantFromTimeIntervalSince1970(951_782_399.0)))
        assertEquals(":SC02/28/00#", LX200Codec.setDate(other))
        assertEquals(":SL23:59:59#", LX200Codec.setTime(other))
    }

    @Test
    fun testNonFiniteNumbersNeverBreakTheFormatting() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals("00:00:00", LX200Codec.formatRightAscension(value, LX200Precision.HIGH))
            assertEquals("00:00.0", LX200Codec.formatRightAscension(value, LX200Precision.LOW))
            assertEquals("+00*00:00", LX200Codec.formatDeclination(value, LX200Precision.HIGH))
            assertEquals("+00*00", LX200Codec.formatDeclination(value, LX200Precision.LOW))
            assertEquals(":St+00*00#", LX200Codec.setLatitude(value))
            assertEquals(":Sg000*00#", LX200Codec.setLongitude(value))
            assertEquals(":SG+00.0#", LX200Codec.setUTCOffset(value, LX200Dialect.MEADE))
            assertEquals(":SG+00#", LX200Codec.setUTCOffset(value, LX200Dialect.ONSTEP))
        }
    }

    private fun <T : Any> assertNotNull(value: T?): T = kotlin.test.assertNotNull(value)
}
