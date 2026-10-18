package net.kuecker.lodestar.kit.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The kind of a sky object, as shown in lists and used to pick an icon.
 *
 * The cases follow the OpenNGC type codes for deep-sky objects, plus the kinds that the catalog
 * adds itself: the solar-system bodies (the Sun, the Moon, the planets and, once small bodies are added with
 * [Catalog.withSmallBodies], comets and asteroids), the Milky Way core, the celestial poles and
 * user-created targets.
 *
 * Serialized as its [rawValue] string (the Swift case name, e.g. `"doubleStar"`).
 *
 * @property rawValue The Swift case name, which is also the serialized form.
 */
@Serializable
enum class ObjectCategory(val rawValue: String) {
    /** The Sun. */
    @SerialName("sun")
    SUN("sun"),

    /** The Moon. */
    @SerialName("moon")
    MOON("moon"),

    /** A planet. */
    @SerialName("planet")
    PLANET("planet"),

    /** A comet; added to a catalog with [Catalog.withSmallBodies]. */
    @SerialName("comet")
    COMET("comet"),

    /** An asteroid (minor planet); the bright ones are bundled, see [BundledAsteroids]. */
    @SerialName("asteroid")
    ASTEROID("asteroid"),

    /** A star. */
    @SerialName("star")
    STAR("star"),

    /** A double star. */
    @SerialName("doubleStar")
    DOUBLE_STAR("doubleStar"),

    /** A galaxy. */
    @SerialName("galaxy")
    GALAXY("galaxy"),

    /** A pair, triplet or group of galaxies. */
    @SerialName("galaxyGroup")
    GALAXY_GROUP("galaxyGroup"),

    /** An emission nebula. */
    @SerialName("emissionNebula")
    EMISSION_NEBULA("emissionNebula"),

    /** A reflection nebula. */
    @SerialName("reflectionNebula")
    REFLECTION_NEBULA("reflectionNebula"),

    /** A dark nebula. */
    @SerialName("darkNebula")
    DARK_NEBULA("darkNebula"),

    /** A nebula of another kind. */
    @SerialName("nebula")
    NEBULA("nebula"),

    /** A planetary nebula. */
    @SerialName("planetaryNebula")
    PLANETARY_NEBULA("planetaryNebula"),

    /** A supernova remnant. */
    @SerialName("supernovaRemnant")
    SUPERNOVA_REMNANT("supernovaRemnant"),

    /** An open cluster. */
    @SerialName("openCluster")
    OPEN_CLUSTER("openCluster"),

    /** A globular cluster. */
    @SerialName("globularCluster")
    GLOBULAR_CLUSTER("globularCluster"),

    /** A cluster with nebulosity. */
    @SerialName("clusterWithNebula")
    CLUSTER_WITH_NEBULA("clusterWithNebula"),

    /** A star association. */
    @SerialName("starAssociation")
    STAR_ASSOCIATION("starAssociation"),

    /** An asterism. */
    @SerialName("asterism")
    ASTERISM("asterism"),

    /** The Milky Way core. */
    @SerialName("milkyWay")
    MILKY_WAY("milkyWay"),

    /** A celestial pole. */
    @SerialName("celestialPole")
    CELESTIAL_POLE("celestialPole"),

    /** A user-created target. */
    @SerialName("custom")
    CUSTOM("custom"),

    /** Anything else. */
    @SerialName("other")
    OTHER("other"),
    ;

    /** The English name shown to people, for example "Emission Nebula". */
    val displayName: String
        get() = when (this) {
            SUN -> "Sun"
            MOON -> "Moon"
            PLANET -> "Planet"
            COMET -> "Comet"
            ASTEROID -> "Asteroid"
            STAR -> "Star"
            DOUBLE_STAR -> "Double Star"
            GALAXY -> "Galaxy"
            GALAXY_GROUP -> "Galaxy Group"
            EMISSION_NEBULA -> "Emission Nebula"
            REFLECTION_NEBULA -> "Reflection Nebula"
            DARK_NEBULA -> "Dark Nebula"
            NEBULA -> "Nebula"
            PLANETARY_NEBULA -> "Planetary Nebula"
            SUPERNOVA_REMNANT -> "Supernova Remnant"
            OPEN_CLUSTER -> "Open Cluster"
            GLOBULAR_CLUSTER -> "Globular Cluster"
            CLUSTER_WITH_NEBULA -> "Cluster with Nebula"
            STAR_ASSOCIATION -> "Star Association"
            ASTERISM -> "Asterism"
            MILKY_WAY -> "Milky Way"
            CELESTIAL_POLE -> "Celestial Pole"
            CUSTOM -> "Custom Target"
            OTHER -> "Other"
        }

    /**
     * The name of an SF Symbol that suggests the category (the iOS app's icon names; the Android app maps
     * the category to its own icon). Every name is available on iOS 15 and later.
     */
    val systemImageName: String
        get() = when (this) {
            SUN -> "sun.max"
            MOON -> "moon"
            PLANET -> "circle.circle"
            COMET -> "sparkle"
            ASTEROID -> "circle.fill"
            STAR -> "star"
            DOUBLE_STAR -> "star.leadinghalf.filled"
            GALAXY -> "hurricane"
            GALAXY_GROUP -> "circle.hexagongrid"
            EMISSION_NEBULA -> "cloud.fill"
            REFLECTION_NEBULA -> "cloud.sun"
            DARK_NEBULA -> "cloud.fog.fill"
            NEBULA -> "cloud"
            PLANETARY_NEBULA -> "circle.dotted"
            SUPERNOVA_REMNANT -> "burst"
            OPEN_CLUSTER -> "sparkles"
            GLOBULAR_CLUSTER -> "aqi.medium"
            CLUSTER_WITH_NEBULA -> "cloud.moon"
            STAR_ASSOCIATION -> "aqi.low"
            ASTERISM -> "star.circle"
            MILKY_WAY -> "moon.stars"
            CELESTIAL_POLE -> "scope"
            CUSTOM -> "plus.circle"
            OTHER -> "sparkle"
        }

