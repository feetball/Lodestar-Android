package net.kuecker.lodestar.kit.planning

import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kotlin-only checks of the port itself (not in the Swift test suite): JSON shapes, call forms, edge cases. */
class PlanningApiTests {
    private val boulder = PlanningTestSupport.boulder

    @Test
    fun testEnumsSerializeAsTheirSwiftRawValues() {
        assertEquals("\"astronomical\"", Json.encodeToString(DarknessKind.ASTRONOMICAL))
        assertEquals("\"moonFree\"", Json.encodeToString(DarknessKind.MOON_FREE))
        assertEquals("\"north\"", Json.encodeToString(PolarAlignment.Hemisphere.NORTH))
        assertEquals("\"south\"", Json.encodeToString(PolarAlignment.Hemisphere.SOUTH))
        assertEquals("\"naked\"", Json.encodeToString(PolarAlignment.ScopeView.NAKED))
        assertEquals("\"inverted\"", Json.encodeToString(PolarAlignment.ScopeView.INVERTED))
        assertEquals(DarknessKind.MOON_FREE, Json.decodeFromString<DarknessKind>("\"moonFree\""))
        assertEquals(PolarAlignment.ScopeView.INVERTED, Json.decodeFromString<PolarAlignment.ScopeView>("\"inverted\""))
    }

    @Test
    fun testRawValueLookups() {
        for (kind in DarknessKind.entries) assertEquals(kind, DarknessKind.fromRawValue(kind.rawValue))
        for (hemisphere in PolarAlignment.Hemisphere.entries) {
            assertEquals(hemisphere, PolarAlignment.Hemisphere.fromRawValue(hemisphere.rawValue))
        }
        for (view in PolarAlignment.ScopeView.entries) assertEquals(view, PolarAlignment.ScopeView.fromRawValue(view.rawValue))
        assertNull(DarknessKind.fromRawValue("moon_free"))
        assertNull(DarknessKind.fromRawValue("MOON_FREE"))
        assertNull(PolarAlignment.Hemisphere.fromRawValue("East"))
        assertNull(PolarAlignment.ScopeView.fromRawValue(""))
        assertFailsWith<kotlinx.serialization.SerializationException> { Json.decodeFromString<DarknessKind>("\"civil\"") }
    }

    @Test
    fun testHemisphereOfANonNumberCountsAsNorthLikeSwift() {
        assertEquals(PolarAlignment.Hemisphere.NORTH, PolarAlignment.Hemisphere.fromLatitude(Double.NaN))
        assertEquals(PolarAlignment.Hemisphere.SOUTH, PolarAlignment.Hemisphere.fromLatitude(Double.NEGATIVE_INFINITY))
        assertEquals(PolarAlignment.Hemisphere.NORTH, PolarAlignment.Hemisphere.fromLatitude(Double.POSITIVE_INFINITY))
    }

    @Test
    fun testIntervalSetTreatsTheSaturatedInstantsAsInfiniteDates() {
        val origin = instantFromTimeIntervalSinceReferenceDate(800_000_000.0)
        val finite = DateInterval(origin, origin.addingTimeInterval(10.0))
        // Swift's distantPast and distantFuture are finite dates and stay usable.
        val everything = DateInterval(distantPast, distantFuture)
        assertEquals(
            listOf(everything),
            IntervalSet.normalized(listOf(DateInterval(distantPast, origin), DateInterval(origin, distantFuture), finite)),
        )
        val unbounded = DateInterval(instantFromTimeIntervalSinceReferenceDate(Double.NEGATIVE_INFINITY), origin)
        assertEquals(listOf(finite), IntervalSet.normalized(listOf(unbounded, finite)))
        assertEquals(0.0, IntervalSet.totalDuration(listOf(unbounded)))
        assertTrue(IntervalSet.intersection(listOf(unbounded), listOf(finite)).isEmpty())
    }

    @Test
    fun testDarknessSamplerSpacesFramesEvenlyAndCapsTheirNumber() {
        val observer = boulder.observer
        val origin = instantFromTimeIntervalSinceReferenceDate(800_000_000.0)
        val night = DateInterval(origin, 11.4 * 3_600)
        val long = DateInterval(origin.addingTimeInterval(1e6), 100.0 * 86_400)
        val sampler = DarknessSampler(observer, listOf(long, night, night))
        assertEquals(2, sampler.stretches.size)
        val (first, second) = sampler.stretches
        assertEquals(night, first.interval)
        assertEquals(69, first.frames.size - 1) // ceil(41 040 / 600) gaps
        assertTrue(first.step <= DarknessSampler.maximumStep)
        assertEquals(long, second.interval)
        assertEquals(DarknessSampler.maximumGapCount + 1, second.frames.size)
        assertEquals(long.duration / DarknessSampler.maximumGapCount, second.step, 1e-9)
        assertEquals(0.0, second.frames.last().date.timeIntervalSince(long.end), 1e-3)
        assertTrue(DarknessSampler(observer, emptyList()).stretches.isEmpty())
    }

