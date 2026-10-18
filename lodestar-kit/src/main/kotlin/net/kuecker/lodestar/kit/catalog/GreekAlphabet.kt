package net.kuecker.lodestar.kit.catalog

import java.util.Locale

/** The Greek letters used in Bayer designations, with the spellings people type. */
internal object GreekAlphabet {
    /**
     * One Greek letter.
     *
     * @property name The English name in lower case ("alpha").
     * @property abbreviations The three-letter forms used by catalogs and SIMBAD ("alp", "alf"), other
     * than the full name.
     */
    data class Letter(val name: String, val abbreviations: List<String>) {
        /** The name with a capital initial ("Alpha"). */
        val capitalizedName: String get() = name.substring(0, 1).uppercase(Locale.ROOT) + name.substring(1)

        /** The name followed by the abbreviations: every spelling of the letter that search keys are made for. */
        val forms: List<String> = listOf(name) + abbreviations
    }

    /** The 24 letters from alpha to omega. */
    val letters: List<Letter> = listOf(
        Letter("alpha", listOf("alp", "alf")),
        Letter("beta", listOf("bet")),
        Letter("gamma", listOf("gam")),
        Letter("delta", listOf("del")),
        Letter("epsilon", listOf("eps")),
        Letter("zeta", listOf("zet")),
        Letter("eta", emptyList()),
        Letter("theta", listOf("the", "tet")),
        Letter("iota", listOf("iot")),
        Letter("kappa", listOf("kap")),
        Letter("lambda", listOf("lam")),
        Letter("mu", emptyList()),
        Letter("nu", emptyList()),
        Letter("xi", listOf("ksi")),
        Letter("omicron", listOf("omi")),
        Letter("pi", emptyList()),
        Letter("rho", emptyList()),
        Letter("sigma", listOf("sig")),
        Letter("tau", emptyList()),
        Letter("upsilon", listOf("ups")),
        Letter("phi", emptyList()),
        Letter("chi", listOf("khi")),
        Letter("psi", emptyList()),
        Letter("omega", listOf("ome")),
    )

    /** The names of [letters] as lower-case ASCII bytes, for the text normalizer. */
    val nameBytes: List<ByteArray> = letters.map { it.name.toByteArray(Charsets.US_ASCII) }

    /**
     * The position in [letters] of the Greek letter written as [scalar] (a Unicode code point), in lower
     * or upper case (including final sigma and the symbol variants of theta, kappa, phi, pi, rho and
     * epsilon), or `null` if it is not one.
     */
    fun index(scalar: Int): Int? {
        val offset = when (scalar) {
            in 0x0391..0x03A1, in 0x03A3..0x03A9 -> scalar - 0x0391
            in 0x03B1..0x03C9 -> scalar - 0x03B1
            0x03D1, 0x03F4 -> return 7
            0x03D5 -> return 20
            0x03D6 -> return 15
            0x03F0 -> return 9
            0x03F1 -> return 16
            0x03F5 -> return 4
            else -> return null
        }
        // Between rho and sigma Unicode has a slot for the final sigma (lower case; upper case has
        // none). It counts as sigma, and the letters after sigma sit one place past their alphabet
        // position.
        return if (offset <= 17) offset else offset - 1
    }

    /** The letter written as [scalar] (a Unicode code point), if it is a Greek letter. */
    fun letter(scalar: Int): Letter? = index(scalar)?.let { letters[it] }
}
