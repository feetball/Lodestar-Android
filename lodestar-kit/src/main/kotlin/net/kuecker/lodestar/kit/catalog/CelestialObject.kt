package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.ProperMotion
import java.util.Locale
import java.util.UUID

/** Where an object is, and how its position is found. */
sealed interface ObjectPosition {
    /**
     * A fixed place on the celestial sphere: the J2000 catalog position and the proper motion, if known.
     */
    data class Fixed(val coordinate: EquatorialCoordinate, val properMotion: ProperMotion?) : ObjectPosition

    /** A solar-system body; its position comes from the ephemeris. */
    data class SolarSystem(val body: SolarSystemBody) : ObjectPosition

    /** The north or south celestial pole of date. */
    data class CelestialPole(val north: Boolean) : ObjectPosition
}

/**
 * A star, deep-sky object, solar-system body, special direction or user-made target.
 *
 * Objects are immutable values. Two objects are equal when all their properties are equal, but
 * they hash by [id] alone, which is stable between app launches and catalog versions.
 *
 * @property id A stable identifier: `"sun"`, `"moon"`, `"planet.mars"`, `"star.hip32349"` (or `"star.hr<n>"`,
 * `"star.hyg<n>"` for stars without a Hipparcos number), `"dso.M31"`, `"dso.NGC7000"`,
 * `"special.galacticCenter"`, `"special.ncp"`, `"special.scp"` or `"custom.<uuid>"`.
 * @property name The name shown in lists. Stars use their proper name, else the Bayer designation with the
 * constellation ("γ Cas"), else the Flamsteed number ("48 Cas"), else a catalog number ("HR 123").
 * Deep-sky objects use their first common name, else "M 31", "NGC 7000" or "IC 434".
 * @property designations Catalog designations, in display order: for example "M 31", "NGC 224" or "α CMa",
 * "Alpha Canis Majoris", "9 CMa", "HR 2491", "HD 48915", "HIP 32349". May contain [name].
 * @property alternateNames Common names other than [name].
 * @property category The kind of object.
 * @property constellation The IAU abbreviation of the constellation the object lies in ("CMa"), if known.
 * @property constellationName The full name of the constellation ("Canis Major"), if known.
 * @property magnitude The visual magnitude, if known. For the few deep-sky objects that only have a blue
 * magnitude in the source data, that value. `null` for the Sun, the Moon and the planets, whose
 * brightness changes; use `SkyFrame.currentMagnitude(obj)` for those.
 * @property angularSize The size of an extended object, if known.
 * @property position Where the object is.
 * @property colorIndex The B−V color index of a star, if known.
 * @property spectralType The spectral type of a star, if known.
 * @property distanceLightYears The distance in light-years, if known.
 * @property isListed Whether browse lists show the object. All solar-system and special objects are listed, and
 * so are the deep-sky objects whose [prominence] is [Prominence.NOTABLE] or better, stars with a proper name and
 * other stars of magnitude 3.5 or brighter. The remaining objects (the obscure deep-sky objects and the faint
 * stars) are only found by search, by catalogue ([Catalog.objects] with a [DeepSkyCatalogue]) and drawn on the sky.
 * @property notes A short note for the detail view, if there is something worth saying.
 * @property prominence How conspicuous the object is, see [Prominence]. Deep-sky objects have the value of the
 * data file (a record without one counts as [Prominence.NOTABLE]), stars follow their magnitude
 * ([Prominence.forStar]), and the Sun, the Moon, the planets, the special directions and custom targets are
 * showpieces.
 * @property catalogNumbers The object's numbers in the catalogues of [DeepSkyCatalogue], in the order of
 * [designations] (Messier, NGC, IC, then the others). Empty for stars, solar-system bodies and deep-sky objects
 * that are only in other catalogues (ESO, PGC, UGC...).
 */
