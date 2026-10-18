// WorldMagneticModelTests.kt
//
// Validates the World Magnetic Model 2025 implementation against:
//   * NOAA's official test values (100 points, six decimals) and NOAA's rounded test values,
//   * the high-precision numerical example and the pole values in the WMM2025 technical report,
//   * closed-form solutions (dipole, quadrupole, the poles) and mathematical identities.
//
// Reference data and sources are listed at the bottom of this file.

package net.kuecker.lodestar.kit.geomagnetism

import java.lang.reflect.Modifier
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

private typealias CoefficientRow = WorldMagneticModel.CoefficientRow
private typealias Coefficients = WMM2025Coefficients
private typealias GeocentricPosition = WorldMagneticModel.GeocentricPosition
private typealias LegendreTable = WorldMagneticModel.LegendreTable

class WorldMagneticModelTests {
    private val model = WorldMagneticModel.wmm2025

    // MARK: - NOAA test values

    /**
     * NOAA requires agreement within 0.1 nT. The published X, H and F deviate from the exact solution
     * of the model equations by up to 0.0007 nT, while Y and Z agree to the rounding of the file
     * (a 50-digit evaluation of the equations at all 100 points matched this implementation to
     * 1e-10 nT). A tolerance of 0.002 nT is therefore 50 times tighter than required. The published
     * angles carry two decimals.
     */
    @Test
    fun testOfficialTestValues_matchMainField() {
        val points = officialPoints()
        assertEquals(100, points.size)
        assertMainField(points, componentTolerance = 0.002, angleTolerance = twoDecimalRounding)
    }

    @Test
    fun testOfficialTestValues_matchSecularVariation() {
        val points = officialPoints()
        assertSecularVariation(points, intensityTolerance = 2e-5, angleTolerance = 2e-6)
    }

    /** The rounded values have one decimal for nT and two for degrees, and include longitudes above 180. */
    @Test
    fun testRoundedTestValues_matchMainFieldAndSecularVariation() {
        val points = roundedPoints()
        assertEquals(12, points.size)
        assertTrue(points.any { it.longitude > 180 })
        // Half a unit in the last published decimal, plus the deviation of the published values.
        assertMainField(points, componentTolerance = 0.05 + 0.002, angleTolerance = twoDecimalRounding)
        assertSecularVariation(points, intensityTolerance = 0.05 + 1e-4, angleTolerance = twoDecimalRounding)
    }

    // MARK: - Technical report examples

    /** Tables 3a and 3b of the technical report: every printed intermediate and final value. */
    @Test
    fun testTechnicalReportTable3_matchesHighPrecisionExample() {
        val latitude = -80.0
        val longitude = 240.0
        val heightMeters = 100_000.0
        val year = 2027.5

        val position = GeocentricPosition(geodeticLatitudeDegrees = latitude, heightKilometers = heightMeters / 1000)
        assertEquals(-1.3951289589, atan2(position.sinLatitude, position.cosLatitude), 1e-9)
        assertEquals(6457402.3484473705, position.radiusKilometers * 1000, 1e-6)

        val geocentric = model.synthesize(
            sinLatitude = position.sinLatitude,
            cosLatitude = position.cosLatitude,
            longitudeRadians = longitude * PI / 180,
            radiusKilometers = position.radiusKilometers,
            decimalYear = year,
        )
        assertEquals(5928.0241392588, geocentric.north, 1e-7)
        assertEquals(14760.1359757868, geocentric.east, 1e-7)
        assertEquals(-49324.4273570284, geocentric.down, 1e-7)

        val field = model.field(
            latitude = latitude, longitude = longitude, altitudeMeters = heightMeters, decimalYear = year,
        )
        assertEquals(5983.9760496518, field.north, 1e-7)
        assertEquals(14760.1359757868, field.east, 1e-7)
        assertEquals(-49317.6706154255, field.down, 1e-7)
        assertEquals(51825.6907172314, field.totalIntensity, 1e-7)
        assertEquals(15927.0079860130, field.horizontalIntensity, 1e-7)
        assertEquals(1.1856308407, field.declination * PI / 180, 1e-9)
        assertEquals(-1.2584221541, field.inclination * PI / 180, 1e-9)

        val rates = fieldRates(
            latitude = latitude, longitude = longitude, altitudeMeters = heightMeters, decimalYear = year,
        )
        assertEquals(30.5553533530, rates.north, 1e-6)
        assertEquals(-8.0494228995, rates.east, 1e-6)
        assertEquals(89.2174069382, rates.down, 1e-6)
        assertEquals(-83.6643506802, rates.total, 1e-6)
        assertEquals(4.0203361601, rates.horizontal, 1e-6)
        assertEquals(-0.0019677910, rates.declination * PI / 180, 1e-9)
        assertEquals(0.0006028663, rates.inclination * PI / 180, 1e-9)
    }

    /**
     * Section 1.4: on 1 January 2025, 6,371,200 m from the Earth's center above each pole, with the
     * pole assigned longitude 0. The report prints one decimal.
     */
    @Test
    fun testTechnicalReportPoleValues_matchGeocentricAndGeodeticEvaluation() {
        val polarRadiusMeters = ellipsoidSemiMajorAxisMeters * (1 - ellipsoidFlattening)
        val altitudeAboveEllipsoid = 6_371_200.0 - polarRadiusMeters

        val expectations = listOf(
            PoleExpectation(latitude = 90.0, sinLatitude = 1.0, field = Triple(1709.5, 417.8, 56517.5)),
            PoleExpectation(latitude = -90.0, sinLatitude = -1.0, field = Triple(14185.8, -8732.7, -51366.5)),
        )
        for (expectation in expectations) {
            val geocentric = model.synthesize(
                sinLatitude = expectation.sinLatitude,
                cosLatitude = 0.0,
                longitudeRadians = 0.0,
                radiusKilometers = 6371.2,
                decimalYear = 2025.0,
            )
            assertEquals(expectation.field.first, geocentric.north, 0.05)
            assertEquals(expectation.field.second, geocentric.east, 0.05)
            assertEquals(expectation.field.third, geocentric.down, 0.05)

            val geodetic = model.field(
                latitude = expectation.latitude,
                longitude = 0.0,
                altitudeMeters = altitudeAboveEllipsoid,
                decimalYear = 2025.0,
            )
            assertEquals(expectation.field.first, geodetic.north, 0.05)
            assertEquals(expectation.field.second, geodetic.east, 0.05)
            assertEquals(expectation.field.third, geodetic.down, 0.05)
        }
    }

    /** Section 1.6 locates the 2025.0 dip poles; the horizontal field must vanish there. */
    @Test
    fun testDipPoles_haveNearlyVerticalField() {
        val northDipPole = model.field(latitude = 85.76, longitude = 139.30, altitudeMeters = 0.0, decimalYear = 2025.0)
        assertTrue(northDipPole.horizontalIntensity < 100)
        assertTrue(northDipPole.inclination > 89.8)
        assertEquals(CompassReliability.BLACKOUT, northDipPole.compassReliability)

        val southDipPole = model.field(latitude = -63.85, longitude = 135.08, altitudeMeters = 0.0, decimalYear = 2025.0)
        assertTrue(southDipPole.horizontalIntensity < 100)
        assertTrue(southDipPole.inclination < -89.8)
        assertEquals(CompassReliability.BLACKOUT, southDipPole.compassReliability)
    }

    // MARK: - Geographic poles

    @Test
    fun testPoles_matchClosedFormSumsOverTheCoefficients() {
        val cases = listOf(
            PoleCase(longitude = 0.0, altitudeMeters = 0.0, year = 2025.0),
            PoleCase(longitude = -121.5, altitudeMeters = 100_000.0, year = 2027.25),
            PoleCase(longitude = 77.0, altitudeMeters = -1000.0, year = 2029.9),
            PoleCase(longitude = 180.0, altitudeMeters = 850_000.0, year = 2025.5),
            PoleCase(longitude = -0.001, altitudeMeters = 12_345.0, year = 2031.0),
        )
        for (pole in listOf(Pole.NORTH, Pole.SOUTH)) {
            for (testCase in cases) {
                val expected = poleField(
                    pole,
                    longitude = testCase.longitude,
                    altitudeMeters = testCase.altitudeMeters,
                    decimalYear = testCase.year,
                )
                val actual = model.field(
                    latitude = if (pole == Pole.NORTH) 90.0 else -90.0,
                    longitude = testCase.longitude,
                    altitudeMeters = testCase.altitudeMeters,
                    decimalYear = testCase.year,
                )
                assertEquals(expected.north, actual.north, 1e-8, "$pole $testCase")
                assertEquals(expected.east, actual.east, 1e-8, "$pole $testCase")
                assertEquals(expected.down, actual.down, 1e-8, "$pole $testCase")
            }
        }
    }

    /**
     * Assigning another longitude to a pole rotates its north-east-down frame about the polar axis,
     * so the declination changes by the longitude (north pole) or by minus the longitude (south
     * pole) while the horizontal and vertical intensities stay the same (technical report, 1.4).
     */
    @Test
    fun testPoles_declinationFollowsAssignedLongitude() {
        for ((latitude, direction) in listOf(90.0 to 1.0, -90.0 to -1.0)) {
            val reference = model.field(latitude = latitude, longitude = 0.0, altitudeMeters = 0.0, decimalYear = 2026.0)
            for (longitude in listOf(-170.0, -90.0, -10.0, 33.0, 90.0, 135.0, 180.0)) {
                val rotated = model.field(
                    latitude = latitude, longitude = longitude, altitudeMeters = 0.0, decimalYear = 2026.0,
                )
                val rotation = angleDifference(rotated.declination - reference.declination, direction * longitude)
                assertEquals(0.0, rotation, 1e-9, "latitude $latitude, longitude $longitude")
                assertEquals(reference.horizontalIntensity, rotated.horizontalIntensity, 1e-8)
                assertEquals(reference.down, rotated.down, 1e-8)
            }
        }
    }

    @Test
    fun testField_isFiniteAndContinuousApproachingThePoles() {
        for (longitude in listOf(-121.5, 0.0, 77.0)) {
            for (altitudeMeters in listOf(0.0, 100_000.0)) {
                for (sign in listOf(1.0, -1.0)) {
                    val atPole = model.field(
                        latitude = sign * 90, longitude = longitude, altitudeMeters = altitudeMeters, decimalYear = 2026.5,
                    )
                    assertTrue(atPole.isValid)
                    for (offsetDegrees in listOf(1e-7, 1e-5)) {
                        val near = model.field(
                            latitude = sign * (90 - offsetDegrees),
                            longitude = longitude,
                            altitudeMeters = altitudeMeters,
                            decimalYear = 2026.5,
                        )
                        // The field changes by well under 2e5 nT per radian of latitude.
                        val tolerance = 2e5 * offsetDegrees * PI / 180 + 1e-6
                        assertEquals(atPole.north, near.north, tolerance)
                        assertEquals(atPole.east, near.east, tolerance)
                        assertEquals(atPole.down, near.down, tolerance)
                    }
                }
            }
        }
    }

