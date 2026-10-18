package net.kuecker.lodestar.kit.planning

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.foundation.*

/**
 * Where to find the pole star in a polar scope, for aligning an equatorial mount or star tracker.
 *
 * The celestial pole is not marked by a star. Polaris (north) and σ Octantis (south) lie a fraction of a
 * degree from it and circle it once per sidereal day, so the pole is found by putting the star at the
 * right place on a clock face centred on the pole.
 *
 * **Clock convention.** The reading describes the polar region as an observer sees it with the naked
 * eye while looking toward the pole, with the direction of the zenith at the top (12 o'clock):
 * - At upper culmination (hour angle 0°) the star is directly above the pole: 12:00.
 * - The sky turns around the pole once per sidereal day, 15° per hour. Seen facing north it turns
 *   counter-clockwise, so in the northern hemisphere the star's clock angle is minus its hour angle:
 *   six hours after upper culmination Polaris is at 9:00, after twelve hours at 6:00, after eighteen at 3:00.
 * - Seen facing south the sky turns clockwise, so in the southern hemisphere the clock angle equals the
 *   hour angle: six hours after upper culmination σ Octantis is at 3:00.
 * - A polar scope is a small telescope and shows the field upside down. Its ([ScopeView.INVERTED]) reading is
 *   the naked-eye reading turned through 180°, six hours further round the clock.
 *
 * The star's place is its apparent place of date, so the reading includes precession, nutation,
 * aberration and proper motion.
 */
object PolarAlignment {
    /**
     * The hemisphere whose celestial pole is used.
     *
     * Serialized as its [rawValue] (`"north"`, `"south"`).
     */
    @Serializable
    enum class Hemisphere(val rawValue: String) {
        /** The north celestial pole, marked by Polaris. */
        @SerialName("north")
        NORTH("north"),

        /** The south celestial pole, marked by σ Octantis. */
        @SerialName("south")
        SOUTH("south"),
        ;

        companion object {
            /** The hemisphere whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
            fun fromRawValue(raw: String): Hemisphere? = entries.firstOrNull { it.rawValue == raw }

            /**
             * The hemisphere of a geographic [latitude] in degrees: the equator counts as north
             * (Swift `Hemisphere(latitude:)`).
             */
            fun fromLatitude(latitude: Double): Hemisphere = if (latitude < 0) SOUTH else NORTH
        }
    }

    /**
     * How the polar region is presented.
     *
     * Serialized as its [rawValue] (`"naked"`, `"inverted"`).
     */
    @Serializable
    enum class ScopeView(val rawValue: String) {
        /** As seen by the naked eye looking toward the pole, zenith at the top. */
        @SerialName("naked")
        NAKED("naked"),

        /** As seen through a polar scope without an erecting prism: turned through 180°. */
        @SerialName("inverted")
        INVERTED("inverted"),
        ;

