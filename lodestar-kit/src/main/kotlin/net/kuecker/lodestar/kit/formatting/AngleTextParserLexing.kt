package net.kuecker.lodestar.kit.formatting

import net.kuecker.lodestar.kit.formatting.AngleTextParser.Mark
import net.kuecker.lodestar.kit.formatting.AngleTextParser.Quantity
import net.kuecker.lodestar.kit.formatting.AngleTextParser.Token
import net.kuecker.lodestar.kit.formatting.AngleTextParser.UnitMark

// Lexing: turns the scalars of an input string into `AngleTextParser.Token`s.

/** Splits scalars into tokens, or returns null when any scalar is not part of the grammar. */
internal fun AngleTextParser.tokenize(scalars: IntArray, quantity: Quantity): List<Token>? {
    val tokens = ArrayList<Token>()
    var index = 0
    var followsSpace = false

    while (index < scalars.size) {
        val scalar = scalars[index]
        if (isWhitespace(scalar)) {
            followsSpace = true
            index += 1
            continue
        }

        val kind: Token.Kind
        if (isDigit(scalar) || scalar == '.'.code || scalar == ','.code) {
            val parsed = number(scalars, index) ?: return null
            kind = parsed.kind
            index = parsed.next
        } else {
            kind = symbol(scalar, quantity) ?: return null
            index += 1
        }
        tokens.add(Token(kind, followsSpace))
        followsSpace = false
    }
    return mergingDoubledPrimes(tokens)
}

/**
 * The Unicode `White_Space` property, which is what Swift's `Unicode.Scalar.Properties.isWhitespace`
 * reports. Spelled out so the result does not depend on the Unicode tables of the Java runtime
 * (`Character.isWhitespace` excludes the no-break spaces).
 */
private fun isWhitespace(scalar: Int): Boolean = when (scalar) {
    in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
    else -> false
}

private fun isDigit(scalar: Int): Boolean = scalar >= 0x30 && scalar <= 0x39

private fun isDecimalMark(scalar: Int): Boolean = scalar == '.'.code || scalar == ','.code

/** A number token and the index of the first scalar after it. */
private class ScannedNumber(val kind: Token.Kind, val next: Int)

/**
 * Reads an unsigned number starting at [start]. Both "." and "," are accepted as the decimal
 * mark, but a comma only counts when a digit follows it so that "40, 30" is not read as 40.30.
 */
private fun number(scalars: IntArray, start: Int): ScannedNumber? {
    var index = start
    val integerDigits = StringBuilder()
    val fractionDigits = StringBuilder()
    var hasDecimalMark = false

    while (index < scalars.size && isDigit(scalars[index])) {
        integerDigits.append(scalars[index].toChar())
        index += 1
    }
    if (index < scalars.size && isDecimalMark(scalars[index])) {
        val followedByDigit = index + 1 < scalars.size && isDigit(scalars[index + 1])
        if (scalars[index] == '.'.code || followedByDigit) {
            hasDecimalMark = true
            index += 1
            while (index < scalars.size && isDigit(scalars[index])) {
                fractionDigits.append(scalars[index].toChar())
                index += 1
            }
        }
    }
    if (integerDigits.isEmpty() && fractionDigits.isEmpty()) return null
    // A second decimal mark ("1.2.3", "1,5,5", or a comma after "40") makes the number ambiguous.
    if (index < scalars.size && isDecimalMark(scalars[index])) return null

    // The literal is digits, a point and digits, so it always parses.
    val literal = (if (integerDigits.isEmpty()) "0" else integerDigits.toString()) + "." +
        (if (fractionDigits.isEmpty()) "0" else fractionDigits.toString())
    val value = literal.toDouble()
    if (!value.isFinite()) return null
    return ScannedNumber(Token.Kind.Number(value, !hasDecimalMark), index)
}

/** Classifies a single non-digit, non-space scalar. */
private fun symbol(scalar: Int, quantity: Quantity): Token.Kind? = when (scalar) {
    0x2B -> Token.Kind.Sign(isNegative = false)

    // Hyphen-minus, hyphens, dashes, minus sign, small and fullwidth hyphen-minus.
    0x2D, in 0x2010..0x2013, 0x2212, 0xFE63, 0xFF0D -> Token.Kind.Sign(isNegative = true)

    0x3A -> Token.Kind.Colon

    // Degree sign, masculine ordinal indicator (often typed by mistake) and ring above.
    0xB0, 0xBA, 0x2DA -> Token.Kind.Mark(UnitMark(Mark.DEGREE, isLetter = false))

    // Apostrophe, curly single quotes (smart punctuation), acute accent, modifier prime, prime.
    0x27, 0x2018, 0x2019, 0xB4, 0x2B9, 0x2032 -> Token.Kind.Mark(UnitMark(Mark.MINUTE, isLetter = false))

    // Quotation mark, curly double quotes, modifier double prime, double prime.
    0x22, 0x201C, 0x201D, 0x2BA, 0x2033 -> Token.Kind.Mark(UnitMark(Mark.SECOND, isLetter = false))

    0x48, 0x68 -> Token.Kind.Mark(UnitMark(Mark.HOUR, isLetter = true))

    0x44, 0x64 -> Token.Kind.Mark(UnitMark(Mark.DEGREE, isLetter = true))

    0x4D, 0x6D -> Token.Kind.Mark(UnitMark(Mark.MINUTE, isLetter = true))

    0x53, 0x73 ->
        if (quantity == Quantity.DECLINATION) {
            Token.Kind.SouthOrSeconds
        } else {
            Token.Kind.Mark(UnitMark(Mark.SECOND, isLetter = true))
        }

    0x4E, 0x6E -> if (quantity == Quantity.DECLINATION) Token.Kind.Hemisphere(isNegative = false) else null

    0x45, 0x65 -> if (quantity == Quantity.LONGITUDE) Token.Kind.Hemisphere(isNegative = false) else null

    0x57, 0x77 -> if (quantity == Quantity.LONGITUDE) Token.Kind.Hemisphere(isNegative = true) else null

    else -> null
}

/** Two adjacent minute symbols ("''" or "′′") are a seconds symbol. */
private fun mergingDoubledPrimes(tokens: List<Token>): List<Token> {
    val minuteSymbol = Token.Kind.Mark(UnitMark(Mark.MINUTE, isLetter = false))
    val secondSymbol = Token.Kind.Mark(UnitMark(Mark.SECOND, isLetter = false))
    val merged = ArrayList<Token>(tokens.size)
    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        if (index + 1 < tokens.size &&
            token.kind == minuteSymbol &&
            tokens[index + 1].kind == minuteSymbol &&
            !tokens[index + 1].followsSpace
        ) {
            merged.add(Token(secondSymbol, token.followsSpace))
            index += 2
        } else {
            merged.add(token)
            index += 1
        }
    }
    return merged
}