    // MARK: - Longitude and the date line

    @Test
    fun testDateLine_isContinuous() {
        for (latitude in listOf(-60.0, 0.0, 12.0, 85.0)) {
            val east = model.field(latitude = latitude, longitude = 180.0, altitudeMeters = 500.0, decimalYear = 2026.0)
            val west = model.field(latitude = latitude, longitude = -180.0, altitudeMeters = 500.0, decimalYear = 2026.0)
            assertEqual(east, west, accuracy = 1e-9)

            val justWest = model.field(
                latitude = latitude, longitude = 179.9999999, altitudeMeters = 500.0, decimalYear = 2026.0,
            )
            val justEast = model.field(
                latitude = latitude, longitude = -179.9999999, altitudeMeters = 500.0, decimalYear = 2026.0,
            )
            assertEqual(justWest, east, accuracy = 1e-3)
            assertEqual(justEast, east, accuracy = 1e-3)
        }
    }

    @Test
    fun testLongitude_isWrappedIntoOneTurn() {
        val reference = model.field(latitude = 33.0, longitude = 33.5, altitudeMeters = 0.0, decimalYear = 2026.0)
        for (longitude in listOf(33.5 + 360, 33.5 - 360, 33.5 + 720, 33.5 + 360 * 1e9, 33.5 - 360 * 1e9)) {
            val wrapped = model.field(latitude = 33.0, longitude = longitude, altitudeMeters = 0.0, decimalYear = 2026.0)
            assertEquals(reference, wrapped, "longitude $longitude")
        }
        assertTrue(model.field(latitude = 33.0, longitude = 1e300, altitudeMeters = 0.0, decimalYear = 2026.0).isValid)
    }

    // MARK: - Input handling

