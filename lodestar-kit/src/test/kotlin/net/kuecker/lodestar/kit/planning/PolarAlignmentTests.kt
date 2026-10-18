package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The polar-scope reading: its clock convention, its invariants over a day, and worked examples. */
class PolarAlignmentTests {
    private val boulder = PlanningTestSupport.boulder // 40° N, 105.27° W
    private val paranal = PlanningTestSupport.paranal // 24.63° S, 70.40° W
    private val sydney = PlanningTestSupport.sydney // 33.87° S, 151.21° E

    /** Degrees of hour angle per second: a sidereal day is 86 164.0905 s. */
    private val siderealRate = 360.985_647_366_29 / 86_400

    private val origin: Instant = instantFromTimeIntervalSince1970(1_792_195_200.0) // 2026-10-17T00:00:00Z

    private fun frame(site: PlanningTestSupport.Site, date: Instant): SkyFrame = SkyFrame(date, site.observer)

    private fun reading(
        site: PlanningTestSupport.Site,
        date: Instant,
        hemisphere: PolarAlignment.Hemisphere,
        view: PolarAlignment.ScopeView = PolarAlignment.ScopeView.NAKED,
    ): PolarScopeReading = PolarAlignment.polarScopeReading(frame(site, date), hemisphere, view)

    /** Difference of two angles in degrees, in (−180, 180]. */
    private fun difference(a: Double, b: Double): Double = AstroMath.normalizeSignedDegrees(a - b)

    /** The instant after [date] at which the reference star's hour angle is [target] degrees. */
    private fun instant(
        target: Double,
        date: Instant,
        site: PlanningTestSupport.Site,
        hemisphere: PolarAlignment.Hemisphere,
    ): Instant {
        var current = date
        repeat(6) {
            val error = difference(target, reading(site, current, hemisphere).hourAngle)
            current = current.addingTimeInterval(error / siderealRate)
        }
        return current
    }

    // MARK: Worked examples

    /**
     * Numbers worked out by hand from Skyfield's apparent places and sidereal time (the fixtures), not from
     * LodestarKit. At 2026-10-17T00:00:00Z Greenwich apparent sidereal time is 1.7010005 h = 25.5150°.
     */
    @Test
    fun testPolarisFromBoulderAtSixPM() {
        // Polaris: apparent RA 47.174033°, Dec +89.374866°. Local apparent sidereal time at 105.27° W is
        // 25.5150° − 105.27° = −79.7550°, so the hour angle is −79.7550° − 47.1740° = −126.9290°. Facing north the
        // sky turns counter-clockwise, so the clock angle is +126.929°: 4 h 13.9 min. Pole distance 0.62513°.
        val result = reading(boulder, origin, PolarAlignment.Hemisphere.NORTH)
        assertEquals("Polaris", result.referenceStarName)
        assertEquals(-126.929_025, result.hourAngle, 0.002)
        assertEquals(126.929_025, result.angle, 0.002)
        assertEquals(4.230_968, result.clockPosition, 0.0001)
        assertEquals(0.625_134, result.separationFromPole, 0.0001)
        assertEquals(40.0, result.poleAltitude, 1e-6)
        assertEquals(0.0, result.poleAzimuth, 1e-6)

        val scope = reading(boulder, origin, PolarAlignment.Hemisphere.NORTH, PolarAlignment.ScopeView.INVERTED)
        assertEquals(306.929_025, scope.angle, 0.002)
        assertEquals(10.230_968, scope.clockPosition, 0.0001)
    }

    @Test
    fun testSigmaOctantisFromParanalAtEightPM() {
        // σ Octantis: apparent RA 322.785822°, Dec −88.847666°. Local sidereal time at 70.40° W is
        // 25.5150° − 70.40° = −44.8850°, so the hour angle is −44.8850° − 322.7858° = −367.6708°, which is
        // −7.6708°. Facing south the sky turns clockwise: the clock angle is 352.329°, or 11 h 44.6 min,
        // half an hour before the star stands directly above the pole. Pole distance 1.15233°.
        val result = reading(paranal, origin, PolarAlignment.Hemisphere.SOUTH)
        assertEquals("σ Octantis", result.referenceStarName)
        assertEquals(-7.670_814, result.hourAngle, 0.002)
        assertEquals(352.329_186, result.angle, 0.002)
        assertEquals(11.744_306, result.clockPosition, 0.0001)
        assertEquals(1.152_334, result.separationFromPole, 0.0001)
        assertEquals(24.63, result.poleAltitude, 1e-6)
        assertEquals(180.0, result.poleAzimuth, 1e-6)
    }

