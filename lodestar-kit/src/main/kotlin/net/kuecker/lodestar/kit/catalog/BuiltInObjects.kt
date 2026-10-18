package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate

/**
 * The objects that are not in the data files: the solar-system bodies, the Milky Way core and
 * the celestial poles.
 */
internal object BuiltInObjects {
    /** Right ascension of Sagittarius A* (J2000): 17h 45m 40.04s, in hours. */
    private const val sagittariusARightAscensionHours: Double = 17.0 + 45.0 / 60 + 40.04 / 3600

    /** Declination of Sagittarius A* (J2000): −29° 00′ 28.1″. */
    private const val sagittariusADeclination: Double = -(29.0 + 28.1 / 3600)

    /** The Sun, the Moon and the seven other planets, in `SolarSystemBody.entries` order. */
    fun solarSystem(): List<CelestialObject> = SolarSystemBody.entries.map { body ->
        val id: String
        val category: ObjectCategory
        when (body) {
            SolarSystemBody.SUN -> {
                id = "sun"
                category = ObjectCategory.SUN
            }
            SolarSystemBody.MOON -> {
                id = "moon"
                category = ObjectCategory.MOON
            }
            SolarSystemBody.MERCURY, SolarSystemBody.VENUS, SolarSystemBody.MARS, SolarSystemBody.JUPITER,
            SolarSystemBody.SATURN, SolarSystemBody.URANUS, SolarSystemBody.NEPTUNE,
            -> {
                id = "planet.${body.rawValue}"
                category = ObjectCategory.PLANET
            }
        }
        CelestialObject(
            id = id, name = body.displayName, alternateNames = alternateNames(body), category = category,
            position = ObjectPosition.SolarSystem(body), isListed = true,
            notes = if (body == SolarSystemBody.SUN) {
                "Look at the Sun only through a certified solar filter. Pointing a camera or telescope at it " +
                    "without one can damage eyes and equipment."
            } else {
                null
            },
        )
    }

    /** Other names people may search for. */
    private fun alternateNames(body: SolarSystemBody): List<String> = when (body) {
        SolarSystemBody.SUN -> listOf("Sol")
        SolarSystemBody.MOON -> listOf("Luna")
        else -> emptyList()
    }

    /** The Milky Way core and the two celestial poles. */
    fun special(constellations: ConstellationDirectory): List<CelestialObject> {
        fun constellationName(abbreviation: String): String? = constellations.constellation(abbreviation)?.name
        return listOf(
            CelestialObject(
                id = "special.galacticCenter", name = "Galactic Center", designations = listOf("Sgr A*"),
                alternateNames = listOf("Sagittarius A*", "Galactic Centre", "Milky Way Core", "Galactic Core"),
                category = ObjectCategory.MILKY_WAY, constellation = "Sgr", constellationName = constellationName("Sgr"),
                position = ObjectPosition.Fixed(
                    EquatorialCoordinate.fromRightAscensionHours(
                        rightAscensionHours = sagittariusARightAscensionHours,
                        declination = sagittariusADeclination,
                    ),
                    null,
                ),
                isListed = true,
                notes = "The direction of Sagittarius A*, the center of the Milky Way. The brightest part of the " +
                    "Milky Way band, and a favorite for wide-field astrophotography.",
            ),
            CelestialObject(
                id = "special.ncp", name = "North Celestial Pole", designations = listOf("NCP"),
                alternateNames = listOf("Celestial North Pole"), category = ObjectCategory.CELESTIAL_POLE,
                constellation = "UMi", constellationName = constellationName("UMi"),
                position = ObjectPosition.CelestialPole(north = true), isListed = true,
                notes = "The point in the sky that the Earth's axis points to; the stars appear to turn around it. " +
                    "Polaris lies within one degree of it.",
            ),
            CelestialObject(
                id = "special.scp", name = "South Celestial Pole", designations = listOf("SCP"),
                alternateNames = listOf("Celestial South Pole"), category = ObjectCategory.CELESTIAL_POLE,
                constellation = "Oct", constellationName = constellationName("Oct"),
                position = ObjectPosition.CelestialPole(north = false), isListed = true,
                notes = "The southern counterpart of the north celestial pole. No bright star marks it; the faint " +
                    "σ Octantis lies about one degree away.",
            ),
        )
    }
}