    @Test
    fun testNonFiniteArguments_yieldInvalidElements() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val results = listOf(
                model.field(latitude = bad, longitude = 10.0, altitudeMeters = 0.0, decimalYear = 2026.0),
                model.field(latitude = 10.0, longitude = bad, altitudeMeters = 0.0, decimalYear = 2026.0),
                model.field(latitude = 10.0, longitude = 10.0, altitudeMeters = bad, decimalYear = 2026.0),
                model.field(latitude = 10.0, longitude = 10.0, altitudeMeters = 0.0, decimalYear = bad),
            )
            for (result in results) {
                assertFalse(result.isValid)
                assertTrue(result.declination.isNaN())
                assertTrue(result.totalIntensity.isNaN())
                assertEquals(CompassReliability.BLACKOUT, result.compassReliability)
            }
        }
        assertTrue(model.field(latitude = 10.0, longitude = 10.0, altitudeMeters = 0.0, decimalYear = 2026.0).isValid)
    }

    @Test
    fun testLatitudeAndAltitude_areClamped() {
        fun field(latitude: Double = 40.0, altitude: Double = 0.0): MagneticFieldElements =
            model.field(latitude = latitude, longitude = 20.0, altitudeMeters = altitude, decimalYear = 2026.0)

        assertEquals(field(latitude = 90.0), field(latitude = 90.5))
        assertEquals(field(latitude = 90.0), field(latitude = 1e9))
        assertEquals(field(latitude = -90.0), field(latitude = -90.5))
        assertEquals(field(latitude = -90.0), field(latitude = -1e9))
        assertEquals(field(altitude = 850_000.0), field(altitude = 850_001.0))
        assertEquals(field(altitude = 850_000.0), field(altitude = 1e12))
        assertEquals(field(altitude = -1000.0), field(altitude = -1001.0))
        assertEquals(field(altitude = -1000.0), field(altitude = -1e12))
        assertNotEquals(field(altitude = 850_000.0), field(altitude = 849_999.0))
    }

    @Test
    fun testTotalIntensity_decreasesWithAltitude() {
        val altitudes = listOf(-1000.0, 0.0, 100_000.0, 400_000.0, 850_000.0)
        val intensities = altitudes.map {
            model.field(latitude = 45.0, longitude = 10.0, altitudeMeters = it, decimalYear = 2026.0).totalIntensity
        }
        assertEquals(intensities.sortedDescending(), intensities)
        assertTrue(assertNotNull(intensities.lastOrNull()) < 0.75 * intensities[1])
    }

    // MARK: - Validity interval and time

    /**
     * Swift checks `Sendable` conformance at compile time, which the JVM has no counterpart for. This
     * checks what the conformance promises instead: the public value types are immutable, and
     * evaluation on several threads at once gives the same results as on one.
     */
    @Test
    fun testPublicValueTypesAreSendable() {
        for (type in listOf(
            WorldMagneticModel::class.java,
            MagneticFieldElements::class.java,
            CompassReliability::class.java,
        )) {
            val mutableFields = type.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) }
                .map { it.name }
            assertTrue(mutableFields.isEmpty(), "${type.simpleName} has mutable fields $mutableFields")
        }

        fun evaluate(): List<MagneticFieldElements> = (0 until 200).map {
            model.field(
                latitude = -85.0 + it * 0.85,
                longitude = -179.0 + it * 1.7,
                altitudeMeters = it * 1000.0,
                decimalYear = 2025.0 + it * 0.02,
            )
        }

        val expected = evaluate()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(List(16) { Callable { evaluate() } }).map { it.get() }
            for (result in results) {
                assertEquals(expected.size, result.size)
                for ((index, element) in result.withIndex()) {
                    assertEquals(expected[index].north, element.north, 1e-9)
                    assertEquals(expected[index].east, element.east, 1e-9)
                    assertEquals(expected[index].down, element.down, 1e-9)
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun testMetadata_describesWMM2025() {
        assertEquals(2025.0, model.epoch)
        assertEquals(2025.0, model.validFrom)
        assertEquals(2030.0, model.validUntil)
    }

    @Test
    fun testValidity_coversJanuary2025ThroughDecember2029() {
        assertFalse(model.isWithinValidity(decimalYear = 2024.999))
        assertTrue(model.isWithinValidity(decimalYear = 2025.0))
        assertTrue(model.isWithinValidity(decimalYear = 2027.5))
        assertTrue(model.isWithinValidity(decimalYear = 2029.9999))
        assertFalse(model.isWithinValidity(decimalYear = 2030.0))
        assertFalse(model.isWithinValidity(decimalYear = 2031.0))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(model.isWithinValidity(decimalYear = invalid))
        }
    }

    @Test
    fun testExtrapolation_beyondValidityStillComputesSmoothly() {
        val last = model.field(latitude = 40.0, longitude = -100.0, altitudeMeters = 0.0, decimalYear = 2029.99)
        val after = model.field(latitude = 40.0, longitude = -100.0, altitudeMeters = 0.0, decimalYear = 2030.01)
        val before = model.field(latitude = 40.0, longitude = -100.0, altitudeMeters = 0.0, decimalYear = 2024.99)
        assertTrue(after.isValid)
        assertTrue(before.isValid)
        // The field changes by tens of nT per year, so 0.02 years moves it by a few nT at most.
        assertEqual(after, last, accuracy = 5.0)
        assertFalse(model.isWithinValidity(decimalYear = 2030.01))
    }

    @Test
    fun testDecimalYear_atYearStartsAndMidYears() {
        assertEquals(2025.0, decimalYear(2025, 1, 1, 0, 0, 0))
        assertEquals(2025.5, decimalYear(2025, 7, 2, 12, 0, 0))
        assertEquals(2028.5, decimalYear(2028, 7, 2, 0, 0, 0)) // 2028 has 366 days
        assertEquals(2026.0, decimalYear(2026, 1, 1, 0, 0, 0))
        assertEquals(1970.0, decimalYear(1970, 1, 1, 0, 0, 0))
        assertEquals(2000.0, decimalYear(2000, 1, 1, 0, 0, 0))
    }

    @Test
    fun testDecimalYear_justBeforeNewYearBelongsToTheOldYear() {
        val lastSecond = decimalYear(2025, 12, 31, 23, 59, 59)
        assertTrue(lastSecond < 2026.0)
        assertEquals(2026.0 - 1 / (365 * 86_400.0), lastSecond, 1e-12)
        assertEquals(1970.0 - 1 / (365 * 86_400.0), decimalYear(1969, 12, 31, 23, 59, 59), 1e-12)
        assertTrue(decimalYear(2029, 12, 31, 23, 59, 59) < 2030.0)
    }

    @Test
    fun testDecimalYear_followsGregorianLeapYearRules() {
        // Days elapsed at midnight on 1 March: 59 in a common year and 60 in a leap year.
        assertEquals(1900 + 59.0 / 365, decimalYear(1900, 3, 1, 0, 0, 0), 1e-12)
        assertEquals(2000 + 60.0 / 366, decimalYear(2000, 3, 1, 0, 0, 0), 1e-12)
        assertEquals(2100 + 59.0 / 365, decimalYear(2100, 3, 1, 0, 0, 0), 1e-12)
        assertEquals(2024 + 59.5 / 366, decimalYear(2024, 2, 29, 12, 0, 0), 1e-12)
    }

    @Test
    fun testDecimalYear_coversTheRangeOfFoundationDates() {
        // Foundation's distantPast and distantFuture are about the years 1 and 4001.
        val past = WorldMagneticModel.decimalYear(distantPast)
        val future = WorldMagneticModel.decimalYear(distantFuture)
        assertEquals(1.0, past, 1.0)
        assertEquals(4001.0, future, 1.0)
    }

    @Test
    fun testDecimalYear_matchesTheGregorianCalendarForManyDates() {
        val generator = SplitMix64(seed = 0x57_4D_4D_32_30_32_35uL) // "WMM2025"
        repeat(500) {
            // Between the years 1600 and 2400, in whole and fractional seconds.
            val seconds = generator.nextDouble(from = -11_676_096_000.0, through = 13_569_465_600.0)
            val date = instantSince1970(seconds)
            val year = date.atZone(ZoneOffset.UTC).year
            val start = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
            val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
            val expected = year.toDouble() + date.secondsSince(start) / end.secondsSince(start)
            assertEquals(expected, WorldMagneticModel.decimalYear(date), 1e-9, "$date")
        }
    }

    /**
     * Swift's `Date` can hold any `Double`; a `java.time.Instant` cannot be NaN or infinite, so the
     * non-finite and huge inputs go through the seconds-based overload that `decimalYear(Instant)`
     * delegates to, and the extremes of `Instant` are covered as well.
     */
    @Test
    fun testDecimalYear_isNotANumberForUnrepresentableDates() {
        for (seconds in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e300, -1e300, 2e13)) {
            val year = WorldMagneticModel.decimalYear(secondsSince1970 = seconds)
            assertTrue(year.isNaN(), "$seconds")
        }
        assertTrue(WorldMagneticModel.decimalYear(Instant.MAX).isNaN())
        assertTrue(WorldMagneticModel.decimalYear(Instant.MIN).isNaN())
        assertTrue(WorldMagneticModel.decimalYear(Instant.ofEpochSecond(20_000_000_000_000L)).isNaN())
        assertTrue(WorldMagneticModel.decimalYear(Instant.ofEpochSecond(-20_000_000_000_000L)).isNaN())
    }

    // MARK: - Field elements

    @Test
    fun testElements_deriveHorizontalTotalAndAngles() {
        val diagonal = MagneticFieldElements(north = 3.0, east = 3.0, down = 0.0)
        assertEquals(sqrt(18.0), diagonal.horizontalIntensity, 1e-12)
        assertEquals(sqrt(18.0), diagonal.totalIntensity, 1e-12)
        assertEquals(45.0, diagonal.declination, 1e-12)
        assertEquals(0.0, diagonal.inclination, 1e-12)

        assertEquals(90.0, MagneticFieldElements(north = 0.0, east = 0.0, down = 5.0).inclination, 1e-12)
        assertEquals(-90.0, MagneticFieldElements(north = 0.0, east = 0.0, down = -5.0).inclination, 1e-12)
        assertEquals(180.0, MagneticFieldElements(north = -1.0, east = 0.0, down = 0.0).declination, 1e-12)
        assertEquals(-45.0, MagneticFieldElements(north = 1.0, east = -1.0, down = 0.0).declination, 1e-12)
        assertEquals(13.0, MagneticFieldElements(north = 3.0, east = 4.0, down = 12.0).totalIntensity, 1e-12)
        assertEquals(
            atan2(12.0, 5.0) * 180 / PI, MagneticFieldElements(north = 3.0, east = 4.0, down = 12.0).inclination, 1e-12,
        )
    }

    @Test
    fun testElements_areHashableByTheirComponents() {
        val first = MagneticFieldElements(north = 1.0, east = 2.0, down = 3.0)
        assertEquals(1, setOf(first, MagneticFieldElements(north = 1.0, east = 2.0, down = 3.0)).size)
        assertNotEquals(MagneticFieldElements(north = 1.0, east = 2.0, down = 4.0), first)
    }

    @Test
    fun testCompassReliability_followsHorizontalIntensityThresholds() {
        fun reliability(horizontal: Double): CompassReliability =
            MagneticFieldElements(north = horizontal, east = 0.0, down = 50_000.0).compassReliability

        assertEquals(CompassReliability.RELIABLE, reliability(horizontal = 30_000.0))
        assertEquals(CompassReliability.RELIABLE, reliability(horizontal = 6000.0))
        assertEquals(CompassReliability.CAUTION, reliability(horizontal = 5999.9))
        assertEquals(CompassReliability.CAUTION, reliability(horizontal = 2000.0))
        assertEquals(CompassReliability.BLACKOUT, reliability(horizontal = 1999.9))
        assertEquals(CompassReliability.BLACKOUT, reliability(horizontal = 0.0))

        // Tromso is well clear of the Caution Zone; Resolute Bay, Nunavut, lies inside it.
        val tromso = model.field(latitude = 69.65, longitude = 18.96, altitudeMeters = 0.0, decimalYear = 2026.0)
        assertEquals(CompassReliability.RELIABLE, tromso.compassReliability)
        val resolute = model.field(latitude = 74.7, longitude = -94.8, altitudeMeters = 0.0, decimalYear = 2026.0)
        assertEquals(CompassReliability.CAUTION, resolute.compassReliability)
    }

    // MARK: - Geodetic to geocentric conversion

    @Test
    fun testGeocentricConversion_matchesTheClosedFormOnTheEllipsoid() {
        val semiMajorAxis = ellipsoidSemiMajorAxisMeters / 1000
        val semiMinorAxis = semiMajorAxis * (1 - ellipsoidFlattening)
        val eccentricitySquared = ellipsoidFlattening * (2 - ellipsoidFlattening)
        for (latitudeDegrees in listOf(-90.0, -75.0, -45.5, -10.0, 0.0, 10.0, 38.0, 60.0, 89.9, 90.0)) {
            val latitude = latitudeDegrees * PI / 180
            val position = GeocentricPosition(geodeticLatitudeDegrees = latitudeDegrees, heightKilometers = 0.0)

            // On the surface: tan(phi') = (1 - e^2) tan(phi) and the radius of an ellipse.
            val geocentricLatitude = atan2(position.sinLatitude, position.cosLatitude)
            if (abs(latitudeDegrees) < 90) {
                assertEquals(
                    (1 - eccentricitySquared) * tan(latitude), tan(geocentricLatitude),
                    1e-9 * max(1.0, abs(tan(latitude))),
                )
            }
            val a = semiMajorAxis
            val b = semiMinorAxis
            val numerator = (a * a * cos(latitude)).pow(2) + (b * b * sin(latitude)).pow(2)
            val denominator = (a * cos(latitude)).pow(2) + (b * sin(latitude)).pow(2)
            assertEquals(sqrt(numerator / denominator), position.radiusKilometers, 1e-9)

            // The rotation angle is the difference of the two latitudes.
            assertEquals(
                geocentricLatitude - latitude, atan2(position.rotationSine, position.rotationCosine), 1e-12,
            )
            assertEquals(1.0, hypot(position.sinLatitude, position.cosLatitude), 1e-14)
        }

        // Height adds to the radius along the ellipsoid normal: exactly at the equator and the poles.
        val equator = GeocentricPosition(geodeticLatitudeDegrees = 0.0, heightKilometers = 100.0)
        assertEquals(semiMajorAxis + 100, equator.radiusKilometers, 1e-9)
        val pole = GeocentricPosition(geodeticLatitudeDegrees = 90.0, heightKilometers = 100.0)
        assertEquals(semiMinorAxis + 100, pole.radiusKilometers, 1e-9)
        assertEquals(1.0, pole.sinLatitude, 1e-15)
    }

    // MARK: - Associated Legendre functions

    /** Equation (6) of the report gives P(3, m) without normalization; equation (5) normalizes them. */
    @Test
    fun testLegendreTable_matchesTheReportsDegreeThreeFunctions() {
        for (latitudeDegrees in listOf(-80.0, -33.0, 0.0, 12.5, 61.0, 90.0)) {
            val latitude = latitudeDegrees * PI / 180
            val s = sin(latitude)
            val c = cos(latitude)
            val table = LegendreTable(maximumDegree = 3, sinLatitude = s, cosLatitude = c)
            val expected = listOf(
                0 to 0.5 * s * (5 * s * s - 3),
                1 to sqrt(1.0 / 6) * (-1.5 * c * (1 - 5 * s * s)),
                2 to sqrt(1.0 / 60) * (15 * s * (1 - s * s)),
                3 to sqrt(1.0 / 360) * (15 * c * c * c),
            )
            for (entry in expected) {
                val index = LegendreTable.index(degree = 3, order = entry.first)
                assertEquals(
                    entry.second, table.value[index], 1e-13, "P(3,${entry.first}) at $latitudeDegrees",
                )
            }
        }
    }

    @Test
    fun testLegendreTable_derivativesMatchFiniteDifferencesInLatitude() {
        fun table(latitude: Double): LegendreTable =
            LegendreTable(maximumDegree = 12, sinLatitude = sin(latitude), cosLatitude = cos(latitude))

        val step = 1e-6
        for (latitudeDegrees in listOf(-89.0, -60.0, -10.0, 0.0, 25.0, 70.0, 88.0)) {
            val latitude = latitudeDegrees * PI / 180
            val analytic = table(latitude)
            val above = table(latitude + step)
            val below = table(latitude - step)
            for (degree in 1..12) {
                for (order in 0..degree) {
                    val index = LegendreTable.index(degree = degree, order = order)
                    val numeric = (above.value[index] - below.value[index]) / (2 * step)
                    assertEquals(
                        numeric, analytic.latitudeDerivative[index], 1e-7 * max(1.0, abs(numeric)),
                        "dP($degree,$order) at $latitudeDegrees",
                    )
                }
            }
        }
    }

    @Test
    fun testLegendreTable_overCosineEqualsValueDividedByCosine() {
        for (latitudeDegrees in listOf(-89.0, -45.0, 0.0, 30.0, 88.0)) {
            val latitude = latitudeDegrees * PI / 180
            val table = LegendreTable(maximumDegree = 12, sinLatitude = sin(latitude), cosLatitude = cos(latitude))
            for (degree in 1..12) {
                for (order in 0..degree) {
                    val index = LegendreTable.index(degree = degree, order = order)
                    val expected = if (order == 0) 0.0 else table.value[index] / cos(latitude)
                    assertEquals(
                        expected, table.overCosine[index], 1e-11 * max(1.0, abs(expected)),
                        "($degree,$order) at $latitudeDegrees",
                    )
                }
            }
        }
    }

    /** Equation (5): the Schmidt functions integrate to 2/(2n+1) for order 0 and to twice that otherwise. */
    @Test
    fun testLegendreTable_isSchmidtSemiNormalized() {
        val intervals = 4000
        val step = PI / intervals
        val integrals = DoubleArray(LegendreTable.entryCount(maximumDegree = 12))
        for (k in 0..intervals) {
            val latitude = -PI / 2 + k * step
            val table = LegendreTable(maximumDegree = 12, sinLatitude = sin(latitude), cosLatitude = cos(latitude))
            val simpson = if (k == 0 || k == intervals) 1.0 else (if (k % 2 == 1) 4.0 else 2.0)
            // d(sin phi) = cos(phi) d(phi).
            for (index in integrals.indices) {
                integrals[index] += simpson * table.value[index] * table.value[index] * cos(latitude) * step / 3
            }
        }
        for (degree in 1..12) {
            for (order in 0..degree) {
                val expected = (if (order == 0) 2.0 else 4.0) / (2 * degree + 1)
                val index = LegendreTable.index(degree = degree, order = order)
                assertEquals(expected, integrals[index], 1e-9, "($degree,$order)")
            }
        }
    }

    /** The report's table of limits of P(n, 1)(sin phi') / cos(phi') at the pole, n = 1 ... 12. */
    @Test
    fun testLegendreTable_atThePolesHasTheReportedLimits() {
        val reported = listOf(
            1.0, sqrt(3.0), sqrt(6.0), sqrt(10.0), sqrt(15.0), sqrt(21.0),
            2 * sqrt(7.0), 6.0, 3 * sqrt(5.0), sqrt(55.0), sqrt(66.0), sqrt(78.0),
        )
        val north = LegendreTable(maximumDegree = 12, sinLatitude = 1.0, cosLatitude = 0.0)
        val south = LegendreTable(maximumDegree = 12, sinLatitude = -1.0, cosLatitude = 0.0)
        for (degree in 1..12) {
            val limit = reported[degree - 1]
            val parity = if (degree % 2 == 0) 1.0 else -1.0
            for (order in 0..degree) {
                val index = LegendreTable.index(degree = degree, order = order)
                when (order) {
                    0 -> {
                        assertEquals(1.0, north.value[index], 1e-14)
                        assertEquals(parity, south.value[index], 1e-14)
                        assertEquals(0.0, north.latitudeDerivative[index], 1e-14)
                        assertEquals(0.0, south.latitudeDerivative[index], 1e-14)
                    }
                    1 -> {
                        assertEquals(0.0, north.value[index], 1e-14)
                        assertEquals(limit, north.overCosine[index], 1e-13)
                        assertEquals(-parity * limit, south.overCosine[index], 1e-13)
                        assertEquals(-limit, north.latitudeDerivative[index], 1e-13)
                        assertEquals(-parity * limit, south.latitudeDerivative[index], 1e-13)
                    }
                    else -> {
                        for (table in listOf(north, south)) {
                            assertEquals(0.0, table.value[index], 1e-14)
                            assertEquals(0.0, table.overCosine[index], 1e-14)
                            assertEquals(0.0, table.latitudeDerivative[index], 1e-14)
                        }
                    }
                }
            }
        }
    }

    // MARK: - Closed-form synthetic models

    @Test
    fun testCenteredDipole_matchesTheAnalyticField() {
        val rows = listOf(CoefficientRow(degree = 1, order = 0, g = -30_000.0, h = 0.0, gDot = 10.0, hDot = 0.0))
        val dipole = WorldMagneticModel(epoch = 2000.0, rows = rows)
        for (latitudeDegrees in listOf(-90.0, -50.0, 0.0, 33.0, 90.0)) {
            val s = sin(latitudeDegrees * PI / 180)
            val c = cos(latitudeDegrees * PI / 180)
            for (radius in listOf(6371.2, 6500.0, 7221.2)) {
                val g = -30_000 + 10 * 12.5 // 12.5 years after the epoch
                val scale = radialScale(radius = radius, degree = 1)
                val field = dipole.synthesize(
                    sinLatitude = s, cosLatitude = c, longitudeRadians = 1.3, radiusKilometers = radius,
                    decimalYear = 2012.5,
                )
                assertEquals(-g * scale * c, field.north, 1e-9)
                assertEquals(0.0, field.east, 1e-9)
                assertEquals(-2 * g * scale * s, field.down, 1e-9)
            }
        }

        // Through the public interface: the field points north along the equator, down at the north pole.
        val equator = dipole.field(latitude = 0.0, longitude = 77.0, altitudeMeters = 0.0, decimalYear = 2000.0)
        val equatorScale = radialScale(radius = 6378.137, degree = 1)
        assertEquals(30_000 * equatorScale, equator.north, 1e-8)
        assertEquals(0.0, equator.down, 1e-8)
        assertEquals(0.0, equator.declination, 1e-9)
        assertEquals(0.0, equator.inclination, 1e-9)
        val pole = dipole.field(latitude = 90.0, longitude = 77.0, altitudeMeters = 0.0, decimalYear = 2000.0)
        val polarRadius = ellipsoidSemiMajorAxisMeters * (1 - ellipsoidFlattening) / 1000
        assertEquals(60_000 * radialScale(radius = polarRadius, degree = 1), pole.down, 1e-8)
        assertEquals(0.0, pole.horizontalIntensity, 1e-8)
    }

    /**
     * The order-one dipole terms exercise the east component, which divides by cos(phi'): at the
     * pole it must stay finite and equal its limit.
     */
    @Test
    fun testEquatorialDipole_matchesTheAnalyticFieldIncludingAtThePole() {
        val rows = listOf(CoefficientRow(degree = 1, order = 1, g = 1000.0, h = -2000.0, gDot = 0.0, hDot = 0.0))
        val dipole = WorldMagneticModel(epoch = 2000.0, rows = rows)
        for (latitudeDegrees in listOf(-90.0, -40.0, 0.0, 70.0, 90.0)) {
            val s = sin(latitudeDegrees * PI / 180)
            val c = cos(latitudeDegrees * PI / 180)
            for (longitudeDegrees in listOf(-150.0, 0.0, 35.0, 90.0)) {
                val lambda = longitudeDegrees * PI / 180
                val scale = radialScale(radius = 6400.0, degree = 1)
                val field = dipole.synthesize(
                    sinLatitude = s, cosLatitude = c, longitudeRadians = lambda, radiusKilometers = 6400.0,
                    decimalYear = 2000.0,
                )
                // V = a (a/r)^2 (g cos(lambda) + h sin(lambda)) cos(phi').
                val potentialTerm = 1000 * cos(lambda) - 2000 * sin(lambda)
                val derivativeTerm = 1000 * sin(lambda) + 2000 * cos(lambda)
                assertEquals(scale * potentialTerm * s, field.north, 1e-9)
                assertEquals(scale * derivativeTerm, field.east, 1e-9)
                assertEquals(-2 * scale * potentialTerm * c, field.down, 1e-9)
            }
        }
    }

    @Test
    fun testQuadrupole_matchesTheAnalyticField() {
        val rows = listOf(CoefficientRow(degree = 2, order = 0, g = 500.0, h = 0.0, gDot = 0.0, hDot = 0.0))
        val quadrupole = WorldMagneticModel(epoch = 2000.0, rows = rows)
        for (latitudeDegrees in listOf(-70.0, -20.0, 0.0, 45.0, 90.0)) {
            val s = sin(latitudeDegrees * PI / 180)
            val c = cos(latitudeDegrees * PI / 180)
            val scale = radialScale(radius = 6800.0, degree = 2)
            // P(2,0) = (3 sin^2 - 1) / 2, dP/dphi = 3 sin cos.
            val field = quadrupole.synthesize(
                sinLatitude = s, cosLatitude = c, longitudeRadians = 0.4, radiusKilometers = 6800.0,
                decimalYear = 2000.0,
            )
            assertEquals(-500 * scale * 3 * s * c, field.north, 1e-9)
            assertEquals(0.0, field.east, 1e-9)
            assertEquals(-3 * 500 * scale * (3 * s * s - 1) / 2, field.down, 1e-9)
        }
    }

    @Test
    fun testSecularVariation_isLinearInTime() {
        val rows = listOf(CoefficientRow(degree = 1, order = 1, g = 1000.0, h = -2000.0, gDot = 30.0, hDot = -40.0))
        val dipole = WorldMagneticModel(epoch = 2000.0, rows = rows)
        val components: (Double) -> WorldMagneticModel.GeocentricComponents = {
            dipole.synthesize(
                sinLatitude = 0.6, cosLatitude = 0.8, longitudeRadians = 0.7, radiusKilometers = 6371.2,
                decimalYear = it,
            )
        }
        val start = components(2000.0)
        val middle = components(2003.0)
        val end = components(2006.0)
        assertEquals((start.north + end.north) / 2, middle.north, 1e-9)
        assertEquals((start.east + end.east) / 2, middle.east, 1e-9)
        assertEquals((start.down + end.down) / 2, middle.down, 1e-9)
        assertNotEquals(end.east, start.east)
    }

    // MARK: - Coefficient table

    @Test
    fun testCoefficientTable_isTheCompleteDegreeTwelveModel() {
        val rows = Coefficients.rows
        assertEquals(2025.0, Coefficients.epoch)
        assertEquals(90, rows.size)
        val iterator = rows.iterator()
        for (degree in 1..12) {
            for (order in 0..degree) {
                val row = if (iterator.hasNext()) iterator.next() else null
                assertEquals(degree, row?.degree)
                assertEquals(order, row?.order)
                if (order == 0) {
                    assertEquals(0.0, row?.h)
                    assertEquals(0.0, row?.hDot)
                }
            }
        }
        assertEquals(
            CoefficientRow(degree = 1, order = 0, g = -29351.8, h = 0.0, gDot = 12.0, hDot = 0.0), rows.firstOrNull(),
        )
        assertEquals(CoefficientRow(degree = 1, order = 1, g = -1410.8, h = 4545.4, gDot = 9.7, hDot = -21.5), rows[1])
        assertEquals(
            CoefficientRow(degree = 12, order = 12, g = -0.7, h = 0.2, gDot = -0.1, hDot = -0.1), rows.lastOrNull(),
        )
    }

    // MARK: - Kotlin-specific additions (no Swift counterpart)

    /**
     * The Swift model traps on invalid coefficient rows (`precondition`); Kotlin throws
     * [IllegalArgumentException], which can be tested.
     */
    @Test
    fun testInvalidCoefficientRows_areRejected() {
        val valid = CoefficientRow(degree = 1, order = 0, g = 1.0, h = 0.0, gDot = 0.0, hDot = 0.0)
        assertFailsWith<IllegalArgumentException> { WorldMagneticModel(epoch = 2000.0, rows = emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            WorldMagneticModel(epoch = 2000.0, rows = listOf(CoefficientRow(0, 0, 1.0, 0.0, 0.0, 0.0)))
        }
        assertFailsWith<IllegalArgumentException> {
            WorldMagneticModel(epoch = 2000.0, rows = listOf(valid, CoefficientRow(0, 0, 1.0, 0.0, 0.0, 0.0)))
        }
        assertFailsWith<IllegalArgumentException> {
            WorldMagneticModel(epoch = 2000.0, rows = listOf(valid, CoefficientRow(1, 2, 1.0, 0.0, 0.0, 0.0)))
        }
        assertFailsWith<IllegalArgumentException> {
            WorldMagneticModel(epoch = 2000.0, rows = listOf(valid, CoefficientRow(1, -1, 1.0, 0.0, 0.0, 0.0)))
        }
        assertFailsWith<IllegalArgumentException> {
            LegendreTable(maximumDegree = 0, sinLatitude = 0.0, cosLatitude = 1.0)
        }
    }

    /** `decimalYear(Instant)` converts with nanosecond input and shares its result with the seconds overload. */
    @Test
    fun testDecimalYear_instantOverloadAgreesWithTheSecondsOverload() {
        assertEquals(2025.5, WorldMagneticModel.decimalYear(Instant.parse("2025-07-02T12:00:00Z")))
        for (instant in listOf(
            Instant.EPOCH,
            Instant.ofEpochSecond(1_700_000_000L, 123_456_789L),
            Instant.ofEpochSecond(-62_135_769_600L),
            Instant.ofEpochSecond(64_092_211_200L, 999_999_999L),
        )) {
            val seconds = instant.epochSecond + instant.nano / 1e9
            assertEquals(
                WorldMagneticModel.decimalYear(secondsSince1970 = seconds), WorldMagneticModel.decimalYear(instant),
                "$instant",
            )
        }
    }

    // MARK: - Helpers

    private companion object {
        /** A reading of the two-decimal rounding of published angles. */
        const val twoDecimalRounding = 0.005 + 1e-6

        const val ellipsoidSemiMajorAxisMeters = 6_378_137.0
        const val ellipsoidFlattening = 1 / 298.257223563
        const val referenceRadiusKilometers = 6371.2

        /** Foundation's `Date.distantPast`: 63,114,076,800 s before the reference date 2001-01-01T00:00:00Z. */
        val distantPast: Instant = Instant.parse("2001-01-01T00:00:00Z").minusSeconds(63_114_076_800L)

        /** Foundation's `Date.distantFuture`: 63,113,904,000 s after the reference date 2001-01-01T00:00:00Z. */
        val distantFuture: Instant = Instant.parse("2001-01-01T00:00:00Z").plusSeconds(63_113_904_000L)

        /**
         * The field at a geographic pole evaluated from the closed-form limits of section 1.4 instead
         * of the model's recurrences: only the zonal terms (order 0) and the order-one terms survive.
         */
        fun poleField(
            pole: Pole,
            longitude: Double,
            altitudeMeters: Double,
            decimalYear: Double,
        ): WorldMagneticModel.GeocentricComponents {
            val polarRadiusKilometers = ellipsoidSemiMajorAxisMeters * (1 - ellipsoidFlattening) / 1000
            val ratio = referenceRadiusKilometers / (polarRadiusKilometers + altitudeMeters / 1000)
            val lambda = Math.IEEEremainder(longitude, 360.0) * PI / 180
            val elapsed = decimalYear - Coefficients.epoch

            var north = 0.0
            var east = 0.0
            var down = 0.0
            for (row in Coefficients.rows.filter { it.order <= 1 }) {
                val n = row.degree.toDouble()
                val radial = ratio.pow(n + 2)
                val g = row.g + elapsed * row.gDot
                val h = row.h + elapsed * row.hDot
                // P(n, 0) at the pole is 1 in the north and (-1)^n in the south.
                val zonal = if (pole == Pole.NORTH || row.degree % 2 == 0) 1.0 else -1.0
                if (row.order == 0) {
                    down -= (n + 1) * radial * g * zonal
                } else {
                    val limit = sqrt(n * (n + 1) / 2)
                    // (-1)^(n+1) in the south.
                    val sectoral = if (pole == Pole.NORTH || row.degree % 2 == 1) 1.0 else -1.0
                    val northward = radial * limit * sectoral * (g * cos(lambda) + h * sin(lambda))
                    north += if (pole == Pole.NORTH) northward else -northward
                    east += radial * limit * sectoral * (g * sin(lambda) - h * cos(lambda))
                }
            }
            return WorldMagneticModel.GeocentricComponents(north = north, east = east, down = down)
        }

        /** The smallest signed angle from `second` to `first` in degrees, in `[-180, 180]`. */
        fun angleDifference(first: Double, second: Double): Double = Math.IEEEremainder(first - second, 360.0)
    }

    /** The radial factor (a/r)^(n+2) of a term of degree n at geocentric radius r. */
    private fun radialScale(radius: Double, degree: Int): Double =
        (referenceRadiusKilometers / radius).pow((degree + 2).toDouble())

    private fun officialPoints(): List<ReferencePoint> =
        ReferencePoint.parse(NOAATestData.official, columns = 18) { ReferencePoint.fromOfficialColumns(it) }

    private fun roundedPoints(): List<ReferencePoint> =
        ReferencePoint.parse(NOAATestData.rounded, columns = 19) { ReferencePoint.fromRoundedColumns(it) }

    private fun assertEqual(first: MagneticFieldElements, second: MagneticFieldElements, accuracy: Double) {
        assertEquals(second.north, first.north, accuracy)
        assertEquals(second.east, first.east, accuracy)
        assertEquals(second.down, first.down, accuracy)
    }

    private fun decimalYear(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Double {
        val date = try {
            ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneOffset.UTC).toInstant()
        } catch (error: DateTimeException) {
            fail("Invalid date $year-$month-$day $hour:$minute:$second: ${error.message}")
        }
        return WorldMagneticModel.decimalYear(date)
    }

    private fun elements(point: ReferencePoint, decimalYear: Double? = null): MagneticFieldElements =
        model.field(
            latitude = point.latitude,
            longitude = point.longitude,
            altitudeMeters = point.altitudeKilometers * 1000,
            decimalYear = decimalYear ?: point.decimalYear,
        )

    /**
     * Annual rates of change estimated by central differences over a 0.02-year interval. Over so
     * short a span the second-order error is far below the published precision.
     */
    private fun fieldRates(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double,
        decimalYear: Double,
    ): FieldRates {
        val step = 0.01
        val later = model.field(
            latitude = latitude, longitude = longitude, altitudeMeters = altitudeMeters, decimalYear = decimalYear + step,
        )
        val earlier = model.field(
            latitude = latitude, longitude = longitude, altitudeMeters = altitudeMeters, decimalYear = decimalYear - step,
        )
        val interval = 2 * step
        return FieldRates(
            declination = angleDifference(later.declination, earlier.declination) / interval,
            inclination = (later.inclination - earlier.inclination) / interval,
            horizontal = (later.horizontalIntensity - earlier.horizontalIntensity) / interval,
            north = (later.north - earlier.north) / interval,
            east = (later.east - earlier.east) / interval,
            down = (later.down - earlier.down) / interval,
            total = (later.totalIntensity - earlier.totalIntensity) / interval,
        )
    }

    private fun assertMainField(points: List<ReferencePoint>, componentTolerance: Double, angleTolerance: Double) {
        val checks: List<Check<MagneticFieldElements>> = listOf(
            Check("declination", angleTolerance) { point, e -> abs(angleDifference(e.declination, point.declination)) },
            Check("inclination", angleTolerance) { point, e -> abs(e.inclination - point.inclination) },
            Check("H", componentTolerance) { point, e -> abs(e.horizontalIntensity - point.horizontal) },
            Check("X", componentTolerance) { point, e -> abs(e.north - point.north) },
            Check("Y", componentTolerance) { point, e -> abs(e.east - point.east) },
            Check("Z", componentTolerance) { point, e -> abs(e.down - point.down) },
            Check("F", componentTolerance) { point, e -> abs(e.totalIntensity - point.total) },
        )
        for (check in checks) {
            var worst = 0.0
            var worstPoint = points[0]
            for (point in points) {
                val error = check.error(point, elements(point))
                assertFalse(error.isNaN(), "${check.name} is not a number at $point")
                if (error > worst) {
                    worst = error
                    worstPoint = point
                }
            }
            assertTrue(worst <= check.tolerance, "${check.name} at $worstPoint: error $worst exceeds ${check.tolerance}")
        }
    }

    private fun assertSecularVariation(points: List<ReferencePoint>, intensityTolerance: Double, angleTolerance: Double) {
        val checks: List<Check<FieldRates>> = listOf(
            Check("dD/dt", angleTolerance) { point, r -> abs(r.declination - point.declinationRate) },
            Check("dI/dt", angleTolerance) { point, r -> abs(r.inclination - point.inclinationRate) },
            Check("dH/dt", intensityTolerance) { point, r -> abs(r.horizontal - point.horizontalRate) },
            Check("dX/dt", intensityTolerance) { point, r -> abs(r.north - point.northRate) },
            Check("dY/dt", intensityTolerance) { point, r -> abs(r.east - point.eastRate) },
            Check("dZ/dt", intensityTolerance) { point, r -> abs(r.down - point.downRate) },
            Check("dF/dt", intensityTolerance) { point, r -> abs(r.total - point.totalRate) },
        )
        for (check in checks) {
            var worst = 0.0
            var worstPoint = points[0]
            for (point in points) {
                val rates = fieldRates(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    altitudeMeters = point.altitudeKilometers * 1000,
                    decimalYear = point.decimalYear,
                )
                val error = check.error(point, rates)
                assertFalse(error.isNaN(), "${check.name} is not a number at $point")
                if (error > worst) {
                    worst = error
                    worstPoint = point
                }
            }
            assertTrue(worst <= check.tolerance, "${check.name} at $worstPoint: error $worst exceeds ${check.tolerance}")
        }
    }
}

