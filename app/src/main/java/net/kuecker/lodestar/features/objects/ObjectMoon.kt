package net.kuecker.lodestar.features.objects

import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.ephemeris.state
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.planning.ImagingFilter
import net.kuecker.lodestar.kit.planning.MoonImpact
import java.time.Instant

/**
 * Where the Moon is, seen from the observer, relative to one object at one instant: the three numbers
 * [MoonImpact.assess] needs besides the filter and the object's kind.
 */
internal data class ObjectMoonGeometry(
    /** The Moon's geometric altitude, in degrees. */
    val moonAltitude: Double,
    /** The angle between the Moon and the object, in degrees. */
    val separation: Double,
    /** The lit fraction of the Moon's disc, 0…1. */
    val illuminatedFraction: Double,
) {
    /** What the Moon does to a shot of an object of kind [kind] through [filter], and without any filter. */
    fun assess(filter: ImagingFilter, kind: ObjectCategory): ObjectMoonAssessment = ObjectMoonAssessment(
        level = MoonImpact.assess(moonAltitude, separation, illuminatedFraction, filter, kind),
        broadbandLevel = MoonImpact.assess(moonAltitude, separation, illuminatedFraction, ImagingFilter.NONE, kind),
    )

    companion object {
        /** The geometry of [obj] and the Moon at [date]. It builds one sky frame and asks the Moon's ephemeris twice. */
        fun of(obj: CelestialObject, observer: Observer, date: Instant): ObjectMoonGeometry {
            val frame = SkyFrame(date, observer)
            val moon = frame.horizontal(SolarSystemBody.MOON, false)
            val separation = frame.horizontal(obj, false).angularSeparation(moon)
            val illuminatedFraction = frame.state(SolarSystemBody.MOON).illuminatedFraction ?: 0.0
            return ObjectMoonGeometry(moon.altitude, separation, illuminatedFraction)
        }
    }
}

/** How much the Moon disturbs a shot of one object: through the filter in use, and without any filter. */
internal data class ObjectMoonAssessment(
    /** The level with the user's filter. */
    val level: MoonImpact.Level,
    /** The level without a filter. */
    val broadbandLevel: MoonImpact.Level,
) {
    /** Whether the filter made the Moon matter less. */
    val isRelaxed: Boolean get() = level != broadbandLevel
}

/** The words for the Moon's effect, the same on every screen. */
internal object ObjectMoonText {
    /** The filter as it reads in "your narrowband filter". Empty for no filter. */
    fun filterName(filter: ImagingFilter): String = when (filter) {
        ImagingFilter.NONE -> ""
        ImagingFilter.LIGHT_POLLUTION -> "light-pollution"
        ImagingFilter.DUAL_NARROWBAND -> "dual narrowband"
        ImagingFilter.NARROWBAND -> "narrowband"
    }

    /**
     * What the Moon does to this target: a sentence, or when [filter] relaxed the level, the level without it and
     * what the filter achieved: "Moderate · fine with your narrowband filter".
     */
    fun advice(assessment: ObjectMoonAssessment, filter: ImagingFilter): String {
        if (assessment.isRelaxed) {
            val verdict = verdict(assessment.level)
            return "${levelName(assessment.broadbandLevel)} · $verdict with your ${filterName(filter)} filter"
        }
        return when (assessment.level) {
            MoonImpact.Level.MOON_DOWN -> "The Moon is down, so the sky stays dark."
            MoonImpact.Level.MINOR -> "The Moon has little effect on this target."
            MoonImpact.Level.MODERATE -> "Moonlight will brighten the sky around this target."
            MoonImpact.Level.SEVERE -> "A bright Moon close by will wash out faint detail."
        }
    }

    /** The short label of a Tonight row: "Moon down", "Moon: moderate" or "Moon: fine with your filter". */
    fun chip(assessment: ObjectMoonAssessment): String {
        if (assessment.level == MoonImpact.Level.MOON_DOWN) return "Moon down"
        if (assessment.isRelaxed) return "Moon: ${verdict(assessment.level)} with your filter"
        return "Moon: ${levelName(assessment.level).lowercase()}"
    }

    /** "Moderate", "Severe": a level as a word. */
    private fun levelName(level: MoonImpact.Level): String = when (level) {
        MoonImpact.Level.MOON_DOWN -> "Moon down"
        MoonImpact.Level.MINOR -> "Minor"
        MoonImpact.Level.MODERATE -> "Moderate"
        MoonImpact.Level.SEVERE -> "Severe"
    }

    /** What a filter achieved: "fine" when the Moon is down to minor, "manageable" when it is still moderate. */
    private fun verdict(level: MoonImpact.Level): String = when (level) {
        MoonImpact.Level.MOON_DOWN, MoonImpact.Level.MINOR -> "fine"
        MoonImpact.Level.MODERATE, MoonImpact.Level.SEVERE -> "manageable"
    }
}
