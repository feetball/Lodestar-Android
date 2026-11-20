package net.kuecker.lodestar.features.alignmentfinder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.catalog.ObjectPosition
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.TwilightCalculator
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.planning.SkyWindow
import net.kuecker.lodestar.kit.planning.SkyWindowDarkness
import net.kuecker.lodestar.kit.planning.SkyWindowError
import net.kuecker.lodestar.kit.planning.SkyWindowFinder
import net.kuecker.lodestar.kit.planning.SkyWindowMatch
import net.kuecker.lodestar.kit.planning.SkyWindowMoonRule
import net.kuecker.lodestar.kit.planning.SkyWindowTarget
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.ZoneId

/** What the finder looks for: the Milky Way core, the Moon, the Sun or an object of the catalog. */
internal enum class AlignmentFinderWhat(val title: String) {
    MILKY_WAY_CORE("Milky Way core"),
    MOON("Moon"),
    SUN("Sun"),
    OBJECT("Choose object…"),
}

/** Where it should stand: over a point of the recorded skyline, or in a window given by numbers. */
internal enum class AlignmentFinderPlace(val title: String) {
    SKYLINE("On my skyline"),
    WINDOW("Azimuth & altitude"),
}

/** How far ahead to search, from tonight. */
internal enum class AlignmentFinderRange(val days: Int, val title: String) {
    MONTH(30, "30 days"),
    QUARTER(90, "90 days"),
    YEAR(365, "365 days"),
}

/** How dark the sky has to be. */
internal enum class AlignmentFinderDarkness(val kind: SkyWindowDarkness, val title: String) {
    ASTRONOMICAL(SkyWindowDarkness.ASTRONOMICAL, "Astronomical"),
    NAUTICAL(SkyWindowDarkness.NAUTICAL, "Nautical"),
    ANY_DARKNESS(SkyWindowDarkness.CIVIL, "Any darkness"),
    ANY_TIME(SkyWindowDarkness.ANY_TIME, "Any time"),
}

/** What the Moon has to do. */
internal enum class AlignmentFinderMoon(val title: String) {
    ANY("Any"),
    BELOW_HORIZON("Below the horizon"),
    DIM("Dim"),
}

/**
 * The inputs of the screen, kept while it is pushed (the Sky chart is another tab). Changing the target also resets the
 * darkness to what suits it: any time for the Sun and the Moon, astronomical darkness for the rest.
 */
internal class AlignmentFinderInputs {
    var what by mutableStateOf(AlignmentFinderWhat.MILKY_WAY_CORE)
        private set
    var chosenObject by mutableStateOf<CelestialObject?>(null)
        private set
    var place by mutableStateOf(AlignmentFinderPlace.SKYLINE)
    var skylineAzimuth by mutableIntStateOf(180)
    var skylineTolerance by mutableIntStateOf(SKYLINE_TOLERANCE_DEFAULT)
    var windowAzimuth by mutableIntStateOf(180)
    var windowTolerance by mutableIntStateOf(WINDOW_TOLERANCE_DEFAULT)
    var minimumAltitude by mutableIntStateOf(0)
    var maximumAltitude by mutableIntStateOf(30)
    var range by mutableStateOf(AlignmentFinderRange.MONTH)
    var darkness by mutableStateOf(AlignmentFinderDarkness.ASTRONOMICAL)
    var moon by mutableStateOf(AlignmentFinderMoon.ANY)
    var dimPercent by mutableIntStateOf(DIM_PERCENT_DEFAULT)

    /** Chooses the Milky Way core, the Moon or the Sun; [AlignmentFinderWhat.OBJECT] needs [choose]. */
    fun choose(what: AlignmentFinderWhat) {
        if (what == AlignmentFinderWhat.OBJECT && chosenObject == null) return
        this.what = what
        darkness = defaultDarkness(target())
    }

    /** Chooses an object of the catalog. */
    fun choose(obj: CelestialObject) {
        chosenObject = obj
        what = AlignmentFinderWhat.OBJECT
        darkness = defaultDarkness(target())
    }

    /** What the finder follows, or `null` while "Choose object…" has no object yet. */
    fun target(): SkyWindowTarget? = when (what) {
        AlignmentFinderWhat.MILKY_WAY_CORE -> SkyWindowTarget.MilkyWayCore
        AlignmentFinderWhat.MOON -> SkyWindowTarget.Body(SolarSystemBody.MOON)
        AlignmentFinderWhat.SUN -> SkyWindowTarget.Body(SolarSystemBody.SUN)
        AlignmentFinderWhat.OBJECT -> chosenObject?.let { SkyWindowTarget.CatalogObject(it) }
    }