    /** The browse group the category is listed under. */
    val group: ObjectGroup
        get() = when (this) {
            SUN, MOON, PLANET, COMET, ASTEROID -> ObjectGroup.SOLAR_SYSTEM
            STAR, DOUBLE_STAR, ASTERISM -> ObjectGroup.STARS
            GALAXY, GALAXY_GROUP -> ObjectGroup.GALAXIES
            EMISSION_NEBULA, REFLECTION_NEBULA, DARK_NEBULA, NEBULA, PLANETARY_NEBULA,
            SUPERNOVA_REMNANT, CLUSTER_WITH_NEBULA,
            -> ObjectGroup.NEBULAE
            OPEN_CLUSTER, GLOBULAR_CLUSTER, STAR_ASSOCIATION -> ObjectGroup.CLUSTERS
            MILKY_WAY, CELESTIAL_POLE, CUSTOM, OTHER -> ObjectGroup.SPECIAL
        }

    companion object {
        /** The category whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): ObjectCategory? = entries.firstOrNull { it.rawValue == raw }

        /**
         * Creates the category of an OpenNGC object type code (`G`, `OCl`, `PN`, ...). Swift
         * `init(openNGCType:)`.
         *
         * | Code | Category |
         * | --- | --- |
         * | `G` | `GALAXY` |
         * | `GPair`, `GTrpl`, `GGroup` | `GALAXY_GROUP` |
         * | `OCl` | `OPEN_CLUSTER` |
         * | `GCl` | `GLOBULAR_CLUSTER` |
         * | `Cl+N` | `CLUSTER_WITH_NEBULA` |
         * | `PN` | `PLANETARY_NEBULA` |
         * | `HII`, `EmN` | `EMISSION_NEBULA` |
         * | `RfN` | `REFLECTION_NEBULA` |
         * | `DrkN` | `DARK_NEBULA` |
         * | `Neb` | `NEBULA` |
         * | `SNR` | `SUPERNOVA_REMNANT` |
         * | `*Ass` | `STAR_ASSOCIATION` |
         * | `**` | `DOUBLE_STAR` |
         * | `*` | `STAR` |
         *
         * Any other code (`Other`, `Nova`, or a code this version does not know) gives `OTHER`. Codes are
         * case-sensitive.
         */
        fun fromOpenNGCType(code: String): ObjectCategory = when (code) {
            "G" -> GALAXY
            "GPair", "GTrpl", "GGroup" -> GALAXY_GROUP
            "OCl" -> OPEN_CLUSTER
            "GCl" -> GLOBULAR_CLUSTER
            "Cl+N" -> CLUSTER_WITH_NEBULA
            "PN" -> PLANETARY_NEBULA
            "HII", "EmN" -> EMISSION_NEBULA
            "RfN" -> REFLECTION_NEBULA
            "DrkN" -> DARK_NEBULA
            "Neb" -> NEBULA
            "SNR" -> SUPERNOVA_REMNANT
            "*Ass" -> STAR_ASSOCIATION
            "**" -> DOUBLE_STAR
            "*" -> STAR
            else -> OTHER
        }
    }
}

/**
 * The groups the browse screens divide the catalog into.
 *
 * Serialized as its [rawValue] string (the Swift case name, e.g. `"solarSystem"`).
 *
 * @property rawValue The Swift case name, which is also the serialized form.
 */
@Serializable
enum class ObjectGroup(val rawValue: String) {
    /** The Sun, the Moon, the planets, comets and asteroids. */
    @SerialName("solarSystem")
    SOLAR_SYSTEM("solarSystem"),

    /** Stars, double stars and asterisms. */
    @SerialName("stars")
    STARS("stars"),

    /** Galaxies and groups of galaxies. */
    @SerialName("galaxies")
    GALAXIES("galaxies"),

    /** Nebulae, supernova remnants and clusters with nebulosity. */
    @SerialName("nebulae")
    NEBULAE("nebulae"),

    /** Open and globular clusters and star associations. */
    @SerialName("clusters")
    CLUSTERS("clusters"),

    /** The Milky Way core, the celestial poles, custom targets and everything else. */
    @SerialName("special")
    SPECIAL("special"),
    ;

    /** The English name shown to people, for example "Star Clusters". */
    val displayName: String
        get() = when (this) {
            SOLAR_SYSTEM -> "Solar System"
            STARS -> "Stars"
            GALAXIES -> "Galaxies"
            NEBULAE -> "Nebulae"
            CLUSTERS -> "Star Clusters"
            SPECIAL -> "Special"
        }

    /**
     * The name of an SF Symbol that suggests the group (the iOS app's icon names; the Android app maps the
     * group to its own icon). Every name is available on iOS 15 and later.
     */
    val systemImageName: String
        get() = when (this) {
            SOLAR_SYSTEM -> "sun.max"
            STARS -> "star"
            GALAXIES -> "hurricane"
            NEBULAE -> "cloud"
            CLUSTERS -> "sparkles"
            SPECIAL -> "scope"
        }

    /** The categories that belong to the group, in `ObjectCategory.entries` order. */
    val categories: List<ObjectCategory>
        get() = ObjectCategory.entries.filter { it.group == this }

    companion object {
        /** The group whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): ObjectGroup? = entries.firstOrNull { it.rawValue == raw }
    }
}
