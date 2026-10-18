package net.kuecker.lodestar.kit.catalog

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Timing checks. The Swift suite holds optimized builds to the design targets (a search in 5 ms, a load in 1 s,
 * 100 ms for the sorts) and debug builds to a multiple of them. The JVM is neither: it runs interpreted until
 * the JIT has warmed up, so every measurement follows a warm-up run and is judged against three times the
 * optimized Swift budget, which still catches an algorithmic regression. The measurements are printed.
 *
 * A query is timed several times and judged by its fastest run, which is not disturbed by other work
 * running on the same machine.
 */
class CatalogPerformanceTests {
    /** Seconds allowed for loading the bundled catalog (Swift: 1 s optimized). */
    private val loadBudget = 3.0

    /** Milliseconds allowed for one search (Swift: the design target is 5 ms optimized). */
    private val searchBudget = 15.0

    /** Milliseconds allowed for sorting the listed objects (Swift: 100 ms optimized). */
    private val sortBudget = 300.0

    private fun milliseconds(body: () -> Unit): Double {
        val start = System.nanoTime()
        body()
        return (System.nanoTime() - start) / 1e6
    }

    @Test
    fun testLoadingTheBundledCatalogIsFast() {
        Catalog.loadBundled()
        val times = ArrayList<Double>()
        repeat(3) {
            times.add(milliseconds { Catalog.loadBundled() })
        }
        val best = assertNotNull(times.minOrNull())
        println("CATALOG PERF load (best of 3, after a warm-up): ${best.toInt()} ms")
        assertTrue(best / 1000 < loadBudget, "load took $best ms")
    }

    @Test
    fun testEverySearchMeetsTheLatencyBudgetOnTenThousandObjects() {
        val catalog = CatalogFixture.catalog()
        assertTrue(catalog.objects.size > 9_000)
        val queries = listOf(
            "a", "m", "ngc", "m31", "sirius", "alpha", "alpha cma", "hd 4", "andromeda", "orion neb", "xyz", "andgal",
            "hip 3", "cen", "e", "1", "ngc 7", "the", "nebula", "α¹ cen", "Bélénos", "m 0031", "zzzzzzzzzz",
            "betelgeuse", "omega centauri", "double cluster", "sgr a", "hr 5", "9 cma", "k", "0",
            "a".repeat(64), "m31 ".repeat(30),
        )
        catalog.search("warm up")
        for (query in queries) {
            repeat(20) { catalog.search(query, limit = 50) }
        }
        var slowestQuery = ""
        var slowestTime = 0.0
        var total = 0.0
        for (query in queries) {
            var best = Double.POSITIVE_INFINITY
            repeat(10) {
                val time = milliseconds { catalog.search(query, limit = 50) }
                best = minOf(best, time)
                total += time
            }
            if (best > slowestTime) {
                slowestQuery = query.take(12)
                slowestTime = best
            }
            assertTrue(best < searchBudget, "\"${query.take(20)}\" took $best ms")
        }
        val average = total / (10 * queries.size)
        println(
            "CATALOG PERF search over ${catalog.objects.size} objects: average ${"%.3f".format(java.util.Locale.ROOT, average)} ms, " +
                "slowest query \"$slowestQuery\" ${"%.3f".format(java.util.Locale.ROOT, slowestTime)} ms (best of 10)",
        )
    }

    @Test
    fun testSearchWithAHugeLimitIsStillFast() {
        val catalog = CatalogFixture.catalog()
        repeat(5) { catalog.search("a", limit = Int.MAX_VALUE) }
        var best = Double.POSITIVE_INFINITY
        repeat(5) {
            best = minOf(best, milliseconds { catalog.search("a", limit = Int.MAX_VALUE) })
        }
        println("CATALOG PERF search returning every match of \"a\": ${"%.1f".format(java.util.Locale.ROOT, best)} ms")
        assertTrue(best < searchBudget * 5, "took $best ms")
    }

    @Test
    fun testSortingAndGroupAccessAreFast() {
        val catalog = CatalogFixture.catalog()
        catalog.sorted(catalog.listedObjects, CatalogSortOrder.NAME)
        catalog.sorted(catalog.objects, CatalogSortOrder.NAME)
        val sortTime = milliseconds {
            catalog.sorted(catalog.listedObjects, CatalogSortOrder.NAME)
            catalog.sorted(catalog.listedObjects, CatalogSortOrder.MAGNITUDE)
        }
        val allTime = milliseconds { catalog.sorted(catalog.objects, CatalogSortOrder.NAME) }
        val groupTime = milliseconds {
            repeat(1_000) {
                for (group in ObjectGroup.entries) catalog.objects(group)
            }
        }
        println(
            "CATALOG PERF sort listed: ${"%.1f".format(java.util.Locale.ROOT, sortTime)} ms, " +
                "sort all: ${"%.1f".format(java.util.Locale.ROOT, allTime)} ms, " +
                "6000 group lookups: ${"%.1f".format(java.util.Locale.ROOT, groupTime)} ms",
        )
        assertTrue(sortTime < sortBudget, "sorting the listed objects took $sortTime ms")
        assertTrue(allTime < sortBudget * 10, "sorting all objects took $allTime ms")
        assertTrue(groupTime < sortBudget, "the group lookups took $groupTime ms")
    }

    @Test
    fun testIdentifierLookupDoesNotScan() {
        val catalog = CatalogFixture.catalog()
        val identifiers = catalog.objects.map { it.id }
        repeat(2) {
            for (identifier in identifiers) catalog.objectWithID(identifier)
        }
        val time = milliseconds {
            repeat(20) {
                for (identifier in identifiers) catalog.objectWithID(identifier)
            }
        }
        println("CATALOG PERF ${20 * identifiers.size} identifier lookups: ${"%.1f".format(java.util.Locale.ROOT, time)} ms")
        assertTrue(time < sortBudget * 5, "took $time ms")
    }
}
