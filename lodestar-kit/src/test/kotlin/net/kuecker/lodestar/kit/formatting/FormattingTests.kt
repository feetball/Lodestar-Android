package net.kuecker.lodestar.kit.formatting

import kotlinx.serialization.json.Json
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// U+2212 MINUS SIGN, written out so the tests cannot silently compare against a hyphen.
private const val minus = "\u2212"

private fun isASCII(text: String): Boolean = text.all { it.code < 128 }

private fun isNegativeZero(value: Double): Boolean = value == 0.0 && 1.0 / value < 0.0

/** Swift's `String.count`: the number of extended grapheme clusters. */
private fun graphemeCount(text: String): Int {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    var count = 0
    while (iterator.next() != BreakIterator.DONE) count += 1
    return count
}

/** Quotes a string for failure messages, like Swift's `debugDescription`. */
private fun quoted(text: String): String = "\"" + text + "\""

// MARK: - Formatting angles and coordinates

class AngleFormatterTests {

    // MARK: Right ascension

    @Test
    fun testRightAscensionOfOrionNebula() {
        val hours = 5.0 + 35.0 / 60 + 17.3 / 3600
        assertEquals("05h 35m 17.3s", AngleFormatter.formatRightAscension(hours = hours))
    }

    @Test
    fun testRightAscensionZeroAndWholeHours() {
        assertEquals("00h 00m 00.0s", AngleFormatter.formatRightAscension(hours = 0.0))
        assertEquals("00h 00m 00.0s", AngleFormatter.formatRightAscension(hours = -0.0))
        assertEquals("12h 30m 00.0s", AngleFormatter.formatRightAscension(hours = 12.5))
        assertEquals("23h 00m 00.0s", AngleFormatter.formatRightAscension(hours = 23.0))
    }

    @Test
    fun testRightAscensionSecondsDecimals() {
        val hours = 5.0 + 35.0 / 60 + 17.3 / 3600
        assertEquals("05h 35m 17s", AngleFormatter.formatRightAscension(hours = hours, secondsDecimals = 0))
        assertEquals("05h 35m 17.30s", AngleFormatter.formatRightAscension(hours = hours, secondsDecimals = 2))
        assertEquals("05h 35m 17.300s", AngleFormatter.formatRightAscension(hours = hours, secondsDecimals = 3))
    }

    @Test
    fun testRightAscensionDecimalsAreClamped() {
        val hours = 5.0 + 35.0 / 60 + 17.3 / 3600
        assertEquals("05h 35m 17s", AngleFormatter.formatRightAscension(hours = hours, secondsDecimals = -4))
        assertEquals("05h 35m 17.300000s", AngleFormatter.formatRightAscension(hours = hours, secondsDecimals = 99))
    }

    @Test
    fun testRightAscensionRoundingCarriesIntoMinutesAndHours() {
        // 59.96 s at one decimal rounds to 60.0 s, which must carry rather than print "60.0s".
        assertEquals("05h 36m 00.0s", AngleFormatter.formatRightAscension(hours = 5 + 35.0 / 60 + 59.96 / 3600))
        assertEquals("06h 00m 00.0s", AngleFormatter.formatRightAscension(hours = 5 + 59.0 / 60 + 59.96 / 3600))
        // Kept at two decimals the same value needs no carry.
        assertEquals(
            "05h 35m 59.96s",
            AngleFormatter.formatRightAscension(hours = 5 + 35.0 / 60 + 59.96 / 3600, secondsDecimals = 2),
        )
        // 59.96 s with no decimals rounds up as well.
        assertEquals(
            "05h 36m 00s",
            AngleFormatter.formatRightAscension(hours = 5 + 35.0 / 60 + 59.6 / 3600, secondsDecimals = 0),
        )
    }

    @Test
    fun testRightAscensionWrapsTwentyFourHoursToZero() {
        assertEquals("00h 00m 00.0s", AngleFormatter.formatRightAscension(hours = 23 + 59.0 / 60 + 59.99 / 3600))
        assertEquals("00h 00m 00.0s", AngleFormatter.formatRightAscension(hours = 23.99999999))
        assertEquals("23h 59m 59.6s", AngleFormatter.formatRightAscension(hours = 23.9999))
        assertEquals("00h 00m 00.0s", AngleFormatter.formatRightAscension(hours = 24.0))
        assertEquals("01h 30m 00.0s", AngleFormatter.formatRightAscension(hours = 25.5))
        assertEquals("00h 00m 00.0s", AngleFormatter.formatRightAscension(hours = 48.0))
    }

    @Test
    fun testRightAscensionNegativeValuesWrap() {
        assertEquals("23h 00m 00.0s", AngleFormatter.formatRightAscension(hours = -1.0))
        assertEquals("23h 30m 00.0s", AngleFormatter.formatRightAscension(hours = -0.5))
        // A negative value that rounds to a whole 24 hours must not print "24h".
        assertEquals("00h 00m 00.0s", AngleFormatter.formatRightAscension(hours = -1e-20))
    }

    @Test
    fun testRightAscensionNonFiniteAndHugeInput() {
        assertEquals("\u2014", AngleFormatter.formatRightAscension(hours = Double.NaN))
        assertEquals("\u2014", AngleFormatter.formatRightAscension(hours = Double.POSITIVE_INFINITY))
        assertEquals("\u2014", AngleFormatter.formatRightAscension(hours = Double.NEGATIVE_INFINITY))
        assertEquals("--", AngleFormatter.formatRightAscension(hours = Double.NaN, style = TypographyStyle.ASCII))
        val huge = AngleFormatter.formatRightAscension(hours = 1e300)
        assertTrue(huge.endsWith("s"))
        assertNotEquals("\u2014", huge)
    }

    // MARK: Declination

    @Test
    fun testDeclinationNegative() {
        val degrees = -(5.0 + 23.0 / 60 + 28.0 / 3600)
        assertEquals("${minus}05\u00B0 23\u2032 28\u2033", AngleFormatter.formatDeclination(degrees = degrees))
    }

    @Test
    fun testDeclinationPositiveHasPlusSign() {
        val degrees = 41.0 + 16.0 / 60 + 9.0 / 3600
        assertEquals("+41\u00B0 16\u2032 09\u2033", AngleFormatter.formatDeclination(degrees = degrees))
    }

    @Test
    fun testDeclinationZeroHasPlusSignEvenWhenNegativeZero() {
        assertEquals("+00\u00B0 00\u2032 00\u2033", AngleFormatter.formatDeclination(degrees = 0.0))
        assertEquals("+00\u00B0 00\u2032 00\u2033", AngleFormatter.formatDeclination(degrees = -0.0))
        // -0.36 arcsecond rounds to zero and must not read "-00° 00′ 00″".
        assertEquals("+00\u00B0 00\u2032 00\u2033", AngleFormatter.formatDeclination(degrees = -0.0001))
    }

    @Test
    fun testDeclinationKeepsSignWhenDegreesAreZero() {
        assertEquals("${minus}00\u00B0 30\u2032 00\u2033", AngleFormatter.formatDeclination(degrees = -0.5))
        assertEquals("+00\u00B0 30\u2032 00\u2033", AngleFormatter.formatDeclination(degrees = 0.5))
        assertEquals("${minus}00\u00B0 00\u2032 01\u2033", AngleFormatter.formatDeclination(degrees = -1.0 / 3600))
    }

    @Test
    fun testDeclinationSecondsDecimals() {
        val degrees = -(5.0 + 23.0 / 60 + 28.0 / 3600)
        assertEquals(
            "${minus}05\u00B0 23\u2032 28.0\u2033",
            AngleFormatter.formatDeclination(degrees = degrees, secondsDecimals = 1),
        )
        assertEquals(
            "+12\u00B0 20\u2032 44.44\u2033",
            AngleFormatter.formatDeclination(degrees = 12.345678, secondsDecimals = 2),
        )
    }

    @Test
    fun testDeclinationRoundingCarries() {
        // 59.6 arcseconds round up to the next arcminute, and 59′ 59.6″ up to the next degree.
        assertEquals(
            "+11\u00B0 00\u2032 00\u2033",
            AngleFormatter.formatDeclination(degrees = 10 + 59.0 / 60 + 59.6 / 3600),
        )
        assertEquals(
            "+10\u00B0 21\u2032 00\u2033",
            AngleFormatter.formatDeclination(degrees = 10 + 20.0 / 60 + 59.6 / 3600),
        )
        assertEquals(
            "${minus}11\u00B0 00\u2032 00.0\u2033",
            AngleFormatter.formatDeclination(degrees = -(10 + 59.0 / 60 + 59.96 / 3600), secondsDecimals = 1),
        )
    }