    /** Whether the finder follows the Sun, for which there is no Aim button. */
    val followsSun: Boolean
        get() = what == AlignmentFinderWhat.SUN ||
            (what == AlignmentFinderWhat.OBJECT && chosenObject?.category == ObjectCategory.SUN)

    /**
     * The object that Aim takes for the target ([lookup] finds the catalog's Milky Way core and Moon by identifier);
     * `null` for the Sun and while "Choose object…" has no object.
     */
    fun aimObject(lookup: (String) -> CelestialObject?): CelestialObject? = when {
        followsSun -> null
        what == AlignmentFinderWhat.MILKY_WAY_CORE -> lookup("special.galacticCenter")
        what == AlignmentFinderWhat.MOON -> lookup("moon")
        else -> chosenObject
    }

    /** The search these inputs ask for at [context] and [now]; `null` while they cannot ask for one. */
    fun request(context: ObserverContext, now: Instant): AlignmentFinderRequest? {
        val target = target() ?: return null
        val skyline = AlignmentFinderRequest.skyline(context)
        val window = when (place) {
            AlignmentFinderPlace.SKYLINE ->
                skyline?.let { SkyWindow.skylinePoint(skylineAzimuth.toDouble(), it, skylineTolerance.toDouble()) }
            AlignmentFinderPlace.WINDOW -> SkyWindow.around(
                windowAzimuth.toDouble(), windowTolerance.toDouble(), minimumAltitude.toDouble(), maximumAltitude.toDouble(),
            )
        } ?: return null
        val from = AlignmentFinderRequest.nightStart(now, context.timeZone)
        return AlignmentFinderRequest(
            target = target,
            window = window,
            observer = context.observer,
            timeZone = context.timeZone,
            horizon = skyline,
            from = from,
            to = from.plusSeconds(range.days * SECONDS_PER_DAY),
            darkness = darkness.kind,
            moon = when (moon) {
                AlignmentFinderMoon.ANY -> SkyWindowMoonRule.Unrestricted
                AlignmentFinderMoon.BELOW_HORIZON -> SkyWindowMoonRule.BelowHorizon
                AlignmentFinderMoon.DIM -> SkyWindowMoonRule.IlluminatedAtMost(dimPercent / 100.0)
            },
            isAtSkyline = place == AlignmentFinderPlace.SKYLINE,
        )
    }

    private fun defaultDarkness(target: SkyWindowTarget?): AlignmentFinderDarkness {
        val body = when (target) {
            is SkyWindowTarget.Body -> target.body
            is SkyWindowTarget.CatalogObject -> (target.obj.position as? ObjectPosition.SolarSystem)?.body
            else -> null
        }
        return if (body == SolarSystemBody.SUN || body == SolarSystemBody.MOON) {
            AlignmentFinderDarkness.ANY_TIME
        } else {
            AlignmentFinderDarkness.ASTRONOMICAL
        }
    }

    companion object {
        const val SKYLINE_TOLERANCE_DEFAULT = 3
        const val SKYLINE_TOLERANCE_MAXIMUM = 10
        const val WINDOW_TOLERANCE_DEFAULT = 10
        const val WINDOW_TOLERANCE_MAXIMUM = 90
        const val DIM_PERCENT_DEFAULT = 25
        const val SECONDS_PER_DAY = 86_400L
    }
}

/** One search: everything the engine needs and the screen's wording of the question. Equal requests give equal results. */
internal data class AlignmentFinderRequest(
    val target: SkyWindowTarget,
    val window: SkyWindow,
    val observer: Observer,
    val timeZone: ZoneId,
    val horizon: HorizonProfile?,
    val from: Instant,
    val to: Instant,
    val darkness: SkyWindowDarkness,
    val moon: SkyWindowMoonRule,
    /** Whether the window was cut from the skyline (for the wording of an empty result). */
    val isAtSkyline: Boolean,
) {
    companion object {
        /** The recorded skyline of the site, or `null` for none; a flat one counts as none. */
        fun skyline(context: ObserverContext): HorizonProfile? = context.horizon?.takeIf { !it.isFlat }

        /** The start (local noon) of the night that contains [now]. */
        fun nightStart(now: Instant, zone: ZoneId): Instant {
            val local = now.atZone(zone)
            val noonToday = local.toLocalDate().atTime(NOON, 0).atZone(zone).toInstant()
            return if (now >= noonToday) noonToday else local.toLocalDate().minusDays(1).atTime(NOON, 0).atZone(zone).toInstant()
        }

        private const val NOON = 12
    }
}

