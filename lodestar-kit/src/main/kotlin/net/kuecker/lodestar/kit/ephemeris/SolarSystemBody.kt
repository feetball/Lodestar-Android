package net.kuecker.lodestar.kit.ephemeris

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A solar-system body whose apparent position is computed by [Ephemeris]: the Sun, the Moon, the
 * seven other planets and Pluto, a dwarf planet that is listed with the planets. (The Earth is the
 * observer's home and is not listed.)
 *
 * Serialized as its [rawValue] string, like the Swift `Codable` raw-value enum.
 *
 * @property rawValue The lowercase English name used as the serialized form, e.g. `"jupiter"`.
 */
@Serializable
enum class SolarSystemBody(val rawValue: String) {
    /** The Sun. */
    @SerialName("sun")
    SUN("sun"),

    /** The Moon. */
    @SerialName("moon")
    MOON("moon"),

    /** Mercury. */
    @SerialName("mercury")
    MERCURY("mercury"),

    /** Venus. */
    @SerialName("venus")
    VENUS("venus"),

    /** Mars. */
    @SerialName("mars")
    MARS("mars"),

    /** Jupiter. */
    @SerialName("jupiter")
    JUPITER("jupiter"),

    /** Saturn. */
    @SerialName("saturn")
    SATURN("saturn"),

    /** Uranus. */
    @SerialName("uranus")
    URANUS("uranus"),

    /** Neptune. */
    @SerialName("neptune")
    NEPTUNE("neptune"),

    /** Pluto, a dwarf planet. */
    @SerialName("pluto")
    PLUTO("pluto"),
    ;

    /** The English name, e.g. "Jupiter". */
    val displayName: String
        get() = when (this) {
            SUN -> "Sun"
            MOON -> "Moon"
            MERCURY -> "Mercury"
            VENUS -> "Venus"
            MARS -> "Mars"
            JUPITER -> "Jupiter"
            SATURN -> "Saturn"
            URANUS -> "Uranus"
            NEPTUNE -> "Neptune"
            PLUTO -> "Pluto"
        }

    /**
     * Mean (volumetric) radius in kilometres: the IAU nominal solar radius, the Moon, and the
     * planets' volumetric means (Pluto's is 1,188.3 km, IAU 2015 and JPL Horizons). For the gas
     * giants this is a few per cent below the equatorial radius of the visible cloud tops.
     */
    val meanRadiusKilometers: Double
        get() = when (this) {
            SUN -> 695_700.0
            MOON -> 1_737.4
            MERCURY -> 2_439.7
            VENUS -> 6_051.8
            MARS -> 3_389.5
            JUPITER -> 69_911.0
            SATURN -> 58_232.0
            URANUS -> 25_362.0
            NEPTUNE -> 24_622.0
            PLUTO -> 1_188.3
        }

    /**
     * `true` for Mercury through Neptune and for Pluto (a dwarf planet, listed with the planets); `false` for the Sun
     * and the Moon.
     */
    val isPlanet: Boolean
        get() = when (this) {
            SUN, MOON -> false
            MERCURY, VENUS, MARS, JUPITER, SATURN, URANUS, NEPTUNE, PLUTO -> true
        }

    companion object {
        /** The body whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): SolarSystemBody? = entries.firstOrNull { it.rawValue == raw }
    }
}
