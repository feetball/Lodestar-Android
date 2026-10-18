package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant

/**
 * What the night holds for an astrophotographer at one place: twilight, the Moon, the dark hours,
 * the Milky Way's core and the planets.
 *
 * The night runs from local noon of the starting date to local noon of the next day. Altitudes are
 * geometric (no refraction). "Astronomical darkness" is the time the Sun is more than 18° below the
 * horizon; "dark" ([darknessIntervals]) additionally requires the Moon to be below the horizon.
 *
 * @property observer The place the summary was computed for.
 * @property night Local noon of the starting date to local noon of the next day.
 * @property twilight Sunset, the three twilights and sunrise.
 * @property referenceDate The instant at which the Moon's phase and the planets' magnitudes were
 * evaluated: astronomical dusk, or solar midnight when the Sun does not set below −18° that evening.
 * @property moon The Moon's phase and the times it is above the horizon.
 * @property astronomicalDarknessIntervals The times the Sun is more than 18° below the horizon, in time
 * order. Empty when the Sun does not get that low, as in high-latitude summers (then
 * [hasAstronomicalDarkness] is `false`); the whole night in polar winter.
 * @property darknessIntervals The times of astronomical darkness during which the Moon is also below the
 * horizon, in time order.
 * @property milkyWayCore When the Galactic Centre can be photographed.
 * @property planets Mercury to Neptune, in that order.
 */
data class NightSummary(
    val observer: Observer,
    val night: DateInterval,
    val twilight: NightTwilight,
    val referenceDate: Instant,
    val moon: MoonSummary,
    val astronomicalDarknessIntervals: List<DateInterval>,
    val darknessIntervals: List<DateInterval>,
    val milkyWayCore: MilkyWayCoreVisibility,
    val planets: List<PlanetVisibility>,
) {
    /** `false` when the Sun never gets 18° below the horizon during the night. */
    val hasAstronomicalDarkness: Boolean get() = astronomicalDarknessIntervals.isNotEmpty()

    /** The hours of astronomical darkness, whether or not the Moon is up. */
    val astronomicalDarkHours: Double
        get() = IntervalSet.totalDuration(astronomicalDarknessIntervals) / 3600

    /** The hours of astronomical darkness with the Moon below the horizon. */
    val moonFreeDarkHours: Double
        get() = IntervalSet.totalDuration(darknessIntervals) / 3600

    /** The dark intervals of the given [kind]. */
    fun darkness(kind: DarknessKind): List<DateInterval> = when (kind) {
        DarknessKind.ASTRONOMICAL -> astronomicalDarknessIntervals
        DarknessKind.MOON_FREE -> darknessIntervals
    }
}

/**
 * The Moon during one night.
 *
 * @property phase The phase at [NightSummary.referenceDate] (`phase.date`).
 * @property riseTransitSet The first rise, transit and set within the night. The horizon is the Moon's
 * upper limb after standard refraction, as in [RiseSetCalculator].
 * @property upIntervals Every stretch of the night in which the Moon is above the horizon, in time order.
 */
data class MoonSummary(
    val phase: MoonPhaseInfo,
    val riseTransitSet: RiseTransitSet,
    val upIntervals: List<DateInterval>,
) {
    /** The fraction of the visible disc that is sunlit, [0, 1]. */
    val illuminatedFraction: Double get() = phase.illuminatedFraction
}

/**
 * When the core of the Milky Way, the Galactic Centre in Sagittarius, can be photographed.
 *
 * The core counts as visible while it is at least [minimumAltitude] above the horizon during
 * astronomical darkness.
 *
 * @property riseTransitSet When the core rises, culminates and sets within the night, with the horizon at
 * −0.5667°.
 * @property visibleIntervals The stretches of astronomical darkness in which the core is at least
 * [minimumAltitude] high, whether or not the Moon is up.
 * @property moonFreeVisibleIntervals The parts of [visibleIntervals] in which the Moon is below the horizon.
 * @property bestTime The best moment to photograph the core: when it is highest, within
 * [moonFreeVisibleIntervals] if there are any and otherwise within [visibleIntervals]. `null` when the core
 * is never visible.
 * @property maxAltitude The core's geometric altitude at [bestTime], in degrees.
 * @property bestAzimuth The core's azimuth at [bestTime], in degrees from north through east.
 */
data class MilkyWayCoreVisibility(
    val riseTransitSet: RiseTransitSet,
    val visibleIntervals: List<DateInterval>,
    val moonFreeVisibleIntervals: List<DateInterval>,
    val bestTime: Instant?,
    val maxAltitude: Double?,
    val bestAzimuth: Double?,
) {
    companion object {
        /** The altitude, in degrees, from which the core counts as visible. */
        const val minimumAltitude: Double = 10.0

        /**
         * The Galactic Centre's J2000 position: the radio source Sagittarius A*,
         * 17h 45m 40.04s, −29° 00′ 28.1″.
         */
        internal val galacticCentre: EquatorialCoordinate = EquatorialCoordinate.fromRightAscensionHours(
            17.0 + 45.0 / 60 + 40.04 / 3600,
            -(29.0 + 28.1 / 3600),
        )
    }
}

/**
 * One planet during a night.
 *
 * A planet is visible while it is at least [minimumAltitude] above the horizon during astronomical
 * darkness; the Moon is ignored because planets are bright. Mercury and Venus stay close to the Sun
 * and are mostly seen in twilight, before or after the dark hours; [riseTransitSet] still shows when
 * they set or rise.
 *
 * @property body The planet.
 * @property riseTransitSet When the planet rises, culminates and sets within the night.
 * @property visibleIntervals The stretches of astronomical darkness in which the planet is at least
 * [minimumAltitude] high.
 * @property maxAltitudeDuringDarkness The planet's highest geometric altitude during astronomical
 * darkness, in degrees. Negative when it stays below the horizon; `null` when there is no astronomical
 * darkness.
 * @property magnitude The planet's apparent visual magnitude at [NightSummary.referenceDate].
 */
data class PlanetVisibility(
    val body: SolarSystemBody,
    val riseTransitSet: RiseTransitSet,
    val visibleIntervals: List<DateInterval>,
    val maxAltitudeDuringDarkness: Double?,
    val magnitude: Double?,
) {
    /** `true` when the planet is at least [minimumAltitude] high at some time during astronomical darkness. */
    val visibleDuringDarkness: Boolean get() = visibleIntervals.isNotEmpty()

    companion object {
        /** The altitude, in degrees, from which a planet counts as visible. */
        const val minimumAltitude: Double = 10.0
    }
}