    @Test
    fun testDeclinationPolesAndClamping() {
        assertEquals("+90\u00B0 00\u2032 00\u2033", AngleFormatter.formatDeclination(degrees = 90.0))
        assertEquals("${minus}90\u00B0 00\u2032 00\u2033", AngleFormatter.formatDeclination(degrees = -90.0))
        assertEquals("+90\u00B0 00\u2032 00\u2033", AngleFormatter.formatDeclination(degrees = 89.99999))
        assertEquals("+90\u00B0 00\u2032 00\u2033", AngleFormatter.formatDeclination(degrees = 91.0))
        assertEquals("${minus}90\u00B0 00\u2032 00\u2033", AngleFormatter.formatDeclination(degrees = -200.0))
    }

    @Test
    fun testDeclinationNonFinite() {
        assertEquals("\u2014", AngleFormatter.formatDeclination(degrees = Double.NaN))
        assertEquals("\u2014", AngleFormatter.formatDeclination(degrees = Double.POSITIVE_INFINITY))
        assertEquals("--", AngleFormatter.formatDeclination(degrees = Double.NEGATIVE_INFINITY, style = TypographyStyle.ASCII))
    }

    @Test
    fun testDeclinationAsciiStyle() {
        val degrees = -(5.0 + 23.0 / 60 + 28.0 / 3600)
        val text = AngleFormatter.formatDeclination(degrees = degrees, style = TypographyStyle.ASCII)
        assertEquals("-05d 23' 28\"", text)
        assertTrue(isASCII(text))
        assertEquals("+41d 16' 12\"", AngleFormatter.formatDeclination(degrees = 41.27, style = TypographyStyle.ASCII))
    }

    // MARK: Decimal degrees

    @Test
    fun testFormatDegrees() {
        assertEquals("123.4\u00B0", AngleFormatter.formatDegrees(123.4))
        assertEquals("0.0\u00B0", AngleFormatter.formatDegrees(0.0))
        assertEquals("${minus}12.3\u00B0", AngleFormatter.formatDegrees(-12.34))
        assertEquals("45.68\u00B0", AngleFormatter.formatDegrees(45.678, decimals = 2))
        assertEquals("124\u00B0", AngleFormatter.formatDegrees(123.5, decimals = 0))
        assertEquals("${minus}124\u00B0", AngleFormatter.formatDegrees(-123.5, decimals = 0))
    }

    @Test
    fun testFormatDegreesDoesNotWrap() {
        assertEquals("360.0\u00B0", AngleFormatter.formatDegrees(359.96))
        assertEquals("720.0\u00B0", AngleFormatter.formatDegrees(720.0))
    }

    @Test
    fun testFormatDegreesNeverPrintsNegativeZero() {
        assertEquals("0.0\u00B0", AngleFormatter.formatDegrees(-0.0))
        assertEquals("0.0\u00B0", AngleFormatter.formatDegrees(-0.04))
        assertEquals("${minus}0.1\u00B0", AngleFormatter.formatDegrees(-0.05))
    }

    @Test
    fun testFormatDegreesNonFiniteAndHuge() {
        assertEquals("\u2014", AngleFormatter.formatDegrees(Double.NaN))
        assertEquals("\u2014", AngleFormatter.formatDegrees(Double.POSITIVE_INFINITY))
        assertEquals("\u2014", AngleFormatter.formatDegrees(1e300))
        assertEquals("--", AngleFormatter.formatDegrees(Double.NaN, style = TypographyStyle.ASCII))
    }

    @Test
    fun testFormatDegreesAsciiStyle() {
        assertEquals("123.4d", AngleFormatter.formatDegrees(123.4, style = TypographyStyle.ASCII))
        assertEquals("-12.3d", AngleFormatter.formatDegrees(-12.34, style = TypographyStyle.ASCII))
    }

    @Test
    fun testFormatSignedDegrees() {
        assertEquals("+45.3\u00B0", AngleFormatter.formatSignedDegrees(45.34))
        assertEquals("${minus}5.2\u00B0", AngleFormatter.formatSignedDegrees(-5.2))
        assertEquals("+0.0\u00B0", AngleFormatter.formatSignedDegrees(0.0))
        assertEquals("+0.0\u00B0", AngleFormatter.formatSignedDegrees(-0.04))
        assertEquals("${minus}90\u00B0", AngleFormatter.formatSignedDegrees(-90.0, decimals = 0))
        assertEquals("\u2014", AngleFormatter.formatSignedDegrees(Double.NaN))
        assertEquals("-5.2d", AngleFormatter.formatSignedDegrees(-5.2, style = TypographyStyle.ASCII))
    }

    @Test
    fun testFormatAzimuthWrapsAfterRounding() {
        assertEquals("123.4\u00B0", AngleFormatter.formatAzimuth(123.4))
        assertEquals("0.0\u00B0", AngleFormatter.formatAzimuth(0.0))
        assertEquals("359.9\u00B0", AngleFormatter.formatAzimuth(359.94))
        assertEquals("0.0\u00B0", AngleFormatter.formatAzimuth(359.96))
        assertEquals("0.0\u00B0", AngleFormatter.formatAzimuth(360.0))
        assertEquals("350.0\u00B0", AngleFormatter.formatAzimuth(-10.0))
        assertEquals("0.5\u00B0", AngleFormatter.formatAzimuth(720.5))
        assertEquals("0.0\u00B0", AngleFormatter.formatAzimuth(-1e-20))
        assertEquals("0\u00B0", AngleFormatter.formatAzimuth(359.5, decimals = 0))
        assertEquals("\u2014", AngleFormatter.formatAzimuth(Double.NaN))
        assertEquals("123.4d", AngleFormatter.formatAzimuth(123.4, style = TypographyStyle.ASCII))
    }

    @Test
    fun testFormatDMS() {
        assertEquals("123\u00B0 27\u2032 24\u2033", AngleFormatter.formatDMS(123.4567))
        assertEquals("123\u00B0 27\u2032 24.12\u2033", AngleFormatter.formatDMS(123.4567, secondsDecimals = 2))
        assertEquals("0\u00B0 00\u2032 00\u2033", AngleFormatter.formatDMS(0.0))
        assertEquals("5\u00B0 03\u2032 08\u2033", AngleFormatter.formatDMS(5 + 3.0 / 60 + 8.0 / 3600))
        assertEquals("${minus}5\u00B0 30\u2032 00\u2033", AngleFormatter.formatDMS(-5.5))
        assertEquals("${minus}0\u00B0 30\u2032 00\u2033", AngleFormatter.formatDMS(-0.5))
    }

    @Test
    fun testFormatDMSCarriesAndSigns() {
        assertEquals("360\u00B0 00\u2032 00\u2033", AngleFormatter.formatDMS(359.99999))
        assertEquals("11\u00B0 00\u2032 00\u2033", AngleFormatter.formatDMS(10 + 59.0 / 60 + 59.6 / 3600))
        assertEquals("0\u00B0 00\u2032 00\u2033", AngleFormatter.formatDMS(-0.0))
        assertEquals("0\u00B0 00\u2032 00\u2033", AngleFormatter.formatDMS(-0.0001))
        assertEquals("\u2014", AngleFormatter.formatDMS(Double.NaN))
        assertEquals("\u2014", AngleFormatter.formatDMS(1e300))
        assertEquals("-5d 30' 00\"", AngleFormatter.formatDMS(-5.5, style = TypographyStyle.ASCII))
    }

    // MARK: Latitude and longitude

    @Test
    fun testLatitude() {
        assertEquals("40.0150\u00B0 N", AngleFormatter.formatLatitude(40.015))
        assertEquals("33.8688\u00B0 S", AngleFormatter.formatLatitude(-33.8688))
        assertEquals("40.01\u00B0 N", AngleFormatter.formatLatitude(40.0149, decimals = 2))
        assertEquals("41\u00B0 N", AngleFormatter.formatLatitude(40.5, decimals = 0))
    }

    @Test
    fun testLatitudeEdges() {
        assertEquals("0.0000\u00B0 N", AngleFormatter.formatLatitude(0.0))
        assertEquals("0.0000\u00B0 N", AngleFormatter.formatLatitude(-0.0))
        assertEquals("0.0000\u00B0 N", AngleFormatter.formatLatitude(-0.00001))
        assertEquals("90.0000\u00B0 N", AngleFormatter.formatLatitude(90.0))
        assertEquals("90.0000\u00B0 S", AngleFormatter.formatLatitude(-90.0))
        assertEquals("90.0000\u00B0 N", AngleFormatter.formatLatitude(95.0))
        assertEquals("90.0000\u00B0 S", AngleFormatter.formatLatitude(-95.0))
        assertEquals("\u2014", AngleFormatter.formatLatitude(Double.NaN))
        assertEquals("40.0150d N", AngleFormatter.formatLatitude(40.015, style = TypographyStyle.ASCII))
    }

    @Test
    fun testLongitude() {
        assertEquals("105.2705\u00B0 W", AngleFormatter.formatLongitude(-105.2705))
        assertEquals("2.3522\u00B0 E", AngleFormatter.formatLongitude(2.3522))
        assertEquals("151.2\u00B0 E", AngleFormatter.formatLongitude(151.2093, decimals = 1))
    }

