package net.kuecker.lodestar.kit.ephemeris

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests that exist only in the Kotlin port (the Swift suites do not cover them): the transcription of the
 * series tables, the serialized forms of the enums, the sampler's caches and the numerical search routines.
 */
class EphemerisApiTests {
    // MARK: Series tables

    @Test
    fun testPlanetaryTheoryHasAllTermsOfTheSwiftTables() {
        assertEquals(1002, PlanetaryTheory.termCount)
    }

    /**
     * The digests were computed from the numbers in `PlanetaryTheory.swift` and `LunarTheory.swift` (parsed with
     * Python, packed big endian in the order described at `tableDigest`); they change if any digit of any
     * coefficient, multiplier or row order differs.
     */
    @Test
    fun testSeriesTablesAreDigitForDigitTheSwiftTables() {
        assertEquals("0d591e17b9ef6c65d556a7e2161518acaca483a9e92d10f1895fd007fffd39a3", PlanetaryTheory.tableDigest)
        assertEquals("14885d8b8de0109710bf79c4d79667696e07bbff89cb689f22cc1351f35129f1", LunarTheory.tableDigest)
    }

    @Test
    fun testEarthAtJ2000IsWhereTheSunIsSeenOppositeToIt() {
        // At J2000.0 the Sun's geometric ecliptic longitude is 280.37° and its distance 0.98332 AU.
        val earth = PlanetaryTheory.heliocentricJ2000(PlanetaryTheory.Planet.EARTH, JulianDate.j2000)
        val ecliptic = Matrix3.rotationX(-AstroMath.radians(Obliquity.j2000)) * earth
        val longitude = AstroMath.normalizeDegrees(AstroMath.degrees(atan2(ecliptic.y, ecliptic.x)))
        assertEquals(280.37 - 180, longitude, 0.02)
        assertEquals(0.98332, earth.length, 0.0001)
        assertEquals(0.0, ecliptic.z, 1e-5)
    }

    @Test
    fun testLunarTheoryReproducesTheMeanLongitudeAtJ2000() {
        // At J2000.0 the Moon's mean longitude is 218.3164°; the periodic terms move it by at most ~6.3°.
        val moon = LunarTheory.geocentricEcliptic(0.0)
        assertTrue(abs(AstroMath.normalizeSignedDegrees(moon.longitude - 218.3164)) < 7.0)
        assertTrue(moon.distanceKilometers in 356_000.0..407_000.0)
        assertTrue(abs(moon.latitude) < 5.4)
    }

    // MARK: Enumerations

    private fun <T : Enum<T>> assertSerializesAsRawValue(
        serializer: KSerializer<T>,
        entries: List<T>,
        rawValue: (T) -> String,
    ) {
        for (entry in entries) {
            val data = Json.encodeToString(serializer, entry)
            assertEquals("\"${rawValue(entry)}\"", data)
            assertEquals(entry, Json.decodeFromString(serializer, data))
        }
        assertFailsWith<SerializationException> { Json.decodeFromString(serializer, "\"notAValue\"") }
    }

    @Test
    fun testEnumerationsSerializeAsTheirRawValues() {
        assertSerializesAsRawValue(MoonPhaseName.serializer(), MoonPhaseName.entries) { it.rawValue }
        assertSerializesAsRawValue(PrincipalMoonPhase.serializer(), PrincipalMoonPhase.entries) { it.rawValue }
        assertSerializesAsRawValue(AltitudeCrossing.Kind.serializer(), AltitudeCrossing.Kind.entries) { it.rawValue }
        assertSerializesAsRawValue(
            RiseTransitSet.Circumstance.serializer(), RiseTransitSet.Circumstance.entries,
        ) { it.rawValue }
        assertSerializesAsRawValue(SolarSystemBody.serializer(), SolarSystemBody.entries) { it.rawValue }
        assertEquals(
            listOf(
                "newMoon", "waxingCrescent", "firstQuarter", "waxingGibbous", "fullMoon", "waningGibbous",
                "lastQuarter", "waningCrescent",
            ),
            MoonPhaseName.entries.map { it.rawValue },
        )
        assertEquals(
            listOf("newMoon", "firstQuarter", "fullMoon", "lastQuarter"),
            PrincipalMoonPhase.entries.map { it.rawValue },
        )
        assertEquals(listOf("rising", "setting"), AltitudeCrossing.Kind.entries.map { it.rawValue })
        assertEquals(
            listOf("normal", "alwaysUp", "alwaysDown"), RiseTransitSet.Circumstance.entries.map { it.rawValue },
        )
    }