// MARK: - Test support

private enum class Pole {
    NORTH,
    SOUTH,
}

/** One closed-form pole case: the longitude assigned to the pole, the altitude and the decimal year. */
private data class PoleCase(val longitude: Double, val altitudeMeters: Double, val year: Double)

/** The report's field at a pole: latitude, sin(phi') and the expected north, east and down components. */
private data class PoleExpectation(val latitude: Double, val sinLatitude: Double, val field: Triple<Double, Double, Double>)

/** A named error measure of a reference point against a computed [Subject], with its tolerance. */
private class Check<Subject>(val name: String, val tolerance: Double, val error: (ReferencePoint, Subject) -> Double)

/** Annual rates of change of the field elements: degrees per year for the angles, nT per year otherwise. */
private data class FieldRates(
    val declination: Double,
    val inclination: Double,
    val horizontal: Double,
    val north: Double,
    val east: Double,
    val down: Double,
    val total: Double,
)

/** Seconds from [other] to this instant, with sub-second precision. */
private fun Instant.secondsSince(other: Instant): Double {
    val elapsed = Duration.between(other, this)
    return elapsed.seconds + elapsed.nano / 1e9
}

/** Swift's `Date(timeIntervalSince1970:)` for a finite, in-range value. */
private fun instantSince1970(seconds: Double): Instant {
    val whole = floor(seconds)
    return Instant.ofEpochSecond(whole.toLong(), ((seconds - whole) * 1e9).toLong())
}

