package net.kuecker.lodestar.kit.comets

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Selection of the bright comets and asteroids from a list of orbit records. */
class CometsSelectionTests {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val monthEnd = Instant.parse("2026-11-18T12:00:00Z")

    private fun fixtureComets(): List<SmallBody> = listOf(
        CometsTestData.cometHaleBopp, CometsTestData.cometTsuchinshanAtlas, CometsTestData.cometPonsBrooks,
        CometsTestData.cometHalley, CometsTestData.cometEncke, CometsTestData.cometBielaA, CometsTestData.cometPerrineMrkos,
        CometsTestData.cometSchwassmannWachmann3Bt, CometsTestData.cometIkeyaMurakamiB, CometsTestData.cometOumuamua,
        CometsTestData.cometBorisov, CometsTestData.cometAtlas3i, CometsTestData.cometSidingSpring,
        CometsTestData.cometAsteroidLike, CometsTestData.cometUnnamedPeriodic, CometsTestData.cometHartleyIras,
        CometsTestData.cometGehrels, CometsTestData.cometSchwassmannWachmann, CometsTestData.cometBernardinelliBernstein,
        CometsTestData.cometWierzchos, CometsTestData.cometLeonard,
    ).map { CometElementsParser.parseLine(it) }

    private fun fixtureAsteroids(): List<SmallBody> = listOf(
        CometsTestData.asteroidCeres, CometsTestData.asteroidVesta, CometsTestData.asteroidEros,
        CometsTestData.asteroidApophis, CometsTestData.asteroidBennu, CometsTestData.asteroidYr42024,
        CometsTestData.asteroidUnknownH, CometsTestData.asteroidNumberedUnnamed,
    ).map { MinorPlanetElementsParser.parseLine(it) }

    /** The magnitude of every sample of [body] (infinite where it cannot be computed or the elongation is too small). */
    private fun sampledMagnitudes(body: SmallBody, start: Instant, days: Double, count: Int, minimumElongation: Double): List<Double> =
        List(count) { index ->
            val julianDate = JulianDate(start).addingDays(if (count > 1) days * index / (count - 1) else 0.0)
            val state = SmallBodyEphemeris.state(body, if (index == 0) JulianDate(start) else julianDate)
            val magnitude = state?.apparentMagnitude
            if (state == null || magnitude == null || state.elongation < minimumElongation) Double.POSITIVE_INFINITY else magnitude
        }

    // MARK: Real records

    @Test
    fun brightCometsNowOnTheFixtureSet() {
        val comets = fixtureComets()
        val bright = SmallBodySelection.brightNow(comets, now, magnitudeLimit = 14.0)
        // On 2026 October 18 only 161P/Hartley-IRAS (9.7), P/2026 R2 (Leonard, 13.3) and 78P/Gehrels (13.9) are brighter than 14.
        assertEquals(listOf("comet.161P", "comet.P/2026 R2", "comet.78P"), bright.map { it.body.id })
        assertEquals(9.72, bright[0].peakMagnitude, 0.01)
        assertEquals(13.27, bright[1].peakMagnitude, 0.01)
        assertEquals(now, bright[0].peakDate.let { Instant.ofEpochSecond(it.epochSecond) })
        assertTrue(bright.all { it.peak.elongation >= 25.0 })
        assertEquals(bright.map { it.peakMagnitude }, bright.map { it.peakMagnitude }.sorted())
        assertEquals(110.3, bright[0].peak.elongation, 0.1)
    }

    @Test
    fun brightNowAgreesWithABruteForceScanOfAllRecords() {
        val bodies = fixtureComets() + fixtureAsteroids()
        for (limit in listOf(9.0, 12.0, 15.0, 30.0)) {
            val selected = SmallBodySelection.brightNow(bodies, now, magnitudeLimit = limit, minimumElongation = 20.0)
            val expected = bodies.mapNotNull { body ->
                val state = SmallBodyEphemeris.state(body, JulianDate(now)) ?: return@mapNotNull null
                val magnitude = state.apparentMagnitude ?: return@mapNotNull null
                if (magnitude <= limit && state.elongation >= 20.0) Pair(body.id, magnitude) else null
            }.sortedWith(compareBy({ it.second }, { it.first }))
            assertEquals(expected.map { it.first }, selected.map { it.body.id }, "limit $limit")
            assertEquals(expected.map { it.second }, selected.map { it.peakMagnitude }, "limit $limit")
        }
    }

