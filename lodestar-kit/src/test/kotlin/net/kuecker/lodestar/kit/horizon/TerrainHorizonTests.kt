package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.horizon.TerrainTestSupport as Support
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TerrainHorizon] on synthetic tiles. The expected skylines are worked out from the formula of the class description:
 * `atan((h − h0) / d) − d / (2 R′)`, `R′ = R / (1 − 0.13)`, `R` = 6 371 008.8 m. Over flat ground the horizon dips by
 * `√(2h / R′)`: 0.0423° for an eye 2 m high (the geometric dip, without refraction, is 0.0454°).
 */
class TerrainHorizonTests {
    private val site = GeographicLocation(47.5, 8.5, 0.0)

    private fun key(latitude: Int, longitude: Int): TerrainTileKey = assertNotNull(TerrainTileKey.create(latitude, longitude))

    private fun names(keys: List<TerrainTileKey>): List<String> = keys.map { it.name }

    // MARK: Geometry

    @Test
    fun testApparentAltitudeOfAPointAboveTheEye() {
        // 100 m above the eye at 10 km: atan(0.01) = 0.572939° less 10 000 / (2 · 7 323 001) rad = 0.039120°.
        assertEquals(0.533819, TerrainHorizon.apparentAltitude(100.0, 10_000.0), 1e-5)
        // Level with the eye at 5 km: only the drop of the surface, 0.019560°.
        assertEquals(-0.019560, TerrainHorizon.apparentAltitude(0.0, 5_000.0), 1e-5)
        // Refraction makes the drop 13% smaller than the geometric d / 2R.
        val geometric = -AstroMath.degrees(5_000.0 / (2 * TerrainHorizon.earthRadiusMeters))
        assertEquals(0.87, TerrainHorizon.apparentAltitude(0.0, 5_000.0) / geometric, 1e-12)
    }

    @Test
    fun testSampleDistancesFollowTheStepSchedule() {
        val distances = TerrainHorizon.sampleDistances(50_000.0)
        assertEquals(306, distances.size)
        assertEquals(30.0, distances.first())
        assertEquals(60.0, distances[1])
        assertEquals(1980.0, distances[65])
        assertEquals(2010.0, distances[66])
        assertEquals(2110.0, distances[67])
        assertEquals(9910.0, distances[145])
        assertEquals(10_010.0, distances[146])
        assertEquals(10_260.0, distances[147])
        assertEquals(49_760.0, distances.last())
        assertEquals(listOf(30.0, 60.0, 90.0), TerrainHorizon.sampleDistances(1_000.0).take(3))
        assertEquals(33, TerrainHorizon.sampleDistances(1_000.0).size)
        // Out of range values are limited, a value that is not finite means the default.
        assertEquals(TerrainHorizon.sampleDistances(1_000.0), TerrainHorizon.sampleDistances(5.0))
        assertEquals(distances, TerrainHorizon.sampleDistances(Double.NaN))
        assertEquals(TerrainHorizon.sampleDistances(100_000.0), TerrainHorizon.sampleDistances(1e9))
    }

    // MARK: Tiles needed

    @Test
    fun testRequiredTilesAroundAMidLatitudeSite() {
        val tiles = TerrainHorizon.requiredTiles(GeographicLocation(47.3, 8.4))
        assertEquals("N47E008", tiles.first().name)
        assertEquals(setOf("N47E008", "N47E007", "N47E009", "N46E008", "N46E007"), names(tiles).toSet())
        // The same site with a 10 km range needs only its own tile.
        assertEquals(listOf("N47E008"), names(TerrainHorizon.requiredTiles(GeographicLocation(47.3, 8.4), 10_000.0)))
    }

    @Test
    fun testRequiredTilesAtTheCentreOfATileAreOrderedByDistanceThenCorner() {
        // 0.5° of longitude is 37.6 km at 47.5° N: both neighbours are in range and equally far, the western one first.
        assertEquals(listOf("N47E008", "N47E007", "N47E009"), names(TerrainHorizon.requiredTiles(site)))
    }