    @Test
    fun testSigmaOctantisFromSydneyAtElevenAM() {
        // Sydney, 151.21° E: local sidereal time 25.5150° + 151.21° = 176.7250°; the hour angle is
        // 176.7250° − 322.7858° = −146.0608°; clockwise from the top that is 213.939°, or 7 h 8 min.
        val result = reading(sydney, origin, PolarAlignment.Hemisphere.SOUTH)
        assertEquals(-146.060_814, result.hourAngle, 0.002)
        assertEquals(213.939_186, result.angle, 0.002)
        assertEquals(7.131_306, result.clockPosition, 0.0001)
        assertEquals(PolarAlignment.ScopeView.NAKED, result.view)
        assertEquals(PolarAlignment.Hemisphere.SOUTH, result.hemisphere)
    }

    // MARK: The clock convention

    @Test
    fun testNorthernSkyTurnsCounterClockwiseWithUpperCulminationAtTwelve() {
        val upper = instant(0.0, origin, boulder, PolarAlignment.Hemisphere.NORTH)

        // "Hours" are sidereal hours, 3 600 / 1.0027379 s each; clock readings are compared on the dial, so 11.999 is near 0.
        fun offDial(afterHours: Double, expected: Double): Double {
            val clock = reading(
                boulder, upper.addingTimeInterval(afterHours * 3_600 / 1.002_737_909), PolarAlignment.Hemisphere.NORTH,
            ).clockPosition
            return difference(clock * 30, expected * 30) / 30
        }
        assertEquals(0.0, offDial(0.0, 0.0), 0.003, "12:00, directly above the pole")
        assertEquals(0.0, offDial(6.0, 9.0), 0.003, "a quarter turn counter-clockwise: 9:00")
        assertEquals(0.0, offDial(12.0, 6.0), 0.003, "lower culmination, directly below the pole")
        assertEquals(0.0, offDial(18.0, 3.0), 0.003)
        assertEquals(0.0, offDial(-6.0, 3.0), 0.003, "six hours before upper culmination: 3:00")
    }

    @Test
    fun testSouthernSkyTurnsClockwiseWithUpperCulminationAtTwelve() {
        val upper = instant(0.0, origin, paranal, PolarAlignment.Hemisphere.SOUTH)

        fun offDial(afterHours: Double, expected: Double): Double {
            val clock = reading(
                paranal, upper.addingTimeInterval(afterHours * 3_600 / 1.002_737_909), PolarAlignment.Hemisphere.SOUTH,
            ).clockPosition
            return difference(clock * 30, expected * 30) / 30
        }
        assertEquals(0.0, offDial(0.0, 0.0), 0.003)
        assertEquals(0.0, offDial(6.0, 3.0), 0.003, "a quarter turn clockwise: 3:00")
        assertEquals(0.0, offDial(12.0, 6.0), 0.003)
        assertEquals(0.0, offDial(18.0, 9.0), 0.003)
    }

    @Test
    fun testUpperCulminationPutsTheStarStraightAboveThePoleInTheSky() {
        // At upper culmination the star is on the meridian on the zenith side of the pole, so it is higher
        // than the pole; at lower culmination it is lower.
        for ((site, hemisphere) in listOf(
            boulder to PolarAlignment.Hemisphere.NORTH, paranal to PolarAlignment.Hemisphere.SOUTH,
        )) {
            val upper = instant(0.0, origin, site, hemisphere)
            val lower = instant(180.0, origin, site, hemisphere)
            val north = hemisphere == PolarAlignment.Hemisphere.NORTH
            val star = if (north) PolarAlignment.polaris else PolarAlignment.sigmaOctantis

            fun height(date: Instant): Pair<Double, Double> {
                val skyFrame = frame(site, date)
                return Pair(
                    skyFrame.horizontal(star.position, star.properMotion, false).altitude,
                    skyFrame.horizontalOfCelestialPole(north, false).altitude,
                )
            }
            assertTrue(height(upper).first > height(upper).second + 0.5, "$hemisphere")
            assertTrue(height(lower).first < height(lower).second - 0.5, "$hemisphere")
            assertEquals(0.0, difference(reading(site, upper, hemisphere).angle, 0.0), 0.001)
            assertEquals(0.0, difference(reading(site, lower, hemisphere).angle, 180.0), 0.001)
        }
    }