    @Test
    fun brightestDuringAMonthOnTheFixtureSet() {
        val bodies = fixtureComets() + fixtureAsteroids()
        val month = SmallBodySelection.brightest(bodies, DateInterval(now, monthEnd), magnitudeLimit = 10.0)
        // Vesta (6.4 at opposition on October 18) leads; Ceres, Pallas-like asteroids and 161P follow.
        assertEquals("asteroid.4", month.first().body.id)
        assertTrue(month.first().peakMagnitude < 6.6)
        assertTrue(month.any { it.body.id == "comet.161P" })
        assertEquals(month.map { it.peakMagnitude }, month.map { it.peakMagnitude }.sorted())
        for (entry in month) {
            assertTrue(entry.peakMagnitude <= 10.0)
            assertTrue(entry.peakDate >= now && entry.peakDate <= monthEnd, entry.body.id)
            assertTrue(entry.peak.elongation >= 25.0)
        }
        // The peak is at least as bright as the state at the first and last instant.
        for (entry in month) {
            for (edge in listOf(now, monthEnd)) {
                val state = assertNotNull(SmallBodyEphemeris.state(entry.body, JulianDate(edge)))
                if (state.elongation >= 25.0) assertTrue(entry.peakMagnitude <= assertNotNull(state.apparentMagnitude) + 1e-9, entry.body.id)
            }
        }
    }

    @Test
    fun peakBetweenSamplesIsRefined() {
        // C/2023 A3 brightened towards the Sun in early October 2024; with daily samples the peak falls between two of them.
        val case = CometsHorizonsData.c2023a3
        val orbit = assertNotNull(
            OrbitalElements.create(
                case.perihelionDistance, case.eccentricity, case.inclination, case.argumentOfPerihelion,
                case.ascendingNode, case.perihelionTimeTDB, case.epochTDB,
            ),
        )
        val atlas = assertNotNull(
            SmallBody.create(
                "C/2023 A3", "", SmallBodyKind.COMET, null, orbit,
                SmallBodyPhotometry.Comet(assertNotNull(CometMagnitudes.create(case.absoluteMagnitude, case.slope))),
            ),
        )
        val start = Instant.parse("2024-10-04T06:30:00Z")
        val interval = DateInterval(start, 26.0 * 86_400.0)
        val result = SmallBodySelection.brightest(listOf(atlas), interval, magnitudeLimit = 8.0, minimumElongation = 10.0)
        val peak = result.single()
        // Dense scan, every 10 minutes.
        var bestMagnitude = Double.POSITIVE_INFINITY
        var bestTime = start
        var seconds = 0.0
        while (seconds <= 26.0 * 86_400.0) {
            val instant = start.addingTimeInterval(seconds)
            val state = assertNotNull(SmallBodyEphemeris.state(atlas, JulianDate(instant)))
            val magnitude = assertNotNull(state.apparentMagnitude)
            if (state.elongation >= 10.0 && magnitude < bestMagnitude) {
                bestMagnitude = magnitude
                bestTime = instant
            }
            seconds += 600.0
        }
        assertEquals(bestMagnitude, peak.peakMagnitude, 0.002)
        assertTrue(abs(peak.peakDate.timeIntervalSince(bestTime)) < 6 * 3600.0, "peak ${peak.peakDate}, dense $bestTime")
        // It is brighter than every daily sample.
        val daily = sampledMagnitudes(atlas, start, 26.0, 27, 10.0)
        assertTrue(peak.peakMagnitude <= (daily.minOrNull() ?: 0.0) + 1e-12)
    }

    // MARK: Synthetic records

