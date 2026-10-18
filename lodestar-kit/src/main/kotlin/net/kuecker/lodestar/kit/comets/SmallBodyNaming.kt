package net.kuecker.lodestar.kit.comets

/** The pieces of a minor planet's name: its number, its designation (number or provisional designation) and its name. */
internal data class AsteroidIdentity(val number: Int?, val designation: String, val name: String)

/** Splitting of the titles the catalogs print into designation and name. */
internal object SmallBodyNaming {
    /**
     * Splits a comet title into its designation and its name: `"C/2023 A3 (Tsuchinshan-ATLAS)"` into `C/2023 A3` and
     * `Tsuchinshan-ATLAS`, `"12P/Pons-Brooks"` into `12P` and `Pons-Brooks`, `"P/2026 N2"` into itself and an empty name.
     * [title] must be trimmed.
     */
    fun cometParts(title: String): Pair<String, String> {
        val slash = title.indexOf('/')
        if (slash > 0 && isNumberedCometPrefix(title.substring(0, slash))) {
            return Pair(title.substring(0, slash), title.substring(slash + 1).trim(' ', '\t'))
        }
        if (title.endsWith(")")) {
            val open = title.indexOf(" (")
            if (open > 0) {
                val designation = title.substring(0, open).trim(' ', '\t')
                val name = title.substring(open + 2, title.length - 1).trim(' ', '\t')
                if (designation.isNotEmpty()) return Pair(designation, name)
            }
        }
        return Pair(title, "")
    }

    /** The comet number of a numbered designation such as `12P` or `73P-B` (the leading digits), or `null`. */
    fun cometNumber(designation: String): Int? {
        var end = 0
        while (end < designation.length && designation[end] in '0'..'9') end++
        if (end == 0 || end > 4 || end >= designation.length) return null
        if (designation[end] !in "PCDXI") return null
        if (end + 1 < designation.length && designation[end + 1] != '-') return null
        val number = designation.substring(0, end).toInt()
        return if (number >= 1) number else null
    }

    /** Digits, then one of `PCDXI`, then optionally `-` and one or two capital letters. */
    private fun isNumberedCometPrefix(prefix: String): Boolean {
        var index = 0
        while (index < prefix.length && prefix[index] in '0'..'9') index++
        if (index == 0 || index >= prefix.length) return false
        if (prefix[index] !in "PCDXI") return false
        index++
        if (index == prefix.length) return true
        if (prefix[index] != '-') return false
        val letters = prefix.length - index - 1
        if (letters < 1 || letters > 2) return false
        for (position in index + 1 until prefix.length) {
            if (prefix[position] !in 'A'..'Z') return false
        }
        return true
    }

    /**
     * Reads the readable designation of an MPCORB line: `"(4) Vesta"` is number 4, name `Vesta`; `"(44594) 1999 OX3"`
     * is number 44594, name `1999 OX3`; `"2024 YR4"` is an unnumbered asteroid. [text] must be trimmed and not empty.
     */
    fun asteroidReadable(text: String): AsteroidIdentity {
        if (text.startsWith("(")) {
            val close = text.indexOf(')')
            if (close > 1) {
                val inner = text.substring(1, close)
                val rest = text.substring(close + 1).trim(' ', '\t')
                val number = digitsToNumber(inner)
                if (number != null) return AsteroidIdentity(number, number.toString(), rest)
            }
        }
        return AsteroidIdentity(null, text, "")
    }

    /**
     * Reads the full name of the JPL Small-Body Database: `"4 Vesta (A807 FA)"` is number 4, name `Vesta`;
     * `"44594 (1999 OX3)"` is number 44594, name `1999 OX3`; `"(2024 YR4)"` is an unnumbered asteroid.
     * [text] must be trimmed and not empty.
     */
    fun asteroidFullName(text: String): AsteroidIdentity {
        if (text.startsWith("(") && text.endsWith(")") && text.length > 2) {
            val inner = text.substring(1, text.length - 1)
            if (inner.indexOf('(') < 0 && inner.indexOf(')') < 0) return AsteroidIdentity(null, inner.trim(' ', '\t'), "")
        }
        if (isProvisionalDesignation(text)) return AsteroidIdentity(null, text, "")
        var end = 0
        while (end < text.length && text[end] in '0'..'9') end++
        if (end in 1..8 && (end == text.length || text[end] == ' ')) {
            val number = digitsToNumber(text.substring(0, end))
            if (number != null) {
                val rest = text.substring(end).trim(' ', '\t')
                return AsteroidIdentity(number, number.toString(), nameWithoutProvisional(rest))
            }
        }
        return AsteroidIdentity(null, text, "")
    }

    /** Four digits, a space, two capital letters and optional digits: `2024 YR4`, which is not a number followed by a name. */
    private fun isProvisionalDesignation(text: String): Boolean {
        if (text.length < 7) return false
        for (index in 0 until 4) {
            if (text[index] !in '0'..'9') return false
        }
        if (text[4] != ' ' || text[5] !in 'A'..'Z' || text[6] !in 'A'..'Z') return false
        for (index in 7 until text.length) {
            if (text[index] !in '0'..'9') return false
        }
        return true
    }

    /** `"Vesta (A807 FA)"` becomes `Vesta`; `"(1999 OX3)"` becomes `1999 OX3`; anything else is unchanged. */
    private fun nameWithoutProvisional(rest: String): String {
        if (!rest.endsWith(")")) return rest
        if (rest.startsWith("(")) {
            val inner = rest.substring(1, rest.length - 1)
            return if (inner.indexOf('(') < 0 && inner.indexOf(')') < 0) inner.trim(' ', '\t') else rest
        }
        val open = rest.lastIndexOf(" (")
        return if (open > 0) rest.substring(0, open).trim(' ', '\t') else rest
    }

    private fun digitsToNumber(text: String): Int? {
        if (text.isEmpty() || text.length > 8) return null
        for (character in text) {
            if (character !in '0'..'9') return null
        }
        val number = text.toInt()
        return if (number >= 1) number else null
    }
}
