package net.kuecker.lodestar.kit.formatting

/**
 * The typography used when formatting angles, coordinates, durations and exposure times.
 *
 * Every formatter in [AngleFormatter] takes a style and defaults to [UNICODE]. Text produced in
 * the [ASCII] style contains only 7-bit characters and is still understood by the parsers in
 * [AngleFormatter].
 */
enum class TypographyStyle {
    /** Typographic symbols: U+2212 minus sign, `°`, `′`, `″` and an em dash for missing values. */
    UNICODE,

    /**
     * Plain ASCII for logs, file names and exports: hyphen-minus, `d`, `'`, `"` and `--` for
     * missing values.
     */
    ASCII;

    /** The sign written in front of negative values. */
    internal val minus: String
        get() = when (this) {
            UNICODE -> "\u2212"
            ASCII -> "-"
        }

    /** The degree symbol. */
    internal val degree: String
        get() = when (this) {
            UNICODE -> "\u00B0"
            ASCII -> "d"
        }

    /** The arcminute symbol. */
    internal val arcminute: String
        get() = when (this) {
            UNICODE -> "\u2032"
            ASCII -> "'"
        }

    /** The arcsecond symbol. */
    internal val arcsecond: String
        get() = when (this) {
            UNICODE -> "\u2033"
            ASCII -> "\""
        }

    /** Text shown in place of a value that is missing, not finite or out of range. */
    internal val placeholder: String
        get() = when (this) {
            UNICODE -> "\u2014"
            ASCII -> "--"
        }
}