    @Test
    fun prefilterBoundIsNeverBrighterThanTheTrueMinimum() {
        val bodies = SyntheticOrbits(20_261_018).bodies(300)
        var excluded = 0
        for (offset in listOf(0.0, 400.0, -700.0)) {
            val start = JulianDate(2_461_330.5 + offset)
            val end = start.addingDays(40.0)
            for (body in bodies) {
                val bound = SmallBodySelection.brightestPossible(body, start.tt, end.tt)
                var minimum = Double.POSITIVE_INFINITY
                for (step in 0..80) {
                    val state = SmallBodyEphemeris.state(body, start.addingDays(step * 0.5)) ?: continue
                    minimum = min(minimum, state.apparentMagnitude ?: Double.POSITIVE_INFINITY)
                }
                assertTrue(bound <= minimum, "${body.id} from $offset: bound $bound, true minimum $minimum")
                if (bound > 10.0) excluded += 1
            }
        }
        // The bound is useful: most of the records are excluded without computing a position.
        assertTrue(excluded > 300, "only $excluded of 900 excluded")
    }

    /** The lower bound of the magnitude for distances from the Sun between [nearest] and [farthest] (AU), computed independently. */
    private fun bound(absolute: Double, slope: Double?, nearest: Double, farthest: Double): Double {
        val earthDistance = kotlin.math.max(0.001, kotlin.math.max(nearest - 1.0169, 0.9831 - farthest))
        return if (slope != null) {
            absolute + 5 * kotlin.math.log10(earthDistance) + slope * kotlin.math.log10(nearest) - 0.35
        } else {
            absolute + 5 * kotlin.math.log10(nearest * earthDistance) - 0.35
        }
    }