/** One NOAA reference point: the inputs, the field elements and their annual rates of change. */
private class ReferencePoint(
    val decimalYear: Double,
    val altitudeKilometers: Double,
    val latitude: Double,
    val longitude: Double,
    val declination: Double,
    val inclination: Double,
    val horizontal: Double,
    val north: Double,
    val east: Double,
    val down: Double,
    val total: Double,
    val declinationRate: Double,
    val inclinationRate: Double,
    val horizontalRate: Double,
    val northRate: Double,
    val eastRate: Double,
    val downRate: Double,
    val totalRate: Double,
) {
    override fun toString(): String =
        "year $decimalYear, $altitudeKilometers km, latitude $latitude, longitude $longitude"

    companion object {
        /**
         * Columns of WMM2025_TestValues.txt: year, height, latitude, longitude, D, I, H, X, Y, Z, F,
         * then the rates dD, dI, dH, dX, dY, dZ, dF. (Swift: `init(officialColumns:)`.)
         */
        fun fromOfficialColumns(c: List<Double>): ReferencePoint = ReferencePoint(
            decimalYear = c[0],
            altitudeKilometers = c[1],
            latitude = c[2],
            longitude = c[3],
            declination = c[4],
            inclination = c[5],
            horizontal = c[6],
            north = c[7],
            east = c[8],
            down = c[9],
            total = c[10],
            declinationRate = c[11],
            inclinationRate = c[12],
            horizontalRate = c[13],
            northRate = c[14],
            eastRate = c[15],
            downRate = c[16],
            totalRate = c[17],
        )

        /**
         * Columns of WMM2025_TEST_VALUES.txt: year, height, latitude, longitude, X, Y, Z, H, F, I, D,
         * grid variation (ignored), then the rates dX, dY, dZ, dH, dF, dI, dD. (Swift: `init(roundedColumns:)`.)
         */
        fun fromRoundedColumns(c: List<Double>): ReferencePoint = ReferencePoint(
            decimalYear = c[0],
            altitudeKilometers = c[1],
            latitude = c[2],
            longitude = c[3],
            declination = c[10],
            inclination = c[9],
            horizontal = c[7],
            north = c[4],
            east = c[5],
            down = c[6],
            total = c[8],
            declinationRate = c[18],
            inclinationRate = c[17],
            horizontalRate = c[15],
            northRate = c[12],
            eastRate = c[13],
            downRate = c[14],
            totalRate = c[16],
        )

        /** Parses a NOAA test-value table, ignoring comment lines and failing on any malformed row. */
        fun parse(text: String, columns: Int, make: (List<Double>) -> ReferencePoint): List<ReferencePoint> =
            text.split('\n').filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
                val tokens = line.split(' ').filter { it.isNotEmpty() }
                if (tokens.size != columns) throw TableError.WrongColumnCount(line)
                make(tokens.map { token -> token.toDoubleOrNull() ?: throw TableError.NotANumber(token) })
            }
    }
}

