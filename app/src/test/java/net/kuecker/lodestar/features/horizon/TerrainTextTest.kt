package net.kuecker.lodestar.features.horizon

import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.horizon.TerrainHorizonResult
import net.kuecker.lodestar.kit.horizon.TerrainTileKey
import net.kuecker.lodestar.services.TerrainComputation
import net.kuecker.lodestar.services.TerrainHorizonLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The words of the terrain horizon section. */
class TerrainTextTest {
    private fun key(latitude: Int, longitude: Int) = assertNotNull(TerrainTileKey.create(latitude, longitude))

    private fun done(
        tilesUsed: Int = 3,
        ground: Double? = 412.4,
        missing: List<TerrainTileKey> = emptyList(),
        unavailable: List<TerrainTileKey> = emptyList(),
        reach: Double = 49_760.0,
        voids: Double = 0.0,
    ) = TerrainComputation.Done(
        TerrainHorizonResult(
            profile = HorizonProfile.flat, altitudes = List(360) { 0.0 }, groundElevationMeters = ground,
            coveredRangeMeters = List(360) { reach }, missingTiles = missing, voidSampleShare = voids,
        ),
        tilesUsed, unavailable,
    )

    @Test
    fun aCompleteComputationSaysHowManyTilesAndWhereTheGroundIs() {
        val summary = TerrainText.summary(done())
        assertEquals(
            listOf(
                "Terrain horizon computed from 3 elevation tiles.",
                "The elevation data puts the ground at the site at 412 m.",
            ),
            summary.lines,
        )
        assertEquals(412, summary.groundElevationMeters)
        assertEquals("Terrain horizon computed from 1 elevation tile.", TerrainText.summary(done(tilesUsed = 1)).lines.first())
    }

    @Test
    fun withoutAGroundElevationNoneIsOffered() {
        val summary = TerrainText.summary(done(ground = null))
        assertNull(summary.groundElevationMeters)
        assertFalse(summary.lines.any { it.contains("ground") })
    }

    @Test
    fun tilesTheProviderDoesNotHaveAreNamed() {
        val sea = key(48, 8)
        val summary = TerrainText.summary(done(tilesUsed = 2, missing = listOf(sea), unavailable = listOf(sea), reach = 37_000.0))
        assertTrue(summary.lines.contains("No elevation data for N48E008 (open sea or outside the tile set): nothing there is in the skyline."))
        // Missing because the provider has no file, not because only four tiles are used.
        assertFalse(summary.lines.any { it.startsWith("Not downloaded") })
    }

    @Test
    fun tilesBeyondTheNearestFourAreNamedWithTheReachOfTheData() {
        val summary = TerrainText.summary(done(tilesUsed = 4, missing = listOf(key(47, 7), key(46, 9)), reach = 37_600.0))
        assertTrue(
            summary.lines.contains(
                "Not downloaded (only the ${TerrainHorizonLoader.MAXIMUM_TILES} nearest tiles are used): N47E007 and N46E009. " +
                    "The skyline reaches 38 km in the worst direction.",
            ),
            summary.lines.toString(),
        )
    }

    @Test
    fun voidsAreMentionedFromOnePercent() {
        assertFalse(TerrainText.summary(done(voids = 0.009)).lines.any { it.contains("sea level") })
        assertTrue(TerrainText.summary(done(voids = 0.01)).lines.any { it.contains("count as sea level") })
    }

    @Test
    fun listsOfTilesReadAsSentences() {
        assertEquals("", TerrainText.names(emptyList()))
        assertEquals("N47E007", TerrainText.names(listOf(key(47, 7))))
        assertEquals("N47E007 and N46E008", TerrainText.names(listOf(key(47, 7), key(46, 8))))
        assertEquals("N47E007, N46E008 and N46E007", TerrainText.names(listOf(key(47, 7), key(46, 8), key(46, 7))))
    }

    @Test
    fun theConfirmationNamesTheTilesTheSizeAndWhatTheServerSees() {
        val three = TerrainText.confirmMessage(3)
        assertTrue(three.contains("3 elevation tiles of about 11 MB each"), three)
        assertTrue(three.contains("sees your IP address and which 1° tiles you request"), three)
        assertTrue(TerrainText.confirmMessage(1).contains("1 elevation tile of about"))
    }

    @Test
    fun theProgressLineNamesTheTile() {
        val tile = key(47, 8)
        assertEquals(
            "Downloading elevation tile 2 of 3 (N47E008)…",
            TerrainText.progress(TerrainHorizonLoader.Progress.Downloading(2, 3, tile)),
        )
        assertEquals(
            "Reading elevation tile 2 of 3 (N47E008)…",
            TerrainText.progress(TerrainHorizonLoader.Progress.Reading(2, 3, tile)),
        )
        assertEquals("Use 412 m as the Site Elevation", TerrainText.useElevation(412))
    }

    @Test
    fun theTextsDoNotMentionAnotherPlatform() {
        val texts = listOf(
            TerrainText.FOOTER, TerrainText.SWITCHED_OFF, TerrainText.SAVE_FIRST, TerrainText.MOVED, TerrainText.MOVED_STORED,
            TerrainText.REMOVE_MESSAGE, TerrainText.STRIP_NOTE, TerrainText.confirmMessage(2),
        )
        for (text in texts) assertFalse(text.contains("iPhone") || text.contains("iOS"), text)
    }
}
