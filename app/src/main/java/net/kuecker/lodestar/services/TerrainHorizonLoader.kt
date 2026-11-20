package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.horizon.GzipHeader
import net.kuecker.lodestar.kit.horizon.TerrainHorizon
import net.kuecker.lodestar.kit.horizon.TerrainHorizonResult
import net.kuecker.lodestar.kit.horizon.TerrainTile
import net.kuecker.lodestar.kit.horizon.TerrainTileError
import net.kuecker.lodestar.kit.horizon.TerrainTileKey
import java.io.File
import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Why a tile file could not be turned into elevations. */
internal class TerrainTileException(message: String) : Exception(message)

/**
 * Inflates a Skadi tile file (`N47E008.hgt.gz`). The engine finds the DEFLATE data and the size it must have
 * ([GzipHeader]); the inflater of the platform does the rest, into a buffer of exactly the size of a tile, so a damaged or
 * hostile file cannot make the app allocate more.
 */
internal object TerrainTileInflater {
    private const val HIGH_RESOLUTION_BYTES = TerrainTile.highResolutionSamples * TerrainTile.highResolutionSamples * 2
    private const val LOW_RESOLUTION_BYTES = TerrainTile.lowResolutionSamples * TerrainTile.lowResolutionSamples * 2

    /** The inflated file; throws [TerrainTileException] when [gzip] is not a gzip file of a tile or its data is damaged. */
    fun inflate(gzip: ByteArray): ByteArray {
        val payload = GzipHeader.payloadRange(gzip) ?: throw TerrainTileException("The file is not a gzip file.")
        val size = GzipHeader.uncompressedLength(gzip)
        if (size != HIGH_RESOLUTION_BYTES && size != LOW_RESOLUTION_BYTES) {
            throw TerrainTileException("The file is not an elevation tile.")
        }
        val inflater = Inflater(true)
        try {
            inflater.setInput(gzip, payload.first, payload.last - payload.first + 1)
            val out = ByteArray(size)
            var filled = 0
            while (filled < size) {
                val count = inflater.inflate(out, filled, size - filled)
                if (count == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                filled += count
            }
            if (filled != size) throw TerrainTileException("The elevation tile is shorter than it should be.")
            // A tile that goes on after its size is not one: the probe finds the end of the data, or more of it.
            if (inflater.inflate(ByteArray(1)) != 0 || !inflater.finished()) {
                throw TerrainTileException("The elevation tile is longer than it should be.")
            }
            return out
        } catch (_: DataFormatException) {
            throw TerrainTileException("The elevation tile is damaged.")
        } finally {
            inflater.end()
        }
    }
}

/**
 * Downloads the elevation tiles around a site (the optional "Terrain horizons" feed). The tiles of the AWS Open Data
 * Terrain Tiles come one by one from [baseUrl], each is stored in [directory] (the app's cache), inflated, read and
 * deleted; nothing stays on the phone. Blocking: call it from a background dispatcher. At most [MAXIMUM_TILES] tiles are
 * fetched, the nearest to the site first; the engine reports the others as missing.
 *
 * [fetch] downloads one address and polls its `isCancelled` argument while the data arrives.
 */
internal class TerrainHorizonLoader(
    private val directory: File,
    private val fetch: (url: String, isCancelled: () -> Boolean) -> OnlineResult,
    private val baseUrl: String = BASE_URL,
) {
    /** What the loader is doing, for the progress line. */
    sealed interface Progress {
        /** Downloading tile [index] (1-based) of [count]. */
        data class Downloading(val index: Int, val count: Int, val tile: TerrainTileKey) : Progress

        /** Inflating and reading the tile [index] of [count]. */
        data class Reading(val index: Int, val count: Int, val tile: TerrainTileKey) : Progress
    }

    /** The result of [fetchTiles]. */
    sealed interface Outcome {
        /** The tiles that could be fetched, and the needed ones the provider has no file for (HTTP 404). */
        class Tiles(val tiles: List<TerrainTile>, val unavailable: List<TerrainTileKey>) : Outcome

        /** Nothing usable arrived; [message] says why. */
        class Failed(val message: String) : Outcome

        /** [isCancelled] became true. */
        data object Cancelled : Outcome
    }

    /** Fetches the tiles [TerrainHorizon.requiredTiles] lists for [location], at most [MAXIMUM_TILES]. */
    fun fetchTiles(location: GeographicLocation, onProgress: (Progress) -> Unit, isCancelled: () -> Boolean): Outcome {
        val wanted = TerrainHorizon.requiredTiles(location).take(MAXIMUM_TILES)
        val tiles = ArrayList<TerrainTile>()
        val unavailable = ArrayList<TerrainTileKey>()
        try {
            deleteLeftovers()
            for ((position, key) in wanted.withIndex()) {
                if (isCancelled()) return Outcome.Cancelled
                onProgress(Progress.Downloading(position + 1, wanted.size, key))
                val file = File(directory, "${key.name}.hgt.gz")
                when (val result = fetch(baseUrl + key.path, isCancelled)) {
                    is OnlineResult.Downloaded -> store(file, result.bytes)
                    is OnlineResult.Failed -> {
                        if (isCancelled()) return Outcome.Cancelled
                        if (result.statusCode == NOT_FOUND) {
                            unavailable.add(key)
                            continue
                        }
                        return Outcome.Failed("The elevation tile ${key.name} could not be downloaded. ${result.message}")
                    }
                    is OnlineResult.NotModified -> return Outcome.Failed(OnlineFailure.EMPTY.message)
                }
                if (isCancelled()) return Outcome.Cancelled
                onProgress(Progress.Reading(position + 1, wanted.size, key))
                tiles.add(read(file, key))
                file.delete()
            }
        } catch (error: TerrainTileException) {
            return Outcome.Failed(error.message ?: OnlineFailure.UNREADABLE.message)
        } catch (error: TerrainTileError) {
            return Outcome.Failed(error.message ?: OnlineFailure.UNREADABLE.message)
        } catch (_: IOException) {
            return Outcome.Failed(OnlineFailure.STORAGE.message)
        } finally {
            deleteLeftovers()
        }
        if (tiles.isEmpty()) return Outcome.Failed("No elevation data is available for this place.")
        return Outcome.Tiles(tiles, unavailable)
    }

    /** Deletes what a download that was cut short (the app was closed) left in the cache folder. */
    fun deleteLeftovers() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun store(file: File, bytes: ByteArray) {
        directory.mkdirs()
        file.writeBytes(bytes)
    }

    private fun read(file: File, key: TerrainTileKey): TerrainTile {
        val inflated = TerrainTileInflater.inflate(file.readBytes())
        return TerrainTile.parse(key.latitude, key.longitude, inflated)
    }

    companion object {
        /** The address of the tile set: tiles are `skadi/N47/N47E008.hgt.gz` below it. */
        const val BASE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/skadi/"

        /** The most tiles one computation downloads (about 11 MB each). */
        const val MAXIMUM_TILES = 4

        /** The most bytes a tile file may have; the largest seen is about 11 MB, the sea and the plains compress to less. */
        const val MAXIMUM_TILE_BYTES = 40 * 1024 * 1024

        private const val NOT_FOUND = 404
    }
}

/** What [OnlineDataService.computeTerrainHorizon] came to. */
internal sealed interface TerrainComputation {
    /**
     * The terrain horizon, worked out from [tilesUsed] tiles; [unavailable] lists the needed tiles the provider has no file
     * for. [TerrainHorizonResult.missingTiles] has those and the tiles beyond the nearest [TerrainHorizonLoader.MAXIMUM_TILES].
     */
    class Done(
        val result: TerrainHorizonResult,
        val tilesUsed: Int,
        val unavailable: List<TerrainTileKey>,
    ) : TerrainComputation

    /** Nothing was computed; [message] says why. */
    class Failed(val message: String) : TerrainComputation

    /** The user cancelled, or the feed was switched off. */
    data object Cancelled : TerrainComputation
}