private sealed class TableError(message: String) : Exception(message) {
    class WrongColumnCount(val line: String) : TableError("Wrong column count: $line")
    class NotANumber(val token: String) : TableError("Not a number: $token")
}

/** A small deterministic generator so that randomized tests are reproducible. */
private class SplitMix64(seed: ULong) {
    private var state: ULong = seed

    fun next(): ULong {
        state += 0x9E37_79B9_7F4A_7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58_476D_1CE4_E5B9uL
        z = (z xor (z shr 27)) * 0x94D0_49BB_1331_11EBuL
        return z xor (z shr 31)
    }

    /** Swift's `RandomNumberGenerator.next(upperBound:)` for `UInt64`: Lemire's multiply-shift method. */
    fun next(upperBound: ULong): ULong {
        require(upperBound != 0uL) { "upperBound cannot be zero." }
        var random = next()
        var low = random * upperBound
        var high = multiplyHigh(random, upperBound)
        if (low < upperBound) {
            val threshold = (0uL - upperBound) % upperBound
            while (low < threshold) {
                random = next()
                low = random * upperBound
                high = multiplyHigh(random, upperBound)
            }
        }
        return high
    }

    /** Swift's `Double.random(in: from...through, using: &generator)`. */
    fun nextDouble(from: Double, through: Double): Double {
        val delta = through - from
        val maxSignificand = 1uL shl 53
        val random = next(upperBound = maxSignificand + 1uL)
        if (random == maxSignificand) return through
        val unitRandom = random.toDouble() * (Math.ulp(1.0) / 2)
        return delta * unitRandom + from
    }

    private fun multiplyHigh(x: ULong, y: ULong): ULong {
        val a = x.toLong()
        val b = y.toLong()
        return (Math.multiplyHigh(a, b) + ((a shr 63) and b) + ((b shr 63) and a)).toULong()
    }
}

/**
 * NOAA's published WMM2025 test values, reproduced verbatim (U.S. Government works, public domain).
 *
 * `official` is `WMM2025COF/WMM2025_TestValues.txt` from
 * https://www.ncei.noaa.gov/sites/default/files/2024-12/WMM2025COF.zip (zip SHA-256
 * 2e76569370d081f2cd7919490218bd094ca9afde347b198eff5621e0af460d03; the member's SHA-256 is
 * e6975b093dddeb6153e0b23cc418425c438167e7c5b1dd795da379cb654f5819). It lists 100 points in
 * full double precision, with declination and inclination rounded to two decimals.
 *
 * `rounded` is https://www.ncei.noaa.gov/sites/default/files/2025-02/WMM2025_TEST_VALUES.txt
 * (SHA-256 ae289c94e2200e4deeae2dfdedf543a8a0e93096512b30d6a754199731f570d0), the shorter table
 * of NOAA's product page, rounded to one decimal.
 *
 * Each string equals its file without the final line feed.
 */