    @Test
    fun testRequiredTilesNeedSixAtMidLatitudesAndFourAtTheEquator() {
        assertEquals(6, TerrainHorizon.requiredTiles(GeographicLocation(47.9, 8.5)).size)
        assertEquals(4, TerrainHorizon.requiredTiles(GeographicLocation(0.05, 0.05)).size)
        assertEquals(
            setOf("N00E000", "S01E000", "N00W001", "S01W001"),
            names(TerrainHorizon.requiredTiles(GeographicLocation(0.05, 0.05))).toSet(),
        )
    }

    @Test
    fun testRequiredTilesWrapAroundTheAntimeridian() {
        val tiles = names(TerrainHorizon.requiredTiles(GeographicLocation(0.5, 179.9)))
        assertTrue("N00E179" in tiles)
        assertTrue("N00W180" in tiles)
        assertEquals("N00E179", tiles.first())
        val west = names(TerrainHorizon.requiredTiles(GeographicLocation(0.5, -179.9)))
        assertTrue("N00W180" in west)
        assertTrue("N00E179" in west)
    }

    @Test
    fun testRequiredTilesAreFewNearThePoles() {
        val north = TerrainHorizon.requiredTiles(GeographicLocation(89.5, 10.0))
        assertTrue(north.size in 2..TerrainHorizon.maximumTileCount)
        assertEquals(north.size, north.toSet().size)
        assertTrue(north.all { it.latitude <= 89 })
        val south = TerrainHorizon.requiredTiles(GeographicLocation(-90.0, 0.0))
        assertTrue(south.size in 1..TerrainHorizon.maximumTileCount)
        assertTrue(south.all { it.latitude == -90 || it.latitude == -89 })
    }

    // MARK: Flat ground

    @Test
    fun testFlatTileGivesTheDipOfTheHorizonAtTwoMetres() {
        val result = TerrainHorizon.profile(site, listOf(Support.flatTile(47, 8, 400)))
        // The dip: √(2 · 2 / R′) = 7.39e-4 rad = 0.0423°, a little less than the 0.0454° without refraction.
        val dip = Support.flatDip(2.0)
        assertEquals(-0.04235, dip, 2e-5)
        assertEquals(360, result.altitudes.size)
        for (altitude in result.altitudes) assertEquals(dip, altitude, 5e-5)
        // The profile has a resolution of 0.1°, so the dip rounds to nothing.
        assertTrue(result.profile.isFlat)
        assertEquals(400.0, result.groundElevationMeters)
        assertEquals(0.0, result.voidSampleShare)
    }

    @Test
    fun testTheEyeHeightSetsTheDip() {
        val tiles = listOf(Support.flatTile(47, 8, 400))
        // 10 m: √(2 · 10 / R′) = 0.0947°, which the profile shows as −0.1°.
        val ten = TerrainHorizon.profile(site, tiles, eyeHeightMeters = 10.0)
        assertEquals(-0.0947, Support.flatDip(10.0), 1e-4)
        for (altitude in ten.altitudes) assertEquals(Support.flatDip(10.0), altitude, 5e-5)
        assertTrue(ten.profile.altitudes.all { it == -0.1 })
        // Out of range or not finite values are limited, or replaced by the 2 m default.
        val high = TerrainHorizon.profile(site, tiles, eyeHeightMeters = 1e6)
        assertEquals(Support.flatDip(100.0), high.altitudes[0], 5e-5)
        assertTrue(high.profile.altitudes.all { it == -0.3 })
        // An eye at the ground sees the surface fall away from the first step on: −30 m / 2R′ = −0.000117°.
        val negative = TerrainHorizon.profile(site, tiles, eyeHeightMeters = -5.0)
        assertEquals(TerrainHorizon.apparentAltitude(0.0, 30.0), negative.altitudes[0], 1e-9)
        val unknown = TerrainHorizon.profile(site, tiles, eyeHeightMeters = Double.NaN)
        assertEquals(Support.flatDip(2.0), unknown.altitudes[200], 5e-5)
    }

