package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.foundation.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Speed and thread safety of the planner: a catalog-sized batch in a fraction of a second, and a pure
 * computation that several tasks can run at once.
 *
 * The Swift budgets apply multiplied by three, and every timed computation runs once beforehand so the
 * JIT compiler has warmed up.
 */
class PlanningPerformanceTests {
    private companion object {
        /**
         * Seconds allowed for ranking a thousand targets: the Swift release-build budget of half a second,
         * times three.
         */
        const val thousandTargetsLimit: Double = 0.5 * 3

        /** Seconds allowed for one night summary: the Swift budget of two seconds, times three. */
        const val nightSummaryLimit: Double = 2.0 * 3
    }

    private fun scatteredTargets(count: Int): List<EquatorialCoordinate> {
        val generator = PlanningTestSupport.SeededGenerator(1_000uL)
        return List(count) {
            EquatorialCoordinate(
                generator.nextDouble(0.0, 360.0),
                AstroMath.asinDeg(generator.nextDoubleInclusive(-1.0, 1.0)),
            )
        }
    }

    private inline fun seconds(work: () -> Unit): Double {
        val start = System.nanoTime()
        work()
        return (System.nanoTime() - start) / 1e9
    }

    @Test
    fun testRankingAThousandTargetsThroughALongNightIsFast() {
        val summary = PlanningTestSupport.summary(PlanningTestSupport.boulder, 2026, 12, 21) // 11.4 hours of darkness
        val targets = scatteredTargets(1_000)
        NightPlanner.visibility(targets, summary = summary)
        var results = emptyList<TargetVisibility>()
        val elapsed = seconds { results = NightPlanner.visibility(targets, summary = summary) }
        assertEquals(1_000, results.size)
        assertTrue(elapsed < thousandTargetsLimit, "took $elapsed s")
        assertTrue(results.count { it.hoursAboveMinimum > 0 } > 200, "many targets get above 20°")
        assertTrue(results.count { it.hoursAboveMinimum == 0.0 } > 200, "and many never do")
    }

    @Test
    fun testRankingWithProperMotionsAndMoonFreeTimeIsJustAsFast() {
        val summary = PlanningTestSupport.summary(PlanningTestSupport.boulder, 2026, 6, 21)
        val targets = scatteredTargets(1_000)
        val motions: List<ProperMotion?> = targets.indices.map { index ->
            if (index % 2 == 0) ProperMotion(index.toDouble(), -index.toDouble()) else null
        }
        NightPlanner.visibility(targets, motions, summary, DarknessKind.MOON_FREE)
        var results = emptyList<TargetVisibility>()
        val elapsed = seconds {
            results = NightPlanner.visibility(targets, motions, summary, DarknessKind.MOON_FREE)
        }
        assertEquals(1_000, results.size)
        assertTrue(elapsed < thousandTargetsLimit, "took $elapsed s")
    }

    @Test
    fun testBatchOfAThousandWithoutDarknessOnlyPaysForTheEvents() {
        val summary = PlanningTestSupport.summary(PlanningTestSupport.tromso, 2026, 6, 21)
        NightPlanner.visibility(scatteredTargets(1_000), summary = summary)
        var results = emptyList<TargetVisibility>()
        val elapsed = seconds { results = NightPlanner.visibility(scatteredTargets(1_000), summary = summary) }
        assertEquals(1_000, results.size)
        assertTrue(results.all { it.maxAltitudeDuringDarkness == null })
        assertTrue(elapsed < thousandTargetsLimit, "took $elapsed s")
    }

    @Test
    fun testANightSummaryTakesWellUnderASecond() {
        PlanningTestSupport.summary(PlanningTestSupport.boulder, 2026, 12, 21)
        val elapsed = seconds { PlanningTestSupport.summary(PlanningTestSupport.boulder, 2026, 12, 21) }
        assertTrue(elapsed < nightSummaryLimit, "took $elapsed s")
    }

    @Test
    fun testSummariesAndRankingsCanRunOnSeveralTasksAtOnce() {
        val site = PlanningTestSupport.boulder
        val zone = site.timeZone()
        val noon = PlanningTestSupport.localNoon(2026, 10, 25, zone)
        val observer = site.observer
        val targets = scatteredTargets(50)
        val reference = NightPlanner.summary(noon, observer, zone)
        val referenceRanking = NightPlanner.visibility(targets, summary = reference)

        val executor = Executors.newFixedThreadPool(6)
        val outcomes = try {
            val tasks = List(6) {
                executor.submit(
                    Callable {
                        val summary = NightPlanner.summary(noon, observer, zone)
                        Pair(summary, NightPlanner.visibility(targets, summary = summary))
                    },
                )
            }
            tasks.map { it.get() }
        } finally {
            executor.shutdown()
        }
        assertEquals(6, outcomes.size)
        for ((summary, ranking) in outcomes) {
            assertEquals(reference, summary)
            assertEquals(referenceRanking, ranking)
        }
    }
}
