package net.kuecker.lodestar.features.horizon

import android.content.SharedPreferences
import net.kuecker.lodestar.kit.horizon.AzimuthRange
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.horizon.merged
import net.kuecker.lodestar.kit.horizon.unsweptRanges
import net.kuecker.lodestar.model.HorizonSource
import net.kuecker.lodestar.model.SavedSite

/** The skyline that applies at a site and where it comes from; both `null` for an open horizon. */
internal data class ResolvedHorizon(val profile: HorizonProfile?, val source: HorizonSource?) {
    companion object {
        /** An open horizon. */
        val OPEN = ResolvedHorizon(null, null)
    }
}

/**
 * Decides which skyline applies at a site: the one the user recorded, the terrain horizon computed from elevation data,
 * or the recorded one with the terrain in the stretches the sweep left open. Pure, apart from reading the coverage of the
 * recording from the preferences.
 */
internal object SiteHorizon {
    /**
     * [recorded] where the sweep reached, [terrain] in the [gaps] (see `HorizonProfile.merged`). A flat result is an
     * open horizon. The source is [HorizonSource.RECORDED_AND_TERRAIN] only when the terrain contributes something: a
     * recorded skyline without gaps stays a recorded one however much terrain is stored.
     */
    fun resolve(recorded: HorizonProfile?, terrain: HorizonProfile?, gaps: List<AzimuthRange>): ResolvedHorizon {
        val recordedUsed = recorded != null && !recorded.isFlat
        val merged = HorizonProfile.merged(recorded?.takeIf { recordedUsed }, terrain, gaps) ?: return ResolvedHorizon.OPEN
        if (merged.isFlat) return ResolvedHorizon.OPEN
        val source = when {
            !recordedUsed -> HorizonSource.TERRAIN
            terrain != null && gaps.isNotEmpty() -> HorizonSource.RECORDED_AND_TERRAIN
            else -> HorizonSource.RECORDED
        }
        return ResolvedHorizon(merged, source)
    }

    /**
     * What applies at [site]: the stretches the recording left open come from the swept degrees remembered with it in
     * [preferences] (the app's device-local ones); without them the recorded skyline wins everywhere.
     */
    fun resolve(site: SavedSite, preferences: SharedPreferences): ResolvedHorizon {
        val terrain = site.terrainHorizon
        if (terrain == null || site.horizon == null) return resolve(site.horizon, terrain, emptyList())
        val swept = HorizonCoverageMemory.entry(preferences, site)?.mask
        return resolve(site.horizon, terrain, swept?.let { HorizonProfile.unsweptRanges(it) }.orEmpty())
    }
}