    @Test
    fun testTheGroundOfTheTileIsTheReferenceNotTheSiteElevation() {
        val elevated = GeographicLocation(47.5, 8.5, 1234.0)
        val result = TerrainHorizon.profile(elevated, listOf(Support.flatTile(47, 8, 400)))
        assertEquals(400.0, result.groundElevationMeters)
        assertEquals(Support.flatDip(2.0), result.altitudes[45], 5e-5)
    }

    @Test
    fun testCoveredRangeEndsWhereTheTilesDo() {
        val result = TerrainHorizon.profile(site, listOf(Support.flatTile(47, 8, 400)))
        // North and south: the tile reaches 55 km, so the whole 50 km range is covered (the last step is 49 760 m).
        assertEquals(49_760.0, result.coveredRangeMeters[0])
        assertEquals(49_760.0, result.coveredRangeMeters[180])
        // East and west: the tile ends 37.6 km from the centre.
        for (azimuth in listOf(90, 270)) {
            assertTrue(result.coveredRangeMeters[azimuth] in 37_000.0..37_600.0, "${result.coveredRangeMeters[azimuth]}")
        }
        assertEquals(result.coveredRangeMeters.min(), result.minimumCoveredRangeMeters)
        assertEquals(names(listOf(key(47, 7), key(47, 9))), names(result.missingTiles))
        assertFalse(result.isComplete)
    }

    @Test
    fun testNoTilesGiveAFlatSkylineAndEveryTileMissing() {
        val result = TerrainHorizon.profile(GeographicLocation(47.5, 8.5, 123.0), emptyList())
        assertTrue(result.profile.isFlat)
        assertNull(result.groundElevationMeters)
        assertTrue(result.coveredRangeMeters.all { it == 0.0 })
        assertEquals(TerrainHorizon.requiredTiles(site), result.missingTiles)
        assertEquals(0.0, result.voidSampleShare)
        assertEquals(0.0, result.minimumCoveredRangeMeters)
    }

    @Test
    fun testATileGivenTwiceCountsOnceAndTilesNotNeededAreIgnored() {
        val once = TerrainHorizon.profile(site, listOf(Support.flatTile(47, 8, 400)))
        val twice = TerrainHorizon.profile(site, listOf(Support.flatTile(47, 8, 400), Support.flatTile(47, 8, 900)))
        assertEquals(once, twice)
        val far = TerrainHorizon.profile(site, listOf(Support.flatTile(47, 8, 400), Support.flatTile(10, 10, 900)))
        assertEquals(once, far)
    }

    // MARK: A hill

    /** A hill 300 m high and 1 km in radius whose summit is [distance] m from the site towards [azimuth]. */
    private fun hillAt(azimuth: Double, distance: Double, height: Double = 300.0, radius: Double = 1000.0):
        (Double, Double) -> Double {
        val (apexLatitude, apexLongitude) = Support.destination(47.5, 8.5, azimuth, distance)
        return Support.dome(apexLatitude, apexLongitude, height, radius)
    }

