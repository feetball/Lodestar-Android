package net.kuecker.lodestar.kit.satellites

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the SGP4/SDP4 port against the verification run of the reference implementation.
 *
 * `sgp4_ver.tle` is `SGP4-VER.TLE` from Vallado's distribution (CelesTrak, "Revisiting Spacetrack Report #3",
 * AIAA 2006-6753): 33 element sets chosen to exercise every branch of the code (near-earth, deep-space,
 * 12-hour and 24-hour resonance, the Lyddane branch, low perigees, decay, the error codes 1, 4 and 6), each with
 * a start, stop and step time in minutes after its second line.
 * `sgp4_tcppver.out` is the output of the reference C++ code (`SGP4.cpp`, version 2020-07-13, `opsmode` 'i',
 * WGS-72) for that file, produced exactly as its `TestSGP4.cpp` verification run does (`tcppver.out`); the
 * generator is `Tools/make_satellite_fixtures.py`. The official MATLAB (Dec 2015) and Fortran outputs of the
 * 2006 distribution agree with it to the last printed digit for every satellite except 23599 after 420 minutes
 * (an older Lyddane rule, below 1 km); the C++ and Java v2 outputs agree with each other and with this port.
 *
 * The expected values are printed with 8 decimals (position, km) and 9 decimals (velocity, km/s).
 */
class Sgp4VerificationTests {
    private class Case(
        val catalog: String,
        val elements: OrbitalElements,
        val start: Double,
        val stop: Double,
        val step: Double,
    )

    private class Row(val time: Double, val values: DoubleArray)

    private class Expected(val rows: MutableList<Row> = ArrayList(), val errors: MutableList<Pair<Double, Int>> = ArrayList())