    @Test
    fun testInvertedViewIsTurnedThroughSixHours() {
        for (hemisphere in PolarAlignment.Hemisphere.entries) {
            var index = 0
            while (true) {
                val hours = 0.0 + index.toDouble() * 1.7
                if (hours >= 24) break
                index += 1
                val date = origin.addingTimeInterval(hours * 3_600)
                val naked = reading(boulder, date, hemisphere)
                val inverted = reading(boulder, date, hemisphere, PolarAlignment.ScopeView.INVERTED)
                assertEquals(PolarAlignment.ScopeView.NAKED, naked.view)
                assertEquals(PolarAlignment.ScopeView.INVERTED, inverted.view)
                assertEquals(0.0, difference(inverted.angle, naked.angle + 180), 1e-9)
                val hourShift = (inverted.clockPosition - naked.clockPosition + 12) % 12
                assertEquals(6.0, hourShift, 1e-9)
                assertEquals(naked.hourAngle, inverted.hourAngle)
                assertEquals(naked.separationFromPole, inverted.separationFromPole)
            }
        }
    }

    // MARK: Invariants

    @Test
    fun testReadingsStayInRangeAndAgreeWithTheirDefinitionsThroughADay() {
        for ((site, hemisphere) in listOf(
            boulder to PolarAlignment.Hemisphere.NORTH, paranal to PolarAlignment.Hemisphere.SOUTH,
            sydney to PolarAlignment.Hemisphere.SOUTH,
        )) {
            for (step in 0 until 288) {
                val date = origin.addingTimeInterval(step.toDouble() * 300)
                for (view in PolarAlignment.ScopeView.entries) {
                    val result = reading(site, date, hemisphere, view)
                    val label = "${site.name} $hemisphere $view +${step * 5} min"
                    assertTrue(result.angle >= 0.0 && result.angle < 360.0, label)
                    assertTrue(result.clockPosition >= 0.0 && result.clockPosition < 12.0, label)
                    assertEquals(result.angle, result.clockPosition * 30, 1e-9, label)
                    assertTrue(result.hourAngle > -180 && result.hourAngle <= 180, label)
                    assertEquals(hemisphere, result.hemisphere, label)
                    assertEquals(
                        if (hemisphere == PolarAlignment.Hemisphere.NORTH) site.latitude else -site.latitude,
                        result.poleAltitude, 1e-6, label,
                    )
                    assertEquals(
                        if (hemisphere == PolarAlignment.Hemisphere.NORTH) 0.0 else 180.0,
                        result.poleAzimuth, 1e-6, label,
                    )
                }
            }
        }
    }

    @Test
    fun testTheStarMovesAroundTheClockAtTheSiderealRate() {
        for ((site, hemisphere, direction) in listOf(
            Triple(boulder, PolarAlignment.Hemisphere.NORTH, -1.0), Triple(paranal, PolarAlignment.Hemisphere.SOUTH, 1.0),
        )) {
            var previous = reading(site, origin, hemisphere).angle
            for (hour in 1..30) {
                val angle = reading(site, origin.addingTimeInterval(hour.toDouble() * 3_600), hemisphere).angle
                assertEquals(direction * siderealRate * 3_600, difference(angle, previous), 0.002, "$hemisphere hour $hour")
                previous = angle
            }
        }
    }

    @Test
    fun testTheReadingRepeatsAfterOneSiderealDay() {
        val siderealDay = 86_400 / 1.002_737_909_350_795
        for ((site, hemisphere) in listOf(
            boulder to PolarAlignment.Hemisphere.NORTH, paranal to PolarAlignment.Hemisphere.SOUTH,
        )) {
            val first = reading(site, origin, hemisphere)
            val next = reading(site, origin.addingTimeInterval(siderealDay), hemisphere)
            assertEquals(0.0, difference(next.angle, first.angle), 0.01)
            assertEquals(first.separationFromPole, next.separationFromPole, 3e-4)
        }
    }