    @Test
    fun testHillFiveKilometresEastRisesAboveTheHorizon() {
        // The summit is 5010 m east: one of the steps of the ray.
        val hill = hillAt(90.0, 5_010.0)
        val result = TerrainHorizon.profile(site, listOf(Support.tile(47, 8, hill)))
        // atan((300 − 2) / 5010) = 3.4041° less the drop of 5010 m, 0.0196°. The highest apparent point of a round hill is
        // a little before its summit (the hill is rising there), so the skyline is read off the whole ray.
        val summit = TerrainHorizon.apparentAltitude(300.0 - 2.0, 5_010.0)
        assertEquals(3.3845, summit, 2e-4)
        val expected = Support.expectedSkyline(47.5, 8.5, 90.0, 0.0, hill)
        assertEquals(summit, expected, 0.05)
        assertEquals(expected, result.altitudes[90], 0.03)
        assertEquals(3.4, result.profile.altitude(90.0), 0.1)
        // The summit is the highest point of the skyline, and the hill is narrow: elsewhere is the dip.
        assertEquals(90, result.profile.altitudes.indices.maxByOrNull { result.altitudes[it] })
        assertEquals(Support.flatDip(2.0), result.altitudes[270], 5e-5)
        assertEquals(Support.flatDip(2.0), result.altitudes[0], 5e-5)
        assertEquals(Support.flatDip(2.0), result.altitudes[180], 5e-5)
        // The flanks fall away: a hill 1 km in radius, seen from 5 km, spans about ±11° round the east.
        assertTrue(result.altitudes[100] < result.altitudes[95])
        assertTrue(result.altitudes[95] < result.altitudes[90])
        assertTrue(result.altitudes[85] < result.altitudes[90])
        assertEquals(Support.flatDip(2.0), result.altitudes[120], 5e-5)
        assertEquals(Support.expectedSkyline(47.5, 8.5, 95.0, 0.0, hill), result.altitudes[95], 0.03)
    }

    @Test
    fun testTheSkylineIsMeasuredFromTheEyeNotFromSeaLevel() {
        // The same hill with the whole land 500 m up: the site's ground is 500 m, the summit 800 m.
        val dome = hillAt(90.0, 5_010.0)
        val raised = { latitude: Double, longitude: Double -> 500 + dome(latitude, longitude) }
        val result = TerrainHorizon.profile(site, listOf(Support.tile(47, 8, raised)))
        assertEquals(500.0, result.groundElevationMeters)
        val expected = Support.expectedSkyline(47.5, 8.5, 90.0, 500.0, raised)
        assertEquals(TerrainHorizon.apparentAltitude(300.0 - 2.0, 5_010.0), expected, 0.05)
        assertEquals(expected, result.altitudes[90], 0.03)
    }

    @Test
    fun testTheSkylineIsClampedToWhatAProfileCanHold() {
        // A 3000 m spike 300 m away is at 85°: the profile stops at 60°, the unclamped value stays in altitudes.
        val spike = hillAt(90.0, 300.0, height = 3000.0, radius = 250.0)
        val result = TerrainHorizon.profile(site, listOf(Support.tile(47, 8, spike)))
        assertEquals(0.0, result.groundElevationMeters)
        assertTrue(result.altitudes[90] > 80, "${result.altitudes[90]}")
        assertEquals(60.0, result.profile.altitude(90.0))
    }

    // MARK: Seams

    @Test
    fun testARayCrossingATileSeamSeesTheHillOnTheOtherSide() {
        // The site is 1.1 km south of the seam at 48° N; the hill is 5010 m due north, in the next tile.
        val near = GeographicLocation(47.99, 8.5, 0.0)
        val (apexLatitude, apexLongitude) = Support.destination(47.99, 8.5, 0.0, 5_010.0)
        val hill = Support.dome(apexLatitude, apexLongitude, 300.0, 1000.0)
        val south = Support.tile(47, 8) { latitude, longitude -> hill(latitude, longitude) }
        val north = Support.tile(48, 8) { latitude, longitude -> hill(latitude, longitude) }
        val both = TerrainHorizon.profile(near, listOf(south, north))
        val expected = Support.expectedSkyline(47.99, 8.5, 0.0, 0.0, hill)
        assertEquals(3.3845, expected, 0.05)
        assertEquals(expected, both.altitudes[0], 0.03)
        assertEquals(3.4, both.profile.altitude(0.0), 0.1)
        assertTrue(both.coveredRangeMeters[0] > 49_000)
        assertFalse(key(48, 8) in both.missingTiles)
        assertTrue(key(47, 7) in both.missingTiles)

        // Without the northern tile the ray sees nothing beyond the seam, and the tile is reported.
        val onlySouth = TerrainHorizon.profile(near, listOf(south))
        // Only the first 1.1 km of the ray is on the tile, so its highest point is the last one: −2 m / 1110 m = −0.1076°.
        assertTrue(onlySouth.coveredRangeMeters[0] in 1_080.0..1_112.0, "${onlySouth.coveredRangeMeters[0]}")
        assertEquals(TerrainHorizon.apparentAltitude(-2.0, onlySouth.coveredRangeMeters[0]), onlySouth.altitudes[0], 1e-9)
        assertEquals(-0.1076, onlySouth.altitudes[0], 1e-3)
        assertTrue(key(48, 8) in onlySouth.missingTiles)
        assertEquals(names(listOf(key(48, 8))), names(onlySouth.missingTiles).filter { it == "N48E008" })
    }