        companion object {
            /** The view whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
            fun fromRawValue(raw: String): ScopeView? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    /**
     * A pole star with its Hipparcos astrometry.
     *
     * @property position ICRS position at J2000.0.
     */
    internal data class ReferenceStar(
        val name: String,
        val hipparcosNumber: Int,
        val position: EquatorialCoordinate,
        val properMotion: ProperMotion,
    )

    /**
     * Polaris, α Ursae Minoris, HIP 11767 (Hipparcos Catalogue, ESA 1997: ICRS epoch J1991.25 position
     * carried to J2000.0 with its proper motion; μα* = +44.22, μδ = −11.74 mas/yr).
     */
    internal val polaris: ReferenceStar = ReferenceStar(
        name = "Polaris",
        hipparcosNumber = 11767,
        position = EquatorialCoordinate(rightAscension = 37.95451535, declination = 89.26410951),
        properMotion = ProperMotion(raMilliarcsecondsPerYear = 44.22, decMilliarcsecondsPerYear = -11.74),
    )

    /**
     * σ Octantis, HIP 104382 (Hipparcos Catalogue, ESA 1997: ICRS epoch J1991.25 position carried to
     * J2000.0 with its proper motion; μα* = +25.96, μδ = +5.02 mas/yr).
     */
    internal val sigmaOctantis: ReferenceStar = ReferenceStar(
        name = "σ Octantis",
        hipparcosNumber = 104382,
        position = EquatorialCoordinate(rightAscension = 317.19519016, declination = -88.95649900),
        properMotion = ProperMotion(raMilliarcsecondsPerYear = 25.96, decMilliarcsecondsPerYear = 5.02),
    )

    /**
     * Where the pole star of [hemisphere] stands relative to the pole at the instant of [frame].
     *
     * @param frame The instant and observer.
     * @param hemisphere Which pole to align on.
     * @param view Whether the reading is for the naked eye or an inverting polar scope.
     */
    fun polarScopeReading(
        frame: SkyFrame,
        hemisphere: Hemisphere,
        view: ScopeView = ScopeView.NAKED,
    ): PolarScopeReading {
        val north = hemisphere == Hemisphere.NORTH
        val star = if (north) polaris else sigmaOctantis
        val apparent = frame.apparentPlace(star.position, star.properMotion)
        val hourAngle = frame.hourAngle(apparent.rightAscension)
        val pole = frame.horizontalOfCelestialPole(north, false)
        val nakedEyeAngle = if (north) -hourAngle else hourAngle
        return PolarScopeReading(
            referenceStarName = star.name,
            hipparcosNumber = star.hipparcosNumber,
            hemisphere = hemisphere,
            view = view,
            hourAngle = hourAngle,
            separationFromPole = apparent.unitVector.angle(Vector3(0.0, 0.0, if (north) 1.0 else -1.0)),
            angle = AstroMath.normalizeDegrees(if (view == ScopeView.INVERTED) nakedEyeAngle + 180 else nakedEyeAngle),
            poleAltitude = pole.altitude,
            poleAzimuth = pole.azimuth,
        )
    }

    /** The reading for the hemisphere of the frame's observer (north on or above the equator). */
    fun polarScopeReading(frame: SkyFrame, view: ScopeView = ScopeView.NAKED): PolarScopeReading =
        polarScopeReading(frame, Hemisphere.fromLatitude(frame.observer.location.latitude), view)
}

/**
 * The place of the pole star on a polar-scope clock face.
 *
 * See [PolarAlignment] for the clock convention.
 *
 * @property referenceStarName "Polaris" or "σ Octantis".
 * @property hipparcosNumber The star's Hipparcos catalogue number: 11767 or 104382.
 * @property hemisphere The pole the reading refers to.
 * @property view Whether [angle] is for the naked eye or for an inverting polar scope.
 * @property hourAngle The star's local hour angle in degrees, (−180, 180]: positive once it has passed
 * upper culmination.
 * @property separationFromPole The angular distance of the star from the celestial pole of date in degrees,
 * about 0.63° for Polaris and 1.15° for σ Octantis in 2026 (the pole is drawing closer to Polaris and away
 * from σ Octantis).
 * @property angle The star's direction from the pole in degrees, [0, 360), clockwise from 12 o'clock as drawn.
 * @property poleAltitude The altitude of the celestial pole of date in degrees: the latitude to set on the
 * mount's scale. It is geometric; refraction raises the apparent pole by about a minute of arc at
 * mid-latitudes.
 * @property poleAzimuth The azimuth of the celestial pole in degrees: 0° (north) for the north pole, 180°
 * for the south pole.
 */
data class PolarScopeReading(
    val referenceStarName: String,
    val hipparcosNumber: Int,
    val hemisphere: PolarAlignment.Hemisphere,
    val view: PolarAlignment.ScopeView,
    val hourAngle: Double,
    val separationFromPole: Double,
    val angle: Double,
    val poleAltitude: Double,
    val poleAzimuth: Double,
) {
    /** The clock hour of [angle], [0, 12): 0 is 12 o'clock and 10.5 is 10:30. */
    val clockPosition: Double get() = angle / 30
}