    @Test
    fun testLongitudeWrapsIntoHalfOpenRange() {
        assertEquals("0.0000\u00B0 E", AngleFormatter.formatLongitude(0.0))
        assertEquals("0.0000\u00B0 E", AngleFormatter.formatLongitude(-0.00001))
        assertEquals("180.0000\u00B0 E", AngleFormatter.formatLongitude(180.0))
        assertEquals("180.0000\u00B0 E", AngleFormatter.formatLongitude(-180.0))
        assertEquals("170.0000\u00B0 W", AngleFormatter.formatLongitude(190.0))
        assertEquals("170.0000\u00B0 E", AngleFormatter.formatLongitude(-190.0))
        assertEquals("0.0000\u00B0 E", AngleFormatter.formatLongitude(360.0))
        assertEquals("180.0000\u00B0 E", AngleFormatter.formatLongitude(540.0))
        assertEquals("180.0000\u00B0 E", AngleFormatter.formatLongitude(-540.0))
    }

    @Test
    fun testLongitudeAntimeridianRoundingIsEast() {
        // -179.99999 rounds to 180.0000, which is written as east like 180 itself.
        assertEquals("180.0000\u00B0 E", AngleFormatter.formatLongitude(-179.99999))
        assertEquals("179.9999\u00B0 W", AngleFormatter.formatLongitude(-179.9999))
    }

    @Test
    fun testLongitudeNonFinite() {
        assertEquals("\u2014", AngleFormatter.formatLongitude(Double.NaN))
        assertEquals("--", AngleFormatter.formatLongitude(Double.POSITIVE_INFINITY, style = TypographyStyle.ASCII))
        assertEquals("105.2705d W", AngleFormatter.formatLongitude(-105.2705, style = TypographyStyle.ASCII))
    }

    @Test
    fun testLocaleDoesNotAffectOutput() {
        // Output is built from integers, so the decimal mark is always a period.
        assertEquals("1234.5\u00B0", AngleFormatter.formatDegrees(1234.5))
        assertFalse(AngleFormatter.formatLatitude(40.015).contains(","))
    }

    @Test
    fun testCompassPointForwarder() {
        assertEquals(CompassPoint.EAST, AngleFormatter.compassPoint(azimuth = 90.0))
        assertEquals(CompassPoint.SOUTH_SOUTHWEST, AngleFormatter.compassPoint(azimuth = 200.0))
    }

    @Test
    fun testTypographyStyleCases() {
        assertEquals(listOf(TypographyStyle.UNICODE, TypographyStyle.ASCII), TypographyStyle.entries)
    }

    // MARK: Number helpers

    @Test
    fun testNumberTextHelpers() {
        assertEquals(0, NumberText.clampedDecimals(-3))
        assertEquals(4, NumberText.clampedDecimals(4))
        assertEquals(6, NumberText.clampedDecimals(60))
        assertEquals(1L, NumberText.powerOfTen(0))
        assertEquals(1_000_000L, NumberText.powerOfTen(6))
        assertEquals(1L, NumberText.powerOfTen(-2))
        assertEquals("07", NumberText.padded(7, width = 2))
        assertEquals("123", NumberText.padded(123, width = 2))
        assertEquals("2.5", NumberText.trimmed(magnitude = 2.5, maxDecimals = 2))
        assertEquals("13", NumberText.trimmed(magnitude = 13.0, maxDecimals = 2))
        assertEquals("100", NumberText.trimmed(magnitude = 100.0, maxDecimals = 1))
        assertEquals("0", NumberText.trimmed(magnitude = 0.0, maxDecimals = 2))
        assertEquals("0.25", NumberText.trimmed(magnitude = 0.25, maxDecimals = 2))
        assertEquals("10", NumberText.trimmed(magnitude = 9.96, maxDecimals = 1))
        assertNull(NumberText.trimmed(magnitude = -1.0, maxDecimals = 1))
        assertNull(NumberText.trimmed(magnitude = Double.NaN, maxDecimals = 1))
        assertNull(NumberText.fixedPoint(magnitude = 1e300, decimals = 6))
        assertNull(NumberText.sexagesimal(magnitude = 1e300, secondsDecimals = 6))
        assertNull(NumberText.sexagesimal(magnitude = -1.0, secondsDecimals = 0))
    }
}

// MARK: - Compass points

class CompassPointTests {

    @Test
    fun testSixteenPointsInCompassOrder() {
        val expected = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        assertEquals(expected, CompassPoint.entries.map { it.abbreviation })
        assertEquals((0 until 16).toList(), CompassPoint.entries.map { it.rawValue })
    }

    @Test
    fun testFullNames() {
        val expected = listOf(
            "North", "North-northeast", "Northeast", "East-northeast",
            "East", "East-southeast", "Southeast", "South-southeast",
            "South", "South-southwest", "Southwest", "West-southwest",
            "West", "West-northwest", "Northwest", "North-northwest",
        )
        assertEquals(expected, CompassPoint.entries.map { it.fullName })
    }

    @Test
    fun testCenterAzimuthsAreEvenlySpaced() {
        for ((index, point) in CompassPoint.entries.withIndex()) {
            assertEquals(index.toDouble() * 22.5, point.centerAzimuth)
        }
    }

    @Test
    fun testEveryCenterMapsToItself() {
        for (point in CompassPoint.entries) {
            assertEquals(point, CompassPoint(azimuth = point.centerAzimuth))
            assertEquals(point, AngleFormatter.compassPoint(azimuth = point.centerAzimuth))
        }
    }

    @Test
    fun testBoundariesBelongToTheClockwiseSector() {
        // Boundaries lie at 11.25° + k × 22.5°. They are exact in binary floating point, and
        // each sector includes its lower boundary.
        for (sector in 0 until 16) {
            val boundary = 11.25 + sector.toDouble() * 22.5
            val next = CompassPoint.entries[(sector + 1) % 16]
            val previous = CompassPoint.entries[sector]
            assertEquals(next, CompassPoint(azimuth = boundary), "azimuth $boundary")
            assertEquals(previous, CompassPoint(azimuth = boundary.nextDown()), "azimuth just below $boundary")
            assertEquals(next, CompassPoint(azimuth = boundary.nextUp()), "azimuth just above $boundary")
        }
    }

    @Test
    fun testNorthSpansTheWrapAround() {
        assertEquals(CompassPoint.NORTH, CompassPoint(azimuth = 0.0))
        assertEquals(CompassPoint.NORTH, CompassPoint(azimuth = 11.2499))
        assertEquals(CompassPoint.NORTH_NORTHEAST, CompassPoint(azimuth = 11.25))
        assertEquals(CompassPoint.NORTH_NORTHWEST, CompassPoint(azimuth = 348.7499))
        assertEquals(CompassPoint.NORTH, CompassPoint(azimuth = 348.75))
        assertEquals(CompassPoint.NORTH, CompassPoint(azimuth = 359.999))
        assertEquals(CompassPoint.NORTH, CompassPoint(azimuth = 360.0))
    }

    @Test
    fun testAzimuthsAreWrapped() {
        assertEquals(CompassPoint.WEST, CompassPoint(azimuth = -90.0))
        assertEquals(CompassPoint.NORTH, CompassPoint(azimuth = -11.25))
        assertEquals(CompassPoint.NORTH_NORTHWEST, CompassPoint(azimuth = -11.2501))
        assertEquals(CompassPoint.EAST, CompassPoint(azimuth = 450.0))
        assertEquals(CompassPoint.NORTH, CompassPoint(azimuth = 720.0))
        assertEquals(CompassPoint.NORTH_NORTHEAST, CompassPoint(azimuth = 371.25))
        assertEquals(CompassPoint.NORTH, CompassPoint(azimuth = -1e-20))
        assertEquals(CompassPoint.NORTH, CompassPoint(azimuth = -360.0))
        assertTrue(CompassPoint.entries.contains(CompassPoint(azimuth = 1e300)))
    }

    @Test
    fun testNonFiniteAzimuthFallsBackToNorth() {
        assertEquals(CompassPoint.NORTH, CompassPoint(azimuth = Double.NaN))
        assertEquals(CompassPoint.NORTH, CompassPoint(azimuth = Double.POSITIVE_INFINITY))
        assertEquals(CompassPoint.NORTH, CompassPoint(azimuth = Double.NEGATIVE_INFINITY))
    }

    @Test
    fun testCardinalAndIntercardinalPoints() {
        assertEquals(CompassPoint.NORTHEAST, CompassPoint(azimuth = 45.0))
        assertEquals(CompassPoint.EAST, CompassPoint(azimuth = 90.0))
        assertEquals(CompassPoint.SOUTHEAST, CompassPoint(azimuth = 135.0))
        assertEquals(CompassPoint.SOUTH, CompassPoint(azimuth = 180.0))
        assertEquals(CompassPoint.SOUTHWEST, CompassPoint(azimuth = 225.0))
        assertEquals(CompassPoint.WEST, CompassPoint(azimuth = 270.0))
        assertEquals(CompassPoint.NORTHWEST, CompassPoint(azimuth = 315.0))
    }