    // MARK: Voids

    @Test
    fun testVoidsAreSeaLevelAndAreCounted() {
        // All land at 400 m, but everything east of 8.583° E (6.2 km from the site) is a void.
        val side = TerrainTile.lowResolutionSamples
        val data = Support.bytes(side) { _, column -> if (column >= 700) -32768 else 400 }
        val tile = TerrainTile.parse(47, 8, data)
        val result = TerrainHorizon.profile(site, listOf(tile))
        // Voids count as 0 m, far below the eye (400 m), so they never raise the skyline.
        assertEquals(Support.flatDip(2.0), result.altitudes[90], 5e-5)
        assertTrue(result.voidSampleShare > 0.05, "${result.voidSampleShare}")
        assertTrue(result.voidSampleShare < 0.5)
    }

    @Test
    fun testAVoidAtTheSiteFallsBackToTheSiteElevation() {
        // 400 m everywhere, but a void patch of 5 × 5 samples (about 90 m) round the site.
        val side = TerrainTile.lowResolutionSamples
        val data = Support.bytes(side) { row, column -> if (abs(row - 600) <= 2 && abs(column - 600) <= 2) -32768 else 400 }
        val tile = TerrainTile.parse(47, 8, data)
        val located = GeographicLocation(47.5, 8.5, 400.0)
        val result = TerrainHorizon.profile(located, listOf(tile))
        assertNull(result.groundElevationMeters)
        // The eye is at the site's own elevation plus 2 m, so the flat ground beyond the patch gives the usual dip.
        assertEquals(Support.flatDip(2.0), result.altitudes[30], 5e-5)
        assertTrue(result.voidSampleShare > 0)
    }

    // MARK: Merging

    private fun ramp(offset: Double): HorizonProfile =
        assertNotNull(HorizonProfile.create(List(360) { offset + it.toDouble() / 10 }))

    @Test
    fun testMergedUsesTheRecordedSkylineWhereTheSweepWasAndTheTerrainInTheGaps() {
        val recorded = HorizonProfile.constant(5.0)
        val terrain = ramp(-5.0)
        val merged = assertNotNull(HorizonProfile.merged(recorded, terrain, listOf(AzimuthRange(79.5, 16.0))))
        for (bin in 0 until 360) {
            val expected = if (bin in 80..95) terrain.altitudes[bin] else 5.0
            assertEquals(expected, merged.altitudes[bin], "bin $bin")
        }
    }

    @Test
    fun testMergedGapsMayRunThroughNorthAndSeveralMayExist() {
        val recorded = HorizonProfile.constant(5.0)
        val terrain = ramp(0.0)
        val merged = assertNotNull(
            HorizonProfile.merged(
                recorded, terrain,
                listOf(AzimuthRange(349.5, 21.0), AzimuthRange(179.5, 2.0)),
            ),
        )
        val fromTerrain = (350..359).toSet() + (0..10).toSet() + setOf(180, 181)
        for (bin in 0 until 360) {
            assertEquals(if (bin in fromTerrain) terrain.altitudes[bin] else 5.0, merged.altitudes[bin], "bin $bin")
        }
    }

