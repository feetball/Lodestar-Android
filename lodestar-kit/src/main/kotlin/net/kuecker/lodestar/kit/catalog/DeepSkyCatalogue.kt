package net.kuecker.lodestar.kit.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How conspicuous an object is among objects of its kind, from [SHOWPIECE] to [OBSCURE].
 *
 * The value is part of the catalog data: for deep-sky objects it follows the rules in `Tools/README.md`
 * (catalogue membership, name, brightness and size; dark nebulae have no magnitude and are rated by name and
 * size), for stars it follows the magnitude, for comets and asteroids it follows the peak magnitude estimate
 * ([forSmallBody]), and the Sun, the Moon, the planets, the Milky Way core, the celestial poles and custom targets are
 * always showpieces. A smaller value is more conspicuous, so the
 * natural order is the order of importance: `SHOWPIECE < PROMINENT < NOTABLE < OBSCURE`.
 *
 * Use it to keep a crowded sky readable: draw (or label) only the objects up to a limit that grows as the
 * field of view narrows. The deep-sky objects with [NOTABLE] or better are the ones [CelestialObject.isListed]
 * lists.
 *
 * @property rawValue The number stored in the data files, 1 to 4.
 */
enum class Prominence(val rawValue: Int) {
    /** Messier and Caldwell objects, the best-known named targets, and anything brighter than magnitude 4. */
    SHOWPIECE(1),

    /** Bright (brighter than magnitude 8) or well-known objects, and the larger nebulae. */
    PROMINENT(2),

    /** Everything else that is bright, large or named enough to be worth a place in a list. */
    NOTABLE(3),

    /** Faint, small or obscure objects: found by search and by catalogue, drawn only at narrow fields. */
    OBSCURE(4),
    ;

    /** The English name shown to people, for example "Showpiece". */
    val displayName: String
        get() = when (this) {
            SHOWPIECE -> "Showpiece"
            PROMINENT -> "Prominent"
            NOTABLE -> "Notable"
            OBSCURE -> "Obscure"
        }

    companion object {
        /** The prominence whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: Int): Prominence? = entries.firstOrNull { it.rawValue == raw }

        /** The least prominent value a listed deep-sky object has. */
        val lastListed: Prominence = NOTABLE

        /** Stars brighter than this (visual magnitude, inclusive) are showpieces. */
        const val showpieceStarMagnitude: Double = 1.5

        /** Stars brighter than this (inclusive) are prominent. */
        const val prominentStarMagnitude: Double = 3.5

        /** Stars brighter than this (inclusive) are notable; fainter ones are obscure. */
        const val notableStarMagnitude: Double = 5.0

        /** Comets and asteroids with a peak magnitude estimate up to this value (inclusive) are showpieces: naked-eye class. */
        const val showpieceSmallBodyMagnitude: Double = 6.0

        /** Comets and asteroids with a peak magnitude estimate up to this value (inclusive) are prominent: binocular class. */
        const val prominentSmallBodyMagnitude: Double = 8.0

        /**
         * Comets and asteroids with a peak magnitude estimate up to this value (inclusive) are notable: within reach of
         * a camera on a tripod, the default limit of `SmallBodySelection`. Fainter ones, and bodies without magnitude
         * parameters, are obscure.
         */
        const val notableSmallBodyMagnitude: Double = 10.0

        /**
         * The prominence of a comet or an asteroid from its peak magnitude estimate
         * ([net.kuecker.lodestar.kit.comets.SmallBodyBrightness.peakMagnitudeEstimate]): 1 up to magnitude 6, 2 up to 8,
         * 3 up to 10, else 4; a body without an estimate is 4 (Swift `init(smallBodyPeakMagnitude:)`).
         */
        fun forSmallBody(peakMagnitude: Double?): Prominence = when {
            peakMagnitude == null -> OBSCURE
            peakMagnitude <= showpieceSmallBodyMagnitude -> SHOWPIECE
            peakMagnitude <= prominentSmallBodyMagnitude -> PROMINENT
            peakMagnitude <= notableSmallBodyMagnitude -> NOTABLE
            else -> OBSCURE
        }

        /**
         * The prominence of a star of visual magnitude [starMagnitude]: 1 up to magnitude 1.5, 2 up to 3.5, 3 up
         * to 5.0, else 4 (Swift `init(starMagnitude:)`).
         */
        fun forStar(starMagnitude: Double): Prominence = when {
            starMagnitude <= showpieceStarMagnitude -> SHOWPIECE
            starMagnitude <= prominentStarMagnitude -> PROMINENT
            starMagnitude <= notableStarMagnitude -> NOTABLE
            else -> OBSCURE
        }
    }
}

/**
 * A deep-sky catalogue whose numbers the catalog knows, for filters and numbered lists.
 *
 * Serialized as its [rawValue] string (the Swift case name, e.g. `"sharpless"`).
 *
 * @property rawValue The Swift case name.
 * @property displayName The English name of the catalogue ("Sharpless").
 * @property abbreviation The usual abbreviation used in labels ("Sh2", "LDN", "Cr").
 */
@Serializable
enum class DeepSkyCatalogue(val rawValue: String, val displayName: String, val abbreviation: String) {
    /** Messier's 110 objects. */
    @SerialName("messier")
    MESSIER("messier", "Messier", "M"),

    /** Patrick Moore's 109 Caldwell objects. */
    @SerialName("caldwell")
    CALDWELL("caldwell", "Caldwell", "C"),

    /** The New General Catalogue. */
    @SerialName("ngc")
    NGC("ngc", "NGC", "NGC"),

    /** The Index Catalogues. */
    @SerialName("ic")
    IC("ic", "IC", "IC"),

    /** Sharpless' second catalogue of HII regions (313 regions, `Sh2-1` to `Sh2-313`). */
    @SerialName("sharpless")
    SHARPLESS("sharpless", "Sharpless", "Sh2"),

    /** Barnard's catalogue of dark objects (349 of them, numbers up to 370, some with a letter). */
    @SerialName("barnard")
    BARNARD("barnard", "Barnard", "B"),

    /** Lynds' catalogue of dark nebulae (the darkest and the largest clouds). */
    @SerialName("lynds")
    LYNDS("lynds", "Lynds", "LDN"),

    /** Collinder's open clusters (the large ones). */
    @SerialName("collinder")
    COLLINDER("collinder", "Collinder", "Cr"),

    /** Melotte's open clusters. */
    @SerialName("melotte")
    MELOTTE("melotte", "Melotte", "Mel"),

    /** Trumpler's open clusters. */
    @SerialName("trumpler")
    TRUMPLER("trumpler", "Trumpler", "Tr"),

    /** Stock's open clusters. */
    @SerialName("stock")
    STOCK("stock", "Stock", "St"),
    ;

    companion object {
        /** The catalogue whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): DeepSkyCatalogue? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * An object's number in a [DeepSkyCatalogue].
 *
 * @property catalogue The catalogue.
 * @property number The number, 1 or more.
 * @property suffix The letter some numbers carry ("a" in Barnard 67a, "A" in NGC 2818A), or empty.
 */
@ConsistentCopyVisibility
data class CatalogNumber internal constructor(
    val catalogue: DeepSkyCatalogue,
    val number: Int,
    val suffix: String = "",
) {
    /** The short label: "M 31", "NGC 7000", "C 14", "Sh2-155", "LDN 1622", "B 33a". */
    val label: String
        get() = if (catalogue == DeepSkyCatalogue.SHARPLESS) {
            "Sh2-$number"
        } else {
            "${catalogue.abbreviation} $number$suffix"
        }
}
