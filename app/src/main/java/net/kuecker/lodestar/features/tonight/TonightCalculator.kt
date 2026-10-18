package net.kuecker.lodestar.features.tonight

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectGroup
import net.kuecker.lodestar.kit.catalog.ObjectPosition
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.ephemeris.TwilightCalculator
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.ProperMotion
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.planning.DarknessKind
import net.kuecker.lodestar.kit.planning.NightPlanner
import net.kuecker.lodestar.kit.planning.NightSummary
import net.kuecker.lodestar.kit.planning.TargetVisibility
import net.kuecker.lodestar.kit.planning.summary
import net.kuecker.lodestar.kit.planning.visibility
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.log10

/** The astronomy behind the Tonight screen. Pure, so it can run on any thread. */
internal object TonightCalculator {
    /** The lowest altitude, in degrees, at which a deep-sky object still counts as a target. */
    const val minimumTargetAltitude: Double = NightPlanner.defaultMinimumAltitude

    /** The most targets the list shows. */
    const val targetLimit: Int = 12

    /** Plans the night that [input] asks for: twilight, Moon, Milky Way, planets and the best targets. */
    fun report(input: TonightInput): TonightReport {
        val context = input.key.context
        val zone = context.timeZone
        val tonight = tonightDate(input.now, context)
        val requested = when (val choice = input.key.choice) {
            TonightNightChoice.Tonight -> tonight
            is TonightNightChoice.Date -> choice.date
        }
        val summary = NightPlanner.summary(requested, context.observer, zone)
        val tonightStart = localNoon(tonight, zone)
        val nights = ChronoUnit.DAYS.between(
            tonightStart.atZone(zone).toLocalDate(),
            summary.night.start.atZone(zone).toLocalDate(),
        ).toInt()
        return TonightReport(
            summary = summary,
            timeZone = zone,
            timeline = TonightTimelineModel(summary, zone),
            targets = targets(summary, input.catalog),
            tonightStart = tonightStart,
            nightsFromTonight = nights,
        )
    }

    /**
     * An instant on the date of the night that is under way at [now], or of the coming night once the Sun has
     * risen on the night before.
     */
    private fun tonightDate(now: Instant, context: ObserverContext): Instant {
        val yesterday = now.atZone(context.timeZone).minusDays(1).toInstant()
        val previous = TwilightCalculator.night(yesterday, context.observer, context.timeZone)
        if (now < previous.interval.end && now < (previous.sunrise ?: previous.interval.end)) {
            return yesterday
        }
        return now
    }

    /** Noon of the calendar day in [zone] that contains [date]: the instant the screen uses to stand for a night. */
    fun localNoon(date: Instant, zone: ZoneId): Instant = localNoon(date.atZone(zone).toLocalDate(), zone)

    /** Noon of the calendar [day] in [zone]. */
    fun localNoon(day: LocalDate, zone: ZoneId): Instant = day.atStartOfDay(zone).with(LocalTime.NOON).toInstant()

    // Best targets

    private fun targets(summary: NightSummary, catalog: Catalog?): TonightTargetsOutcome {
        if (!summary.hasAstronomicalDarkness) return TonightTargetsOutcome.NoDarkness
        if (catalog == null) return TonightTargetsOutcome.CatalogPending
        return TonightTargetsOutcome.Ranked(rankedTargets(catalog, summary))
    }

    private class Candidate(val obj: CelestialObject, val visibility: TargetVisibility, val peak: Double, val score: Double)

    /**
     * The listed deep-sky objects that climb to at least [minimumTargetAltitude] in the dark, best first, at most
     * [targetLimit] of them.
     */
    private fun rankedTargets(catalog: Catalog, summary: NightSummary): List<TonightTarget> {
        val objects = ArrayList<CelestialObject>()
        val places = ArrayList<EquatorialCoordinate>()
        val motions = ArrayList<ProperMotion?>()
        for (obj in catalog.listedObjects) {
            if (!isDeepSky(obj)) continue
            val position = obj.position as? ObjectPosition.Fixed ?: continue
            objects.add(obj)
            places.add(position.coordinate)
            motions.add(position.properMotion)
        }
        val visibilities = NightPlanner.visibility(
            places, motions, summary, DarknessKind.ASTRONOMICAL, minimumTargetAltitude,
        )
        val candidates = ArrayList<Candidate>()
        for ((index, obj) in objects.withIndex()) {
            val visibility = visibilities[index]
            val peak = visibility.maxAltitudeDuringDarkness ?: continue
            if (peak.isNaN() || peak < minimumTargetAltitude || visibility.bestTime == null) continue
            candidates.add(Candidate(obj, visibility, peak, score(obj, peak)))
        }
        candidates.sortWith(compareByDescending<Candidate> { it.score }.thenBy { it.obj.id })
        return candidates.take(targetLimit).mapNotNull { candidate ->
            val time = candidate.visibility.bestTime ?: return@mapNotNull null
            val frame = SkyFrame(time, summary.observer)
            TonightTarget(
                obj = candidate.obj,
                peakAltitude = candidate.peak,
                peakTime = time,
                peakAzimuth = frame.horizontal(candidate.obj, false).azimuth,
                hoursAboveMinimum = candidate.visibility.hoursAboveMinimum,
            )
        }
    }

    private fun isDeepSky(obj: CelestialObject): Boolean = when (obj.group) {
        ObjectGroup.GALAXIES, ObjectGroup.NEBULAE, ObjectGroup.CLUSTERS -> true
        ObjectGroup.SOLAR_SYSTEM, ObjectGroup.STARS, ObjectGroup.SPECIAL -> false
    }

    /**
     * Rates an object for a wide-field camera: higher in the dark is better, and so are brighter and larger. Each
     * part runs from 0 to 1; brightness counts most.
     */
    private fun score(obj: CelestialObject, peakAltitude: Double): Double {
        val altitude = AstroMath.clamp((peakAltitude - minimumTargetAltitude) / (90 - minimumTargetAltitude), 0.0, 1.0)
        // Magnitude 3 and brighter is full marks, magnitude 11 and fainter none.
        val magnitude = obj.magnitude
        val brightness = if (magnitude != null && magnitude.isFinite()) AstroMath.clamp((11 - magnitude) / 8, 0.0, 1.0) else 0.0
        // 5′ is a speck, 2° or more a big target for a wide lens.
        val size = obj.angularSize?.let { AstroMath.clamp(log10(it.majorArcminutes / 5) / 1.4, 0.0, 1.0) } ?: 0.0
        return altitude + 1.5 * brightness + 0.8 * size
    }
}
