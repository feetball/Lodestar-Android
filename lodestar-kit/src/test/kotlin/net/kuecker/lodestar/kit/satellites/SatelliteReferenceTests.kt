package net.kuecker.lodestar.kit.satellites

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Compares passes, looks and shadow transitions with Skyfield 1.55 (SGP4 from the `sgp4` package, JPL DE421,
 * IAU 2000 frames, `is_sunlit` with a point-source Sun), computed by `Tools/make_satellite_fixtures.py passes`
 * from the CelesTrak GP snapshot of 2026-10-18 in `satellite_omm_sample.json`. Skyfield shares no code with
 * LodestarKit. Differences in the models: LodestarKit takes UT1 equal to UTC and ignores polar motion; Skyfield
 * uses its UT1 - UTC table (0.09 s here) and the full TEME rotation.
 */
class SatelliteReferenceTests {
    private fun resource(name: String): ByteArray {
        val stream = checkNotNull(SatelliteReferenceTests::class.java.getResourceAsStream("/fixtures/$name")) {
            "The fixture $name is missing from the test resources."
        }
        return stream.use { it.readBytes() }
    }

    private val reference: JsonObject = Json.parseToJsonElement(String(resource("satellite_reference.json"), Charsets.UTF_8)).jsonObject

    private val satellites: Map<String, Satellite> by lazy {
        val ids = reference["satellites"]!!.jsonArray.map { it.jsonPrimitive.content }
        val batch = ElementSetParser.parseJson(resource("satellite_omm_sample.json"))
        assertEquals(0, batch.skippedCount)
        assertEquals(ids.size, batch.elements.size)
        ids.zip(batch.elements.map { Satellite(it) }).toMap()
    }

    private fun observer(site: String): Observer {
        val s = reference["sites"]!!.jsonObject[site]!!.jsonObject
        val location = GeographicLocation.validated(
            s["latitude"]!!.jsonPrimitive.double, s["longitude"]!!.jsonPrimitive.double, s["elevation"]!!.jsonPrimitive.double,
        )
        return Observer(assertNotNull(location))
    }

    private fun instant(seconds: Double): Instant = instantFromTimeIntervalSince1970(seconds)

    private fun seconds(instant: Instant): Double = instant.timeIntervalSince1970

    @Test
    fun testPassesMatchSkyfield() {
        var worstTime = 0.0
        var worstLongTime = 0.0
        var worstAltitude = 0.0
        var worstAzimuth = 0.0
        var worstVisible = 0.0
        var comparedPasses = 0
        var comparedVisible = 0
        for (case in reference["cases"]!!.jsonArray.map { it.jsonObject }) {
            val id = case["satellite"]!!.jsonPrimitive.content
            val site = case["site"]!!.jsonPrimitive.content
            val label = "$id at $site"
            val start = case["start"]!!.jsonPrimitive.double
            val end = case["end"]!!.jsonPrimitive.double
            val finder = SatellitePassFinder(observer(site), DateInterval(instant(start), instant(end)), case["minimumAltitude"]!!.jsonPrimitive.double, case["darknessSunAltitude"]!!.jsonPrimitive.double)
            val found = finder.passes(satellites.getValue(id))
            val complete = found.filter { !it.isRiseCut && !it.isSetCut }
            val expected = case["passes"]!!.jsonArray.map { it.jsonObject }
            assertEquals(expected.size, complete.size, "$label: number of complete passes")
            for ((pass, truth) in complete.zip(expected)) {
                val rise = truth["rise"]!!.jsonPrimitive.double
                val culmination = truth["culmination"]!!.jsonPrimitive.double
                val set = truth["set"]!!.jsonPrimitive.double
                // A pass that lasts hours (a geosynchronous or Molniya orbit) has altitude rates of 0.01 degrees
                // per second and less, so the same altitude error shifts its times by seconds.
                val long = set - rise > 3_600.0
                val differences = max(
                    abs(seconds(pass.rise.date) - rise),
                    max(abs(seconds(pass.culmination.date) - culmination), abs(seconds(pass.set.date) - set)),
                )
                if (long) worstLongTime = max(worstLongTime, differences) else worstTime = max(worstTime, differences)
                worstAltitude = max(worstAltitude, abs(pass.culmination.altitude - truth["culminationAltitude"]!!.jsonPrimitive.double))
                // Near the zenith the azimuth is ill-conditioned: compare directions.
                val culminationTruth = HorizontalCoordinate(truth["culminationAzimuth"]!!.jsonPrimitive.double, truth["culminationAltitude"]!!.jsonPrimitive.double)
                worstAzimuth = max(worstAzimuth, pass.culmination.horizontal.angularSeparation(culminationTruth))
                worstAzimuth = max(worstAzimuth, angleDifference(pass.rise.azimuth, truth["riseAzimuth"]!!.jsonPrimitive.double))
                worstAzimuth = max(worstAzimuth, angleDifference(pass.set.azimuth, truth["setAzimuth"]!!.jsonPrimitive.double))
                val visible = truth["visible"]!!.jsonArray.map { it.jsonArray }
                assertEquals(visible.size, pass.visibleIntervals.size, "$label: visible parts of the pass at $rise")
                for ((interval, edges) in pass.visibleIntervals.zip(visible)) {
                    worstVisible = max(worstVisible, abs(seconds(interval.start) - edges[0].jsonPrimitive.double))
                    worstVisible = max(worstVisible, abs(seconds(interval.end) - edges[1].jsonPrimitive.double))
                    comparedVisible += 1
                }
                comparedPasses += 1
            }
        }
        println("passes: $comparedPasses compared, worst time $worstTime s (long passes $worstLongTime s), altitude $worstAltitude deg, azimuth $worstAzimuth deg; $comparedVisible visible parts, worst edge $worstVisible s")
        assertTrue(comparedPasses >= 90)
        assertTrue(comparedVisible >= 25)
        assertTrue(worstTime < 0.5, "worst time difference $worstTime s")
        assertTrue(worstLongTime < 2.0, "worst time difference of the long passes $worstLongTime s")
        assertTrue(worstAltitude < 0.01, "worst culmination altitude difference $worstAltitude deg")
        assertTrue(worstAzimuth < 0.02, "worst direction difference $worstAzimuth deg")
        assertTrue(worstVisible < 1.0, "worst visible edge difference $worstVisible s")
    }