    @Test
    fun prefilterBoundForSpecialOrbits() {
        val orbit = assertNotNull(OrbitalElements.create(0.5, 1.0, 20.0, 30.0, 40.0, 2_461_340.0))
        val photometry = SmallBodyPhotometry.Comet(assertNotNull(CometMagnitudes.create(8.0, 10.0)))
        val parabolic = assertNotNull(SmallBody.create("P", "", SmallBodyKind.COMET, null, orbit, photometry))
        // Perihelion (r = 0.5) lies inside the window; the ends are farther from the Sun.
        val inside = SmallBodySelection.brightestPossible(parabolic, 2_461_330.0, 2_461_350.0)
        val farthest = kotlin.math.max(
            assertNotNull(OrbitPropagator.heliocentricDistance(orbit, 2_461_330.0)),
            assertNotNull(OrbitPropagator.heliocentricDistance(orbit, 2_461_350.0)),
        )
        assertEquals(bound(8.0, 10.0, 0.5, farthest), inside, 1e-9)
        // After perihelion r only grows: the distance at the start is the smallest.
        val after = SmallBodySelection.brightestPossible(parabolic, 2_461_345.0, 2_461_350.0)
        val rStart = assertNotNull(OrbitPropagator.heliocentricDistance(orbit, 2_461_345.0))
        val rEnd = assertNotNull(OrbitPropagator.heliocentricDistance(orbit, 2_461_350.0))
        assertEquals(bound(8.0, 10.0, rStart, rEnd), after, 1e-9)
        // Far from the Sun the Earth is at least r - 1.0169 away.
        val far = SmallBodySelection.brightestPossible(parabolic, 2_461_340.0 + 4_000.0, 2_461_340.0 + 4_010.0)
        val rFar = assertNotNull(OrbitPropagator.heliocentricDistance(orbit, 2_461_340.0 + 4_000.0))
        assertTrue(rFar > 10.0 && far > 20.0, "far $far at $rFar")
        // No photometry: nothing to select.
        val bare = assertNotNull(SmallBody.create("B", "", SmallBodyKind.COMET, null, orbit, null))
        assertEquals(Double.POSITIVE_INFINITY, SmallBodySelection.brightestPossible(bare, 2_461_330.0, 2_461_350.0))
        // Beyond the span of an unbound orbit no bound can be given.
        assertEquals(Double.NEGATIVE_INFINITY, SmallBodySelection.brightestPossible(parabolic, 2_461_340.0 + 60_000.0, 2_461_340.0 + 60_010.0))
        assertEquals(Double.NEGATIVE_INFINITY, SmallBodySelection.brightestPossible(parabolic, 2_461_340.0, 2_461_340.0 + 60_000.0))
        // An elliptic orbit that completes several revolutions in the window passes perihelion and aphelion.
        val short = assertNotNull(OrbitalElements.create(1.0, 0.1, 5.0, 0.0, 0.0, 2_461_000.0)) // period 1.2 years
        val asteroid = assertNotNull(
            SmallBody.create("A", "", SmallBodyKind.ASTEROID, null, short, SmallBodyPhotometry.Asteroid(assertNotNull(AsteroidMagnitudes.create(10.0, 0.15)))),
        )
        val aphelion = assertNotNull(short.aphelionDistanceAU)
        assertEquals(1.222, aphelion, 1e-3)
        assertEquals(bound(10.0, null, 1.0, aphelion), SmallBodySelection.brightestPossible(asteroid, 2_461_100.0, 2_462_100.0), 1e-9)
        // A window that contains neither extremum is bounded by its end points.
        val r0 = assertNotNull(OrbitPropagator.heliocentricDistance(short, 2_461_050.0))
        val r1 = assertNotNull(OrbitPropagator.heliocentricDistance(short, 2_461_060.0))
        assertEquals(bound(10.0, null, min(r0, r1), kotlin.math.max(r0, r1)), SmallBodySelection.brightestPossible(asteroid, 2_461_050.0, 2_461_060.0), 1e-9)
        // Perihelion only (aphelion is half a period later, outside the window).
        val periodDays = assertNotNull(short.periodDays)
        val aroundPerihelion = SmallBodySelection.brightestPossible(asteroid, 2_461_000.0 + periodDays - 5, 2_461_000.0 + periodDays + 5)
        val rBefore = assertNotNull(OrbitPropagator.heliocentricDistance(short, 2_461_000.0 + periodDays - 5))
        assertEquals(bound(10.0, null, 1.0, rBefore), aroundPerihelion, 1e-9)
        // Negative slope: the farthest distance gives the brightest value.
        val inverted = SmallBodyPhotometry.Comet(assertNotNull(CometMagnitudes.create(8.0, -2.0)))
        val odd = assertNotNull(SmallBody.create("N", "", SmallBodyKind.COMET, null, orbit, inverted))
        val oddBound = SmallBodySelection.brightestPossible(odd, 2_461_345.0, 2_461_350.0)
        val earthDistance = kotlin.math.max(0.001, kotlin.math.max(rStart - 1.0169, 0.9831 - rEnd))
        assertEquals(8.0 + 5 * kotlin.math.log10(earthDistance) - 2.0 * kotlin.math.log10(rEnd) - 0.35, oddBound, 1e-9)
    }

    @Test
    fun selectionEqualsABruteForceScan() {
        val bodies = SyntheticOrbits(777).bodies(400)
        val interval = DateInterval(now, monthEnd)
        val days = interval.duration / 86_400.0
        val count = 32
        for (limit in listOf(8.0, 10.0, 12.0)) {
            val selected = SmallBodySelection.brightest(bodies, interval, magnitudeLimit = limit, maximumCount = 1000)
            val byId = selected.associateBy { it.body.id }
            var sampledQualifiers = 0
            for (body in bodies) {
                val sampled = sampledMagnitudes(body, now, days, count, 25.0).minOrNull() ?: Double.POSITIVE_INFINITY
                val entry = byId[body.id]
                if (sampled <= limit) {
                    sampledQualifiers += 1
                    val found = assertNotNull(entry, "${body.id} sampled at $sampled")
                    assertTrue(found.peakMagnitude <= sampled + 1e-9, "${body.id}: ${found.peakMagnitude} vs $sampled")
                }
                if (entry != null) {
                    assertTrue(entry.peakMagnitude <= limit, body.id)
                    assertTrue(entry.peak.elongation >= 25.0, body.id)
                    // The refined peak is not brighter than a dense scan finds (hourly samples, 0.005 mag margin).
                    var dense = Double.POSITIVE_INFINITY
                    for (hour in 0..(days * 24).toInt()) {
                        val state = SmallBodyEphemeris.state(body, JulianDate(now).addingDays(hour / 24.0)) ?: continue
                        if (state.elongation >= 25.0) dense = min(dense, state.apparentMagnitude ?: Double.POSITIVE_INFINITY)
                    }
                    assertTrue(entry.peakMagnitude >= dense - 0.01, "${body.id}: ${entry.peakMagnitude} vs dense $dense")
                }
            }
            assertTrue(selected.size >= sampledQualifiers)
        }
    }