private object NOAATestData {
    val official: String = """
# Field 1: Decimal year
# Field 2: Altitude (km)
# Field 3: geodetic latitude (deg)
# Field 4: geodetic longitude (deg)
# Field 5: declination (deg)
# Field 6: inclination (deg)
# Field 7: H (nT)
# Field 8: X (nT)
# Field 9: Y (nT)
# Field 10: Z (nT)
# Field 11: F (nT)
# Field 12: dD/dt (deg/year)
# Field 13: dI/dt (deg/year)
# Field 14: dH/dt (nT/year)
# Field 15: dX/dt (nT/year)
# Field 16: dY/dt (nT/year)
# Field 17: dZ/dt (nT/year)
# Field 18: dF/dt (nT/year)
2025.000000      28      89    -121   -99.77    88.47    1504.298146    -255.388723   -1482.460628   56194.288771   56214.419888    2.491706   -0.009987   10.285640   62.723738  -21.242793   18.075146   18.343917
2025.000000      48      80     -96   -29.91    87.77    2164.285547    1875.982280   -1079.269389   55623.044051   55665.134163    1.248417   -0.057537   55.469239   71.596398   13.214774  -12.148507   -9.982652
2025.000000      54      82      87    54.89    87.68    2302.427342    1324.336929    1883.428620   56740.772059   56787.466800    0.884574    0.036130  -34.169939  -48.732002   -7.505574   41.134755   39.715524
2025.000000      65      43      93     0.50    64.10   24300.764692   24299.852822     210.517066   50037.923998   55626.621348   -0.019813    0.030822   -3.938347   -3.865401   -8.437166   60.388819   52.601187
2025.000000      51     -33     109    -5.49   -67.50   21838.046477   21737.778822   -2090.274098  -52710.003920   57054.752538    0.069861    0.026375   29.809763   32.221578   23.651711   -3.334040   14.490015
2025.000000      39     -59      -8   -15.75   -58.55   14918.115796   14358.095523   -4049.107540  -24389.086374   28589.818346    0.010988    0.055916   -0.905656   -0.095123    2.999401   54.951779  -47.350226
2025.000000       3     -50    -103    27.96   -54.89   22106.041373   19526.532799   10362.990980  -31437.562789   38431.724126   -0.032104    0.024888  -42.140973  -31.417096  -30.696074   88.952383  -97.003616
2025.000000      94     -29    -110    15.74   -38.25   24181.990098   23275.471080    6559.046509  -19063.605287   30792.688931   -0.011062    0.027107  -42.453758  -39.595926  -16.008811   52.018933  -65.544285
2025.000000      66      14     143    -0.19    12.82   35003.635649   35003.441364    -116.624818    7966.315182   35898.700342   -0.055086   -0.003859   10.848172   10.735986  -33.689325   -0.010939   10.575266
2025.000000      18       0      21     1.29   -26.06   29282.246275   29274.811882     659.800118  -14316.722540   32594.761714    0.030544    0.079429   -8.761425   -9.110937   15.408838   54.581250  -31.844958
2025.500000       6     -36    -137    20.28   -52.11   25353.230658   23781.930678    8786.698927  -32577.518648   41280.516301    0.025348    0.014224  -34.463126  -36.214518   -1.422651   60.968936  -69.281309
2025.500000      63      26      81     0.51    41.07   34803.980117   34802.613433     308.431881   30332.056989   46166.554053    0.018525    0.021888   20.342054   20.241534   11.432574   41.122831   42.353703
2025.500000      69      38    -144    12.93    56.97   23096.337032   22510.804524    5167.636206   35525.990264   42373.774537   -0.105300   -0.007297  -33.951051  -23.593092  -48.967345  -62.123761  -70.589724
2025.500000      50     -70    -133    57.21   -71.94   16656.709403    9021.847823   14001.865232  -51084.838301   53731.803175   -0.036998    0.045634    8.590794   13.694592    1.395803  111.703074  -103.537175
2025.500000       8     -52     -75    14.91   -49.63   20005.506364   19331.857134    5147.774727  -23532.664273   30886.996822   -0.098427   -0.028885  -58.502212  -47.688987  -48.263512   44.775999  -72.006513
2025.500000       8     -66      17   -33.14   -59.55   18154.870136   15201.438652   -9925.501123  -30881.123197   35822.382382   -0.112991    0.041932   10.080437  -11.133189  -35.489343   34.582697  -24.703647
2025.500000      22     -37     140     9.28   -68.62   21688.847590   21404.761773    3498.897430  -55397.587760   59492.006517    0.028818    0.004094    0.393984   -1.371020   10.829532   10.653875   -9.777010
2025.500000      40     -12    -129    10.76   -15.46   29105.866326   28594.451238    5432.201489   -8052.525092   30199.248583   -0.009568    0.030404  -39.188467  -37.592705  -12.089297   27.469404  -45.094246
2025.500000      44      33    -118    11.10    57.89   23678.743422   23235.835850    4558.379362   37727.715752   44542.826874   -0.075717   -0.021971  -41.700619  -34.896682  -38.734089  -98.573110  -105.659134
2025.500000      50     -81     -67    28.13   -67.61   18292.842720   16132.682326    8623.494405  -44412.283868   48032.062762   -0.099291    0.019879  -12.853806    3.608124  -34.016532   74.964128  -74.210029
2026.000000      74     -57       3   -22.51   -58.65   14362.206593   13268.119649   -5498.179626  -23576.062921   27606.226129   -0.037845    0.082339   12.945610    8.327751  -13.719801   55.005355  -40.240277
2026.000000      46     -24    -122    14.01   -34.17   26638.500090   25846.118652    6448.863284  -18080.399376   32194.883578    0.000271    0.019932  -40.514451  -39.339842   -9.685735   41.034418  -56.566841
2026.000000      69      23      63     1.17    35.92   34565.858527   34558.605297     708.078839   25043.405885   42684.549360    0.013234    0.010624   23.857027   23.688475    8.470762   27.058242   35.194683
2026.000000      33      -3    -147     9.71    -2.12   30957.666082   30514.086409    5221.840663   -1146.978999   30978.906535   -0.006185    0.036855  -36.361404  -35.276692   -9.427361   21.287924  -37.124648
2026.000000      47     -72     -22    -6.32   -61.16   18392.516222   18280.800055   -2024.105320  -33397.486160   38127.112857   -0.048225    0.017330  -15.939764  -17.546607  -13.632509   52.849287  -53.982732
2026.000000      62     -14      99    -1.43   -44.70   33448.275663   33437.828630    -835.919473  -33100.922253   47058.030120    0.061400    0.072480   38.755947   39.639639   34.864408   45.397052   -4.385324
2026.000000      83      86     -46   -30.61    86.84    3000.255301    2582.099403   -1527.839829   54279.308437   54362.163830    1.221388   -0.002547    3.280093   35.392266   53.372894   15.551936   15.709262
2026.000000      82     -64      87   -81.74   -75.40   13974.248411    2007.074870  -13829.362571  -53663.514288   55453.154865   -0.187260   -0.029553  -23.144309  -48.522801   16.344602  -24.623137   17.996086
2026.000000      34     -19      43   -14.98   -52.33   20212.448819   19525.694050   -5224.017527  -26182.862330   33076.961273   -0.135700    0.023383   51.024088   36.917850  -59.432251  -44.004821   66.012531
2026.000000      56     -81      40   -59.77   -68.45   17877.818542    9000.536024  -15446.900890  -45267.890393   48670.301997   -0.146798    0.017031   -1.330470  -40.246526  -21.910829   42.754262  -40.254140
2026.500000      14       0      80    -3.10   -17.15   39489.089663   39431.415992   -2133.456168  -12188.838466   41327.424134    0.056431    0.021103   33.618195   35.670346   37.019904    5.553327   30.484922
2026.500000      12     -82     -68    29.79   -68.07   18497.480257   16052.819558    9190.416754  -45940.146795   49524.275496   -0.104864    0.021236  -11.118018    7.171828  -34.904116   76.756556  -75.354213
2026.500000      44     -46     -42   -11.36   -54.39   14140.316272   13863.176808   -2785.834358  -19744.304022   24285.511845    0.039043   -0.119143  -63.277988  -60.139435   21.913412    1.623579  -38.163791
2026.500000      43      17      52     1.19    23.95   36002.676553   35994.907968     747.876551   15990.895993   39394.180708   -0.010785    0.016571   20.351040   20.487426   -6.352799   21.505886   27.328663
2026.500000      64      10      78    -1.53     7.53   39441.702532   39427.724480   -1049.971869    5214.811025   39784.948821    0.031231    0.021184   27.794943   28.357414   20.751458   18.512565   29.981674
2026.500000      12      33    -145    11.96    52.51   24672.289649   24136.839639    5112.225422   32162.934508   40536.110231   -0.086620    0.000340  -40.973255  -32.355349  -44.980044  -53.017362  -67.004405
2026.500000      12     -79     115  -137.58   -77.37   13023.480845   -9613.650418   -8785.714482  -58104.306533   59545.961164   -0.241722    0.014808    7.471685  -42.581077   35.518140   37.027107  -34.496496
2026.500000      14     -33    -114    18.12   -44.10   24613.183584   23393.133584    7653.110952  -23854.293436   34275.882505   -0.000322    0.022971  -42.803882  -40.639067  -13.440897   60.620545  -72.925914
2026.500000      19      29      66     2.24    46.04   32749.959268   32725.000691    1278.343380   33958.454002   47177.711160    0.018224    0.011101   23.712541   23.287876   11.334193   37.754815   43.636705
2026.500000      86     -11     167    10.24   -31.60   33105.255278   32578.377044    5882.794932  -20368.224196   38869.300019    0.013426   -0.025948  -16.653141  -17.766581    4.674631  -10.422224   -8.722161
2027.000000      37     -66      -5   -17.22   -59.04   17159.836500   16390.771268   -5079.626554  -28608.243575   33360.029814   -0.043630    0.039964   -2.074991   -5.850093  -11.867216   48.695530  -42.826703
2027.000000      67      72    -115    13.73    84.84    5026.868699    4883.287833    1192.857435   55689.615237   55916.032175   -0.315901   -0.072636   66.581032   71.256140  -11.124661  -50.889259  -44.697541
2027.000000      44      22     174     6.46    31.89   28867.487799   28683.972510    3249.857362   17961.198575   33999.066253   -0.024375   -0.000168    2.815221    4.179894  -11.885941    1.634357    3.253718
2027.000000      54      54     178     0.63    65.46   20617.099795   20615.871559     225.041793   45149.631876   49634.202547   -0.170729    0.012944   -3.909066   -3.238256  -61.473597   18.434524   15.145168
2027.000000      57     -43      50   -48.27   -63.13   16833.417225   11203.748204  -12563.437494  -33221.366617   37242.759503   -0.165742   -0.033376   12.323002  -28.141121  -41.606787  -72.317297   70.078525
2027.000000      44     -43    -111    24.31   -52.57   22462.470699   20471.522067    9245.505620  -29347.556906   36957.295441   -0.006299    0.024728  -40.058633  -35.491603  -18.738696   78.579889  -86.747247
2027.000000      12     -63     178    57.87   -79.14   11720.691727    6233.774935    9925.455386  -61075.730989   62190.188377    0.151793    0.036783   27.353301  -11.747306   39.678751   69.308326  -62.911163
2027.000000      38      27    -169     8.48    42.66   26106.758229   25821.061601    3851.701313   24058.365347   35501.658671   -0.058765    0.013094  -19.135886  -14.976028  -29.306273   -6.601488  -18.545527
2027.000000      61      59     -77   -16.48    78.68   10884.812802   10437.775689   -3087.391846   54397.713552   55476.034370    0.230418   -0.074136   59.669126   69.634646   25.051419  -67.643997  -54.621632
2027.000000      67     -47     -32   -13.52   -57.98   12805.786103   12450.832543   -2994.148743  -20475.298079   24150.072239    0.115550   -0.077078  -52.470761  -44.977997   37.378183   22.627270  -47.007290
2027.500000       8      62      53    19.39    76.67   12997.751893   12260.425578    4315.497527   54849.810218   56368.814385    0.099601    0.030088  -12.884268  -19.655269   17.035218   74.003425   69.038304
2027.500000      77     -68      -7   -16.19   -59.82   17262.817301   16578.099495   -4813.676173  -29680.383821   34335.550744   -0.048399    0.032649   -5.108702   -8.972297  -12.579376   47.699056  -43.800575
2027.500000      98      -5     159     7.79   -23.22   33857.496691   33544.960996    4589.735713  -14525.780052   36841.937629   -0.005671   -0.036102  -11.751062  -11.188278   -4.913401  -20.218524   -2.827531
2027.500000      34     -29    -107    15.64   -37.45   24446.053109   23540.396213    6592.363667  -18723.097084   30792.269761   -0.017007    0.030517  -44.335923  -40.736596  -18.943541   54.615130  -68.406867
2027.500000      60      27      65     1.85    42.83   33079.422542   33062.159421    1068.555172   30667.425573   45108.083389    0.016699    0.011093   23.388908   23.065275   10.391374   33.592654   39.990433
2027.500000      73     -72      95  -102.64   -76.49   13306.747292   -2912.418045  -12984.118939  -55399.217725   56974.931751   -0.235287   -0.006801   -8.442550  -51.471801   20.197772    6.191978   -7.992526
2027.500000      96     -46     -85    17.93   -47.37   19914.457641   18947.658009    6129.590452  -21631.434316   29402.458634   -0.091418   -0.005439  -53.229644  -40.865392  -46.615842   53.698344  -75.558704
2027.500000       0     -13     -59   -17.49   -15.26   22401.493010   21365.849306   -6732.560620   -6112.313520   23220.406233   -0.173690   -0.385680  -68.873971  -86.099325  -44.070217  -143.226783  -28.743372
2027.500000      16      66    -178     0.37    75.67   13821.614337   13821.326215      89.244328   54092.646409   55830.559897   -0.327448    0.003026   -1.280867   -0.770805  -78.997758    6.899408    6.367545
2027.500000      72     -87      38   -65.44   -70.97   16661.696834    6926.051596  -15153.941754  -48295.635435   51088.947370   -0.148310    0.021340    0.540463  -39.001360  -18.419660   56.777921  -53.497300
2028.000000      49      20     167     5.10    26.82   30251.262453   30131.436134    2689.876669   15295.611788   33898.298187   -0.020383   -0.011359    9.497816   10.417136   -9.874921   -2.728173    7.244961
2028.000000      71       5     -13    -6.47   -17.66   28323.200567   28142.647915   -3192.970202   -9017.928693   29724.177504    0.166481   -0.073692  -18.125869   -8.732692   83.815865  -34.350117   -6.850172
2028.000000      95      14      65    -0.51    17.44   36933.940251   36932.466767    -329.910612   11601.180703   38713.089985    0.011924    0.011881   29.013291   29.080794    7.427118   17.527263   32.932326
2028.000000      86     -85     -79    41.09   -70.25   16867.459172   12713.115116   11085.480728  -46988.258092   49924.018042   -0.121705    0.024621   -3.420695   20.969078  -29.252744   73.025430  -69.886927
2028.000000      30     -36     -64    -4.65   -40.08   17398.651081   17341.333537   -1411.102609  -14639.967305   22738.551012   -0.162162   -0.152944  -69.721104  -73.485198  -43.425730  -20.660569  -40.045784
2028.000000      75      79     125   -18.59    87.42    2582.332595    2447.595900    -823.235047   57308.751756   57366.902212   -0.910648    0.022931  -21.221891  -33.198946  -32.136170   39.082660   38.087754
2028.000000      21       6     -32   -14.34    -8.70   28453.070088   27567.094260   -7045.034525   -4352.046687   28783.980055    0.177960   -0.311470  -29.041854   -6.255704   92.814073  -153.852374   -5.445988
2028.000000       1     -76     -75    29.87   -65.23   19597.635210   16993.742946    9761.147808  -42479.847250   46782.525885   -0.086171    0.016127  -22.854241   -5.137167  -36.941289   80.971555  -83.098300
2028.000000      45     -46     -41   -11.68   -54.96   13897.562372   13609.973828   -2812.623735  -19816.196211   24203.798713    0.048411   -0.117246  -62.721602  -59.047199   24.193263    3.174357  -38.612991
2028.000000      11     -22     -21   -23.24   -57.67   13528.270036   12430.606447   -5337.987778  -21373.837958   25295.356080    0.176027   -0.196560  -92.571749  -68.660974   74.716931  -16.002042  -35.987260
2028.500000      28      54    -120    15.43    73.74   15286.094495   14735.145798    4066.959949   52393.678137   54578.037650   -0.147623   -0.053732   20.801434   30.530225  -32.430768  -111.448817  -101.162318
2028.500000      68     -58     156    41.57   -81.52    9282.943032    6944.959164    6159.591995  -62264.415929   62952.605366    0.218259    0.014957   11.274695  -15.028950   33.936954   35.824068  -33.769886
2028.500000      39     -65     -88    29.45   -60.20   20609.270068   17946.905687   10131.662696  -35982.872979   41466.964689   -0.076076    0.014457  -36.693634  -18.500934  -41.868201   85.117374  -92.097328
2028.500000      27     -23      81   -13.27   -58.58   25625.329284   24940.672257   -5883.907569  -41948.733657   49156.421313    0.129297   -0.007968   18.629651   31.409843   52.004836  -43.610533   46.927695
2028.500000      11      34       0     1.57    46.77   29089.194286   29078.234597     798.434048   30945.577813   42471.284539    0.111314    0.010464   15.380882   13.823891   56.915296   27.686933   30.707939
2028.500000      72     -62      65   -67.87   -68.50   17434.847799    6567.891205  -16150.440331  -44267.327811   47576.992646   -0.176819   -0.022421   -7.894025  -52.815238  -12.956527  -30.762466   25.729685
2028.500000      55      86      70    67.64    87.57    2370.361097     901.741754    2192.139033   55926.154052   55976.363930    1.341225    0.014915  -13.210618  -56.340956    8.891342   32.415740   31.827250
2028.500000      59      32     163     0.15    43.10   28217.204381   28217.104175      75.200106   26405.862611   38645.571587   -0.043407    0.005164   13.000120   13.057045  -21.342658   16.936400   21.064439
2028.500000      65      48     148    -9.55    61.79   23693.546804   23365.167010   -3931.047029   44177.553125   50130.233993   -0.040524    0.019409    4.495479    1.652822  -17.271572   44.310648   41.173752
2028.500000      95      30      28     4.56    44.27   29786.406936   29692.133298    2367.965026   29039.808439   41599.765773    0.034245    0.035803    8.349023    6.907310   18.410171   44.444119   37.003480
2029.000000      95     -60     -59     8.58   -55.17   18095.598879   17893.018948    2700.105868  -26011.845842   31687.013474   -0.053323   -0.035373  -49.689333  -46.620155  -24.066802   37.170306  -58.889314
2029.000000      95     -70      42   -55.06   -64.54   18202.660480   10426.249753  -14920.796381  -38237.047006   42348.655378   -0.157751    0.012766    3.604264  -39.016467  -31.660684   14.381378  -11.435882
2029.000000      50      87    -154   -73.48    89.07     906.920459     257.877420    -869.484879   55992.308183   55999.652503    1.562405   -0.064156   62.930173   41.603892  -53.300475   13.406039   14.423442
2029.000000      58      32      19     4.11    46.03   29434.989012   29359.395781    2108.188210   30508.465542   42393.219362    0.056569    0.028397   10.593100    8.484435   29.745872   41.240139   37.033780
2029.000000      57      34     -13    -1.89    45.74   28257.277809   28241.863242    -933.225495   28997.458189   40488.595068    0.154151   -0.026819   21.195169   23.694393   75.283010   -5.404499   10.921620
2029.000000      38     -76      49   -64.28   -67.36   18412.640681    7991.121323  -16588.167977  -44151.303941   47836.837024   -0.160186    0.012467   -0.041101  -46.394534  -22.304306   27.140173  -25.065010
2029.000000      49     -50    -179    32.11   -71.33   18080.593790   15315.042731    9610.272521  -53522.651338   56494.088877    0.110906    0.012602   -5.111774  -22.932228   26.927867   53.956270  -52.754307
2029.000000      90     -55    -171    38.65   -72.79   16416.538468   12821.507915   10252.398258  -52995.143972   55479.618058    0.117199    0.024083    2.622098  -18.923406   27.863957   70.344114  -66.418097
2029.000000      41      42     -19    -4.13    56.44   24503.475397   24439.977735   -1762.893879   36929.897501   44319.720622    0.180959   -0.034375   24.712482   30.216258   75.411786  -10.849192    4.622821
2029.000000      19      46     -22    -5.65    60.89   22632.016516   22522.142232   -2227.393290   40651.688191   46527.066578    0.196585   -0.034725   25.397648   32.916674   74.775309  -12.351198    1.562595
2029.500000      31      13    -132     9.04    31.41   28145.022897   27795.620707    4421.061344   17187.983590   32978.312476   -0.039071    0.025976  -57.604250  -53.874337  -28.002848  -17.659477  -58.365744
2029.500000      93      -2     158     7.09   -17.84   34067.301020   33806.520595    4207.156292  -10964.647547   35788.329028   -0.010065   -0.038403   -8.149803   -7.348347   -6.945244  -22.576489   -0.841015
2029.500000      51     -76      40   -56.34   -66.22   18517.441118   10263.650094  -15412.758102  -42018.541264   45917.898858   -0.148779    0.015688    0.140771  -39.943896  -26.768531   30.856345  -28.179249
2029.500000      64      22    -132    10.23    43.76   26014.780783   25601.266218    4619.955329   24914.378368   36020.758857   -0.057237    0.010172  -55.619345  -50.120044  -35.452393  -44.412257  -70.887703
2029.500000      26     -65      55   -63.48   -65.71   18695.741849    8347.198524  -16728.868464  -41431.528035   45454.397791   -0.179396   -0.005003    1.416835  -51.746426  -27.403316  -12.788863   12.239760
2029.500000      66     -21      32   -14.63   -56.68   16100.322907   15578.335534   -4066.430831  -24494.877642   29312.444941   -0.198629    0.076846   37.723336   22.403071  -63.533684   14.184486    8.866905
2029.500000      18       9    -172     9.24    15.85   30922.514410   30520.999126    4966.941695    8779.472279   32144.689001   -0.000053    0.016657  -17.137150  -16.910013   -2.781046    4.848633  -15.161302
2029.500000      63      88      26    36.52    87.37    2539.900024    2041.140972    1511.567287   55286.620082   55344.931586    1.375737    0.002257   -1.029565  -37.121853   48.397411   25.084712   25.011033
2029.500000      33      17       5     0.89    13.77   34026.126581   34021.976056     531.446418    8341.137769   35033.582023    0.078642    0.026998    3.099752    2.369935   46.745426   17.756374    7.238224
2029.500000      77     -18     138     4.45   -47.55   31847.600506   31751.497580    2472.257962  -34817.395113   47186.021876   -0.036580    0.012491    0.895474    2.471158  -20.201885   14.262673   -9.919684
""".trimIndent()