    @Test
    fun testCodableRoundTrip() {
        val data = Json.encodeToString(CompassPoint.serializer(), CompassPoint.WEST_SOUTHWEST)
        assertEquals(CompassPoint.WEST_SOUTHWEST, Json.decodeFromString(CompassPoint.serializer(), data))
    }
}

// MARK: - Durations and exposure times

class DurationFormattingTests {

    @Test
    fun testDurationUnits() {
        assertEquals("0s", AngleFormatter.formatDuration(seconds = 0.0))
        assertEquals("12s", AngleFormatter.formatDuration(seconds = 12.0))
        assertEquals("59s", AngleFormatter.formatDuration(seconds = 59.0))
        assertEquals("1m 00s", AngleFormatter.formatDuration(seconds = 60.0))
        assertEquals("1m 05s", AngleFormatter.formatDuration(seconds = 65.0))
        assertEquals("4m 30s", AngleFormatter.formatDuration(seconds = 270.0))
        assertEquals("59m 59s", AngleFormatter.formatDuration(seconds = 3599.0))
        assertEquals("1h 00m", AngleFormatter.formatDuration(seconds = 3600.0))
        assertEquals("1h 05m", AngleFormatter.formatDuration(seconds = 3900.0))
        assertEquals("9h 17m", AngleFormatter.formatDuration(seconds = (9 * 3600 + 17 * 60).toDouble()))
        assertEquals("25h 00m", AngleFormatter.formatDuration(seconds = (25 * 3600).toDouble()))
    }

    @Test
    fun testDurationRoundingCarries() {
        assertEquals("12s", AngleFormatter.formatDuration(seconds = 11.6))
        assertEquals("59s", AngleFormatter.formatDuration(seconds = 59.4))
        assertEquals("1m 00s", AngleFormatter.formatDuration(seconds = 59.6))
        assertEquals("2m 00s", AngleFormatter.formatDuration(seconds = 119.5))
        assertEquals("59m 59s", AngleFormatter.formatDuration(seconds = 3599.4))
        assertEquals("1h 00m", AngleFormatter.formatDuration(seconds = 3599.6))
        // Above an hour seconds are dropped by rounding to the nearest minute.
        assertEquals("1h 00m", AngleFormatter.formatDuration(seconds = 3629.0))
        assertEquals("1h 01m", AngleFormatter.formatDuration(seconds = 3631.0))
        assertEquals("1h 59m", AngleFormatter.formatDuration(seconds = (7200 - 31).toDouble()))
        assertEquals("2h 00m", AngleFormatter.formatDuration(seconds = (7200 - 29).toDouble()))
    }

    @Test
    fun testNegativeDurations() {
        assertEquals("${minus}4m 30s", AngleFormatter.formatDuration(seconds = -270.0))
        assertEquals("${minus}1h 05m", AngleFormatter.formatDuration(seconds = -3900.0))
        assertEquals("${minus}12s", AngleFormatter.formatDuration(seconds = -12.0))
        assertEquals("0s", AngleFormatter.formatDuration(seconds = -0.2))
        assertEquals("0s", AngleFormatter.formatDuration(seconds = -0.0))
        assertEquals("-4m 30s", AngleFormatter.formatDuration(seconds = -270.0, style = TypographyStyle.ASCII))
    }

    @Test
    fun testDurationNonFiniteAndHuge() {
        assertEquals("\u2014", AngleFormatter.formatDuration(seconds = Double.NaN))
        assertEquals("\u2014", AngleFormatter.formatDuration(seconds = Double.POSITIVE_INFINITY))
        assertEquals("\u2014", AngleFormatter.formatDuration(seconds = Double.NEGATIVE_INFINITY))
        assertEquals("\u2014", AngleFormatter.formatDuration(seconds = 1e12))
        assertEquals("\u2014", AngleFormatter.formatDuration(seconds = 1e300))
        assertEquals("--", AngleFormatter.formatDuration(seconds = Double.NaN, style = TypographyStyle.ASCII))
        assertNotEquals("\u2014", AngleFormatter.formatDuration(seconds = 9.9e11))
    }

    @Test
    fun testExposureFractions() {
        assertEquals("1/250 s", AngleFormatter.formatExposure(seconds = 1.0 / 250))
        assertEquals("1/250 s", AngleFormatter.formatExposure(seconds = 0.004))
        assertEquals("1/8000 s", AngleFormatter.formatExposure(seconds = 1.0 / 8000))
        assertEquals("1/2000 s", AngleFormatter.formatExposure(seconds = 0.0005))
        assertEquals("1/10 s", AngleFormatter.formatExposure(seconds = 0.1))
        assertEquals("1/4 s", AngleFormatter.formatExposure(seconds = 0.2499))
    }

    @Test
    fun testExposureFractionsCannotOverflow() {
        assertEquals("1/1000000000 s", AngleFormatter.formatExposure(seconds = 1e-12))
        // Double.MIN_VALUE is Swift's leastNonzeroMagnitude, the smallest subnormal.
        assertEquals("1/1000000000 s", AngleFormatter.formatExposure(seconds = Double.MIN_VALUE))
    }

    @Test
    fun testExposureSubSecondDecimals() {
        assertEquals("0.25 s", AngleFormatter.formatExposure(seconds = 0.25))
        assertEquals("0.3 s", AngleFormatter.formatExposure(seconds = 0.3))
        assertEquals("0.5 s", AngleFormatter.formatExposure(seconds = 0.5))
        assertEquals("0.8 s", AngleFormatter.formatExposure(seconds = 0.8))
        assertEquals("0.33 s", AngleFormatter.formatExposure(seconds = 0.333))
        assertEquals("1 s", AngleFormatter.formatExposure(seconds = 0.999))
    }

    @Test
    fun testExposureShortSeconds() {
        assertEquals("1 s", AngleFormatter.formatExposure(seconds = 1.0))
        assertEquals("1 s", AngleFormatter.formatExposure(seconds = 1.04))
        assertEquals("1.3 s", AngleFormatter.formatExposure(seconds = 1.25))
        assertEquals("2.5 s", AngleFormatter.formatExposure(seconds = 2.5))
        assertEquals("4 s", AngleFormatter.formatExposure(seconds = 4.0))
        assertEquals("10 s", AngleFormatter.formatExposure(seconds = 9.96))
    }

    @Test
    fun testExposureWholeSecondsAndMinutes() {
        assertEquals("10 s", AngleFormatter.formatExposure(seconds = 10.0))
        assertEquals("13 s", AngleFormatter.formatExposure(seconds = 13.0))
        assertEquals("13 s", AngleFormatter.formatExposure(seconds = 13.4))
        assertEquals("14 s", AngleFormatter.formatExposure(seconds = 13.9))
        assertEquals("30 s", AngleFormatter.formatExposure(seconds = 30.0))
        assertEquals("59 s", AngleFormatter.formatExposure(seconds = 59.4))
        assertEquals("1m 00s", AngleFormatter.formatExposure(seconds = 59.5))
        assertEquals("1m 00s", AngleFormatter.formatExposure(seconds = 60.0))
        assertEquals("2m 30s", AngleFormatter.formatExposure(seconds = 150.0))
        assertEquals("1h 00m", AngleFormatter.formatExposure(seconds = 3600.0))
    }

    @Test
    fun testExposureInvalidValues() {
        assertEquals("\u2014", AngleFormatter.formatExposure(seconds = 0.0))
        assertEquals("\u2014", AngleFormatter.formatExposure(seconds = -1.0))
        assertEquals("\u2014", AngleFormatter.formatExposure(seconds = Double.NaN))
        assertEquals("\u2014", AngleFormatter.formatExposure(seconds = Double.POSITIVE_INFINITY))
        assertEquals("--", AngleFormatter.formatExposure(seconds = 0.0, style = TypographyStyle.ASCII))
    }

    @Test
    fun testExposureOutputIsPlainWhenAsciiRequested() {
        for (seconds in listOf(0.002, 0.8, 2.5, 13.0, 150.0, 7300.0)) {
            assertTrue(isASCII(AngleFormatter.formatExposure(seconds = seconds, style = TypographyStyle.ASCII)))
        }
    }
}

// MARK: - Parsing

class AngleParserTests {

    private fun assertValue(value: Double?, expected: Double, text: String, accuracy: Double = 1e-9) {
        if (value == null) {
            fail("expected $expected for ${quoted(text)}, got nil")
        }
        assertEquals(expected, value, accuracy, "for ${quoted(text)}")
    }

    private fun assertRA(text: String, expected: Double) {
        assertValue(AngleFormatter.parseRightAscension(text), expected, text)
    }

