package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.satellites.ElementSetGroup
import net.kuecker.lodestar.kit.satellites.ElementSetParser
import net.kuecker.lodestar.kit.satellites.OrbitalElements
import net.kuecker.lodestar.kit.satellites.Satellite
import java.time.Duration
import java.time.Instant

/**
 * The satellites of one CelesTrak group, built once per download and kept in memory (about 1.2 KB each: 11,000 Starlink
 * satellites are 12 MB).
 *
 * @property newest The element set with the newest epoch, which decides whether the group is fresh.
 * @property skippedCount How many records of the download were unusable (a few are normal).
 */
class SatelliteGroup(
    val group: ElementSetGroup,
    val satellites: List<Satellite>,
    val newest: OrbitalElements?,
    val skippedCount: Int,
) {
    /** Whether the newest element set is within the group's freshness limit at [now]. */
    fun isFresh(now: Instant): Boolean = newest != null && group.isFresh(newest, now)

    companion object {
        /** Element sets whose epoch is later than this after the present are dropped (a clock or data error). */
        private val futureTolerance: Duration = Duration.ofDays(1)

        /**
         * Reads a CelesTrak OMM JSON download. Throws [OnlineDataException] for anything that is not a usable list: not
         * JSON, no valid element set, or more unusable records than usable ones (the format has probably changed).
         */
        fun parse(group: ElementSetGroup, bytes: ByteArray, now: Instant): OnlineParsed<SatelliteGroup> {
            val batch = try {
                ElementSetParser.parseJson(bytes).latestPerObject()
            } catch (e: Exception) {
                throw OnlineDataException("Not a CelesTrak element list: ${e.message}")
            }
            val elements = batch.elements.filter { it.epoch <= now.plus(futureTolerance) }
            if (elements.isEmpty()) throw OnlineDataException("No usable element sets.")
            if (batch.skippedCount > elements.size) throw OnlineDataException("Too many unusable records.")
            val newest = elements.maxBy { it.epoch }
            val built = SatelliteGroup(group, elements.map { Satellite(it) }, newest, batch.skippedCount)
            return OnlineParsed(built, newest.epoch.toEpochMilli())
        }
    }
}

/** A satellite of the pass lists together with the group it came from, which decides how old its data may be. */
class PassSatellite(val satellite: Satellite, val group: ElementSetGroup) {
    /** Whether this satellite's own element set is within its group's freshness limit at [now]. */
    fun isFresh(now: Instant): Boolean = group.isFresh(satellite.elements, now)
}

/**
 * Everything downloaded about satellites, as an immutable snapshot (a new one for every change, so a pass list can tell
 * that its inputs changed).
 */
class SatelliteLibrary private constructor(
    val stations: SatelliteGroup?,
    val visual: SatelliteGroup?,
    val starlink: SatelliteGroup?,
    val generation: Long,
) {
    /** The ISS, Tiangong and the visual group, each satellite once (the newer element set wins), ISS first. */
    val passSatellites: List<PassSatellite> by lazy {
        val byNumber = LinkedHashMap<Int, PassSatellite>()
        fun add(group: SatelliteGroup?, only: Set<Int>?) {
            if (group == null) return
            for (satellite in group.satellites) {
                if (only != null && satellite.catalogNumber !in only) continue
                val known = byNumber[satellite.catalogNumber]
                if (known == null || satellite.elements.epoch > known.satellite.elements.epoch) {
                    byNumber[satellite.catalogNumber] = PassSatellite(satellite, group.group)
                }
            }
        }
        // The station group lists every module of the ISS: only the bright, independent ones are wanted.
        add(stations, brightStations)
        add(visual, null)
        byNumber.values.sortedBy { it.satellite.catalogNumber != ISS_CATALOG_NUMBER }
    }

    /** Whether the ISS and the bright satellites have data. */
    val hasPassData: Boolean get() = passSatellites.isNotEmpty()

    /** Whether any group needed for the passes is past its freshness limit at [now]. */
    fun isPassDataStale(now: Instant): Boolean =
        listOfNotNull(stations, visual).any { !it.isFresh(now) }

    /** This library with [group] added or replaced. */
    fun with(group: SatelliteGroup): SatelliteLibrary = when (group.group) {
        ElementSetGroup.STATIONS -> SatelliteLibrary(group, visual, starlink, generation + 1)
        ElementSetGroup.VISUAL -> SatelliteLibrary(stations, group, starlink, generation + 1)
        ElementSetGroup.STARLINK -> SatelliteLibrary(stations, visual, group, generation + 1)
    }

    /** This library without the groups of [feed]. */
    fun without(feed: OnlineFeed): SatelliteLibrary = when (feed) {
        OnlineFeed.SATELLITE_PASSES -> SatelliteLibrary(null, null, starlink, generation + 1)
        OnlineFeed.STARLINK -> SatelliteLibrary(stations, visual, null, generation + 1)
        OnlineFeed.COMETS, OnlineFeed.CLOUDS -> this
    }

    companion object {
        /** NORAD number of the International Space Station. */
        const val ISS_CATALOG_NUMBER = 25544

        /** NORAD number of the Tiangong core module (Tianhe). */
        const val TIANGONG_CATALOG_NUMBER = 48274

        private val brightStations = setOf(ISS_CATALOG_NUMBER, TIANGONG_CATALOG_NUMBER)

        val EMPTY = SatelliteLibrary(null, null, null, 0)
    }
}
