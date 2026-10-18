package net.kuecker.lodestar.kit.catalog

/**
 * A text an object can be found by, and which of the object's displayed texts it stands for.
 *
 * @property entry 0 is the object's name, then come its designations, then its alternate names.
 */
internal class KeyText(val text: String, val entry: Int)

/** A finished object together with the texts it can be found by. */
internal class ObjectDraft(val obj: CelestialObject, val keys: List<KeyText>) {
    companion object {
        /** A draft whose keys are the object's name, designations and alternate names as displayed (Swift `init(plain:)`). */
        fun plain(obj: CelestialObject): ObjectDraft {
            val keys = ArrayList<KeyText>(1 + obj.designations.size + obj.alternateNames.size)
            keys.add(KeyText(obj.name, 0))
            for ((index, designation) in obj.designations.withIndex()) {
                keys.add(KeyText(designation, index + 1))
            }
            for ((index, name) in obj.alternateNames.withIndex()) {
                keys.add(KeyText(name, obj.designations.size + index + 1))
            }
            return ObjectDraft(obj, keys)
        }
    }
}

/** The constellations by IAU abbreviation (case-insensitive). */
internal class ConstellationDirectory(val constellations: List<Constellation>) {
    private val indexByAbbreviation: Map<String, Int>

    /** The same by the abbreviation as spelled in the data, which the records of the catalog use. */
    private val indexByExactAbbreviation: Map<String, Int>

    init {
        val index = HashMap<String, Int>()
        val exact = HashMap<String, Int>()
        for ((offset, constellation) in constellations.withIndex()) {
            index[constellation.abbreviation.lowercase(java.util.Locale.ROOT)] = offset
            exact[constellation.abbreviation] = offset
        }
        indexByAbbreviation = index
        indexByExactAbbreviation = exact
    }

    /** The constellation with an IAU abbreviation, ignoring case. */
    fun constellation(abbreviation: String): Constellation? {
        // Lower-casing makes a copy; the catalog's own records spell the abbreviations as the file does.
        val offset = indexByExactAbbreviation[abbreviation] ?: indexByAbbreviation[abbreviation.lowercase(java.util.Locale.ROOT)]
        return if (offset != null) constellations[offset] else null
    }
}

/** How the catalog identifiers of the deep-sky data are written and abbreviated for people. */
internal object CatalogDesignation {
    /**
     * One designation as shown and the other ways people type it.
     *
     * @property display The form shown ("Caldwell 14").
     * @property aliases Other forms people type ("C 14").
     */
    class Spelling(val display: String, val aliases: List<String>)

    /**
     * The form shown and the other spellings of an identifier prefix.
     *
     * @property catalogue The catalogue the prefix stands for, if it is one of [DeepSkyCatalogue].
     */
    private class Names(val display: String, val aliases: List<String>, val catalogue: DeepSkyCatalogue? = null)

    /** Prefix of a compact designation → the form shown and the other spellings of the prefix. */
    private val prefixes: Map<String, Names> = mapOf(
        "M" to Names("M", listOf("Messier"), DeepSkyCatalogue.MESSIER),
        "NGC" to Names("NGC", emptyList(), DeepSkyCatalogue.NGC),
        "IC" to Names("IC", emptyList(), DeepSkyCatalogue.IC),
        "C" to Names("Caldwell", listOf("C"), DeepSkyCatalogue.CALDWELL),
        "Mel" to Names("Melotte", listOf("Mel"), DeepSkyCatalogue.MELOTTE),
        "B" to Names("Barnard", listOf("B"), DeepSkyCatalogue.BARNARD),
        "H" to Names("Harvard", listOf("H")),
        "Cl" to Names("Collinder", listOf("Cl", "Cr"), DeepSkyCatalogue.COLLINDER),
        "Tr" to Names("Trumpler", listOf("Tr"), DeepSkyCatalogue.TRUMPLER),
        "St" to Names("Stock", listOf("St"), DeepSkyCatalogue.STOCK),
        "LDN" to Names("LDN", listOf("Lynds", "L"), DeepSkyCatalogue.LYNDS),
        "ESO" to Names("ESO", emptyList()),
        "HCG" to Names("HCG", listOf("Hickson")),
        "MWSC" to Names("MWSC", emptyList()),
        "PGC" to Names("PGC", emptyList()),
        "UGC" to Names("UGC", emptyList()),
    )

