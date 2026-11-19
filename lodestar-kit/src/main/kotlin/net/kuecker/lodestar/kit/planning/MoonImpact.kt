package net.kuecker.lodestar.kit.planning

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import kotlin.math.max
import kotlin.math.min

/** The filter in front of the sensor, as far as moonlight is concerned. */
@Serializable
enum class ImagingFilter(
    /** The stable string used in JSON and as [id]. */
    val rawValue: String,
) {
    /** No filter, or a broadband one (UV/IR cut, luminance, colour). */
    @SerialName("none")
    NONE("none"),

    /** A light-pollution filter of the CLS or UHC type. */
    @SerialName("lightPollution")
    LIGHT_POLLUTION("lightPollution"),

    /** A dual-band Hα + OIII filter, usually on a colour camera. */
    @SerialName("dualNarrowband")
    DUAL_NARROWBAND("dualNarrowband"),

    /** A single narrowband filter (Hα, SII or OIII), usually on a monochrome camera. */
    @SerialName("narrowband")
    NARROWBAND("narrowband");

    /** A stable identifier, equal to the raw value. */
    val id: String
        get() = rawValue

    /** A short name for pickers, for example "Dual narrowband". */
    val displayName: String
        get() = when (this) {
            NONE -> "None"
            LIGHT_POLLUTION -> "Light pollution"
            DUAL_NARROWBAND -> "Dual narrowband"
            NARROWBAND -> "Narrowband"
        }

    /** A one-line description of the filter and what it does about moonlight. */
    val summary: String
        get() = when (this) {
            NONE -> "No filter or a broadband one. Moonlight is judged by phase and distance alone."
            LIGHT_POLLUTION ->
                "A CLS or UHC-type filter. It helps a little with emission nebulae when the Moon is not close."
            DUAL_NARROWBAND ->
                "An Hα + OIII dual-band filter. It keeps emission nebulae usable under most moonlight."
            NARROWBAND ->
                "A mono Hα, SII or OIII filter. Emission nebulae stay usable even under a bright Moon."
        }

    companion object {
        /** The filter with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): ImagingFilter? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * How much the Moon disturbs a shot of one target, with the filter on the lens taken into account.
 *
 * The broadband rule is the one the apps always used: the Moon's lit fraction times how close it is to the target.
 * A filter relaxes it only for targets that shine in emission lines (see [isEmissionLineTarget]).
 *
 * Why a filter helps. Moonlight is sunlight scattered by the lunar surface and then by the atmosphere, so the glow it
 * adds to the sky is a continuum across the visible spectrum (Krisciunas and Schaefer 1991, "A model of the brightness
 * of moonlight", PASP 103, 1033). An emission nebula puts most of its light into a few lines: Hα at 656.3 nm, [OIII]
 * at 495.9 and 500.7 nm and [SII] at 671.6 and 673.1 nm (Osterbrock and Ferland 2006, "Astrophysics of Gaseous
 * Nebulae and Active Galactic Nuclei", 2nd ed.). A narrowband filter, typically 3 to 12 nm wide at each line, passes
 * the lines whole and only that sliver of the continuum, where a broadband filter passes about 300 nm of it. The
 * nebula's contrast against the moonlit sky therefore improves by about the ratio of the bandwidths, tens of times.
 * A dual-band filter does the same with wider or two bands, and a light-pollution filter passes a much wider band, so
 * it removes less moonlight. Galaxies, clusters, stars and reflection nebulae shine in a continuum (reflection
 * nebulae in scattered starlight, which has the same spectrum as the moonlight), so no filter separates them from
 * the glow. The number of steps each filter relaxes the level by is a rule of thumb that follows this reasoning; it
 * is not fitted to measurements.
 *
 * Every function is pure and takes constant time.
 */
object MoonImpact {
    /** How strongly the Moon disturbs a shot, from none to the worst. */
    @Serializable
    enum class Level(
        /** The stable string used in JSON. */
        val rawValue: String,
    ) {
        /** The Moon is below the horizon. */
        @SerialName("moonDown")
        MOON_DOWN("moonDown"),

        /** The Moon is up but hardly matters. */
        @SerialName("minor")
        MINOR("minor"),

        /** Moonlight brightens the sky noticeably. */
        @SerialName("moderate")
        MODERATE("moderate"),

        /** A bright Moon close by washes out faint detail. */
        @SerialName("severe")
        SEVERE("severe");

        /** The level [steps] steps lower: severe to moderate to minor. `MOON_DOWN` and `MINOR` do not go lower. */
        fun lowered(steps: Int): Level {
            if (this == MOON_DOWN || steps <= 0) return this
            return when (max(rank - steps, MINOR.rank)) {
                SEVERE.rank -> SEVERE
                MODERATE.rank -> MODERATE
                else -> MINOR
            }
        }

        private val rank: Int
            get() = when (this) {
                MOON_DOWN -> 0
                MINOR -> 1
                MODERATE -> 2
                SEVERE -> 3
            }

        companion object {
            /** The level with the given raw value, or null if there is none. */
            fun fromRawValue(raw: String): Level? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    /** The Moon's lit fraction times its proximity at which the level becomes severe. */
    private const val severeEffect = 0.5

    /** The Moon's lit fraction times its proximity at which the level becomes moderate. */
    private const val moderateEffect = 0.2

    /** The separation, in degrees, at which the proximity of the Moon falls to zero before its floor applies. */
    private const val proximityRange = 120.0

    /** The proximity of a far-away Moon: moonlight scatters across the whole sky. */
    private const val proximityFloor = 0.25

    /** The least separation, in degrees, at which a light-pollution filter relaxes the level. */
    private const val lightPollutionMinimumSeparation = 40.0

    /** The least separation, in degrees, at which a dual-band filter relaxes the level by two steps. */
    private const val dualNarrowbandFullSeparation = 60.0

    /** The most separation, in degrees, at which a narrowband filter relaxes the level by one step only. */
    private const val narrowbandCloseSeparation = 30.0

    /** The least lit fraction at which a close Moon holds a narrowband filter to one step. */
    private const val narrowbandBrightFraction = 0.5

    /**
     * How strongly the Moon disturbs a shot of a target of kind [objectKind] through [filter].
     *
     * The Moon counts only when its altitude is above 0°; a value that is not finite counts as below. Without a
     * filter the level follows the Moon's lit fraction times a proximity that falls from 1 at the target to 0.25 at
     * 90° and beyond: 0.5 and over is severe, 0.2 and over moderate, else minor. The filter then lowers that level
     * by [relaxationSteps] steps, never below minor. A [separation] or [illuminatedFraction] that is not finite
     * cannot be judged and gives minor; the fraction is limited to 0…1 and the separation to 0…180°.
     *
     * @param moonAltitude The Moon's altitude in degrees, geometric.
     * @param separation The angle between the Moon and the target in degrees.
     * @param illuminatedFraction The lit fraction of the Moon's disc, 0…1.
     * @param filter The filter in use.
     * @param objectKind The target's category.
     */
    fun assess(
        moonAltitude: Double,
        separation: Double,
        illuminatedFraction: Double,
        filter: ImagingFilter,
        objectKind: ObjectCategory,
    ): Level {
        if (!moonAltitude.isFinite() || !(moonAltitude > 0)) return Level.MOON_DOWN
        if (!separation.isFinite() || !illuminatedFraction.isFinite()) return Level.MINOR
        val angle = min(max(separation, 0.0), 180.0)
        val lit = min(max(illuminatedFraction, 0.0), 1.0)
        // Moonlight scatters across the whole sky but is strongest near the Moon.
        val proximity = min(max(1 - angle / proximityRange, proximityFloor), 1.0)
        val effect = lit * proximity
        val broadband = when {
            effect >= severeEffect -> Level.SEVERE
            effect >= moderateEffect -> Level.MODERATE
            else -> Level.MINOR
        }
        return broadband.lowered(relaxationSteps(angle, lit, filter, objectKind))
    }

    /**
     * How many steps [filter] lowers the level for a target of kind [objectKind] with the Moon [separation] degrees
     * away and [illuminatedFraction] lit; 0, 1 or 2.
     *
     * - [ImagingFilter.NONE] and every target that is not an emission-line target: 0.
     * - [ImagingFilter.LIGHT_POLLUTION]: 1 from a separation of 40°, else 0.
     * - [ImagingFilter.DUAL_NARROWBAND]: 1, and 2 from a separation of 60°.
     * - [ImagingFilter.NARROWBAND]: 2, but 1 when the Moon is within 30° (30° included) and at least half lit.
     *
     * A value that is not finite gives 0.
     */
    fun relaxationSteps(
        separation: Double,
        illuminatedFraction: Double,
        filter: ImagingFilter,
        objectKind: ObjectCategory,
    ): Int {
        if (!isEmissionLineTarget(objectKind)) return 0
        if (!separation.isFinite() || !illuminatedFraction.isFinite()) return 0
        return when (filter) {
            ImagingFilter.NONE -> 0
            ImagingFilter.LIGHT_POLLUTION -> if (separation >= lightPollutionMinimumSeparation) 1 else 0
            ImagingFilter.DUAL_NARROWBAND -> if (separation >= dualNarrowbandFullSeparation) 2 else 1
            ImagingFilter.NARROWBAND ->
                if (separation <= narrowbandCloseSeparation && illuminatedFraction >= narrowbandBrightFraction) 1 else 2
        }
    }

    /**
     * Whether a target of this category shines mainly in emission lines, so that a filter can separate it from
     * moonlight: emission nebulae (OpenNGC `HII`, `EmN`), planetary nebulae (`PN`), supernova remnants (`SNR`) and
     * clusters with nebulosity (`Cl+N`, which are embedded in emission nebulae). Wolf-Rayet shells and similar are
     * typed `HII` or `EmN` in the catalog. The generic nebula (`Neb`) is left out because that type mixes emission
     * and reflection nebulae; so are dark and reflection nebulae, galaxies, clusters, stars, the Milky Way and every
     * solar-system body.
     */
    fun isEmissionLineTarget(objectKind: ObjectCategory): Boolean = when (objectKind) {
        ObjectCategory.EMISSION_NEBULA,
        ObjectCategory.PLANETARY_NEBULA,
        ObjectCategory.SUPERNOVA_REMNANT,
        ObjectCategory.CLUSTER_WITH_NEBULA,
        -> true
        else -> false
    }

    /**
     * How much a target at [level] counts when targets are ranked: 1 for a dark sky, falling to 0.4 for a severe
     * Moon. A ranking multiplies a target's score by it, so a target that the Moon spoils drops behind one that it
     * leaves alone. The values are a ranking heuristic: minor 0.9, moderate 0.7, severe 0.4.
     */
    fun moonFreeWeight(level: Level): Double = when (level) {
        Level.MOON_DOWN -> 1.0
        Level.MINOR -> 0.9
        Level.MODERATE -> 0.7
        Level.SEVERE -> 0.4
    }
}
