package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrbitalElementsTests {
    private val epoch: Instant = instantFromTimeIntervalSince1970(1_792_294_095.076416)

    private fun make(
        name: String? = "TEST",
        catalogNumber: Int = 12345,
        classification: Char = 'U',
        epoch: Instant = this.epoch,
        meanMotion: Double = 15.5,
        eccentricity: Double = 0.001,
        inclination: Double = 51.6,
        node: Double = 10.0,
        argument: Double = 20.0,
        anomaly: Double = 30.0,
        ephemerisType: Int = 0,
        elementSetNumber: Int = 1,
        revolutions: Int = 2,
        bstar: Double = 0.0001,
        ndot: Double = 0.0,
        nddot: Double = 0.0,
    ): OrbitalElements = OrbitalElements.create(
        name = name, catalogNumber = catalogNumber, classification = classification, epoch = epoch,
        meanMotion = meanMotion, eccentricity = eccentricity, inclination = inclination,
        rightAscensionOfAscendingNode = node, argumentOfPericenter = argument, meanAnomaly = anomaly,
        ephemerisType = ephemerisType, elementSetNumber = elementSetNumber, revolutionNumberAtEpoch = revolutions,
        bstar = bstar, meanMotionDot = ndot, meanMotionDdot = nddot,
    )

    @Test
    fun testCreateKeepsTheValuesAndComputesTheEpochLikeTheReference() {
        val elements = make()
        assertEquals("TEST", elements.name)
        assertEquals(12345, elements.catalogNumber)
        assertEquals(15.5, elements.meanMotion, 0.0)
        assertEquals(epoch.timeIntervalSince1970, elements.epoch.timeIntervalSince1970, 1e-6)
        // Days since 1949-12-31T00:00Z: 1970-01-01T00:00Z is day 7306 of that scale.
        assertEquals(1_792_294_095.076416 / 86_400.0 + 7_306.0, elements.sgp4EpochDays, 1e-9)
        assertEquals(2_433_281.5 + elements.sgp4EpochDays, elements.epochJulianDay + elements.epochJulianDayFraction, 1e-9)
        assertEquals(make(), make())
        assertEquals(make().hashCode(), make().hashCode())
    }

    @Test
    fun testEveryFieldIsValidated() {
        fun field(block: () -> OrbitalElements): String = assertFailsWith<ElementSetError.ValueOutOfRange>(block = block).field
        assertEquals("NORAD_CAT_ID", field { make(catalogNumber = -1) })
        assertEquals("NORAD_CAT_ID", field { make(catalogNumber = 1_000_000_000) })
        assertEquals("MEAN_MOTION", field { make(meanMotion = 0.0) })
        assertEquals("MEAN_MOTION", field { make(meanMotion = 100.0) })
        assertEquals("MEAN_MOTION", field { make(meanMotion = Double.NaN) })
        assertEquals("ECCENTRICITY", field { make(eccentricity = 1.0) })
        assertEquals("ECCENTRICITY", field { make(eccentricity = -0.1) })
        assertEquals("INCLINATION", field { make(inclination = 180.1) })
        assertEquals("INCLINATION", field { make(inclination = Double.POSITIVE_INFINITY) })
        assertEquals("RA_OF_ASC_NODE", field { make(node = -0.1) })
        assertEquals("ARG_OF_PERICENTER", field { make(argument = 360.1) })
        assertEquals("MEAN_ANOMALY", field { make(anomaly = Double.NaN) })
        assertEquals("BSTAR", field { make(bstar = 10.5) })
        assertEquals("MEAN_MOTION_DOT", field { make(ndot = 101.0) })
        assertEquals("MEAN_MOTION_DDOT", field { make(nddot = Double.NaN) })
        assertEquals("EPHEMERIS_TYPE", field { make(ephemerisType = 10) })
        assertEquals("ELEMENT_SET_NO", field { make(elementSetNumber = 100_000) })
        assertEquals("REV_AT_EPOCH", field { make(revolutions = -1) })
        assertEquals("EPOCH", field { make(epoch = instantFromTimeIntervalSince1970(-600_000_000.0)) }) // 1951
    }

    @Test
    fun testBoundaryValuesAreAccepted() {
        make(meanMotion = 99.99999999, eccentricity = 0.0, inclination = 180.0, node = 360.0, argument = 360.0, anomaly = 360.0)
        make(bstar = -10.0, ndot = -100.0, nddot = 100.0, ephemerisType = 9, elementSetNumber = 99_999, revolutions = 9_999_999)
        make(catalogNumber = 999_999_999, classification = 'Z')
    }

    @Test
    fun testClassificationMustBeAnUpperCaseLetter() {
        for (bad in listOf('u', '1', ' ', 'Ü')) {
            assertEquals("CLASSIFICATION_TYPE", assertFailsWith<ElementSetError.InvalidField> { make(classification = bad) }.field)
        }
    }

    @Test
    fun testEpochsMustBeInTheSupportedYears() {
        make(epoch = instantFromTimeIntervalSince1970(-400_000_000.0)) // 1957-05
        make(epoch = instantFromTimeIntervalSince1970(4_070_000_000.0)) // 2098
        for (seconds in listOf(-500_000_000.0, 4_200_000_000.0, 1.0e12)) {
            assertEquals("EPOCH", assertFailsWith<ElementSetError.ValueOutOfRange> { make(epoch = instantFromTimeIntervalSince1970(seconds)) }.field)
        }
    }

    @Test
    fun testNamesAndDesignatorsAreSanitized() {
        assertNull(make(name = null).name)
        assertNull(make(name = "\u0000\u0001  ").name)
        assertEquals("AB", make(name = " A\u0003B ").name)
        assertEquals(80, make(name = "N".repeat(81)).name.orEmpty().length)
        val designator = OrbitalElements.create(
            objectId = "1998-067A\n", catalogNumber = 1, epoch = epoch, meanMotion = 15.0, eccentricity = 0.0,
            inclination = 1.0, rightAscensionOfAscendingNode = 1.0, argumentOfPericenter = 1.0, meanAnomaly = 1.0,
        )
        assertEquals("1998-067A", designator.objectId)
    }

    @Test
    fun testAgeIsSignedAndInDays() {
        val elements = make()
        assertEquals(0.0, elements.ageDays(epoch), 1e-9)
        assertEquals(1.5, elements.ageDays(epoch.addingTimeInterval(129_600.0)), 1e-9)
        assertEquals(-2.0, elements.ageDays(epoch.addingTimeInterval(-172_800.0)), 1e-9)
    }

    @Test
    fun testCalendarArithmetic() {
        assertEquals(0L, ElementCalendar.daysFromCivil(1970, 1, 1))
        assertEquals(11_017L, ElementCalendar.daysFromCivil(2000, 3, 1))
        assertEquals(-1L, ElementCalendar.daysFromCivil(1969, 12, 31))
        assertEquals(20_379L, ElementCalendar.daysFromCivil(2025, 10, 18))
        var day = -20_000L
        while (day < 30_000L) {
            val civil = ElementCalendar.civilFromDays(day)
            assertEquals(day, ElementCalendar.daysFromCivil(civil.year, civil.month, civil.day))
            assertTrue(civil.day in 1..ElementCalendar.daysInMonth(civil.year, civil.month))
            day += 7
        }
        assertTrue(ElementCalendar.isLeapYear(2000))
        assertTrue(!ElementCalendar.isLeapYear(1900))
        assertTrue(ElementCalendar.isLeapYear(2024))
        assertTrue(!ElementCalendar.isLeapYear(2026))
        assertEquals(29, ElementCalendar.daysInMonth(2024, 2))
        assertEquals(28, ElementCalendar.daysInMonth(2026, 2))
        assertEquals(30, ElementCalendar.daysInMonth(2026, 11))
    }

    @Test
    fun testJulianDayAndDayOfYearFollowTheReference() {
        val noon = ElementCalendar.julianDay(2000, 1, 1, 12, 0, 0.0)
        assertEquals(2_451_544.5, noon.day, 0.0)
        assertEquals(0.5, noon.fraction, 0.0)
        val monthDayTime = ElementCalendar.monthDayTime(2000, 60.5)
        assertEquals(listOf(2, 29, 12, 0), listOf(monthDayTime.month, monthDayTime.day, monthDayTime.hour, monthDayTime.minute))
        val first = ElementCalendar.monthDayTime(2026, 1.0)
        assertEquals(listOf(1, 1, 0, 0), listOf(first.month, first.day, first.hour, first.minute))
        val last = ElementCalendar.monthDayTime(2026, 365.75)
        assertEquals(listOf(12, 31, 18, 0), listOf(last.month, last.day, last.hour, last.minute))
        val leapEnd = ElementCalendar.monthDayTime(2024, 366.25)
        assertEquals(listOf(12, 31, 6), listOf(leapEnd.month, leapEnd.day, leapEnd.hour))
    }
}