    /** The prefix of the Sharpless 2 designations, which carry the catalogue number: `Sh2-155`. */
    private const val sharplessPrefix = "Sh2-"

    /** The longest number a Sharpless 2 designation has (the catalogue ends at 313). */
    private const val maximumSharplessDigits = 4

    /** "Sharpless 2-155" is only an alias of numbers with this many digits, see [spelling]. */
    private const val minimumDigitsOfUnambiguousSharplessAlias = 3

    /** The longest prefix and the longest number a compact designation has. */
    private const val maximumPrefixLength = 6
    private const val maximumNumberDigits = 6

    /**
     * The spelling of a compact designation, that is an OpenNGC identifier such as `M31`, `C14`, `Sh2-155`,
     * `LDN1622`, `ESO56-115` or `IC2431_NED02`.
     *
     * Sharpless regions are shown as "Sh2-155" and found as "Sh 2-155", "Sharpless 155" and, from Sh2-100 on,
     * "Sharpless 2-155". The last form is left out below 100 because a search key has no punctuation:
     * "Sharpless 2-45" and "Sharpless 245" are the same key, and "Sharpless 245" must find Sh2-245, not M 17.
     */
    fun spelling(id: String): Spelling {
        if (id.startsWith(sharplessPrefix) && id.length > sharplessPrefix.length) {
            val number = id.substring(sharplessPrefix.length)
            val aliases = arrayListOf("Sh 2-$number", "Sharpless $number")
            if (number.length >= minimumDigitsOfUnambiguousSharplessAlias) aliases.add("Sharpless 2-$number")
            return Spelling("Sh2-$number", aliases)
        }
        var prefixLength = 0
        while (prefixLength < id.length && isAsciiLetter(id[prefixLength])) prefixLength += 1
        val prefix = id.substring(0, prefixLength)
        val number = id.substring(prefixLength).replace('_', ' ')
        if (prefix.isEmpty() || number.isEmpty()) {
            return Spelling(id.replace('_', ' '), emptyList())
        }
        val known = prefixes[prefix]
        val display = known?.display ?: prefix
        val aliases = known?.aliases ?: emptyList()
        return Spelling("$display $number", aliases.map { "$it $number" })
    }

    /**
     * Whether [text] is a compact designation of the kind the `cat` member of a deep-sky record holds: one to
     * six ASCII letters, the first of them upper case, and a number of up to six digits, optionally followed by
     * one more letter (`C20`, `Mel22`, `B67a`, `NGC2818A`, `LDN1622`), or a Sharpless 2 designation (`Sh2-117`).
     */
    fun isCompact(text: String): Boolean = parse(text) != null

    /** A compact designation split into its prefix, its number and the letter after the number, if any. */
    private class Parts(val prefix: String, val number: Int, val suffix: String)

    private fun parse(text: String): Parts? {
        if (text.startsWith(sharplessPrefix)) {
            val digits = text.substring(sharplessPrefix.length)
            val number = number(digits, maximumSharplessDigits) ?: return null
            return Parts("Sh2", number, "")
        }
        var index = 0
        while (index < text.length && isAsciiLetter(text[index])) index += 1
        if (index == 0 || index > maximumPrefixLength || text[0] !in 'A'..'Z') return null
        val prefix = text.substring(0, index)
        var end = index
        while (end < text.length && text[end] in '0'..'9') end += 1
        val number = number(text.substring(index, end), maximumNumberDigits) ?: return null
        val suffix = text.substring(end)
        if (suffix.length > 1 || (suffix.isNotEmpty() && !isAsciiLetter(suffix[0]))) return null
        return Parts(prefix, number, suffix)
    }

    private fun number(digits: String, maximumDigits: Int): Int? {
        if (digits.isEmpty() || digits.length > maximumDigits) return null
        for (character in digits) {
            if (character !in '0'..'9') return null
        }
        val value = digits.toInt()
        return if (value >= 1) value else null
    }

