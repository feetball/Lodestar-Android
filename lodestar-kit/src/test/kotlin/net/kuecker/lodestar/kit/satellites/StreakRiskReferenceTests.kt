package net.kuecker.lodestar.kit.satellites

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Compares the streak-risk assessment with Skyfield: `satellite_streaks.json` lists, for five camera frames, the
 * runs in which each satellite of a synthetic constellation of 2976 element sets (the 124 Starlink records of
 * `satellite_starlink_sample.json` copied 24 times with the node moved by 15° and the mean anomaly by 37° per
 * copy, catalog number 200000 + 1000·copy + position) is inside the frame, sunlit (Skyfield's `is_sunlit`) and
 * above the horizon, found by sampling every 0.1 s (`Tools/make_satellite_fixtures.py streaks`).
 */
class StreakRiskReferenceTests {
    private fun resource(name: String): String {
        val stream = checkNotNull(StreakRiskReferenceTests::class.java.getResourceAsStream("/fixtures/$name")) {
            "The fixture $name is missing from the test resources."
        }
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    /** The synthetic constellation, built exactly as the generator builds it. */
    private fun constellation(): List<Satellite> {
        val base = ElementSetParser.parseJson(resource("satellite_starlink_sample.json"))
        assertEquals(0, base.skippedCount)
        assertEquals(124, base.elements.size)
        val result = ArrayList<Satellite>()
        for (copy in 0 until 24) {
            for ((position, element) in base.elements.withIndex()) {
                val shifted = OrbitalElements.create(
                    name = element.name,
                    objectId = element.objectId,
                    catalogNumber = 200_000 + 1000 * copy + position,
                    classification = element.classification,
                    epoch = element.epoch,
                    meanMotion = element.meanMotion,
                    eccentricity = element.eccentricity,
                    inclination = element.inclination,
                    rightAscensionOfAscendingNode = (element.rightAscensionOfAscendingNode + 15.0 * copy) % 360.0,
                    argumentOfPericenter = element.argumentOfPericenter,
                    meanAnomaly = (element.meanAnomaly + 37.0 * copy) % 360.0,
                    ephemerisType = element.ephemerisType,
                    elementSetNumber = element.elementSetNumber,
                    revolutionNumberAtEpoch = element.revolutionNumberAtEpoch,
                    bstar = element.bstar,
                    meanMotionDot = element.meanMotionDot,
                    meanMotionDdot = element.meanMotionDdot,
                )
                result.add(Satellite(shifted))
            }
        }
        return result
    }

    @Test
    fun testCrossingsMatchSkyfield() {
        val reference = Json.parseToJsonElement(resource("satellite_streaks.json")).jsonObject
        val satellites = constellation()
        assertEquals(reference["satellites"]!!.jsonArray.map { it.jsonPrimitive.int }, satellites.map { it.catalogNumber })
        var totalCrossings = 0
        var worstTime = 0.0
        for (case in reference["cases"]!!.jsonArray.map { it.jsonObject }) {
            val site = reference["sites"]!!.jsonObject[case["site"]!!.jsonPrimitive.content]!!.jsonObject
            val observer = Observer(
                assertNotNull(
                    GeographicLocation.validated(
                        site["latitude"]!!.jsonPrimitive.double, site["longitude"]!!.jsonPrimitive.double,
                        site["elevation"]!!.jsonPrimitive.double,
                    ),
                ),
            )
            val frame = assertNotNull(
                CameraFrame.create(
                    HorizontalCoordinate(case["azimuth"]!!.jsonPrimitive.double, case["altitude"]!!.jsonPrimitive.double),
                    case["width"]!!.jsonPrimitive.double, case["height"]!!.jsonPrimitive.double, case["roll"]!!.jsonPrimitive.double,
                ),
            )
            val start = case["start"]!!.jsonPrimitive.double
            val exposure = case["exposure"]!!.jsonPrimitive.double
            val rateWindow = case["rateWindow"]!!.jsonPrimitive.double
            val options = StreakOptions(
                minimumAltitude = 0.0,
                summaryAltitude = case["summaryAltitude"]!!.jsonPrimitive.double,
                rateWindow = rateWindow,
            )
            val assessment = StreakRisk.assess(
                satellites, observer, frame, DateInterval(instantFromTimeIntervalSince1970(start), exposure), options,
            )
            val label = "${case["site"]!!.jsonPrimitive.content} ${case["width"]!!.jsonPrimitive.double}x${case["height"]!!.jsonPrimitive.double}"
            assertEquals(case["evaluated"]!!.jsonPrimitive.int, assessment.evaluatedCount, "$label: evaluated")
            assertEquals(case["aboveHorizon"]!!.jsonPrimitive.int, assessment.aboveHorizonCount, "$label: above the horizon")
            assertEquals(
                case["sunlitAboveSummaryAltitude"]!!.jsonPrimitive.int, assessment.sunlitAboveSummaryAltitudeCount,
                "$label: sunlit above the summary altitude",
            )

            val expected = case["crossings"]!!.jsonArray.map { it.jsonObject }
            // The whole window (the longer of exposure and rate window) is in the fixture: the rate counts all
            // runs, the crossing list those that touch the exposure.
            assertEquals(expected.size * 60.0 / rateWindow, assessment.crossingsPerMinute, 1e-9, "$label: crossings per minute")
            val inExposure = expected.filter { it.time("exit") >= start && it.time("entry") <= start + exposure }
            assertEquals(inExposure.size, assessment.crossings.size, "$label: number of crossings in the exposure")
            val used = HashSet<Int>()
            for (truth in inExposure) {
                val number = truth["satellite"]!!.jsonPrimitive.int
                val entry = max(truth.time("entry"), start)
                val exit = minOf(truth.time("exit"), start + exposure)
                val matching = assessment.crossings.withIndex().firstOrNull { (index, crossing) ->
                    index !in used && constellationNumber(crossing) == number &&
                        abs(crossing.entry.date.timeIntervalSince1970 - entry) < 0.5 &&
                        abs(crossing.exit.date.timeIntervalSince1970 - exit) < 0.5
                }
                assertNotNull(matching, "$label: no crossing of $number between $entry and $exit")
                used.add(matching.index)
                worstTime = max(worstTime, abs(matching.value.entry.date.timeIntervalSince1970 - entry))
                worstTime = max(worstTime, abs(matching.value.exit.date.timeIntervalSince1970 - exit))
                totalCrossings += 1
            }
            for (crossing in assessment.crossings) {
                val position = assertNotNull(crossing.entry.position)
                assertTrue(abs(position.x) < 1.0001 && abs(position.y) < 1.0001, "$label: entry point on the frame")
            }
        }
        println("streaks: $totalCrossings crossings in 5 frames matched, worst entry/exit difference $worstTime s")
        assertTrue(totalCrossings >= 30)
        assertTrue(worstTime < 0.3, "worst entry/exit difference $worstTime s")
    }

    private fun constellationNumber(crossing: SatelliteCrossing): Int = crossing.satellite.catalogNumber

    private fun JsonObject.time(key: String): Double = this[key]!!.jsonPrimitive.double
}
