package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.GeographicLocation
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The tile files of the terrain horizon: inflating them and fetching the tiles around a site. No network. */
class TerrainHorizonLoaderTest {
    private val directory: File = Files.createTempDirectory("terrain-tiles").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** The bytes of a 1201 × 1201 tile of [meters] everywhere. */
    private fun tile(meters: Int): ByteArray = ByteArray(1201 * 1201 * 2) { index -> if (index % 2 == 0) (meters shr 8).toByte() else meters.toByte() }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun downloaded(bytes: ByteArray) = OnlineResult.Downloaded(bytes, null, null, null)

    private fun notFound() = OnlineResult.Failed(OnlineFailure.HTTP_ERROR, 404)

    /** A loader whose "network" is [answers] by address; [asked] lists the addresses in the order they were requested. */
    private class FakeTiles(private val answers: (String) -> OnlineResult) {
        val asked = ArrayList<String>()

        fun fetch(url: String, @Suppress("UNUSED_PARAMETER") isCancelled: () -> Boolean): OnlineResult {
            asked.add(url.removePrefix(TerrainHorizonLoader.BASE_URL))
            return answers(url)
        }
    }

    private fun loader(fake: FakeTiles) = TerrainHorizonLoader(directory, fake::fetch)

    private val centre = GeographicLocation(47.5, 8.5, 0.0)

    // MARK: Inflating

    @Test
    fun aTileFileInflatesToItsBytes() {
        val bytes = tile(400)
        assertTrue(bytes.contentEquals(TerrainTileInflater.inflate(gzip(bytes))))
    }

    @Test
    fun theThreeArcSecondAndOneArcSecondSizesAreAccepted() {
        val big = ByteArray(3601 * 3601 * 2)
        assertEquals(big.size, TerrainTileInflater.inflate(gzip(big)).size)
    }

    @Test
    fun aFileThatIsNotATileIsRefused() {
        assertFailsWith<TerrainTileException> { TerrainTileInflater.inflate(ByteArray(0)) }
        assertFailsWith<TerrainTileException> { TerrainTileInflater.inflate(ByteArray(100) { it.toByte() }) }
        // A gzip file of the wrong size: refused from its trailer, before anything is inflated.
        assertFailsWith<TerrainTileException> { TerrainTileInflater.inflate(gzip(ByteArray(1000))) }
        assertFailsWith<TerrainTileException> { TerrainTileInflater.inflate(gzip(ByteArray(1201 * 1201 * 2 + 2))) }
    }

    @Test
    fun aFileCutShortIsRefused() {
        val whole = gzip(tile(400))
        // Half of the data and the original trailer: the trailer promises a whole tile, the data is not there.
        val cut = whole.copyOfRange(0, 10 + (whole.size - 18) / 2) + whole.copyOfRange(whole.size - 8, whole.size)
        assertFailsWith<TerrainTileException> { TerrainTileInflater.inflate(cut) }
    }

    @Test
    fun dataThatGoesOnAfterTheTrailerSizeIsRefused() {
        // The trailer says a tile, the data is a tile and a bit more.
        val longer = gzip(ByteArray(1201 * 1201 * 2 + 5))
        val lie = longer.copyOfRange(0, longer.size - 4) + byteArrayOf(0xC2.toByte(), 0x04, 0x2C, 0x00) // ISIZE 2 884 802
        assertEquals(2_884_802, 0x002C04C2)
        assertFailsWith<TerrainTileException> { TerrainTileInflater.inflate(lie) }
    }

    // MARK: Fetching

    @Test
    fun theTilesAroundASiteAreFetchedNearestFirstAndDeleted() {
        val fake = FakeTiles { downloaded(gzip(tile(400))) }
        val progress = ArrayList<TerrainHorizonLoader.Progress>()
        val outcome = loader(fake).fetchTiles(centre, { progress.add(it) }, { false })
        val tiles = assertIs<TerrainHorizonLoader.Outcome.Tiles>(outcome)
        assertEquals(listOf("N47/N47E008.hgt.gz", "N47/N47E007.hgt.gz", "N47/N47E009.hgt.gz"), fake.asked)
        assertEquals(listOf("N47E008", "N47E007", "N47E009"), tiles.tiles.map { it.key.name })
        assertEquals(emptyList(), tiles.unavailable)
        assertEquals(400.0, tiles.tiles.first().elevation(47.5, 8.5))
        assertEquals(3, progress.count { it is TerrainHorizonLoader.Progress.Downloading })
        assertEquals(3, progress.count { it is TerrainHorizonLoader.Progress.Reading })
        assertEquals(TerrainHorizonLoader.Progress.Downloading(1, 3, tiles.tiles.first().key), progress.first())
        assertEquals(emptyList(), directory.listFiles()?.toList().orEmpty())
    }