    val rounded: String = """
# Field 1: Date
# Field 2: Height above WGS84 ellipsoid (km)
# Field 3: Geodetic Latitude (deg)
# Field 4: Geodetic Longitude (deg)
# Field 5: X (nT)
# Field 6: Y (nT)
# Field 7: Z (nT)
# Field 8: H (nT)
# Field 9: F (nT)
# Field 10: Inclination (deg)
# Field 11: Declination (deg)
# Field 12: Grid Variation (deg)
# Field 13: Xdot (nT/yr)
# Field 14: Ydot (nT/yr)
# Field 15: Zdot (nT/yr)
# Field 16: Hdot (nT/yr)
# Field 17: Fdot (nT/yr)
# Field 18: Idot (deg/yr)
# Field 19: Ddot (deg/yr)
  2025.0    0.0   80.0    0.0     6521.6      145.9    54791.5     6523.2    55178.5   83.21    1.28    1.28       -8.3       59.5       31.1       -7.0       30.1    0.01    0.52
  2025.0    0.0    0.0  120.0    39677.8     -109.6   -10580.2    39677.9    41064.3  -14.93   -0.16     NaN        9.5      -23.1       79.4        9.6      -11.2    0.11   -0.03
  2025.0    0.0  -80.0  240.0     6117.5    15751.9   -52022.5    16898.1    54698.2  -72.00   68.78  -51.22       33.3       -8.6       95.5        4.0      -89.6    0.03   -0.12
  2025.0  100.0   80.0    0.0     6216.0       92.4    52598.8     6216.7    52964.9   83.26    0.85    0.85       -7.7       56.5       28.7       -6.9       27.6    0.01    0.52
  2025.0  100.0    0.0  120.0    37688.6      -96.2   -10152.1    37688.7    39032.1  -15.08   -0.15     NaN        9.2      -21.0       72.9        9.2      -10.0    0.11   -0.03
  2025.0  100.0  -80.0  240.0     5907.6    14780.3   -49540.7    15917.1    52035.0  -72.19   68.21  -51.79       30.6       -8.0       89.2        3.9      -83.8    0.03   -0.11
  2027.5    0.0   80.0    0.0     6500.8      294.5    54869.4     6507.5    55253.9   83.24    2.59    2.59       -8.3       59.5       31.1       -5.6       30.3    0.01    0.53
  2027.5    0.0    0.0  120.0    39701.6     -167.4   -10381.8    39702.0    41036.9  -14.65   -0.24     NaN        9.5      -23.1       79.4        9.6      -10.7    0.11   -0.03
  2027.5    0.0  -80.0  240.0     6200.7    15730.3   -51783.7    16908.3    54474.2  -71.92   68.49  -51.51       33.3       -8.6       95.5        4.2      -89.5    0.04   -0.12
  2027.5  100.0   80.0    0.0     6196.7      233.8    52670.5     6201.1    53034.3   83.29    2.16    2.16       -7.7       56.5       28.7       -5.6       27.8    0.01    0.52
  2027.5  100.0    0.0  120.0    37711.5     -148.7    -9969.8    37711.8    39007.4  -14.81   -0.23     NaN        9.2      -21.0       72.9        9.3       -9.7    0.11   -0.03
  2027.5  100.0  -80.0  240.0     5984.0    14760.1   -49317.7    15927.0    51825.7  -72.10   67.93  -52.07       30.6       -8.0       89.2        4.0      -83.7    0.03   -0.11
""".trimIndent()
}
