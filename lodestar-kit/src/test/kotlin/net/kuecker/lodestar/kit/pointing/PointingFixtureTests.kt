package net.kuecker.lodestar.kit.pointing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.PointingTestSupport as Support
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pointing on real sky positions: apparent azimuth/altitude of bright stars computed with
 * Skyfield and JPL DE440s (see `Fixtures/README.md`), independent of LodestarKit's own astronomy.
 */
class PointingFixtureTests {
    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    private data class Case(
        val utc: String,
        val site: String,
        val star: String,
        @SerialName("az_deg") val azDeg: Double,
        @SerialName("alt_refracted_deg") val altRefractedDeg: Double? = null,
    ) {
        /** The apparent (refracted) position, or null below the horizon. */
        val target: HorizontalCoordinate?
            get() = altRefractedDeg?.let { HorizontalCoordinate(azDeg, it) }
    }

    @Serializable
    private data class Document(val cases: List<Case>)

    private fun loadCases(): List<Case> {
        val stream = assertNotNull(
            PointingFixtureTests::class.java.getResourceAsStream("/fixtures/stars_apparent.json"),
            "The fixtures resource stars_apparent.json is missing from the test resources.",
        )
        val text = stream.use { it.readBytes().decodeToString() }
        val document = json.decodeFromString(Document.serializer(), text)
        assertTrue(document.cases.size > 100, "stars_apparent.json looks truncated")
        return document.cases
    }

    @Test
    fun testEveryVisibleStarCanBeAimedAtWithEveryMount() {
        val cases = loadCases()
        val random = PointingTestRandom(801)
        var aimed = 0
        for (item in cases) {
            val target = item.target ?: continue
            if (!(target.altitude > -0.5)) continue
            for (mount in MountProfile.presets) {
                val attitude = Support.attitude(
                    mount = mount,
                    frame = Support.cameraFrame(target, random.uniform(-90.0, 90.0)),
                    referenceAzimuth = random.uniform(-20.0, 20.0),
                )
                val solution = PointingSolution.solve(attitude, mount)
                val tracker = GuidanceTracker(onTargetThreshold = 0.5)
                val guidance = assertNotNull(tracker.update(solution, target))
                assertEquals(GuidanceState.ON_TARGET, guidance.state, "${item.star} ${item.site} ${mount.id}")
                assertEquals(0.0, guidance.separation, 1e-6)
                assertEquals(target.altitude < 0, guidance.targetBelowHorizon)
                assertEquals(target.altitude > Guidance.nearZenithAltitude, guidance.isNearZenith)
            }
            aimed += 1
        }
        assertTrue(aimed > 100)
    }

    @Test
    fun testAlignmentOnRealStarsRecoversThePointingElsewhere() {
        val cases = loadCases()
        val groups = LinkedHashMap<String, MutableList<Case>>()
        for (item in cases) groups.getOrPut("${item.utc} ${item.site}") { ArrayList() }.add(item)
        val random = PointingTestRandom(802)
        val mount = MountProfile.backCameraLandscapeRight
        var validated = 0
        for ((key, group) in groups.entries.sortedBy { it.key }.map { it.key to it.value }) {
            val up = group.filter { (it.altRefractedDeg ?: -1.0) > 8 && (it.altRefractedDeg ?: 90.0) < 78 }
            if (up.size < 4) continue
            // The phone's compass is 9° off and the clamp is 1.5° out of true.
            val scenario = AlignmentTestScenario(headingOffset = 9.0, correction = Vector3(1.3, -0.8, 0.0), mount = mount)
            val aligned = up.take(3).withIndex().mapNotNull { (index, item) ->
                val target = item.target ?: return@mapNotNull null
                scenario.point(target, random.uniform(-30.0, 30.0), random = random, index = index, name = item.star)
            }
            val model = AlignmentModel.solve(aligned, mount)
            assertTrue(model.rmsResidual < 0.02, key)
            for (item in up.drop(3)) {
                val target = item.target ?: continue
                val attitude = scenario.point(target, random.uniform(-30.0, 30.0), random = random).attitude
                val solved = PointingSolution.solve(attitude, mount, model)
                assertEquals(0.0, solved.boresight.angularSeparation(target), 0.1, "$key ${item.star}")
                validated += 1
            }
        }
        assertTrue(validated > 30, "too few groups were usable")
    }
}