    @Test
    fun resultsAreSortedCappedAndFiltered() {
        val bodies = SyntheticOrbits(4242).bodies(300)
        val interval = DateInterval(now, monthEnd)
        val all = SmallBodySelection.brightest(bodies, interval, magnitudeLimit = 12.0, maximumCount = 1000)
        assertTrue(all.size > 10, "only ${all.size} bright bodies")
        assertEquals(all.map { it.peakMagnitude }, all.map { it.peakMagnitude }.sorted())
        assertEquals(all.size, all.map { it.body.id }.toSet().size)
        val capped = SmallBodySelection.brightest(bodies, interval, magnitudeLimit = 12.0, maximumCount = 5)
        assertEquals(all.take(5).map { it.body.id }, capped.map { it.body.id })
        assertEquals(emptyList(), SmallBodySelection.brightest(bodies, interval, magnitudeLimit = 12.0, maximumCount = 0))
        assertEquals(emptyList(), SmallBodySelection.brightest(bodies, interval, magnitudeLimit = 12.0, maximumCount = -3))
        val comets = SmallBodySelection.brightest(bodies, interval, magnitudeLimit = 12.0, kind = SmallBodyKind.COMET, maximumCount = 1000)
        val asteroids = SmallBodySelection.brightest(bodies, interval, magnitudeLimit = 12.0, kind = SmallBodyKind.ASTEROID, maximumCount = 1000)
        assertTrue(comets.all { it.body.kind == SmallBodyKind.COMET })
        assertTrue(asteroids.all { it.body.kind == SmallBodyKind.ASTEROID })
        assertEquals(all.map { it.body.id }.toSet(), (comets + asteroids).map { it.body.id }.toSet())
        // A stricter elongation limit never adds bodies.
        val wide = SmallBodySelection.brightest(bodies, interval, magnitudeLimit = 12.0, minimumElongation = 90.0, maximumCount = 1000)
        assertTrue(wide.map { it.body.id }.toSet().subtract(all.map { it.body.id }.toSet()).isEmpty())
        assertTrue(wide.all { it.peak.elongation >= 90.0 })
        assertEquals(emptyList(), SmallBodySelection.brightest(bodies, interval, magnitudeLimit = 12.0, minimumElongation = 180.0, maximumCount = 1000))
        assertEquals(emptyList(), SmallBodySelection.brightest(bodies, interval, magnitudeLimit = -30.0, maximumCount = 1000))
    }

