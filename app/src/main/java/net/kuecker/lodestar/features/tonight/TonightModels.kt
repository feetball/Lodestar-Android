package net.kuecker.lodestar.features.tonight

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.planning.NightSummary
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.ZoneId

/** Which night the Tonight screen shows. */
internal sealed interface TonightNightChoice {
    /** The night under way, or the coming one once the Sun has risen. It moves on by itself. */
    data object Tonight : TonightNightChoice

    /** The night that starts on the local date of [date]. */
    data class Date(val date: Instant) : TonightNightChoice
}

/** Everything a night depends on besides the sky catalog: where the observer is and which night is wanted. */
internal data class TonightNightKey(val context: ObserverContext, val choice: TonightNightChoice)

/** The work order for [TonightCalculator]: plain immutable values, so it can cross to a background thread. */
internal class TonightInput(
    val key: TonightNightKey,
    /** The present moment, which decides what "tonight" is. */
    val now: Instant,
    /** The sky catalog, or `null` while it is still loading. */
    val catalog: Catalog?,
)

/** A deep-sky object ranked for the night. */
internal data class TonightTarget(
    val obj: CelestialObject,
    /** The highest geometric altitude reached while it is dark, in degrees. */
    val peakAltitude: Double,
    /** When that happens. */
    val peakTime: Instant,
    /** The azimuth at that moment, in degrees from north through east. */
    val peakAzimuth: Double,
    /** The hours of darkness the object spends at or above [TonightCalculator.minimumTargetAltitude]. */
    val hoursAboveMinimum: Double,
) {
    val id: String get() = obj.id
}

/** The best-targets list, or the reason there is none. */
internal sealed interface TonightTargetsOutcome {
    /** The targets, best first; empty when no listed object climbs high enough. */
    data class Ranked(val targets: List<TonightTarget>) : TonightTargetsOutcome

    /** The Sun never gets 18° below the horizon, so there is no dark time to rank in. */
    data object NoDarkness : TonightTargetsOutcome

    /** The sky catalog has not been loaded yet. */
    data object CatalogPending : TonightTargetsOutcome
}

/** Everything the Tonight screen shows about one night. */
internal data class TonightReport(
    val summary: NightSummary,
    val timeZone: ZoneId,
    val timeline: TonightTimelineModel,
    val targets: TonightTargetsOutcome,
    /** Local noon of the date of the night under way (or coming): the zero of [nightsFromTonight]. */
    val tonightStart: Instant,
    /** How many nights this one is after tonight: 1 is tomorrow night, -1 is last night. */
    val nightsFromTonight: Int,
    /** The site's recorded skyline when the Moon, the Milky Way core, the planets and the targets respect it. */
    val skyline: HorizonProfile? = null,
) {
    /** Local noon of the date this night starts on. */
    val nightStart: Instant get() = summary.night.start

    /** When tonight turns into the next night: sunrise, or the end of the night when the Sun does not rise. */
    val expiry: Instant get() = summary.twilight.sunrise ?: summary.night.end
}