    /**
     * The catalogue number of a compact designation (`M31`, `NGC7000`, `C14`, `Sh2-155`, `B67a`, `LDN1622`,
     * `Cl399`, `Mel20`), or `null` for an identifier of a catalogue that is not a [DeepSkyCatalogue] (`ESO56-115`,
     * `H20`, `PGC143`) or for something that is no designation.
     */
    fun catalogNumber(text: String): CatalogNumber? {
        val parts = parse(text) ?: return null
        val catalogue = if (parts.prefix == "Sh2") {
            DeepSkyCatalogue.SHARPLESS
        } else {
            prefixes[parts.prefix]?.catalogue
        } ?: return null
        return CatalogNumber(catalogue, parts.number, parts.suffix)
    }

    private fun isAsciiLetter(character: Char): Boolean = character in 'a'..'z' || character in 'A'..'Z'
}

/** Turns validated records into catalog objects and the texts they are found by. */
internal object CatalogFactory {
    const val lightYearsPerParsec: Double = 3.261_563_777_167_433

    /** Stars without a proper name that are brighter than this appear in browse lists. */
    const val maximumListedStarMagnitude: Double = 3.5

    /** Popular names of a few stars that the star data does not have, by Hipparcos number. */
    val popularNames: Map<Int, List<String>> = mapOf(
        11767 to listOf("North Star", "Pole Star", "Lodestar"), // Polaris
        32349 to listOf("Dog Star"), // Sirius
    )

    /** The Hipparcos number of 3C 273, the one entry of the star data that is a quasar and not a star. */
    const val quasarHipparcosNumber: Int = 60936

    private const val quasarNote = "A quasar: the glowing core of a distant galaxy, about 2.4 billion light-years away, " +
        "and not a star. It is among the brightest quasars in the sky but still needs a telescope to be seen."

    private val NO_SUFFIX = listOf("")

    /** A Bayer designation split into its Greek letter and superscript digits ("α¹" → "α" and "¹"). */
    private class BayerLabel(val glyph: String, val suffix: String, val letter: GreekAlphabet.Letter?) {
        companion object {
            fun create(text: String): BayerLabel? {
                if (text.isEmpty()) return null
                val first = text.codePointAt(0)
                return BayerLabel(
                    glyph = String(Character.toChars(first)),
                    suffix = text.substring(Character.charCount(first)),
                    letter = GreekAlphabet.letter(first),
                )
            }
        }
    }

