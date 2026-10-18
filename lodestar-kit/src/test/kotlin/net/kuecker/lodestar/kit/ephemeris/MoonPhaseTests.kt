package net.kuecker.lodestar.kit.ephemeris

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MoonPhaseTests {
    @Serializable
    private class PhaseRecord(val phase: String, val unix: Double) {
        val kind: PrincipalMoonPhase?
            get() = when (phase) {
                "new" -> PrincipalMoonPhase.NEW_MOON
                "first_quarter" -> PrincipalMoonPhase.FIRST_QUARTER
                "full" -> PrincipalMoonPhase.FULL_MOON
                "last_quarter" -> PrincipalMoonPhase.LAST_QUARTER
                else -> null
            }
    }

    @Serializable
    private class SampleRecord(
        val utc: String,
        val ut1Jd: Double,
        val ttJd: Double,
        val illuminatedFraction: Double,
        val phaseAngleDeg: Double,
        val eclipticElongationDeg: Double,
        val ageDays: Double,
    )

    @Serializable
    private class File(val phases: List<PhaseRecord>, val samples: List<SampleRecord>)

    private fun fixture(): File {
        val file = EphemerisFixtureStore.load<File>("moon_phases.json")
        assertTrue(file.phases.size > 240, "2024–2028 holds about 248 principal phases")
        assertEquals(30, file.samples.size)
        return file
    }

    // MARK: Names

    @Test
    fun testPhaseNamesFollowTheElongation() {
        val expectations: List<Pair<Double, MoonPhaseName>> = listOf(
            0.0 to MoonPhaseName.NEW_MOON, 11.2 to MoonPhaseName.NEW_MOON, 11.25 to MoonPhaseName.WAXING_CRESCENT,
            45.0 to MoonPhaseName.WAXING_CRESCENT, 78.7 to MoonPhaseName.WAXING_CRESCENT,
            78.75 to MoonPhaseName.FIRST_QUARTER, 90.0 to MoonPhaseName.FIRST_QUARTER,
            101.2 to MoonPhaseName.FIRST_QUARTER, 101.25 to MoonPhaseName.WAXING_GIBBOUS,
            135.0 to MoonPhaseName.WAXING_GIBBOUS, 168.7 to MoonPhaseName.WAXING_GIBBOUS,
            168.75 to MoonPhaseName.FULL_MOON, 180.0 to MoonPhaseName.FULL_MOON, 191.2 to MoonPhaseName.FULL_MOON,
            191.25 to MoonPhaseName.WANING_GIBBOUS, 225.0 to MoonPhaseName.WANING_GIBBOUS,
            258.7 to MoonPhaseName.WANING_GIBBOUS, 258.75 to MoonPhaseName.LAST_QUARTER,
            270.0 to MoonPhaseName.LAST_QUARTER, 281.2 to MoonPhaseName.LAST_QUARTER,
            281.25 to MoonPhaseName.WANING_CRESCENT, 315.0 to MoonPhaseName.WANING_CRESCENT,
            348.7 to MoonPhaseName.WANING_CRESCENT, 348.75 to MoonPhaseName.NEW_MOON,
            359.999 to MoonPhaseName.NEW_MOON,
            // Wrapped and invalid angles.
            360.0 to MoonPhaseName.NEW_MOON, -5.0 to MoonPhaseName.NEW_MOON, -45.0 to MoonPhaseName.WANING_CRESCENT,
            450.0 to MoonPhaseName.FIRST_QUARTER, Double.NaN to MoonPhaseName.NEW_MOON,
            Double.POSITIVE_INFINITY to MoonPhaseName.NEW_MOON,
        )
        for ((elongation, name) in expectations) {
            assertEquals(name, MoonPhaseName.fromElongation(elongation), "elongation $elongation")
        }
        // Each principal wedge spans 22.5°, each crescent or gibbous wedge 67.5°.
        val counts = HashMap<MoonPhaseName, Int>()
        for (tenth in 0 until 3600) {
            val name = MoonPhaseName.fromElongation(tenth.toDouble() / 10)
            counts[name] = (counts[name] ?: 0) + 1
        }
        for (name in listOf(
            MoonPhaseName.NEW_MOON, MoonPhaseName.FIRST_QUARTER, MoonPhaseName.FULL_MOON, MoonPhaseName.LAST_QUARTER,
        )) {
            assertEquals(225, counts[name], "$name")
        }
        for (name in listOf(
            MoonPhaseName.WAXING_CRESCENT, MoonPhaseName.WAXING_GIBBOUS, MoonPhaseName.WANING_GIBBOUS,
            MoonPhaseName.WANING_CRESCENT,
        )) {
            assertEquals(675, counts[name], "$name")
        }
    }

    @Test
    fun testDisplayNamesAndSymbols() {
        assertEquals(8, MoonPhaseName.entries.size)
        assertEquals(
            listOf(
                "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous", "Full Moon", "Waning Gibbous",
                "Last Quarter", "Waning Crescent",
            ),
            MoonPhaseName.entries.map { it.displayName },
        )
        assertEquals(
            listOf(
                "moonphase.new.moon", "moonphase.waxing.crescent", "moonphase.first.quarter",
                "moonphase.waxing.gibbous", "moonphase.full.moon", "moonphase.waning.gibbous",
                "moonphase.last.quarter", "moonphase.waning.crescent",
            ),
            MoonPhaseName.entries.map { it.symbolName },
        )
        assertEquals(8, MoonPhaseName.entries.map { it.symbolName }.toSet().size)
        assertEquals(listOf(0.0, 90.0, 180.0, 270.0), PrincipalMoonPhase.entries.map { it.elongation })
        assertEquals(
            listOf(
                MoonPhaseName.NEW_MOON, MoonPhaseName.FIRST_QUARTER, MoonPhaseName.FULL_MOON,
                MoonPhaseName.LAST_QUARTER,
            ),
            PrincipalMoonPhase.entries.map { it.phaseName },
        )
        for (phase in PrincipalMoonPhase.entries) {
            assertEquals(phase.phaseName, MoonPhaseName.fromElongation(phase.elongation))
            assertEquals(phase.phaseName.displayName, phase.displayName)
            assertEquals(phase.phaseName.symbolName, phase.symbolName)
        }
        for (name in MoonPhaseName.entries) {
            val data = Json.encodeToString(MoonPhaseName.serializer(), name)
            assertEquals(name, Json.decodeFromString(MoonPhaseName.serializer(), data))
        }
    }

    // MARK: Meeus chapter 49

    /**
     * Example 49.a: new moon of 1977 February 18, JDE 2443192.65118 (3h37m42s TD); DE440s gives
     * 2443192.65116. Example 49.b: last quarter of 2044 January 21, JDE 2467636.49186 (DE440s 2467636.49187).
     */
    @Test
    fun testPhaseInstantsMeeusExamples49() {
        val february = MoonPhaseCalculator.nextPhases(JulianDate(2_443_185.0).date, 3)
        assertEquals(
            listOf(PrincipalMoonPhase.LAST_QUARTER, PrincipalMoonPhase.NEW_MOON, PrincipalMoonPhase.FIRST_QUARTER),
            february.map { it.kind },
        )
        assertEquals(2_443_192.65118, JulianDate(february[1].date).tt, 60.0 / 86_400)
        assertEquals(2_443_185.67220, JulianDate(february[0].date).tt, 60.0 / 86_400)

        val january = MoonPhaseCalculator.nextPhases(JulianDate(2_467_620.0).date, 4)
        assertEquals(
            listOf(
                PrincipalMoonPhase.FIRST_QUARTER, PrincipalMoonPhase.FULL_MOON, PrincipalMoonPhase.LAST_QUARTER,
                PrincipalMoonPhase.NEW_MOON,
            ),
            january.map { it.kind },
        )
        assertEquals(2_467_636.49186, JulianDate(january[2].date).tt, 60.0 / 86_400)
        assertEquals(2_467_629.28637, JulianDate(january[1].date).tt, 60.0 / 86_400)
    }

    @Test
    fun testIlluminationAtMeeusExample48a() {
        // The Moon on 1992 April 12 at 0h TD was 67.9 % lit and waxing. The book's elongation ψ = 110.7929° is the
        // angular distance from the Sun; the difference in ecliptic longitude, from cos ψ = cos β cos Δλ with
        // the Moon's latitude β = −3.2291°, is 110.8265°.
        val info = MoonPhaseCalculator.info(JulianDate.fromTt(2_448_724.5).date)
        assertEquals(0.6786, info.illuminatedFraction, 0.0005)
        assertEquals(110.8265, info.elongation, 0.003)
        assertEquals(MoonPhaseName.WAXING_GIBBOUS, info.name)
        assertTrue(info.isWaxing)
        // The new moon of 1992 April 3, 05:01 UT (JDE 2448715.71002 by DE440s) was 8.79 days earlier.
        assertEquals(8.79, info.ageDays, 0.02)
    }

    // MARK: Fixtures

    @Test
    fun testPrincipalPhasesMatchSkyfield() {
        val phases = fixture().phases
        val start = instantFromTimeIntervalSince1970(assertNotNull(phases.firstOrNull()).unix - 86_400)
        val found = MoonPhaseCalculator.nextPhases(start, phases.size)
        assertEquals(phases.size, found.size)
        for ((mine, reference) in found.zip(phases)) {
            assertEquals(
                reference.kind, mine.kind,
                "phase at ${instantFromTimeIntervalSince1970(reference.unix)}",
            )
            // Observed worst case 15 s; the requirement is 2 minutes.
            assertEquals(
                reference.unix, mine.date.timeIntervalSince1970, 30.0,
                "${reference.phase} at ${instantFromTimeIntervalSince1970(reference.unix)}",
            )
        }
        // The phases are in time order and the sequence cycles through the four kinds.
        for ((earlier, later) in found.zip(found.drop(1))) {
            assertTrue(earlier.date < later.date)
            val index = PrincipalMoonPhase.entries.indexOf(earlier.kind)
            assertEquals(PrincipalMoonPhase.entries[(index + 1) % 4], later.kind)
            val gap = later.date.timeIntervalSince(earlier.date) / 86_400
            assertTrue(gap in 6.2..8.6, "gap of $gap days")
        }
    }

    @Test
    fun testIlluminationElongationAndAgeMatchSkyfield() {
        for (sample in fixture().samples) {
            val date = EphemerisFixtureStore.date(sample.ut1Jd)
            val info = MoonPhaseCalculator.info(date)
            val label = "at ${sample.utc}"
            assertEquals(date, info.date)
            // The requirement is 0.003; the observed worst case is 3×10⁻⁵.
            assertEquals(sample.illuminatedFraction, info.illuminatedFraction, 0.0005, label)
            assertEquals(sample.phaseAngleDeg, info.phaseAngle, 0.02, label)
            assertEquals(
                0.0, AstroMath.normalizeSignedDegrees(info.elongation - sample.eclipticElongationDeg), 0.005, label,
            )
            assertEquals(sample.ageDays, info.ageDays, 0.002, label)
            assertEquals(sample.eclipticElongationDeg < 180, info.isWaxing, label)
            assertEquals(MoonPhaseName.fromElongation(sample.eclipticElongationDeg), info.name, label)
        }
    }

    // MARK: Invariants

    @Test
    fun testInfoIsSelfConsistentAcrossTheSky() {
        val start = instantFromTimeIntervalSince1970(1_790_000_000.0)
        for (step in 0 until 400) {
            val date = start.addingTimeInterval(step.toDouble() * 3 * 3600 + 417)
            val info = MoonPhaseCalculator.info(date)
            val label = "step $step"
            assertTrue(info.illuminatedFraction in 0.0..1.0, label)
            assertTrue(info.phaseAngle in 0.0..180.0, label)
            assertTrue(info.elongation in 0.0..<360.0, label)
            assertTrue(info.ageDays in 0.0..29.9, "$label: age ${info.ageDays}")
            assertEquals(info.elongation < 180, info.isWaxing, label)
            assertEquals(MoonPhaseName.fromElongation(info.elongation), info.name, label)
            // The lit fraction is (1 + cos i)/2 with i the phase angle, and cos i ≈ −cos β cos Δλ for the Moon's
            // ecliptic latitude β (at most 5.3°) and the elongation Δλ; and it matches the body state.
            assertEquals(
                (1 + cos(AstroMath.radians(info.phaseAngle))) / 2, info.illuminatedFraction, 1e-12, label,
            )
            assertEquals(
                (1 - cos(AstroMath.radians(info.elongation))) / 2, info.illuminatedFraction, 0.003, label,
            )
            val state = Ephemeris.state(SolarSystemBody.MOON, JulianDate(date))
            assertEquals(state.phaseAngle ?: Double.NaN, info.phaseAngle, 1e-9, label)
            assertEquals(state.isWaxing, info.isWaxing, label)
            // The age tracks the elongation at the mean rate of 12.19° a day, to within the periodic
            // variations of the Moon's and the Sun's motion at both ends (up to about ±17° or 1.4 days).
            val meanAge = info.elongation / (360 / MoonPhaseCalculator.meanSynodicMonthDays)
            assertEquals(meanAge, info.ageDays, 1.4, label)
        }
    }

    @Test
    fun testAgeJustAfterAndJustBeforeNewMoon() {
        val phases = MoonPhaseCalculator.nextPhases(instantFromTimeIntervalSince1970(1_790_000_000.0), 8)
        val newMoon = assertNotNull(phases.firstOrNull { it.kind == PrincipalMoonPhase.NEW_MOON }).date
        val after = MoonPhaseCalculator.info(newMoon.addingTimeInterval(3600.0))
        assertEquals(1.0 / 24, after.ageDays, 0.002)
        assertEquals(MoonPhaseName.NEW_MOON, after.name)
        // The Moon's latitude (up to 5°) keeps a sliver lit even at new moon.
        assertTrue(after.illuminatedFraction < 0.003)
        val before = MoonPhaseCalculator.info(newMoon.addingTimeInterval(-3600.0))
        assertEquals(MoonPhaseCalculator.meanSynodicMonthDays, before.ageDays, 0.5)
        assertEquals(MoonPhaseName.NEW_MOON, before.name)
        val fullMoon = assertNotNull(phases.firstOrNull { it.kind == PrincipalMoonPhase.FULL_MOON }).date
        val full = MoonPhaseCalculator.info(fullMoon)
        assertEquals(MoonPhaseName.FULL_MOON, full.name)
        assertTrue(full.illuminatedFraction > 0.995)
        assertEquals(14.77, full.ageDays, 0.8)
        assertEquals(0.0, full.phaseAngle, 6.0)
        val quarter = MoonPhaseCalculator.info(
            assertNotNull(phases.firstOrNull { it.kind == PrincipalMoonPhase.FIRST_QUARTER }).date,
        )
        assertEquals(0.5, quarter.illuminatedFraction, 0.01)
        assertEquals(MoonPhaseName.FIRST_QUARTER, quarter.name)
        assertTrue(quarter.isWaxing)
    }

    // MARK: nextPhases

    @Test
    fun testNextPhasesArgumentsAndPaging() {
        val start = instantFromTimeIntervalSince1970(1_790_000_000.0)
        assertTrue(MoonPhaseCalculator.nextPhases(start, 0).isEmpty())
        assertTrue(MoonPhaseCalculator.nextPhases(start, -3).isEmpty())
        // Swift passes `Date(timeIntervalSince1970: .nan)` here. An `Instant` cannot be NaN or infinite (the
        // Foundation helpers map them to the epoch or saturate at `Instant.MIN` / `Instant.MAX`), so the
        // dates that stand for a non-finite one in Kotlin are the saturated extremes.
        assertTrue(MoonPhaseCalculator.nextPhases(Instant.MAX, 4).isEmpty())
        val farFuture = instantFromTimeIntervalSince1970(Double.POSITIVE_INFINITY)
        val farPast = instantFromTimeIntervalSince1970(Double.NEGATIVE_INFINITY)
        assertTrue(MoonPhaseCalculator.nextPhases(farFuture, 4).isEmpty())
        assertTrue(MoonPhaseCalculator.nextPhases(farPast, 4).isEmpty())

        val eight = MoonPhaseCalculator.nextPhases(start, 8)
        assertEquals(8, eight.size)
        assertTrue(assertNotNull(eight.firstOrNull()).date > start)
        assertTrue(assertNotNull(eight.firstOrNull()).date.timeIntervalSince(start) < 9 * 86_400)
        assertEquals(
            eight.take(3).map { it.date },
            MoonPhaseCalculator.nextPhases(start, 3).map { it.date },
        )

        // Passing the date of one phase returns the phases that follow it, not the same one again.
        for (index in 0 until 7) {
            val following = MoonPhaseCalculator.nextPhases(eight[index].date, 1)
            assertEquals(eight[index + 1].kind, following.firstOrNull()?.kind)
            assertEquals(
                0.0,
                following.firstOrNull()?.date?.timeIntervalSince(eight[index + 1].date) ?: Double.NaN,
                0.05,
            )
        }
        // A few seconds before a phase it is the next one.
        val justBefore = MoonPhaseCalculator.nextPhases(eight[2].date.addingTimeInterval(-5.0), 1)
        assertEquals(eight[2].kind, justBefore.firstOrNull()?.kind)

        val many = MoonPhaseCalculator.nextPhases(start, 5_000)
        assertEquals(MoonPhaseCalculator.maximumPhaseCount, many.size)
        // 1000 phases are 250 lunations, about 20 years.
        assertEquals(
            20.2, assertNotNull(many.lastOrNull()).date.timeIntervalSince(start) / (365.25 * 86_400), 0.4,
        )
    }

    @Test
    fun testPhasesInTheDistantPastAndFuture() {
        for (year in listOf(1700, 1900, 2100, 2300)) {
            val start = JulianDate(JulianDate.julianDay(year, 1, 1)).date
            val phases = MoonPhaseCalculator.nextPhases(start, 5)
            assertEquals(5, phases.size, "year $year")
            var previous = start
            for (phase in phases) {
                assertTrue(phase.date > previous, "year $year")
                assertTrue(phase.date.timeIntervalSince(previous) < 9 * 86_400, "year $year")
                previous = phase.date
            }
            val info = MoonPhaseCalculator.info(start)
            assertTrue(info.ageDays in 0.0..29.9, "year $year")
        }
    }
}