    @Test
    fun testPoleDistanceIsStableThroughTheNightAndAtTheExpectedSize() {
        val polaris = ArrayList<Double>()
        val sigma = ArrayList<Double>()
        for (step in 0 until 144) {
            val date = origin.addingTimeInterval(step.toDouble() * 600)
            polaris.add(reading(boulder, date, PolarAlignment.Hemisphere.NORTH).separationFromPole)
            sigma.add(reading(paranal, date, PolarAlignment.Hemisphere.SOUTH).separationFromPole)
        }
        assertTrue((polaris.maxOrNull() ?: 0.0) - (polaris.minOrNull() ?: 0.0) < 0.0005, "under two arcseconds a day")
        assertTrue((sigma.maxOrNull() ?: 0.0) - (sigma.minOrNull() ?: 0.0) < 0.0005)
        assertEquals(0.625, polaris[0], 0.01, "Polaris is 37.5′ from the pole in 2026")
        assertEquals(1.152, sigma[0], 0.01, "σ Octantis is 69′ from the pole in 2026")
    }

    @Test
    fun testPolarisSpiralsInTowardThePoleUntilTheTwentyTwentiesAndSigmaOctantisMovesOut() {
        // 2000 to 2060: the distance of Polaris from the pole falls from 0.7° to 0.45° while σ Octantis drifts away.
        fun separations(year: Double): Pair<Double, Double> {
            val date = instantFromTimeIntervalSince1970((year - 1970) * 365.2425 * 86_400)
            return Pair(
                reading(boulder, date, PolarAlignment.Hemisphere.NORTH).separationFromPole,
                reading(paranal, date, PolarAlignment.Hemisphere.SOUTH).separationFromPole,
            )
        }
        val early = separations(2000.5)
        val late = separations(2040.5)
        assertTrue(late.first < early.first)
        assertTrue(late.second > early.second)
        assertEquals(0.7, early.first, 0.05)
    }

    // MARK: Hemispheres

    @Test
    fun testHemisphereFollowsTheSignOfTheLatitude() {
        assertEquals(PolarAlignment.Hemisphere.NORTH, PolarAlignment.Hemisphere.fromLatitude(40.0))
        assertEquals(PolarAlignment.Hemisphere.NORTH, PolarAlignment.Hemisphere.fromLatitude(0.0))
        assertEquals(PolarAlignment.Hemisphere.NORTH, PolarAlignment.Hemisphere.fromLatitude(-0.0))
        assertEquals(PolarAlignment.Hemisphere.SOUTH, PolarAlignment.Hemisphere.fromLatitude(-0.001))
        assertEquals(PolarAlignment.Hemisphere.SOUTH, PolarAlignment.Hemisphere.fromLatitude(-89.9))
        assertEquals(
            listOf(PolarAlignment.Hemisphere.NORTH, PolarAlignment.Hemisphere.SOUTH),
            PolarAlignment.Hemisphere.entries,
        )
    }

    @Test
    fun testReadingWithoutAHemisphereUsesTheObserversOwn() {
        for (site in listOf(boulder, paranal, sydney, PlanningTestSupport.tromso, PlanningTestSupport.quito)) {
            val skyFrame = frame(site, origin)
            val automatic = PolarAlignment.polarScopeReading(skyFrame)
            val expected = if (site.latitude < 0) PolarAlignment.Hemisphere.SOUTH else PolarAlignment.Hemisphere.NORTH
            assertEquals(PolarAlignment.polarScopeReading(skyFrame, expected), automatic, site.name)
            assertEquals(
                if (expected == PolarAlignment.Hemisphere.NORTH) "Polaris" else "σ Octantis",
                automatic.referenceStarName, site.name,
            )
            assertEquals(
                PolarAlignment.polarScopeReading(skyFrame, expected, PolarAlignment.ScopeView.INVERTED),
                PolarAlignment.polarScopeReading(skyFrame, view = PolarAlignment.ScopeView.INVERTED),
                site.name,
            )
        }
    }

    @Test
    fun testThePoleOfTheOtherHemisphereIsBelowTheHorizon() {
        val polaris = reading(sydney, origin, PolarAlignment.Hemisphere.NORTH)
        assertEquals(-33.87, polaris.poleAltitude, 1e-6)
        assertEquals(0.0, polaris.poleAzimuth, 1e-6)
        assertTrue(polaris.angle >= 0.0 && polaris.angle < 360.0)
        val sigma = reading(boulder, origin, PolarAlignment.Hemisphere.SOUTH)
        assertEquals(-40.0, sigma.poleAltitude, 1e-6)
        assertEquals(180.0, sigma.poleAzimuth, 1e-6)
    }