    /**
     * Creates a star. [duplicates] are deep-sky records that describe the same star; their
     * designations and names are added to it, and so are the popular names the star may have. The
     * quasar 3C 273 that the star data contains is created as an object of category `OTHER` with an
     * explanatory note.
     */
    fun star(
        entry: StarEntry,
        constellations: ConstellationDirectory,
        duplicates: List<DeepSkyEntry> = emptyList(),
    ): ObjectDraft {
        val constellation = entry.constellation?.let { constellations.constellation(it) }
        val abbreviation = constellation?.abbreviation ?: entry.constellation
        val genitive = constellation?.genitive

        val designations = ArrayList<String>()
        // Spelling variants of designations; the entry refers to the designation they stand for.
        val variants = ArrayList<KeyText>()

        var bayerName: String? = null
        val label = if (abbreviation != null) entry.bayer?.let { BayerLabel.create(it) } else null
        if (abbreviation != null && label != null) {
            val short = "${label.glyph}${label.suffix} $abbreviation"
            designations.add(short)
            bayerName = short
            val shortEntry = designations.size
            var longEntry = shortEntry
            val letter = label.letter
            if (letter != null && genitive != null) {
                designations.add("${letter.capitalizedName}${label.suffix} $genitive")
                longEntry = designations.size
            }
            // "alpha", "alp" and "α", with and without the superscript digits, followed by the
            // constellation as abbreviation or genitive.
            val letterForms = if (letter != null) letter.forms else listOf(label.glyph)
            val suffixes = if (label.suffix.isEmpty()) NO_SUFFIX else listOf(label.suffix, "")
            for (form in letterForms) {
                for (suffix in suffixes) {
                    variants.add(KeyText("$form$suffix $abbreviation", shortEntry))
                    if (genitive != null) {
                        variants.add(KeyText("$form$suffix $genitive", longEntry))
                    }
                }
            }
        }

        var flamsteedName: String? = null
        val flamsteedNumber = entry.flamsteed
        if (flamsteedNumber != null && abbreviation != null) {
            designations.add("$flamsteedNumber $abbreviation")
            flamsteedName = designations.last()
            if (genitive != null) {
                variants.add(KeyText("$flamsteedNumber $genitive", designations.size))
            }
        }

        var catalogName: String? = null
        val harvard = entry.harvard
        if (harvard != null) {
            designations.add("HR $harvard")
            catalogName = catalogName ?: designations.last()
        }
        val henryDraper = entry.henryDraper
        if (henryDraper != null) {
            designations.add("HD $henryDraper")
            catalogName = catalogName ?: designations.last()
        }
        val hipparcos = entry.hipparcos
        val hygRow = entry.hygRow
        if (hipparcos != null) {
            designations.add("HIP $hipparcos")
            catalogName = catalogName ?: designations.last()
        } else if (harvard == null && henryDraper == null && hygRow != null) {
            designations.add("HYG $hygRow")
            catalogName = catalogName ?: designations.last()
        }

        for (duplicate in duplicates) {
            for (spelling in spellings(duplicate)) {
                designations.add(spelling.display)
                for (alias in spelling.aliases) {
                    variants.add(KeyText(alias, designations.size))
                }
            }
        }

        val objectID = entry.objectID
        val name = entry.properName ?: bayerName ?: flamsteedName ?: catalogName ?: objectID
        val keys = ArrayList<KeyText>(1 + designations.size + variants.size + 2)
        keys.add(KeyText(name, 0))
        for ((index, designation) in designations.withIndex()) {
            keys.add(KeyText(designation, index + 1))
        }
        keys.addAll(variants)

        // Popular names of the star, and names of the duplicates, that the star does not already answer to.
        val alternateNames = ArrayList<String>()
        val otherNames = ArrayList<String>()
        if (hipparcos != null) popularNames[hipparcos]?.let { otherNames.addAll(it) }
        for (duplicate in duplicates) otherNames.addAll(duplicate.names)
        if (otherNames.isNotEmpty()) {
            val knownKeys = HashSet<String>()
            for (key in keys) knownKeys.add(keyIdentity(key.text))
            for (alternate in otherNames) {
                if (knownKeys.add(keyIdentity(alternate))) {
                    alternateNames.add(alternate)
                    keys.add(KeyText(alternate, designations.size + alternateNames.size))
                }
            }
        }

        val isQuasar = hipparcos == quasarHipparcosNumber
        val obj = CelestialObject(
            id = objectID, name = name, designations = designations, alternateNames = alternateNames,
            category = if (isQuasar) ObjectCategory.OTHER else ObjectCategory.STAR,
            constellation = abbreviation, constellationName = constellation?.name,
            magnitude = entry.magnitude, position = ObjectPosition.Fixed(entry.position, entry.properMotion),
            colorIndex = entry.colorIndex, spectralType = entry.spectralType,
            distanceLightYears = entry.distanceParsecs?.let { it * lightYearsPerParsec },
            isListed = entry.properName != null || entry.magnitude <= maximumListedStarMagnitude,
            notes = if (isQuasar) quasarNote else null,
            prominence = Prominence.forStar(entry.magnitude),
        )
        return ObjectDraft(obj, keys)
    }

