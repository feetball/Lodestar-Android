package net.kuecker.lodestar.features.tonight

import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.horizon.HorizonVisibility
import net.kuecker.lodestar.kit.horizon.visibility
import net.kuecker.lodestar.kit.planning.DarknessKind
import net.kuecker.lodestar.kit.planning.MilkyWayCoreVisibility
import net.kuecker.lodestar.kit.planning.NightPlanner
import net.kuecker.lodestar.kit.planning.NightSummary
import net.kuecker.lodestar.kit.planning.PlanetVisibility
import java.time.Instant

/**
 * Puts the photographer's recorded skyline into a night summary: the Moon, the Milky Way's core and the planets count
 * as up only while they are above the skyline, not merely above the true horizon. The result has the same shape as the
 * plain summary, so every Tonight card and the darkness timeline show it unchanged. Pure, so it can run on any thread.
 */
internal object TonightSkyline {
    /** The Galactic Centre's J2000 place: Sagittarius A*, 17h 45m 40.04s, −29° 00′ 28.1″. */
    private val galacticCentre: EquatorialCoordinate = EquatorialCoordinate.fromRightAscensionHours(
        17.0 + 45.0 / 60 + 40.04 / 3600,
        -(29.0 + 28.1 / 3600),
    )

    /** [summary] as seen over [profile]. */
    fun apply(summary: NightSummary, profile: HorizonProfile): NightSummary {
        val horizon = HorizonVisibility(profile)
        val observer = summary.observer
        val night = summary.night

        val moonUp = horizon.visibleWindows(SolarSystemBody.MOON, observer, night)
        val moon = summary.moon.copy(
            riseTransitSet = horizon.riseTransitSet(SolarSystemBody.MOON, observer, night),
            upIntervals = moonUp,
        )
        val core = core(summary, horizon, profile, moonUp)
        val planets = summary.planets.map { planet ->
            val visibility = NightPlanner.visibility(
                planet.body, summary, DarknessKind.ASTRONOMICAL, PlanetVisibility.minimumAltitude, profile,
            )
            planet.copy(
                riseTransitSet = visibility.riseTransitSet,
                visibleIntervals = visibility.intervalsAboveMinimum,
                maxAltitudeDuringDarkness = visibility.maxAltitudeDuringDarkness,
            )
        }
        return summary.copy(
            moon = moon,
            darknessIntervals = subtract(summary.astronomicalDarknessIntervals, moonUp),
            milkyWayCore = core,
            planets = planets,
        )
    }

    // The Milky Way's core

    private fun core(
        summary: NightSummary,
        horizon: HorizonVisibility,
        profile: HorizonProfile,
        moonUp: List<DateInterval>,
    ): MilkyWayCoreVisibility {
        val observer = summary.observer
        val minimum = MilkyWayCoreVisibility.minimumAltitude
        val visible = horizon.visibleWindows(
            position = galacticCentre,
            observer = observer,
            intervals = summary.astronomicalDarknessIntervals,
            minimumAltitude = minimum,
        )
        val moonFree = subtract(visible, moonUp)
        val best = bestPoint(observer, moonFree.ifEmpty { visible }, profile)
        return MilkyWayCoreVisibility(
            riseTransitSet = horizon.riseTransitSet(galacticCentre, null, observer, summary.night),
            visibleIntervals = visible,
            moonFreeVisibleIntervals = moonFree,
            bestTime = best?.time,
            maxAltitude = best?.altitude,
            bestAzimuth = best?.azimuth,
        )
    }

    private class BestPoint(val time: Instant, val altitude: Double, val azimuth: Double)

    /** The highest point of the core inside [intervals] (all of which are above the skyline). */
    private fun bestPoint(observer: Observer, intervals: List<DateInterval>, profile: HorizonProfile): BestPoint? {
        var best: Pair<Instant, Double>? = null
        for (interval in intervals) {
            val visibility = NightPlanner.visibility(
                listOf(galacticCentre),
                observer = observer,
                interval = interval,
                minimumAltitude = MilkyWayCoreVisibility.minimumAltitude,
                horizon = profile,
            ).firstOrNull() ?: continue
            val time = visibility.bestTime ?: continue
            val altitude = visibility.maxAltitudeDuringDarkness ?: continue
            if (visibility.intervalsAboveMinimum.isEmpty()) continue
            if (best == null || altitude > best.second) best = time to altitude
        }
        val (time, altitude) = best ?: return null
        val azimuth = SkyFrame(time, observer).horizontal(galacticCentre, null, false).azimuth
        return BestPoint(time, altitude, azimuth)
    }

    // Interval arithmetic

    /** The parts of [base] that no interval of [removed] covers. */
    fun subtract(base: List<DateInterval>, removed: List<DateInterval>): List<DateInterval> {
        var result = base
        for (cut in removed) result = result.flatMap { piece -> split(piece, cut) }
        return result.filter { it.duration > 0 }
    }

    private fun split(piece: DateInterval, cut: DateInterval): List<DateInterval> {
        if (!piece.intersects(cut)) return listOf(piece)
        val parts = ArrayList<DateInterval>(2)
        if (cut.start > piece.start) parts.add(DateInterval(piece.start, minOf(cut.start, piece.end)))
        if (cut.end < piece.end) parts.add(DateInterval(maxOf(cut.end, piece.start), piece.end))
        return parts
    }
}
