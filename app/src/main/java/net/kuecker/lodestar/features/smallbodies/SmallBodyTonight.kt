package net.kuecker.lodestar.features.smallbodies

import net.kuecker.lodestar.features.tonight.TonightCalculator
import net.kuecker.lodestar.features.tonight.TonightTarget
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.comets.SmallBody
import net.kuecker.lodestar.kit.comets.SmallBodySelection
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.horizon.visibility as visibilityOverSkyline
import net.kuecker.lodestar.kit.planning.DarknessKind
import net.kuecker.lodestar.kit.planning.NightPlanner
import net.kuecker.lodestar.kit.planning.NightSummary
import net.kuecker.lodestar.kit.planning.TargetVisibility
import net.kuecker.lodestar.kit.planning.visibility
import java.time.Instant

/**
 * What the Tonight screen shows about comets and asteroids for one night.
 *
 * @property bright The brightest comets of the night, for the "Bright Comets" card (empty when there is none).
 * @property targets Comets and asteroids that are genuinely good tonight, for the Best Targets list.
 */
internal class SmallBodyTonight(val bright: List<SmallBodyListEntry>, val targets: List<TonightTarget>) {
    companion object {
        val none = SmallBodyTonight(emptyList(), emptyList())
    }
}

/** Plans the comets and asteroids of a night. Pure, so it can run on any thread (a few milliseconds). */
internal object SmallBodyTonightPlanner {
    /** The most comets the card shows. */
    const val CARD_COUNT = 3

    /** The faintest magnitude the card lists: a camera on a tripod. */
    const val CARD_MAGNITUDE_LIMIT = 10.0

    /** The most comets and asteroids added to the Best Targets list. */
    const val TARGET_COUNT = 3

    /** The faintest magnitude of a Best Target: binoculars or a wide-field camera. */
    const val TARGET_MAGNITUDE_LIMIT = 8.0

    /** A Best Target must spend at least this many hours of the dark above [TonightCalculator.minimumTargetAltitude]. */
    const val MINIMUM_HOURS = 1.0

    /** The candidates looked at: the brightest ones at the night's reference time. */
    private const val CANDIDATE_LIMIT = 30

    /**
     * The comets for the card (bright at the night's reference time) and the comets and asteroids worth a place among
     * the Best Targets: at most [TARGET_MAGNITUDE_LIMIT], 25° or more from the Sun, and at least
     * [TonightCalculator.minimumTargetAltitude] high for [MINIMUM_HOURS] in the dark (over the skyline, if there is one).
     * Small bodies are not deep-sky objects, so only the few that are clearly good are ranked; the card lists the rest.
     */
    fun plan(catalog: Catalog, summary: NightSummary, skyline: HorizonProfile?): SmallBodyTonight {
        val bodies = SmallBodyBrightLists.bodies(catalog)
        if (bodies.isEmpty()) return SmallBodyTonight.none
        val date = summary.referenceDate
        val bright = SmallBodyBrightLists.entries(
            catalog, date, SmallBodyListPeriod.NOW, CARD_MAGNITUDE_LIMIT, SmallBodyListKind.COMETS, CARD_COUNT,
        )
        return SmallBodyTonight(bright, targets(catalog, summary, skyline, date, bodies))
    }

    private fun targets(
        catalog: Catalog,
        summary: NightSummary,
        skyline: HorizonProfile?,
        date: Instant,
        bodies: List<SmallBody>,
    ): List<TonightTarget> {
        if (!summary.hasAstronomicalDarkness) return emptyList()
        val minimum = TonightCalculator.minimumTargetAltitude
        val candidates = SmallBodySelection.brightNow(
            bodies, date, TARGET_MAGNITUDE_LIMIT, maximumCount = CANDIDATE_LIMIT,
        )
        val targets = ArrayList<TonightTarget>()
        for (candidate in candidates) {
            if (targets.size >= TARGET_COUNT) break
            val obj = catalog.objectWithID(candidate.body.id) ?: continue
            val visibility = visibility(obj, summary, skyline)
            if (skyline != null && visibility.intervalsAboveMinimum.isEmpty()) continue
            val peak = visibility.maxAltitudeDuringDarkness ?: continue
            val time = visibility.bestTime ?: continue
            if (peak.isNaN() || peak < minimum || visibility.hoursAboveMinimum < MINIMUM_HOURS) continue
            // Judged where it stands at its best moment: bright enough and clear of the Sun's glow then.
            val frame = SkyFrame(time, summary.observer)
            val info = SmallBodyInfo.compute(candidate.body, frame) ?: continue
            val magnitude = info.magnitude ?: continue
            if (!info.isPhotographable || magnitude > TARGET_MAGNITUDE_LIMIT) continue
            targets.add(
                TonightTarget(
                    obj = obj,
                    peakAltitude = peak,
                    peakTime = time,
                    peakAzimuth = frame.horizontal(obj, false).azimuth,
                    hoursAboveMinimum = visibility.hoursAboveMinimum,
                    note = SmallBodyText.summary(obj, info),
                ),
            )
        }
        return targets
    }

    /** How well [obj] can be observed during the dark hours of the night (the planner's one-object call, about 1 ms). */
    private fun visibility(obj: CelestialObject, summary: NightSummary, skyline: HorizonProfile?): TargetVisibility {
        val position: (SkyFrame) -> HorizontalCoordinate = { frame -> frame.horizontal(obj, false) }
        val minimum = TonightCalculator.minimumTargetAltitude
        return if (skyline != null) {
            NightPlanner.visibilityOverSkyline(position, summary, DarknessKind.ASTRONOMICAL, minimum, skyline)
        } else {
            NightPlanner.visibility(position, summary, DarknessKind.ASTRONOMICAL, minimum)
        }
    }
}