    private fun resource(name: String): String {
        val stream = checkNotNull(Sgp4VerificationTests::class.java.getResourceAsStream("/fixtures/$name")) {
            "The fixture $name is missing from the test resources."
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun cases(): List<Case> {
        val lines = resource("sgp4_ver.tle").lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val result = ArrayList<Case>()
        var index = 0
        while (index + 1 < lines.size) {
            val secondFull = lines[index + 1]
            val times = secondFull.substring(69).trim().split(Regex("\\s+")).map { it.toDouble() }
            val first = withValidChecksum(lines[index].substring(0, 69))
            val second = withValidChecksum(secondFull.substring(0, 69))
            val elements = ElementSetParser.parseTwoLine(first, second)
            result.add(Case(elements.catalogNumber.toString(), elements, times[0], times[1], times[2]))
            index += 2
        }
        return result
    }

    /**
     * The checksum digit of the lines of the three hand-made error-code cases (33333, 33334, 33335) is wrong
     * in the distribution file; the reference does not check it. [testTheHandMadeCasesCarryWrongChecksums]
     * pins which lines these are.
     */
    private fun withValidChecksum(line: String): String = line.substring(0, 68) + ElementSetParser.checksum(line)

    /** The groups of `tcppver.out`: one per element set, keyed by catalog number, in file order. */
    private fun expectations(): List<Pair<String, Expected>> {
        val groups = ArrayList<Pair<String, Expected>>()
        var current: Expected? = null
        for (line in resource("sgp4_tcppver.out").lines()) {
            if (line.isBlank()) continue
            if (line.trim().endsWith("xx")) {
                current = Expected()
                groups.add(Pair(line.trim().split(" ")[0].trimStart('0'), current))
            } else if (line.startsWith("# *** error")) {
                val time = line.substringAfter("t:=").substringBefore("***").trim().toDouble()
                val code = line.substringAfter("code =").trim().toInt()
                checkNotNull(current).errors.add(Pair(time, code))
            } else {
                val fields = line.trim().split(Regex("\\s+")).take(7).map { it.toDouble() }
                checkNotNull(current).rows.add(Row(fields[0], doubleArrayOf(fields[1], fields[2], fields[3], fields[4], fields[5], fields[6])))
            }
        }
        return groups
    }

    @Test
    fun testTheFileHoldsThirtyThreeElementSetsAndTheReferenceOutputMatchesIt() {
        val all = cases()
        val expected = expectations()
        assertEquals(33, all.size)
        assertEquals(33, expected.size)
        assertEquals(all.map { it.catalog }, expected.map { it.first })
    }

    @Test
    fun testTheHandMadeCasesCarryWrongChecksums() {
        val lines = resource("sgp4_ver.tle").lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val rejected = ArrayList<String>()
        var index = 0
        while (index + 1 < lines.size) {
            val first = lines[index].substring(0, 69)
            val second = lines[index + 1].substring(0, 69)
            try {
                ElementSetParser.parseTwoLine(first, second)
            } catch (error: ElementSetError.ChecksumMismatch) {
                rejected.add(first.substring(2, 7))
                val wrongLine = if (error.line == 1) first else second
                assertEquals(ElementSetParser.checksum(wrongLine), error.expected)
                assertEquals(wrongLine[68] - '0', error.found)
            }
            index += 2
        }
        assertEquals(listOf("33333", "33334", "33335"), rejected)
    }

    @Test
    fun testEveryVerificationSatelliteMatchesTheReferenceToTheLastPrintedDigit() {
        val all = cases()
        val expected = expectations()
        var worstPosition = 0.0
        var worstVelocity = 0.0
        var worstWhere = ""
        var compared = 0
        for ((case, group) in all.zip(expected.map { it.second })) {
            val propagator = Sgp4Propagator(case.elements)
            var rowIndex = 0
            val out = DoubleArray(6)

            // The reference prints the state at the epoch first, then steps from start to stop.
            val epochCode = propagator.propagateRaw(0.0, out)
            if (epochCode != 0) {
                // Only 33334 (mean motion 0.00001 rev/day) fails at the epoch, with error 3; the reference then
                // stops and prints the stale contents of its output arrays, which are not a state.
                assertEquals("33334", case.catalog)
                assertEquals(3, epochCode)
                assertEquals(1, group.rows.size)
                continue
            }
            val (epochPosition, epochVelocity) = compare(case, group.rows[rowIndex], out)
            worstPosition = max(worstPosition, epochPosition)
            worstVelocity = max(worstVelocity, epochVelocity)
            rowIndex += 1
            compared += 1

            var tsince = case.start
            if (abs(tsince) > 1.0e-8) tsince -= case.step
            var errorIndex = 0
            var failed = false
            while (tsince < case.stop && !failed) {
                tsince += case.step
                if (tsince > case.stop) tsince = case.stop
                val code = propagator.propagateRaw(tsince, out)
                if (code != 0) {
                    val (errorTime, errorCode) = group.errors[errorIndex]
                    assertEquals(errorTime, tsince, 1.0e-6, "${case.catalog}: time of the error")
                    assertEquals(errorCode, code, "${case.catalog}: error code at $tsince")
                    errorIndex += 1
                    failed = true
                } else {
                    val row = group.rows[rowIndex]
                    assertEquals(row.time, tsince, 1.0e-6, "${case.catalog}: time of row $rowIndex")
                    val (dp, dv) = compare(case, row, out)
                    if (dp > worstPosition) worstWhere = "${case.catalog} at $tsince min"
                    worstPosition = max(worstPosition, dp)
                    worstVelocity = max(worstVelocity, dv)
                    rowIndex += 1
                    compared += 1
                }
            }
            assertEquals(group.rows.size, rowIndex, "${case.catalog}: number of rows")
            assertEquals(group.errors.size, errorIndex, "${case.catalog}: number of errors")
        }
        println("SGP4 verification: $compared states, worst position difference $worstPosition km ($worstWhere), worst velocity difference $worstVelocity km/s")
        assertTrue(compared > 600)
        assertTrue(worstPosition < positionTolerance, "worst position difference $worstPosition km at $worstWhere")
        assertTrue(worstVelocity < velocityTolerance, "worst velocity difference $worstVelocity km/s")
    }

    private fun compare(case: Case, row: Row, state: DoubleArray): Pair<Double, Double> {
        var dp = 0.0
        var dv = 0.0
        for (index in 0 until 3) {
            dp = max(dp, abs(state[index] - row.values[index]))
            dv = max(dv, abs(state[index + 3] - row.values[index + 3]))
        }
        assertTrue(dp < positionTolerance, "${case.catalog} at ${row.time} min: position differs by $dp km")
        assertTrue(dv < velocityTolerance, "${case.catalog} at ${row.time} min: velocity differs by $dv km/s")
        return Pair(dp, dv)
    }

    private companion object {
        /** The reference prints 8 decimals (10 micrometres); a millimetre leaves room for the last-ulp libm differences. */
        const val positionTolerance = 1.0e-6

        /** The reference prints 9 decimals (1 micrometre per second). */
        const val velocityTolerance = 1.0e-8
    }
}