/** Why a search found nothing, as far as can be told. */
internal enum class AlignmentFinderEmptyReason {
    /** The Sun never gets low enough in the period. */
    NEVER_DARK,

    /** There are matches, but only with the Moon up or too bright for the Moon setting. */
    ONLY_WITH_MOON,

    /** There are matches, but only when the sky is lighter than the darkness setting allows. */
    ONLY_IN_LIGHT,

    /** The target does not pass through the window in the period. */
    NEVER_IN_WINDOW,
}

/** What a search came to. */
internal sealed interface AlignmentFinderOutcome {
    /** The matches in time order; [isCutShort] when the engine stopped at its limit. */
    data class Found(val matches: List<SkyWindowMatch>, val isCutShort: Boolean) : AlignmentFinderOutcome

    /** No match, and the likely [reason]. */
    data class Empty(val reason: AlignmentFinderEmptyReason) : AlignmentFinderOutcome

    /** The engine refused the search. */
    data class Failed(val message: String) : AlignmentFinderOutcome
}

/** Runs searches and explains empty results. Pure: call it off the main thread. */
internal object AlignmentFinderSearch {
    /** Nights between the nights checked for darkness when a search finds nothing. */
    private const val DARKNESS_SAMPLE_DAYS = 10

    /**
     * The matches of [request]. A search that finds nothing is repeated with the Moon rule and then the darkness
     * relaxed, which tells which setting is in the way; a year of searching takes under a second, so that is cheap.
     */
    fun run(request: AlignmentFinderRequest): AlignmentFinderOutcome {
        val found = try {
            matches(request)
        } catch (error: SkyWindowError) {
            return AlignmentFinderOutcome.Failed(failure(error))
        }
        if (found.isNotEmpty()) {
            return AlignmentFinderOutcome.Found(found, found.size >= SkyWindowFinder.maximumMatchCount)
        }
        return AlignmentFinderOutcome.Empty(reason(request))
    }

    private fun matches(request: AlignmentFinderRequest): List<SkyWindowMatch> =
        SkyWindowFinder.matches(
            target = request.target,
            window = request.window,
            observer = request.observer,
            horizon = request.horizon,
            from = request.from,
            to = request.to,
            darkness = request.darkness,
            moon = request.moon,
            timeZone = request.timeZone,
        )

    private fun reason(request: AlignmentFinderRequest): AlignmentFinderEmptyReason {
        if (request.darkness != SkyWindowDarkness.ANY_TIME && isNeverDark(request)) return AlignmentFinderEmptyReason.NEVER_DARK
        val anyMoon = request.copy(moon = SkyWindowMoonRule.Unrestricted)
        if (request.moon != SkyWindowMoonRule.Unrestricted && hasMatches(anyMoon)) return AlignmentFinderEmptyReason.ONLY_WITH_MOON
        if (request.darkness != SkyWindowDarkness.ANY_TIME && hasMatches(anyMoon.copy(darkness = SkyWindowDarkness.ANY_TIME))) {
            return AlignmentFinderEmptyReason.ONLY_IN_LIGHT
        }
        return AlignmentFinderEmptyReason.NEVER_IN_WINDOW
    }

    private fun hasMatches(request: AlignmentFinderRequest): Boolean =
        try {
            matches(request).isNotEmpty()
        } catch (error: SkyWindowError) {
            false
        }

    /** Whether the Sun stays above the darkness limit at every sampled night of the range, as in a polar summer. */
    private fun isNeverDark(request: AlignmentFinderRequest): Boolean {
        val limit = request.darkness.sunAltitudeLimit ?: return false
        val days = (request.to.epochSecond - request.from.epochSecond) / AlignmentFinderInputs.SECONDS_PER_DAY
        var day = 0L
        while (day <= days) {
            val date = request.from.plusSeconds(day * AlignmentFinderInputs.SECONDS_PER_DAY + 1)
            val twilight = TwilightCalculator.night(date, request.observer, request.timeZone)
            if (twilight.sunAltitudeAtMidnight < limit) return false
            day += DARKNESS_SAMPLE_DAYS
        }
        return true
    }

    private fun failure(error: SkyWindowError): String = when (error) {
        is SkyWindowError.RangeTooLong -> "That is too many nights to search. Choose a shorter period."
        is SkyWindowError.InvalidRange -> "The dates cannot be searched."
        is SkyWindowError.InvalidTarget -> "This object has no position to search for."
        is SkyWindowError.InvalidMoonRule -> "The Moon setting is not a usable percentage."
    }
}
