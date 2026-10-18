package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.Aberration
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.ProperMotion
import net.kuecker.lodestar.kit.foundation.SkyFrame

/**
 * The equatorial reference frame in which a mount takes and reports coordinates.
 *
 * The engine's places are J2000. Most amateur mounts expect "JNow"; what that means exactly is not standardised, so
 * two variants are offered. They differ by nutation and annual aberration, at most about 0.01° (40″), far below the
 * pointing accuracy of any GoTo mount.
 */
enum class MountEpoch(
    /** The stable string used for storage. */
    val rawValue: String,
) {
    /** Mean equator and equinox of J2000.0: the catalog place, with proper motion applied to the date. */
    J2000("j2000"),

    /**
     * Mean equator and equinox of date ("JNow" as planetarium programs usually send it): the J2000 place precessed to
     * the date. No nutation, no aberration.
     */
    MEAN_OF_DATE("meanOfDate"),

    /**
     * True equator and equinox of date, geocentric (the "local topocentric" coordinates of ASCOM): precession,
     * nutation and annual aberration applied, as [SkyFrame.apparentPlace] computes it.
     */
    APPARENT_OF_DATE("apparentOfDate");

    /**
     * The place a mount using this frame calls the J2000 place [j2000], at the instant of [frame].
     * Proper motion, if given, moves the place from J2000 to the date first.
     */
    fun fromJ2000(j2000: EquatorialCoordinate, frame: SkyFrame, properMotion: ProperMotion? = null): EquatorialCoordinate =
        when (this) {
            J2000 -> properMotion?.apply(j2000, frame.yearsSinceJ2000) ?: j2000
            MEAN_OF_DATE -> {
                val moved = properMotion?.applyToUnitVector(j2000, frame.yearsSinceJ2000) ?: j2000.unitVector
                EquatorialCoordinate(frame.precessionMatrix * moved)
            }
            APPARENT_OF_DATE -> frame.apparentPlace(j2000, properMotion)
        }

    /** The J2000 place of the position [mount] in this frame, at the instant of [frame] (the inverse of [fromJ2000]). */
    fun toJ2000(mount: EquatorialCoordinate, frame: SkyFrame): EquatorialCoordinate =
        when (this) {
            J2000 -> mount
            MEAN_OF_DATE -> EquatorialCoordinate(frame.precessionMatrix.transposed * mount.unitVector)
            APPARENT_OF_DATE -> EquatorialCoordinate(
                Aberration.remove(frame.precessionNutationMatrix.transposed * mount.unitVector, frame.earthVelocity),
            )
        }

    companion object {
        /** The frame with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): MountEpoch? = entries.firstOrNull { it.rawValue == raw }
    }
}