    fun deepSkyObject(entry: DeepSkyEntry, constellations: ConstellationDirectory): ObjectDraft {
        val constellation = entry.constellation?.let { constellations.constellation(it) }
        val spellings = spellings(entry)
        val designations = spellings.map { it.display }
        val name = entry.names.firstOrNull() ?: designations[0]
        val alternateNames = entry.names.drop(1)

        val keys = ArrayList<KeyText>()
        keys.add(KeyText(name, 0))
        for ((index, spelling) in spellings.withIndex()) {
            keys.add(KeyText(spelling.display, index + 1))
            for (alias in spelling.aliases) {
                keys.add(KeyText(alias, index + 1))
            }
        }
        for ((index, alternate) in alternateNames.withIndex()) {
            keys.add(KeyText(alternate, designations.size + index + 1))
        }

        val noteSentences = ArrayList<String>()
        val hubbleType = entry.hubbleType
        if (hubbleType != null) {
            noteSentences.add("Hubble type $hubbleType.")
        }
        val brightness = entry.surfaceBrightness
        if (brightness != null) {
            noteSentences.add("Surface brightness ${CatalogFormat.oneDecimal(brightness)} mag/arcsec².")
        }

        val majorAxis = entry.majorAxis
        val prominence = entry.prominence ?: Prominence.NOTABLE
        val obj = CelestialObject(
            id = "dso.${entry.id}", name = name, designations = designations, alternateNames = alternateNames,
            category = ObjectCategory.fromOpenNGCType(entry.typeCode),
            constellation = constellation?.abbreviation ?: entry.constellation,
            constellationName = constellation?.name,
            magnitude = entry.visualMagnitude ?: entry.blueMagnitude,
            angularSize = if (majorAxis != null) {
                AngularSize.create(majorAxis, entry.minorAxis, entry.positionAngle)
            } else {
                null
            },
            position = ObjectPosition.Fixed(entry.position, null), isListed = prominence <= Prominence.lastListed,
            notes = if (noteSentences.isEmpty()) null else noteSentences.joinToString(separator = " "),
            prominence = prominence, catalogNumbers = catalogNumbers(entry),
        )
        return ObjectDraft(obj, keys)
    }

    /**
     * The designations of a deep-sky record: Messier, NGC and IC numbers, then the identifier of
     * the data file when it is from another catalog, then the designations of other catalogues the record
     * lists (`cat`: Caldwell, Sharpless, Melotte...). A designation that reads like an earlier one is left out.
     */
    fun spellings(entry: DeepSkyEntry): List<CatalogDesignation.Spelling> {
        val spellings = ArrayList<CatalogDesignation.Spelling>(2 + entry.cat.size)
        val keys = ArrayList<ByteArray>(2 + entry.cat.size)
        fun add(spelling: CatalogDesignation.Spelling) {
            val key = SearchText.key(spelling.display).bytes
            if (keys.none { it.contentEquals(key) }) {
                keys.add(key)
                spellings.add(spelling)
            }
        }
        val messier = entry.messier
        if (messier != null) {
            add(CatalogDesignation.Spelling("M $messier", listOf("Messier $messier")))
        }
        for (number in entry.ngc) {
            add(CatalogDesignation.Spelling("NGC $number", emptyList()))
        }
        for (number in entry.ic) {
            add(CatalogDesignation.Spelling("IC $number", emptyList()))
        }
        add(CatalogDesignation.spelling(entry.id))
        for (designation in entry.cat) {
            add(CatalogDesignation.spelling(designation))
        }
        return spellings
    }

    /**
     * The catalogue numbers of a deep-sky record, in the order of its designations: Messier, NGC, IC, the
     * identifier of the data file, then `cat`; repeats and identifiers of other catalogues are left out.
     */
    fun catalogNumbers(entry: DeepSkyEntry): List<CatalogNumber> {
        val numbers = ArrayList<CatalogNumber>(2 + entry.cat.size)
        fun add(number: CatalogNumber?) {
            if (number != null && !numbers.contains(number)) numbers.add(number)
        }
        val messier = entry.messier
        if (messier != null) add(CatalogNumber(DeepSkyCatalogue.MESSIER, messier))
        for (number in entry.ngc) add(CatalogNumber(DeepSkyCatalogue.NGC, number))
        for (number in entry.ic) add(CatalogNumber(DeepSkyCatalogue.IC, number))
        add(CatalogDesignation.catalogNumber(entry.id))
        for (designation in entry.cat) add(CatalogDesignation.catalogNumber(designation))
        return numbers
    }

    /** The identifier under which a deep-sky object with an acronym for a name ("LMC") also answers. */
    fun acronymAlias(alternateName: String): String? {
        // Two to five characters, upper-case ASCII letters and digits, at least one of them a letter.
        if (alternateName.length !in 2..5) return null
        var hasLetter = false
        for (character in alternateName) {
            if (character in 'A'..'Z') {
                hasLetter = true
            } else if (character !in '0'..'9') {
                return null
            }
        }
        return if (hasLetter) "dso.$alternateName" else null
    }

    /** The search key of [text] as a string (its bytes are ASCII), to tell whether two texts are found alike. */
    private fun keyIdentity(text: String): String = String(SearchText.key(text).bytes, Charsets.ISO_8859_1)
}
