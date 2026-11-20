package net.kuecker.lodestar.features.horizon

import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.horizon.TerrainTileKey
import net.kuecker.lodestar.services.TerrainComputation
import net.kuecker.lodestar.services.TerrainHorizonLoader
import kotlin.math.roundToInt

/**
 * What a finished terrain computation says: the [lines] under the buttons of the site editor, and the ground
 * elevation the elevation data puts at the site, rounded to whole metres, which the editor offers as the site's
 * elevation.
 */
internal class TerrainSummary(val lines: List<String>, val groundElevationMeters: Int?)

/** The words of the terrain horizon section of the site editor. The iOS app uses the same texts. */
internal object TerrainText {
    const val SECTION_TITLE = "Terrain Horizon"

    const val FOOTER =
        "Works out the skyline of the hills and mountains around the site from elevation data. It is used where you " +
            "have not recorded a skyline, and in the stretches a partial sweep left open. Trees and buildings are not " +
            "in it."

    /** Under the skyline strip of the recording screen, for a site that has a terrain horizon. */
    const val STRIP_NOTE = "The stretches you do not sweep use the terrain horizon."

    const val NOT_COMPUTED = "Not computed"
    const val NO_RECORDED_SKYLINE = "No recorded skyline"
    const val COMPUTE = "Compute from Elevation Data…"
    const val REMOVE = "Remove Terrain Horizon…"
    const val CANCEL = "Cancel"
    const val SWITCHED_OFF = "Turn on Terrain horizons in Settings ▸ Online Data to download elevation data."
    const val SAVE_FIRST = "Save the site first, then compute its terrain horizon here."
    const val MOVED =
        "You moved this site. Save it first, then compute the terrain horizon of the new place."
    const val MOVED_STORED =
        "You moved this site. The terrain horizon belongs to the old spot: save the site, then compute it again."
    const val CONFIRM_TITLE = "Download Elevation Data?"
    const val CONFIRM_BUTTON = "Download"
    const val REMOVE_TITLE = "Remove the Terrain Horizon?"
    const val REMOVE_BUTTON = "Remove"
    const val KEEP_BUTTON = "Keep"
    const val REMOVE_MESSAGE =
        "The site goes back to its recorded skyline, or to an open horizon. You can compute the terrain horizon again, " +
            "which downloads the elevation data again."
    const val CANCELLED = "Cancelled. Nothing was changed."
    const val UNREADABLE = "The elevation data could not be turned into a skyline."

    /** About how many megabytes one tile file is. */
    const val TILE_MEGABYTES = 11

    /** "Computed · highest 9° in the south" for a site with a terrain horizon. */
    fun status(profile: HorizonProfile): String = "Computed · ${HorizonFormat.highest(profile)}"

    /** What the confirmation says before [tileCount] tiles are downloaded. */
    fun confirmMessage(tileCount: Int): String {
        val tiles = if (tileCount == 1) "1 elevation tile" else "$tileCount elevation tiles"
        return "Lodestar will download $tiles of about $TILE_MEGABYTES MB each from AWS Open Data, work out the " +
            "skyline and delete the tiles. The tile server sees your IP address and which 1° tiles you request. " +
            "This uses mobile data when you are not on Wi-Fi."
    }

    /** The progress line while the loader works. */
    fun progress(progress: TerrainHorizonLoader.Progress): String = when (progress) {
        is TerrainHorizonLoader.Progress.Downloading ->
            "Downloading elevation tile ${progress.index} of ${progress.count} (${progress.tile.name})…"
        is TerrainHorizonLoader.Progress.Reading ->
            "Reading elevation tile ${progress.index} of ${progress.count} (${progress.tile.name})…"
    }

    const val COMPUTING = "Computing the skyline…"

    /** "Use 412 m as the Site Elevation". */
    fun useElevation(meters: Int): String = "Use $meters m as the Site Elevation"

    /** The lines that say what the computation did and what it could not do. */
    fun summary(done: TerrainComputation.Done): TerrainSummary {
        val result = done.result
        val lines = ArrayList<String>()
        lines.add("Terrain horizon computed from ${if (done.tilesUsed == 1) "1 elevation tile" else "${done.tilesUsed} elevation tiles"}.")
        val ground = result.groundElevationMeters?.roundToInt()
        if (ground != null) lines.add("The elevation data puts the ground at the site at $ground m.")
        if (done.unavailable.isNotEmpty()) {
            lines.add(
                "No elevation data for ${names(done.unavailable)} (open sea or outside the tile set): nothing there is " +
                    "in the skyline.",
            )
        }
        val skipped = result.missingTiles.filter { it !in done.unavailable }
        if (skipped.isNotEmpty()) {
            val kilometres = (result.minimumCoveredRangeMeters / 1000).roundToInt()
            lines.add(
                "Not downloaded (only the ${TerrainHorizonLoader.MAXIMUM_TILES} nearest tiles are used): " +
                    "${names(skipped)}. The skyline reaches $kilometres km in the worst direction.",
            )
        }
        if (result.voidSampleShare >= VOID_NOTE_SHARE) {
            lines.add("Some elevation samples were missing (water or gaps) and count as sea level.")
        }
        return TerrainSummary(lines, ground)
    }

    /** The share of void samples from which the summary mentions them. */
    const val VOID_NOTE_SHARE = 0.01

    /** "N47E007", "N47E007 and N46E008", "N47E007, N46E008 and N46E007". */
    fun names(keys: List<TerrainTileKey>): String {
        val names = keys.map { it.name }
        return if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}