    @Test
    fun aTileTheProviderHasNoFileForIsReportedNotAnError() {
        val fake = FakeTiles { url -> if (url.endsWith("N47E009.hgt.gz")) notFound() else downloaded(gzip(tile(0))) }
        val outcome = assertIs<TerrainHorizonLoader.Outcome.Tiles>(loader(fake).fetchTiles(centre, {}, { false }))
        assertEquals(listOf("N47E008", "N47E007"), outcome.tiles.map { it.key.name })
        assertEquals(listOf("N47E009"), outcome.unavailable.map { it.name })
    }

    @Test
    fun noDataAtAllIsAFailure() {
        val outcome = loader(FakeTiles { notFound() }).fetchTiles(centre, {}, { false })
        assertEquals("No elevation data is available for this place.", assertIs<TerrainHorizonLoader.Outcome.Failed>(outcome).message)
    }

    @Test
    fun aFailedDownloadStopsTheComputationAndNamesTheTile() {
        val fake = FakeTiles { url ->
            if (url.endsWith("N47E007.hgt.gz")) OnlineResult.Failed(OnlineFailure.TIMEOUT) else downloaded(gzip(tile(0)))
        }
        val outcome = assertIs<TerrainHorizonLoader.Outcome.Failed>(loader(fake).fetchTiles(centre, {}, { false }))
        assertTrue(outcome.message.contains("N47E007"), outcome.message)
        assertTrue(outcome.message.contains(OnlineFailure.TIMEOUT.message), outcome.message)
        assertEquals(2, fake.asked.size)
        assertEquals(emptyList(), directory.listFiles()?.toList().orEmpty())
    }

    @Test
    fun aTileThatCannotBeReadStopsTheComputation() {
        val outcome = loader(FakeTiles { downloaded(gzip(ByteArray(1000))) }).fetchTiles(centre, {}, { false })
        assertIs<TerrainHorizonLoader.Outcome.Failed>(outcome)
        assertEquals(emptyList(), directory.listFiles()?.toList().orEmpty())
    }

    @Test
    fun onlyTheNearestFourTilesAreFetched() {
        // 47.9° N, 8.5° E needs six tiles (see the engine's tests); the four nearest are downloaded.
        val fake = FakeTiles { downloaded(gzip(tile(100))) }
        val outcome = assertIs<TerrainHorizonLoader.Outcome.Tiles>(
            loader(fake).fetchTiles(GeographicLocation(47.9, 8.5, 0.0), {}, { false }),
        )
        assertEquals(TerrainHorizonLoader.MAXIMUM_TILES, fake.asked.size)
        assertEquals(TerrainHorizonLoader.MAXIMUM_TILES, outcome.tiles.size)
    }

    @Test
    fun cancellingStopsBeforeTheNextTileAndLeavesNothing() {
        val fake = FakeTiles { downloaded(gzip(tile(0))) }
        var cancelled = false
        val outcome = loader(fake).fetchTiles(centre, { if (it is TerrainHorizonLoader.Progress.Reading) cancelled = true }, { cancelled })
        assertEquals(TerrainHorizonLoader.Outcome.Cancelled, outcome)
        assertEquals(1, fake.asked.size)
        assertEquals(emptyList(), directory.listFiles()?.toList().orEmpty())
    }

    @Test
    fun filesLeftByAnInterruptedDownloadAreDeleted() {
        File(directory, "N47E008.hgt.gz").writeBytes(ByteArray(10))
        val unreadable = loader(FakeTiles { notFound() })
        unreadable.fetchTiles(centre, {}, { false })
        assertEquals(emptyList(), directory.listFiles()?.toList().orEmpty())
        File(directory, "left.hgt.gz").writeBytes(ByteArray(10))
        unreadable.deleteLeftovers()
        assertEquals(emptyList(), directory.listFiles()?.toList().orEmpty())
    }
}
