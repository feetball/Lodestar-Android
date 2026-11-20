package net.kuecker.lodestar.features.tonight

import net.kuecker.lodestar.features.objects.ObjectMoonAssessment
import net.kuecker.lodestar.features.objects.ObjectMoonGeometry
import net.kuecker.lodestar.features.smallbodies.SmallBodyTonightPlanner
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectGroup
import net.kuecker.lodestar.kit.catalog.ObjectPosition
import net.kuecker.lodestar.kit.catalog.Prominence
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.ephemeris.TwilightCalculator
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.ProperMotion
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.horizon.visibility as visibilityOverSkyline
import net.kuecker.lodestar.kit.planning.DarknessKind
import net.kuecker.lodestar.kit.planning.ImagingFilter
import net.kuecker.lodestar.kit.planning.MoonImpact
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

    /** The ranking bonus per hour a target is clear of the site's skyline (only with a skyline). */
    const val clearHoursWeight: Double = 0.1

    /** The hours of clearance beyond which the bonus stops growing. */
    const val clearHoursCap: Double = 6.0

    /** Plans the night that [input] asks for: twilight, Moon, Milky Way, planets and the best targets. */
    fun report(input: TonightInput): TonightReport {
        val context = input.key.context
        val zone = context.timeZone
        val tonight = tonightDate(input.now, context)
        val requested = when (val choice = input.key.choice) {
            TonightNightChoice.Tonight -> tonight
            is TonightNightChoice.Date -> choice.date
        }
        // A recorded skyline replaces the flat horizon for the Moon, the Milky Way core, the planets and the targets.
        val skyline = context.horizon?.takeUnless { it.isFlat }
        val plain = NightPlanner.summary(requested, context.observer, zone)
        val summary = if (skyline != null) TonightSkyline.apply(plain, skyline) else plain
        val tonightStart = localNoon(tonight, zone)
        val nights = ChronoUnit.DAYS.between(
            tonightStart.atZone(zone).toLocalDate(),
            summary.night.start.atZone(zone).toLocalDate(),
        ).toInt()
        // Comets and asteroids are not deep-sky objects: they get their own card, and the clearly good ones join the targets.
        val smallBodies = input.catalog?.let { SmallBodyTonightPlanner.plan(it, summary, skyline) }
        return TonightReport(
            summary = summary,
            timeZone = zone,
            timeline = TonightTimelineModel(summary, zone),
            targets = targets(summary, input.catalog, skyline, smallBodies?.targets.orEmpty(), input.imagingFilter),
            tonightStart = tonightStart,
            nightsFromTonight = nights,
            skyline = skyline,
            smallBodies = smallBodies?.bright,
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

    private fun targets(
        summary: NightSummary,
        catalog: Catalog?,
        skyline: HorizonProfile?,
        movingTargets: List<TonightTarget>,
        filter: ImagingFilter,
    ): TonightTargetsOutcome {
        if (!summary.hasAstronomicalDarkness) return TonightTargetsOutcome.NoDarkness
        if (catalog == null) return TonightTargetsOutcome.CatalogPending
        // With a skyline the hours of the comets and asteroids are the hours above it too.
        val moving = if (skyline != null) movingTargets.map { it.copy(unobstructedHours = it.hoursAboveMinimum) } else movingTargets
        return TonightTargetsOutcome.Ranked(moving + rankedTargets(catalog, summary, skyline, filter))
    }

    private class Candidate(
        val obj: CelestialObject,
        val visibility: TargetVisibility,
        val peak: Double,
        val score: Double,
        val moon: ObjectMoonAssessment,
    )

    /**
     * The listed deep-sky objects that climb to at least [minimumTargetAltitude] in the dark, best first, at most
     * [targetLimit] of them. With a [skyline] an object must also clear it: hidden targets are dropped, and the
     * hours and the best time count only the stretches above it. The Moon counts too: a target's score is multiplied by
     * [MoonImpact.moonFreeWeight] of what the Moon does to it, through [filter], at its best time.
     */
    private fun rankedTargets(
        catalog: Catalog,
        summary: NightSummary,
        skyline: HorizonProfile?,
        filter: ImagingFilter,
    ): List<TonightTarget> {
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
        val visibilities = if (skyline != null) {
            NightPlanner.visibilityOverSkyline(
                places, motions, summary, DarknessKind.ASTRONOMICAL, minimumTargetAltitude, skyline,
            )
        } else {
            NightPlanner.visibility(places, motions, summary, DarknessKind.ASTRONOMICAL, minimumTargetAltitude)
        }
        val candidates = ArrayList<Candidate>()
        for ((index, obj) in objects.withIndex()) {
            val visibility = visibilities[index]
            // Filter hidden targets on the usable stretches, not on the altitude (see API-horizon.md).
            if (skyline != null && visibility.intervalsAboveMinimum.isEmpty()) continue
            val peak = visibility.maxAltitudeDuringDarkness ?: continue
            val bestTime = visibility.bestTime
            if (peak.isNaN() || peak < minimumTargetAltitude || bestTime == null) continue
            val moon = ObjectMoonGeometry.of(obj, summary.observer, bestTime).assess(filter, MoonImpact.assessmentKind(obj))
            // The hours clear of the skyline count only when there is a skyline; an open horizon ranks as it always did.
            val clearHours = if (skyline != null) visibility.unobstructedHours else null
            val weighted = score(obj, peak, clearHours) * MoonImpact.moonFreeWeight(moon.level)
            candidates.add(Candidate(obj, visibility, peak, weighted, moon))
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
                moon = candidate.moon,
                unobstructedHours = if (skyline != null) candidate.visibility.unobstructedHours else null,
            )
        }
    }

    private fun isDeepSky(obj: CelestialObject): Boolean = when (obj.group) {
        ObjectGroup.GALAXIES, ObjectGroup.NEBULAE, ObjectGroup.CLUSTERS -> true
        ObjectGroup.SOLAR_SYSTEM, ObjectGroup.STARS, ObjectGroup.SPECIAL -> false
    }

    /**
     * Rates an object for a wide-field camera: higher in the dark is better, and so are brighter and larger. Each
     * part runs from 0 to 1; brightness counts most. A little extra goes to the more prominent objects, so that a
     * showpiece without a magnitude (a dark nebula) is not ranked below a faint galaxy. With a skyline, [clearHours]
     * (the hours above it in the dark) adds [clearHoursWeight] per hour up to [clearHoursCap] hours, at most 0.6,
     * so that a target that stays clear of the trees and ridges for hours outranks one that only peeks over them.
     */
    internal fun score(obj: CelestialObject, peakAltitude: Double, clearHours: Double? = null): Double {
        val altitude = AstroMath.clamp((peakAltitude - minimumTargetAltitude) / (90 - minimumTargetAltitude), 0.0, 1.0)
        // Magnitude 3 and brighter is full marks, magnitude 11 and fainter none.
        val magnitude = obj.magnitude
        val brightness = if (magnitude != null && magnitude.isFinite()) AstroMath.clamp((11 - magnitude) / 8, 0.0, 1.0) else 0.0
        // 5′ is a speck, 2° or more a big target for a wide lens.
        val size = obj.angularSize?.let { AstroMath.clamp(log10(it.majorArcminutes / 5) / 1.4, 0.0, 1.0) } ?: 0.0
        val prominence = (Prominence.OBSCURE.rawValue - obj.prominence.rawValue) / 3.0
        val clearance = if (clearHours != null && clearHours.isFinite()) clearHoursWeight * minOf(clearHours, clearHoursCap) else 0.0
        return altitude + 1.5 * brightness + 0.8 * size + 0.6 * prominence + clearance
    }
}