    private fun assertDec(text: String, expected: Double) {
        assertValue(AngleFormatter.parseDeclination(text), expected, text)
        // Latitude shares the grammar and range of declination.
        assertValue(AngleFormatter.parseLatitude(text), expected, text)
    }

    private fun assertLon(text: String, expected: Double) {
        assertValue(AngleFormatter.parseLongitude(text), expected, text)
    }

    private fun assertNoRA(inputs: List<String>) {
        for (text in inputs) {
            assertNull(AngleFormatter.parseRightAscension(text), "RA ${quoted(text)}")
        }
    }

    private fun assertNoDec(inputs: List<String>) {
        for (text in inputs) {
            assertNull(AngleFormatter.parseDeclination(text), "Dec ${quoted(text)}")
            assertNull(AngleFormatter.parseLatitude(text), "Lat ${quoted(text)}")
        }
    }

    private fun assertNoLon(inputs: List<String>) {
        for (text in inputs) {
            assertNull(AngleFormatter.parseLongitude(text), "Lon ${quoted(text)}")
        }
    }

    // MARK: Right ascension

    @Test
    fun testRightAscensionAcceptedForms() {
        val orion = 5.0 + 35.0 / 60 + 17.3 / 3600
        assertRA("5 35 17.3", orion)
        assertRA("5h35m17.3s", orion)
        assertRA("5h 35m 17.3s", orion)
        assertRA("5 h 35 m 17.3 s", orion)
        assertRA("05:35:17.3", orion)
        assertRA("05:35:17", 5.0 + 35.0 / 60 + 17.0 / 3600)
        assertRA("5.588", 5.588)
        assertRA("5,588", 5.588)
        assertRA("5H35M17.3S", orion)
        assertRA("  5h 35m 17.3s  ", orion)
        assertRA("\t5\u00A035\u00A017.3\n", orion)
        assertRA("5h:35m:17.3s", orion)
    }

    @Test
    fun testRightAscensionPartialForms() {
        assertRA("0", 0.0)
        assertRA("0h", 0.0)
        assertRA("00:00:00", 0.0)
        assertRA("12h", 12.0)
        assertRA("12 h 30 m", 12.5)
        assertRA("12h 30.5m", 12 + 30.5 / 60)
        assertRA("5h35", 5 + 35.0 / 60)
        assertRA("5:35", 5 + 35.0 / 60)
        assertRA("+5.5", 5.5)
        // A decimal mark may trail or lead its digits while a value is being typed.
        assertRA("5.", 5.0)
        assertRA(".5", 0.5)
        assertDec("40.", 40.0)
        assertRA("23:59:59.9", 23 + 59.0 / 60 + 59.9 / 3600)
        assertRA("23.99999999", 23.99999999)
    }

    @Test
    fun testRightAscensionDegreesRequireAnExplicitMark() {
        assertRA("83.8\u00B0", 83.8 / 15)
        assertRA("83.8d", 83.8 / 15)
        assertRA("83.8 D", 83.8 / 15)
        assertRA("83\u00B0 49\u2032 17\u2033", (83 + 49.0 / 60 + 17.0 / 3600) / 15)
        assertRA("83d49m17s", (83 + 49.0 / 60 + 17.0 / 3600) / 15)
        assertRA("0\u00B0", 0.0)
        assertRA("359.9\u00B0", 359.9 / 15)
        // A bare number is hours; 83.8 hours is out of range rather than guessed to be degrees.
        assertNoRA(listOf("83.8", "83 49 17", "360\u00B0", "360d", "-1\u00B0", "400\u00B0"))
    }

    @Test
    fun testRightAscensionAcceptsPrimeMarksOnTimeFields() {
        val orion = 5.0 + 35.0 / 60 + 17.3 / 3600
        assertRA("5h 35\u2032 17.3\u2033", orion)
        assertRA("5h 35' 17.3\"", orion)
    }

    @Test
    fun testRightAscensionRejectsOutOfRangeAndNegative() {
        assertNoRA(listOf("24", "24h", "24:00:00", "24.0001", "100", "-1", "${minus}0.5", "-0", "${minus}5h"))
    }

    @Test
    fun testRightAscensionRejectsInvalidFields() {
        assertNoRA(
            listOf(
                "5h 60m", "5h 35m 60s", "5:60", "5:59:60", "5h 75m",
                "5h 35.5m 10s", "5.5h 35m", "5.0 35 17", "1 2 3 4", "5:35:17:3",
                "5h5h", "5m", "17.3s", "5s 3m", "5m 3h", "h5", "m", "s", "h",
                "5h 35m 17.3s extra", "5x", "RA 5h",
            ),
        )
    }

    @Test
    fun testRightAscensionRejectsHemisphereLetters() {
        assertNoRA(listOf("N 5", "5 N", "5 E", "5 W", "E5", "5h 35m 17s W"))
    }

    @Test
    fun testRightAscensionRejectsMalformedSeparators() {
        assertNoRA(listOf("", " ", "\n\t", "5:", ":5", "5::35", "5 : : 35", "5 17.3.4", "5..5", "5.5.5", "5,5,5", "5 , 35", ",", "."))
    }

    // MARK: Declination and latitude

    @Test
    fun testDeclinationAcceptedForms() {
        val value = -(5.0 + 23.0 / 60 + 28.0 / 3600)
        assertDec("${minus}05\u00B0 23\u2032 28\u2033", value)
        assertDec("-05 23 28", value)
        assertDec("-5d 23m 28s", value)
        assertDec("-5d23m28s", value)
        assertDec("-5:23:28", value)
        assertDec("${minus}5.391111111111111", value)
        assertDec("5 23 28 S", value)
        assertDec("S 5 23 28", value)
        assertDec("S5 23 28", value)
        assertDec("5\u00B023\u203228\u2033S", value)
        assertDec("5\u00B023\u203228\u2033 S", value)
        assertDec("+41:16:09", 41 + 16.0 / 60 + 9.0 / 3600)
        assertDec("41 16 09 N", 41 + 16.0 / 60 + 9.0 / 3600)
        assertDec("N 41 16 09", 41 + 16.0 / 60 + 9.0 / 3600)
        assertDec("+41\u00B0 16\u2032 09\u2033", 41 + 16.0 / 60 + 9.0 / 3600)
    }

    @Test
    fun testDeclinationDecimalForms() {
        assertDec("5.391", 5.391)
        assertDec("5,391", 5.391)
        assertDec("-5.391", -5.391)
        assertDec("+0.5", 0.5)
        assertDec("5.391S", -5.391)
        assertDec("5.391 S", -5.391)
        assertDec("5.391 s", -5.391)
        assertDec("5.391N", 5.391)
        assertDec("S 5.391", -5.391)
        assertDec("s5.391", -5.391)
        assertDec("40.015\u00B0", 40.015)
        assertDec("40.015 \u00B0", 40.015)
        assertDec("40.015\u00B0N", 40.015)
        assertDec("40.015\u00B0 N", 40.015)
        assertDec("40,015\u00B0 N", 40.015)
        assertDec("+90 N", 90.0)
        assertDec("+5 N", 5.0)
    }

    @Test
    fun testDeclinationUnitStyles() {
        val value = 40.0 + 0.0 / 60 + 54.0 / 3600
        assertDec("40\u00B00\u203254\u2033", value)
        assertDec("40\u00B0 0' 54\"", value)
        assertDec("40\u00B00\u201954\u201D", value)
        assertDec("40\u00BA0\u203254\u2033", value)
        assertDec("40\u02DA0\u203254\u2033", value)
        assertDec("40\u00B00\u00B454\u201D", value)
        assertDec("40\u00B00\u02B954\u02BA", value)
        assertDec("40\u00B00\u203254\u2032\u2032", value)
        assertDec("40\u00B00'54''", value)
        assertDec("40\u00B00\u201954\u2019\u2019", value)
        assertDec("40 0 54", value)
        assertDec("40d0m54s", value)
        assertDec("40D0M54S", value)
        assertDec("40d 0m 54s", value)
        assertDec("40:0:54", value)
    }

    @Test
    fun testDeclinationUnicodeMinusVariants() {
        for (sign in listOf("-", "\u2212", "\u2013", "\u2012", "\u2010", "\u2011", "\uFE63", "\uFF0D")) {
            assertDec("${sign}5.391", -5.391)
            assertDec("$sign 5.391", -5.391)
            assertDec("${sign}5\u00B0 30\u2032", -5.5)
        }
    }

    @Test
    fun testDeclinationSouthernHemisphereAfterLetterSeconds() {
        val value = -(40.0 + 30.0 / 60 + 15.0 / 3600)
        assertDec("40d30m15s S", value)
        assertDec("40d 30m 15s S", value)
        assertDec("40d30m15sS", value)
        assertDec("40d30m15 S", value)
        assertDec("S 40d 30m 15s", value)
        assertDec("40d30m15s N", -value)
        assertDec("40d30m15s", -value)
    }

