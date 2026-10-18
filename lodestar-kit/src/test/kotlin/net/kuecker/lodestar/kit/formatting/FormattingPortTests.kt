package net.kuecker.lodestar.kit.formatting

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for the parts of the port that have no Swift counterpart: the Kotlin replacements for
// Foundation behaviour (Double.description, Unicode.Scalar.Properties.isWhitespace, JSON encoding of
// the Int-backed CompassPoint) and the guarantee that the default locale never matters.

class NumberTextSwiftDescriptionTests {

    @Test
    fun testKnownSwiftDescriptions() {
        val expected = listOf(
            36.0 to "36.0",
            24.0 to "24.0",
            23.5 to "23.5",
            0.1 to "0.1",
            100.0 to "100.0",
            200.0 to "200.0",
            -2.5 to "-2.5",
            0.0001 to "0.0001",
            0.00001 to "1e-05",
            1.5e-7 to "1.5e-07",
            1e15 to "1000000000000000.0",
            9007199254740992.0 to "9007199254740992.0",
            1e22 to "1e+22",
            123456789.125 to "123456789.125",
            Double.MIN_VALUE to "5e-324",
            Double.MAX_VALUE to "1.7976931348623157e+308",
            0.30000000000000004 to "0.30000000000000004",
            17.299999999999997 to "17.299999999999997",
            1.0 / 3 to "0.3333333333333333",
            0.0 to "0.0",
            -0.0 to "-0.0",
        )
        for ((value, text) in expected) {
            assertEquals(text, NumberText.swiftDescription(value), "description of $value")
        }
    }