    @Test
    fun testRawValueLookups() {
        for (body in SolarSystemBody.entries) assertEquals(body, SolarSystemBody.fromRawValue(body.rawValue))
        for (name in MoonPhaseName.entries) assertEquals(name, MoonPhaseName.fromRawValue(name.rawValue))
        for (phase in PrincipalMoonPhase.entries) assertEquals(phase, PrincipalMoonPhase.fromRawValue(phase.rawValue))
        for (kind in AltitudeCrossing.Kind.entries) {
            assertEquals(kind, AltitudeCrossing.Kind.fromRawValue(kind.rawValue))
        }
        for (circumstance in RiseTransitSet.Circumstance.entries) {
            assertEquals(circumstance, RiseTransitSet.Circumstance.fromRawValue(circumstance.rawValue))
        }
        assertNull(SolarSystemBody.fromRawValue("pluto"))
        assertNull(SolarSystemBody.fromRawValue("Sun"))
        assertNull(MoonPhaseName.fromRawValue("full"))
        assertNull(PrincipalMoonPhase.fromRawValue(""))
        assertNull(AltitudeCrossing.Kind.fromRawValue("rise"))
        assertNull(RiseTransitSet.Circumstance.fromRawValue("always_up"))
    }

    // MARK: Photometry

    @Test
    fun testPhotometryPlanetEntryPointIsNullForTheSunAndTheMoon() {
        val jd = JulianDate(2_460_000.5)
        val vector = Vector3(1.0, 0.0, 0.0)
        assertNull(Photometry.planet(SolarSystemBody.SUN, vector, vector, 30.0, jd))
        assertNull(Photometry.planet(SolarSystemBody.MOON, vector, vector, 30.0, jd))
        for (body in SolarSystemBody.entries.filter { it.isPlanet }) {
            assertNotNull(Photometry.planet(body, vector * 5.0, vector * 4.0, 5.0, jd), "$body")
        }
    }

    // MARK: Sampler

    @Test
    fun testSamplerResultsDoNotDependOnTheOrderOfEvaluation() {
        val observer = Observer(GeographicLocation(35.0, -100.0, 500.0))
        val start = instantFromTimeIntervalSince1970(1_790_000_000.0).timeIntervalSinceReferenceDate
        val times = (0 until 400).map { start + it * 977.3 - 100_000 }
        val shuffled = times.shuffled(Random(1))
        val star = EquatorialCoordinate(213.9153, 19.1824)
        val targets = listOf(
            RiseSetSampler.Target.Body(SolarSystemBody.MOON),
            RiseSetSampler.Target.Body(SolarSystemBody.MARS),
            RiseSetSampler.Target.Body(SolarSystemBody.SUN),
            RiseSetSampler.Target.Fixed(star, ProperMotion(100.0, -50.0)),
        )
        for (target in targets) {
            val sequential = RiseSetSampler(target, observer)
            val expected = times.map { sequential.sample(it) }
            val random = RiseSetSampler(target, observer)
            val byTime = shuffled.associateWith { random.sample(it) }
            for ((index, time) in times.withIndex()) {
                assertEquals(expected[index], byTime[time], "$target at $time")
                assertEquals(expected[index], RiseSetSampler(target, observer).sample(time), "fresh $target at $time")
            }
            // The allocation-free accessors agree with the sample.
            for (time in times.take(50)) {
                val sample = random.sample(time)
                assertEquals(sample.centreAltitude, random.centreAltitude(time))
                assertEquals(sample.limbAltitude, random.limbAltitude(time))
            }
        }
    }

