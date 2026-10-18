package net.kuecker.lodestar.features.smallbodies

import net.kuecker.lodestar.features.skychart.PlanisphereLayout
import net.kuecker.lodestar.features.skychart.PlanisphereViewport
import net.kuecker.lodestar.features.skychart.SkyChartLabel
import net.kuecker.lodestar.features.skychart.SkyChartPickable
import net.kuecker.lodestar.features.skychart.SkyChartSceneBuilder
import net.kuecker.lodestar.features.smallbodies.SmallBodyFixtures as Fixtures
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.comets.SmallBodyEphemeris
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.horizon.HorizonMask
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.shared.SkySnapshot
import net.kuecker.lodestar.shared.SkySnapshotBuilder
import net.kuecker.lodestar.shared.SkySnapshotOptions
import net.kuecker.lodestar.theme.Palette
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmallBodySkyTest {
    private val opposition = Fixtures.oppositionComet("C/2026 X1", "Opposition")
    private val catalog = Fixtures.catalog(extra = listOf(opposition))
    private val frame = SkyFrame(Fixtures.date, Fixtures.observer)

    private fun markers(
        target: CelestialObject? = null,
        limit: Double = SmallBodySkyMarkers.DEFAULT_MAGNITUDE_LIMIT,
        mask: HorizonMask? = null,
        source: List<CelestialObject> = catalog.smallBodies,
    ) = SmallBodySkyMarkers.build(source, frame, target, limit, mask)

    // Which bodies are drawn

    @Test
    fun onlyTheBrightOnesAreDrawn() {
        val drawn = markers()
        assertTrue(drawn.isNotEmpty())
        for (marker in drawn) {
            val magnitude = assertNotNull(marker.magnitude, marker.id)
            assertTrue(magnitude <= SmallBodySkyMarkers.DEFAULT_MAGNITUDE_LIMIT, "${marker.id} $magnitude")
        }
        assertTrue(drawn.any { it.id == opposition.id })
        // A body that is currently fainter, or that has no magnitude parameters, is not drawn.
        assertTrue(drawn.none { it.id == Fixtures.withoutMagnitude.id })
        assertTrue(drawn.size < catalog.smallBodies.size)
        assertEquals(drawn.map { it.magnitude }.sortedBy { it }, drawn.map { it.magnitude }, "brightest first")
    }

    @Test
    fun theLimitIsTheMagnitudeOfTheCurrentSky() {
        val expected = catalog.smallBodies.count { obj ->
            val state = SmallBodyEphemeris.state(assertNotNull(obj.smallBody), frame.julianDate)
            (state?.apparentMagnitude ?: 99.0) <= 9.0
        }
        assertEquals(expected, markers().size)
        assertTrue(markers(limit = 5.0).none { it.id == opposition.id }, "magnitude 5.7 is fainter than 5")
        assertTrue(markers(limit = 6.5).any { it.id == opposition.id })
        assertTrue(markers(limit = -20.0).isEmpty())
    }

    @Test
    fun theTargetIsAlwaysDrawnFirst() {
        for (target in listOf(Fixtures.obj(Fixtures.leonard), Fixtures.obj(Fixtures.withoutMagnitude))) {
            val drawn = markers(target = target, limit = 3.0)
            assertEquals(listOf(target.id), drawn.map { it.id }, "faint, or no magnitude: only the target")
        }
        val withTarget = markers(target = Fixtures.obj(Fixtures.leonard))
        assertEquals(Fixtures.leonard.id, withTarget.first().id)
        assertEquals(markers().size + 1, withTarget.size)
        // A target that was never added to the catalog (a body fetched for one use) is drawn too.
        val stranger = Fixtures.obj(Fixtures.comet("C/2026 Z9", "Stranger", 12.0))
        assertEquals(stranger.id, markers(target = stranger, limit = 3.0).first().id)
    }

    @Test
    fun aBodyThatCannotBeEvaluatedIsLeftOut() {
        val oumuamua = Fixtures.obj(Fixtures.longGone)
        assertTrue(markers(target = oumuamua, limit = 30.0, source = listOf(oumuamua)).isEmpty())
    }

    @Test
    fun aHundredFaintDotsNeverFloodTheSky() {
        val crowd = (1..120).map { Fixtures.obj(Fixtures.oppositionComet("C/2026 Y$it", "Crowd $it")) }
        val drawn = markers(source = crowd)
        assertEquals(SmallBodySkyMarkers.MAXIMUM_COUNT, drawn.size)
    }

    @Test
    fun theSkylineHidesTheBodiesBehindIt() {
        val mask = HorizonMask(HorizonProfile.constant(80.0))
        val hidden = markers(mask = mask)
        assertTrue(hidden.isNotEmpty() && hidden.all { it.isBehindSkyline || it.horizontal.altitude >= 80.0 })
        assertTrue(markers().none { it.isBehindSkyline })
    }

    // Comets have a tail

    @Test
    fun onlyACometHasATailAndItIsOneDegreeLong() {
        val drawn = markers(limit = 30.0)
        val comet = assertNotNull(drawn.firstOrNull { it.id == opposition.id })
        val end = assertNotNull(comet.tailEnd)
        assertEquals(1.0, comet.direction.angle(end), 1e-6)
        assertTrue(comet.isComet)
        val asteroid = assertNotNull(drawn.firstOrNull { !it.isComet })
        assertNull(asteroid.tailEnd)
    }

    @Test
    fun theTailLiesWhereTheEngineSaysAgainstTheVertical() {
        val facing = HorizontalCoordinate(180.0, 40.0).unitVector
        val up = SmallBodySkyMarkers.tailEnd(facing, 0.0)!!
        assertTrue(up.z > facing.z, "straight up leaves the horizon behind")
        assertEquals(0.0, abs(up.x - facing.x), 1e-9)
        val right = SmallBodySkyMarkers.tailEnd(facing, 90.0)!!
        assertTrue(right.x < facing.x, "facing south, the right-hand side is west (negative x)")
        val down = SmallBodySkyMarkers.tailEnd(facing, 180.0)!!
        assertTrue(down.z < facing.z)
        val left = SmallBodySkyMarkers.tailEnd(facing, -90.0)!!
        assertTrue(left.x > facing.x)
        assertNull(SmallBodySkyMarkers.tailEnd(HorizontalCoordinate(0.0, 90.0).unitVector, 20.0), "no vertical at the zenith")
    }

    // The snapshot

    @Test
    fun theSnapshotCarriesTheSmallBodiesAndKeepsTheRest() {
        val snapshot = SkySnapshotBuilder.build(catalog, Fixtures.observer, Fixtures.date, SkySnapshotOptions(), target = null)
        val plain = SkySnapshotBuilder.build(Fixtures.bundled, Fixtures.observer, Fixtures.date, SkySnapshotOptions(), target = null)
        assertTrue(snapshot.smallBodies.any { it.id == opposition.id })
        assertTrue(plain.smallBodies.isEmpty())
        // They are not planets and not deep-sky objects.
        assertEquals(9, snapshot.bodies.size)
        assertEquals(plain.deepSky.map { it.id }, snapshot.deepSky.map { it.id })
        assertTrue(snapshot.deepSky.none { it.obj.smallBody != null })
        assertTrue(snapshot.bodies.none { it.obj.smallBody != null })
        assertEquals(plain.stars.size, snapshot.stars.size)
        // The magnitude limit is an option.
        val strict = SkySnapshotBuilder.build(
            catalog, Fixtures.observer, Fixtures.date, SkySnapshotOptions(smallBodyMagnitudeLimit = 5.0), target = null,
        )
        assertTrue(strict.smallBodies.none { it.id == opposition.id })
    }

    @Test
    fun aSmallBodyAsTheTargetIsPlacedLikeAnyTarget() {
        val target = Fixtures.obj(Fixtures.leonard)
        val snapshot = SkySnapshotBuilder.build(catalog, Fixtures.observer, Fixtures.date, SkySnapshotOptions(), target)
        val placed = assertNotNull(snapshot.target)
        assertEquals(frame.horizontal(target, true), placed.horizontal)
        assertEquals(target.id, snapshot.smallBodies.first().id)
        assertEquals(placed.direction, snapshot.smallBodies.first().direction)
    }

    @Test
    fun theSnapshotHidesWhatTheRecordedSkylineHides() {
        val snapshot = SkySnapshotBuilder.build(
            catalog, Fixtures.observer, Fixtures.date, SkySnapshotOptions(), target = null, horizon = HorizonProfile.constant(80.0),
        )
        assertTrue(snapshot.smallBodies.all { it.isBehindSkyline == (it.horizontal.altitude < 80.0) || abs(it.horizontal.altitude - 80.0) < 0.01 })
    }

    // The chart

    private fun marker(altitude: Double, azimuth: Double = 180.0, angle: Double? = 0.0, comet: Boolean = true): SkySnapshot.SmallBodyMarker {
        val horizontal = HorizontalCoordinate(azimuth, altitude)
        val obj = Fixtures.obj(if (comet) Fixtures.tsuchinshanAtlas else Fixtures.vesta)
        return SkySnapshot.SmallBodyMarker(
            obj, horizontal.unitVector, horizontal, 7.0,
            tailEnd = angle?.let { SmallBodySkyMarkers.tailEnd(horizontal.unitVector, it) },
        )
    }

    private fun chart(marker: SkySnapshot.SmallBodyMarker, target: String? = null) =
        SmallBodyChartMarkers.build(listOf(marker), target)

    @Test
    fun onTheChartVerticalIsTowardsTheZenith() {
        // Facing south at 40°: the chart has the object below the middle, the zenith above it.
        fun tail(angle: Double): Pair<Float, Float> = chart(marker(40.0, angle = angle)).single().let { it.tailX to it.tailY }
        val (upX, upY) = tail(0.0)
        assertEquals(0f, upX, 1e-3f)
        assertTrue(upY < -0.99f, "straight up is towards the chart's middle: $upY")
        val (rightX, _) = tail(90.0)
        assertTrue(rightX > 0.99f, "west is on the chart's right: $rightX")
        val (leftX, _) = tail(-90.0)
        assertTrue(leftX < -0.99f)
        val (_, downY) = tail(180.0)
        assertTrue(downY > 0.99f)
        for (angle in listOf(-170.0, -45.0, 10.0, 77.0, 135.0)) {
            val (x, y) = tail(angle)
            assertEquals(1f, hypot(x, y), 1e-4f, "unit length at $angle")
        }
    }

    @Test
    fun theChartFollowsTheTailAtAnyAzimuth() {
        // In the east at 30° the zenith is towards the middle too, whatever way the object lies.
        for (azimuth in listOf(0.0, 45.0, 90.0, 135.0, 225.0, 300.0)) {
            val item = chart(marker(30.0, azimuth = azimuth, angle = 0.0)).single()
            val length = hypot(item.x, item.y)
            val towardsMiddle = (-item.x * item.tailX - item.y * item.tailY) / length
            assertTrue(towardsMiddle > 0.999f, "azimuth $azimuth: $towardsMiddle")
        }
    }

    @Test
    fun anAsteroidAndATaillessCometHaveNoTailOnTheChart() {
        assertFalse(chart(marker(40.0, angle = null, comet = false)).single().hasTail)
        assertFalse(chart(marker(40.0, angle = null)).single().hasTail)
        assertTrue(chart(marker(40.0, angle = 20.0)).single().hasTail)
    }

    @Test
    fun theChartLeavesOutWhatIsBelowTheHorizonOrBehindTheSkyline() {
        assertTrue(chart(marker(-5.0)).isEmpty())
        assertEquals(1, chart(marker(5.0)).size)
        assertTrue(chart(marker(40.0).copy(isBehindSkyline = true)).isEmpty())
    }

    @Test
    fun theTargetIsFlaggedAndNotLabelledTwice() {
        val item = marker(40.0)
        val target = chart(item, target = item.id).single()
        assertTrue(target.isTarget)
        assertTrue(SmallBodyChartMarkers.labels(listOf(target)).isEmpty(), "the target has its own label")
        val other = chart(item).single()
        assertFalse(other.isTarget)
        val label = SmallBodyChartMarkers.labels(listOf(other)).single()
        assertEquals("smallBody.${item.id}", label.id)
        assertEquals("Tsuchinshan-ATLAS", label.text)
        assertEquals(SkyChartLabel.Style.BODY, label.style)
    }

    @Test
    fun brightBodiesAreNamedFromAWiderViewAndCometsOutrankAsteroids() {
        assertEquals(1f, SmallBodyMarkerStyle.labelZoom(5.0))
        assertEquals(1.8f, SmallBodyMarkerStyle.labelZoom(7.5))
        assertEquals(3f, SmallBodyMarkerStyle.labelZoom(8.7))
        assertEquals(3f, SmallBodyMarkerStyle.labelZoom(null))
        val comet = SmallBodyChartMarkers.labels(chart(marker(40.0))).single()
        val asteroid = SmallBodyChartMarkers.labels(chart(marker(40.0, comet = false, angle = null))).single()
        assertTrue(comet.priority > asteroid.priority)
        // Below the planets (90) and above the stars' names are a matter of the priorities.
        assertTrue(comet.priority < 90)
    }

    @Test
    fun theSceneDrawsPicksAndNamesTheSmallBodies() {
        val snapshot = SkySnapshotBuilder.build(catalog, Fixtures.observer, Fixtures.date, SkySnapshotOptions(), target = null)
        val scene = SkyChartSceneBuilder.build(snapshot)
        val item = assertNotNull(scene.smallBodies.firstOrNull { it.id == opposition.id })
        assertTrue(scene.smallBodies.all { it.horizontal.altitude >= -1.0 })
        assertTrue(scene.labels.any { it.id == "smallBody.${opposition.id}" })
        val pickable = assertNotNull(scene.pickables.firstOrNull { it.id == opposition.id })
        assertEquals(SkyChartPickable.Kind.SMALL_BODY, pickable.kind)
        assertEquals(opposition.id, pickable.obj?.id)
        // A tap on the dot selects the comet.
        val viewport = PlanisphereViewport(PlanisphereLayout(1000f, 1000f, 2f))
        val hit = scene.pickable(viewport.screenX(item.x, item.y), viewport.screenY(item.x, item.y), viewport)
        assertEquals(opposition.id, hit?.id)
        // Without small bodies in the catalog the scene is as before.
        val plain = SkyChartSceneBuilder.build(
            SkySnapshotBuilder.build(Fixtures.bundled, Fixtures.observer, Fixtures.date, SkySnapshotOptions(), target = null),
        )
        assertTrue(plain.smallBodies.isEmpty())
        assertTrue(plain.pickables.none { it.kind == SkyChartPickable.Kind.SMALL_BODY })
    }

    @Test
    fun theScreenReaderHearsTheBodiesAbovetheHorizon() {
        assertNull(SmallBodyChartMarkers.spokenSummary(emptyList()))
        val items = chart(marker(40.0, azimuth = 180.0))
        assertEquals(
            "Comets and asteroids above the horizon. Tsuchinshan-ATLAS, comet, altitude 40 degrees, south.",
            SmallBodyChartMarkers.spokenSummary(items),
        )
        val many = (1..7).flatMap { chart(marker(40.0 + it)) }
        assertTrue(assertNotNull(SmallBodyChartMarkers.spokenSummary(many)).endsWith("And 2 more."))
    }

    @Test
    fun theDotsGrowWithBrightnessWithinLimits() {
        assertEquals(4.5f, SmallBodyMarkerStyle.dotRadius(0.0, true))
        assertEquals(2.5f, SmallBodyMarkerStyle.dotRadius(12.0, true))
        assertEquals(2.5f, SmallBodyMarkerStyle.dotRadius(null, true))
        assertTrue(SmallBodyMarkerStyle.dotRadius(5.0, true) > SmallBodyMarkerStyle.dotRadius(8.0, true))
        assertEquals(3.4f, SmallBodyMarkerStyle.dotRadius(0.0, false))
        assertEquals(2.0f, SmallBodyMarkerStyle.dotRadius(12.0, false))
        assertEquals(12f, SmallBodyMarkerStyle.tailLength(null))
        assertEquals(22f, SmallBodyMarkerStyle.tailLength(0.0))
        assertEquals(12f, SmallBodyMarkerStyle.tailLength(9.0))
    }

    @Test
    fun theAimOverlayNamesOnlyTheBrightOnes() {
        assertTrue(isNamed(6.0, 60.0))
        assertTrue(isNamed(8.0, 60.0))
        assertFalse(isNamed(8.5, 60.0))
        assertTrue(isNamed(8.5, 30.0), "a narrow view names the fainter ones")
        assertFalse(isNamed(9.5, 30.0))
        assertFalse(isNamed(null, 10.0))
    }

    @Test
    fun theColoursComeFromThePaletteInBothModes() {
        for (palette in listOf(Palette.normal, Palette.night(1.0))) {
            assertEquals(palette.deepSkyMarker, SmallBodyMarkerStyle.color(palette, isComet = true))
            assertEquals(palette.secondaryText, SmallBodyMarkerStyle.color(palette, isComet = false))
        }
        val night = Palette.night(1.0)
        for (isComet in listOf(true, false)) {
            val color = SmallBodyMarkerStyle.color(night, isComet)
            assertEquals(0f, color.green)
            assertEquals(0f, color.blue)
        }
    }
}