    @Test
    fun testTheEquatorHasTheCelestialPolesOnTheHorizon() {
        val north = reading(PlanningTestSupport.quito, origin, PolarAlignment.Hemisphere.NORTH)
        val south = reading(PlanningTestSupport.quito, origin, PolarAlignment.Hemisphere.SOUTH)
        assertEquals(0.0, north.poleAltitude, 1e-6)
        assertEquals(0.0, south.poleAltitude, 1e-6)
        assertEquals(0.0, north.poleAzimuth, 1e-6)
        assertEquals(180.0, south.poleAzimuth, 1e-6)
    }

    @Test
    fun testTheGeographicPolesHaveTheCelestialPoleAtTheZenith() {
        for ((latitude, hemisphere) in listOf(
            90.0 to PolarAlignment.Hemisphere.NORTH, -90.0 to PolarAlignment.Hemisphere.SOUTH,
        )) {
            val pole = PlanningTestSupport.Site("pole $latitude", latitude, 0.0)
            val result = reading(pole, origin, hemisphere)
            assertEquals(90.0, result.poleAltitude, 1e-6)
            assertTrue(result.angle.isFinite() && result.angle >= 0.0 && result.angle < 360.0)
            assertTrue(result.separationFromPole > 0.5 && result.separationFromPole < 1.3)
        }
    }

    // MARK: The reference stars

    @Test
    fun testReferenceStarsAreNamedAndNumbered() {
        val polaris = reading(boulder, origin, PolarAlignment.Hemisphere.NORTH)
        assertEquals("Polaris", polaris.referenceStarName)
        assertEquals(11_767, polaris.hipparcosNumber)
        val sigma = reading(paranal, origin, PolarAlignment.Hemisphere.SOUTH)
        assertEquals("σ Octantis", sigma.referenceStarName)
        assertEquals(104_382, sigma.hipparcosNumber)
    }

    @Test
    fun testReadingsWithTheSameValuesAreEqual() {
        val first = reading(boulder, origin, PolarAlignment.Hemisphere.NORTH)
        assertEquals(reading(boulder, origin, PolarAlignment.Hemisphere.NORTH), first)
        assertNotEquals(reading(boulder, origin, PolarAlignment.Hemisphere.NORTH, PolarAlignment.ScopeView.INVERTED), first)
        assertNotEquals(reading(boulder, origin.addingTimeInterval(60.0), PolarAlignment.Hemisphere.NORTH), first)
    }

    /** The astrometry in `PolarAlignment` is the Hipparcos data in the bundled `stars.json` (HYG v4.1). */
    @Test
    fun testAstrometryMatchesTheBundledStarCatalog() {
        val stream = assertNotNull(
            PolarAlignmentTests::class.java.getResourceAsStream("/net/kuecker/lodestar/kit/catalog/stars.json"),
            "stars.json is not among the bundled catalog resources",
        )
        val catalog = starCatalogJson.decodeFromString<StarCatalog>(stream.use { it.readBytes().decodeToString() })
        for (star in listOf(PolarAlignment.polaris, PolarAlignment.sigmaOctantis)) {
            val entry = assertNotNull(catalog.stars.firstOrNull { it.hip == star.hipparcosNumber }, star.name)
            val place = EquatorialCoordinate(entry.ra, entry.de)
            assertEquals(0.0, star.position.angularSeparation(place) * 3_600, 0.05, "${star.name}: arcseconds apart")
            assertEquals(assertNotNull(entry.pa), star.properMotion.raMilliarcsecondsPerYear, 0.005, star.name)
            assertEquals(assertNotNull(entry.pd), star.properMotion.decMilliarcsecondsPerYear, 0.005, star.name)
        }
        assertEquals(11_767, PolarAlignment.polaris.hipparcosNumber)
        assertEquals(104_382, PolarAlignment.sigmaOctantis.hipparcosNumber)
    }
}

/** The fields of the bundled `stars.json` this test reads. */
@Serializable
private data class StarCatalog(val stars: List<StarRecord>)

@Serializable
private data class StarRecord(val hip: Int? = null, val ra: Double, val de: Double, val pa: Double? = null, val pd: Double? = null)

private val starCatalogJson = Json { ignoreUnknownKeys = true }
