package net.kuecker.lodestar.kit.comets

/**
 * A comet or an asteroid: identity, orbit and brightness model. Immutable; build one with the parsers
 * ([CometElementsParser], [MinorPlanetElementsParser], [SmallBodyJSONParser]) or with [create].
 *
 * @property id A stable identifier for lists and favourites: `"comet.C/2023 A3"`, `"comet.12P"`, `"asteroid.4"`
 * (a numbered asteroid) or `"asteroid.2024 YR4"`. It is made of [kind] and [designation] only, so it survives element updates.
 * @property designation The official designation without the name: `"C/2023 A3"`, `"12P"` (a periodic comet with its
 * number), `"73P-B"` (a fragment), `"P/2020 K9"`, `"4"` (a numbered asteroid) or `"2024 YR4"`.
 * @property name The common name (`"Tsuchinshan-ATLAS"`, `"Pons-Brooks"`, `"Vesta"`), or an empty string.
 * @property kind Comet or asteroid.
 * @property number The periodic-comet number (12 for 12P) or the asteroid number, if it has one.
 * @property orbit The heliocentric orbit.
 * @property photometry How to compute the magnitude; `null` when the source gave no magnitude parameters.
 */
@ConsistentCopyVisibility
data class SmallBody private constructor(
    val id: String,
    val designation: String,
    val name: String,
    val kind: SmallBodyKind,
    val number: Int?,
    val orbit: OrbitalElements,
    val photometry: SmallBodyPhotometry?,
) {
    /** The name if there is one, else the designation. */
    val displayName: String get() = if (name.isEmpty()) designation else name

    /**
     * The full title as astronomers write it: `"C/2023 A3 (Tsuchinshan-ATLAS)"`, `"12P/Pons-Brooks"`, `"(4) Vesta"`,
     * `"2024 YR4"`.
     */
    val title: String
        get() = when (kind) {
            SmallBodyKind.COMET -> when {
                name.isEmpty() -> designation
                number != null -> "$designation/$name"
                else -> "$designation ($name)"
            }
            SmallBodyKind.ASTEROID -> when {
                number != null && name.isEmpty() -> "($designation)"
                number != null -> "($designation) $name"
                else -> designation
            }
        }

    companion object {
        /** The longest accepted designation or name, in characters. */
        const val maximumTextLength: Int = 80

        /**
         * Creates a body, or returns `null` if [designation] is empty after trimming, a text is longer than
         * [maximumTextLength] characters or contains control characters, or [number] is not in 1…99 999 999.
         * Surrounding spaces of the texts are removed. The identifier is built from [kind] and [designation].
         */
        fun create(
            designation: String,
            name: String = "",
            kind: SmallBodyKind,
            number: Int? = null,
            orbit: OrbitalElements,
            photometry: SmallBodyPhotometry? = null,
        ): SmallBody? {
            val cleanDesignation = cleaned(designation) ?: return null
            val cleanName = cleaned(name) ?: return null
            if (cleanDesignation.isEmpty()) return null
            if (number != null && (number < 1 || number > 99_999_999)) return null
            return SmallBody(
                id = "${kind.rawValue}.$cleanDesignation",
                designation = cleanDesignation,
                name = cleanName,
                kind = kind,
                number = number,
                orbit = orbit,
                photometry = photometry,
            )
        }

        /** [text] without surrounding spaces and tabs, or `null` if it is too long or has control characters. */
        internal fun cleaned(text: String): String? {
            val scalars = text.codePoints().toArray()
            var first = 0
            var last = scalars.size
            while (first < last && (scalars[first] == 0x20 || scalars[first] == 0x09)) first++
            while (last > first && (scalars[last - 1] == 0x20 || scalars[last - 1] == 0x09)) last--
            if (last - first > maximumTextLength) return null
            for (index in first until last) {
                val scalar = scalars[index]
                if (scalar < 0x20 || (scalar in 0x7F..0x9F)) return null
            }
            return String(scalars, first, last - first)
        }
    }
}