    @Test
    fun testNegativeDegreesZeroKeepsItsSign() {
        assertDec("${minus}00\u00B0 30\u2032", -0.5)
        assertDec("-0 30", -0.5)
        assertDec("-0\u00B030'", -0.5)
        assertDec("-00:30:00", -0.5)
        assertDec("00 30 S", -0.5)
        assertDec("${minus}00\u00B0 00\u2032 01\u2033", -1.0 / 3600)
        assertDec("+00\u00B0 30\u2032", 0.5)
    }

    @Test
    fun testNegativeZeroResultIsPositiveZero() {
        for (text in listOf("-0", "${minus}0\u00B0", "0 S", "S 0", "-0 0 0", "-0.0")) {
            val declination = assertNotNull(AngleFormatter.parseDeclination(text), text)
            assertEquals(0.0, declination)
            assertFalse(isNegativeZero(declination), "${quoted(text)} must not produce negative zero")
            val longitude = assertNotNull(AngleFormatter.parseLongitude(text.replace("S", "W")), text)
            assertFalse(isNegativeZero(longitude))
        }
    }

    @Test
    fun testDeclinationBoundaries() {
        assertDec("90", 90.0)
        assertDec("-90", -90.0)
        assertDec("90\u00B000\u203200\u2033", 90.0)
        assertDec("${minus}90\u00B0 0\u2032 0\u2033", -90.0)
        assertDec("89.99999999", 89.99999999)
        assertDec("89 59 59.999", 89 + 59.0 / 60 + 59.999 / 3600)
        assertDec("90 N", 90.0)
        assertDec("90 S", -90.0)
        assertNoDec(listOf("90.0001", "-90.0001", "91", "-91", "180", "360", "90\u00B0 0\u2032 1\u2033", "${minus}90 0 1", "1e3", "100 S"))
    }

    @Test
    fun testDeclinationRejectsInvalidFields() {
        assertNoDec(
            listOf(
                "89 60 0", "89 59 60", "5 75", "5 3 2 1", "1.5 30", "1.5 30 10", "5 30.5 10",
                "5h", "5h 30m", "5 23 28 E", "5 23 28 W", "E 5", "W 5",
                "5\u2032 3\u00B0", "5\u2033", "5\u00B0\u00B0", "5\u00B0\u2032", "\u00B0",
                "5 30\u2032 15\u00B0", "5d 30d", "5m", "5h 5h",
            ),
        )
    }

    @Test
    fun testDeclinationRejectsSignAndHemisphereTogether() {
        assertNoDec(listOf("-5 S", "${minus}5 S", "S -5", "S ${minus}5", "+5 S", "-5 N", "N -5", "N 5 S", "S 5 N", "N5N", "SS5", "5SS"))
    }

    @Test
    fun testDeclinationRejectsAmbiguousSouthOrSeconds() {
        // An "s" glued to the third field is either seconds or south; without the d/m/s letter
        // style around it the text is rejected instead of guessed.
        assertNoDec(listOf("40 30 15S", "40 30 15s", "40\u00B0 30\u2032 15S", "40\u00B030\u203215s", "40d 30 15s", "40 30m 15s"))
    }

    @Test
    fun testDeclinationRejectsMalformedText() {
        assertNoDec(
            listOf(
                "", " ", "-", "+", "S", "N", "s", "\u00B0", "abc", "Dec -5", "5 N 3", "40,015,5", "40, 30", "40, -105",
                "40.015, -105.27", "5 -3", "5-3", "5.5.5", "5..5", ":5", "5:", "5::3", "5 3 2 1", "--5", "+-5", "5 N N",
                "40d30m15s.", "40\u00B030\u203215\u2033.", "40 .", ". 40",
            ),
        )
    }

    @Test
    fun testLatitudeAcceptedForms() {
        assertValue(AngleFormatter.parseLatitude("40.015 N"), 40.015, "40.015 N")
        assertValue(AngleFormatter.parseLatitude("N 40.015"), 40.015, "N 40.015")
        assertValue(AngleFormatter.parseLatitude("40.0150\u00B0 N"), 40.015, "40.0150\u00B0 N")
        assertValue(AngleFormatter.parseLatitude("33.8688\u00B0 S"), -33.8688, "33.8688\u00B0 S")
        assertValue(AngleFormatter.parseLatitude("-33.8688"), -33.8688, "-33.8688")
        assertValue(AngleFormatter.parseLatitude("33\u00B052\u203208\u2033S"), -(33 + 52.0 / 60 + 8.0 / 3600), "33\u00B052\u203208\u2033S")
        assertValue(AngleFormatter.parseLatitude("0"), 0.0, "0")
        assertValue(AngleFormatter.parseLatitude("S90"), -90.0, "S90")
        assertNoDec(listOf("90.1 N", "100 S", "E 5", "5 W", "40.015 NN", "-91"))
    }

    // MARK: Longitude

    @Test
    fun testLongitudeAcceptedForms() {
        val dms = -(105.0 + 16.0 / 60 + 14.0 / 3600)
        assertLon("-105.2705", -105.2705)
        assertLon("${minus}105.2705", -105.2705)
        assertLon("105.2705 W", -105.2705)
        assertLon("105.2705W", -105.2705)
        assertLon("105.2705\u00B0 W", -105.2705)
        assertLon("W 105.2705", -105.2705)
        assertLon("W105.2705", -105.2705)
        assertLon("w 105.2705", -105.2705)
        assertLon("105,2705 w", -105.2705)
        assertLon("E 105.2705", 105.2705)
        assertLon("105.2705 E", 105.2705)
        assertLon("+105.2705", 105.2705)
        assertLon("105\u00B016\u203214\u2033 W", dms)
        assertLon("105 16 14 W", dms)
        assertLon("105:16:14 W", dms)
        assertLon("105d16m14s W", dms)
        assertLon("105d16m14sW", dms)
        assertLon("105d 16m 14s w", dms)
        assertLon("W 105d 16m 14s", dms)
        assertLon("${minus}105\u00B0 16\u2032 14\u2033", dms)
        assertLon("2.3522 E", 2.3522)
    }

    @Test
    fun testLongitudeAcceptsEastDegreesFrom180To360() {
        assertLon("254.7295", -105.2705)
        assertLon("E 254.7295", -105.2705)
        assertLon("254.7295 E", -105.2705)
        assertLon("181", -179.0)
        assertLon("359.5", -0.5)
        assertLon("E 270", -90.0)
        assertLon("270\u00B0 0\u2032 0\u2033", -90.0)
    }

    @Test
    fun testLongitudeBoundaries() {
        assertLon("0", 0.0)
        assertLon("0\u00B0", 0.0)
        assertLon("180", 180.0)
        assertLon("-180", 180.0)
        assertLon("W 180", 180.0)
        assertLon("E 180", 180.0)
        assertLon("180\u00B000\u203200\u2033", 180.0)
        assertLon("360", 0.0)
        assertLon("179.9999", 179.9999)
        assertLon("-179.9999", -179.9999)
        assertLon("180.0001", -179.9999)
        assertNoLon(listOf("-181", "-180.0001", "361", "360.0001", "W 181", "270 W", "E 361", "${minus}181", "1000", "-360"))
    }

    @Test
    fun testLongitudeRejectsInvalidText() {
        assertNoLon(
            listOf(
                "", " ", "W", "E", "N 5", "5 N", "5 N 3", "105 16 60", "105 60 0", "105d 16m 14s X", "5h", "5 S",
                "-5 W", "+5 W", "W -5", "W 5 E", "E5E", "1e2", "105.2705, -40", "105.2705 W 40", "5 3 2 1", "NaN", "inf",
            ),
        )
    }

    // MARK: Hostile input

    @Test
    fun testNonNumbersAreRejected() {
        val words = listOf(
            "NaN", "nan", "-nan", "inf", "-inf", "Infinity", "-Infinity", "+infinity", "0x10", "1e5", "1E-5", "1e400",
            "0b101", "NULL", "null", "true",
        )
        assertNoRA(words)
        assertNoDec(words)
        assertNoLon(words)
    }

    @Test
    fun testNonAsciiDigitsAreRejected() {
        val digits = listOf("\u0665", "\uFF15", "\u0966", "\u00B2", "\u2155", "5\u0665", "\uFF15.\uFF15", "\u2460")
        assertNoRA(digits)
        assertNoDec(digits)
        assertNoLon(digits)
    }

    @Test
    fun testStrayUnicodeIsRejected() {
        val strays = listOf(
            "5\u200B", "\uFEFF5", "5\u0301", "5 \uD83C\uDF1F", "\u20145", "5\u2014", "\u2014", "5\u00B0\u200D", "5\u0000", "5\u0007",
        )
        assertNoRA(strays)
        assertNoDec(strays)
        assertNoLon(strays)
    }

