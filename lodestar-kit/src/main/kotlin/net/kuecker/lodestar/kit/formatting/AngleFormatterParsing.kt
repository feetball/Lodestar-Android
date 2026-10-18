package net.kuecker.lodestar.kit.formatting

/**
 * Parses a right ascension and returns it in hours, in [0, 24).
 *
 * Accepted forms:
 * - hours, minutes and seconds: `5h35m17.3s`, `5h 35.5m`, `05:35:17`, `5 35 17.3`
 * - decimal hours: `5.588`, `5,588`
 * - degrees, only when explicitly marked with `°` or `d`, converted by dividing by 15:
 *   `83.8°`, `83.8d`, `83° 49′ 17″`
 *
 * A bare number is always hours, so `83.8` is rejected rather than guessed to be degrees.
 * Negative values, hemisphere letters and 24 h or more are rejected. The shared parsing rules
 * are described on [AngleFormatter].
 */
fun AngleFormatter.parseRightAscension(text: String): Double? =
    AngleTextParser.parse(text, AngleTextParser.Quantity.RIGHT_ASCENSION)

/**
 * Parses a declination and returns it in degrees, in [-90, 90].
 *
 * Accepted forms include `−05° 23′ 28″`, `-5d 23m 28s`, `+41:16:09`, `5.391`, `5 23 28 S` and
 * `S 5.391`. A trailing `S` or `N` is the hemisphere; in the letter style `d m s` an `s`
 * attached to the seconds (`40d30m15s`) is the seconds unit, so write a southern hemisphere
 * there as `40d30m15s S`; an attached `s` after any other style (`40 30 15s`) is ambiguous
 * and rejected. The shared parsing rules are described on [AngleFormatter].
 */
fun AngleFormatter.parseDeclination(text: String): Double? =
    AngleTextParser.parse(text, AngleTextParser.Quantity.DECLINATION)

/**
 * Parses a latitude and returns it in degrees, in [-90, 90].
 *
 * Accepts the same forms as [parseDeclination], for example `40.015 N`, `N 40° 0′ 54″`,
 * `-33.8688` and `33°52′08″S`.
 */
fun AngleFormatter.parseLatitude(text: String): Double? =
    AngleTextParser.parse(text, AngleTextParser.Quantity.DECLINATION)

/**
 * Parses a longitude and returns it in degrees east, in (-180, 180].
 *
 * Accepted forms include `-105.2705`, `105.2705 W`, `W 105° 16′ 14″`, `105d16m14s W`. Values
 * from 180° up to 360° are east longitudes and are converted, so `254.7295` is -105.2705; a
 * negative or western value below -180° is rejected. The hemisphere letters are `E` and `W`.
 */
fun AngleFormatter.parseLongitude(text: String): Double? =
    AngleTextParser.parse(text, AngleTextParser.Quantity.LONGITUDE)