    @Test
    fun testNonFiniteDescriptions() {
        assertEquals("nan", NumberText.swiftDescription(Double.NaN))
        assertEquals("inf", NumberText.swiftDescription(Double.POSITIVE_INFINITY))
        assertEquals("-inf", NumberText.swiftDescription(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun testFallbackDescriptionsOfDisplayText() {
        // Values that cannot be scaled to an integer fall back to the description.
        assertEquals("1e+300", NumberText.fixedDescription(1e300, decimals = 1))
        assertEquals("nan", NumberText.fixedDescription(Double.NaN, decimals = 1))
        assertEquals("-1.5", NumberText.fixedDescription(-1.5, decimals = 1))
        assertEquals("inf", NumberText.trimmedDescription(Double.POSITIVE_INFINITY, maxDecimals = 2))
        assertEquals("2.5", NumberText.fixedDescription(2.5, decimals = 1))
        assertEquals("24", NumberText.trimmedDescription(24.0, maxDecimals = 2))
    }

    @Test
    fun testDescriptionReadsBackAndIsShortest() {
        val generator = SeededRandom(2026uL)
        repeat(20_000) {
            val value = when (it % 4) {
                0 -> 1.0 + generator.nextDouble() * 199.0
                1 -> generator.nextDouble()
                2 -> (generator.next() % 2_000_000uL).toDouble() / 1000.0
                else -> Double.fromBits((generator.next() and 0x7FFF_FFFF_FFFF_FFFFuL).toLong())
                    .takeIf { v -> v.isFinite() && v != 0.0 } ?: 1.0
            }
            val text = NumberText.swiftDescription(value)
            assertEquals(value, text.toDouble(), "the description of $value reads back")
            // No decimal with fewer significant digits reads back as the same value.
            val digits = BigDecimal(text).stripTrailingZeros().precision()
            if (digits > 1) {
                for (rounding in listOf(RoundingMode.FLOOR, RoundingMode.CEILING)) {
                    val shorter = BigDecimal(value).round(MathContext(digits - 1, rounding))
                    assertTrue(shorter.toDouble() != value, "$text is not the shortest description of $value")
                }
            }
        }
    }

    @Test
    fun testDescriptionAgreesWithTheJdkWhereTheJdkIsShortest() {
        // Double.toString is the shortest representation from JDK 19 on; older runtimes may differ.
        Assumptions.assumeTrue(Runtime.version().feature() >= 19)
        val generator = SeededRandom(7uL)
        repeat(20_000) {
            val value = if (it % 2 == 0) 1.0 + generator.nextDouble() * 199.0 else (generator.next() % 1_000_000uL).toDouble() / 10_000.0
            if (value == 0.0) return@repeat
            val jdk = BigDecimal(value.toString()).stripTrailingZeros()
            val ours = BigDecimal(NumberText.swiftDescription(value)).stripTrailingZeros()
            assertEquals(jdk, ours, "digits of $value")
        }
    }

    /** A small seeded generator so the randomised checks are reproducible. */
    private class SeededRandom(seed: ULong) {
        private var state = seed

        fun next(): ULong {
            state += 0x9E37_79B9_7F4A_7C15uL
            var value = state
            value = (value xor (value shr 30)) * 0xBF58_476D_1CE4_E5B9uL
            value = (value xor (value shr 27)) * 0x94D0_49BB_1331_11EBuL
            return value xor (value shr 31)
        }

        fun nextDouble(): Double = (next() shr 11).toDouble() / (1uL shl 53).toDouble()
    }
}

class AngleTextParserWhitespaceTests {

    /** The Unicode White_Space property: what Swift's `Unicode.Scalar.Properties.isWhitespace` reports. */
    private val whitespace: List<Int> =
        (0x09..0x0D).toList() + listOf(0x20, 0x85, 0xA0, 0x1680) + (0x2000..0x200A).toList() +
            listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)

    @Test
    fun testEveryUnicodeWhiteSpaceSeparatesFields() {
        for (scalar in whitespace) {
            val separator = String(Character.toChars(scalar))
            val hours = assertNotNull(AngleFormatter.parseRightAscension("5" + separator + "35"), "U+%04X".format(Locale.ROOT, scalar))
            assertEquals(5.0 + 35.0 / 60, hours, 1e-12)
            assertNotNull(AngleFormatter.parseDeclination(separator + "-5" + separator), "U+%04X".format(Locale.ROOT, scalar))
        }
    }

    @Test
    fun testNonWhiteSpaceSeparatorsAreRejected() {
        // Java treats the first four as whitespace, Unicode does not; the others are format or
        // separator-like characters that a person might paste.
        val notWhitespace = listOf(0x1C, 0x1D, 0x1E, 0x1F, 0x180E, 0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x00AD)
        for (scalar in notWhitespace) {
            val separator = String(Character.toChars(scalar))
            assertNull(AngleFormatter.parseRightAscension("5" + separator + "35"), "U+%04X".format(Locale.ROOT, scalar))
            assertNull(AngleFormatter.parseDeclination("5" + separator), "U+%04X".format(Locale.ROOT, scalar))
        }
    }

    @Test
    fun testLoneSurrogatesAreRejectedWithoutFailing() {
        for (text in listOf("5\uD800", "\uDC005", "5\uD800\uD800", "\uDFFF")) {
            assertNull(AngleFormatter.parseRightAscension(text))
            assertNull(AngleFormatter.parseDeclination(text))
            assertNull(AngleFormatter.parseLongitude(text))
        }
    }
}

class CompassPointSerializationTests {

    @Test
    fun testJsonIsTheRawInteger() {
        for (point in CompassPoint.entries) {
            assertEquals(point.rawValue.toString(), Json.encodeToString(CompassPoint.serializer(), point))
            assertEquals(point, Json.decodeFromString(CompassPoint.serializer(), point.rawValue.toString()))
        }
    }

    @Test
    fun testUnknownRawValuesAreRejected() {
        for (document in listOf("16", "-1", "99", "\"north\"", "1.5", "null", "[]")) {
            assertFailsWith<SerializationException>(document) { Json.decodeFromString(CompassPoint.serializer(), document) }
        }
    }

    @Test
    fun testRawValueLookup() {
        assertEquals(CompassPoint.EAST, CompassPoint.fromRawValue(4))
        assertNull(CompassPoint.fromRawValue(16))
        assertNull(CompassPoint.fromRawValue(-1))
    }
}

class LocaleIndependenceTests {

    private fun battery(): List<String> = listOf(
        AngleFormatter.formatRightAscension(hours = 5.588, secondsDecimals = 3),
        AngleFormatter.formatDeclination(degrees = -5.391, secondsDecimals = 2),
        AngleFormatter.formatDegrees(1234.5),
        AngleFormatter.formatSignedDegrees(-45.678, decimals = 3),
        AngleFormatter.formatAzimuth(359.96),
        AngleFormatter.formatDMS(123.4567, secondsDecimals = 2),
        AngleFormatter.formatLatitude(40.015),
        AngleFormatter.formatLongitude(-105.2705, style = TypographyStyle.ASCII),
        AngleFormatter.formatDuration(seconds = 9060.0),
        AngleFormatter.formatExposure(seconds = 0.8),
        AngleFormatter.formatExposure(seconds = 150.0),
        AngleFormatter.parseLatitude("33,8688 S").toString(),
        AngleFormatter.parseRightAscension("05:35:17.3").toString(),
        AngleFormatter.parseLongitude("105.2705 W").toString(),
        NumberText.swiftDescription(36.5),
        NumberText.trimmed(magnitude = 2.5, maxDecimals = 2).toString(),
    )

    @Test
    fun testOutputIsTheSameInEveryLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            val reference = battery()
            val locales = listOf(
                Locale.GERMANY,
                Locale.FRANCE,
                Locale.forLanguageTag("ar-SA"),
                Locale.forLanguageTag("ar-EG-u-nu-arab"),
                Locale.forLanguageTag("hi-IN-u-nu-deva"),
                Locale.forLanguageTag("th-TH-u-nu-thai"),
                Locale.forLanguageTag("fa-IR"),
                Locale.forLanguageTag("tr-TR"),
            )
            for (locale in locales) {
                Locale.setDefault(locale)
                assertEquals(reference, battery(), "locale $locale")
            }
        } finally {
            Locale.setDefault(saved)
        }
        // Digits are always ASCII digits, whatever numbering system the locale prefers.
        assertTrue(battery().all { text -> text.all { !Character.isDigit(it) || it in '0'..'9' } })
    }
}