    @Test
    fun testHugeNumbersAreRejected() {
        val huge = "9".repeat(64)
        assertNoRA(listOf(huge, "$huge."))
        assertNoDec(listOf(huge))
        assertNoLon(listOf(huge))
        assertNoRA(listOf("99999999999999999999999999"))
        assertNoDec(listOf("99999999999999999999999999", "0.0000000000000000000000000001 99999999999999999999"))
        assertNoLon(listOf("99999999999999999999999999"))
    }

    @Test
    fun testTinyNumbersAreAccepted() {
        val tiny = "0." + "0".repeat(61) + "1"
        assertEquals(64, tiny.length)
        assertValue(AngleFormatter.parseDeclination(tiny), 0.0, tiny, accuracy = 1e-60)
        assertValue(AngleFormatter.parseRightAscension(tiny), 0.0, tiny, accuracy = 1e-60)
    }

    @Test
    fun testLengthIsCappedAtSixtyFourCharacters() {
        val accepted = "5." + "0".repeat(61) + "1"
        assertEquals(64, accepted.codePointCount(0, accepted.length))
        assertValue(AngleFormatter.parseDeclination(accepted), 5.0, accepted)
        assertValue(AngleFormatter.parseRightAscension(accepted), 5.0, accepted)
        assertValue(AngleFormatter.parseLongitude(accepted), 5.0, accepted)

        val rejected = "5." + "0".repeat(62) + "1"
        assertEquals(65, rejected.codePointCount(0, rejected.length))
        assertNoRA(listOf(rejected))
        assertNoDec(listOf(rejected))
        assertNoLon(listOf(rejected))

        // Whitespace counts towards the cap, so padding cannot be used to smuggle in extra input.
        assertNoDec(listOf(" ".repeat(64) + "5"))
        assertValue(AngleFormatter.parseDeclination(" ".repeat(63) + "5"), 5.0, "padded")
    }

    @Test
    fun testVeryLongInputIsRejectedQuickly() {
        val megabyte = "1".repeat(1_000_000)
        assertNoRA(listOf(megabyte))
        assertNoDec(listOf(megabyte))
        assertNoLon(listOf(megabyte))
    }

    @Test
    fun testMultiScalarCharactersCountPerScalar() {
        // 33 combining sequences are 66 scalars: over the cap even though they are 33 characters.
        val sequence = "5\u0301".repeat(33)
        assertEquals(33, graphemeCount(sequence))
        assertEquals(66, sequence.codePointCount(0, sequence.length))
        assertNoDec(listOf(sequence))
    }

    // MARK: Grammar properties

    /** The same sexagesimal value written in every supported style. */
    private fun styles(whole: Int, minutes: Int, seconds: String): List<String> = listOf(
        "$whole $minutes $seconds",
        "$whole:$minutes:$seconds",
        "${whole}d${minutes}m${seconds}s",
        "${whole}d ${minutes}m ${seconds}s",
        "${whole}D${minutes}M${seconds}S",
        "${whole}\u00B0${minutes}\u2032${seconds}\u2033",
        "${whole}\u00B0 ${minutes}\u2032 ${seconds}\u2033",
        "${whole}\u00B0 ${minutes}' ${seconds}\"",
        "${whole}\u00BA${minutes}\u2019${seconds}\u201D",
        "${whole}\u00B0${minutes}'${seconds}''",
    )

    private class Sample(val whole: Int, val minutes: Int, val seconds: String, val value: Double)

    private fun randomSexagesimal(generator: SplitMix64, wholeLimit: ULong): Sample {
        val whole = (generator.next() % wholeLimit).toInt()
        val minutes = (generator.next() % 60uL).toInt()
        val tenths = (generator.next() % 600uL).toInt()
        val seconds = if (tenths % 10 == 0 && generator.next() % 2uL == 0uL) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
        val value = whole.toDouble() + minutes.toDouble() / 60 + tenths.toDouble() / 10 / 3600
        return Sample(whole, minutes, seconds, value)
    }

    @Test
    fun testEveryDeclinationStyleAgrees() {
        val generator = SplitMix64(101uL)
        repeat(200) {
            val sample = randomSexagesimal(generator, 90uL)
            for (text in styles(sample.whole, sample.minutes, sample.seconds)) {
                val decorated = listOf(
                    text to sample.value,
                    "+$text" to sample.value,
                    "-$text" to -sample.value,
                    "$minus$text" to -sample.value,
                    "\u2013$text" to -sample.value,
                    "$text N" to sample.value,
                    "N $text" to sample.value,
                    "$text S" to -sample.value,
                    "S $text" to -sample.value,
                    "$text  s" to -sample.value,
                )
                for ((input, expected) in decorated) {
                    assertValue(AngleFormatter.parseDeclination(input), expected, input, accuracy = 1e-9)
                    assertValue(AngleFormatter.parseLatitude(input), expected, input, accuracy = 1e-9)
                }
            }
            // Symbol styles can carry the hemisphere letter without a space.
            for (text in styles(sample.whole, sample.minutes, sample.seconds).takeLast(5).filter { !it.endsWith("S") }) {
                assertValue(AngleFormatter.parseDeclination(text + "S"), -sample.value, text + "S", accuracy = 1e-9)
                assertValue(AngleFormatter.parseDeclination(text + "N"), sample.value, text + "N", accuracy = 1e-9)
            }
        }
    }

    @Test
    fun testEveryLongitudeStyleAgrees() {
        val generator = SplitMix64(202uL)
        repeat(200) {
            val sample = randomSexagesimal(generator, 180uL)
            for (text in styles(sample.whole, sample.minutes, sample.seconds)) {
                val decorated = listOf(
                    text to sample.value,
                    "+$text" to sample.value,
                    "-$text" to -sample.value,
                    "$minus$text" to -sample.value,
                    "$text E" to sample.value,
                    "E $text" to sample.value,
                    "$text W" to -sample.value,
                    "W $text" to -sample.value,
                    "${text}w" to -sample.value,
                )
                for ((input, expected) in decorated) {
                    // Negative zero longitudes and the antimeridian have their own tests.
                    val wrapped = if (expected == -180.0) 180.0 else expected
                    assertValue(AngleFormatter.parseLongitude(input), wrapped, input, accuracy = 1e-9)
                }
            }
        }
    }

    @Test
    fun testEveryRightAscensionStyleAgrees() {
        val generator = SplitMix64(303uL)
        repeat(200) {
            val sample = randomSexagesimal(generator, 24uL)
            val hourStyles = listOf(
                "${sample.whole} ${sample.minutes} ${sample.seconds}",
                "${sample.whole}:${sample.minutes}:${sample.seconds}",
                "${sample.whole}h${sample.minutes}m${sample.seconds}s",
                "${sample.whole}h ${sample.minutes}m ${sample.seconds}s",
                "${sample.whole} h ${sample.minutes} m ${sample.seconds} s",
                "${sample.whole}H${sample.minutes}M${sample.seconds}S",
                "${sample.whole}h${sample.minutes}\u2032${sample.seconds}\u2033",
            )
            for (text in hourStyles) {
                assertValue(AngleFormatter.parseRightAscension(text), sample.value, text, accuracy = 1e-9)
                assertValue(AngleFormatter.parseRightAscension("+$text"), sample.value, "+$text", accuracy = 1e-9)
                assertNoRA(listOf("-$text", "$text N", "${text}x", "x$text", "$text 1"))
            }

            // The degrees form divides by 15 and needs a degree mark on the first field.
            val degrees = randomSexagesimal(generator, 360uL)
            val degreeStyles = listOf(
                "${degrees.whole}d${degrees.minutes}m${degrees.seconds}s",
                "${degrees.whole}\u00B0${degrees.minutes}\u2032${degrees.seconds}\u2033",
                "${degrees.whole}\u00B0 ${degrees.minutes} ${degrees.seconds}",
            )
            for (text in degreeStyles) {
                assertValue(AngleFormatter.parseRightAscension(text), degrees.value / 15, text, accuracy = 1e-9)
            }
        }
    }

    @Test
    fun testTrailingOrLeadingJunkAlwaysFails() {
        val generator = SplitMix64(404uL)
        repeat(100) {
            val sample = randomSexagesimal(generator, 90uL)
            for (text in styles(sample.whole, sample.minutes, sample.seconds)) {
                assertNoDec(listOf(text + "x", "x" + text, "$text 1", "1 $text", "$text N N", "$text ?", text + "\u200B"))
                assertNoLon(listOf(text + "x", "x" + text, "$text 1", "$text E E"))
            }
        }
    }