    @Test
    fun testMergedWithOnlyOneSourceOrNoGapsIsThatSource() {
        val recorded = HorizonProfile.constant(5.0)
        val terrain = ramp(0.0)
        assertNull(HorizonProfile.merged(null, null))
        assertNull(HorizonProfile.merged(null, null, listOf(AzimuthRange.full)))
        assertEquals(terrain, HorizonProfile.merged(null, terrain))
        assertEquals(terrain, HorizonProfile.merged(null, terrain, listOf(AzimuthRange(10.0, 5.0))))
        assertEquals(recorded, HorizonProfile.merged(recorded, null, listOf(AzimuthRange.full)))
        assertEquals(recorded, HorizonProfile.merged(recorded, terrain))
        assertEquals(recorded, HorizonProfile.merged(recorded, terrain, emptyList()))
        assertEquals(terrain, HorizonProfile.merged(recorded, terrain, listOf(AzimuthRange.full)))
    }

    // MARK: Unswept stretches

    private fun mask(unswept: List<IntRange>): List<Boolean> = List(360) { bin -> unswept.none { bin in it } }

    @Test
    fun testUnsweptRangesOfAnEmptyAndAFullSweep() {
        assertEquals(listOf(AzimuthRange.full), HorizonProfile.unsweptRanges(List(360) { false }))
        assertEquals(emptyList(), HorizonProfile.unsweptRanges(List(360) { true }))
    }

    @Test
    fun testUnsweptRangesWrapThroughNorthAndStartAtTheLowerEdgeOfTheFirstDegree() {
        val swept = mask(listOf(200..359, 0..99))
        assertEquals(listOf(AzimuthRange(199.5, 260.0)), HorizonProfile.unsweptRanges(swept))
        assertEquals(listOf(AzimuthRange(359.5, 10.0)), HorizonProfile.unsweptRanges(mask(listOf(0..9))))
        assertEquals(listOf(AzimuthRange(349.5, 21.0)), HorizonProfile.unsweptRanges(mask(listOf(350..359, 0..10))))
    }

    @Test
    fun testUnsweptRangesAreInAzimuthOrderAndShortGapsAreFilledByTheRecorder() {
        val swept = mask(listOf(300..320, 20..40, 100..105, 200..206))
        assertEquals(
            listOf(AzimuthRange(19.5, 21.0), AzimuthRange(199.5, 7.0), AzimuthRange(299.5, 21.0)),
            HorizonProfile.unsweptRanges(swept),
        )
        // The limit is the recorder's, 6 degrees: six unswept degrees are interpolated, seven are a gap.
        assertEquals(emptyList(), HorizonProfile.unsweptRanges(mask(listOf(10..15))))
        assertEquals(1, HorizonProfile.unsweptRanges(mask(listOf(10..16))).size)
        assertEquals(1, HorizonProfile.unsweptRanges(mask(listOf(10..10)), maximumInterpolatedGap = 0).size)
        assertEquals(emptyList(), HorizonProfile.unsweptRanges(mask(listOf(10..39)), maximumInterpolatedGap = 99))
    }

    @Test
    fun testUnsweptRangesOfAMaskThatIsNotACircleAreNone() {
        assertEquals(emptyList(), HorizonProfile.unsweptRanges(emptyList()))
        assertEquals(emptyList(), HorizonProfile.unsweptRanges(List(359) { false }))
        assertEquals(emptyList(), HorizonProfile.unsweptRanges(List(361) { false }))
    }

    @Test
    fun testTheRecordersOwnGapsAgreeWithUnsweptRanges() {
        // A recorder that swept 0…179 and 250…359 reports the same stretch as the mask it publishes.
        val recorder = HorizonRecorder()
        for (azimuth in (0..179) + (250..359)) recorder.add(azimuth.toDouble(), 3.0, azimuth.toDouble() * 0.02)
        assertEquals(recorder.gaps, HorizonProfile.unsweptRanges(recorder.recordedMask))
    }
}