    @Test
    fun testSamplerHandlesTimesNearItsLimits() {
        // Node indices of the hourly grid exceed the Int range for times beyond about 7.7e12 s.
        val observer = Observer(GeographicLocation(10.0, 20.0))
        val targets = listOf(
            RiseSetSampler.Target.Body(SolarSystemBody.MOON),
            RiseSetSampler.Target.Body(SolarSystemBody.SATURN),
            RiseSetSampler.Target.Fixed(EquatorialCoordinate(10.0, 20.0), null),
        )
        for (target in targets) {
            val sampler = RiseSetSampler(target, observer)
            for (time in listOf(7.8e12, 9.9e12, -9.9e12, 1e13 - 1000, -1e13 + 1000)) {
                val sample = sampler.sample(time)
                assertTrue(sample.centreAltitude.isFinite() && abs(sample.centreAltitude) <= 90.0, "$target at $time")
            }
            for (time in listOf(1e13, -1e13, 1e300, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                val sample = sampler.sample(time)
                assertTrue(sample.centreAltitude.isNaN(), "$target at $time")
                assertEquals(0.0, sample.limbAllowance)
            }
        }
    }

    // MARK: Numerical search

    @Test
    fun testBrentRootAndGoldenSectionSearch() {
        val square = RiseSetProfile.RealFunction { it * it - 2 }
        assertEquals(sqrt(2.0), RiseSetProfile.root(square, 1.0, 2.0, -1.0, 2.0, 1e-9), 1e-8)
        val cosine = RiseSetProfile.RealFunction { cos(it) }
        assertEquals(PI / 2, RiseSetProfile.root(cosine, 0.0, 3.0, 1.0, cos(3.0), 1e-12), 1e-11)
        // An exact zero at an end of the bracket is returned as is.
        assertEquals(1.0, RiseSetProfile.root(RiseSetProfile.RealFunction { it - 1 }, 1.0, 5.0, 0.0, 4.0, 1e-6))
        assertEquals(5.0, RiseSetProfile.root(RiseSetProfile.RealFunction { it - 5 }, 1.0, 5.0, -4.0, 0.0, 1e-6))

        val parabola = RiseSetProfile.RealFunction { 5 - (it - 1.3) * (it - 1.3) }
        val peak = RiseSetProfile.maximum(parabola, 0.0, 3.0, 1e-9)
        assertEquals(1.3, peak.time, 1e-6)
        assertEquals(5.0, peak.value, 1e-9)
        // A maximum at an end of the interval ends up next to that end.
        val rising = RiseSetProfile.maximum(RiseSetProfile.RealFunction { it }, 0.0, 1.0, 1e-9)
        assertEquals(1.0, rising.time, 1e-6)
    }

    @Test
    fun testProfileSamplingGridAndSampleCountLimit() {
        val evaluator = RiseSetProfile.Evaluator { time -> RiseSetSample(time) }
        val profile = RiseSetProfile(0.0, 1000.0, 300.0, evaluator)
        assertEquals(listOf(0.0, 250.0, 500.0, 750.0, 1000.0), profile.times.toList())
        assertEquals(profile.times.toList(), profile.centreAltitudes.toList())
        assertEquals(profile.times.toList(), profile.limbAltitudes.toList())
        val coarse = RiseSetProfile(0.0, 1e9, 1.0, evaluator)
        assertEquals(RiseSetProfile.maximumSampleCount, coarse.times.size)
        assertEquals(1e9, coarse.times.last())
        // An empty or reversed interval, or an invalid step, gives the one sample at the start.
        assertEquals(listOf(5.0), RiseSetProfile(5.0, 5.0, 1.0, evaluator).times.toList())
        assertEquals(listOf(5.0), RiseSetProfile(5.0, 1.0, 1.0, evaluator).times.toList())
        assertEquals(listOf(5.0), RiseSetProfile(5.0, 10.0, 0.0, evaluator).times.toList())
        assertEquals(listOf(5.0), RiseSetProfile(5.0, 10.0, Double.NaN, evaluator).times.toList())
    }

    // MARK: Twilight

    @Test
    fun testNightIntervalOfExtremeDates() {
        val utc = ZoneOffset.UTC
        val reference = TwilightCalculator.nightInterval(instantFromTimeIntervalSinceReferenceDate(Double.NaN), utc)
        assertEquals(Instant.parse("2001-01-01T12:00:00Z"), reference.start)
        assertEquals(Instant.parse("2001-01-02T12:00:00Z"), reference.end)

        val future = TwilightCalculator.nightInterval(distantFuture, utc)
        assertEquals(Instant.parse("4001-01-01T12:00:00Z"), future.start)
        assertEquals(future, TwilightCalculator.nightInterval(Instant.MAX, utc))
        assertEquals(future, TwilightCalculator.nightInterval(instantFromTimeIntervalSinceReferenceDate(1e300), utc))

        val past = TwilightCalculator.nightInterval(distantPast, utc)
        assertEquals(86_400.0, past.duration)
        assertEquals(past, TwilightCalculator.nightInterval(Instant.MIN, utc))
        assertEquals(past, TwilightCalculator.nightInterval(instantFromTimeIntervalSinceReferenceDate(-1e300), utc))
    }

    @Test
    fun testNightIntervalFollowsTheLocalDate() {
        // 2026-10-17T03:00Z is the 17th in Sydney (UTC+11 in October) and the 16th in Denver (UTC-6).
        val instant = Instant.parse("2026-10-17T03:00:00Z")
        val sydney = TwilightCalculator.nightInterval(instant, java.time.ZoneId.of("Australia/Sydney"))
        assertEquals(Instant.parse("2026-10-17T01:00:00Z"), sydney.start)
        val denver = TwilightCalculator.nightInterval(instant, java.time.ZoneId.of("America/Denver"))
        assertEquals(Instant.parse("2026-10-16T18:00:00Z"), denver.start)
        assertEquals(Instant.parse("2026-10-17T18:00:00Z"), denver.end)
    }
}