    @Test
    fun testFuzzNeverTrapsAndResultsStayInRange() {
        val alphabet = "0123456789 .,:+-\u2212\u00B0\u2032\u2033'\"\u2019hdmsHDMSNEWnewx\u00E9\u0665\u200B\n\t"
        val generator = SplitMix64(0x5EED_0F_5747uL)
        repeat(40_000) {
            val length = (generator.next() % 21uL).toInt()
            val builder = StringBuilder()
            repeat(length) {
                builder.append(alphabet[(generator.next() % alphabet.length.toULong()).toInt()])
            }
            val text = builder.toString()
            val hours = AngleFormatter.parseRightAscension(text)
            if (hours != null) {
                assertTrue(hours >= 0 && hours < 24, "RA ${quoted(text)} -> $hours")
            }
            val declination = AngleFormatter.parseDeclination(text)
            if (declination != null) {
                assertTrue(declination.isFinite() && abs(declination) <= 90, "Dec ${quoted(text)} -> $declination")
                assertFalse(isNegativeZero(declination), "negative zero for ${quoted(text)}")
            }
            val longitude = AngleFormatter.parseLongitude(text)
            if (longitude != null) {
                assertTrue(longitude > -180 && longitude <= 180, "Lon ${quoted(text)} -> $longitude")
                assertFalse(isNegativeZero(longitude), "negative zero for ${quoted(text)}")
            }
        }
    }

    @Test
    fun testFuzzOfValidShapedInput() {
        // Structured random strings: mostly valid, so the accepted paths are exercised too.
        val generator = SplitMix64(42uL)
        val separators = listOf(" ", ":", "", "\u00B0", "d", "h", "m", "'", "\u2032", "\u2033", "s", "\"", " S", " N", " W", " E")
        val prefixes = listOf("-", "+", "\u2212", "S ", "N ", "W ", "E ")
        val parsers = listOf<(String) -> Double?>(
            { AngleFormatter.parseRightAscension(it) },
            { AngleFormatter.parseDeclination(it) },
            { AngleFormatter.parseLatitude(it) },
            { AngleFormatter.parseLongitude(it) },
        )
        var accepted = 0
        repeat(40_000) {
            var text = if ((generator.next() % 2uL).toInt() == 0) "" else prefixes[(generator.next() % 7uL).toInt()]
            val fields = 1 + (generator.next() % 3uL).toInt()
            for (index in 0 until fields) {
                text += (generator.next() % 400uL).toString()
                if ((generator.next() % 4uL).toInt() == 0) text += "." + (generator.next() % 1000uL).toString()
                text += separators[(generator.next() % separators.size.toULong()).toInt()]
                if (index < fields - 1 && (generator.next() % 2uL).toInt() == 0) text += " "
            }
            for (parse in parsers) {
                val value = parse(text)
                if (value != null) {
                    accepted += 1
                    assertTrue(value.isFinite(), text)
                }
            }
        }
        assertTrue(accepted > 1000, "the generator should reach the accepting paths")
    }
}

// MARK: - Round trips

class FormattingRoundTripTests {

    @Test
    fun testRightAscensionRoundTrip() {
        val generator = SplitMix64(7uL)
        for (style in TypographyStyle.entries) {
            repeat(2_000) {
                val hours = (generator.next() % 24_000_000uL).toDouble() / 1_000_000
                val text = AngleFormatter.formatRightAscension(hours = hours, secondsDecimals = 3, style = style)
                val parsed = assertNotNull(AngleFormatter.parseRightAscension(text), text)
                // Seconds are printed to the millisecond: half of that, in hours, plus rounding slack.
                assertEquals(hours, parsed, 0.0005 / 3600 + 1e-12, text)
            }
        }
    }

    @Test
    fun testDeclinationRoundTrip() {
        val generator = SplitMix64(8uL)
        for (style in TypographyStyle.entries) {
            repeat(2_000) {
                val degrees = (generator.next() % 180_000_000uL).toDouble() / 1_000_000 - 90
                val text = AngleFormatter.formatDeclination(degrees = degrees, secondsDecimals = 2, style = style)
                val parsed = assertNotNull(AngleFormatter.parseDeclination(text), text)
                assertEquals(degrees, parsed, 0.005 / 3600 + 1e-12, text)
                val latitude = assertNotNull(AngleFormatter.parseLatitude(text), text)
                assertEquals(parsed, latitude)
            }
        }
    }

    @Test
    fun testDeclinationRoundTripAtTheEdges() {
        for (degrees in listOf(-90.0, 90.0, 0.0, -0.5, 0.5, -1.0 / 3600, 89.9999999)) {
            val text = AngleFormatter.formatDeclination(degrees = degrees)
            val parsed = assertNotNull(AngleFormatter.parseDeclination(text), text)
            assertEquals(degrees, parsed, 0.5 / 3600 + 1e-12, text)
        }
    }

    @Test
    fun testDMSRoundTrip() {
        val generator = SplitMix64(9uL)
        for (style in TypographyStyle.entries) {
            repeat(2_000) {
                val degrees = (generator.next() % 358_000_000uL).toDouble() / 1_000_000 - 179
                val text = AngleFormatter.formatDMS(degrees, secondsDecimals = 1, style = style)
                val parsed = assertNotNull(AngleFormatter.parseLongitude(text), text)
                // Seconds are printed to a tenth: half of that, in degrees, plus rounding slack.
                assertEquals(degrees, parsed, 0.05 / 3600 + 1e-12, text)
            }
        }
    }

    @Test
    fun testLatitudeAndLongitudeRoundTrip() {
        val generator = SplitMix64(10uL)
        for (style in TypographyStyle.entries) {
            repeat(2_000) {
                val latitude = (generator.next() % 180_000_000uL).toDouble() / 1_000_000 - 90
                val longitude = (generator.next() % 360_000_000uL).toDouble() / 1_000_000 - 180
                val latitudeText = AngleFormatter.formatLatitude(latitude, style = style)
                val longitudeText = AngleFormatter.formatLongitude(longitude, style = style)
                val parsedLatitude = assertNotNull(AngleFormatter.parseLatitude(latitudeText), latitudeText)
                val parsedLongitude = assertNotNull(AngleFormatter.parseLongitude(longitudeText), longitudeText)
                // Four decimals: half a unit in the last place, plus rounding slack.
                assertEquals(latitude, parsedLatitude, 0.00006, latitudeText)
                // Longitudes next to the antimeridian may legitimately come back on the other side.
                val difference = abs(parsedLongitude - longitude) % 360
                assertTrue(min(difference, 360 - difference) < 0.00006, longitudeText)
            }
        }
    }

    @Test
    fun testAsciiStyleIsPureAsciiAndStillParses() {
        val declination = AngleFormatter.formatDeclination(degrees = -5.391, secondsDecimals = 1, style = TypographyStyle.ASCII)
        assertEquals("-05d 23' 27.6\"", declination)
        assertTrue(isASCII(declination))
        assertEquals(-5.391, assertNotNull(AngleFormatter.parseDeclination(declination)), 0.05 / 3600)

        val samples = listOf(
            AngleFormatter.formatRightAscension(hours = 5.588, style = TypographyStyle.ASCII),
            AngleFormatter.formatDegrees(-12.3, style = TypographyStyle.ASCII),
            AngleFormatter.formatSignedDegrees(12.3, style = TypographyStyle.ASCII),
            AngleFormatter.formatAzimuth(300.0, style = TypographyStyle.ASCII),
            AngleFormatter.formatDMS(-1.5, style = TypographyStyle.ASCII),
            AngleFormatter.formatLatitude(-33.8688, style = TypographyStyle.ASCII),
            AngleFormatter.formatLongitude(-105.2705, style = TypographyStyle.ASCII),
            AngleFormatter.formatDeclination(degrees = Double.NaN, style = TypographyStyle.ASCII),
            AngleFormatter.formatDuration(seconds = -3900.0, style = TypographyStyle.ASCII),
            AngleFormatter.formatExposure(seconds = 0.002, style = TypographyStyle.ASCII),
        )
        for (sample in samples) {
            assertTrue(isASCII(sample), sample)
        }
        assertEquals(-33.8688, assertNotNull(AngleFormatter.parseLatitude("33.8688d S")), 1e-9)
        assertEquals(-105.2705, assertNotNull(AngleFormatter.parseLongitude("105.2705d W")), 1e-9)
    }

    @Test
    fun testPlaceholdersAreNotParseable() {
        for (style in TypographyStyle.entries) {
            val placeholder = AngleFormatter.formatDegrees(Double.NaN, style = style)
            assertNull(AngleFormatter.parseRightAscension(placeholder))
            assertNull(AngleFormatter.parseDeclination(placeholder))
            assertNull(AngleFormatter.parseLongitude(placeholder))
        }
    }
}

// MARK: - Deterministic random numbers

/** A small, seeded generator so the randomised tests are reproducible. */
private class SplitMix64(seed: ULong) {
    private var state: ULong = seed

    fun next(): ULong {
        state += 0x9E37_79B9_7F4A_7C15uL
        var value = state
        value = (value xor (value shr 30)) * 0xBF58_476D_1CE4_E5B9uL
        value = (value xor (value shr 27)) * 0x94D0_49BB_1331_11EBuL
        return value xor (value shr 31)
    }
}