    @Test
    fun testSamplesThatAreNotFiniteCountAsFarBelowTheHorizonAndOthersAreClamped() {
        val night = DateInterval(instantFromTimeIntervalSinceReferenceDate(800_000_000.0), 3_600.0)
        val sampler = DarknessSampler(boulder.observer, listOf(night))
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, -5.0)) {
            val sines = sampler.profile { value }.stretches.single().altitude.sines
            assertTrue(sines.all { it == -1.0 }, "$value")
        }
        assertTrue(sampler.profile { 5.0 }.stretches.single().altitude.sines.all { it == 1.0 })
        assertTrue(sampler.profile { 0.25 }.stretches.single().altitude.sines.all { it == 0.25 })
    }

    @Test
    fun testVisibilityOverloadsAcceptTheirDocumentedCallForms() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 10, 25)
        val observer = boulder.observer
        val window = assertNotNull(summary.astronomicalDarknessIntervals.firstOrNull())
        val target = EquatorialCoordinate(10.684, 41.269)
        val motion = ProperMotion(10.0, -5.0)
        val position: (SkyFrame) -> HorizontalCoordinate = { it.horizontal(target, null, false) }

        val byPosition = NightPlanner.visibility(position, summary)
        val byPositionInWindow = NightPlanner.visibility(position, observer, window)
        val byBody = NightPlanner.visibility(SolarSystemBody.JUPITER, summary, DarknessKind.ASTRONOMICAL, 5.0)
        val byBodyInWindow = NightPlanner.visibility(SolarSystemBody.JUPITER, observer, window, 5.0)
        val batch = NightPlanner.visibility(listOf(target), summary = summary)
        val batchWithMotion = NightPlanner.visibility(listOf(target), listOf(motion), summary, DarknessKind.MOON_FREE, 30.0)
        val batchInWindow = NightPlanner.visibility(listOf(target), observer = observer, interval = window)
        val batchInWindowWithMotion = NightPlanner.visibility(listOf(target), listOf(motion), observer, window, 15.0)

        assertEquals(20.0, byPosition.minimumAltitude)
        assertEquals(20.0, byPositionInWindow.minimumAltitude)
        assertEquals(5.0, byBody.minimumAltitude)
        assertEquals(5.0, byBodyInWindow.minimumAltitude)
        assertEquals(1, batch.size)
        assertEquals(30.0, batchWithMotion.single().minimumAltitude)
        assertEquals(20.0, batchInWindow.single().minimumAltitude)
        assertEquals(15.0, batchInWindowWithMotion.single().minimumAltitude)
        // The batch path and the position path judge the same dark time.
        assertEquals(byPosition.hoursAboveMinimum, batch.single().hoursAboveMinimum, 0.005)
        assertEquals(byPosition.hoursAboveMinimum, byPositionInWindow.hoursAboveMinimum, 1e-6)
    }

    @Test
    fun testAltitudeCurveOverloadsAcceptTheirDocumentedCallForms() {
        val interval = DateInterval(instantFromTimeIntervalSinceReferenceDate(800_000_000.0), 7_200.0)
        val observer = boulder.observer
        val place = EquatorialCoordinate(83.822_083, -5.391_111)
        val byPosition = NightPlanner.altitudeCurve(observer, interval, 1_800.0) { it.horizontal(place, null, false) }
        val byDefaultStep = NightPlanner.altitudeCurve(observer, interval) { it.horizontal(place, null, false) }
        val byPlace = NightPlanner.altitudeCurve(place, observer = observer, interval = interval, step = 1_800.0)
        val byBody = NightPlanner.altitudeCurve(SolarSystemBody.MOON, observer, interval)
        assertEquals(5, byPosition.size)
        assertEquals(13, byDefaultStep.size)
        assertEquals(byPosition, byPlace)
        assertEquals(13, byBody.size)
        assertEquals(interval.start, byBody.first().date)
        assertEquals(interval.end, byBody.last().date)
    }

    @Test
    fun testPolarScopeReadingDerivedValuesFollowTheirDefinitions() {
        val reading = PolarScopeReading(
            "Polaris", 11_767, PolarAlignment.Hemisphere.NORTH, PolarAlignment.ScopeView.NAKED,
            hourAngle = -90.0, separationFromPole = 0.6, angle = 315.0, poleAltitude = 40.0, poleAzimuth = 0.0,
        )
        assertEquals(10.5, reading.clockPosition)
        assertEquals(reading, reading.copy())
        assertTrue(reading != reading.copy(angle = 316.0))
    }

    @Test
    fun testNightSummaryDarknessSelectsTheMatchingIntervals() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 6, 21)
        assertEquals(summary.astronomicalDarknessIntervals, summary.darkness(DarknessKind.ASTRONOMICAL))
        assertEquals(summary.darknessIntervals, summary.darkness(DarknessKind.MOON_FREE))
        assertEquals(7, summary.planets.size)
        assertEquals(
            IntervalSet.totalDuration(summary.darknessIntervals) / 3_600, summary.moonFreeDarkHours,
        )
    }
}