    private fun angleDifference(a: Double, b: Double): Double {
        val difference = abs(a - b) % 360.0
        return if (difference > 180.0) 360.0 - difference else difference
    }

    @Test
    fun testLooksMatchSkyfield() {
        var worstAngle = 0.0
        var worstRange = 0.0
        var worstRate = 0.0
        var sunlitMismatches = 0
        var worstSun = 0.0
        val looks = reference["looks"]!!.jsonArray.map { it.jsonObject }
        for (truth in looks) {
            val id = truth["satellite"]!!.jsonPrimitive.content
            val site = truth["site"]!!.jsonPrimitive.content
            val time = truth["time"]!!.jsonPrimitive.double
            val look = assertNotNull(satellites.getValue(id).look(instant(time), observer(site)), "$id at $site")
            val expected = HorizontalCoordinate(truth["azimuth"]!!.jsonPrimitive.double, truth["altitude"]!!.jsonPrimitive.double)
            worstAngle = max(worstAngle, look.horizontal.angularSeparation(expected))
            worstRange = max(worstRange, abs(look.rangeKilometers - truth["rangeKilometers"]!!.jsonPrimitive.double))
            worstRate = max(worstRate, abs(look.rangeRateKilometersPerSecond - truth["rangeRateKilometersPerSecond"]!!.jsonPrimitive.double))
            worstSun = max(worstSun, abs(look.sunAltitude - truth["sunAltitude"]!!.jsonPrimitive.double))
            if (look.isSunlit != truth["isSunlit"]!!.jsonPrimitive.boolean) sunlitMismatches += 1
        }
        println("looks: ${looks.size} compared, worst angle $worstAngle deg, range $worstRange km, range rate $worstRate km/s, Sun altitude $worstSun deg, sunlit mismatches $sunlitMismatches")
        assertEquals(0, sunlitMismatches)
        assertTrue(worstAngle < 0.01, "worst direction difference $worstAngle deg")
        assertTrue(worstRange < 0.5, "worst range difference $worstRange km")
        assertTrue(worstRate < 0.001, "worst range-rate difference $worstRate km/s")
        assertTrue(worstSun < 0.02, "worst Sun altitude difference $worstSun deg")
    }

    @Test
    fun testEarthShadowTransitionsMatchSkyfield() {
        var worst = 0.0
        var compared = 0
        for (entry in reference["shadowTransitions"]!!.jsonArray.map { it.jsonObject }) {
            val satellite = satellites.getValue(entry["satellite"]!!.jsonPrimitive.content)
            val propagator = satellite.propagator
            for (transition in entry["transitions"]!!.jsonArray.map { it.jsonObject }) {
                val time = transition["time"]!!.jsonPrimitive.double
                val enters = transition["entersSunlight"]!!.jsonPrimitive.boolean
                // Bisect our own half-illumination crossing between 20 s before and 20 s after Skyfield's.
                fun lit(t: Double): Boolean {
                    val date = instant(t)
                    val state = propagator.state(date).state!!
                    return EarthShadow.isSunlit(state.position, SatelliteFrames.sunPosition(date))
                }
                assertEquals(!enters, lit(time - 20.0), "${satellite.name} before the transition at $time")
                assertEquals(enters, lit(time + 20.0), "${satellite.name} after the transition at $time")
                var low = time - 20.0
                var high = time + 20.0
                while (high - low > 0.001) {
                    val middle = 0.5 * (low + high)
                    if (lit(middle) == enters) high = middle else low = middle
                }
                worst = max(worst, abs(0.5 * (low + high) - time))
                compared += 1
            }
        }
        println("shadow: $compared transitions compared, worst difference $worst s")
        assertTrue(compared >= 12)
        assertTrue(worst < 0.5, "worst shadow transition difference $worst s")
    }
}