    @Test
    fun degenerateArguments() {
        val bodies = fixtureComets()
        val interval = DateInterval(now, monthEnd)
        assertEquals(emptyList(), SmallBodySelection.brightest(emptyList(), interval))
        assertEquals(emptyList(), SmallBodySelection.brightest(bodies, interval, magnitudeLimit = Double.NaN))
        assertEquals(emptyList(), SmallBodySelection.brightest(bodies, interval, magnitudeLimit = Double.POSITIVE_INFINITY.let { Double.NaN }))
        assertEquals(emptyList(), SmallBodySelection.brightest(bodies, interval, minimumElongation = Double.NaN))
        assertEquals(emptyList(), SmallBodySelection.brightNow(bodies, now, magnitudeLimit = Double.NaN))
        // A zero-length interval is the same as "now".
        val now = SmallBodySelection.brightNow(bodies, now, magnitudeLimit = 14.0)
        val zero = SmallBodySelection.brightest(bodies, DateInterval(this.now, 0.0), magnitudeLimit = 14.0)
        assertEquals(now.map { it.body.id }, zero.map { it.body.id })
        assertEquals(now.map { it.peakMagnitude }, zero.map { it.peakMagnitude })
        // An elongation limit above 180 is clamped to 180: nothing qualifies.
        assertEquals(emptyList(), SmallBodySelection.brightNow(bodies, this.now, magnitudeLimit = 30.0, minimumElongation = 500.0))
        // A negative elongation limit is clamped to 0.
        assertTrue(SmallBodySelection.brightNow(bodies, this.now, magnitudeLimit = 30.0, minimumElongation = -50.0).isNotEmpty())
        // Records without photometry are skipped.
        val orbit = assertNotNull(OrbitalElements.create(0.3, 1.0, 20.0, 30.0, 40.0, 2_461_330.0))
        val bare = assertNotNull(SmallBody.create("B", "", SmallBodyKind.COMET, null, orbit, null))
        assertEquals(emptyList(), SmallBodySelection.brightNow(listOf(bare), this.now, magnitudeLimit = 99.0))
        // A long interval is sampled coarsely but still works.
        val year = SmallBodySelection.brightest(bodies, DateInterval(this.now, 365.0 * 86_400.0), magnitudeLimit = 10.0)
        assertTrue(year.isNotEmpty())
        assertEquals(64, SmallBodySelection.maximumSampleCount)
    }

    @Test
    fun peakStateIsConsistentWithTheBody() {
        val bodies = fixtureAsteroids()
        val result = SmallBodySelection.brightest(bodies, DateInterval(now, monthEnd), magnitudeLimit = 11.0)
        assertTrue(result.isNotEmpty())
        for (entry in result) {
            assertEquals(entry.body, entry.peak.body)
            val again = assertNotNull(SmallBodyEphemeris.state(entry.body, entry.peak.julianDate))
            assertEquals(entry.peak, again)
            assertEquals(entry.peak.apparentMagnitude, entry.peakMagnitude)
            assertEquals(entry.peak.julianDate.date, entry.peakDate)
        }
        assertNull(result.firstOrNull { it.body.id == "asteroid.1991 TT" }) // no H, no photometry
    }

    // MARK: Cost

    @Test
    fun thousandsOfRecordsAreFast() {
        val bodies = SyntheticOrbits(99).bodies(5_000)
        val started = System.nanoTime()
        val month = SmallBodySelection.brightest(bodies, DateInterval(now, monthEnd), magnitudeLimit = 10.0)
        val monthSeconds = (System.nanoTime() - started) / 1e9
        val startedNow = System.nanoTime()
        val current = SmallBodySelection.brightNow(bodies, now, magnitudeLimit = 10.0)
        val nowSeconds = (System.nanoTime() - startedNow) / 1e9
        assertTrue(month.isNotEmpty() && current.isNotEmpty())
        assertTrue(monthSeconds < 3.0, "5000 records over a month took $monthSeconds s")
        assertTrue(nowSeconds < 1.5, "5000 records now took $nowSeconds s")
    }

    @Test
    fun compareIdentifiersUsesScalarOrder() {
        // Equal magnitudes are ordered by identifier; identifiers are compared scalar by scalar.
        val orbit = assertNotNull(OrbitalElements.create(2.0, 0.1, 5.0, 10.0, 20.0, 2_461_330.0))
        val photometry = SmallBodyPhotometry.Comet(assertNotNull(CometMagnitudes.create(5.0, 10.0)))
        val bodies = listOf("C/B", "C/A", "C/AA", "C/é").map { assertNotNull(SmallBody.create(it, "", SmallBodyKind.COMET, null, orbit, photometry)) }
        val result = SmallBodySelection.brightNow(bodies, now, magnitudeLimit = 30.0, minimumElongation = 0.0)
        assertEquals(listOf("comet.C/A", "comet.C/AA", "comet.C/B", "comet.C/é"), result.map { it.body.id })
    }
}