@ConsistentCopyVisibility
data class CelestialObject internal constructor(
    val id: String,
    val name: String,
    val designations: List<String> = emptyList(),
    val alternateNames: List<String> = emptyList(),
    val category: ObjectCategory,
    val constellation: String? = null,
    val constellationName: String? = null,
    val magnitude: Double? = null,
    val angularSize: AngularSize? = null,
    val position: ObjectPosition,
    val colorIndex: Double? = null,
    val spectralType: String? = null,
    val distanceLightYears: Double? = null,
    val isListed: Boolean,
    val notes: String? = null,
    val prominence: Prominence = Prominence.NOTABLE,
    val catalogNumbers: List<CatalogNumber> = emptyList(),
) {
    /** Hashes the identifier only, so objects with the same identifier hash alike. */
    override fun hashCode(): Int = id.hashCode()

    // MARK: Derived values

    /** The browse group of the object's category. */
    val group: ObjectGroup get() = category.group

    /** The catalogues the object is in, in the order of [catalogNumbers] (for example Messier, NGC, Caldwell). */
    val catalogues: List<DeepSkyCatalogue> get() = catalogNumbers.map { it.catalogue }.distinct()

    /** `true` for the objects of the deep-sky data (identifiers starting with `"dso."`). */
    val isDeepSky: Boolean get() = id.startsWith("dso.")

    /** The J2000 position of a fixed object; `null` for solar-system bodies and the poles. */
    val j2000: EquatorialCoordinate? get() = (position as? ObjectPosition.Fixed)?.coordinate

    /** `true` for the Sun, the Moon and the planets. */
    val isSolarSystem: Boolean get() = position is ObjectPosition.SolarSystem

    /** One line describing the object, such as "Galaxy · Andromeda · mag 3.4". */
    val subtitle: String
        get() {
            val parts = arrayListOf(category.displayName)
            if (constellationName != null) parts.add(constellationName)
            if (magnitude != null) parts.add("mag ${CatalogFormat.oneDecimal(magnitude)}")
            return parts.joinToString(separator = " · ")
        }

    /**
     * The magnitude used to order objects by brightness: the catalog magnitude, or for
     * solar-system bodies a typical peak magnitude, or +∞ when nothing is known.
     */
    internal val rankingMagnitude: Double
        get() {
            if (magnitude != null) return magnitude
            val position = position
            if (position is ObjectPosition.SolarSystem) return typicalPeakMagnitude(position.body)
            return Double.POSITIVE_INFINITY
        }

    companion object {
        /** The longest name a custom target keeps, in characters. */
        const val maximumCustomNameLength: Int = 60

        /** The name given to a custom target whose name is empty. */
        const val defaultCustomName: String = "Custom Target"

        private fun typicalPeakMagnitude(body: SolarSystemBody): Double = when (body) {
            SolarSystemBody.SUN -> -26.7
            SolarSystemBody.MOON -> -12.7
            SolarSystemBody.VENUS -> -4.4
            SolarSystemBody.JUPITER -> -2.7
            SolarSystemBody.MARS -> -2.0
            SolarSystemBody.MERCURY -> -1.9
            SolarSystemBody.SATURN -> -0.2
            SolarSystemBody.URANUS -> 5.7
            SolarSystemBody.NEPTUNE -> 7.8
        }

        // MARK: Custom targets

        /**
         * Creates a user-made target at a J2000 position, with a new identifier.
         *
         * The name is trimmed, runs of whitespace become single spaces, control characters are dropped
         * and the result is cut to [maximumCustomNameLength] characters; an empty name becomes
         * [defaultCustomName]. Components of [j2000] that are not finite are replaced by 0.
         */
        fun custom(name: String, j2000: EquatorialCoordinate): CelestialObject =
            custom(UUID.randomUUID(), name, j2000)

        /**
         * Creates a user-made target with a given identifier, to restore a target saved earlier.
         *
         * The object's `id` is `"custom."` followed by the UUID string in upper case (the form Swift's
         * `UUID.uuidString` writes, so identifiers saved by the iOS app restore unchanged). See
         * [custom] (name and position) for how the name and the position are cleaned up.
         */
        fun custom(id: UUID, name: String, j2000: EquatorialCoordinate): CelestialObject {
            val position = EquatorialCoordinate(
                rightAscension = if (j2000.rightAscension.isFinite()) j2000.rightAscension else 0.0,
                declination = if (j2000.declination.isFinite()) j2000.declination else 0.0,
            )
            val cleanedName = cleanedCustomName(name)
            return CelestialObject(
                id = "custom.${id.toString().uppercase(Locale.ROOT)}",
                name = cleanedName.ifEmpty { defaultCustomName },
                category = ObjectCategory.CUSTOM,
                position = ObjectPosition.Fixed(position, null),
                isListed = true,
                prominence = Prominence.SHOWPIECE,
            )
        }

        private fun cleanedCustomName(raw: String): String {
            // Bounds the work for absurdly long input before looking at grapheme clusters.
            val scalarLimit = 8 * maximumCustomNameLength
            val result = StringBuilder()
            var keptScalars = 0
            var pendingSpace = false
            var index = 0
            while (index < raw.length) {
                if (keptScalars >= scalarLimit) break
                val scalar = raw.codePointAt(index)
                index += Character.charCount(scalar)
                if (CatalogText.isWhiteSpace(scalar)) {
                    pendingSpace = keptScalars > 0
                    continue
                }
                when (Character.getType(scalar).toByte()) {
                    Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                    Character.UNASSIGNED,
                    -> continue
                    else -> Unit
                }
                if (pendingSpace) {
                    result.append(' ')
                    pendingSpace = false
                    keptScalars += 1
                }
                result.appendCodePoint(scalar)
                keptScalars += 1
            }
            val capped = CatalogText.prefixCharacters(result.toString(), maximumCustomNameLength)
            return CatalogText.trimmingSpacesAndTabs(capped)
        }
    }
}
